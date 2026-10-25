// Keyboard wiring: wire a new output port to the counter's register with keys only. node kbwire.mjs <url> <outdir>
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
// a new output port to the right of the register, placed by the API, then wired with keys only
const id = await page.evaluate(() => { const r = Studio.app.doc.d.parts.find((p) => p.type === 'reg'); const p = Studio.app.doc.addPart('out', r.x + 16, r.y + 8, { name: 'copy', width: 8 }); Studio.app.canvas.selectOnly(['p:' + r.id]); return p.id; });
await page.locator('.cv').focus();
const say = () => page.evaluate(() => document.querySelector('.sr-only').textContent);
await page.keyboard.press('Tab'); console.log('1', await say());
await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); console.log('2', await say());
await page.keyboard.press('Enter'); console.log('3', await say());
await page.keyboard.press('ArrowRight'); console.log('4', await say());
await page.keyboard.press('ArrowDown'); console.log('5', await say());
await page.screenshot({ path: out + '/kbwire.png' });
await page.keyboard.press('Enter'); console.log('6', await say());
await page.waitForTimeout(2000);
const r = await page.evaluate((id) => ({ problems: Studio.app.gen.problems.map((p) => p.message), wired: !!Studio.app.doc.netOf(id, 'a'), java: Studio.app.gen.java.split('\n').filter((l) => l.includes('copy')).join(' ') }), id);
console.log(JSON.stringify(r));
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
