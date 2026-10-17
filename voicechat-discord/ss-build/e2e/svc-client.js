// Minimal headless Simple Voice Chat client for end-to-end tests: joins a Minecraft server with mineflayer, does
// SVC's plugin-message handshake, then talks SVC's encrypted UDP protocol. It can send a tone as microphone audio
// and records the audio channels it receives. Protocol: henkelmax/simple-voice-chat, compatibility version 20
// (SVC 2.6.x): NetworkMessage, Secret (AES/GCM), AuthenticatePacket, ConnectionCheckPacket, MicPacket,
// GroupSoundPacket, KeepAlivePacket, SecretPacket, RequestSecretPacket, UpdateStatePacket.
const dgram = require('dgram');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const mineflayer = require('mineflayer');
const OpusScript = require('opusscript');

const COMPATIBILITY_VERSION = 20;
const SAMPLE_RATE = 48000;
const FRAME = 960; // 20 ms
const MAGIC = 0xff;
const T = { MIC: 1, PLAYER_SOUND: 2, GROUP_SOUND: 3, LOCATION_SOUND: 4, AUTH: 5, AUTH_ACK: 6, PING: 7, KEEP_ALIVE: 8, CHECK: 9, CHECK_ACK: 10 };
// Channels the server sends us; Bukkit only sends plugin messages on channels the client registered.
const CLIENT_CHANNELS = ['secret', 'player_state', 'player_states', 'remove_player_state', 'add_group', 'remove_group',
  'joined_group', 'add_category', 'remove_category'].map(c => 'voicechat:' + c);

// --- FriendlyByteBuf encoding ---
const varint = (n) => { const out = []; do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b); } while (n); return Buffer.from(out); };
const uuidBytes = (u) => Buffer.from(u.replace(/-/g, ''), 'hex');
const uuidString = (b) => { const h = b.toString('hex'); return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`; };
const byteArray = (b) => Buffer.concat([varint(b.length), b]);
const long = (n) => { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(n)); return b; };
class Reader {
  constructor(buf) { this.b = buf; this.o = 0; }
  byte() { return this.b.readUInt8(this.o++); }
  bool() { return this.byte() !== 0; }
  int() { const v = this.b.readInt32BE(this.o); this.o += 4; return v; }
  long() { const v = this.b.readBigInt64BE(this.o); this.o += 8; return v; }
  double() { const v = this.b.readDoubleBE(this.o); this.o += 8; return v; }
  varint() { let n = 0, s = 0, b; do { b = this.byte(); n |= (b & 0x7f) << s; s += 7; } while (b & 0x80); return n; }
  bytes(n) { const v = this.b.subarray(this.o, this.o + n); this.o += n; return v; }
  byteArray() { return this.bytes(this.varint()); }
  uuid() { return uuidString(this.bytes(16)); }
  utf() { return this.byteArray().toString('utf8'); }
}

// --- Secret: AES/GCM/NoPadding, 12-byte IV prepended, 128-bit tag appended ---
function encrypt(key, data) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-128-gcm', key, iv);
  return Buffer.concat([iv, c.update(data), c.final(), c.getAuthTag()]);
}
function decrypt(key, payload) {
  const iv = payload.subarray(0, 12), tag = payload.subarray(payload.length - 16), ct = payload.subarray(12, payload.length - 16);
  const d = crypto.createDecipheriv('aes-128-gcm', key, iv);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(ct), d.final()]);
}

const flat = (c) => { if (c == null) return ''; if (typeof c === 'string') return c; if (Array.isArray(c)) return c.map(flat).join('');
  if (c.type === 'compound' || c.type === 'list' || c.type === 'string') return flat(c.value);
  let s = flat(c.text ?? c[''] ?? ''); if (c.extra) s += flat(c.extra); return s; };

/** Connects and resolves once SVC's UDP connection is validated. */
function connect({ host, port, username, version = '1.21.11', log = () => {} }) {
  const ev = new EventEmitter();
  ev.messages = [];
  ev.audio = new Map(); // channelId -> { category, sender, packets, pcm: Int16Array[] }
  const decoders = new Map();
  let secret = null, svcPort = 0, playerUuid = null, udp = null, connected = false;

  const bot = mineflayer.createBot({ host, port, username, version, auth: 'offline', physicsEnabled: false });
  ev.bot = bot;
  bot.physicsEnabled = false;
  { const w = bot._client.write.bind(bot._client); // Via kicks for "invalid move" otherwise
    bot._client.write = (name, data) => { if (/^(position|position_look|look|flying|player_input|move_player)/.test(name)) return; return w(name, data); }; }

  const sendPayload = (channel, data) => bot._client.write('custom_payload', { channel, data });

  const sendUdp = (type, body) => {
    const payload = encrypt(secret, Buffer.concat([Buffer.from([type]), body]));
    udp.send(Buffer.concat([Buffer.from([MAGIC]), uuidBytes(playerUuid), byteArray(payload)]), svcPort, host);
  };

  const onUdp = (msg) => {
    const r = new Reader(msg);
    if (r.byte() !== MAGIC) return;
    let p;
    try { p = new Reader(decrypt(secret, r.byteArray())); } catch (e) { log('bad udp packet ' + e.message); return; }
    const type = p.byte();
    if (type === T.AUTH_ACK) { log('authenticated'); sendUdp(T.CHECK, Buffer.alloc(0)); }
    else if (type === T.CHECK_ACK) {
      if (!connected) {
        connected = true;
        log('voice connection validated');
        sendPayload('voicechat:update_state', Buffer.from([0])); // not disabled
        ev.emit('voice');
      }
    } else if (type === T.KEEP_ALIVE) sendUdp(T.KEEP_ALIVE, Buffer.alloc(0));
    else if (type === T.PING) { /* ignore */ }
    else if (type === T.GROUP_SOUND || type === T.PLAYER_SOUND || type === T.LOCATION_SOUND) {
      const channelId = p.uuid(), sender = p.uuid();
      const data = p.byteArray();
      p.long(); // sequence number
      let category = null;
      if (type === T.GROUP_SOUND) { const flags = p.byte(); if (flags & 0b10) category = p.utf(); }
      if (!ev.audio.has(channelId)) ev.audio.set(channelId, { type, category, sender, packets: 0, flushes: 0, pcm: [] });
      const ch = ev.audio.get(channelId);
      if (data.length === 0) { ch.flushes++; return; }
      if (!decoders.has(channelId)) decoders.set(channelId, new OpusScript(SAMPLE_RATE, 1, OpusScript.Application.VOIP));
      const pcm = decoders.get(channelId).decode(data);
      ch.packets++;
      ch.pcm.push(new Int16Array(pcm.buffer, pcm.byteOffset, pcm.length / 2).slice());
      ev.emit('audio', channelId, ch);
    }
  };

  ev.categories = [];
  bot._client.on('custom_payload', (packet) => {
    if (packet.channel === 'voicechat:add_category') {
      const r = new Reader(packet.data);
      ev.categories.push(r.utf()); // AddCategoryPacket: VolumeCategoryImpl starts with its id
      return;
    }
    if (packet.channel !== 'voicechat:secret') return;
    const r = new Reader(packet.data);
    secret = Buffer.from(r.bytes(16));
    svcPort = r.int();
    playerUuid = r.uuid();
    const codec = r.byte(), mtu = r.int(), distance = r.double(), keepAlive = r.int(), groups = r.bool(), voiceHost = r.utf();
    log(`got secret: port ${svcPort}, uuid ${playerUuid}, codec ${codec}, distance ${distance}, voiceHost '${voiceHost}'`);
    udp = dgram.createSocket('udp4');
    udp.on('message', onUdp);
    const auth = () => { if (!connected) sendUdp(T.AUTH, Buffer.concat([uuidBytes(playerUuid), secret])); };
    auth();
    const retry = setInterval(() => (connected ? clearInterval(retry) : auth()), 1000);
  });
  bot._client.on('system_chat', (d) => { const m = flat(d.content); if (m.trim()) { ev.messages.push(m); log('CHAT ' + m); ev.emit('chat', m); } });
  bot._client.on('error', (e) => log('CLIENT_ERROR ' + e.message));
  bot.on('kicked', (r) => log('KICKED ' + JSON.stringify(r)));

  bot.once('spawn', () => {
    sendPayload('minecraft:register', Buffer.from(CLIENT_CHANNELS.join('\0')));
    const b = Buffer.alloc(4); b.writeInt32BE(COMPATIBILITY_VERSION);
    sendPayload('voicechat:request_secret', b);
    log('spawned, requested secret');
  });

  ev.chat = (text) => bot.chat(text);
  ev.waitFor = (re, ms = 15000) => new Promise((resolve, reject) => {
    const found = ev.messages.find(m => re.test(m)); if (found) return resolve(found);
    const t = setTimeout(() => { ev.off('chat', on); reject(new Error(`timed out waiting for chat ${re}`)); }, ms);
    const on = (m) => { if (re.test(m)) { clearTimeout(t); ev.off('chat', on); resolve(m); } };
    ev.on('chat', on);
  });
  /** Speaks a sine tone into the microphone for `ms`, one 20 ms Opus packet at a time. */
  ev.speak = (freq, ms, amplitude = 8000) => new Promise((resolve) => {
    const enc = new OpusScript(SAMPLE_RATE, 1, OpusScript.Application.VOIP);
    let frame = 0, seq = ev._seq || 0;
    const frames = Math.round(ms / 20);
    const iv = setInterval(() => {
      const pcm = Buffer.alloc(FRAME * 2);
      for (let i = 0; i < FRAME; i++) pcm.writeInt16LE(Math.round(amplitude * Math.sin(2 * Math.PI * freq * (frame * FRAME + i) / SAMPLE_RATE)), i * 2);
      const opus = Buffer.from(enc.encode(pcm, FRAME));
      sendUdp(T.MIC, Buffer.concat([byteArray(opus), long(seq++), Buffer.from([0])]));
      if (++frame >= frames) { clearInterval(iv); ev._seq = seq; enc.delete(); resolve(); }
    }, 20);
  });
  ev.quit = () => { try { bot.quit(); } catch (e) {} if (udp) udp.close(); };

  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('voice chat never connected')), 30000);
    ev.once('voice', () => { clearTimeout(t); resolve(ev); });
    bot.once('end', (r) => { clearTimeout(t); reject(new Error('disconnected: ' + r)); });
  });
}

/** Strength of one frequency in some samples (Goertzel), normalised by length. */
function powerAt(samples, freq) {
  const w = 2 * Math.PI * freq / SAMPLE_RATE, coeff = 2 * Math.cos(w);
  let s1 = 0, s2 = 0;
  for (const x of samples) { const s0 = x + coeff * s1 - s2; s2 = s1; s1 = s0; }
  return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (samples.length * samples.length);
}
const concat = (frames) => { const n = frames.reduce((a, f) => a + f.length, 0); const out = new Int16Array(n); let o = 0; for (const f of frames) { out.set(f, o); o += f.length; } return out; };

module.exports = { connect, powerAt, concat, SAMPLE_RATE, FRAME };
