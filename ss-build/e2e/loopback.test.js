// End-to-end test of call mode's Simple Voice Chat side, with the loopback-plugin standing in for Discord (whatever
// the call says comes straight back as the "Discord" audio). Real server, real SVC 2.6.21, real /dvc commands.
// Run against server.sh started with config-no-discord.yml and loopback-plugin/out/vcd-loopback-test.jar:
//   docker run --rm --network vcd-e2e -v "$PWD":/w -w /w node:22 node loopback.test.js
const { connect, powerAt, concat } = require('./svc-client');

const HOST = process.env.MC_HOST || 'vcd-e2e-mc';
const CATEGORY = 'discord_call';
const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (who) => (m) => process.env.DEBUG && console.log(`[${who}]`, m);

/** Call-audio channels a client has received (category discord_call). */
const callChannels = (c) => [...c.audio.values()].filter(ch => ch.category === CATEGORY);
const callPackets = (c) => callChannels(c).reduce((n, ch) => n + ch.packets, 0);
/** Whether the call audio a client received since `fromFrame` is clearly the tone `freq`. */
function heardTone(c, freq, fromFrames) {
  const chans = callChannels(c);
  if (!chans.length) return { ok: false, detail: 'no call audio at all' };
  const frames = chans.flatMap((ch, i) => ch.pcm.slice(fromFrames[i] || 0));
  if (frames.length < 10) return { ok: false, detail: `only ${frames.length} packets` };
  const pcm = concat(frames);
  const p = powerAt(pcm, freq), other = powerAt(pcm, freq * 2.37);
  return { ok: p > 50 * other, detail: `${frames.length} packets, ${freq} Hz power ${p.toFixed(0)} vs ${other.toFixed(1)} elsewhere` };
}
const marks = (c) => callChannels(c).map(ch => ch.pcm.length);
/** Asks to join the loopback call and has its stand-in Discord user answer. */
async function ask(c, answer = 'let in') {
  c.chat('/dvc call loopback');
  const asked = await c.waitFor(/Asking the people in the Discord call/).catch(() => null);
  c.chat('/loopback-answer ' + answer);
  return asked;
}
/** What the stand-in Discord chat shows: [newest message, bot nickname]. */
async function discordSide(c) {
  c.messages.length = 0;
  c.chat('/loopback-chat');
  const chat = await c.waitFor(/^CHAT: /).catch(() => '');
  const nick = await c.waitFor(/^NICK: /).catch(() => '');
  return [chat, nick];
}

(async () => {
  const alice = await connect({ host: HOST, port: 25565, username: 'SvcAlice', log: log('alice') });
  const bob = await connect({ host: HOST, port: 25565, username: 'SvcBob', log: log('bob') });
  const carol = await connect({ host: HOST, port: 25565, username: 'SvcCarol', log: log('carol') });

  alice.chat('/dvc call loopback');
  check('/dvc call asks Discord first', !!(await alice.waitFor(/Asking the people in the Discord call/).catch(() => null)));
  await sleep(1000);
  check('nothing is joined before an answer', callChannels(alice).length === 0 && !alice.messages.some(m => /Connected!/.test(m)));
  let [chat, nick] = await discordSide(alice);
  check('Discord sees the request with Let in / Deny', /SvcAlice\*\* wants to join this voice channel from Minecraft/.test(chat), chat);
  alice.chat('/loopback-answer let in');
  check('first player connects the call once let in', !!(await alice.waitFor(/Connected! You're in the Discord voice call Loopback/).catch(() => null)));
  await sleep(500);
  [chat, nick] = await discordSide(alice);
  check('Discord sees who is listening and who let them in', /SvcAlice\*\* is in the call from Minecraft, let in by LoopbackFriend/.test(chat), chat);
  check("the bot's nickname lists who's listening", nick === 'NICK: MC: SvcAlice', nick);
  check('the Discord volume slider is registered with clients', alice.categories.includes(CATEGORY), JSON.stringify(alice.categories));

  await ask(bob);
  check('second player joins the running call', !!(await bob.waitFor(/You're in the Discord voice call Loopback/).catch(() => null)));
  await sleep(500);
  [, nick] = await discordSide(alice);
  check('the nickname follows', nick === 'NICK: MC: SvcAlice, SvcBob', nick);

  await ask(carol, 'deny');
  check('a denied player is told', !!(await carol.waitFor(/The Discord call said no/).catch(() => null)));
  [chat] = await discordSide(alice);
  check('Discord sees the no', /SvcCarol\*\* asked to join from Minecraft\. LoopbackFriend said no/.test(chat), chat);
  carol.chat('/dvc call loopback');
  check('a denied player has to wait before asking again', !!(await carol.waitFor(/Wait \d+ seconds? before asking again/).catch(() => null)));

  await alice.speak(440, 1500);
  await sleep(500);
  let h = heardTone(bob, 440, []);
  check("another player in the call hears it", h.ok, h.detail);
  h = heardTone(alice, 440, []);
  check('the speaker hears the "Discord" side too (loopback echoes her)', h.ok, h.detail);
  check('a player who is not in the call gets no call audio', callPackets(carol) === 0, `${callPackets(carol)} packets`);
  check('call audio arrives as one stable channel per player', callChannels(bob).length === 1 && callChannels(alice).length === 1);

  await sleep(1500);
  check('the stream is flushed when the "Discord" side goes quiet', callChannels(bob)[0].flushes >= 1, `${callChannels(bob)[0].flushes} flushes`);

  bob.chat('/dvc group create iso "" isolated');
  await bob.waitFor(/Successfully created the group/).catch(() => null);
  let m = marks(bob);
  await alice.speak(700, 1500);
  await sleep(500);
  h = heardTone(bob, 700, m);
  check('a player in an isolated voice group still hears the call', h.ok, h.detail);

  bob.chat('/dvc hangup');
  check('/dvc hangup confirms', !!(await bob.waitFor(/You left the Discord call/).catch(() => null)));
  const bobBefore = callPackets(bob);
  m = marks(alice);
  await alice.speak(900, 1000);
  await sleep(500);
  check('after hanging up a player hears nothing more', callPackets(bob) === bobBefore, `${callPackets(bob) - bobBefore} new packets`);
  h = heardTone(alice, 900, m);
  check('the rest of the call carries on', h.ok, h.detail);
  [, nick] = await discordSide(alice);
  check('the nickname drops whoever hung up', nick === 'NICK: MC: SvcAlice', nick);

  await ask(bob);
  await bob.waitFor(/You're in the Discord voice call Loopback/).catch(() => null);
  // The player hears first; the Discord message gets its Remove button a moment later.
  for (let i = 0; i < 20 && !/SvcBob\*\* is in the call/.test((await discordSide(alice))[0]); i++) await sleep(250);
  bob.messages.length = 0;
  alice.chat('/loopback-answer remove');
  check('Discord can remove a Minecraft player', !!(await bob.waitFor(/Someone in the Discord call removed you/).catch(() => null)));
  await sleep(500);
  const bobRemoved = callPackets(bob);
  m = marks(alice);
  await alice.speak(800, 800);
  await sleep(500);
  check('a removed player hears nothing more', callPackets(bob) === bobRemoved, `${callPackets(bob) - bobRemoved} new packets`);
  [chat] = await discordSide(alice);
  check('Discord sees who removed them', /SvcBob\*\* was in the call from Minecraft \(let in by LoopbackFriend\) and was removed by LoopbackFriend/.test(chat), chat);
  bob.chat('/dvc call loopback');
  check('a removed player has to wait before asking again', !!(await bob.waitFor(/Wait \d+ seconds? before asking again/).catch(() => null)));

  // Quitting: Carol (still on cooldown) can't be used, so a fresh player joins and quits.
  const dave = await connect({ host: HOST, port: 25565, username: 'SvcDave', log: log('dave') });
  await ask(dave);
  await dave.waitFor(/You're in the Discord voice call Loopback/).catch(() => null);
  dave.quit();
  await sleep(1500);
  alice.messages.length = 0;
  alice.chat('/dvc calls');
  const list = await alice.waitFor(/^loopback/).catch(() => '');
  check('a player who quits the server is removed from the call', /SvcAlice$/.test(list), list);

  alice.chat('/dvc hangup');
  await alice.waitFor(/You left the Discord call/).catch(() => null);
  alice.messages.length = 0;
  alice.chat('/dvc calls');
  check('when the last player leaves, the call disconnects', /^loopback: nobody$/.test(await alice.waitFor(/^loopback/).catch(() => '')));
  await sleep(500);
  [, nick] = await discordSide(alice);
  check("with nobody listening the bot's own name is back", nick === 'NICK: null', nick);

  alice.chat('/dvc call loopback');
  await alice.waitFor(/Asking the people in the Discord call/).catch(() => null);
  alice.chat('/dvc hangup');
  check('/dvc hangup cancels an open request', !!(await alice.waitFor(/Cancelled your request/).catch(() => null)));
  [chat] = await discordSide(alice);
  check('Discord sees it was cancelled', /They cancelled it\./.test(chat), chat);

  await ask(alice);
  check('the call can be joined again afterwards', !!(await alice.waitFor(/Connected! You're in the Discord voice call Loopback/).catch(() => null)));
  m = marks(alice);
  await alice.speak(1100, 1000);
  await sleep(500);
  h = heardTone(alice, 1100, m);
  check('and audio flows again', h.ok, h.detail);

  alice.quit(); bob.quit(); carol.quit();
  const failed = results.filter(r => !r).length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.log('FAIL ' + e.message); process.exit(1); });
