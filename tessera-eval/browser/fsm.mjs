// State machine part: live state on the symbol and in the diagram. node fsm.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const P = (id, type, x, y, params) => ({ id, type, x, y, rot: 0, mir: false, params });
const doc = {
  name: 'Handshake', title: 'A request/acknowledge controller', package: 'studio', next: 100, notes: [],
  parts: [
    P('g', 'in', 2, 6, { name: 'go', width: 1 }),
    P('s', 'in', 2, 10, { name: 'stop', width: 1 }),
    P('f', 'fsm', 18, 4, { name: 'ctl', states: 'IDLE RUN DRAIN DONE', inputs: 'go stop',
      transitions: 'IDLE -> RUN : go\nRUN -> DRAIN : stop\nRUN -> RUN : go & !stop\nDRAIN -> DONE : !go\nDONE -> IDLE : 1', outputs: 'busy : RUN DRAIN\nready : IDLE' }),
    P('o1', 'out', 40, 6, { name: 'busy', width: 1 }),
    P('o2', 'out', 40, 10, { name: 'ready', width: 1 }),
    P('o3', 'out', 40, 14, { name: 'st', width: 2 }),
  ],
  nets: [
    { id: 'w1', pins: [['g', 'y'], ['f', 'go']] },
    { id: 'w2', pins: [['s', 'y'], ['f', 'stop']] },
    { id: 'w3', pins: [['f', 'busy'], ['o1', 'a']] },
    { id: 'w4', pins: [['f', 'ready'], ['o2', 'a']] },
    { id: 'w5', pins: [['f', 'state'], ['o3', 'a']] },
  ],
};
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1500);
await page.evaluate((d) => { localStorage.clear(); localStorage.setItem('tstudio.doc', JSON.stringify(d)); }, doc);
await page.reload();
await page.waitForTimeout(2500);
const g = await page.evaluate(() => ({ problems: (Studio.app.gen.problems || []).map((p) => p.code + ': ' + p.message), java: Studio.app.gen.java }));
console.log('problems', JSON.stringify(g.problems));
console.log(g.java.split('\n').filter((l) => /ctl/.test(l)).join('\n'));
await page.evaluate(() => Studio.app.sim({ cmd: 'reset' }));
await page.evaluate(() => Studio.app.canvas.selectOnly(['p:f']));
await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'go', value: '1' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'step' }));
await page.waitForTimeout(800);
const st = async () => page.evaluate(() => ({ sym: document.querySelector('.cv [data-fsmval]')?.textContent, lit: document.querySelector('.fsm-s.now text')?.textContent, next: [...document.querySelectorAll('.fsm-t.next')].map((e) => e.getAttribute('data-t')).join(',') }));
console.log('after go', JSON.stringify(await st()));
console.log('state tag', await page.evaluate(() => [...document.querySelectorAll('.vtag.enum')].map((e) => e.textContent).join(',')));
await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'stop', value: '1' }));
await page.waitForTimeout(600);
console.log('stop poked', JSON.stringify(await st()));
await page.evaluate(() => Studio.app.waves.add('ctl'));
await page.waitForTimeout(800);
console.log('wave value', await page.evaluate(() => document.querySelector('.wv-name:last-of-type .wv-val')?.textContent + ' / ' + [...document.querySelectorAll('.wv-name .wv-lb')].map((e) => e.textContent).join(',')));
await page.screenshot({ path: out + '/fsm.png' });
await page.locator('.fsm-d').screenshot({ path: out + '/fsm-diagram.png' });
await page.evaluate(() => Studio.app.sim({ cmd: 'step' }));
await page.waitForTimeout(600);
console.log('after step', JSON.stringify(await st()));
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
