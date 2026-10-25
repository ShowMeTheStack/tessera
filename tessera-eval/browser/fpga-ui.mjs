// The FPGA pane end to end, through the UI: a drawing with its counter on the ZedBoard's LEDs, the pane's Build
// clicked (Vivado runs on the build host), and the build's worst timing paths shown in the Analyse pane, mapped onto
// the drawing. Not part of studio-suite.mjs: it needs the build host (ZED_HOST) and takes minutes.
//   node fpga-ui.mjs <studio url> <drawing .json> <screenshot .png>
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';

const [url, drawing, shot] = process.argv.slice(2);
const doc = JSON.parse(readFileSync(drawing, 'utf8'));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1200);
await page.evaluate((d) => { localStorage.clear(); localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, doc);
await page.reload();
await page.waitForTimeout(2500);

await page.locator('.tab[data-tab="fpga"]').click();
const t0 = Date.now();
await page.locator('button', { hasText: /^Build$/ }).click();
// the build: the state cell leaves "…" (running) for built or failed; Vivado takes a minute or more
await page.waitForFunction(() => /built|failed|on the board/.test(document.querySelector('.insp .an-cells .an-cell b')?.textContent || ''), null, { timeout: 15 * 60000 });
const state = await page.evaluate(() => document.querySelector('.insp .an-cells .an-cell b').textContent);
const secs = Math.round((Date.now() - t0) / 1000);
let ok = state === 'built';
console.log('build state:', state, 'after', secs, 's');
// the worst paths of the build, in the Analyse pane, mapped onto the drawing
await page.waitForFunction(() => document.querySelectorAll('.cp-row').length > 0, null, { timeout: 60000 }).catch(() => { ok = false; });
const rows = await page.evaluate(() => document.querySelectorAll('.cp-row').length);
const mapping = await page.evaluate(() => document.querySelector('.cp-map')?.textContent || '');
console.log('paths shown:', rows, '| mapping:', mapping);
if (rows === 0) ok = false;
await page.locator('.tab[data-tab="analyse"]').click();
await page.waitForTimeout(800);
await page.screenshot({ path: shot });
if (errors.length) { console.log('page errors:', errors.join(' | ')); ok = false; }
console.log(ok ? 'FPGA UI CHECK PASSED' : 'FPGA UI CHECK FAILED');
await browser.close();
process.exit(ok ? 0 : 1);
