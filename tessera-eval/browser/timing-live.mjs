// Tessera Studio: the timing view, live. Opens a multiply-accumulate drawing, presses "Time it with
// Vivado" (out of context on the build host, ZED_HOST), waits for the paths, and saves the report Vivado
// wrote (fetched back by the studio) as the fixture the suite's timing specs use.
//
//   node timing-live.mjs <url> <fixture out> [period ns]
import { chromium } from 'playwright';
import { writeFileSync } from 'node:fs';

const [url, out, period] = process.argv.slice(2);
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });
export const MAC = { name: 'Mac', title: '', package: 'studio', next: 100, notes: [],
  parts: [P('a', 'in', 2, 2, { name: 'a', width: 32 }), P('b', 'in', 2, 10, { name: 'b', width: 32 }),
    P('ra', 'reg', 12, 2, { name: 'ra', width: 32, init: '0' }), P('rb', 'reg', 12, 10, { name: 'rb', width: 32, init: '0' }),
    P('m', 'mul', 26, 5), P('s', 'add', 40, 14, { mode: 'wrap' }),
    P('acc', 'reg', 56, 12, { name: 'acc', width: 64, init: '0' }), P('y', 'out', 70, 7, { name: 'y', width: 64 })],
  nets: [{ id: 'n1', pins: [['a', 'y'], ['ra', 'd']] }, { id: 'n2', pins: [['b', 'y'], ['rb', 'd']] },
    { id: 'n3', pins: [['ra', 'q'], ['m', 'a']] }, { id: 'n4', pins: [['rb', 'q'], ['m', 'b']] },
    { id: 'n5', pins: [['m', 'y'], ['s', 'a']] }, { id: 'n6', pins: [['s', 'y'], ['acc', 'd']] },
    { id: 'n7', pins: [['acc', 'q'], ['s', 'b'], ['y', 'a']] }] };

// only when run (not when another script imports MAC)
if (url && import.meta.url === new URL('file://' + process.argv[1]).href) {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  await page.goto(url);
  await page.waitForFunction(() => globalThis.Studio && Studio.app && Studio.app.canvas);
  await page.evaluate((d) => { localStorage.clear(); localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, MAC);
  await page.reload();
  await page.waitForFunction(() => Studio.app.gen && Studio.app.gen.java && /class Mac/.test(Studio.app.gen.java));
  await page.waitForTimeout(1500);
  console.log('problems:', JSON.stringify(await page.evaluate(() => Studio.app.gen.problems)));
  // the API call the button makes, and its polling, as the page does it
  const t0 = Date.now();
  const r = await page.evaluate(async (p) => {
    const j = await Studio.Api.call('timing', { action: 'run', period: p, paths: 20 });
    for (;;) {
      await new Promise((ok) => setTimeout(ok, 3000));
      const s = await Studio.Api.call('timing', { action: 'status', job: j.job });
      if (s.state !== 'running') return s;
    }
  }, Number(period || 4));
  console.log('state', r.state, 'in', Math.round((Date.now() - t0) / 1000), 's;', JSON.stringify({ wns: r.result.wns, luts: r.result.luts, dsps: r.result.dsps, mapping: r.result.timing && r.result.timing.mapping }));
  console.log((r.log || []).slice(-6).join('\n'));
  if (r.result.timing) {
    const p1 = r.result.timing.paths[0];
    console.log('path 1:', p1.slack, p1.delay, p1.levels, p1.start, '->', p1.end);
    for (const hp of p1.hops) console.log('  ', hp.quality.padEnd(11), String(hp.incr).padStart(6), hp.net ? 'net' : hp.prim, hp.object, hp.signal || '', hp.file ? hp.file + ':' + hp.line : '');
  }
  writeFileSync(out + '.json', JSON.stringify(r, null, 1));
  await browser.close();
}
