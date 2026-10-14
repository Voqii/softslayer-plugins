// Joins the throwaway server, runs commands, prints dialogs the server shows, optionally clicks a button by label.
const mineflayer = require('mineflayer');
// minecraft-data declares the click payload as option(nbt); the game writes the tag directly (END = none).
for (const v of ['26.1', '1.21.11']) { const pr = require('minecraft-data')(v).protocol;
  const fix = (t) => { if (t && t.packet_common_custom_click_action) t.packet_common_custom_click_action = ["container",[{"name":"id","type":"string"},{"name":"nbt","type":["buffer",{"countType":"varint"}]}]]; };
  fix(pr.types); fix(pr.play && pr.play.toServer && pr.play.toServer.types); }
const steps = JSON.parse(process.argv[2] || '[]');   // [{chat:"/guild"}, {click:"Guild info"}, {wait:2000}]
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'DlgTester', version: process.env.V || '26.1', auth: 'offline', physicsEnabled: false });
bot.physicsEnabled = false;
{ const w = bot._client.write.bind(bot._client);
  bot._client.write = (name, data) => { if (/^(position|position_look|look|flying|player_input|move_player)/.test(name)) return; return w(name, data); }; }
let last = null;
const text = (c) => { if (c == null) return ''; if (typeof c === 'string') return c; if (c.value !== undefined && typeof c.value !== 'object') return String(c.value);
  if (c.type === 'compound' || c.type === 'list') return text(c.value);
  if (Array.isArray(c)) return c.map(text).join(''); if (typeof c === 'object') { let s = ''; if (c.text) s += text(c.text); if (c[''] ) s += text(c['']); if (c.extra) s += text(c.extra); return s; } return ''; };
function simplify(n) { // prismarine-nbt simplify
  if (n && typeof n === 'object' && 'type' in n && 'value' in n) {
    if (n.type === 'compound') { const o = {}; for (const k in n.value) o[k] = simplify(n.value[k]); return o; }
    if (n.type === 'list') return (n.value.value || []).map(v => simplify({ type: n.value.type, value: v }));
    return n.value;
  }
  if (Array.isArray(n)) return n.map(simplify);
  if (n && typeof n === 'object') { const o = {}; for (const k in n) o[k] = simplify(n[k]); return o; }
  return n;
}
const flat = (c) => { if (c == null) return ''; if (typeof c === 'string') return c; if (Array.isArray(c)) return c.map(flat).join(''); let s = (c.text ?? c[''] ?? ''); if (c.extra) s += flat(c.extra); return s; };
bot._client.on('packet', (data, meta) => {
  if (meta.name === 'show_dialog') {
    if (process.env.RAW) console.log('RAW', JSON.stringify(data).slice(0, 3000));
    const d = simplify(data.dialog && data.dialog.data ? data.dialog.data : data.dialog);
    last = d;
    if (process.env.DUMP) console.log('DUMP', JSON.stringify({ title: d.title, firstButton: [].concat(d.actions || [])[0], body0: [].concat(d.body || [])[0] }).slice(0, 2500));
    const buttons = [...[].concat(d.actions || []), ...(d.yes ? [d.yes] : []), ...(d.no ? [d.no] : []), ...(d.exit_action ? [d.exit_action] : []), ...(d.action ? [d.action] : [])];
    console.log('DIALOG', JSON.stringify({ type: d.type, title: flat(d.title), body: [].concat(d.body || []).map(b => flat(b.contents) || (b.item ? 'item:' + (b.item.id) : '')).join(' | ').slice(0, 300),
      inputs: [].concat(d.inputs || []).map(i => i.key + ':' + i.type), buttons: buttons.map(b => flat(b.label)) }));
  } else if (meta.name === 'clear_dialog') console.log('CLEAR_DIALOG');
  else if (/effect/.test(meta.name)) console.log('EFFECT', meta.name, JSON.stringify(data));
  else if (meta.name === 'system_chat') { const m = flat(simplify(data.content)); if (m.trim()) console.log('CHAT', m.slice(0, 200)); }
});
bot._client.on('error', e => console.log('CLIENT_ERROR', e.message));
bot.on('kicked', r => { console.log('KICKED', JSON.stringify(r).slice(0, 300)); process.exit(1); });
bot.on('end', r => { console.log('END', r); });
function click(label, inputs) {
  if (!last) return console.log('NO DIALOG to click');
  const buttons = [...[].concat(last.actions || []), ...(last.yes ? [last.yes] : []), ...(last.no ? [last.no] : []), ...(last.exit_action ? [last.exit_action] : []), ...(last.action ? [last.action] : [])];
  const b = buttons.find(x => flat(x.label).includes(label));
  if (!b) return console.log('NO BUTTON', label, buttons.map(x => flat(x.label)));
  const a = b.action || {};
  if (!String(a.type).includes('custom')) return console.log('NOT CUSTOM', JSON.stringify(a).slice(0, 200));
  // payload = the button's static additions plus our inputs (the client merges inputs into the payload)
  const payload = Object.assign({}, a.additions || {}, inputs || {});
  const nbt = require('prismarine-nbt');
  const comp = {}; for (const k in payload) { const v = payload[k]; comp[k] = typeof v === 'string' ? nbt.string(v) : typeof v === 'boolean' ? nbt.byte(v ? 1 : 0) : (Number.isInteger(v) ? nbt.int(v) : (Array.isArray(v) ? nbt.intArray(v) : nbt.float(v))); }
  console.log('CLICK', label, a.id);
  const body = nbt.protos.big.createPacketBuffer('anonymousNbt', nbt.comp(comp));
  bot._client.write('custom_click_action', { id: a.id, nbt: body });
}
bot.on('windowOpen', w => { console.log('OPEN_WINDOW', JSON.stringify(w.title).slice(0, 150), w.slots.length);
  const rows = []; const n = w.inventoryStart; for (let r = 0; r < n / 9; r++) { rows.push(w.slots.slice(r * 9, r * 9 + 9).map(it => it ? (it.name.replace(/_stained_glass_pane/, '~').replace('player_head', 'HEAD').replace(/_banner/, 'BNR') + (it.count > 1 ? 'x' + it.count : '')) : '.').join(' ')); }
  console.log(rows.join('\n')); });
bot.once('spawn', async () => {
  console.log('SPAWNED');
  for (const s of steps) {
    if (s.chat) { console.log('> ' + s.chat); bot.chat(s.chat); }
    if (s.click) click(s.click, s.inputs);
    if (s.slot !== undefined) { const w = bot.currentWindow; console.log('WINDOW', w ? (w.title && JSON.stringify(w.title).slice(0, 120)) : 'none'); if (w) bot.clickWindow(s.slot, 0, 0).catch(e => console.log('CLICKERR', e.message)); }
    await new Promise(r => setTimeout(r, s.wait || 1500));
  }
  bot.quit(); setTimeout(() => process.exit(0), 500);
});
