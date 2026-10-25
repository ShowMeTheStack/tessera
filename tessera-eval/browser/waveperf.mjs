// The S4 checkpoint: waves over 10^6 cycles stay smooth. node waveperf.mjs <url> <outdir>
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
await page.evaluate(() => Studio.app.sim({ cmd: 'reset' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'en', value: '1' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'rate', rate: 0 }));
const t0 = Date.now();
await page.evaluate(() => Studio.app.sim({ cmd: 'until', n: 1000000 }));
let c = 0;
for (let i = 0; i < 240; i++) { await page.waitForTimeout(500); c = await page.evaluate(() => Studio.app.head); if (c >= 1000000 && !(await page.evaluate(() => Studio.app.running))) break; }
const secs = (Date.now() - t0) / 1000;
console.log('ran to', c, 'in', secs.toFixed(1), 's =', Math.round(c / secs).toLocaleString('en'), 'cycles/s (with recording and frames)');
// whole-run window, then zoom and pan; time each fetch+draw
const times = await page.evaluate(async () => {
  const w = Studio.app.waves, out = [];
  const go = async (t0, t1) => { const a = performance.now(); w.t0 = t0; w.t1 = t1; w.follow = false; await w.fetch(); out.push(Math.round(performance.now() - a)); };
  await go(0, w.head);
  for (let k = 0; k < 6; k++) await go(w.head * k / 7, w.head * k / 7 + w.head / 20);
  await go(500000, 500100);
  return out;
});
console.log('fetch+draw ms (whole run, 6 zoomed windows, 100 cycles):', times.join(' '));
await page.evaluate(() => { Studio.app.waves.t0 = 0; Studio.app.waves.t1 = Studio.app.waves.head; Studio.app.waves.fetch(); });
await page.waitForTimeout(800);
await page.screenshot({ path: out + '/waveperf.png' });
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
