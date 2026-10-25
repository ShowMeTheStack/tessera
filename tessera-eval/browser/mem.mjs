// Memory and truth table parts: a counter addresses a ROM and a 2-bit truth table. node mem.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });
const doc = {
  name: 'RomWalk', title: 'A counter walks a ROM', package: 'studio', next: 100, notes: [],
  parts: [
    P('c1', 'const', 2, 10, { value: '1', width: 2 }),
    P('a1', 'add', 12, 8, { mode: 'wrap' }),
    P('r1', 'reg', 22, 8, { name: 'addr', width: 2, init: '0' }),
    P('m1', 'mem', 36, 4, { name: 'rom', width: 8, depth: 4, read: 'async', write: false, init: '0x11 0x22 0x33 0x44' }),
    P('o1', 'out', 54, 6, { name: 'data', width: 8 }),
    P('b1', 'slice', 30, 16, { hi: 1, lo: 1 }),
    P('b0', 'slice', 30, 20, { hi: 0, lo: 0 }),
    P('t1', 'table', 38, 16, { inputs: 'hi lo', outputs: 'odd', rows: '0 1 | 1\n1 1 | 1' }),
    P('o2', 'out', 54, 17, { name: 'odd', width: 1 }),
  ],
  nets: [
    { id: 'w1', pins: [['c1', 'y'], ['a1', 'b']] },
    { id: 'w2', pins: [['r1', 'q'], ['a1', 'a'], ['m1', 'addr'], ['b1', 'a'], ['b0', 'a']] },
    { id: 'w6', pins: [['b1', 'y'], ['t1', 'hi']] },
    { id: 'w7', pins: [['b0', 'y'], ['t1', 'lo']] },
    { id: 'w3', pins: [['a1', 'y'], ['r1', 'd']] },
    { id: 'w4', pins: [['m1', 'rdata'], ['o1', 'a']] },
    { id: 'w5', pins: [['t1', 'odd'], ['o2', 'a']] },
  ],
};
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1500);
await page.evaluate((d) => { localStorage.clear(); localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, doc);
await page.reload();
await page.waitForTimeout(2500);
const g = await page.evaluate(() => ({ problems: (Studio.app.gen.problems || []).map((p) => p.code + ': ' + p.message), java: Studio.app.gen.java }));
console.log('problems', JSON.stringify(g.problems));
console.log(g.java.split('\n').filter((l) => /rom|table|Mem|odd|\|/.test(l)).join('\n'));
await page.evaluate(() => Studio.app.sim({ cmd: 'step' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'step' }));
await page.waitForTimeout(800);
console.log('values', await page.evaluate(() => [...Studio.app.canvas.values.entries()].map(([k, v]) => k + '=' + v.toString(16)).join(' ')));
await page.evaluate(() => { Studio.app.canvas.selectOnly(['p:t1']); });
await page.waitForTimeout(500);
console.log('row now', await page.evaluate(() => document.querySelector('table.tt tr.now')?.getAttribute('data-row')));
await page.locator('button.tt-bit').nth(2).click();
await page.waitForTimeout(1500);
console.log('rows after click', await page.evaluate(() => Studio.app.doc.part('t1').params.rows));
await page.screenshot({ path: out + '/mem.png' });
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
