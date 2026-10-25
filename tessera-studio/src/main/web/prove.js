// Tessera Studio: the Prove pane. The design's assertions, assumptions and cover points, checked by
// Tessera's own model checker: bounded (no failure within N cycles of reset), by k-induction (for
// every cycle), or cover (the first cycle a point can be reached). A failure comes back as a
// counterexample that replaces the run: the waves and the drawing show it, and the waves' cursor
// steps back through it. Assertions that fail while simulating are listed here too.
(function (S) {
  'use strict';
  const { h, fill, button, icon, toast } = S.UI;

  class Prove {
    constructor(host, app) {
      this.app = app;
      this.depth = h('input.input', { value: S.UI.store.get('proveDepth', 20), inputmode: 'numeric', 'aria-label': 'Depth in cycles', style: { width: '80px' } });
      this.depth.addEventListener('change', () => S.UI.store.set('proveDepth', Number(this.depth.value) || 20));
      this.checks = h('div');
      this.log = h('div.prove-log', { 'aria-live': 'polite' });
      this.busy = false;
      this.root = h('div.insp',
        h('h2', icon('prove'), 'Prove'),
        h('div.sub', 'Tessera’s model checker on the running design. Assertions must hold in every cycle after reset; assumptions narrow the inputs it considers.'),
        h('div.sec', 'Properties'), this.checks,
        h('div.sec', 'Check'),
        h('div', { style: { display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'wrap' } },
          h('label', { style: { color: 'var(--muted)' } }, 'Depth'), this.depth,
          button('Bounded', { icon: 'prove', title: 'No assertion fails within this many cycles of reset (BMC)', onclick: () => this.run('bmc') }),
          button('Induction', { icon: 'ok', title: 'Every assertion holds in every cycle (k-induction with this k)', onclick: () => this.run('prove') }),
          button('Cover', { icon: 'target', title: 'The first cycle each cover point can be reached', onclick: () => this.run('cover') })),
        h('div.sec', 'Results'), this.log);
      host.append(this.root);
      this.entries = [];
      this.render();
    }

    render() {
      const ps = this.app.doc.d.parts.filter((p) => ['assert', 'assume', 'cover'].includes(p.type));
      fill(this.checks, ps.length ? h('table', ps.map((p) => {
        const row = h('tr', { style: { cursor: 'pointer' } }, h('td', { 'class': 'chk-' + p.type }, { assert: 'assert', assume: 'assume', cover: 'cover' }[p.type]), h('td', p.params.name));
        row.addEventListener('click', () => { this.app.canvas.selectOnly(['p:' + p.id]); this.app.canvas.centreOn(p.id); });
        return row;
      })) : h('div.help', this.app.viewMode ? 'This design’s properties are in its Java code (Hw.assertThat, Hw.assume, Hw.cover).' : 'No properties yet: add Assert, Assume or Cover from the palette (Verify) and wire a 1-bit condition to it.'));
      fill(this.log, this.entries.length ? this.entries.map((e) => e.el) : h('div.help', 'Nothing checked yet.'));
    }

    async run(mode) {
      if (this.busy) return;
      this.busy = true;
      const depth = Math.max(1, Math.min(400, Number(this.depth.value) || 20));
      const pending = this.entry('…', (mode === 'bmc' ? 'Bounded check to ' : mode === 'prove' ? 'Induction with k = ' : 'Cover search to ') + depth + ' cycles', 'running');
      try {
        const r = await S.Api.call('prove', { mode, depth });
        pending.el.remove();
        this.entries = this.entries.filter((e) => e !== pending);
        if (mode === 'cover') {
          const cov = Object.entries(r.covers || {});
          this.entry('COVER', cov.length ? cov.map(([n, c]) => n + (c >= 0 ? ' reached at cycle ' + c : ' not reached within ' + depth)).join('; ') : 'No cover points.', cov.every(([, c]) => c >= 0) ? 'ok' : 'warn', r.ms);
        } else if (r.status === 'FAIL') {
          const e = this.entry('FAIL', r.text.replace(/^FAIL: /, ''), 'bad', r.ms);
          e.el.append(h('div.fixes', button('Go to the failing cycle', { onclick: () => this.app.goto(r.traceFrom + r.failCycle) }),
            button('Back to the live run', { onclick: () => this.app.restartLive() })));
          this.app.message = 'Counterexample loaded: use the waves (or Backspace) to step back through it.';
          this.app.updateBar();
          toast('“' + r.check + '” fails at cycle ' + r.failCycle + ' after reset; the counterexample is in the waves.', 'err', 7000);
          this.markFailing(r.check);
        } else {
          this.entry(r.status, r.text.replace(/^(PASS|PROVEN|UNKNOWN)(: | by )?/, (m, a, b) => b === ' by ' ? 'by ' : ''), r.status === 'PROVEN' || r.status === 'PASS' ? 'ok' : 'warn', r.ms);
          this.markFailing(null);
        }
      } catch (e) {
        pending.el.remove();
        this.entries = this.entries.filter((x) => x !== pending);
        this.entry('ERROR', e.message, 'bad');
      }
      this.busy = false;
      this.render();
    }

    // an assertion failed while simulating
    simFailed(cycle, message) {
      this.entry('FAIL', 'At cycle ' + cycle + ' in simulation: ' + message, 'bad');
      const m = /'([^']+)'/.exec(message);
      this.markFailing(m ? m[1] : null);
      this.render();
    }

    markFailing(name) {
      for (const p of this.app.doc.d.parts) {
        const el = this.app.canvas.partEls.get(p.id);
        if (el) el.classList.toggle('failing', !!name && ['assert', 'assume'].includes(p.type) && p.params.name === name);
      }
    }

    entry(status, text, kind, ms) {
      const el = h('div.prob' + (kind === 'warn' ? '.warning' : ''), h('span.sev.' + kind, kind === 'ok' ? '✓' : kind === 'running' ? '…' : kind === 'warn' ? '?' : '×'),
        h('span', h('b', status), ' ', text, ms != null ? h('span', { style: { color: 'var(--faint)' } }, ' · ' + ms + ' ms') : null));
      const e = { el };
      this.entries.unshift(e);
      this.render();
      return e;
    }
  }

  S.Prove = Prove;
})(globalThis.Studio = globalThis.Studio || {});
