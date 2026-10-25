// Drive Tessera Studio through a scenario and take screenshots: node try.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('request', (r) => { if (r.url().includes('/api/') && !r.url().includes('waves') && !r.url().includes('events')) console.log('REQ', r.url().split('/api/')[1], (r.postData() || '').slice(0, 80)); });
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();
await page.waitForTimeout(2000);
// toggle en
const en = page.locator('.cv .part[data-type="in"]').first();
const b = await en.boundingBox();
console.log('live', await page.evaluate(() => Studio.app.canvas.live), JSON.stringify(b));
await page.mouse.click(b.x + b.width / 3, b.y + b.height / 2);
for (let i = 0; i < 5; i++) { await page.waitForTimeout(500); console.log('state', await page.evaluate(() => [Studio.app.running, Studio.app.cycle, Studio.app.head].join(' '))); }
await page.screenshot({ path: out + '/t1.png' });
// click the waves halfway
const plot = await page.locator('.wv-plot').boundingBox();
await page.mouse.click(plot.x + plot.width * 0.15, plot.y + 60);
await page.waitForTimeout(1200);
await page.screenshot({ path: out + '/t2.png' });
console.log(await page.locator('.status').innerText());
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
