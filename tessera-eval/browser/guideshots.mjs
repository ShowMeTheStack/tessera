// The guide's pictures, taken from the tool as it is: node guideshots.mjs <url> <docs/studio-guide dir>
// Each scene starts from a clean browser; the server should run with --src examples/src/main/java.
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch();
const errors = [];
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });

async function fresh(doc, w = 1440, h = 900) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url);
  await page.waitForTimeout(1200);
  await page.evaluate((d) => { localStorage.clear(); if (d) localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, doc || null);
  await page.reload();
  await page.waitForTimeout(2200);
  await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));   // a new run: no marks or waves from before
  await page.waitForTimeout(400);
  return page;
}

// numbered callouts over the page for a picture: [selector, number, where] (the guide's text says what each
// number points at); where is 'tl' (top left, the default), 'tr', 'c', 'l' or 'r' of the element
async function annotate(page, marks) {
  await page.evaluate((marks) => {
    const layer = document.createElement('div');
    layer.id = 'guide-marks';
    layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:9999';
    for (const [sel, n, where] of marks) {
      const el = document.querySelector(sel);
      if (!el) { console.error('annotate: no ' + sel); continue; }
      const b = el.getBoundingClientRect();
      // b: under the element's middle, clear of its label (for toolbar buttons)
      const [x, y] = { tr: [b.right - 6, b.top + 6], c: [b.left + b.width / 2, b.top + b.height / 2], l: [b.left - 4, b.top + b.height / 2], r: [b.right + 4, b.top + b.height / 2],
        b: [b.left + b.width / 2, b.bottom + 14], br: [b.right - 14, b.bottom + 14] }[where] || [b.left + 6, b.top + 6];
      const m = document.createElement('div');
      m.textContent = n;
      m.style.cssText = `position:fixed;left:${x - 12}px;top:${y - 12}px;width:24px;height:24px;border-radius:12px;background:#ff5d8f;color:#fff;font:700 13px/24px system-ui;text-align:center;box-shadow:0 0 0 2px #fff,0 2px 6px rgba(0,0,0,.5)`;
      layer.append(m);
    }
    document.body.append(layer);
  }, marks);
}

// 1. overview: the counter running, waves seeded
{
  const page = await fresh();
  await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'en', value: '1' }));
  await page.evaluate(() => Studio.app.sim({ cmd: 'run' }));
  await page.waitForTimeout(3500);
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((p) => p.type === 'reg').id]));
  await page.waitForTimeout(600);
  await annotate(page, [['.pal .search', 1, 'r'], ['.cv .part.sel', 2, 'tr'], ['[data-key-id="runPause"]', 3, 'tl'], ['.pill.cycle', 4, 'l'],
    ['.insp h2', 5, 'l'], ['.dock .tabs', 6, 'r']]);
  await page.screenshot({ path: out + '/overview.png' });
  await page.close();
}

// 2. a state machine with its diagram
{
  const doc = {
    name: 'Handshake', title: 'A request and drain controller', package: 'studio', next: 100, notes: [],
    parts: [
      P('g', 'in', 2, 6, { name: 'go', width: 1 }), P('s', 'in', 2, 10, { name: 'stop', width: 1 }),
      P('f', 'fsm', 18, 4, { name: 'ctl', states: 'IDLE RUN DRAIN DONE', inputs: 'go stop',
        transitions: 'IDLE -> RUN : go\nRUN -> DRAIN : stop\nRUN -> RUN : go & !stop\nDRAIN -> DONE : !go\nDONE -> IDLE : 1', outputs: 'busy : RUN DRAIN\nready : IDLE' }),
      P('o1', 'out', 40, 6, { name: 'busy', width: 1 }), P('o2', 'out', 40, 10, { name: 'ready', width: 1 }), P('o3', 'out', 40, 14, { name: 'st', width: 2 }),
    ],
    nets: [
      { id: 'w1', pins: [['g', 'y'], ['f', 'go']] }, { id: 'w2', pins: [['s', 'y'], ['f', 'stop']] },
      { id: 'w3', pins: [['f', 'busy'], ['o1', 'a']] }, { id: 'w4', pins: [['f', 'ready'], ['o2', 'a']] }, { id: 'w5', pins: [['f', 'state'], ['o3', 'a']] },
    ],
  };
  const page = await fresh(doc);
  await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'go', value: '1' }));
  await page.evaluate(() => Studio.app.sim({ cmd: 'step' }));
  await page.evaluate(() => Studio.app.canvas.selectOnly(['p:f']));
  await page.waitForTimeout(400);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(800);
  await page.evaluate(() => document.querySelector('.fsm-d').scrollIntoView({ block: 'end' }));
  await page.waitForTimeout(300);
  await page.screenshot({ path: out + '/fsm.png', clip: { x: 248, y: 44, width: 1440 - 248, height: 900 - 44 } });
  await page.close();
}

// 3. the explorer on the C5 core
{
  const page = await fresh(null, 1440, 900);
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForTimeout(2500);
  await page.locator('.sig', { hasText: 'core_pc' }).first().click();
  await page.waitForTimeout(2500);
  await page.evaluate(() => Studio.app.sim({ cmd: 'run' }));
  await page.waitForTimeout(1500);
  await page.screenshot({ path: out + '/explorer.png' });
  await page.close();
}

// 4. the waves: a group, a bus in bits, a marker
{
  const page = await fresh();
  await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'en', value: '1' }));
  await page.evaluate(() => Studio.app.sim({ cmd: 'step', n: 60 }));
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
  await page.evaluate(() => { document.querySelector('.dock').style.height = '470px'; });
  await page.waitForTimeout(900);
  const dock = await page.locator('.dock').boundingBox();
  await annotate(page, [['.wv-group, .wv-name.hd, .wv-hdr', 1, 'r'], ['.wv-name.bit', 2, 'r'], ['.wv-note', 3, 'r']]);
  await page.screenshot({ path: out + '/waves.png', clip: dock });
  await page.close();
}

// 5. Prove: a failing assertion and its counterexample
{
  const page = await fresh();
  await page.evaluate(() => {
    const app = Studio.app, doc = app.doc, r = doc.d.parts.find((p) => p.type === 'reg');
    const k = doc.addPart('const', r.x + 2, r.y + 12, { value: '5', width: 8 });
    const c = doc.addPart('cmp', r.x + 12, r.y + 10, { op: 'lt' });
    const a = doc.addPart('assert', r.x + 22, r.y + 11, { name: 'below_five' });
    doc.connect({ part: r.id, pin: 'q' }, { part: c.id, pin: 'a' });
    doc.connect({ part: k.id, pin: 'y' }, { part: c.id, pin: 'b' });
    doc.connect({ part: c.id, pin: 'y' }, { part: a.id, pin: 'cond' });
  });
  await page.waitForTimeout(2500);
  await page.locator('.tab[data-tab="prove"]').click();
  await page.locator('button', { hasText: 'Bounded' }).click();
  await page.waitForTimeout(8000);   // until the toast has gone
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(800);
  await page.screenshot({ path: out + '/prove.png' });
  await page.close();
}

// 6. the board
{
  const doc = {
    name: 'Panel', title: 'Switches, LEDs and a counted button', package: 'studio', next: 100, notes: [],
    parts: [
      P('s', 'in', 2, 2, { name: 'sw', width: 8 }), P('l', 'out', 30, 2, { name: 'led', width: 8 }),
      P('b', 'in', 2, 10, { name: 'btn', width: 1 }), P('e', 'edge', 12, 10, { kind: 'rising' }),
      P('c', 'counter', 20, 10, { max: 255 }), P('h', 'out', 36, 10, { name: 'hex0', width: 8 }),
    ],
    nets: [
      { id: 'w1', pins: [['s', 'y'], ['l', 'a']] }, { id: 'w2', pins: [['b', 'y'], ['e', 'a']] },
      { id: 'w3', pins: [['e', 'y'], ['c', 'inc']] }, { id: 'w4', pins: [['c', 'value'], ['h', 'a']] },
    ],
  };
  const page = await fresh(doc);
  await page.locator('.tab[data-tab="board"]').click();
  await page.evaluate(() => Studio.app.sim({ cmd: 'run' }));
  for (const b of [7, 5, 2, 0]) await page.locator(`.board .sw[data-bit="${b}"]`).click();
  for (let k = 0; k < 4; k++) {
    const box = await page.locator('.board .push').first().boundingBox();
    await page.mouse.move(box.x + 10, box.y + 10); await page.mouse.down(); await page.waitForTimeout(300); await page.mouse.up(); await page.waitForTimeout(300);
  }
  await page.waitForTimeout(800);
  const panel = await page.locator('.board').boundingBox();
  await page.screenshot({ path: out + '/board.png', clip: { x: panel.x - 4, y: panel.y - 4, width: panel.width + 8, height: Math.min(panel.height + 8, 420) } });
  await page.close();
}

// 7. two clock domains: parts in the fast domain say so; the clock button steps to a clock's next edge
{
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
  await page.evaluate(() => Studio.app.sim({ cmd: 'step', n: 24 }));
  await page.evaluate(() => { Studio.app.waves.add('fcount'); Studio.app.waves.add('count'); });
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1800);
  await annotate(page, [['.cv .part[data-id="rf"]', 1, 'r'], ['.cv .part[data-id="sy"]', 2, 'tr'], ['.cv .part[data-id="f"]', 3, 'tr'], ['[title^="To the next edge"]', 4, 'r']]);
  await page.screenshot({ path: out + '/clocks.png' });
  await page.close();
}

// 8. board parts on the drawing, a testbench, and the serial terminal
{
  const doc = {
    name: 'Panel', title: 'Switches to LEDs and a hex digit', package: 'studio', next: 100, notes: [],
    parts: [P('s', 'in', 2, 2, { name: 'sw', width: 8, look: 'switches' }), P('l', 'out', 30, 2, { name: 'led', width: 8, look: 'leds' }),
      P('x', 'out', 30, 10, { name: 'hex', width: 8, look: 'hex' }), P('b', 'in', 2, 12, { name: 'btn_n', width: 4, look: 'buttons' }), P('h', 'out', 30, 20, { name: 'held', width: 4, look: 'leds' })],
    nets: [{ id: 'n1', pins: [['s', 'y'], ['l', 'a'], ['x', 'a']] }, { id: 'n2', pins: [['b', 'y'], ['h', 'a']] }],
  };
  const page = await fresh(doc);
  await page.evaluate(() => Studio.app.tidy());
  await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'sw', value: 'a5' }));
  await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'btn_n', value: '2' }));
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(1800);
  const cv = await page.locator('.cv').boundingBox();
  await annotate(page, [['.cv .part[data-id="s"] [data-bit="7"]', 1, 'tl'], ['.cv .part[data-id="b"]', 2, 'tl'], ['.cv .part[data-id="x"]', 3, 'tr']]);
  await page.screenshot({ path: out + '/boardparts.png', clip: cv });
  await page.close();
}
{
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
  await page.waitForSelector('.bench-sum');
  await page.evaluate(() => { document.querySelector('.dock').style.height = '420px'; });
  await page.waitForTimeout(800);
  const dock = await page.locator('.dock').boundingBox();
  await page.waitForTimeout(5500);   // until the toast has gone
  await annotate(page, [['.bench-row.ok', 1, 'tr'], ['.bench-row.bad', 2, 'tr'], ['.bench-reset', 3, 'b'], ['.bench-reset + button', 4, 'br']]);
  await page.screenshot({ path: out + '/bench.png', clip: dock });
  await page.close();
}
{
  const doc = { name: 'Loop', title: 'rx straight back to tx', package: 'studio', next: 10, notes: [],
    parts: [P('i', 'in', 2, 4, { name: 'rx', width: 1 }), P('o', 'out', 16, 4, { name: 'tx', width: 1 })], nets: [{ id: 'n', pins: [['i', 'y'], ['o', 'a']] }] };
  const page = await fresh(doc);
  await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));
  await page.evaluate(() => Studio.app.showTab('board'));
  await page.waitForTimeout(600);
  await page.locator('.uart-in').fill('hello, tessera'); await page.locator('.uart-in').press('Enter');
  await page.waitForTimeout(300);
  await page.evaluate(() => Studio.app.sim({ cmd: 'step', n: 1400 }));
  await page.waitForTimeout(1500);
  const panel = await page.locator('.uart').boundingBox();
  await page.screenshot({ path: out + '/uart.png', clip: { x: panel.x - 6, y: panel.y - 6, width: panel.width + 12, height: panel.height + 12 } });
  await page.close();
}

// 9. editing the Java: Edit, change, ⌘Enter, and the drawing follows
{
  const page = await fresh();
  await page.locator('.tab[data-tab="code"]').click();
  await page.locator('.code-pane .btn', { hasText: 'Edit' }).click();
  const ed = page.locator('textarea.java-ed');
  const text = await ed.inputValue();
  await ed.fill(text.replace(/(\n\s*)(output\()/, '$1output("odd", Bool.TYPE).set(count.bit(0));$1$2'));
  await page.waitForTimeout(300);
  await annotate(page, [['textarea.java-ed', 1, 'tr'], ['.code-edbox .btn.primary', 2, 'l']]);
  await page.screenshot({ path: out + '/javaedit.png' });
  await page.close();
}

// 10. the pipeline diagram of the C5 core, with the schedule in words
{
  const page = await fresh();
  await page.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
  await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));
  await page.evaluate(() => Studio.app.sim({ cmd: 'step', n: 47 }));
  await page.evaluate(() => { document.querySelector('.dock').style.height = '470px'; });
  await page.evaluate(() => Studio.app.showTab('pipe'));
  await page.waitForSelector('.pv-row');
  await page.evaluate(() => { document.querySelector('.pv-sched').open = true; });
  await page.evaluate(() => Studio.app.pipeview.refresh());
  await page.waitForTimeout(5500);   // until the toast has gone
  const dock = await page.locator('.dock').boundingBox();
  // the marks sit at the right of an instruction's name, where later rows leave room
  await annotate(page, [['.pv-row:nth-child(10) td.pv-ins', 1, 'r'], ['.pv-row:nth-child(14) td.pv-ins', 2, 'r'], ['.pv-row:nth-child(12) td.pv-ins', 3, 'r'],
    ['.pv-cy.now', 4, 'tr'], ['.pv-sched summary', 5, 'tl']]);
  await page.screenshot({ path: out + '/pipeline.png', clip: dock });
  await page.close();
}

// 11. timing paths: a Vivado report of a drawing, its worst path numbered on the canvas; and C5's, in the explorer
{
  const mac = { name: 'Mac', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'a', width: 32 }), P('b', 'in', 2, 10, { name: 'b', width: 32 }),
      P('ra', 'reg', 12, 2, { name: 'ra', width: 32, init: '0' }), P('rb', 'reg', 12, 10, { name: 'rb', width: 32, init: '0' }),
      P('m', 'mul', 26, 5), P('s', 'add', 40, 14, { mode: 'wrap' }),
      P('acc', 'reg', 56, 12, { name: 'acc', width: 64, init: '0' }), P('y', 'out', 70, 7, { name: 'y', width: 64 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['ra', 'd']] }, { id: 'n2', pins: [['b', 'y'], ['rb', 'd']] },
      { id: 'n3', pins: [['ra', 'q'], ['m', 'a']] }, { id: 'n4', pins: [['rb', 'q'], ['m', 'b']] },
      { id: 'n5', pins: [['m', 'y'], ['s', 'a']] }, { id: 'n6', pins: [['s', 'y'], ['acc', 'd']] },
      { id: 'n7', pins: [['acc', 'q'], ['s', 'b'], ['y', 'a']] }] };
  const fixtures = new URL('./fixtures/', import.meta.url).pathname;
  const page = await fresh(mac);
  await page.locator('.tab[data-tab="analyse"]').click();
  await page.locator('.cp input[type=file]').setInputFiles(fixtures + 'mac.paths.rpt');
  await page.waitForFunction(() => document.querySelectorAll('.cp-row').length > 0);
  await page.evaluate(() => Studio.app.canvas.fit());
  await page.waitForTimeout(600);
  await page.evaluate(() => document.querySelector('.cp').scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(300);
  await annotate(page, [['.cp-map', 1, 'l'], ['.cp-row', 2, 'l'], ['.cp-step', 3, 'l'], ['.cpmark circle', 4, 'l']]);
  await page.screenshot({ path: out + '/timing.png' });
  await page.close();

  const core = await fresh();
  await core.evaluate(() => Studio.app.openDesign('C5 five-stage, scheduled (Level 2)'));
  await core.waitForFunction(() => Studio.app.explorer, null, { timeout: 30000 });
  await core.locator('.tab[data-tab="analyse"]').click();
  await core.locator('.cp input[type=file]').setInputFiles(fixtures + 'c5.paths.rpt');
  await core.waitForFunction(() => document.querySelectorAll('.cp-row').length > 0, null, { timeout: 60000 });
  await core.waitForTimeout(800);
  await core.evaluate(() => document.querySelector('.cp-detail').scrollIntoView({ block: 'start' }));
  await core.waitForTimeout(300);
  const panel = await core.evaluate(() => { const r = document.querySelector('.cp-detail').getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; });
  await core.screenshot({ path: out + '/timing-core.png', clip: { x: panel.x - 6, y: panel.y - 6, width: panel.width + 12, height: Math.min(panel.height + 12, 560) } });
  await core.close();
}

console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
