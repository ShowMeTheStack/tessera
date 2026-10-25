// Explore a Java design: node explore.mjs <url> <design> <signal> <outdir>
import { chromium } from 'playwright';
const [url, design, signal, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1200);
await page.evaluate((d) => Studio.app.openDesign(d), design);
await page.waitForTimeout(1200);
await page.screenshot({ path: out + '/ex0.png' });
const names = await page.evaluate(() => Studio.app.explorer.signals.map((s) => s.kind[0] + ':' + s.name).slice(0, 400).join(' '));
console.log(names.slice(0, 1500));
await page.locator('.sig', { hasText: signal }).first().click();
await page.waitForTimeout(2000);
await page.screenshot({ path: out + '/ex1.png' });
// trace the driver of the first shown source register
const id = await page.evaluate(() => { const ex = Studio.app.explorer; const shown = [...ex.shown]; const r = shown.find((x) => ex.byId.get(x).type === 'reg' && x !== shown[0]); return r; });
if (id) { await page.evaluate((x) => Studio.app.explorer.traceDriver(x), id); await page.waitForTimeout(2000); await page.screenshot({ path: out + '/ex2.png' }); }
console.log(await page.locator('.status').innerText());
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
