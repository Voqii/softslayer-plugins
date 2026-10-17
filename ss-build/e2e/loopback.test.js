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

(async () => {
  const alice = await connect({ host: HOST, port: 25565, username: 'SvcAlice', log: log('alice') });
  const bob = await connect({ host: HOST, port: 25565, username: 'SvcBob', log: log('bob') });
  const carol = await connect({ host: HOST, port: 25565, username: 'SvcCarol', log: log('carol') });

  alice.chat('/dvc call loopback');
  check('first player connects the call', !!(await alice.waitFor(/Connected! You're in the Discord voice call Loopback/).catch(() => null)));
  check('the Discord volume slider is registered with clients', alice.categories.includes(CATEGORY), JSON.stringify(alice.categories));

  bob.chat('/dvc call loopback');
  check('second player joins the running call', !!(await bob.waitFor(/You're in the Discord voice call Loopback/).catch(() => null)));

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

  bob.chat('/dvc call loopback');
  await bob.waitFor(/You're in the Discord voice call Loopback/).catch(() => null);
  bob.quit();
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

  alice.chat('/dvc call loopback');
  check('the call can be joined again afterwards', !!(await alice.waitFor(/Connected! You're in the Discord voice call Loopback/).catch(() => null)));
  m = marks(alice);
  await alice.speak(1100, 1000);
  await sleep(500);
  h = heardTone(alice, 1100, m);
  check('and audio flows again', h.ok, h.detail);

  alice.quit(); carol.quit();
  const failed = results.filter(r => !r).length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.log('FAIL ' + e.message); process.exit(1); });
