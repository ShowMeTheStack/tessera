// Java modules as parts: palette lists them; place a FIFO; wire it; run. node module.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1500);
await page.evaluate(() => { localStorage.clear(); localStorage.setItem('tstudio.palOpen', JSON.stringify(['Modules', 'Ports'])); });
await page.reload();
await page.waitForTimeout(2000);
console.log('modules', await page.evaluate(() => Studio.app.modules.map((m) => m.group + ': ' + m.title).join(' | ')));
await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Queue')));
await page.waitForTimeout(500);
await page.locator('.card[data-type="module:0"]').click();
await page.waitForTimeout(4000);
const f = await page.evaluate(() => { const p = Studio.app.doc.d.parts.find((q) => q.type === 'module'); return p && { id: p.id, name: p.params.name, pins: p.pins.map((q) => q.dir + ':' + q.name).join(' ') }; });
console.log('placed', JSON.stringify(f));
// ports for every pin of the FIFO, wired by the API
await page.evaluate((id) => Studio.app.setParams(id, { bundle: false }), f.id);
await page.evaluate((id) => {
  const app = Studio.app, doc = app.doc, f = doc.part(id), g = Studio.Geom.of(f);
  let k = 0;
  for (const q of g.pins) {
    const w = { in_bits: 8, out_bits: 8, count: 5 }[q.name] || 1;
    const p = doc.addPart(q.dir === 'in' ? 'in' : 'out', q.dir === 'in' ? f.x - 14 : f.x + g.w + 6, f.y + (k++) * 3 - 4, { name: q.name, width: w });
    doc.connect({ part: p.id, pin: q.dir === 'in' ? 'y' : 'a' }, { part: id, pin: q.name });
  }
}, f.id);
await page.waitForTimeout(3000);
const g = await page.evaluate(() => ({ problems: Studio.app.gen.problems.map((p) => p.message), java: Studio.app.gen.java.split('\n').filter((l) => /fifo1|Fifo/.test(l)).join('\n') }));
console.log(JSON.stringify(g.problems));
console.log(g.java);
await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'in_valid', value: '1' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'in_bits', value: '2a' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'step', n: 3 }));
await page.waitForTimeout(800);
await page.waitForTimeout(600);
console.log('stream classes', await page.evaluate(() => { const d = Studio.app.doc, f = d.d.parts.find((q) => q.type === 'module'); const cls = (pin) => { const n = d.netOf(f.id, pin); const el = document.querySelector(`.cv .wire[data-net="${n}"]`); return pin + ':' + (el ? [...el.classList].filter((c) => c === 'stall' || c === 'beat').join('+') || '-' : '?'); }; return ['in_valid', 'out_valid', 'out_bits'].map(cls).join(' '); }));
console.log('fifo fill', await page.evaluate(() => document.querySelector('.cv [data-fill]')?.getAttribute('width') + ' of ' + document.querySelector('.cv [data-fill]')?.getAttribute('data-w')));
console.log('count after 3 pushes', await page.evaluate(() => { const n = Studio.app.doc.netOf(Studio.app.doc.d.parts.find((p) => p.params.name === 'count').id, 'a'); return String(Studio.app.canvas.values.get(n)); }));
await page.evaluate(() => Studio.app.tidy());
await page.waitForTimeout(2500);
await page.evaluate(() => Studio.app.canvas.selectOnly(['p:' + Studio.app.doc.d.parts.find((q) => q.type === 'module').id]));
await page.waitForTimeout(500);
console.log('failed routes', await page.evaluate(() => Object.entries(Studio.app.canvas.routes).filter(([k, v]) => v.failed).map(([k]) => k).join(',') || 'none'));
await page.screenshot({ path: out + '/module.png' });
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
