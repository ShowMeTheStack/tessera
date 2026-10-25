// The diagram audit: every sample drawing at 1440×900 and 390×844, light and dark, fitted to
// the window, must have no text over other text, no text cut off by the canvas's edge, no visible text
// under 11 px, and no sideways scroll. node audit.mjs <url>
import { chromium } from 'playwright';
const [url] = process.argv.slice(2);
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });
const samples = {
  counter: null,
  blinky: 'blinky',
  fsm: { name: 'Hand', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('g', 'in', 2, 6, { name: 'go', width: 1 }), P('s', 'in', 2, 10, { name: 'stop', width: 1 }),
      P('f', 'fsm', 18, 4, { name: 'ctl', states: 'IDLE RUN DRAIN DONE', inputs: 'go stop', transitions: 'IDLE -> RUN : go\nRUN -> DRAIN : stop\nDRAIN -> DONE : !go\nDONE -> IDLE : 1', outputs: 'busy : RUN DRAIN' }),
      P('o1', 'out', 40, 6, { name: 'busy', width: 1 }), P('o3', 'out', 40, 14, { name: 'st', width: 2 })],
    nets: [{ id: 'w1', pins: [['g', 'y'], ['f', 'go']] }, { id: 'w2', pins: [['s', 'y'], ['f', 'stop']] }, { id: 'w3', pins: [['f', 'busy'], ['o1', 'a']] }, { id: 'w5', pins: [['f', 'state'], ['o3', 'a']] }] },
  memory: { name: 'M', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('a', 'in', 2, 2, { name: 'addr', width: 4 }), P('d', 'in', 2, 6, { name: 'wdata', width: 8 }), P('e', 'in', 2, 10, { name: 'we', width: 1 }),
      P('m', 'mem', 16, 2, { name: 'ram', width: 8, depth: 16, read: 'sync' }), P('o', 'out', 34, 4, { name: 'q', width: 8 })],
    nets: [{ id: 'n1', pins: [['a', 'y'], ['m', 'addr']] }, { id: 'n2', pins: [['d', 'y'], ['m', 'wdata']] }, { id: 'n3', pins: [['e', 'y'], ['m', 'we']] }, { id: 'n4', pins: [['m', 'rdata'], ['o', 'a']] }] },
  library: { name: 'L', title: '', package: 'studio', next: 100, notes: [],
    parts: [P('b', 'in', 2, 4, { name: 'btn', width: 1 }), P('e', 'edge', 12, 4, { kind: 'rising' }), P('c', 'counter', 22, 3, { max: 15 }), P('h', 'out', 40, 3, { name: 'hex0', width: 4 }), P('w', 'out', 40, 8, { name: 'wrapped', width: 1 }),
      P('x', 'in', 2, 14, { name: 'x', width: 8 }), P('pe', 'prienc', 14, 13), P('i', 'out', 30, 13, { name: 'low', width: 3 }), P('v', 'out', 30, 17, { name: 'any', width: 1 })],
    nets: [{ id: 'w2', pins: [['b', 'y'], ['e', 'a']] }, { id: 'w3', pins: [['e', 'y'], ['c', 'inc']] }, { id: 'w4', pins: [['c', 'value'], ['h', 'a']] }, { id: 'w5', pins: [['c', 'wrap'], ['w', 'a']] },
      { id: 'w6', pins: [['x', 'y'], ['pe', 'a']] }, { id: 'w7', pins: [['pe', 'index'], ['i', 'a']] }, { id: 'w8', pins: [['pe', 'valid'], ['v', 'a']] }] },
};

const browser = await chromium.launch();
let total = 0, failed = 0;
for (const [name, doc] of Object.entries(samples)) {
  for (const [w, h] of [[1440, 900], [390, 844]]) for (const theme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: w, height: h } });
    const errs = [];
    page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
    await page.goto(url);
    await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.canvas);
    await page.evaluate(([d, t]) => { localStorage.clear(); localStorage.setItem('tstudio.theme', JSON.stringify(t)); if (d && typeof d === 'object') localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, [doc, theme]);
    await page.reload();
    await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.gen && Studio.app.gen.java);
    if (doc === 'blinky') { await page.evaluate(() => Studio.app.replace(Studio.App.SAMPLES.blinky())); await page.waitForTimeout(1500); }
    await page.evaluate(() => Studio.app.sim({ cmd: 'restart' }));
    await page.evaluate(() => Studio.app.sim({ cmd: 'step', n: 3 }));
    await page.waitForTimeout(1200);
    await page.evaluate(() => Studio.app.canvas.fit());
    await page.waitForTimeout(2200);   // routes and values
    const r = await page.evaluate(() => {
      const out = [];
      const cv = document.querySelector('.cv'), box = cv.getBoundingClientRect();
      const texts = [];
      for (const el of cv.querySelectorAll('text, .vtag')) {
        if (!el.textContent.trim()) continue;
        const cs = getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue;
        if (el.closest('[hidden], .cv-mini, .cv-zoom')) continue;
        const b = el.getBoundingClientRect();
        if (!b.width || !b.height) continue;
        // the rendered size: the font size times the canvas's scale (SVG text is scaled by the view)
        const scale = el.tagName.toLowerCase() === 'text' ? (el.getScreenCTM() ? el.getScreenCTM().a : 1) : 1;
        const px = parseFloat(cs.fontSize) * scale;
        texts.push({ t: el.textContent.trim(), b, px, cls: el.getAttribute('class') || el.className });
      }
      for (const x of texts) {
        if (x.px < 11 - 0.01) out.push('small text (' + x.px.toFixed(1) + ' px): “' + x.t + '” ' + x.cls);
        if (x.b.left < box.left - 1 || x.b.right > box.right + 1 || x.b.top < box.top - 1 || x.b.bottom > box.bottom + 1) out.push('cut off: “' + x.t + '”');
      }
      for (let i = 0; i < texts.length; i++) for (let j = i + 1; j < texts.length; j++) {
        const a = texts[i].b, c = texts[j].b;
        const ix = Math.min(a.right, c.right) - Math.max(a.left, c.left), iy = Math.min(a.bottom, c.bottom) - Math.max(a.top, c.top);
        if (ix > 1.5 && iy > 1.5 && ix * iy > 0.15 * Math.min(a.width * a.height, c.width * c.height)) out.push('overlap: “' + texts[i].t + '” and “' + texts[j].t + '”');
      }
      if (document.documentElement.scrollWidth > innerWidth) out.push('sideways scroll: ' + document.documentElement.scrollWidth + ' > ' + innerWidth);
      return { out, n: texts.length, zoom: Studio.app.canvas.view.z };
    });
    total++;
    const all = [...errs, ...r.out];
    if (all.length) failed++;
    console.log((all.length ? 'FAIL ' : 'ok   ') + name.padEnd(8) + ' ' + w + '×' + h + ' ' + theme.padEnd(5) + ' ' + r.n + ' texts, zoom ' + r.zoom.toFixed(2) + (all.length ? '\n       ' + [...new Set(all)].slice(0, 8).join('\n       ') : ''));
    await page.close();
  }
}
console.log(`\n${total - failed} of ${total} renderings pass the audit`);
await browser.close();
process.exit(failed ? 1 : 0);
