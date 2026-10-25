// Build a design from nothing with the mouse: node build.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.evaluate(() => { localStorage.clear(); });
await page.reload();
await page.waitForTimeout(1500);
await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Gate')));
await page.waitForTimeout(500);
const cv = await page.locator('.cv').boundingBox();
async function dragCard(type, x, y) {
  const c = await page.locator(`.pal .card[data-type="${type}"]`).boundingBox();
  await page.mouse.move(c.x + c.width / 2, c.y + c.height / 2);
  await page.mouse.down();
  await page.mouse.move(c.x + 40, c.y + 20, { steps: 4 });
  await page.mouse.move(cv.x + x, cv.y + y, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(150);
}
await dragCard('in', 200, 200);
await dragCard('in', 200, 330);
await dragCard('and', 480, 270);
await dragCard('out', 760, 270);
await page.waitForTimeout(400);
// pins on screen
const pins = await page.evaluate(() => {
  const app = Studio.app, r = app.canvas.wrap.getBoundingClientRect(), U = Studio.Geom.U;
  return app.doc.d.parts.map((p) => ({ id: p.id, type: p.type, pins: Studio.Geom.of(p).pins.map((q) => { const s = app.canvas.toScreen(q.ax * U, q.ay * U); return { name: q.name, x: r.left + s.x, y: r.top + s.y }; }) }));
});
const pin = (type, k, name) => pins.filter((p) => p.type === type)[k].pins.find((q) => q.name === name);
async function wire(a, b, shot) {
  await page.mouse.move(a.x, a.y); await page.mouse.down();
  await page.mouse.move((a.x + b.x) / 2, (a.y + b.y) / 2, { steps: 6 });
  await page.mouse.move(b.x + 2, b.y + 1, { steps: 6 });
  if (shot) await page.screenshot({ path: out + '/ghost.png' });
  await page.mouse.up();
  await page.waitForTimeout(200);
}
await wire(pin('in', 0, 'y'), pin('and', 0, 'in0'), true);
await wire(pin('in', 1, 'y'), pin('and', 0, 'in1'));
await wire(pin('and', 0, 'y'), pin('out', 0, 'a'));
await page.waitForTimeout(1500);
await page.locator('.tab[data-tab="code"]').click();
await page.waitForTimeout(300);
await page.screenshot({ path: out + '/built.png' });
const java = await page.evaluate(() => Studio.app.gen && Studio.app.gen.java);
console.log(java.split('\n').filter((l) => !l.startsWith('// tessera-studio')).join('\n'));
console.log('problems:', JSON.stringify(await page.evaluate(() => Studio.app.gen.problems.map((p) => p.message))));
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
