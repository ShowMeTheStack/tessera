// Tessera Studio end-to-end specs in headless Chromium. Each spec starts from a clean browser on a
// running studio (started with --src examples/src/main/java) and asserts what the user would see.
//
//   node studio-suite.mjs <url> [out.csv] [filter]
//
// Prints one line per spec and writes spec, area, result, milliseconds and the failure (if any).
import { chromium } from 'playwright';
import { writeFileSync, readFileSync, existsSync } from 'node:fs';

const [url, outCsv, filter] = process.argv.slice(2);
const browser = await chromium.launch();
const specs = [];
const spec = (area, name, fn) => specs.push({ area, name, fn });
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });

class Fail extends Error {}
const expect = (cond, what) => { if (!cond) throw new Fail(what); };
const eq = (a, b, what) => { if (String(a) !== String(b)) throw new Fail(what + ': expected ' + b + ', got ' + a); };

async function open(doc, opts = {}) {
  const page = await browser.newPage({ viewport: { width: opts.w || 1600, height: opts.h || 1000 } });
  page.errors = [];
  page.on('pageerror', (e) => page.errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !(opts.allow || []).some((a) => m.text().includes(a)) && !/status of 4\d\d/.test(m.text())) page.errors.push(m.text()); });
  // a refused request is reported with what was asked and what the server said
  page.on('response', async (r) => {
    if (r.status() < 400 || (opts.allow || []).some((a) => String(r.status()).includes(a))) return;
    page.errors.push(r.status() + ' ' + r.url().replace(url, '/') + ' ' + (r.request().postData() || '') + ' → ' + (await r.text().catch(() => '')));
  });
  await page.goto(url);
  await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.canvas);
  await page.evaluate(([d, pal]) => { localStorage.clear(); if (d) localStorage.setItem('tstudio.doc', JSON.stringify(d)); if (pal) localStorage.setItem('tstudio.palOpen', JSON.stringify(pal)); }, [doc || null, opts.palette || null]);
  await page.reload();
  await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.gen && Studio.app.gen.java);
  await idle(page);
  // every spec starts its own run from cycle 0, with all inputs 0
  await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));
  await page.waitForTimeout(250);
  return page;
}
// wait until the last edit has been elaborated
async function idle(page, ms = 450) {
  await page.waitForTimeout(ms);
  await page.waitForFunction(() => { const a = Studio.app; return a.gen && (a.lastSyncDone === undefined || a.lastSyncDone); }, null, { timeout: 15000 }).catch(() => {});
}
const gen = (page) => page.evaluate(() => ({ problems: (Studio.app.gen.problems || []).map((p) => p.code + ': ' + p.message), java: Studio.app.gen.java }));
const val = (page, partName, pin) => page.evaluate(([n, pin]) => { const d = Studio.app.doc, p = d.d.parts.find((q) => q.params.name === n); const net = p && d.netOf(p.id, pin); const v = net && Studio.app.canvas.values.get(net); return v === undefined ? 'none' : v.toString(); }, [partName, pin]);
const sim = (page, cmd) => page.evaluate((c) => Studio.app.sim(c), cmd);

const COUNTER_FSM = {
  name: 'Hand', title: 't', package: 'studio', next: 100, notes: [],
  parts: [P('g', 'in', 2, 6, { name: 'go', width: 1 }), P('s', 'in', 2, 10, { name: 'stop', width: 1 }),
    P('f', 'fsm', 18, 4, { name: 'ctl', states: 'IDLE RUN DRAIN DONE', inputs: 'go stop', transitions: 'IDLE -> RUN : go\nRUN -> DRAIN : stop\nRUN -> RUN : go & !stop\nDRAIN -> DONE : !go\nDONE -> IDLE : 1', outputs: 'busy : RUN DRAIN' }),
    P('o1', 'out', 40, 6, { name: 'busy', width: 1 }), P('o3', 'out', 40, 14, { name: 'st', width: 2 })],
  nets: [{ id: 'w1', pins: [['g', 'y'], ['f', 'go']] }, { id: 'w2', pins: [['s', 'y'], ['f', 'stop']] }, { id: 'w3', pins: [['f', 'busy'], ['o1', 'a']] }, { id: 'w5', pins: [['f', 'state'], ['o3', 'a']] }],
};

// ---------------------------------------------------------------- start and drawing

spec('start', 'the counter sample elaborates with no problems', async () => {
  const page = await open();
  const g = await gen(page);
  eq(g.problems.length, 0, 'problems');
  expect(g.java.includes('public class Counter extends Module'), 'class');
  expect(g.java.includes('// tessera-studio:1 '), 'trailer');
  return page;
});

spec('start', 'the palette lists every category', async () => {
  const page = await open();
  const cats = await page.evaluate(() => [...document.querySelectorAll('.pal h3')].map((e) => e.textContent.replace(/\s*\d+$/, '').trim()));
  for (const c of ['Ports', 'Logic', 'Arithmetic', 'Select', 'Bits', 'State', 'Timing', 'Library', 'Verify', 'Modules', 'My blocks']) expect(cats.includes(c), 'category ' + c + ' in ' + cats.join(','));
  return page;
});

spec('drawing', 'a design built from parts and wires generates strict Java', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Adder')));
  await page.evaluate(() => {
    const d = Studio.app.doc, a = d.addPart('in', 2, 2, { name: 'a', width: 8 }), b = d.addPart('in', 2, 8, { name: 'b', width: 8 });
    const s = d.addPart('add', 14, 4, { mode: 'widen' }), y = d.addPart('out', 26, 5, { name: 'y', width: 9 });
    d.connect({ part: a.id, pin: 'y' }, { part: s.id, pin: 'a' }); d.connect({ part: b.id, pin: 'y' }, { part: s.id, pin: 'b' }); d.connect({ part: s.id, pin: 'y' }, { part: y.id, pin: 'a' });
  });
  await idle(page, 1500);
  const g = await gen(page);
  eq(g.problems.length, 0, 'problems ' + g.problems);
  expect(g.java.includes('UInt sum = a.add(b).named("sum");'), 'add line');
  await sim(page, { cmd: 'poke', path: 'a', value: 'ff' }); await sim(page, { cmd: 'poke', path: 'b', value: '1' }); await sim(page, { cmd: 'step' });
  await page.waitForTimeout(400);
  eq(await val(page, 'y', 'a'), 256, 'y = 0xff + 1 widened');
  return page;
});

spec('drawing', 'undo takes every edit back, redo brings it again', async () => {
  const page = await open();
  const n0 = await page.evaluate(() => Studio.app.doc.d.parts.length);
  await page.evaluate(() => { const d = Studio.app.doc; d.addPart('not', 30, 30); d.addPart('not', 34, 30); });
  eq(await page.evaluate(() => Studio.app.doc.d.parts.length), n0 + 2, 'added');
  await page.locator('.cv').focus();
  await page.keyboard.press('Meta+z'); await page.keyboard.press('Meta+z');
  eq(await page.evaluate(() => Studio.app.doc.d.parts.length), n0, 'after undo');
  await page.keyboard.press('Meta+Shift+z');
  eq(await page.evaluate(() => Studio.app.doc.d.parts.length), n0 + 1, 'after redo');
  return page;
});

spec('drawing', 'a width mismatch is fixed by its fix-it', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Mis')));
  await page.evaluate(() => {
    const d = Studio.app.doc, a = d.addPart('in', 2, 2, { name: 'a', width: 8 }), b = d.addPart('in', 2, 8, { name: 'b', width: 4 });
    const x = d.addPart('and', 14, 4), y = d.addPart('out', 26, 5, { name: 'y', width: 8 });
    d.connect({ part: a.id, pin: 'y' }, { part: x.id, pin: 'in0' }); d.connect({ part: b.id, pin: 'y' }, { part: x.id, pin: 'in1' }); d.connect({ part: x.id, pin: 'y' }, { part: y.id, pin: 'a' });
  });
  await idle(page, 1500);
  let g = await gen(page);
  expect(g.problems.some((p) => p.startsWith('width-mismatch')), 'mismatch reported: ' + g.problems);
  await page.locator('.tab[data-tab="problems"]').click();
  await page.locator('.prob .fixes button').first().click();
  await idle(page, 1500);
  g = await gen(page);
  eq(g.problems.length, 0, 'after the fix: ' + g.problems);
  return page;
});

spec('drawing', 'an open input is reported and tied to 0 by its fix', async () => {
  const page = await open();
  await page.evaluate(() => { const d = Studio.app.doc; const e = d.d.parts.find((p) => p.params.name === 'en'); d.remove([e.id]); });
  await idle(page, 1500);
  let g = await gen(page);
  expect(g.problems.some((p) => p.startsWith('open-input')), 'open input: ' + g.problems);
  await page.locator('.tab[data-tab="problems"]').click();
  await page.locator('.prob .fixes button', { hasText: 'Tie it to 0' }).first().click();
  await idle(page, 1500);
  eq((await gen(page)).problems.length, 0, 'after tie');
  return page;
});

spec('drawing', 'a combinational loop is a problem, not a hang', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Loop')));
  await page.evaluate(() => { const d = Studio.app.doc, n = d.addPart('not', 10, 4), y = d.addPart('out', 20, 4, { name: 'y', width: 1 }); d.connect({ part: n.id, pin: 'y' }, { part: n.id, pin: 'a' }); d.connect({ part: n.id, pin: 'y' }, { part: y.id, pin: 'a' }); });
  await idle(page, 1500);
  expect((await gen(page)).problems.some((p) => p.startsWith('comb-loop')), 'comb-loop');
  return page;
});

spec('drawing', 'copy and paste duplicates parts with their wires', async () => {
  const page = await open();
  const before = await page.evaluate(() => [Studio.app.doc.d.parts.length, Studio.app.doc.d.nets.length]);
  await page.evaluate(() => { const d = Studio.app.doc; const ids = d.d.parts.filter((p) => ['add', 'const'].includes(p.type)).map((p) => 'p:' + p.id); Studio.app.canvas.selectOnly(ids); });
  await page.locator('.cv').focus();
  await page.keyboard.press('Meta+c'); await page.keyboard.press('Meta+v');
  const after = await page.evaluate(() => [Studio.app.doc.d.parts.length, Studio.app.doc.d.nets.length]);
  eq(after[0], before[0] + 2, 'parts'); eq(after[1], before[1] + 1, 'the wire between them');
  return page;
});

spec('drawing', 'keyboard wiring connects two pins with keys only', async () => {
  const page = await open();
  const id = await page.evaluate(() => { const r = Studio.app.doc.d.parts.find((p) => p.type === 'reg'); const p = Studio.app.doc.addPart('out', r.x + 16, r.y + 8, { name: 'copy', width: 8 }); Studio.app.canvas.selectOnly(['p:' + r.id]); return p.id; });
  await page.locator('.cv').focus();
  for (const k of ['Tab', 'Tab', 'Tab', 'Enter', 'ArrowRight', 'ArrowDown', 'Enter']) await page.keyboard.press(k);
  await idle(page, 1500);
  expect(await page.evaluate((id) => !!Studio.app.doc.netOf(id, 'a'), id), 'wired');
  expect((await gen(page)).java.includes('output("copy", UInt.w(8)).set(count);'), 'java');
  expect((await page.evaluate(() => document.querySelector('.sr-only').textContent)).startsWith('Connected'), 'announced');
  return page;
});

spec('drawing', 'a manual bend routes the wire through it, with no spur', async () => {
  const page = await open();
  const net = await page.evaluate(() => { const d = Studio.app.doc.d; return d.nets.find((x) => x.pins.some(([p, q]) => q === 'en' && d.parts.find((pp) => pp.id === p).type === 'reg')).id; });
  await page.evaluate((id) => Studio.app.doc.setVia(id, [[17, 14]]), net);
  await page.waitForTimeout(2000);
  const r = await page.evaluate((id) => Studio.app.canvas.routes[id], net);
  expect(!r.failed, 'routed'); eq(r.dots.length, 0, 'junctions');
  expect(r.segs.some(([a, b, c, e]) => (a === 17 && b === 14) || (c === 17 && e === 14)), 'passes the bend');
  return page;
});

spec('drawing', 'tidy lays the drawing out and keeps it free of failed wires', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.tidy());
  await page.waitForTimeout(2500);
  const failed = await page.evaluate(() => Object.values(Studio.app.canvas.routes).filter((r) => r.failed).length);
  eq(failed, 0, 'failed wires');
  return page;
});

// ---------------------------------------------------------------- hierarchy and parts

spec('hierarchy', 'a block is made, opened, edited, and every use follows', async () => {
  const page = await open();
  await page.evaluate(() => { const ids = Studio.app.doc.d.parts.filter((p) => ['add', 'const'].includes(p.type)).map((p) => 'p:' + p.id); Studio.app.canvas.selectOnly(ids); });
  await page.locator('.cv').focus();
  await page.keyboard.press('b');
  await page.locator('.modal input').fill('Inc');
  await page.keyboard.press('Enter');
  await idle(page, 2000);
  let g = await gen(page);
  eq(g.problems.length, 0, 'problems ' + g.problems);
  expect(g.java.includes('public static class Inc extends Module'), 'nested class');
  await page.evaluate(() => Studio.app.openBlock(Studio.app.doc.d.parts.find((p) => p.type === 'block')));
  eq(await page.evaluate(() => Studio.app.doc.level()), 'Inc', 'inside');
  // edit inside: the constant becomes 2
  await page.evaluate(() => { const c = Studio.app.doc.d.parts.find((p) => p.type === 'const'); Studio.app.setParams(c.id, { value: '2' }); });
  await idle(page, 1500);
  expect((await gen(page)).java.includes('UInt.lit(2, 8)'), 'edited');
  await page.keyboard.press('Escape'); await page.keyboard.press('Escape');
  eq(await page.evaluate(() => Studio.app.doc.level()), 'null', 'back at the top');
  return page;
});

spec('parts', 'memory: a ROM is looked up and a RAM reads back what was written', async () => {
  const doc = { name: 'M', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'addr', width: 2 }), P('rom', 'mem', 12, 2, { name: 'rom', width: 8, depth: 4, write: false, init: '0x11 0x22 0x33 0x44' }), P('o', 'out', 30, 2, { name: 'q', width: 8 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['rom', 'addr']] }, { id: 'n2', pins: [['rom', 'rdata'], ['o', 'a']] }] };
  const page = await open(doc);
  eq((await gen(page)).problems.length, 0, 'problems');
  await sim(page, { cmd: 'poke', path: 'addr', value: '2' }); await sim(page, { cmd: 'step' });
  await page.waitForTimeout(400);
  eq(await val(page, 'q', 'a'), 0x33, 'rom[2]');
  return page;
});

spec('parts', 'a memory\'s contents follow the run in the inspector', async () => {
  const doc = { name: 'R', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'addr', width: 4 }), P('d', 'in', 2, 6, { name: 'wdata', width: 8 }), P('e', 'in', 2, 10, { name: 'we', width: 1 }),
      P('m', 'mem', 16, 2, { name: 'ram', width: 8, depth: 16, init: '0x10 0x20' }), P('o', 'out', 34, 4, { name: 'q', width: 8 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['m', 'addr']] }, { id: 'n2', pins: [['d', 'y'], ['m', 'wdata']] }, { id: 'n3', pins: [['e', 'y'], ['m', 'we']] }, { id: 'n4', pins: [['m', 'rdata'], ['o', 'a']] }] };
  const page = await open(doc);
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:m']));
  await page.waitForFunction(() => document.querySelectorAll('.memgrid .mw').length === 16, null, { timeout: 5000 });
  eq(await page.evaluate(() => [...document.querySelectorAll('.memgrid .mw')].slice(0, 3).map((e) => e.textContent).join(' ')), '10 20 00', 'initial words');
  for (const [k, v] of [['addr', '5'], ['wdata', 'ab'], ['we', '1']]) await sim(page, { cmd: 'poke', path: k, value: v });
  await sim(page, { cmd: 'step' }); await sim(page, { cmd: 'poke', path: 'we', value: '0' }); await sim(page, { cmd: 'step' });
  await page.waitForFunction(() => document.querySelectorAll('.memgrid .mw')[5].textContent === 'ab', null, { timeout: 5000 });
  expect(await page.evaluate(() => document.querySelectorAll('.memgrid .mw')[5].classList.contains('chg')), 'the changed word is lit');
  // the past: the word was not written yet two cycles ago
  await page.evaluate(() => Studio.app.goto(Studio.app.head - 2));
  await page.waitForFunction(() => document.querySelectorAll('.memgrid .mw')[5].textContent === '00', null, { timeout: 5000 });
  return page;
});

spec('parts', 'truth table: the stdlib TruthTable, edited in its grid', async () => {
  const doc = { name: 'T', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'a', width: 1 }), P('b', 'in', 2, 6, { name: 'b', width: 1 }), P('t', 'table', 12, 2, { inputs: 'a b', outputs: 'y', rows: '0 1 | 1\n1 0 | 1' }), P('o', 'out', 26, 2, { name: 'x', width: 1 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['t', 'a']] }, { id: 'n2', pins: [['b', 'y'], ['t', 'b']] }, { id: 'n3', pins: [['t', 'y'], ['o', 'a']] }] };
  const page = await open(doc);
  expect((await gen(page)).java.includes('TruthTable.parse('), 'TruthTable');
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:t']));
  await page.waitForTimeout(300);
  await page.locator('button.tt-bit').nth(3).click();      // row 11 becomes 1: an OR
  await idle(page, 1500);
  eq(await page.evaluate(() => Studio.app.doc.part('t').params.rows), '0 1 | 1\n1 0 | 1\n1 1 | 1', 'rows');
  await sim(page, { cmd: 'poke', path: 'a', value: '1' }); await sim(page, { cmd: 'poke', path: 'b', value: '1' }); await sim(page, { cmd: 'step' });
  await page.waitForTimeout(400);
  eq(await val(page, 'x', 'a'), 1, 'a|b');
  return page;
});

spec('parts', 'state machine: the first listed transition wins, and the diagram follows', async () => {
  const page = await open(COUNTER_FSM);
  expect((await gen(page)).java.includes('HwEnum<CtlState> ctl'), 'enum register');
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:f']));
  await sim(page, { cmd: 'poke', path: 'go', value: '1' }); await sim(page, { cmd: 'step' });
  await page.waitForTimeout(500);
  eq(await page.evaluate(() => document.querySelector('.fsm-s.now text').textContent), 'RUN', 'lit state');
  await sim(page, { cmd: 'poke', path: 'stop', value: '1' }); await page.waitForTimeout(400);
  eq(await page.evaluate(() => [...document.querySelectorAll('.fsm-t.next')].map((e) => e.getAttribute('data-t')).join()), '1', 'next transition is RUN -> DRAIN');
  await sim(page, { cmd: 'step' }); await page.waitForTimeout(400);
  eq(await page.evaluate(() => document.querySelector('.cv [data-fsmval]').textContent), 'DRAIN', 'symbol');
  eq(await page.evaluate(() => [...document.querySelectorAll('.vtag.enum')].map((e) => e.textContent).join()), 'DRAIN', 'state tag');
  return page;
});

spec('parts', 'library parts: a button through an edge detector counts once per press', async () => {
  const doc = { name: 'L', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('b', 'in', 2, 10, { name: 'btn', width: 1 }), P('e', 'edge', 12, 10, { kind: 'rising' }), P('c', 'counter', 20, 10, { max: 15 }), P('h', 'out', 36, 10, { name: 'n', width: 4 })],
    nets: [{ id: 'w2', pins: [['b', 'y'], ['e', 'a']] }, { id: 'w3', pins: [['e', 'y'], ['c', 'inc']] }, { id: 'w4', pins: [['c', 'value'], ['h', 'a']] }] };
  const page = await open(doc);
  for (let k = 0; k < 3; k++) { await sim(page, { cmd: 'poke', path: 'btn', value: '1' }); await sim(page, { cmd: 'step', n: 4 }); await sim(page, { cmd: 'poke', path: 'btn', value: '0' }); await sim(page, { cmd: 'step', n: 4 }); }
  await page.waitForTimeout(400);
  eq(await val(page, 'n', 'a'), 3, 'presses');
  return page;
});

spec('parts', 'a FIFO from the standard library keeps order and shows backpressure', async () => {
  const page = await open(null, { palette: ['Modules'] });
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Q')));
  await page.locator('.card[data-type="module:0"]').click();
  await page.waitForFunction(() => Studio.app.doc.d.parts.some((p) => p.type === 'module'), null, { timeout: 15000 });
  // this spec wires the FIFO pin by pin: its streams are shown as separate wires
  await page.evaluate(() => { const f = Studio.app.doc.d.parts.find((q) => q.type === 'module'); Studio.app.setParams(f.id, { bundle: false }); });
  await page.evaluate(() => {
    const doc = Studio.app.doc, f = doc.d.parts.find((q) => q.type === 'module'), g = Studio.Geom.of(f); let k = 0;
    for (const q of g.pins) { const w = { in_bits: 8, out_bits: 8, count: 5 }[q.name] || 1; const p = doc.addPart(q.dir === 'in' ? 'in' : 'out', q.dir === 'in' ? f.x - 14 : f.x + g.w + 6, f.y + (k++) * 3 - 4, { name: q.name, width: w }); doc.connect({ part: p.id, pin: q.dir === 'in' ? 'y' : 'a' }, { part: f.id, pin: q.name }); }
  });
  await idle(page, 2500);
  eq((await gen(page)).problems.length, 0, 'problems');
  await sim(page, { cmd: 'poke', path: 'in_valid', value: '1' });
  for (const v of ['11', '22', '33']) { await sim(page, { cmd: 'poke', path: 'in_bits', value: v }); await sim(page, { cmd: 'step' }); }
  await sim(page, { cmd: 'poke', path: 'in_valid', value: '0' }); await page.waitForTimeout(500);
  eq(await val(page, 'count', 'a'), 3, 'count');
  eq(await val(page, 'out_bits', 'a'), 0x11, 'head is the first in');
  const stall = await page.evaluate(() => { const d = Studio.app.doc, f = d.d.parts.find((q) => q.type === 'module'); return document.querySelector(`.cv .wire[data-net="${d.netOf(f.id, 'out_valid')}"]`).classList.contains('stall'); });
  expect(stall, 'out stream is stalled (valid, not ready)');
  return page;
});

spec('parts', 'a stream wire joins two FIFOs (valid and bits forward, ready back) and shows transfers', async () => {
  const page = await open(null, { palette: ['Modules'] });
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Chain')));
  for (let k = 0; k < 2; k++) {
    await page.locator('.card[data-type="module:0"]').click();
    await page.waitForFunction((k) => Studio.app.doc.d.parts.filter((p) => p.type === 'module').length === k + 1, k, { timeout: 15000 });
  }
  const ids = await page.evaluate(() => Studio.app.doc.d.parts.filter((p) => p.type === 'module').map((p) => p.id));
  eq(await page.evaluate((id) => Studio.app.doc.part(id).pins.map((q) => q.name).join(','), ids[0]), 'in,out,count', 'bundled pins');
  // the outer streams as separate wires, through the inspector
  for (const [id, x] of [[ids[0], 'in'], [ids[1], 'out']]) {
    await page.evaluate((id) => Studio.app.canvas.selectOnly(['p:' + id]), id);
    await page.locator('label.check', { hasText: x + ' as one stream pin' }).locator('input').uncheck();
    await page.waitForTimeout(300);
  }
  await page.evaluate(([a, b]) => {
    const doc = Studio.app.doc, f1 = doc.part(a), f2 = doc.part(b);
    doc.movePartsBy([b], 30, 0);
    doc.connect({ part: a, pin: 'out' }, { part: b, pin: 'in' });
    const port = (type, name, w, x, y, pid, pin) => { const p = doc.addPart(type, x, y, { name, width: w }); doc.connect({ part: p.id, pin: type === 'in' ? 'y' : 'a' }, { part: pid, pin }); };
    port('in', 'push', 1, f1.x - 16, f1.y, a, 'in_valid'); port('in', 'data', 8, f1.x - 16, f1.y + 4, a, 'in_bits'); port('out', 'room', 1, f1.x - 16, f1.y + 8, a, 'in_ready');
    port('in', 'pop', 1, f2.x + 20, f2.y + 6, b, 'out_ready'); port('out', 'head', 8, f2.x + 20, f2.y, b, 'out_bits'); port('out', 'have', 1, f2.x + 20, f2.y + 3, b, 'out_valid');
  }, ids);
  await idle(page, 3000);
  eq((await gen(page)).problems.length, 0, 'problems ' + (await gen(page)).problems);
  expect((await gen(page)).java.includes('.in.valid().set('), 'stream assignments');
  await sim(page, { cmd: 'poke', path: 'push', value: '1' });
  await sim(page, { cmd: 'poke', path: 'data', value: '2a' });
  await sim(page, { cmd: 'step' }); await page.waitForTimeout(500);
  const tag = await page.evaluate(() => [...document.querySelectorAll('.vtag')].map((e) => e.textContent).find((t) => /[✓⏸]/.test(t)));
  expect(tag === '2a ✓' || tag === '2A ✓' || /✓$/.test(tag || ''), 'stream tag shows a transfer: ' + tag);
  if (process.env.SHOTS) { await page.evaluate(() => Studio.app.canvas.fit()); await page.waitForTimeout(1500); await page.screenshot({ path: process.env.SHOTS + '/stream.png' }); }
  await sim(page, { cmd: 'poke', path: 'push', value: '0' }); await sim(page, { cmd: 'step', n: 3 }); await page.waitForTimeout(500);
  eq(await val(page, 'head', 'a'), 0x2a, 'through both FIFOs');
  return page;
});

spec('parts', 'a project module (examples Counter) is placed and runs', async () => {
  const page = await open(null, { palette: ['Modules'] });
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('UsesCounter')));
  const i = await page.evaluate(() => Studio.app.modules.findIndex((m) => m.title === 'Counter'));
  expect(i >= 0, 'Counter listed');
  await page.locator(`.card[data-type="module:${i}"]`).click();
  await page.waitForFunction(() => Studio.app.doc.d.parts.some((p) => p.type === 'module'), null, { timeout: 15000 });
  await page.evaluate(() => { const d = Studio.app.doc, f = d.d.parts[0]; const e = d.addPart('in', f.x - 14, f.y + 2, { name: 'go', width: 1 }), o = d.addPart('out', f.x + 18, f.y + 2, { name: 'n', width: 8 }); d.connect({ part: e.id, pin: 'y' }, { part: f.id, pin: 'en' }); d.connect({ part: f.id, pin: 'count' }, { part: o.id, pin: 'a' }); });
  await idle(page, 2500);
  eq((await gen(page)).problems.length, 0, 'problems');
  await sim(page, { cmd: 'poke', path: 'go', value: '1' }); await sim(page, { cmd: 'step', n: 5 }); await page.waitForTimeout(400);
  eq(await val(page, 'n', 'a'), 5, 'counted');
  return page;
});

spec('parts', 'a Java code part: a compile error has its line; the class runs', async () => {
  const page = await open(null, { palette: ['Modules'] });
  await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Hand')));
  await page.locator('.card[data-type="code:new"]').click();
  await page.waitForFunction(() => Studio.app.doc.d.parts.some((p) => p.type === 'code'), null, { timeout: 15000 });
  const src = 'public static class Ones extends Module {\n    public final UInt a;\n    public final UInt n;\n\n    public Ones() {\n        a = input("a", UInt.w(8));\n        n = output("n", UInt.w(4));\n        UInt acc = UInt.lit(0, 4);\n        for (int i = 0; i < 8; i++) acc = acc.addWrap(a.bit(i).zext(4));\n        n.set(acc);\n    }\n}';
  await page.locator('.part-edbox .code-ed').fill(src.replace('a.bit(i)', 'a.bitt(i)'));
  await page.locator('.part-edbox .code-ed').press('Meta+Enter');
  await page.waitForFunction(() => /line \d+/.test(document.querySelector('.part-edbox .err').textContent), null, { timeout: 15000 });
  expect((await page.evaluate(() => document.querySelector('.part-edbox .err').textContent)).includes('line 9'), 'line 9');
  await page.locator('.part-edbox .code-ed').fill(src);
  await page.locator('.part-edbox .code-ed').press('Meta+Enter');
  await page.waitForFunction(() => Studio.app.doc.d.parts[0].params.cls === 'Ones', null, { timeout: 15000 });
  await page.evaluate(() => { const d = Studio.app.doc, c = d.d.parts[0]; const i = d.addPart('in', c.x - 14, c.y + 2, { name: 'x', width: 8 }), o = d.addPart('out', c.x + 16, c.y + 2, { name: 'cnt', width: 4 }); d.connect({ part: i.id, pin: 'y' }, { part: c.id, pin: 'a' }); d.connect({ part: c.id, pin: 'n' }, { part: o.id, pin: 'a' }); });
  await idle(page, 2500);
  await sim(page, { cmd: 'poke', path: 'x', value: 'b7' }); await sim(page, { cmd: 'step' }); await page.waitForTimeout(400);
  eq(await val(page, 'cnt', 'a'), 6, 'popcount(0xb7)');
  return page;
});

// ---------------------------------------------------------------- simulation

spec('simulate', 'clicking an input toggles it while running', async () => {
  const page = await open();
  await sim(page, { cmd: 'run' });
  const en = await page.evaluate(() => Studio.app.doc.d.parts.find((p) => p.params.name === 'en').id);
  const box = await page.locator(`.cv .part[data-id="${en}"]`).boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(700);
  eq(await val(page, 'en', 'y'), 1, 'en');
  return page;
});

spec('simulate', 'a register shows its next value and hands over on the edge', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await page.waitForTimeout(500);
  const rv = () => page.evaluate(() => [document.querySelector('.cv [data-regval]').textContent, document.querySelector('.cv [data-regnext]').textContent].join('|'));
  eq(await rv(), '0x00|next 0x01', 'before');
  await sim(page, { cmd: 'step' }); await page.waitForTimeout(100);
  eq(await rv(), '0x01|next 0x02', 'after the edge');
  return page;
});

spec('simulate', 'an edit hot-swaps the design and the register keeps its value', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'step', n: 7 }); await page.waitForTimeout(300);
  await page.evaluate(() => { const c = Studio.app.doc.d.parts.find((p) => p.type === 'const'); Studio.app.setParams(c.id, { value: '2' }); });
  await idle(page, 2000);
  eq(await page.evaluate(() => document.querySelector('.cv [data-regval]').textContent), '0x07', 'kept');
  await sim(page, { cmd: 'step' }); await page.waitForTimeout(300);
  eq(await page.evaluate(() => document.querySelector('.cv [data-regval]').textContent), '0x09', 'now counts by 2');
  expect(await page.evaluate(() => Studio.app.waves.marks.some((m) => /reloaded/.test(m.text))), 'reload marked');
  return page;
});

spec('simulate', 'a stimulus lane drives an input with a pattern over the coming cycles', async () => {
  const page = await open();
  await page.waitForFunction(() => document.querySelector('.wv-name .wv-pat'), null, { timeout: 5000 });
  await page.locator('.wv-name', { hasText: 'en' }).first().locator('.wv-pat').click();
  const ins = page.locator('.modal input');
  await ins.nth(1).fill('1 0'); await ins.nth(2).fill('5'); await ins.nth(3).fill('3');
  await page.locator('.modal button', { hasText: 'Apply' }).click();
  await page.waitForTimeout(500);
  const c0 = await page.evaluate(() => Studio.app.cycle);
  await sim(page, { cmd: 'step', n: 32 }); await page.waitForTimeout(500);
  eq(await page.evaluate(() => parseInt(document.querySelector('.cv [data-regval]').textContent, 16)), 15, 'count after 3 × (5 on, 5 off) from cycle ' + c0);
  return page;
});

spec('simulate', 'run until a condition stops at the cycle it holds', async () => {
  const page = await open();
  await sim(page, { cmd: 'rate', rate: 1000 }); await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  const c0 = await page.evaluate(() => Studio.app.cycle);
  await sim(page, { cmd: 'until', expr: 'count == 0x2a' });
  await page.waitForFunction(() => !Studio.app.running && /holds/.test(Studio.app.message || ''), null, { timeout: 15000 });
  eq(await page.evaluate(() => document.querySelector('.cv [data-regval]').textContent), '0x2a', 'value');
  expect((await page.evaluate(() => Studio.app.cycle)) >= c0 + 42, 'ran far enough');
  return page;
});

spec('simulate', 'run until refuses an unknown signal with a sentence', async () => {
  const page = await open(null, { allow: ['400'] });
  const msg = await page.evaluate(async () => { try { await Studio.Api.call('sim', { cmd: 'until', expr: 'cnt == 3' }); return 'accepted'; } catch (e) { return e.message; } });
  eq(msg, 'There is no signal cnt in this design.', 'message');
  return page;
});

spec('simulate', 'an assertion that fails while running stops the run and says so', async () => {
  const page = await open();
  await page.evaluate(() => {
    const doc = Studio.app.doc, r = doc.d.parts.find((p) => p.type === 'reg');
    const k = doc.addPart('const', r.x + 2, r.y + 12, { value: '3', width: 8 }), c = doc.addPart('cmp', r.x + 12, r.y + 10, { op: 'lt' }), a = doc.addPart('assert', r.x + 22, r.y + 11, { name: 'small' });
    doc.connect({ part: r.id, pin: 'q' }, { part: c.id, pin: 'a' }); doc.connect({ part: k.id, pin: 'y' }, { part: c.id, pin: 'b' }); doc.connect({ part: c.id, pin: 'y' }, { part: a.id, pin: 'cond' });
  });
  await idle(page, 2500);
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'rate', rate: 100 }); await sim(page, { cmd: 'run' });
  await page.waitForFunction(() => !Studio.app.running && /Stopped at cycle/.test(Studio.app.message || ''), null, { timeout: 15000 });
  // the waves learn of marks when they next fetch their window
  const marked = await page.waitForFunction(() => Studio.app.waves.marks.some((m) => m.kind === 'fail'), null, { timeout: 5000 }).then(() => true).catch(() => false);
  expect(marked, 'failure marked in the waves: ' + JSON.stringify(await page.evaluate(() => Studio.app.waves.marks.slice(-3))));
  return page;
});

spec('simulate', 'the board drives switches and shows LEDs and a hex digit', async () => {
  const doc = { name: 'Panel', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('s', 'in', 2, 2, { name: 'sw', width: 4 }), P('l', 'out', 30, 2, { name: 'led', width: 4 }), P('h', 'out', 30, 8, { name: 'hex0', width: 4 })],
    nets: [{ id: 'w1', pins: [['s', 'y'], ['l', 'a'], ['h', 'a']] }] };
  const page = await open(doc);
  await page.locator('.tab[data-tab="board"]').click();
  await sim(page, { cmd: 'run' });
  await page.locator('.board .sw[data-bit="0"]').click(); await page.locator('.board .sw[data-bit="1"]').click();
  await page.waitForTimeout(700);
  eq(await page.evaluate(() => [...document.querySelectorAll('.board .led.on')].map((e) => e.dataset.bit).sort().join()), '0,1', 'leds');
  eq(await page.evaluate(() => document.querySelectorAll('.board .seg7 path.on').length), 5, 'digit 3 has 5 segments');
  return page;
});

// ---------------------------------------------------------------- waves and time travel

spec('waves', 'clicking the waves moves the whole tool to that cycle', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'step', n: 40 }); await page.waitForTimeout(800);
  const head = await page.evaluate(() => Studio.app.head);
  await page.evaluate(() => { Studio.app.waves.t0 = 0; Studio.app.waves.t1 = Studio.app.head; Studio.app.waves.follow = false; return Studio.app.waves.fetch(); });
  const plot = await page.locator('.wv-plot').boundingBox();
  await page.mouse.click(plot.x + plot.width * 0.5, plot.y + 40);
  await page.waitForTimeout(800);
  const c = await page.evaluate(() => Studio.app.cycle);
  expect(c < head - 5, 'went back: cycle ' + c + ' of ' + head);
  const shown = await page.evaluate(() => parseInt(document.querySelector('.cv [data-regval]').textContent, 16));
  const atA = await page.evaluate((c) => Studio.Api.call('waves', { paths: ['count'], from: 0, to: c, columns: 1e9 }).then((r) => parseInt(r.waves.count.changes.filter((x) => x[0] <= c).at(-1)[1], 16)), c);
  eq(shown, atA, 'canvas value equals the record at the cursor');
  return page;
});

spec('waves', 'rows group, expand into bits and take markers', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'step', n: 20 }); await page.waitForTimeout(800);
  const names = page.locator('.wv-name');
  await names.nth(1).click(); await names.nth(2).click({ modifiers: ['Shift'] });
  await page.locator('.wv-plot').focus(); await page.keyboard.press('g');
  await page.locator('.modal input').fill('grp'); await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  expect(await page.evaluate(() => [...document.querySelectorAll('.wv-group')].some((e) => e.textContent.includes('grp'))), 'group header');
  await page.locator('.wv-name', { hasText: 'count' }).locator('.wv-exp').click();
  await page.waitForTimeout(300);
  eq(await page.evaluate(() => document.querySelectorAll('.wv-name.bit').length), 8, 'bits');
  const plot = await page.locator('.wv-plot').boundingBox();
  await page.mouse.click(plot.x + plot.width * 0.4, plot.y + 40);
  await page.locator('.wv-plot').focus(); await page.keyboard.press('m'); await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  eq(await page.evaluate(() => Studio.app.waves.markers.length), 1, 'marker');
  return page;
});

spec('waves', 'arrows go to the previous and next change of the selected row', async () => {
  const page = await open();
  for (const c of [{ cmd: 'poke', path: 'en', value: '1' }, { cmd: 'step', n: 10 }, { cmd: 'poke', path: 'en', value: '0' }, { cmd: 'step', n: 10 }]) await sim(page, c);
  await page.waitForTimeout(800);
  await page.evaluate(() => { const w = Studio.app.waves; w.t0 = 0; w.t1 = Studio.app.head; w.follow = false; w.cursor = 0; return w.fetch(); });
  await page.locator('.wv-name', { hasText: 'en' }).first().click();
  await page.locator('.wv-plot').focus();
  await page.keyboard.press('ArrowRight'); await page.waitForTimeout(300);
  const c1 = await page.evaluate(() => Studio.app.waves.cursor);
  await page.keyboard.press('ArrowRight'); await page.waitForTimeout(300);
  const c2 = await page.evaluate(() => Studio.app.waves.cursor);
  const ch = await page.evaluate(() => Studio.Api.call('waves', { paths: ['en'], from: 0, to: Studio.app.head, columns: 1e9 }).then((r) => JSON.stringify(r.waves.en.changes.slice(-4))));
  expect(c2 - c1 >= 9 && c2 - c1 <= 11, 'en was 1 for ten cycles: ' + c1 + ' → ' + c2 + ' changes ' + ch);
  return page;
});

spec('waves', 'measure: the period and duty of a bit, the values of a bus', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'step', n: 40 }); await page.waitForTimeout(600);
  const r = await page.evaluate(async () => {
    const w = Studio.app.waves, head = Studio.app.head;
    const count = w.rows.find((x) => x.path === 'count' && x.bit == null);
    w.toggleBits(count);
    const b0 = w.rows.find((x) => x.path === 'count' && x.bit === 0);
    w.cursor = head - 30; w.cursorB = head - 10;
    w.sel.clear(); w.sel.add(w.key(b0));
    const bit = await w.measure();
    w.sel.clear(); w.sel.add(w.key(count));
    const bus = await w.measure();
    return [bit, bus];
  });
  expect(/period 2 cycles/.test(r[0]) && /high 50\.0%/.test(r[0]), 'bit: ' + r[0]);
  expect(/20 changes, 2[01] distinct values/.test(r[1]), 'bus: ' + r[1]);
  return page;
});

spec('waves', 'find a condition in the record (⌘F)', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'step', n: 30 }); await page.waitForTimeout(600);
  await page.evaluate(() => { Studio.app.waves.cursor = 0; });
  await page.locator('.wv-plot').focus(); await page.keyboard.press('Meta+f');
  await page.locator('.modal input').fill('count == 16'); await page.keyboard.press('Enter');
  await page.waitForTimeout(800);
  eq(await page.evaluate(() => document.querySelector('.cv [data-regval]').textContent), '0x10', 'canvas at the found cycle');
  return page;
});

spec('waves', 'a million cycles: the whole-run window draws in under 100 ms', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'rate', rate: 0 }); await sim(page, { cmd: 'until', n: 1000000 });
  await page.waitForFunction(() => Studio.app.head >= 1000000 && !Studio.app.running, null, { timeout: 60000, polling: 300 });
  const ms = await page.evaluate(async () => { const w = Studio.app.waves, t = performance.now(); w.t0 = 0; w.t1 = w.head; w.follow = false; await w.fetch(); return performance.now() - t; });
  expect(ms < 100, 'fetch+draw ' + Math.round(ms) + ' ms');
  page.measure = 'whole-run window of ' + (await page.evaluate(() => Studio.app.head)) + ' cycles drawn in ' + Math.round(ms) + ' ms';
  return page;
});

spec('waves', 'changing an input in the past forks a new future', async () => {
  const page = await open();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' }); await sim(page, { cmd: 'step', n: 20 }); await page.waitForTimeout(500);
  const head = await page.evaluate(() => Studio.app.head);
  await page.evaluate((c) => Studio.app.goto(c), head - 10); await page.waitForTimeout(500);
  await sim(page, { cmd: 'poke', path: 'en', value: '0' }); await page.waitForTimeout(600);
  expect(await page.evaluate(() => Studio.app.waves.marks.some((m) => m.kind === 'fork')), 'fork marked');
  expect((await page.evaluate(() => Studio.app.head)) <= head - 9, 'later record dropped');
  return page;
});

// ---------------------------------------------------------------- prove, analyse, code

spec('prove', 'BMC finds the shortest counterexample and it replays', async () => {
  const page = await open();
  await page.evaluate(() => {
    const doc = Studio.app.doc, r = doc.d.parts.find((p) => p.type === 'reg');
    const k = doc.addPart('const', r.x + 2, r.y + 12, { value: '5', width: 8 }), c = doc.addPart('cmp', r.x + 12, r.y + 10, { op: 'lt' }), a = doc.addPart('assert', r.x + 22, r.y + 11, { name: 'below_five' });
    doc.connect({ part: r.id, pin: 'q' }, { part: c.id, pin: 'a' }); doc.connect({ part: k.id, pin: 'y' }, { part: c.id, pin: 'b' }); doc.connect({ part: c.id, pin: 'y' }, { part: a.id, pin: 'cond' });
  });
  await idle(page, 2500);
  await page.locator('.tab[data-tab="prove"]').click();
  await page.locator('button', { hasText: 'Bounded' }).click();
  await page.waitForFunction(() => /FAIL/.test(document.querySelector('.prove-log').textContent), null, { timeout: 30000 });
  const t = await page.evaluate(() => document.querySelector('.prove-log').textContent);
  expect(/fails at\s+cycle 6/.test(t), 'cycle 6: ' + t);
  expect(await page.evaluate(() => Studio.app.waves.marks.some((m) => m.kind === 'trace')), 'counterexample in the waves');
  return page;
});

spec('prove', 'k-induction proves a property that holds for all time', async () => {
  const doc = { name: 'Inv', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'a', width: 8 }), P('x', 'xor', 12, 2), P('c', 'cmp', 22, 2, { op: 'eq' }), P('k', 'assert', 32, 2, { name: 'self' })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['x', 'in0'], ['x', 'in1'], ['c', 'b']] }, { id: 'n2', pins: [['x', 'y'], ['c', 'a']] }, { id: 'n3', pins: [['c', 'y'], ['k', 'cond']] }] };
  // a xor a == a holds only for a == 0: the prover must find the counterexample, never "proven"
  const page = await open(doc);
  await page.locator('.tab[data-tab="prove"]').click();
  await page.locator('button', { hasText: 'Induction' }).click();
  await page.waitForFunction(() => /FAIL|PROVEN|UNKNOWN/.test(document.querySelector('.prove-log').textContent), null, { timeout: 30000 });
  expect(/FAIL/.test(await page.evaluate(() => document.querySelector('.prove-log').textContent)), 'a^a == a is false for a != 0');
  return page;
});

spec('analyse', 'logic levels and a Yosys estimate for the counter', async () => {
  const page = await open();
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('button', { hasText: 'Logic levels' }).click();
  await page.waitForSelector('.an-path');
  eq(await page.evaluate(() => document.querySelector('.an-path .an-n').textContent), 2, 'levels to count');
  await page.locator('button', { hasText: 'Estimate resources' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.an-cell').length || /Yosys/.test(document.querySelector('.an-cells, .insp')?.textContent || ''), null, { timeout: 90000 });
  const ffs = await page.evaluate(() => { const c = [...document.querySelectorAll('.an-cell')].find((e) => /flip-flops/.test(e.textContent)); return c ? c.querySelector('b').textContent : 'no yosys'; });
  expect(ffs === '8' || ffs === 'no yosys', 'flip-flops ' + ffs);
  return page;
});

spec('analyse', 'a logic-levels path of a core lights its signals in the explorer (by name, not as part ids)', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('button', { hasText: 'Logic levels' }).click();
  await page.waitForSelector('.an-path', { timeout: 60000 });
  const before = await page.evaluate(() => Studio.app.explorer.shown.size);
  await page.locator('.an-path').first().click();
  await page.waitForFunction(() => [...document.querySelectorAll('.toast')].some((t) => /in the explorer/.test(t.textContent)), null, { timeout: 15000 });
  const toast = await page.evaluate(() => [...document.querySelectorAll('.toast')].map((t) => t.textContent).find((t) => /in the explorer/.test(t)));
  expect(/^Shown in the explorer: [1-9]/.test(toast), 'toast: ' + toast);
  expect(await page.evaluate(() => Studio.app.explorer.shown.size) > before, 'signals drawn');
  // every shown id is a real part (signal names are not part ids; drivers() would throw on them)
  expect(await page.evaluate(() => [...Studio.app.explorer.shown].every((id) => Studio.app.explorer.byId.has(id))), 'only part ids shown');
  return page;
});

spec('code', 'selecting a part highlights its Java; the SV view shows the module', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'reg').id]));
  await page.locator('.tab[data-tab="code"]').click();
  await page.waitForTimeout(300);
  expect(await page.evaluate(() => [...document.querySelectorAll('.codeview .ln.hl')].some((e) => e.textContent.includes('reg("count"'))), 'highlighted');
  await page.locator('.seg-b[data-v="sv"]').click();
  await page.waitForFunction(() => [...document.querySelectorAll('.code-pane pre:not([hidden]) .ln')].some((e) => /module Counter/.test(e.textContent)), null, { timeout: 15000 });
  return page;
});

spec('analyse', 'the FPGA pane shows where each port goes on the ZedBoard (no build)', async () => {
  const doc = { name: 'Zed', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('s', 'in', 2, 2, { name: 'sw', width: 4 }), P('b', 'in', 2, 6, { name: 'btn', width: 2 }), P('x', 'in', 2, 10, { name: 'other', width: 1 }), P('l', 'out', 30, 2, { name: 'led', width: 4 }), P('q', 'out', 30, 8, { name: 'dbg', width: 1 })],
    nets: [{ id: 'w1', pins: [['s', 'y'], ['l', 'a']] }, { id: 'w2', pins: [['x', 'y'], ['q', 'a']] }] };
  const page = await open(doc, { allow: [] });
  await page.locator('.tab[data-tab="fpga"]').click();
  await page.locator('button', { hasText: 'Check the ports' }).click();
  const pane = () => [...document.querySelectorAll('.insp')].find((e) => e.querySelector('h2') && e.querySelector('h2').textContent.trim() === 'FPGA');
  await page.waitForFunction((f) => { const p = eval(f)(); return p && p.querySelectorAll('table tr').length >= 5; }, pane.toString(), { timeout: 15000 });
  const rows = await page.evaluate((f) => [...eval(f)().querySelectorAll('table tr')].map((r) => r.textContent), pane.toString());
  expect(rows.some((r) => r.includes('sw') && r.includes('SW0–SW3')) && rows.some((r) => r.includes('led') && r.includes('LD0–LD3')) && rows.some((r) => r.includes('btn') && r.includes('BTNC, BTNU')), rows.join(' | '));
  expect(await page.evaluate((f) => (eval(f)().textContent.match(/no board name/g) || []).length === 2, pane.toString()), 'warns about other and dbg');
  return page;
});

// the drawing tessera-eval/browser/fixtures/mac.paths.rpt was made from (timing-live.mjs: Vivado out of context, 4 ns)
const MAC = { name: 'Mac', title: '', package: 'studio', next: 100, notes: [],
  parts: [P('a', 'in', 2, 2, { name: 'a', width: 32 }), P('b', 'in', 2, 10, { name: 'b', width: 32 }),
    P('ra', 'reg', 12, 2, { name: 'ra', width: 32, init: '0' }), P('rb', 'reg', 12, 10, { name: 'rb', width: 32, init: '0' }),
    P('m', 'mul', 26, 5), P('s', 'add', 40, 14, { mode: 'wrap' }),
    P('acc', 'reg', 56, 12, { name: 'acc', width: 64, init: '0' }), P('y', 'out', 70, 7, { name: 'y', width: 64 })],
  nets: [{ id: 'n1', pins: [['a', 'y'], ['ra', 'd']] }, { id: 'n2', pins: [['b', 'y'], ['rb', 'd']] },
    { id: 'n3', pins: [['ra', 'q'], ['m', 'a']] }, { id: 'n4', pins: [['rb', 'q'], ['m', 'b']] },
    { id: 'n5', pins: [['m', 'y'], ['s', 'a']] }, { id: 'n6', pins: [['s', 'y'], ['acc', 'd']] },
    { id: 'n7', pins: [['acc', 'q'], ['s', 'b'], ['y', 'a']] }] };
const FIXTURES = new URL('./fixtures/', import.meta.url).pathname;

spec('analyse', 'a Vivado report maps onto the drawing: its parts numbered in path order, a step opens its Java line', async () => {
  const page = await open(MAC);
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('.cp input[type=file]').setInputFiles(FIXTURES + 'mac.paths.rpt');
  await page.waitForFunction(() => document.querySelectorAll('.cp-row').length === 8, null, { timeout: 30000 });
  eq(await page.evaluate(() => document.querySelector('.cp-row .cp-slack').textContent), '-5.865', 'worst slack');
  const map = await page.evaluate(() => document.querySelector('.cp-map').textContent);
  expect(/exact \d+%/.test(map) && /unmapped 0%/.test(map), 'mapping: ' + map);
  // path 1: the multiplier (a DSP cascade), the carry chain into acc, the adder, acc: numbered in that order
  const marks = await page.evaluate(() => Studio.app.canvas.cpMarks.map((m) => m.part + m.n));
  const at = (p) => { const m = marks.find((x) => x.startsWith(p) && /^\d+$/.test(x.slice(p.length))); return m ? Number(m.slice(p.length)) : -1; };
  expect(at('m') === 1 && at('acc') > at('m') && at('s') > at('acc'), 'marks in path order: ' + marks);
  eq(await page.evaluate(() => document.querySelectorAll('svg .cpmark').length), marks.length, 'drawn marks');
  expect(await page.evaluate(() => Studio.app.canvas.selectedParts().includes('m') && Studio.app.canvas.selectedNets().includes('n5')), 'the multiplier and its wire are lit');
  // the adder's step opens its Java line
  await page.locator('.cp-step', { hasText: 'Mac.java:16' }).first().click();
  await page.waitForTimeout(300);
  const ln = await page.evaluate(() => { const e = document.querySelector('.codeview .ln.hl'); return e ? e.getAttribute('data-n') + ' ' + e.textContent : 'none'; });
  expect(/^16 .*sum/.test(ln), 'line 16 lit in the Java: ' + ln);
  return page;
});

spec('analyse', 'a C5 report draws the worst path in the explorer: the multiply’s carry in Rv32i, with its line', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
  await page.locator('.tab[data-tab="analyse"]').click();
  // C5 (multiply summed in MEM, corrected in WB), timed out of context on the Vivado host
  await page.locator('.cp input[type=file]').setInputFiles(FIXTURES + 'c5.paths.rpt');
  await page.waitForFunction(() => document.querySelectorAll('.cp-row').length === 10, null, { timeout: 60000 });
  eq(await page.evaluate(() => document.querySelector('.cp-row .cp-slack').textContent), '-4.899', 'worst slack');
  const where = await page.evaluate(() => [...document.querySelectorAll('.cp-step .cp-where')].map((e) => e.textContent));
  expect(where.some((w) => w.startsWith('Rv32i.java:214')), 'Rv32i.java:214 in ' + where.join(' | '));
  // the source text comes with the line (the repository's sources)
  expect(where.some((w) => w.startsWith('Rv32i.java:214') && /d\.fromBool\(d\.ltu\(d\.add\(x, y\), x\)\)/.test(w)), 'the code of line 214: ' + where.find((w) => w.startsWith('Rv32i.java:214')));
  expect(await page.evaluate(() => Studio.app.explorer.shown.size > 0 && Studio.app.doc.d.parts.some((p) => p.params.name === 'p_WB_4')), 'p_WB_4 drawn in the explorer');
  return page;
});

spec('analyse', 'an openXC7 timing report is refused, with the reason', async () => {
  const page = await open(null, { allow: ['400'] });
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('.cp input[type=file]').setInputFiles({ name: 'nextpnr.log', mimeType: 'text/plain', buffer: Buffer.from("Info: Max frequency for clock 'clk': 44.55 MHz (PASS at 40.00 MHz)\n") });
  await page.waitForFunction(() => [...document.querySelectorAll('.toast')].some((t) => /openXC7/.test(t.textContent)), null, { timeout: 15000 });
  eq(await page.evaluate(() => document.querySelectorAll('.cp-row').length), 0, 'no paths');
  return page;
});

spec('code', 'the last change shows what an edit did to the Java', async () => {
  const page = await open();
  await page.evaluate(() => { const c = Studio.app.doc.d.parts.find((p) => p.type === 'const'); Studio.app.setParams(c.id, { value: '3' }); });
  await idle(page, 1500);
  await page.evaluate(() => { const c = Studio.app.doc.d.parts.find((p) => p.type === 'const'); Studio.app.doc.movePartsBy([c.id], 1, 0); });   // a move must not hide the change
  await idle(page, 1200);
  await page.locator('.tab[data-tab="code"]').click();
  await page.locator('.seg-b[data-v="changes"]').click();
  const d = await page.evaluate(() => ({ add: [...document.querySelectorAll('.diffview .dadd')].map((e) => e.textContent), del: [...document.querySelectorAll('.diffview .ddel')].map((e) => e.textContent) }));
  expect(d.del.some((l) => l.includes('UInt.lit(1, 8)')) && d.add.some((l) => l.includes('UInt.lit(3, 8)')), JSON.stringify(d));
  return page;
});

spec('code', 'save and open round-trip the drawing through the .java file', async () => {
  const page = await open();
  const java = (await gen(page)).java;
  const r = await page.evaluate((j) => Studio.Api.call('open', { java: j }), java);
  const a = await page.evaluate(() => JSON.stringify(Studio.app.doc.json().parts.map((p) => [p.id, p.type, p.x, p.y, p.params])));
  eq(JSON.stringify(r.doc.parts.map((p) => [p.id, p.type, p.x, p.y, p.params])), a, 'parts after the round trip');
  return page;
});

// ---------------------------------------------------------------- designs from Java, guide, layout

spec('designs', 'the C5 core opens in the explorer and runs live', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
  await page.locator('.sig', { hasText: 'core_pc' }).first().click();
  await page.waitForTimeout(1500);
  await sim(page, { cmd: 'step', n: 20 }); await page.waitForTimeout(800);
  const pc = await page.evaluate(() => [...document.querySelectorAll('.sig')].find((e) => /^.?core_pc0x/.test(e.textContent))?.textContent);
  expect(/0x[0-9a-f]+/.test(pc || ''), 'core_pc has a live value: ' + pc);
  return page;
});

spec('designs', 'C5 runs riscv-tests live: rv32ui-add and rv32ui-beq pass (gp = 1 when the core halts)', async () => {
  const page = await open();
  const res = [];
  for (const t of ['rv32ui-add', 'rv32ui-beq']) {
    await page.evaluate((t) => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)', t), t);
    await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
    await sim(page, { cmd: 'restart' });
    await sim(page, { cmd: 'rate', rate: 0 });
    await sim(page, { cmd: 'until', expr: 'core_halted == 1' });
    await page.waitForFunction(() => !Studio.app.running && /holds/.test(Studio.app.message || ''), null, { timeout: 30000 });
    const r = await page.evaluate(() => Studio.Api.call('mem', { path: 'regs', from: 3, count: 1 }));
    res.push(t + ' x3=' + r.words[0] + ' at cycle ' + r.cycle);
    eq(r.words[0], '1', t + ' gp');
  }
  page.measure = res.join('; ');
  return page;
});

spec('designs', 'the signal picker finds a signal of the C5 core by search', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForFunction(() => Studio.app.explorer && Object.keys(Studio.app.waves.signals || {}).length > 100, null, { timeout: 30000 });
  const n0 = await page.evaluate(() => Studio.app.waves.rows.length);
  await page.locator('.wv-add').click();
  await page.locator('.modal input').fill('core pc_ex');
  await page.waitForTimeout(200);
  const listed = await page.evaluate(() => [...document.querySelectorAll('.wpick-s span:first-child')].map((e) => e.textContent));
  expect(listed.includes('core_pc_EX') || listed.some((x) => /pc_EX/i.test(x)), 'listed: ' + listed.slice(0, 5));
  await page.locator('.modal input').press('Enter');
  await page.waitForTimeout(300);
  await page.keyboard.press('Escape');
  eq(await page.evaluate(() => Studio.app.waves.rows.length), n0 + 1, 'rows');
  expect(await page.evaluate(() => Studio.app.waves.rows.some((r) => /pc_EX/i.test(r.path))), 'pc_EX in the waves');
  return page;
});

spec('designs', 'the pipeline diagram: C5\'s instructions go through the stages, flushes and retirements are marked, a click goes back in time, and the schedule is in words', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
  await sim(page, { cmd: 'restart' });
  const c = (await sim(page, { cmd: 'step', n: 60 })).cycle;
  await page.waitForFunction((c) => Studio.app.cycle === c, c, { timeout: 5000 });
  await page.locator('.tab[data-tab="pipe"]').click();
  await page.waitForFunction(() => document.querySelectorAll('.pv-row').length > 10, null, { timeout: 10000 });
  const d = await page.evaluate(() => ({
    head: document.querySelector('.pv-t th.pv-ins').textContent,
    rows: document.querySelectorAll('.pv-row').length,
    retired: document.querySelectorAll('.pv-c.rt').length,
    flushed: document.querySelectorAll('.pv-row.flushed').length,
    text: [...document.querySelectorAll('.pv-row td.pv-ins')].map((e) => e.textContent).join(' | '),
    now: document.querySelector('.pv-cy.now')?.title,
    sched: document.querySelector('.pv-sched').textContent,
  }));
  expect(d.head.includes('IF ID EX MEM WB'), 'stages (named from the schedule): ' + d.head);
  expect(d.retired > 5, 'retired cells: ' + d.retired);
  expect(d.flushed > 0, 'the loop\'s taken branch flushes the fetches behind it');
  expect(/addi|add|bne|blt|jal/.test(d.text), 'rows are disassembled: ' + d.text.slice(0, 120));
  eq(d.now, 'Go to cycle ' + c, 'the column of the cycle shown');
  expect(d.sched.includes('redirect: resolved in EX'), 'schedule: ' + d.sched);
  // a click on an earlier cycle goes there; the diagram follows it
  await page.locator(`.pv-cy[title="Go to cycle ${c - 15}"]`).click();
  await page.waitForFunction((b) => Studio.app.cycle === b && document.querySelector('.pv-cy.now')?.title === 'Go to cycle ' + b, c - 15, { timeout: 5000 });
  // the Analyse pane says the same in its schedule section
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.getByRole('button', { name: 'The longest combinational paths, from the IR' }).click();
  await page.waitForFunction(() => /forward from/.test(document.querySelector('.an-sched')?.textContent || ''), null, { timeout: 30000 });
  // the out-of-order core has no generated pipeline: the tab says so
  await page.evaluate(() => Studio.app.openDesign('C7 out-of-order, 16-entry ROB / 8 RS'));
  await page.waitForFunction(() => Studio.app.explorer && Studio.app.fullView && /C7|Ooo/i.test(Studio.app.message || ''), null, { timeout: 30000 });
  await page.locator('.tab[data-tab="pipe"]').click();
  await page.waitForFunction(() => /no pipeline built with Tessera/.test(document.querySelector('.pv-body').textContent), null, { timeout: 10000 });
  return page;
});

spec('guide', 'the guide is served with its pictures and opens from F1', async () => {
  const page = await open();
  const r = await page.evaluate(async () => { const t = await (await fetch('/guide')).text(); const img = await fetch('/guide/studio-guide/overview.png'); return [t.includes('<h1 id="tessera-studio-guide">'), img.status, (await Studio.Api.call('guide', {})).length]; });
  expect(r[0], 'h1'); eq(r[1], 200, 'picture'); expect(r[2] >= 30, 'sections ' + r[2]);
  await page.locator('.cv').focus(); await page.keyboard.press('F1'); await page.waitForTimeout(400);
  expect(await page.evaluate(() => document.querySelector('.guide-drawer').classList.contains('open')), 'drawer');
  return page;
});

spec('layout', 'a narrow window never scrolls the page sideways', async () => {
  const page = await open(null, { w: 1100, h: 800 });
  for (const t of ['inspect', 'code', 'problems', 'prove', 'board', 'analyse']) await page.locator(`.tab[data-tab="${t}"]`).click();
  eq(await page.evaluate(() => document.scrollingElement.scrollLeft + ' ' + (document.documentElement.scrollWidth <= innerWidth)), '0 true', 'scroll');
  return page;
});

spec('layout', 'a phone: the canvas has the width, panels are drawers, a tap toggles an input', async () => {
  const page = await open(null, { w: 390, h: 844 });
  eq(await page.evaluate(() => Math.round(document.querySelector('.cv').getBoundingClientRect().width)), 390, 'canvas width');
  expect(await page.locator('.runbar .btn.primary').isVisible(), 'run button visible');
  await page.locator('.narrow-only').first().click(); await page.waitForTimeout(400);
  expect(await page.evaluate(() => Math.round(document.querySelector('.side.left').getBoundingClientRect().left)) === 0, 'parts drawer open');
  await page.locator('.narrow-only').first().click(); await page.waitForTimeout(500);
  await sim(page, { cmd: 'run' });
  const en = await page.evaluate(() => Studio.app.doc.d.parts.find((p) => p.params.name === 'en').id);
  const bx = await page.locator(`.cv .part[data-id="${en}"]`).boundingBox();
  await page.mouse.click(bx.x + bx.width / 2, bx.y + bx.height / 2); await page.waitForTimeout(800);
  eq(await val(page, 'en', 'y'), 1, 'toggled');
  eq(await page.evaluate(() => document.documentElement.scrollWidth), 390, 'no sideways scroll');
  return page;
});

// ---------------------------------------------------------------- testbench

spec('bench', 'a JUnit-style testbench drives the run: one test passes, one fails with its line, and it is saved with the drawing', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.showTab('bench'));
  const src = `import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterBench {
    @Test
    void counts(Simulator sim) {
        long c0 = sim.peek("value");
        sim.poke("en", 1);
        sim.step(10);
        assertEquals((c0 + 10) & 0xff, sim.peek("value"));
    }

    @Test
    void wrongOnPurpose(Simulator sim) {
        sim.step(3);
        assertEquals(-1, sim.peek("value"), "never");
    }
}
`;
  await page.locator('textarea.bench-ed').fill(src);
  await page.locator('textarea.bench-ed').press('Meta+Enter');
  await page.waitForSelector('.bench-sum', { timeout: 20000 });
  eq(await page.locator('.bench-sum').textContent(), '1 of 2 passed', 'summary');
  const msg = await page.locator('.bench-row.bad .bench-msg').textContent();
  expect(msg.startsWith('line 17: never'), 'failure with its line: ' + msg);
  eq(await page.evaluate(() => Studio.app.doc.d.bench.includes('wrongOnPurpose')), true, 'kept in the drawing');
  await idle(page, 900);
  const reopened = await page.evaluate(async () => { const r = await Studio.Api.call('codegen', { doc: Studio.app.doc.json() }); return (await Studio.Api.call('open', { java: r.java })).doc.bench || ''; });
  expect(reopened.includes('wrongOnPurpose'), 'the saved .java carries the testbench');
  return page;
});

spec('bench', 'each test from reset starts like JUnit, and the testbench saves as a JUnit test of the project (downloaded here, nothing written)', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.showTab('bench'));
  // the template names the drawing's class, so the same source runs under JUnit
  await page.getByRole('button', { name: 'New from the ports' }).click();
  await page.waitForFunction(() => document.querySelector('textarea.bench-ed').value.includes('@Design'));
  const tpl = await page.locator('textarea.bench-ed').inputValue();
  expect(tpl.includes('@ExtendWith(HwTestExtension.class)') && /@Design (\w+) dut\(\) \{ return new \1\(/.test(tpl), 'template: ' + tpl);
  const src = `import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FromZero {
    @Test
    void startsAtZero(Simulator sim) {
        assertEquals(0, sim.peek("value"), "not from zero");
        sim.step(4);
    }
}
`;
  await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  await sim(page, { cmd: 'step', n: 20 });
  await page.locator('textarea.bench-ed').fill(src);
  await page.locator('textarea.bench-ed').press('Meta+Enter');
  await page.waitForSelector('.bench-sum', { timeout: 20000 });
  eq(await page.locator('.bench-sum').textContent(), '0 of 1 passed', 'on the live run the counter is not at 0');
  await page.getByLabel('Each test from reset').check();
  await page.locator('textarea.bench-ed').press('Meta+Enter');
  await page.waitForFunction(() => (document.querySelector('.bench-sum') || {}).textContent === '1 of 1 passed', null, { timeout: 20000 });
  eq(await page.evaluate(() => JSON.parse(localStorage.getItem('tstudio.benchFromReset'))), true, 'the choice is remembered');
  // save as a JUnit test: the suite's studio has the examples project, so it offers its src/test/java; download instead
  await page.locator('textarea.bench-ed').fill(tpl);
  await page.locator('textarea.bench-ed').dispatchEvent('change');
  await page.getByRole('button', { name: 'Save as a JUnit test' }).click();
  await page.waitForSelector('.modal');
  const ask = await page.locator('.modal').textContent();
  const m = ask.match(/(?:Write|Replace) (\S+CounterTest\.java)\?/);
  expect(m && m[1].endsWith('examples/src/test/java/studio/CounterTest.java'), 'offers the project test file: ' + ask);
  expect(ask.includes('no studio/Counter.java yet'), 'says the drawing is not in the project: ' + ask);
  const [dl] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Download instead' }).click()]);
  eq(dl.suggestedFilename(), 'CounterTest.java', 'download name');
  const java = readFileSync(await dl.path(), 'utf8');
  expect(java.startsWith('package studio;\n') && java.includes('@ExtendWith(HwTestExtension.class)'), 'the JUnit class: ' + java);
  eq(await page.locator('.modal').count(), 0, 'the dialog closed');
  expect(!existsSync(m[1]), 'nothing written to ' + m[1]);
  return page;
});

// ---------------------------------------------------------------- writable memory

spec('memory', 'a memory word written from the view: the design reads it at once', async () => {
  const page = await open({ name: 'Rom', title: '', package: 'studio', next: 10, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'addr', width: 2 }), P('m', 'mem', 14, 2, { name: 'rom', width: 8, depth: 4, write: false, init: '1 2 3 4' }), P('o', 'out', 32, 2, { name: 'q', width: 8 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['m', 'addr']] }, { id: 'n2', pins: [['m', 'rdata'], ['o', 'a']] }] });
  await sim(page, { cmd: 'poke', path: 'addr', value: '2' });
  await sim(page, { cmd: 'step', n: 2 });
  await page.evaluate(() => { Studio.app.canvas.selectOnly(['p:m']); Studio.app.showTab('inspect'); });
  await page.waitForFunction(() => document.querySelectorAll('.insp .mw').length === 4, null, { timeout: 5000 });
  await page.locator('.insp .mw').nth(2).dblclick();
  const input = page.locator('.modal-back input');
  await input.fill('0xA7');
  await input.press('Enter');
  await page.waitForTimeout(400);
  await sim(page, { cmd: 'step', n: 1 });
  await page.waitForTimeout(500);
  eq(await val(page, 'q', 'a'), 0xa7, 'q reads the written word');
  eq(await page.locator('.insp .mw').nth(2).textContent(), 'a7', 'the view shows it');
  return page;
});

// ---------------------------------------------------------------- board parts on the canvas

spec('board', 'board parts on the drawing: a clicked switch lights its LED and the hex digit; a held button is 1 until let go', async () => {
  const page = await open({ name: 'Panel', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('s', 'in', 2, 2, { name: 'sw', width: 4, look: 'switches' }), P('l', 'out', 24, 2, { name: 'led', width: 4, look: 'leds' }),
      P('x', 'out', 24, 10, { name: 'hex', width: 4, look: 'hex' }), P('b', 'in', 2, 12, { name: 'btn', width: 1, look: 'buttons' }), P('h', 'out', 24, 18, { name: 'held', width: 1, look: 'leds' })],
    nets: [{ id: 'n1', pins: [['s', 'y'], ['l', 'a'], ['x', 'a']] }, { id: 'n2', pins: [['b', 'y'], ['h', 'a']] }] });
  eq((await gen(page)).problems.join('; '), '', 'no problems');
  const box = async (sel) => page.locator(sel).first().boundingBox();
  // flip sw[0] and sw[2]: 0b0101 = 5
  for (const bit of [0, 2]) { const b = await box(`.cv .part[data-id="s"] [data-bit="${bit}"]`); await page.mouse.click(b.x + b.width / 2, b.y + b.height / 2); await page.waitForTimeout(450); }
  const leds = await page.evaluate(() => [...document.querySelectorAll('.cv .part[data-id="l"] .bled')].map((c) => c.classList.contains('on') ? 1 : 0).join(''));
  eq(leds, '0101', 'LEDs led[3..0]');
  const segs = await page.evaluate(() => [...document.querySelectorAll('.cv .part[data-id="x"] .seg')].map((c) => c.classList.contains('on') ? 1 : 0).join(''));
  eq(segs, '1011011', 'the digit 5 (segments a..g)');
  // hold the button: held lights; let go: it goes dark
  const bb = await box('.cv .part[data-id="b"] [data-bit="0"]');
  await page.mouse.move(bb.x + bb.width / 2, bb.y + bb.height / 2);
  await page.mouse.down(); await page.waitForTimeout(450);
  const on = await page.evaluate(() => document.querySelector('.cv .part[data-id="h"] .bled').classList.contains('on'));
  await page.mouse.up(); await page.waitForTimeout(450);
  const off = await page.evaluate(() => document.querySelector('.cv .part[data-id="h"] .bled').classList.contains('on'));
  eq(on + ' ' + off, 'true false', 'held while pressed');
  expect((await gen(page)).java.includes('input("sw", UInt.w(4))'), 'switches are an input in the Java');
  return page;
});

// ---------------------------------------------------------------- stream transactions

spec('waves', 'a stream’s transactions: the Fifo’s input stream shows 3 transfers with their bits and the stall while it is full', async () => {
  const page = await open(null, { palette: ['Modules'] });
  const pins = await page.evaluate(async () => (await Studio.Api.call('module', { expr: 'new Fifo<>(UInt.w(8), 2)', imports: ['org.eninehq.tessera.stdlib.Fifo'] })).pins);
  await page.evaluate((pins) => Studio.app.replace(Studio.app.fill({ name: 'Q', title: '', package: 'studio', next: 50, notes: [],
    parts: [{ id: 'v', type: 'in', x: 2, y: 2, rot: 0, mir: false, params: { name: 'push', width: 1 } }, { id: 'b', type: 'in', x: 2, y: 6, rot: 0, mir: false, params: { name: 'data', width: 8 } },
      { id: 'r', type: 'in', x: 2, y: 10, rot: 0, mir: false, params: { name: 'pop', width: 1 } },
      { id: 'f', type: 'module', x: 16, y: 2, rot: 0, mir: false, params: { name: 'q', expr: 'new Fifo<>(UInt.w(8), 2)', imports: ['org.eninehq.tessera.stdlib.Fifo'], pins } },
      { id: 'o1', type: 'out', x: 34, y: 2, rot: 0, mir: false, params: { name: 'head', width: 8 } }, { id: 'o2', type: 'out', x: 34, y: 6, rot: 0, mir: false, params: { name: 'have', width: 1 } },
      { id: 'o4', type: 'out', x: 34, y: 10, rot: 0, mir: false, params: { name: 'room', width: 1 } }],
    nets: [{ id: 'n1', pins: [['v', 'y'], ['f', 'in_valid']] }, { id: 'n2', pins: [['b', 'y'], ['f', 'in_bits']] }, { id: 'n3', pins: [['r', 'y'], ['f', 'out_ready']] },
      { id: 'n4', pins: [['f', 'out_bits'], ['o1', 'a']] }, { id: 'n5', pins: [['f', 'out_valid'], ['o2', 'a']] }, { id: 'n7', pins: [['f', 'in_ready'], ['o4', 'a']] }] })), pins);
  await idle(page, 2500);
  await sim(page, { cmd: 'restart' });
  await page.waitForTimeout(500);
  const streams = await page.evaluate(() => Studio.app.waves.streams());
  expect(streams.includes('q.in') && streams.includes('q.out'), 'streams found: ' + streams);
  await page.evaluate(() => Studio.app.waves.addStream('q.in'));
  // push 0x11, 0x22, then 0x33 while the 2-deep FIFO is full (a stall), then pop one so 0x33 goes in
  await sim(page, { cmd: 'poke', path: 'push', value: '1' });
  for (const v of ['11', '22', '33']) { await sim(page, { cmd: 'poke', path: 'data', value: v }); await sim(page, { cmd: 'step', n: 1 }); }
  await sim(page, { cmd: 'step', n: 2 });
  await sim(page, { cmd: 'poke', path: 'pop', value: '1' });
  await sim(page, { cmd: 'step', n: 1 });
  await sim(page, { cmd: 'poke', path: 'pop', value: '0' });
  await sim(page, { cmd: 'poke', path: 'push', value: '0' });
  await sim(page, { cmd: 'step', n: 3 });
  const t = await page.evaluate(async () => Studio.Api.call('transactions', { stream: 'q.in', from: 0, to: 1e9 }));
  const fires = t.runs.filter((x) => x[2] === 'fire').map((x) => x[3] + (x[1] - x[0] > 1 ? '×' + (x[1] - x[0]) : '')).join(' ');
  eq(fires, '11 22 33', 'three transfers with their bits');
  expect(t.stalls >= 1, 'a stall while full: ' + JSON.stringify(t.runs));
  await page.waitForTimeout(600);
  const label = await page.evaluate(() => [...document.querySelectorAll('.wv-name')].find((x) => x.textContent.includes('q.in ⇄')).querySelector('.wv-val').textContent);
  expect(/^3⇄ [1-9]\d*⏸$/.test(label), 'the row says 3 transfers and its stalls: ' + label);
  return page;
});

// ---------------------------------------------------------------- view preferences

spec('view', 'View: active-low names get a bar, IEC symbols draw & and ≥1, activity heat tints busy wires warm and quiet ones cool', async () => {
  const page = await open({ name: 'Look', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'rst_n', width: 1 }), P('b', 'in', 2, 8, { name: 'go', width: 1 }), P('g', 'and', 14, 4, { inputs: 2 }), P('o', 'or', 14, 12, { inputs: 2 }),
      P('y', 'out', 28, 4, { name: 'both', width: 1 }), P('z', 'out', 28, 12, { name: 'either', width: 1 }),
      P('r', 'reg', 14, 20, { name: 'tick', width: 1, init: '0', enable: false }), P('n', 'not', 6, 20, {}), P('t', 'out', 28, 20, { name: 'toggle', width: 1 })],
    nets: [{ id: 'w1', pins: [['a', 'y'], ['g', 'in0'], ['o', 'in0']] }, { id: 'w2', pins: [['b', 'y'], ['g', 'in1'], ['o', 'in1']] }, { id: 'w3', pins: [['g', 'y'], ['y', 'a']] }, { id: 'w4', pins: [['o', 'y'], ['z', 'a']] },
      { id: 'w5', pins: [['r', 'q'], ['n', 'a'], ['t', 'a']] }, { id: 'w6', pins: [['n', 'y'], ['r', 'd']] }] });
  // overbars are on by default
  const bar = await page.evaluate(() => { const t = [...document.querySelectorAll('.cv .port-name')].find((x) => x.querySelector('tspan')); return t && t.querySelector('tspan').getAttribute('text-decoration') + ' ' + t.textContent; });
  eq(bar, 'overline rstrst_n (active low)', 'rst_n drawn as rst with a bar (the title holds rst_n)');
  // IEC
  await page.evaluate(() => Studio.app.setView({ symbols: 'iec' }));
  await page.waitForTimeout(300);
  const q = await page.evaluate(() => [...document.querySelectorAll('.cv .iec-q')].map((x) => x.textContent).sort().join(' '));
  eq(q, '& 1 ≥1', 'IEC qualifiers');
  // heat: run 100 cycles; the toggling register's wire is the busiest, the inputs' wires are quiet
  await page.evaluate(() => Studio.app.setView({ heat: true }));
  await sim(page, { cmd: 'step', n: 100 });
  await page.waitForFunction(() => document.querySelector('.cv .wire[data-net="w5"]').getAttribute('class').includes('heat4'), null, { timeout: 5000 }).catch(() => {});
  const cls = await page.evaluate(() => ['w5', 'w1'].map((n) => document.querySelector(`.cv .wire[data-net="${n}"]`).getAttribute('class').match(/heat\d/)?.[0]).join(' '));
  eq(cls, 'heat4 heat0', 'the toggle is hot, the idle input is cold');
  await page.evaluate(() => Studio.app.setView({ heat: false, symbols: 'ansi' }));
  return page;
});

// ---------------------------------------------------------------- clock domains

const TWO_CLOCKS = {
  name: 'TwoClocks', title: '', package: 'studio', next: 100, notes: [],
  parts: [P('c', 'domain', 2, 22, { name: 'clock', period: 3, phase: 0 }), P('f', 'domain', 14, 22, { name: 'fast', period: 1, phase: 0 }),
    P('rs', 'reg', 14, 2, { name: 'count', width: 8, init: '0', enable: false }), P('ks', 'const', 2, 4, { value: '1', width: 8 }), P('as', 'add', 6, 2, { mode: 'wrap' }),
    P('rf', 'reg', 14, 12, { name: 'fcount', width: 8, init: '0', enable: false, domain: 'fast' }), P('kf', 'const', 2, 14, { value: '1', width: 8 }), P('af', 'add', 6, 12, { mode: 'wrap' }),
    P('os', 'out', 30, 2, { name: 'slow_out', width: 8 }), P('of', 'out', 30, 12, { name: 'fast_out', width: 8 })],
  nets: [{ id: 'n1', pins: [['rs', 'q'], ['as', 'a'], ['os', 'a']] }, { id: 'n2', pins: [['ks', 'y'], ['as', 'b']] }, { id: 'n3', pins: [['as', 'y'], ['rs', 'd']] },
    { id: 'm1', pins: [['rf', 'q'], ['af', 'a'], ['of', 'a']] }, { id: 'm2', pins: [['kf', 'y'], ['af', 'b']] }, { id: 'm3', pins: [['af', 'y'], ['rf', 'd']] }],
};

spec('clocks', 'two clock domains: tagged on the canvas, chosen in the inspector, stepped edge by edge', async () => {
  const page = await open(TWO_CLOCKS);
  eq((await gen(page)).problems.join('; '), '', 'no problems');
  expect((await gen(page)).java.includes('withClock(fast, () -> reg("fcount"'), 'withClock in the Java');
  const tags = await page.evaluate(() => [...document.querySelectorAll('.cv .part text')].map((t) => t.textContent).filter((t) => t.includes('⏱ fast') && !t.includes('÷')));
  eq(tags.join(','), 'fcount  ⏱ fast', 'the register in fast says so in its name');
  expect(await page.evaluate(() => !Studio.app.bEdge.hidden), 'the next-edge button is shown');
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:rs']));
  await page.evaluate(() => Studio.app.showTab('inspect'));
  const opts = await page.evaluate(() => [...document.querySelectorAll('.insp select option')].map((o) => o.textContent));
  expect(opts.includes('fast') && opts.includes('the design’s clock'), 'the domain picker lists fast: ' + opts);
  const slow0 = await val(page, 'slow_out', 'a');
  await sim(page, { cmd: 'step', edge: 'clock' });
  await page.waitForTimeout(500);
  eq(BigInt(await val(page, 'slow_out', 'a')) - BigInt(slow0), 1, 'the next edge of clock moves count by one');
  const f0 = BigInt(await val(page, 'fast_out', 'a')), s0 = BigInt(await val(page, 'slow_out', 'a'));
  await sim(page, { cmd: 'step', n: 30 });
  await page.waitForTimeout(500);
  eq(BigInt(await val(page, 'fast_out', 'a')) - f0, 30, 'fast: 30 edges in 30 ticks');
  eq(BigInt(await val(page, 'slow_out', 'a')) - s0, 10, 'clock: 10 edges in 30 ticks');
  return page;
});

// ---------------------------------------------------------------- serial terminal

spec('uart', 'the serial terminal: typed text goes out on rx and comes back decoded on tx (a loopback wire), also after a restart', async () => {
  const page = await open({ name: 'Loop', title: '', package: 'studio', next: 10, notes: [],
    parts: [P('i', 'in', 2, 4, { name: 'rx', width: 1 }), P('o', 'out', 16, 4, { name: 'tx', width: 1 })],
    nets: [{ id: 'n', pins: [['i', 'y'], ['o', 'a']] }] });
  await page.evaluate(() => Studio.app.showTab('board'));
  await page.waitForSelector('.uart-in');
  const cpb = await page.locator('.uart-bar .t').textContent();
  // a run, then a restart: the terminal must decode from the new cycle 0, not from where the old run was
  await sim(page, { cmd: 'step', n: 900 });
  await page.waitForTimeout(700);
  await sim(page, { cmd: 'restart' });
  await page.waitForTimeout(700);
  await page.locator('.uart-in').fill('Hi!');
  await page.locator('.uart-in').press('Enter');
  await page.waitForTimeout(300);
  await sim(page, { cmd: 'step', n: 400 });
  await page.waitForFunction(() => document.querySelector('.uart-out').textContent === 'Hi!\n', null, { timeout: 8000 }).catch(() => {});
  eq(JSON.stringify(await page.locator('.uart-out').textContent()), JSON.stringify('Hi!\n'), 'echoed (' + cpb + ')');
  return page;
});

spec('uart', 'the serial terminal decodes the examples’ UartTx module, a frame at a time', async () => {
  const page = await open(null, { palette: ['Modules'] });
  const pins = await page.evaluate(async () => (await Studio.Api.call('module', { expr: 'new UartTx(8)', imports: ['org.eninehq.tessera.examples.UartTx'] })).pins);
  await page.evaluate((pins) => Studio.app.replace(Studio.app.fill({ name: 'Ser', title: '', package: 'studio', next: 20, notes: [],
    parts: [{ id: 'd', type: 'in', x: 2, y: 2, rot: 0, mir: false, params: { name: 'data', width: 8 } }, { id: 'v', type: 'in', x: 2, y: 8, rot: 0, mir: false, params: { name: 'valid', width: 1 } },
      { id: 'u', type: 'module', x: 16, y: 2, rot: 0, mir: false, params: { name: 'u', expr: 'new UartTx(8)', imports: ['org.eninehq.tessera.examples.UartTx'], pins } },
      { id: 't', type: 'out', x: 34, y: 2, rot: 0, mir: false, params: { name: 'tx', width: 1 } }, { id: 'r', type: 'out', x: 34, y: 8, rot: 0, mir: false, params: { name: 'ready', width: 1 } }],
    nets: [{ id: 'a', pins: [['d', 'y'], ['u', 'data']] }, { id: 'b', pins: [['v', 'y'], ['u', 'valid']] }, { id: 'c', pins: [['u', 'tx'], ['t', 'a']] }, { id: 'e', pins: [['u', 'ready'], ['r', 'a']] }] })), pins);
  await idle(page, 2500);
  eq((await gen(page)).problems.join('; '), '', 'no problems');
  await page.evaluate(() => Studio.app.showTab('board'));
  await page.waitForSelector('.uart-out');
  await page.evaluate(() => { const b = Studio.app.board.uart; b.baud = Studio.app.hz / 8; b.from = 0; });
  for (const ch of 'OK') {
    await sim(page, { cmd: 'poke', path: 'data', value: ch.charCodeAt(0).toString(16) });
    await sim(page, { cmd: 'poke', path: 'valid', value: '1' });
    await sim(page, { cmd: 'step', n: 1 });
    await sim(page, { cmd: 'poke', path: 'valid', value: '0' });
    await sim(page, { cmd: 'step', n: 90 });
  }
  await sim(page, { cmd: 'step', n: 20 });
  await page.waitForFunction(() => document.querySelector('.uart-out').textContent === 'OK', null, { timeout: 8000 }).catch(() => {});
  eq(await page.locator('.uart-out').textContent(), 'OK', 'decoded');
  return page;
});

// ---------------------------------------------------------------- shortcuts

spec('keys', 'a shortcut can be rebound in the sheet: J steps, . no longer does, it survives a reload, defaults come back', async () => {
  const page = await open();
  const cyc = () => page.evaluate(() => Studio.app.cycle);
  await page.mouse.click(5, 300).catch(() => {});
  await page.locator('.cv').click({ position: { x: 40, y: 40 } });
  const c0 = await cyc();
  await page.keyboard.press('.'); await page.waitForTimeout(400);
  eq(await cyc(), c0 + 1, '. steps by default');
  await page.keyboard.press('?');
  await page.locator('.keybtn[data-id="step"]').click();
  await page.keyboard.press('j');
  await page.waitForTimeout(150);
  expect((await page.locator('.keybtn[data-id="step"]').textContent()).trim() === 'J', 'the sheet shows J: ' + await page.locator('.keybtn[data-id="step"]').textContent());
  await page.keyboard.press('Escape');
  await page.locator('.modal-back').waitFor({ state: 'detached', timeout: 3000 }).catch(async () => { await page.locator('.modal-back .btn', { hasText: 'Close' }).click(); });
  await page.locator('.cv').click({ position: { x: 40, y: 40 } });
  const c1 = await cyc();
  await page.keyboard.press('j'); await page.waitForTimeout(400);
  eq(await cyc(), c1 + 1, 'J steps');
  await page.keyboard.press('.'); await page.waitForTimeout(400);
  eq(await cyc(), c1 + 1, '. does nothing now');
  expect((await page.evaluate(() => document.querySelector('[data-key-id="step"]').title)) === 'One cycle (J)', 'the toolbar tooltip follows');
  await page.reload();
  await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.gen && Studio.app.gen.java);
  eq(await page.evaluate(() => Studio.app.keys.label('step')), 'J', 'kept after a reload');
  // a chord another action has moves: T (light or dark) onto one cycle
  await page.evaluate(() => Studio.app.keys.set('step', 'T'));
  eq(await page.evaluate(() => Studio.app.keys.label('theme')), '', 'theme lost T');
  await page.evaluate(() => Studio.app.keys.reset());
  eq(await page.evaluate(() => [Studio.app.keys.label('step'), Studio.app.keys.label('theme')].join(' ')), '. T', 'defaults back');
  return page;
});

// ---------------------------------------------------------------- wave export

spec('waves', 'SVG and FST export: the SVG parses and names the signals; GTKWave’s reader reads the FST counts back', async () => {
  const page = await open();
  await page.evaluate(() => { const en = Studio.app.doc.d.parts.find((p) => p.params.name === 'en'); Studio.app.sim({ cmd: 'poke', path: 'en', value: '1' }); });
  await sim(page, { cmd: 'step', n: 20 });
  await page.waitForTimeout(600);
  const paths = await page.evaluate(() => { Studio.app.waves.add('value'); Studio.app.waves.add('en'); return Studio.app.waves.rows.map((r) => r.path); });
  expect(paths.includes('value'), 'value is in the waves: ' + paths);
  await page.waitForTimeout(800);
  const svg = await page.evaluate(() => Studio.app.waves.exportSvg());
  const ok = await page.evaluate((t) => { const d = new DOMParser().parseFromString(t, 'image/svg+xml'); return !d.querySelector('parsererror') && d.querySelectorAll('path').length > 2 && [...d.querySelectorAll('text')].some((x) => x.textContent.trim() === 'value'); }, svg);
  expect(ok, 'a well-formed SVG with the waves and the name “value”');
  const r = await page.evaluate(() => Studio.app.waves.exportFst());
  const { writeFileSync, existsSync } = await import('node:fs');
  const { execFileSync } = await import('node:child_process');
  const dump = process.env.HOME + '/tessera-tools/fstdump/fstdump';
  if (existsSync(dump)) {
    const f = '/tmp/studio-suite-' + process.pid + '.fst';
    writeFileSync(f, Buffer.from(r.fst, 'base64'));
    const out = execFileSync(dump, [f]).toString().trim().split('\n');
    const handle = out.find((l) => / value 8$/.test(l)).split(' ')[1];
    const vals = out.filter((l) => l.split(' ')[1] === handle && /^\d+ \d+ [01]+$/.test(l)).map((l) => [Number(l.split(' ')[0]) / r.perCycle, parseInt(l.split(' ')[2], 2)]);
    // the counter counts by one every cycle while en is 1: value at cycle c is c - (the cycle it started)
    const d = vals.map(([c, v]) => c - v);
    expect(vals.length >= 15 && d.every((x) => x === d[0]), 'counts one a cycle: ' + JSON.stringify(vals.slice(0, 6)));
  }
  return page;
});

// ---------------------------------------------------------------- Java round trip

spec('java', 'an edit of the Java is read back: a new output appears, the register keeps its place, undo restores', async () => {
  const page = await open();
  const before = await page.evaluate(() => Studio.app.doc.d.parts.map((p) => [p.id, p.type, p.x, p.y]));
  const reg = before.find((p) => p[1] === 'reg');
  expect(reg, 'the counter sample has a register');
  await page.locator('.tab[data-tab="code"]').click();
  await page.locator('.code-pane .btn', { hasText: 'Edit' }).click();
  const ed = page.locator('textarea.java-ed');
  expect(await ed.isVisible(), 'the Java editor opens');
  const text = await ed.inputValue();
  expect(!text.includes('tessera-studio:1'), 'the drawing trailer is not in the editor');
  const regName = text.match(/UInt (\w+) = reg\("/)[1];
  const edited = text.replace(/(\n\s*)(output\()/, `$1output("odd", Bool.TYPE).set(${regName}.bit(0));$1$2`).replace(/import org\.eninehq\.tessera\.core\.Module;/, 'import org.eninehq.tessera.core.Bool;\nimport org.eninehq.tessera.core.Module;');
  await ed.fill(edited);
  await ed.press('Meta+Enter');
  await page.waitForFunction(() => Studio.app.doc.d.parts.some((p) => p.type === 'out' && p.params.name === 'odd'), null, { timeout: 15000 });
  await idle(page);
  const after = await page.evaluate(() => Studio.app.doc.d.parts.map((p) => [p.id, p.type, p.x, p.y]));
  const reg2 = after.find((p) => p[0] === reg[0]);
  eq(reg2 && reg2.slice(2).join(','), reg.slice(2).join(','), 'the register kept its id and place');
  expect((await gen(page)).java.includes('output("odd", Bool.TYPE)'), 'the new output is in the generated Java');
  eq((await gen(page)).problems.length, 0, 'no problems');
  const odd = await page.evaluate(() => { const p = Studio.app.doc.d.parts.find((q) => q.params.name === 'odd'); return [p.x, p.y]; });
  expect(odd[0] != null && odd[1] != null && !before.some((b) => b[2] === odd[0] && b[3] === odd[1]), 'the new part has a place of its own');
  await page.evaluate(() => Studio.app.doc.undo());
  await idle(page);
  expect(!(await page.evaluate(() => Studio.app.doc.d.parts.some((p) => p.params.name === 'odd'))), 'undo takes the edit back');
  return page;
});

spec('java', 'a hand-written design opens: Java outside the dialect becomes a code part and simulates', async () => {
  const page = await open();
  const java = `package studio;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Parity of a byte, by hand. */
public class Parity extends Module {
    public Parity() {
        UInt x = input("x", UInt.w(8));
        Bool p = x.bit(0);
        for (int i = 1; i < 8; i++) p = p.xor(x.bit(i));
        output("parity", Bool.TYPE).set(p);
    }
}
`;
  const r = await page.evaluate(async (j) => { const r = await Studio.Api.call('open', { java: j }); Studio.app.replace(Studio.app.placeNew(r.doc)); return r.report; }, java);
  eq(r.length, 1, 'one run became a code part: ' + r.join(' / '));
  await idle(page, 1500);
  eq((await gen(page)).problems.join('; '), '', 'no problems');
  const code = await page.evaluate(() => Studio.app.doc.d.parts.filter((p) => p.type === 'code').length);
  eq(code, 1, 'code parts');
  await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'x', value: 'b' }));   // 1011: three ones
  await sim(page, { cmd: 'step' });
  await page.waitForTimeout(500);
  eq(await val(page, 'parity', 'a'), 1, 'parity of 0b1011');
  return page;
});

// ---------------------------------------------------------------- core variants, out-of-order view, prover options

spec('cores', 'the designs menu groups the core variants in submenus, and C11-tdp opens from its group', async () => {
  const page = await open();
  await page.locator('button.btn .lb', { hasText: /^Designs$/ }).click();
  await page.waitForSelector('.menu .mi');
  const top = await page.evaluate(() => [...document.querySelectorAll('.menu .mi')].map((e) => e.textContent));
  expect(top.some((t) => t.startsWith('C11 out-of-order')), 'C11 in the menu itself');
  expect(!top.some((t) => t.startsWith('C11-tdp')), 'variants are not in the menu itself');
  for (const g of ['Out-of-order variants of C11 (2)', 'Generated from one model', 'Dual issue, memory in both lanes', 'Dual issue, live-value-table register file', 'Branch predictors'])
    expect(top.some((t) => t.startsWith(g)), 'group ' + g + ' in ' + top.join(' / '));
  await page.locator('.menu .mi', { hasText: 'Out-of-order variants of C11' }).click();
  await page.waitForFunction(() => [...document.querySelectorAll('.menu .mi')].some((e) => e.textContent.startsWith('C11-tdp')));
  await page.locator('.menu .mi', { hasText: 'C11-tdp' }).click();
  // a core takes a program: the dialog opens with the benchmark loop chosen
  await page.waitForSelector('.modal');
  await page.locator('.modal button', { hasText: 'Open' }).click();
  await page.waitForFunction(() => Studio.app.explorer && /C11-tdp/.test(Studio.app.message || ''), null, { timeout: 30000 });
  return page;
});

spec('cores', 'the out-of-order C11 draws its reorder buffer: instructions from dispatch to commit, with ROB, RS, LQ, SQ and checkpoint occupancy', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C11 out-of-order with execute-time branch recovery and a load/store queue, 16-entry ROB / 8 RS / 4 checkpoints / 4+4 LSQ'));
  await page.waitForFunction(() => Studio.app.explorer && /C11/.test(Studio.app.message || ''), null, { timeout: 30000 });
  await sim(page, { cmd: 'restart' });
  const c = (await sim(page, { cmd: 'step', n: 200 })).cycle;
  await page.waitForFunction((c) => Studio.app.cycle === c, c, { timeout: 5000 });
  await page.locator('.tab[data-tab="pipe"]').click();
  await page.waitForFunction(() => document.querySelectorAll('.pv-row').length > 10, null, { timeout: 10000 });
  const d = await page.evaluate(() => ({
    head: document.querySelector('.pv-t th.pv-ins').textContent,
    help: document.querySelector('.pv-body .help').textContent,
    retired: document.querySelectorAll('.pv-c.rt').length,
    occ: [...document.querySelectorAll('.pv-occ td.pv-ins')].map((e) => e.textContent),
    text: [...document.querySelectorAll('.pv-row td.pv-ins')].map((e) => e.textContent).join(' | '),
    title: document.querySelector('.pv-row td.pv-c')?.title,
  }));
  expect(d.head.includes('RS EX LQ SQ OK') && d.head.includes('16 entries'), 'states and entries: ' + d.head);
  expect(/no pipeline built with Tessera/.test(d.help), 'says why it is not drawn by stages: ' + d.help);
  expect(d.retired > 5, 'committed cells: ' + d.retired);
  eq(d.occ.join(','), 'ROB in use,RS in use,LQ in use,SQ in use,checkpoints in use', 'occupancy rows');
  expect(/addi|add|bne|blt|jal|lw|sw/.test(d.text), 'rows are disassembled: ' + d.text.slice(0, 120));
  expect(/reorder-buffer entry \d+/.test(d.title || ''), 'a cell names its entry: ' + d.title);
  page.measure = d.retired + ' committed cells';
  return page;
});

spec('cores', 'C9-lvt runs rv32ui-add live: gp = 1, read through the live-value-table register file', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.openDesign('C9-lvt dual-issue five-stage, live-value-table register file', 'rv32ui-add'));
  await page.waitForFunction(() => Studio.app.explorer && /C9-lvt/.test(Studio.app.message || ''), null, { timeout: 30000 });
  await sim(page, { cmd: 'restart' });
  await sim(page, { cmd: 'rate', rate: 0 });
  await sim(page, { cmd: 'until', expr: 'core_halted == 1' });
  await page.waitForFunction(() => !Studio.app.running && /holds/.test(Studio.app.message || ''), null, { timeout: 30000 });
  const r = await page.evaluate(() => Studio.Api.call('mem', { path: 'regs', from: 3, count: 1 }));
  eq(r.words[0], '1', 'gp');
  eq(r.lvt, 2, 'two banks');
  page.measure = 'x3=' + r.words[0] + ' at cycle ' + r.cycle;
  return page;
});

spec('cores', 'the Prove pane has the multi-clock options and sends the ones changed from the defaults', async () => {
  const page = await open();
  await page.evaluate(() => Studio.app.showTab('prove'));
  await page.locator('.prove-opts summary').click();
  expect(await page.locator('.prove-opts input[type=checkbox]').first().isChecked(), 'asynchronous resets free: the prover\'s default');
  await page.locator('.prove-opts input.input').fill('2');
  await page.evaluate(() => { const call = Studio.Api.call; Studio.Api.call = (n, b) => { if (n === 'prove') window.sentProve = b; return call(n, b); }; });
  await page.locator('button', { hasText: 'Bounded' }).click();
  await page.waitForFunction(() => window.sentProve && !Studio.app.prove.busy, null, { timeout: 30000 });
  const b = await page.evaluate(() => window.sentProve);
  eq(b.maxTickGap, 2, 'maxTickGap sent');
  expect(!('freeAsyncResets' in b) && !('atLeastOneTick' in b), 'defaults not sent: ' + JSON.stringify(b));
  return page;
});

// ---------------------------------------------------------------- run

const rows = [];
let pass = 0, fail = 0;
for (const s of specs) {
  if (filter && !(s.area + ' ' + s.name).includes(filter)) continue;
  const t0 = Date.now();
  let page = null, err = null;
  try {
    page = await s.fn();
    if (page && page.errors.length) err = 'console: ' + page.errors.join(' | ');
  } catch (e) { err = (e instanceof Fail ? '' : e.name + ': ') + e.message.split('\n')[0]; }
  if (page) await page.close().catch(() => {});
  const ms = Date.now() - t0;
  if (err) fail++; else pass++;
  rows.push([s.area, s.name, err ? 'FAIL' : 'pass', ms, err || '', (page && page.measure) || '']);
  console.log((err ? 'FAIL ' : 'pass ') + String(ms).padStart(6) + ' ms  ' + s.area.padEnd(10) + s.name + (err ? '\n       ' + err : ''));
}
console.log(`\n${pass} passed, ${fail} failed, of ${pass + fail}`);
if (outCsv) writeFileSync(outCsv, 'area,spec,result,ms,failure,measure\n' + rows.map((r) => r.map((x) => '"' + String(x).replace(/"/g, '""') + '"').join(',')).join('\n') + '\n');
await browser.close();
process.exit(fail ? 1 : 0);
