// Performance budgets, measured in headless Chromium: node perf.mjs <url>
//  1. opening the largest core (C7) until the explorer is ready, under 2 s;
//  2. pan and zoom at 60 frames per second (frame time under 16.7 ms), on a drawing and on a dense view;
//  3. value frames from the server at the display rate (about 60 Hz) while running, with every net probed.
import { chromium } from 'playwright';
const [url] = process.argv.slice(2);
const browser = await chromium.launch();
const out = [];
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
await page.goto(url);
await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.gen);
await page.evaluate(() => localStorage.clear());
await page.reload();
await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.gen && Studio.app.gen.java);

// pan and zoom: 120 view changes, one per animation frame; the frame time is the gap between frames
async function panZoom(label) {
  const r = await page.evaluate(() => new Promise((done) => {
    const c = Studio.app.canvas, t = [];
    let k = 0, last = performance.now();
    const tick = (now) => {
      t.push(now - last); last = now;
      if (k % 2) { c.view.x += 7; c.view.y += 3; } else c.zoomBy(k % 4 ? 1.02 : 0.98);
      c.applyView();
      if (++k < 120) requestAnimationFrame(tick); else done(t.slice(5));
    };
    requestAnimationFrame(tick);
  }));
  r.sort((a, b) => a - b);
  const p50 = r[Math.floor(r.length / 2)], p95 = r[Math.floor(r.length * 0.95)];
  out.push([label, 'frame time p50 ' + p50.toFixed(1) + ' ms, p95 ' + p95.toFixed(1) + ' ms', p95 <= 17.5]);
}
await page.waitForTimeout(1500);
await panZoom('pan and zoom: counter');

// 1. C7 open
const t0 = Date.now();
await page.evaluate(() => Studio.app.openDesign('C7 out-of-order, 16-entry ROB / 8 RS'));
await page.waitForFunction(() => Studio.app.explorer && document.querySelectorAll('.sig').length > 50, null, { timeout: 60000 });
const openMs = Date.now() - t0;
const parts = await page.evaluate(() => Studio.app.fullView.parts.length);
out.push(['open C7 (' + parts + ' parts) to a ready explorer', openMs + ' ms', openMs < 2000]);

// a dense view: draw everything of C1a
await page.evaluate(() => Studio.app.openDesign('C1a single-cycle, LUT RAM'));
await page.waitForFunction(() => Studio.app.explorer, null, { timeout: 60000 });
await page.evaluate(() => Studio.app.explorer.all());
await page.waitForTimeout(3000);
const n = await page.evaluate(() => Studio.app.doc.d.parts.length);
await panZoom('pan and zoom: C1a, every signal drawn (' + n + ' parts)');

// 3. frames while running with every net probed: count SSE frames over 3 s
await page.evaluate(() => { Studio.app.sim({ cmd: 'rate', rate: 0 }); Studio.app.sim({ cmd: 'run' }); });
const fr = await page.evaluate(() => new Promise((done) => {
  const es = new EventSource('/api/events'); let n = 0, vals = 0; const t = performance.now();
  es.onmessage = (e) => { const f = JSON.parse(e.data); if (f.type === 'frame') { n++; vals += Object.keys(f.values).length; } };
  setTimeout(() => { es.close(); done([n / ((performance.now() - t) / 1000), vals / Math.max(1, n)]); }, 3000);
}));
const nets = await page.evaluate(() => Object.keys(Studio.app.gen.netPaths || {}).length);
out.push(['value frames while running flat out, ' + nets + ' nets sent', fr[0].toFixed(1) + ' frames/s, ' + Math.round(fr[1]) + ' values a frame', fr[0] >= 55]);
await page.evaluate(() => Studio.app.sim({ cmd: 'pause' }));

for (const [what, v, ok] of out) console.log((ok ? 'ok   ' : 'MISS ') + what + ': ' + v);
await browser.close();
