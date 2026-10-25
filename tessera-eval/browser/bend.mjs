// Manual bends and net hover: alt-drag a wire, alt-click the bend, hover highlight. node bend.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1500);
await page.evaluate(() => localStorage.clear());
await page.reload();
await page.waitForTimeout(2000);
// the en wire in the counter: input en -> register en
const info = await page.evaluate(() => {
  const d = Studio.app.doc.d, n = d.nets.find((x) => x.pins.some(([p, q]) => q === 'en' && d.parts.find((pp) => pp.id === p).type === 'reg'));
  const r = Studio.app.canvas.routes[n.id], seg = r.segs.find(([a, b, c, e]) => b === e && Math.abs(c - a) > 4) || r.segs[0];
  const U = 10, mx = (seg[0] + seg[2]) / 2 * U, my = seg[1] * U;
  const s0 = Studio.app.canvas.toScreen(mx, my), R = Studio.app.canvas.wrap.getBoundingClientRect(), s = { x: s0.x + R.left, y: s0.y + R.top };
  return { id: n.id, x: s.x, y: s.y, segs: r.segs.length };
});
console.log('wire', JSON.stringify(info));
await page.mouse.move(info.x, info.y);
await page.waitForTimeout(300);
console.log('hovered', await page.evaluate(() => [Studio.app.canvas.hoverNet, document.querySelectorAll('.cv .wire.hover').length, document.querySelectorAll('.cv .pinhot.net').length].join(' ')));
await page.screenshot({ path: out + '/hover.png' });
await page.keyboard.down('Alt');
await page.mouse.down();
await page.mouse.move(info.x, info.y + 120, { steps: 8 });
await page.mouse.up();
await page.keyboard.up('Alt');
await page.waitForTimeout(2500);
const after = await page.evaluate((id) => ({ via: Studio.app.doc.net(id).via, segs: Studio.app.canvas.routes[id].segs.length, failed: Studio.app.canvas.routes[id].failed, handles: document.querySelectorAll('.cv .via').length, dots: Studio.app.canvas.routes[id].dots.length }), info.id);
console.log('after bend', JSON.stringify(after));
await page.screenshot({ path: out + '/bend.png' });
// alt-click the bend removes it
const vs = await page.evaluate((id) => { const [x, y] = Studio.app.doc.net(id).via[0], s0 = Studio.app.canvas.toScreen(x * 10, y * 10), R = Studio.app.canvas.wrap.getBoundingClientRect(); return { x: s0.x + R.left, y: s0.y + R.top }; }, info.id);
await page.keyboard.down('Alt');
await page.mouse.click(vs.x, vs.y);
await page.keyboard.up('Alt');
await page.waitForTimeout(1200);
console.log('after alt-click', JSON.stringify(await page.evaluate((id) => Studio.app.doc.net(id).via || null, info.id)));
await page.keyboard.press('Meta+z');
await page.waitForTimeout(800);
console.log('after undo', JSON.stringify(await page.evaluate((id) => Studio.app.doc.net(id).via || null, info.id)));
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
