// Open a Java design in the studio and screenshot it: node view.mjs <url> <design> <out.png>
import { chromium } from 'playwright';
const [url, design, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1500);
const t0 = Date.now();
await page.evaluate((d) => Studio.app.openDesign(d), design);
let routed = false;
for (let i = 0; i < 120 && !routed; i++) { await page.waitForTimeout(250); routed = await page.evaluate(() => Object.keys(Studio.app.canvas.routes).length > 50); }
console.log('open+route ms', Date.now() - t0, 'routed', routed);
await page.waitForTimeout(2500);
await page.screenshot({ path: out });
console.log(await page.locator('.status').innerText());
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
