// End-to-end checks that don't need Discord: run against server.sh with config-no-discord.yml (a call bot with a
// token Discord will reject). Run inside the server's docker network:
//   docker run --rm --network vcd-e2e -v "$PWD":/w -w /w node:22 node no-discord.test.js
const mineflayer = require('mineflayer');
const { connect } = require('./svc-client');

const HOST = process.env.MC_HOST || 'vcd-e2e-mc';
const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** A plain mineflayer player without Simple Voice Chat. */
function plainPlayer(username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: HOST, port: 25565, username, version: '1.21.11', auth: 'offline', physicsEnabled: false });
    { const w = bot._client.write.bind(bot._client);
      bot._client.write = (n, d) => { if (/^(position|position_look|look|flying|player_input|move_player)/.test(n)) return; return w(n, d); }; }
    bot.messages = [];
    const flat = (c) => { if (c == null) return ''; if (typeof c === 'string') return c; if (Array.isArray(c)) return c.map(flat).join('');
      if (c.type === 'compound' || c.type === 'list' || c.type === 'string') return flat(c.value); let s = flat(c.text ?? c[''] ?? ''); if (c.extra) s += flat(c.extra); return s; };
    bot._client.on('system_chat', (d) => bot.messages.push(flat(d.content)));
    bot.once('spawn', () => resolve(bot));
    bot.once('end', (r) => reject(new Error('disconnected ' + r)));
  });
}
const last = (bot, re) => bot.messages.find(m => re.test(m));

(async () => {
  const alice = await connect({ host: HOST, port: 25565, username: 'SvcAlice', log: (m) => process.env.DEBUG && console.log('[alice]', m) });
  check('test client completes the Simple Voice Chat handshake', true);

  alice.chat('/dvc calls');
  check('/dvc calls lists the call bot', !!(await alice.waitFor(/^friends: nobody/).catch(() => null)), alice.messages.at(-1));

  const noMod = await plainPlayer('NoModTester');
  await sleep(1000);
  noMod.chat('/dvc call');
  await sleep(1500);
  check('a player without the mod is refused', !!last(noMod, /need the Simple Voice Chat mod/), noMod.messages.at(-1));

  const noPerm = await plainPlayer('NoPermTester');
  await sleep(1000);
  noPerm.chat('/dvc call');
  await sleep(1500);
  check('a player without voicechat-discord.call is refused', !!last(noPerm, /need the `voicechat-discord.call` permission/), noPerm.messages.at(-1));

  alice.chat('/dvc call nosuchcall');
  check('an unknown call name lists the real ones', !!(await alice.waitFor(/no call named nosuchcall.*friends/).catch(() => null)));

  alice.chat('/dvc call');
  check('a player with the mod is asked to wait for Discord', !!(await alice.waitFor(/Asking the people in the Discord call/).catch(() => null)));
  check('a bad token fails cleanly and tells the player', !!(await alice.waitFor(/Couldn't reach Discord/, 30000).catch(() => null)));

  alice.chat('/dvc hangup');
  check('after a failed connect the player is no longer in the call', !!(await alice.waitFor(/not in a Discord call/).catch(() => null)));

  alice.chat('/dvc calls');
  await sleep(1500);
  check('/dvc calls shows nobody again', alice.messages.filter(m => /^friends: nobody/.test(m)).length >= 2);

  alice.chat('/dvc start');
  check('/dvc start still works for player-mode bots (none configured here)', !!(await alice.waitFor(/no bots available/).catch(() => null)));

  alice.quit(); noMod.quit(); noPerm.quit();
  const failed = results.filter(r => !r).length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.log('FAIL ' + e.message); process.exit(1); });
