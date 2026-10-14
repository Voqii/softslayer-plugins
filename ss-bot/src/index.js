// SS-Bot: relays SimpleClans clan chat <-> each clan's own Discord channel.
//
// The plugin side is the HTTP bridge described in SimpleClans' BRIDGE.md. The plugin only knows clan
// tags; the tag -> Discord channel mapping lives here, in data/links.json.
//
// Two transports (BRIDGE_MODE):
//   poll   - the bot calls the plugin's localhost API (bot and server on the same machine).
//   listen - the plugin calls the bot: it POSTs /sync with its queued chat/events plus a guild snapshot,
//            and takes the queued Discord messages back in the response. For hosts where nothing can
//            reach the Minecraft server (panel containers); the bot sits behind a tunnel.
//
// Linking a channel: someone with Manage Server runs /guildlink tag:<tag> in the channel. The bot
// replies with a one-time code, and a leader of that clan proves ownership by typing the code in
// in-game clan chat (/. <code>). The bot sees it via /chat/poll and checks the sender against
// /clan/info's leader list.

import {
  Client, GatewayIntentBits, Events, PermissionFlagsBits, ChannelType,
  SlashCommandBuilder, MessageFlags, WebhookClient, escapeMarkdown,
} from 'discord.js';
import { randomInt, timingSafeEqual } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';

const { DISCORD_TOKEN, BRIDGE_SECRET } = process.env;
const BRIDGE_URL = process.env.BRIDGE_URL || 'http://127.0.0.1:8787';
const POLL_MS = Number(process.env.POLL_MS || 1500);
const LISTEN = (process.env.BRIDGE_MODE || 'poll') === 'listen';
const LISTEN_HOST = process.env.LISTEN_HOST || '127.0.0.1';
const LISTEN_PORT = Number(process.env.LISTEN_PORT || 8790);
const SERVER_STALE_MS = 30_000; // listen mode: no sync for this long = server offline
const MAX_BODY = 1_000_000;
const LINKS_FILE = new URL('../data/links.json', import.meta.url);
const CODE_TTL_MS = 10 * 60 * 1000;
const MAX_INBOUND_LEN = 256;

if (!DISCORD_TOKEN || !BRIDGE_SECRET) {
  console.error('DISCORD_TOKEN and BRIDGE_SECRET must be set in .env');
  process.exit(1);
}

// --- link storage: { [tagLower]: { tag, guildId, channelId, webhookId?, webhookToken? } } ---

let links = existsSync(LINKS_FILE) ? JSON.parse(readFileSync(LINKS_FILE, 'utf8')) : {};
const webhooks = new Map(); // tagLower -> WebhookClient

function saveLinks() {
  const tmp = new URL('../data/links.json.tmp', import.meta.url);
  writeFileSync(tmp, JSON.stringify(links, null, 2), { mode: 0o600 });
  renameSync(tmp, LINKS_FILE);
}

function linkForChannel(channelId) {
  return Object.values(links).find((l) => l.channelId === channelId);
}

function removeLink(tagLower) {
  webhooks.get(tagLower)?.destroy();
  webhooks.delete(tagLower);
  delete links[tagLower];
  saveLinks();
}

// --- pending link codes: code -> { tagLower, guildId, channelId, userId, expires } ---

const pending = new Map();
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I

function newCode() {
  let code;
  do {
    code = 'SS-' + Array.from({ length: 5 }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join('');
  } while (pending.has(code));
  return code;
}

function prunePending() {
  const now = Date.now();
  for (const [code, p] of pending) if (p.expires < now) pending.delete(code);
}

// --- bridge HTTP ---

async function bridge(path, init = {}) {
  const res = await fetch(BRIDGE_URL + path, {
    ...init,
    headers: { 'X-Bridge-Secret': BRIDGE_SECRET, 'Content-Type': 'application/json', ...init.headers },
    signal: AbortSignal.timeout(8000),
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`bridge ${path} -> HTTP ${res.status}`);
  return res.json();
}

// --- listen-mode state ---

const clanCache = new Map(); // tagLower -> { tag, name, leaders: [{ uuid, name }] }
const toGame = []; // Discord messages waiting for the server's next /sync
let lastSync = 0;
let haveClans = false; // got at least one guild list since start
let serverDown = true;

function serverOnline() {
  return Date.now() - lastSync < SERVER_STALE_MS;
}

async function clanInfo(tag) {
  if (!LISTEN) return bridge('/clan/info?tag=' + encodeURIComponent(tag));
  if (!serverOnline()) throw new Error('Minecraft server has not synced recently');
  return clanCache.get(tag.toLowerCase()) ?? null;
}

// discordId lets the plugin show a DiscordSRV-linked sender under their in-game nickname.
async function sayInGame(clanTag, sender, message, discordId = null) {
  if (!LISTEN) return bridge('/chat/in', { method: 'POST', body: JSON.stringify({ clanTag, sender, message, discordId }) });
  if (!serverOnline()) throw new Error('Minecraft server has not synced recently');
  toGame.push({ clanTag, sender, message, discordId });
  if (toGame.length > 500) toGame.shift();
}

// --- Discord ---

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent],
});

const commands = [
  new SlashCommandBuilder()
    .setName('guildlink')
    .setDescription('Link this channel to your guild\'s in-game guild chat')
    .addStringOption((o) => o.setName('tag').setDescription('Your guild tag').setRequired(true).setMaxLength(32))
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
    .setDMPermission(false),
  new SlashCommandBuilder()
    .setName('guildunlink')
    .setDescription('Stop relaying guild chat in this channel')
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
    .setDMPermission(false),
  new SlashCommandBuilder()
    .setName('guildstatus')
    .setDescription('Show which guild this channel is linked to')
    .setDMPermission(false),
];

client.once(Events.ClientReady, async (c) => {
  await c.application.commands.set(commands.map((cmd) => cmd.toJSON()));
  console.log(`Logged in as ${c.user.tag}; ${Object.keys(links).length} guild link(s) loaded`);
  if (LISTEN) startListener();
  else pollLoop();
});

client.on(Events.InteractionCreate, async (i) => {
  if (!i.isChatInputCommand() || !i.inGuild()) return;
  const reply = (content) => i.reply({ content, flags: MessageFlags.Ephemeral });

  try {
    if (i.commandName === 'guildstatus') {
      const link = linkForChannel(i.channelId);
      return reply(link ? `This channel is linked to guild **${link.tag}**.` : 'This channel isn\'t linked to a guild.');
    }

    if (i.commandName === 'guildunlink') {
      const link = linkForChannel(i.channelId);
      if (!link) return reply('This channel isn\'t linked to a guild.');
      removeLink(link.tag.toLowerCase());
      return reply(`Unlinked guild **${link.tag}** from this channel.`);
    }

    if (i.commandName === 'guildlink') {
      if (i.channel?.type !== ChannelType.GuildText) return reply('Run this in a regular text channel.');
      const info = await clanInfo(i.options.getString('tag', true).trim());
      if (!info) return reply('No guild with that tag exists on the server.');

      prunePending();
      const code = newCode();
      pending.set(code, {
        tagLower: info.tag.toLowerCase(), guildId: i.guildId, channelId: i.channelId,
        userId: i.user.id, expires: Date.now() + CODE_TTL_MS,
      });
      return reply(
        `To link this channel to **${info.tag}** (${escapeMarkdown(info.name)}), a leader of that guild types this ` +
        `in-game within 10 minutes:\n\`\`\`\n/. ${code}\n\`\`\``,
      );
    }
  } catch (err) {
    console.error(`/${i.commandName} failed:`, err);
    const msg = 'Couldn\'t reach the Minecraft server. Try again in a minute.';
    if (i.replied || i.deferred) i.followUp({ content: msg, flags: MessageFlags.Ephemeral }).catch(() => {});
    else reply(msg).catch(() => {});
  }
});

// Discord -> game
client.on(Events.MessageCreate, async (m) => {
  if (m.author.bot || m.webhookId || !m.inGuild()) return;
  const link = linkForChannel(m.channelId);
  if (!link) return;

  let text = m.cleanContent.replace(/\s+/g, ' ').trim();
  if (m.attachments.size) text = (text + ' [attachment]').trim();
  if (!text) return;
  if (text.length > MAX_INBOUND_LEN) text = text.slice(0, MAX_INBOUND_LEN - 1) + '…';

  const sender = (m.member?.displayName ?? m.author.username).slice(0, 32);
  try {
    await sayInGame(link.tag, sender, text, m.author.id);
  } catch (err) {
    console.error(`relay to ${link.tag} failed:`, err.message);
    m.react('⚠️').catch(() => {});
  }
});

// Clean up links when the bot loses access.
client.on(Events.ChannelDelete, (ch) => {
  const link = linkForChannel(ch.id);
  if (link) removeLink(link.tag.toLowerCase());
});
client.on(Events.GuildDelete, (g) => {
  for (const [tagLower, l] of Object.entries(links)) if (l.guildId === g.id) removeLink(tagLower);
});

// --- linking ---

async function completeLink(p, msg) {
  const info = await clanInfo(msg.clanTag);
  if (!info || !info.leaders.some((l) => l.uuid === msg.uuid)) {
    await sayInGame(msg.clanTag, 'SS-Bot', 'Only a guild leader can link a Discord channel.');
    return;
  }
  pending.delete(msg.message.trim().toUpperCase());

  const channel = await client.channels.fetch(p.channelId).catch(() => null);
  if (!channel) return;

  // Unlink anything else this clan or this channel was linked to.
  const tagLower = info.tag.toLowerCase();
  const prevChannelLink = linkForChannel(p.channelId);
  if (prevChannelLink) removeLink(prevChannelLink.tag.toLowerCase());
  if (links[tagLower]) removeLink(tagLower);

  const link = { tag: info.tag, guildId: p.guildId, channelId: p.channelId };
  try {
    const hook = await channel.createWebhook({ name: 'SS-Bot Guild Chat', reason: `Guild chat relay for ${info.tag}` });
    link.webhookId = hook.id;
    link.webhookToken = hook.token;
  } catch {
    // No Manage Webhooks permission: fall back to plain bot messages.
  }
  links[tagLower] = link;
  saveLinks();

  await channel.send({
    content: `🔗 Linked to guild **${info.tag}** by ${escapeMarkdown(msg.sender)}. In-game guild chat now shows up here, and messages here go to online guild members.`,
    allowedMentions: { parse: [] },
  });
  await sayInGame(info.tag, 'SS-Bot', `Guild chat is now linked to #${channel.name} on Discord.`);
}

// --- game -> Discord ---

function webhookFor(tagLower) {
  const l = links[tagLower];
  if (!l?.webhookId) return null;
  if (!webhooks.has(tagLower)) webhooks.set(tagLower, new WebhookClient({ id: l.webhookId, token: l.webhookToken }));
  return webhooks.get(tagLower);
}

async function relayToDiscord(msg) {
  const tagLower = msg.clanTag.toLowerCase();
  const link = links[tagLower];
  if (!link) return;

  const content = escapeMarkdown(msg.message).slice(0, 2000);
  const hook = webhookFor(tagLower);
  if (hook) {
    try {
      await hook.send({
        content,
        username: msg.sender,
        avatarURL: `https://mc-heads.net/avatar/${msg.uuid}/64`,
        allowedMentions: { parse: [] },
      });
      return;
    } catch (err) {
      if (err.code !== 10015) throw err; // 10015 = Unknown Webhook (someone deleted it); fall back
      delete link.webhookId;
      delete link.webhookToken;
      webhooks.delete(tagLower);
      saveLinks();
    }
  }
  const channel = await client.channels.fetch(link.channelId).catch(() => null);
  if (!channel) return;
  await channel.send({ content: `**${escapeMarkdown(msg.sender)}**: ${content}`.slice(0, 2000), allowedMentions: { parse: [] } });
}

// --- guild events (tag change / disband) ---

async function noticeInChannel(link, content) {
  const channel = await client.channels.fetch(link.channelId).catch(() => null);
  await channel?.send({ content, allowedMentions: { parse: [] } }).catch(() => {});
}

async function handleGuildEvent(msg) {
  if (msg.type === 'RETAG') {
    const oldLower = msg.oldTag.toLowerCase();
    const link = links[oldLower];
    if (!link) return;
    const newLower = msg.clanTag.toLowerCase();
    if (links[newLower] && newLower !== oldLower) removeLink(newLower);
    webhooks.get(oldLower)?.destroy();
    webhooks.delete(oldLower);
    delete links[oldLower];
    link.tag = msg.clanTag;
    links[newLower] = link;
    saveLinks();
    await noticeInChannel(link, `🏷️ Guild tag changed from **${msg.oldTag}** to **${msg.clanTag}**. This channel stays linked.`);
  } else if (msg.type === 'DISBAND') {
    const link = links[msg.clanTag.toLowerCase()];
    if (!link) return;
    removeLink(msg.clanTag.toLowerCase());
    await noticeInChannel(link, `💀 Guild **${link.tag}** was disbanded, so this channel is no longer linked.`);
  }
}

async function handleOutbound(msg) {
  if (msg.type === 'RETAG' || msg.type === 'DISBAND') return handleGuildEvent(msg);
  if (typeof msg.message !== 'string') return; // an event type this bot doesn't know
  const code = msg.message.trim().toUpperCase();
  const p = pending.get(code);
  if (p && p.expires >= Date.now() && p.tagLower === msg.clanTag.toLowerCase()) {
    return completeLink(p, msg); // the code itself isn't relayed
  }
  return relayToDiscord(msg);
}

let bridgeDown = false;
async function pollLoop() {
  for (;;) {
    try {
      const { messages } = await bridge('/chat/poll');
      if (bridgeDown) console.log('bridge reachable again');
      bridgeDown = false;
      for (const msg of messages) {
        await handleOutbound(msg).catch((err) => console.error(`relay from ${msg.clanTag} failed:`, err.message));
      }
    } catch (err) {
      if (!bridgeDown) console.error('bridge poll failed (will keep retrying):', err.message);
      bridgeDown = true;
    }
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
}

// --- listen mode: the plugin calls POST /sync ---

const SECRET_BUF = Buffer.from(BRIDGE_SECRET);
function authorized(req) {
  const given = Buffer.from(String(req.headers['x-bridge-secret'] ?? ''));
  return given.length === SECRET_BUF.length && timingSafeEqual(given, SECRET_BUF);
}

function sendJson(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

let processing = Promise.resolve(); // keeps game -> Discord messages in order across syncs

function startListener() {
  createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      return sendJson(res, 200, { status: 'ok', serverOnline: serverOnline(), lastSync, guilds: clanCache.size });
    }
    if (req.method !== 'POST' || req.url !== '/sync') return sendJson(res, 404, { error: 'not found' });
    if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' });

    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) req.destroy();
      else chunks.push(c);
    });
    req.on('end', () => {
      let body;
      try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      } catch {
        return sendJson(res, 400, { error: 'bad json' });
      }
      if (Array.isArray(body.clans)) {
        haveClans = true;
        clanCache.clear();
        for (const c of body.clans) if (c?.tag) clanCache.set(String(c.tag).toLowerCase(), c);
      }
      lastSync = Date.now();
      if (serverDown) {
        console.log(`Minecraft server connected (${clanCache.size} guilds known)`);
        serverDown = false;
      }
      const messages = Array.isArray(body.messages) ? body.messages : [];
      processing = processing.then(async () => {
        for (const msg of messages) {
          await handleOutbound(msg).catch((err) => console.error(`relay from ${msg.clanTag} failed:`, err.message));
        }
      });
      sendJson(res, 200, { inbound: toGame.splice(0), needClans: !haveClans });
    });
  }).listen(LISTEN_PORT, LISTEN_HOST, () => console.log(`Listening for the Minecraft server on http://${LISTEN_HOST}:${LISTEN_PORT}/sync`));

  setInterval(() => {
    if (!serverDown && !serverOnline()) {
      console.error('Minecraft server stopped syncing (offline or unreachable)');
      serverDown = true;
    }
  }, 10_000);
}

client.login(DISCORD_TOKEN);
