// End-to-end test through real Discord (DAVE-encrypted voice): two test bots talk in the voice channel at the same
// time and one of them listens; the headless Simple Voice Chat client joins the call in game.
// Checks: both Discord speakers reach the game mixed (the upstream bug), the game reaches Discord, and the bridge
// bot leaves the channel when the call ends. Run with run-discord.sh, which starts the server with the bridge bot.
// Env: VC_ID, BRIDGE_TOKEN (the bot in the plugin's config), SPEAKER1_TOKEN, SPEAKER2_TOKEN (two other bots
// in the same server, with Connect + Speak in the channel).
const { Client, GatewayIntentBits } = require('discord.js');
const { joinVoiceChannel, entersState, VoiceConnectionStatus, createAudioPlayer, createAudioResource, StreamType,
  AudioPlayerStatus, EndBehaviorType, NoSubscriberBehavior } = require('@discordjs/voice');
const { Readable } = require('stream');
const OpusScript = require('opusscript');
const { connect, powerAt, concat, SAMPLE_RATE, FRAME } = require('./svc-client');
const fs = require('fs');
const CONFIG = '/data/plugins/voicechat-discord/config.yml'; // run-discord.sh mounts the server's data dir

const HOST = process.env.MC_HOST || 'vcd-e2e-mc';
const { VC_ID, BRIDGE_TOKEN, SPEAKER1_TOKEN, SPEAKER2_TOKEN } = process.env;
const BRIDGE_ID = BRIDGE_TOKEN && Buffer.from(BRIDGE_TOKEN.split('.')[0], 'base64').toString();
const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** Logs a test bot in and joins the voice channel (not deafened, so it can listen). */
async function speakerBot(token) {
  const client = new Client({ intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildVoiceStates] });
  await client.login(token);
  await new Promise(r => client.isReady() ? r() : client.once('clientReady', r));
  const channel = await client.channels.fetch(VC_ID);
  const connection = joinVoiceChannel({ channelId: channel.id, guildId: channel.guild.id, adapterCreator: channel.guild.voiceAdapterCreator, selfDeaf: false, selfMute: false,
    // @discordjs/voice keeps one connection per guild per group: without this, both bots in this process
    // would share a single voice connection.
    group: client.user.id,
    debug: !!process.env.DEBUG_VOICE });
  const name = client.user.username;
  connection.on('stateChange', (a, b) => process.env.DEBUG && console.log(`[${name}] voice ${a.status} -> ${b.status}`));
  connection.on('error', (e) => console.log(`[${name}] voice error ${e.message}`));
  connection.on('debug', (m) => console.log(`[${name}] debug ${String(m).slice(0, 200)}`));
  await entersState(connection, VoiceConnectionStatus.Ready, 20000);
  return { client, connection, channel, name };
}

/** Plays a sine tone for `ms` as Opus packets (no ffmpeg needed). */
function playTone(bot, freq, ms) {
  const enc = new OpusScript(SAMPLE_RATE, 2, OpusScript.Application.AUDIO); // Discord voice is stereo
  const frames = Math.round(ms / 20);
  let frame = 0;
  // objectMode: one chunk = one Opus packet. Without it Node merges the packets into a few big chunks.
  const stream = new Readable({
    objectMode: true,
    read() {
      if (frame >= frames) { this.push(null); return; }
      const pcm = Buffer.alloc(FRAME * 4);
      for (let i = 0; i < FRAME; i++) {
        const v = Math.round(8000 * Math.sin(2 * Math.PI * freq * (frame * FRAME + i) / SAMPLE_RATE));
        pcm.writeInt16LE(v, i * 4); pcm.writeInt16LE(v, i * 4 + 2);
      }
      frame++;
      this.push(Buffer.from(enc.encode(pcm, FRAME)));
    },
  });
  const player = createAudioPlayer({ behaviors: { noSubscriber: NoSubscriberBehavior.Play } });
  player.on('stateChange', (a, b) => process.env.DEBUG && console.log(`[${bot.name}] player ${a.status} -> ${b.status}`));
  player.on('error', (e) => console.log(`[${bot.name}] player error ${e.message}`));
  bot.connection.subscribe(player);
  player.play(createAudioResource(stream, { inputType: StreamType.Opus }));
  return entersState(player, AudioPlayerStatus.Idle, ms + 10000);
}

/** Records what `userId` says in the channel, decoded to mono PCM. */
function record(bot, userId) {
  const dec = new OpusScript(SAMPLE_RATE, 2, OpusScript.Application.AUDIO);
  const frames = [];
  const stream = bot.connection.receiver.subscribe(userId, { end: { behavior: EndBehaviorType.Manual } });
  stream.on('data', (packet) => {
    const pcm = dec.decode(packet);
    const stereo = new Int16Array(pcm.buffer, pcm.byteOffset, pcm.length / 2);
    const mono = new Int16Array(stereo.length / 2);
    for (let i = 0; i < mono.length; i++) mono[i] = (stereo[2 * i] + stereo[2 * i + 1]) / 2;
    frames.push(mono);
  });
  return { frames, stop: () => stream.destroy() };
}

(async () => {
  for (const v of ['VC_ID', 'BRIDGE_TOKEN', 'SPEAKER1_TOKEN', 'SPEAKER2_TOKEN']) if (!process.env[v]) throw new Error(`set ${v}`);
  const s1 = await speakerBot(SPEAKER1_TOKEN);
  const s2 = await speakerBot(SPEAKER2_TOKEN);
  check('test bots joined the Discord voice channel', true, s1.channel.name);

  // Sanity check of the test bots themselves: can one hear the other, with no bridge involved?
  // Everyone joining makes the DAVE group re-key, and audio sent before that's done is dropped.
  const SETTLE_MS = Number(process.env.SETTLE_MS || 8000);
  await sleep(SETTLE_MS);
  {
    const rec = record(s2, s1.client.user.id);
    await playTone(s1, 600, 2000);
    await sleep(1000);
    rec.stop();
    const pcm = concat(rec.frames.slice(10));
    const p = powerAt(pcm, 600), pn = powerAt(pcm, 3100);
    check('(sanity) one test bot hears the other', pcm.length > SAMPLE_RATE / 2 && p > 50 * pn, `${rec.frames.length} packets`);
  }

  const alice = await connect({ host: HOST, port: 25565, username: 'SvcAlice', log: (m) => process.env.DEBUG && console.log('[alice]', m) });
  const bridgeMessages = async () => (await s1.channel.messages.fetch({ limit: 20 })).filter(msg => msg.author.id === BRIDGE_ID);
  const before = (await bridgeMessages()).size;

  // Approval is on (run-discord.sh's config). Only bots are in the channel, and bots can't let anyone in.
  alice.chat('/dvc call');
  check('with approval on, /dvc call asks first', !!(await alice.waitFor(/Asking the people in the Discord call/).catch(() => null)));
  check("test bots in the channel don't count as people who can answer",
    !!(await alice.waitFor(/Nobody is in the Discord call right now/, 30000).catch(() => null)), alice.messages.at(-1));
  check('so nothing is posted in the channel', (await bridgeMessages()).size === before);
  check('and the bridge bot stays out of voice', !s1.channel.guild.voiceStates.cache.get(BRIDGE_ID)?.channelId);

  // Buttons need a person to click them, so the rest runs with approval off.
  fs.writeFileSync(CONFIG, fs.readFileSync(CONFIG, 'utf8').replace('approval: true', 'approval: false'));
  alice.chat('/dvc reloadconfig');
  await alice.waitFor(/[Rr]eloaded|[Ss]uccessfully/, 30000).catch(() => null);
  await sleep(2000);
  alice.chat('/dvc call');
  check('the bridge connects to Discord', !!(await alice.waitFor(/Connected! You're in the Discord voice call/, 30000).catch(() => null)), alice.messages.at(-1));
  await sleep(SETTLE_MS);
  const bridgeState = s1.channel.guild.voiceStates.cache.get(BRIDGE_ID);
  check('the bridge bot is in the voice channel', bridgeState?.channelId === VC_ID);
  const joinNotices = await bridgeMessages();
  check("the channel's chat gets a notice that a Minecraft player joined", joinNotices.size === before + 1,
    `${joinNotices.size - before} new messages (needs Send Messages + Read Message History for the bridge bot)`);
  const notice = joinNotices.first();
  // Other bots' message text and buttons need the Message Content intent, but a bot can always read its own.
  const own = await (await fetch(`https://discord.com/api/v10/channels/${VC_ID}/messages/${notice?.id}`,
    { headers: { Authorization: `Bot ${BRIDGE_TOKEN}` } })).json();
  check('the notice says who is listening', /\*\*SvcAlice\*\* is in the call from Minecraft\./.test(own.content || ''), own.content);
  check('the notice has a Remove button', !!own.components?.[0]?.components?.some(c => c.label === 'Remove'));
  const bridgeMember = await s1.channel.guild.members.fetch({ user: BRIDGE_ID, force: true });
  check("the bridge bot's nickname shows who's listening", bridgeMember.nickname === 'MC: SvcAlice',
    `nickname ${JSON.stringify(bridgeMember.nickname)} (needs Change Nickname)`);

  // Each speaker alone first, as a reference: Discord's codec doesn't treat every frequency the same, so the
  // mix is compared with how each speaker came through on their own, not with the other speaker.
  const audioChannels = () => [...alice.audio.values()].filter(c => c.category === 'discord_call');
  const mark = () => audioChannels().reduce((n, c) => n + c.pcm.length, 0);
  const since = (m) => concat(audioChannels().flatMap(c => c.pcm).slice(m + 10)); // skip start-up
  let m = mark();
  await playTone(s1, 440, 2500);
  await sleep(1000);
  const solo1 = since(m);
  m = mark();
  await playTone(s2, 1250, 2500);
  await sleep(1000);
  const solo2 = since(m);
  const solo440 = powerAt(solo1, 440), solo1250 = powerAt(solo2, 1250);
  check('the game hears each Discord speaker', solo440 > 50 * powerAt(solo1, 3100) && solo1250 > 50 * powerAt(solo2, 3100),
    `alone: 440 Hz ${solo440.toFixed(0)}, 1250 Hz ${solo1250.toFixed(0)}`);

  // Now both at once. Upstream forwarded one of them per 20 ms, so one would vanish.
  m = mark();
  await Promise.all([playTone(s1, 440, 3000), playTone(s2, 1250, 3000)]);
  await sleep(1000);
  const heard = since(m);
  if (process.env.DEBUG) {
    const w = SAMPLE_RATE / 5; // each tone per 200 ms, to tell a mixing problem from a speaker that started late
    for (let i = 0; i + w <= heard.length; i += w) {
      const win = heard.subarray(i, i + w);
      console.log(`  t=${(i / SAMPLE_RATE).toFixed(1)}s  440Hz ${powerAt(win, 440).toFixed(0).padStart(7)}  1250Hz ${powerAt(win, 1250).toFixed(0).padStart(7)}`);
    }
  }
  const a = powerAt(heard, 440), b = powerAt(heard, 1250);
  // Power ratio 0.25 = half the loudness: allows for start-up and jitter, but not for a speaker being dropped
  // half the time (upstream's behaviour would give ~0 for one of them).
  check('both Discord speakers come through at once, each about as loud as alone', a > 0.25 * solo440 && b > 0.25 * solo1250,
    `together/alone: 440 Hz ${(a / solo440).toFixed(2)}, 1250 Hz ${(b / solo1250).toFixed(2)}`);

  // The game talks to Discord.
  const rec = record(s1, BRIDGE_ID);
  await alice.speak(700, 3000);
  await sleep(1500);
  rec.stop();
  const fromGame = concat(rec.frames.slice(10));
  const p = powerAt(fromGame, 700), pn = powerAt(fromGame, 3100);
  check('Discord hears the player in game', fromGame.length > SAMPLE_RATE / 2 && p > 50 * pn, `${rec.frames.length} packets, 700 Hz ${p.toFixed(0)} vs ${pn.toFixed(1)}`);

  alice.chat('/dvc hangup');
  await alice.waitFor(/You left the Discord call/).catch(() => null);
  await sleep(5000);
  check('the bridge bot leaves when the call ends', !s1.channel.guild.voiceStates.cache.get(BRIDGE_ID)?.channelId);
  const after = await s1.channel.messages.fetch({ message: notice?.id, force: true }).catch(() => null);
  check('the notice is updated when the player leaves', !!after?.editedTimestamp);
  const nickAfter = (await s1.channel.guild.members.fetch({ user: BRIDGE_ID, force: true })).nickname;
  check("the bridge bot's own name is back", nickAfter === null, JSON.stringify(nickAfter));

  alice.quit();
  for (const s of [s1, s2]) {
    if (s.connection.state.status !== VoiceConnectionStatus.Destroyed) s.connection.destroy();
    await s.client.destroy();
  }
  const failed = results.filter(r => !r).length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.log('FAIL ' + (e.stack || e.message)); process.exit(1); });
