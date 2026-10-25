// Tessera Studio: testbenches. A JUnit-style class whose @Test methods take a Simulator drives the live
// run: what each test pokes and steps is in the waves and the history, and a failing assertion says its
// line and marks its cycle. The source is part of the drawing (saved in its .java file with it). "Each test
// from reset" starts every test as JUnit would; "Save as a JUnit test" puts the class in the project's
// src/test/java, where Gradle runs it with a new simulator per test.
(function (S) {
  'use strict';
  const { h, fill, button, toast, store, download } = S.UI;

  // a new testbench for the running design, from the server, which knows its class and constructor: with a
  // @Design method the same source runs under JUnit too
  const template = async (app) => {
    const ins = (app.gen && app.gen.ports || []).filter((p) => p.dir === 'in' && !p.clockOrReset);
    const outs = (app.gen && app.gen.ports || []).filter((p) => p.dir === 'out');
    return (await S.Api.call('bench', { action: 'template', in: ins[0] ? ins[0].name : 'en', out: outs[0] ? outs[0].name : 'value' })).source;
  };

  class Bench {
    constructor(host, app) {
      this.app = app;
      this.ed = h('textarea.code-ed.bench-ed', { spellcheck: 'false', 'aria-label': 'The testbench' });
      this.out = h('div.bench-out', { 'aria-live': 'polite' });
      this.ed.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); this.run(); }
        if (e.key === 'Tab') { e.preventDefault(); const a = this.ed.selectionStart; this.ed.setRangeText('    ', a, this.ed.selectionEnd, 'end'); }
      });
      this.ed.addEventListener('change', () => this.keep());
      this.reset = h('input', { type: 'checkbox', checked: !!store.get('benchFromReset', false), 'aria-label': 'Each test from reset' });
      this.reset.addEventListener('change', () => store.set('benchFromReset', this.reset.checked));
      this.root = h('div.bench',
        h('div.wv-tools', h('b', 'Testbench'), button('Run the tests', { icon: 'run', primary: true, onclick: () => this.run() }),
          button('New from the ports', { onclick: async () => { this.ed.value = await template(this.app); this.keep(); } }),
          h('label.bench-reset', { title: 'Inputs to 0 and reset before each test, as under JUnit, where each test gets a new simulator' }, this.reset, ' Each test from reset'),
          button('Save as a JUnit test', { onclick: () => this.saveJUnit() }),
          h('span.wv-info', 'JUnit-style: @Test methods that take a Simulator; ⌘Enter runs them on the live run')),
        h('div.bench-body', this.ed, this.out));
      host.append(this.root);
      app.doc.on && app.doc.on(() => this.load());
      this.load();
    }

    load() {
      const src = this.app.doc.d.bench;
      if (document.activeElement !== this.ed && this.ed.value !== (src || '')) this.ed.value = src || '';
      if (!this.ed.value) fill(this.out, h('div.help', 'No testbench yet: “New from the ports” starts one. Each test runs from the cycle the run is at (or from reset, if ticked), one after another, and the waves keep everything they did.'));
    }

    // the source belongs to the drawing
    keep() {
      if ((this.app.doc.d.bench || '') === this.ed.value) return;
      this.app.doc.d.bench = this.ed.value;   // not a drawing edit: no undo step, but saved with the drawing
      this.app.doc.emit('testbench');
    }

    async run() {
      this.keep();
      if (!this.ed.value.trim()) { this.ed.value = await template(this.app); this.keep(); }
      fill(this.out, h('div.help', 'Compiling and running…'));
      let r;
      try { r = await S.Api.call('bench', { source: this.ed.value, fromReset: this.reset.checked }); } catch (e) { fill(this.out, h('div.err', e.message)); return; }
      if (!r.ok) { fill(this.out, h('div.err', 'It does not compile:'), ...r.errors.map((x) => h('div.bench-err', x))); return; }
      const pass = r.results.filter((x) => x.ok).length;
      fill(this.out, h('div.bench-sum' + (pass === r.results.length ? '.ok' : '.bad'), pass + ' of ' + r.results.length + ' passed'),
        ...r.results.map((x) => {
          const row = h('button.bench-row' + (x.ok ? '.ok' : '.bad'), { type: 'button', title: x.ok ? 'Show its cycles in the waves' : 'Go to where it failed' },
            h('span.bench-mark', x.ok ? '✓' : '✗'), h('b', x.test), h('span.t', ' cycles ' + x.from + '–' + x.to),
            x.ok ? null : h('div.bench-msg', (x.line ? 'line ' + x.line + ': ' : '') + x.message));
          row.addEventListener('click', () => {
            this.app.waves.t0 = Math.max(0, x.from - 2); this.app.waves.t1 = Math.max(x.to + 2, x.from + 20); this.app.waves.follow = false; this.app.waves.fetchSoon();
            if (!x.ok) this.app.goto && this.app.goto(x.to);
          });
          return row;
        }));
      toast(pass + ' of ' + r.results.length + ' tests passed.', pass === r.results.length ? 'ok' : 'err', 5000);
    }
  }

  // the class as a JUnit test: written into the project's src/test/java when the Studio has a project, else downloaded
  Bench.prototype.saveJUnit = async function () {
    this.keep();
    if (!this.ed.value.trim()) { this.ed.value = await template(this.app); this.keep(); }
    let r;
    try { r = await S.Api.call('bench', { action: 'junit', source: this.ed.value }); } catch (e) { toast(e.message, 'err', 8000); return; }
    const note = r.note ? h('p.help', r.note) : null;
    if (!r.path) { download(r.file, r.java, 'text/x-java'); toast('Downloaded ' + r.file + '. ' + (r.note || ''), 'ok', 6000); return; }
    const other = button('Download instead', { onclick: () => { download(r.file, r.java, 'text/x-java'); other.closest('.modal').querySelector('.actions button').click(); } });
    const ok = await S.UI.modal('Save as a JUnit test', h('div', h('p', (r.exists ? 'Replace ' : 'Write ') + r.path + '?'),
      h('p.help', 'Under JUnit (Gradle) each test gets a new simulator, from reset.'), note, other), { ok: r.exists ? 'Replace it' : 'Write it' });
    if (!ok) return;
    try { r = await S.Api.call('bench', { action: 'junit', source: this.ed.value, write: true, overwrite: true }); } catch (e) { toast(e.message, 'err', 8000); return; }
    toast(r.wrote ? 'Wrote ' + r.path + '.' : 'Nothing was written.', r.wrote ? 'ok' : 'err', 6000);
  };

  S.Bench = Bench;
})(globalThis.Studio = globalThis.Studio || {});
