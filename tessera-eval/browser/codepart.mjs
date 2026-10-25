// Java code part: place, edit its class in the code pane, apply, wire, run. node codepart.mjs <url> <outdir>
import { chromium } from 'playwright';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForTimeout(1500);
await page.evaluate(() => { localStorage.clear(); localStorage.setItem('tstudio.palOpen', JSON.stringify(['Modules'])); });
await page.reload();
await page.waitForTimeout(2000);
await page.evaluate(() => Studio.app.replace(Studio.Doc.blank('Hand')));
await page.waitForTimeout(500);
await page.locator('.card[data-type="code:new"]').click();
await page.waitForTimeout(3000);
console.log('editor shown', await page.evaluate(() => !document.querySelector('.code-edbox').hidden), 'pins', await page.evaluate(() => Studio.app.doc.d.parts[0].pins.map((q) => q.name).join(',')));
// a popcount over 8 bits, with a Java loop (no drawing can say this so briefly)
const src = `public static class Ones extends Module {
    public final UInt a;
    public final UInt n;

    public Ones() {
        a = input("a", UInt.w(8));
        n = output("n", UInt.w(4));
        UInt acc = UInt.lit(0, 4);
        for (int i = 0; i < 8; i++) acc = acc.addWrap(a.bit(i).zext(4));
        n.set(acc);
    }
}`;
await page.locator('.code-ed').fill(src.replace('a.bit(i)', 'a.bitt(i)'));
await page.locator('.code-ed').press('Meta+Enter');
await page.waitForTimeout(3000);
console.log('error shown:', await page.evaluate(() => document.querySelector('.code-edbox .err').textContent));
await page.locator('.code-ed').fill(src);
await page.locator('.code-ed').press('Meta+Enter');
await page.waitForTimeout(3000);
const p = await page.evaluate(() => { const q = Studio.app.doc.d.parts[0]; return { cls: q.params.cls, pins: q.pins.map((x) => x.dir + ':' + x.name).join(',') }; });
console.log('applied', JSON.stringify(p));
await page.evaluate(() => {
  const doc = Studio.app.doc, c = doc.d.parts[0];
  const i = doc.addPart('in', c.x - 14, c.y + 2, { name: 'x', width: 8 }), o = doc.addPart('out', c.x + 16, c.y + 2, { name: 'count', width: 4 });
  doc.connect({ part: i.id, pin: 'y' }, { part: c.id, pin: 'a' }); doc.connect({ part: c.id, pin: 'n' }, { part: o.id, pin: 'a' });
});
await page.waitForTimeout(3000);
console.log('problems', JSON.stringify(await page.evaluate(() => Studio.app.gen.problems.map((x) => x.message))));
await page.evaluate(() => Studio.app.sim({ cmd: 'poke', path: 'x', value: 'b7' }));
await page.evaluate(() => Studio.app.sim({ cmd: 'step' }));
await page.waitForTimeout(800);
console.log('out wire live', await page.evaluate(() => { const d = Studio.app.doc, o = d.d.parts.find((q) => q.params.name === 'count' && q.type === 'out'); return String(Studio.app.canvas.values.get(d.netOf(o.id, 'a'))); }), 'instance', await page.evaluate(() => Studio.app.doc.d.parts[0].params.name));
console.log('ones(0xb7) =', await page.evaluate(() => String(Studio.app.board.vals.get('count'))), '(expect 6)');
await page.screenshot({ path: out + '/codepart.png' });
console.log(errors.length ? errors.join('\n') : 'no console errors');
await browser.close();
