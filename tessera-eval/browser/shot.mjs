// Screenshot Tessera Studio: node shot.mjs <url> <out.png> [width height] [script.js]
import { chromium } from 'playwright';
import fs from 'node:fs';
const [url, out, w = '1500', hgt = '900', script] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: Number(w), height: Number(hgt) }, deviceScaleFactor: 1 });
const errors = [];
page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') errors.push(m.type() + ': ' + m.text()); });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
await page.goto(url);
await page.waitForTimeout(2500);
if (script) { await page.evaluate(fs.readFileSync(script, 'utf8')); await page.waitForTimeout(1500); }
await page.screenshot({ path: out });
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
