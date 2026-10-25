// The board: switches to LEDs, a button counting on a hex digit. node board.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });
const doc = {
  name: 'Panel', title: 'Switches, LEDs and a counted button', package: 'studio', next: 100, notes: [],
  parts: [
    P('s', 'in', 2, 2, { name: 'sw', width: 4 }), P('l', 'out', 30, 2, { name: 'led', width: 4 }),
    P('b', 'in', 2, 10, { name: 'btn', width: 1 }), P('e', 'edge', 12, 10, { kind: 'rising' }),
    P('c', 'counter', 20, 10, { max: 15 }), P('h', 'out', 36, 10, { name: 'hex0', width: 4 }),
  ],
  nets: [
    { id: 'w1', pins: [['s', 'y'], ['l', 'a']] }, { id: 'w2', pins: [['b', 'y'], ['e', 'a']] },
    { id: 'w3', pins: [['e', 'y'], ['c', 'inc']] }, { id: 'w4', pins: [['c', 'value'], ['h', 'a']] },
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
console.log('problems', JSON.stringify(await page.evaluate(() => Studio.app.gen.problems.map((p) => p.message))));
await page.locator('.tab[data-tab="board"]').click();
await page.evaluate(() => Studio.app.sim({ cmd: 'reset' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'run' }));
await page.locator('.board .sw[data-bit="0"]').click();
await page.locator('.board .sw[data-bit="2"]').click();
await page.waitForTimeout(700);
console.log('leds on', await page.evaluate(() => [...document.querySelectorAll('.board .led.on')].map((e) => e.dataset.bit).join(',')));
for (let k = 0; k < 3; k++) {
  const b = page.locator('.board .push').first();
  const box = await b.boundingBox();
  await page.mouse.move(box.x + 10, box.y + 10); await page.mouse.down(); await page.waitForTimeout(400); await page.mouse.up(); await page.waitForTimeout(400);
}
await page.waitForTimeout(600);
console.log('hex segments lit', await page.evaluate(() => document.querySelectorAll('.board .seg7 path.on').length), '(the digit 3 lights a b c d g: 5)');
console.log('hex0 value', await page.evaluate(() => String(Studio.app.board.vals.get('hex0'))));
console.log('page scroll', await page.evaluate(() => [document.scrollingElement.scrollLeft, document.documentElement.scrollWidth, innerWidth].join(' ')));
await page.screenshot({ path: out + '/board.png' });
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
