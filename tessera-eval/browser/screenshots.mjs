// Screenshots of Tessera Studio for docs/screenshots: 2x pixel density, one view per picture.
//
//   node screenshots.mjs <studio url> <out dir> [scene ...]
//
// Start the Studio from the repository root first, with the examples on its source path:
//   tessera-studio/build/install/tessera-studio/bin/tessera-studio --port 8811 --src examples/src/main/java
// The Prove scenes need an SMT solver on the Studio's PATH (z3, bitwuzla or yices); the critical-path scenes
// read the Vivado reports in tessera-studio/src/test/resources/timing. Scene names are the file names without
// .png; with none given, every scene is taken. Each scene starts from a clean browser.
import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { mkdirSync } from 'node:fs';

const [url, out, ...only] = process.argv.slice(2);
if (!url || !out) {
  console.error('usage: node screenshots.mjs <studio url> <out dir> [scene ...]');
  process.exit(2);
}
mkdirSync(out, { recursive: true });
const here = dirname(fileURLToPath(import.meta.url));
const fixtures = resolve(here, 'fixtures');
const reports = resolve(here, '../../tessera-studio/src/test/resources/timing');
const browser = await chromium.launch();
const errors = [];
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });
const W = 1600, H = 1000;

const CORE = {
  C5: 'C5 five-stage, scheduled (Level 2)',
  C7: 'C7 out-of-order, 16-entry ROB / 8 RS',
  C7g: 'C7g out-of-order, generated from the model (C7\'s organisation), 16-entry ROB / 8 RS',
  C9lvt: 'C9-lvt dual-issue five-stage, live-value-table register file',
  C10: 'C10 dual-issue five-stage with branch prediction (64-entry BTB), scheduled (Level 2)',
  C11: 'C11 out-of-order with execute-time branch recovery and a load/store queue, 16-entry ROB / 8 RS / 4 checkpoints / 4+4 LSQ',
  C11tdp: 'C11-tdp C11 with a true dual-port data memory (loads on port A, committed stores on port B), 16-entry ROB / 8 RS / 4 checkpoints / 4+4 LSQ',
};

// a clean page on a drawing (the default counter when doc is null), in the given theme
async function fresh(doc = null, { w = W, h = H, theme = 'dark' } = {}) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/status of 4\d\d/.test(m.text())) errors.push(m.text()); });
  await page.goto(url);
  await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.canvas);
  await page.evaluate((d) => { localStorage.clear(); if (d) localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, doc);
  await page.reload();
  await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.gen && Studio.app.gen.java, null, { timeout: 30000 });
  await page.waitForTimeout(800);
  await page.evaluate((t) => { if (document.documentElement.dataset.theme !== t) Studio.app.theme(); }, theme);
  await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));
  await page.waitForTimeout(500);
  return page;
}

// a page with one of the RISC-V cores open in the explorer, restarted and stepped
async function core(id, cycles = 0, opts = {}) {
  const page = await fresh(null, opts);
  await page.evaluate(([d, prog]) => Studio.app.openDesign(d, prog), [CORE[id] || id, opts.program]);
  await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 90000 });
  await page.waitForTimeout(1500);
  await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));
  if (cycles) await page.evaluate((n) => Studio.app.sim({ cmd: 'step', n }), cycles);
  await page.waitForTimeout(800);
  return page;
}

const sim = (page, cmd) => page.evaluate((c) => Studio.app.sim(c), cmd);
const dock = (page, px) => page.evaluate((px) => { document.querySelector('.dock').style.height = px + 'px'; }, px);
const toastGone = (page) => page.waitForTimeout(5500);

async function save(page, name, clip) {
  const file = resolve(out, name + '.png');
  if (typeof clip === 'string') {
    const b = await page.locator(clip).first().boundingBox();
    clip = { x: Math.max(0, b.x - 4), y: Math.max(0, b.y - 4), width: Math.min(b.width + 8, W), height: Math.min(b.height + 8, H) };
  }
  await page.screenshot({ path: file, clip: clip || undefined });
  console.log('wrote', name + '.png');
}

const scenes = [];
const scene = (name, fn) => scenes.push({ name, fn });

// ---------------------------------------------------------------- the drawing

const COUNTER_ASSERT = (page) => page.evaluate(() => {
  const doc = Studio.app.doc, r = doc.d.parts.find((p) => p.type === 'reg');
  const k = doc.addPart('const', r.x + 2, r.y + 12, { value: '5', width: 8 });
  const c = doc.addPart('cmp', r.x + 12, r.y + 10, { op: 'lt' });
  const a = doc.addPart('assert', r.x + 22, r.y + 11, { name: 'below_five' });
  doc.connect({ part: r.id, pin: 'q' }, { part: c.id, pin: 'a' });
  doc.connect({ part: k.id, pin: 'y' }, { part: c.id, pin: 'b' });
  doc.connect({ part: c.id, pin: 'y' }, { part: a.id, pin: 'cond' });
});

scene('01-overview', async () => {
  const page = await fresh();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  await sim(page, { cmd: 'run' });
  await page.waitForTimeout(3500);
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'reg').id]));
  await page.waitForTimeout(800);
  await save(page, '01-overview');
  await sim(page, { cmd: 'pause' });
  return page;
});

scene('02-overview-light', async () => {
  const page = await fresh(null, { theme: 'light' });
  await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  await sim(page, { cmd: 'step', n: 40 });
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'reg').id]));
  await page.waitForTimeout(1000);
  await save(page, '02-overview-light');
  return page;
});

scene('03-designs-menu', async () => {
  const page = await fresh();
  await page.locator('button.btn .lb', { hasText: /^Designs$/ }).click();
  await page.waitForSelector('.menu .mi');
  await page.waitForTimeout(500);
  await save(page, '03-designs-menu');
  await page.locator('.menu .mi', { hasText: 'Out-of-order variants of C11' }).hover();
  await page.locator('.menu .mi', { hasText: 'Out-of-order variants of C11' }).click();
  await page.waitForFunction(() => [...document.querySelectorAll('.menu .mi')].some((e) => e.textContent.startsWith('C11-tdp')));
  await page.waitForTimeout(500);
  await save(page, '04-designs-variants');
  return page;
});

scene('05-palette', async () => {
  const page = await fresh();
  await page.locator('.pal .search input').fill('reg');
  await page.waitForTimeout(600);
  await save(page, '05-palette', { x: 0, y: 0, width: 520, height: H });
  return page;
});

scene('06-problems', async () => {
  // an 8-bit register driven from a 4-bit input, and an output nobody drives: the problems, as you draw
  const doc = { name: 'Mismatch', title: 'Widths that do not agree', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'a', width: 4 }), P('b', 'in', 2, 8, { name: 'b', width: 8 }), P('s', 'add', 14, 4, { mode: 'wrap' }),
      P('r', 'reg', 26, 4, { name: 'acc', width: 8, init: '0' }), P('y', 'out', 40, 4, { name: 'y', width: 8 }), P('z', 'out', 40, 12, { name: 'unused', width: 8 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['s', 'a']] }, { id: 'n2', pins: [['b', 'y'], ['s', 'b']] }, { id: 'n3', pins: [['s', 'y'], ['r', 'd']] }, { id: 'n4', pins: [['r', 'q'], ['y', 'a']] }] };
  const page = await fresh(doc);
  await page.waitForTimeout(1500);
  await page.evaluate(() => Studio.app.showTab('problems'));
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1200);
  await save(page, '06-problems');
  return page;
});

scene('07-java', async () => {
  const page = await fresh();
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '640px'));
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'reg').id]));
  await page.locator('.tab[data-tab="code"]').click();
  await page.waitForTimeout(800);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(800);
  await save(page, '07-java');
  return page;
});

scene('08-java-edit', async () => {
  const page = await fresh();
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '640px'));
  await page.locator('.tab[data-tab="code"]').click();
  await page.locator('.code-pane .btn', { hasText: 'Edit' }).click();
  const ed = page.locator('textarea.java-ed');
  const text = await ed.inputValue();
  await ed.fill(text.replace(/(\n\s*)(output\()/, '$1output("odd", Bool.TYPE).set(count.bit(0));$1$2'));
  await page.waitForTimeout(600);
  await save(page, '08-java-edit');
  // apply: the drawing follows, and the running simulation is reloaded in place (hot swap)
  await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  await sim(page, { cmd: 'step', n: 30 });
  await page.locator('.code-edbox .btn.primary').first().click();
  await page.waitForTimeout(2500);
  await sim(page, { cmd: 'step', n: 30 });
  await page.evaluate(() => { document.querySelector('.app').style.setProperty('--right', '420px'); Studio.app.waves.add('odd'); });
  await dock(page, 380);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1500);
  await save(page, '09-hot-swap');
  return page;
});

scene('10-fsm', async () => {
  const doc = {
    name: 'Handshake', title: 'A request and drain controller', package: 'studio', next: 100, notes: [],
    parts: [P('g', 'in', 2, 6, { name: 'go', width: 1 }), P('s', 'in', 2, 10, { name: 'stop', width: 1 }),
      P('f', 'fsm', 18, 4, { name: 'ctl', states: 'IDLE RUN DRAIN DONE', inputs: 'go stop',
        transitions: 'IDLE -> RUN : go\nRUN -> DRAIN : stop\nRUN -> RUN : go & !stop\nDRAIN -> DONE : !go\nDONE -> IDLE : 1', outputs: 'busy : RUN DRAIN\nready : IDLE' }),
      P('o1', 'out', 40, 6, { name: 'busy', width: 1 }), P('o2', 'out', 40, 10, { name: 'ready', width: 1 }), P('o3', 'out', 40, 14, { name: 'st', width: 2 })],
    nets: [{ id: 'w1', pins: [['g', 'y'], ['f', 'go']] }, { id: 'w2', pins: [['s', 'y'], ['f', 'stop']] },
      { id: 'w3', pins: [['f', 'busy'], ['o1', 'a']] }, { id: 'w4', pins: [['f', 'ready'], ['o2', 'a']] }, { id: 'w5', pins: [['f', 'state'], ['o3', 'a']] }],
  };
  const page = await fresh(doc);
  await sim(page, { cmd: 'poke', path: 'go', value: '1' });
  await sim(page, { cmd: 'step' });
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:f']));
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '560px'));
  await page.waitForTimeout(500);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(800);
  await page.evaluate(() => document.querySelector('.fsm-d').scrollIntoView({ block: 'end' }));
  await page.waitForTimeout(400);
  await save(page, '10-fsm');
  return page;
});

scene('11-memory', async () => {
  // a memory part: its words in the inspector, lit where they changed
  const doc = { name: 'Scratch', title: 'A memory written by a counter', package: 'studio', next: 100, notes: [],
    parts: [P('c', 'counter', 2, 2, { max: 15 }), P('d', 'const', 2, 8, { value: '0xa5', width: 8 }), P('k', 'const', 2, 13, { value: '1', width: 1 }),
      P('m', 'mem', 20, 2, { name: 'ram', width: 8, depth: 16, init: '0x00 0x11 0x22 0x33 0x44 0x55 0x66 0x77 0x88 0x99 0xaa 0xbb 0xcc 0xdd 0xee 0xff' }),
      P('y', 'out', 40, 4, { name: 'q', width: 8 })],
    nets: [{ id: 'n1', pins: [['c', 'value'], ['m', 'addr']] }, { id: 'n2', pins: [['d', 'y'], ['m', 'wdata']] }, { id: 'n3', pins: [['k', 'y'], ['m', 'we'], ['c', 'inc']] }, { id: 'n4', pins: [['m', 'rdata'], ['y', 'a']] }] };
  const page = await fresh(doc);
  await page.waitForTimeout(1200);
  await sim(page, { cmd: 'step', n: 6 });
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:m']));
  await page.evaluate(() => Studio.app.showTab('inspect'));
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1500);
  await save(page, '11-memory');
  return page;
});

scene('12-waves', async () => {
  const page = await fresh();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  await sim(page, { cmd: 'step', n: 60 });
  await page.waitForTimeout(1200);
  const names = page.locator('.wv-name');
  await names.nth(1).click();
  await names.nth(2).click({ modifiers: ['Shift'] });
  await page.locator('.wv-plot').focus();
  await page.keyboard.press('g');
  await page.waitForTimeout(300);
  await page.locator('.modal input').fill('counter');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  await page.locator('.wv-name', { hasText: 'count' }).locator('.wv-exp').click();
  await page.waitForTimeout(500);
  const plot = await page.locator('.wv-plot').boundingBox();
  await page.mouse.click(plot.x + plot.width * 0.55, plot.y + 40);
  await page.waitForTimeout(400);
  await page.locator('.wv-plot').focus();
  await page.keyboard.press('m');
  await page.waitForTimeout(200);
  await page.locator('.modal input').fill('here');
  await page.keyboard.press('Enter');
  await dock(page, 520);
  await page.waitForTimeout(1200);
  await save(page, '12-waves', '.dock');
  return page;
});

scene('13-time-travel', async () => {
  const page = await fresh();
  await sim(page, { cmd: 'poke', path: 'en', value: '1' });
  await sim(page, { cmd: 'step', n: 120 });
  await page.waitForTimeout(1000);
  await dock(page, 330);
  await page.waitForTimeout(600);
  // back to an earlier cycle by clicking the waves: everything shows that cycle, the live dot turns amber
  const plot = await page.locator('.wv-plot').boundingBox();
  await page.mouse.click(plot.x + plot.width * 0.35, plot.y + 30);
  await page.waitForTimeout(1200);
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'reg').id]));
  await page.waitForTimeout(1000);
  await save(page, '13-time-travel');
  return page;
});

scene('14-clocks', async () => {
  const doc = {
    name: 'TwoClocks', title: 'A counter on each of two clocks', package: 'studio', next: 100, notes: [],
    parts: [P('c', 'domain', 2, 24, { name: 'clock', period: 3, phase: 0 }), P('f', 'domain', 16, 24, { name: 'fast', period: 1, phase: 0 }),
      P('rs', 'reg', 24, 2, { name: 'count', width: 8, init: '0' }), P('ks', 'const', 2, 5, { value: '1', width: 8 }), P('as', 'add', 10, 2, { mode: 'wrap' }),
      P('rf', 'reg', 24, 13, { name: 'fcount', width: 8, init: '0', domain: 'fast' }), P('kf', 'const', 2, 16, { value: '1', width: 8 }), P('af', 'add', 10, 13, { mode: 'wrap' }),
      P('sl', 'slice', 36, 14, { hi: 0, lo: 0 }), P('sy', 'sync', 46, 14, { stages: 2 }), P('os', 'out', 58, 3, { name: 'slow_out', width: 8 }), P('o3', 'out', 58, 14, { name: 'seen', width: 1 })],
    nets: [{ id: 'n1', pins: [['rs', 'q'], ['as', 'a'], ['os', 'a']] }, { id: 'n2', pins: [['ks', 'y'], ['as', 'b']] }, { id: 'n3', pins: [['as', 'y'], ['rs', 'd']] },
      { id: 'm1', pins: [['rf', 'q'], ['af', 'a'], ['sl', 'a']] }, { id: 'm2', pins: [['kf', 'y'], ['af', 'b']] }, { id: 'm3', pins: [['af', 'y'], ['rf', 'd']] },
      { id: 'x1', pins: [['sl', 'y'], ['sy', 'a']] }, { id: 'x2', pins: [['sy', 'y'], ['o3', 'a']] }],
  };
  const page = await fresh(doc);
  await sim(page, { cmd: 'step', n: 24 });
  await page.evaluate(() => { Studio.app.waves.add('fcount'); Studio.app.waves.add('count'); });
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1800);
  await save(page, '14-clocks');
  // the Prove pane on this drawing: the options for separate clocks and asynchronous resets
  await page.evaluate(() => Studio.app.showTab('prove'));
  await page.locator('.prove-opts summary').click();
  await page.waitForTimeout(800);
  await save(page, '15-prove-pane', 'aside.side.right');
  return page;
});

scene('16-prove', async () => {
  const page = await fresh();
  await COUNTER_ASSERT(page);
  await page.waitForTimeout(2500);
  await page.locator('.tab[data-tab="prove"]').click();
  await page.locator('button', { hasText: 'Bounded' }).click();
  await page.waitForFunction(() => !Studio.app.prove.busy, null, { timeout: 60000 });
  await toastGone(page);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(800);
  await save(page, '16-prove-counterexample');
  // replay: go to the failing cycle; canvas, inspector and waves all show it
  const go = page.locator('button', { hasText: /failing cycle/i });
  if (await go.count()) {
    await go.first().click();
    await page.waitForTimeout(1500);
    await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'assert').id]));
    await page.waitForTimeout(800);
    await save(page, '17-prove-replay');
  }
  return page;
});

scene('18-bench', async () => {
  const page = await fresh();
  await page.evaluate(() => Studio.app.showTab('bench'));
  await page.locator('textarea.bench-ed').fill(`import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterBench {
    @Test
    void countsWhileEnabled(Simulator sim) {
        long c0 = sim.peek("value");
        sim.poke("en", 1);
        sim.step(10);
        assertEquals((c0 + 10) & 0xff, sim.peek("value"));
    }

    @Test
    void holdsWhenDisabled(Simulator sim) {
        sim.poke("en", 0);
        long v = sim.peek("value");
        sim.step(5);
        assertEquals(v + 1, sim.peek("value"), "expects a step it should not");
    }
}
`);
  await page.locator('button', { hasText: 'Run the tests' }).click();
  await page.waitForSelector('.bench-sum', { timeout: 60000 });
  await dock(page, 520);
  await toastGone(page);
  await save(page, '18-bench', '.dock');
  return page;
});

scene('19-board', async () => {
  const doc = {
    name: 'Panel', title: 'Switches to LEDs and a hex digit', package: 'studio', next: 100, notes: [],
    parts: [P('s', 'in', 2, 2, { name: 'sw', width: 8, look: 'switches' }), P('l', 'out', 30, 2, { name: 'led', width: 8, look: 'leds' }),
      P('x', 'out', 30, 10, { name: 'hex', width: 8, look: 'hex' }), P('b', 'in', 2, 12, { name: 'btn_n', width: 4, look: 'buttons' }), P('h', 'out', 30, 20, { name: 'held', width: 4, look: 'leds' })],
    nets: [{ id: 'n1', pins: [['s', 'y'], ['l', 'a'], ['x', 'a']] }, { id: 'n2', pins: [['b', 'y'], ['h', 'a']] }],
  };
  const page = await fresh(doc);
  await page.evaluate(() => Studio.app.tidy());
  await sim(page, { cmd: 'poke', path: 'sw', value: 'a5' });
  await sim(page, { cmd: 'poke', path: 'btn_n', value: '2' });
  await page.evaluate(() => Studio.app.showTab('board'));
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1800);
  await save(page, '19-board');
  return page;
});

scene('20-timing-drawing', async () => {
  const mac = { name: 'Mac', title: 'A multiply-accumulate', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'a', width: 32 }), P('b', 'in', 2, 10, { name: 'b', width: 32 }),
      P('ra', 'reg', 12, 2, { name: 'ra', width: 32, init: '0' }), P('rb', 'reg', 12, 10, { name: 'rb', width: 32, init: '0' }),
      P('m', 'mul', 26, 5), P('s', 'add', 40, 14, { mode: 'wrap' }),
      P('acc', 'reg', 56, 12, { name: 'acc', width: 64, init: '0' }), P('y', 'out', 70, 7, { name: 'y', width: 64 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['ra', 'd']] }, { id: 'n2', pins: [['b', 'y'], ['rb', 'd']] },
      { id: 'n3', pins: [['ra', 'q'], ['m', 'a']] }, { id: 'n4', pins: [['rb', 'q'], ['m', 'b']] },
      { id: 'n5', pins: [['m', 'y'], ['s', 'a']] }, { id: 'n6', pins: [['s', 'y'], ['acc', 'd']] },
      { id: 'n7', pins: [['acc', 'q'], ['s', 'b'], ['y', 'a']] }] };
  const page = await fresh(mac);
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('.cp input[type=file]').setInputFiles(resolve(fixtures, 'mac.paths.rpt'));
  await page.waitForFunction(() => document.querySelectorAll('.cp-row').length > 0, null, { timeout: 60000 });
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(600);
  await page.evaluate(() => document.querySelector('.cp').scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(800);
  await save(page, '20-timing-drawing');
  return page;
});

scene('21-analyse', async () => {
  const page = await fresh();
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.waitForTimeout(1000);
  await page.locator('button', { hasText: 'Logic levels' }).first().click();
  await page.waitForTimeout(2000);
  const est = page.locator('button', { hasText: /Estimate resources/i });
  if (await est.count()) {
    await est.first().click();
    await page.waitForTimeout(12000);
  }
  await save(page, '21-analyse');
  return page;
});

scene('22-fpga', async () => {
  const doc = {
    name: 'Panel', title: 'Switches, LEDs and a counted button', package: 'studio', next: 100, notes: [],
    parts: [P('s', 'in', 2, 2, { name: 'sw', width: 8 }), P('l', 'out', 30, 2, { name: 'led', width: 8 }),
      P('b', 'in', 2, 10, { name: 'btn', width: 1 }), P('e', 'edge', 12, 10, { kind: 'rising' }),
      P('c', 'counter', 20, 10, { max: 255 }), P('h', 'out', 36, 10, { name: 'hex0', width: 8 })],
    nets: [{ id: 'w1', pins: [['s', 'y'], ['l', 'a']] }, { id: 'w2', pins: [['b', 'y'], ['e', 'a']] },
      { id: 'w3', pins: [['e', 'y'], ['c', 'inc']] }, { id: 'w4', pins: [['c', 'value'], ['h', 'a']] }],
  };
  const page = await fresh(doc);
  await page.locator('.tab[data-tab="fpga"]').click();
  await page.waitForTimeout(800);
  const check = page.locator('button', { hasText: /Check the ports/i });
  if (await check.count()) { await check.first().click(); await page.waitForTimeout(2500); }
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(800);
  await save(page, '22-fpga');
  return page;
});

scene('23-guide', async () => {
  const page = await fresh();
  await page.evaluate(() => Studio.app.guide());
  await page.waitForTimeout(3000);
  await save(page, '23-guide');
  return page;
});

// ---------------------------------------------------------------- the RISC-V cores

scene('24-explorer-c5', async () => {
  const page = await core('C5');
  await page.locator('.sig', { hasText: 'core_pc' }).first().click();
  await page.waitForTimeout(2500);
  await sim(page, { cmd: 'step', n: 200 });
  await page.waitForTimeout(1500);
  await save(page, '24-explorer-c5');
  return page;
});

scene('25-regfile', async () => {
  // a riscv-test run to its end; the register file in the inspector (x3 = gp = 1: the test passed)
  const page = await core('C5', 0, { program: 'rv32ui-add' });
  await sim(page, { cmd: 'rate', rate: 0 });
  await sim(page, { cmd: 'until', expr: 'core_halted == 1' });
  await page.waitForFunction(() => !Studio.app.running, null, { timeout: 60000 });
  await memory(page, 'regs', 32, 32, 'The register file of C5 after rv32ui-add: x3 (gp) is 1, the test passed.', 'core_pc');
  await save(page, '25-regfile');
  return page;
});

async function memory(page, name, width, depth, title, signal) {
  if (signal) {
    await page.locator('aside.side.left input').first().fill(signal);
    await page.waitForTimeout(500);
    await page.locator('.sig', { hasText: signal }).first().click();
    await page.waitForTimeout(2500);
  }
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '640px'));
  await page.waitForTimeout(400);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.evaluate(() => Studio.app.showTab('inspect'));
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '640px'));
  await page.evaluate(([name, width, depth, title]) => {
    const I = Studio.app.inspector, h = (t, x) => Object.assign(document.createElement(t), { textContent: x });
    const head = h('h2', name), sub = h('div', title);
    sub.className = 'sub';
    I.partId = name;
    I.root.replaceChildren(head, sub, I.memView({ id: name, params: { name, width, depth } }));
  }, [name, width, depth, title]);
  await page.waitForTimeout(2000);
}

async function pipe(id, cycles, name, opts = {}) {
  const page = await core(id, cycles, opts);
  await page.evaluate(() => { const a = document.querySelector('.app'); a.style.setProperty('--left', '0px'); a.style.setProperty('--right', '0px'); });
  await dock(page, 860);
  await page.evaluate(() => Studio.app.showTab('pipe'));
  await page.waitForSelector('.pv-row', { timeout: 30000 });
  if (opts.sched) await page.evaluate(() => { const s = document.querySelector('.pv-sched'); if (s) s.open = true; });
  await page.evaluate(() => Studio.app.pipeview.refresh());
  await toastGone(page);
  await save(page, name, '.dock');
  return page;
}

scene('26-pipeline-c5', () => pipe('C5', 120, '26-pipeline-c5', { sched: true }));
scene('27-pipeline-c10', () => pipe('C10', 120, '27-pipeline-c10'));
scene('28-rob-c11', () => pipe('C11', 200, '28-rob-c11'));
scene('29-rob-c7g', () => pipe('C7g', 200, '29-rob-c7g', { sched: true }));

scene('30-memory-tdp', async () => {
  // C11-tdp: the data memory with two ports, speculative loads on A and committed stores on B
  const page = await core('C11tdp', 400);
  await memory(page, 'dmem', 32, 4096, 'The true dual-port data memory of C11-tdp: port A serves speculative loads, port B committed stores.', 'dmem_qa');
  await page.evaluate(() => { const w = Studio.app.waves; w.remove(w.rows.map((r) => w.key(r))); for (const x of ['dmem_qa', 'dmem_qb', 'dmem_b_we', 'dmem_b_wdata']) w.add(x); });
  await page.waitForTimeout(1500);
  await save(page, '30-memory-tdp');
  return page;
});

scene('31-lvt', async () => {
  const page = await core('C9lvt', 0, { program: 'rv32ui-add' });
  await sim(page, { cmd: 'rate', rate: 0 });
  await sim(page, { cmd: 'until', expr: 'core_halted == 1' });
  await page.waitForFunction(() => !Studio.app.running, null, { timeout: 60000 });
  await memory(page, 'regs_lvt', 1, 32, 'The live-value table of C9-lvt: for each register, the write port (bank) that wrote it last.', 'regs_r0_data');
  await save(page, '31-lvt-table');
  await page.locator('button', { hasText: 'Show the registers' }).click();
  await page.waitForTimeout(2000);
  await save(page, '31-lvt-registers');
  return page;
});

scene('32-waves-c10', async () => {
  const page = await core('C10', 0);
  await page.evaluate(() => {
    const w = Studio.app.waves;
    w.remove(w.rows.map((r) => w.key(r)));
    for (const s of ['rvfi_valid', 'rvfi_pc_rdata', 'rvfi_insn', 'rvfi1_valid', 'rvfi1_pc_rdata', 'rvfi1_insn', 'core_pair', 'core_split']) w.add(s);
  });
  await sim(page, { cmd: 'step', n: 80 });
  await dock(page, 520);
  await page.waitForTimeout(2500);
  await save(page, '32-waves-c10');
  return page;
});

scene('33-timing-c11', async () => {
  // C11's worst paths from its Vivado report, each step mapped to the Java line that made it
  const page = await core('C11');
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('.cp input[type=file]').setInputFiles(resolve(reports, 'core_c11.paths.rpt'));
  await page.waitForFunction(() => document.querySelectorAll('.cp-row').length > 0, null, { timeout: 90000 });
  await page.waitForTimeout(1200);
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '760px'));
  await page.waitForTimeout(800);
  await page.evaluate(() => document.querySelector('.cp-detail').scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(600);
  await save(page, '33-timing-c11');
  return page;
});

scene('34-analyse-c5', async () => {
  // the pipeline generator's schedule for C5, in the Analyse pane
  const page = await core('C5');
  await page.evaluate(() => document.querySelector('.app').style.setProperty('--right', '760px'));
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.waitForTimeout(1000);
  await page.locator('button', { hasText: 'Logic levels' }).first().click();
  await page.waitForTimeout(6000);
  await save(page, '34-analyse-c5');
  return page;
});

// ---------------------------------------------------------------- run

for (const s of scenes) {
  if (only.length && !only.some((o) => s.name.startsWith(o))) continue;
  const t0 = Date.now();
  let page = null;
  try {
    page = await s.fn();
  } catch (e) {
    console.log('FAILED', s.name, e.message.split('\n')[0]);
  }
  if (page) await page.close().catch(() => {});
  console.log('  ', s.name, ((Date.now() - t0) / 1000).toFixed(1) + ' s');
}
console.log(errors.length ? 'page errors:\n  ' + errors.join('\n  ') : 'no page errors');
await browser.close();
