// Tessera Studio: Vivado's timing paths, in the Analyse pane. A path comes from a board build (the FPGA
// pane), from timing the design out of context with Vivado on the build host, or from a report someone
// made (report_timing -path_type full, as scripts/qor_paths.sh writes). Each hop is mapped back to the
// design by the emitter's names: exact when the cell or net is a signal of the design, approximate when
// Vivado named a LUT after a signal its logic only helps compute, unmapped when synthesis made the name up.
// Choosing a path lights its parts and wires in order on the canvas (or draws its signals, for a design in
// the explorer) and lists each step with its delay and its Java line.
(function (S) {
  'use strict';
  const { h, fill, button, toast } = S.UI;

  const ns = (x) => (Math.round(x * 1000) / 1000).toFixed(3);
  const QUAL = { exact: 'exact', approximate: '≈', unmapped: '?', outside: 'wrapper' };

  // consecutive hops that are one signal of the design, as one step
  function steps(path) {
    const out = [];
    for (const hp of path.hops) {
      const key = hp.signal ? 's:' + hp.signal + ':' + (hp.line || '') : 'o:' + hp.object;
      const last = out[out.length - 1];
      if (last && last.key === key) { last.hops.push(hp); continue; }
      out.push({ key, hops: [hp], signal: hp.signal, op: hp.op, kind: hp.kind, name: hp.name, file: hp.file, line: hp.line, code: hp.code });
    }
    for (const s of out) {
      s.delay = s.hops.reduce((a, x) => a + x.incr, 0);
      s.route = s.hops.filter((x) => x.net).reduce((a, x) => a + x.incr, 0);
      const q = s.hops.map((x) => x.quality);
      // a step's colour is its least certain hop's; its text counts the hops of each kind when they differ
      s.quality = q.includes('unmapped') ? 'unmapped' : q.includes('approximate') ? 'approximate' : q.includes('exact') ? 'exact' : 'outside';
      const kinds = [...new Set(q)];
      s.qtext = kinds.length === 1 ? QUAL[kinds[0]] : kinds.map((k) => QUAL[k] + ' ' + q.filter((x) => x === k).length).join(', ');
      const prims = {};
      for (const x of s.hops) if (!x.net && x.prim) prims[x.prim] = (prims[x.prim] || 0) + 1;
      s.prims = Object.entries(prims).map(([p, n]) => n > 1 ? p + '×' + n : p).join(' ');
    }
    return out;
  }

  class Timing {
    constructor(host, app) {
      this.app = app;
      this.period = h('input.input', { type: 'number', min: '1', max: '100', step: '0.5', value: '10', 'aria-label': 'Clock period (ns)', style: { width: '64px' } });
      this.file = h('input', { type: 'file', accept: '.rpt,.txt', hidden: true, 'aria-label': 'A Vivado timing report' });
      this.file.addEventListener('change', () => this.openReport());
      this.out = h('div.cp-out');
      this.root = h('div.cp',
        h('div.sec', 'Timing paths (Vivado)'),
        h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap', alignItems: 'center' } },
          button('Time it with Vivado', { icon: 'clock', title: 'Out-of-context synthesis, placement and routing on the build host, every port kept; the worst 20 paths come back', onclick: () => this.run() }),
          h('label.t', 'at ', this.period, ' ns'),
          button('Open a report…', { icon: 'open', title: 'A Vivado report_timing -path_type full of this design (scripts/qor_paths.sh writes paths.rpt)', onclick: () => this.file.click() }),
          this.file),
        this.out);
      host.append(this.root);
      fill(this.out, h('div.help', 'Vivado’s worst setup paths, mapped back to the drawing and to the Java. A board build in the FPGA pane brings its paths here too. openXC7’s timing is not shown: nextpnr-xilinx does not model DSP and memory cells, so its paths are not trusted for these designs.'));
    }

    async run() {
      const period = Number(this.period.value);
      if (!(period > 0)) { toast('The clock period is in ns.'); return; }
      fill(this.out, h('div.help', 'Starting Vivado on the build host…'));
      let j;
      try { j = await S.Api.call('timing', { action: 'run', period, paths: 20 }); } catch (e) { fill(this.out, h('div.help', e.message)); return; }
      clearInterval(this.poll);
      this.poll = setInterval(async () => {
        let s;
        try { s = await S.Api.call('timing', { action: 'status', job: j.job }); } catch (e) { clearInterval(this.poll); fill(this.out, h('div.help', e.message)); return; }
        if (s.state === 'running') { fill(this.out, h('div.help', (s.log || []).filter((l) => l.trim()).slice(-1)[0] || 'Vivado is running…')); return; }
        clearInterval(this.poll);
        if (s.state === 'failed' || !s.result.timing) { fill(this.out, h('div.help', 'Timing failed: ' + ((s.log || []).slice(-2).join(' ') || 'see the build host'))); return; }
        this.show(s.result.timing, s.result);
      }, 3000);
    }

    async openReport() {
      const f = this.file.files[0];
      this.file.value = '';
      if (!f) return;
      const report = await f.text();
      let r;
      try { r = await S.Api.call('timing', { action: 'map', report, source: f.name }); } catch (e) { toast(e.message, 'err', 8000); return; }
      this.show(r, null);
    }

    // a set of paths: the summary, the list, and the first path open
    show(t, build) {
      this.t = t;
      const m = t.mapping, all = m.exact + m.approximate + m.unmapped;
      const pct = (x) => all ? Math.round(100 * x / all) + '%' : '—';
      const head = [h('div.help', t.paths.length + ' path' + (t.paths.length === 1 ? '' : 's') + ' from ' + t.source + (build && build.wns != null ? ' · worst slack ' + build.wns.toFixed(3) + ' ns' : '') + '.'),
        h('div.cp-map', { title: 'How the hops of these paths map back to the design' },
          h('span.cp-q.exact', 'exact ' + pct(m.exact)), h('span.cp-q.approximate', '≈ ' + pct(m.approximate)), h('span.cp-q.unmapped', 'unmapped ' + pct(m.unmapped)),
          m.outside ? h('span.cp-q.outside', m.outside + ' in the board wrapper') : null)];
      this.list = h('div.cp-list', { role: 'list' });
      t.paths.forEach((p, i) => {
        const end = p.hops.length ? p.hops[p.hops.length - 1] : null, start = p.hops[0];
        const row = h('button.cp-row' + (p.slack < 0 ? '.bad' : '') + (p.unconstrained ? '.free' : ''), { type: 'button', role: 'listitem', 'data-rank': String(p.rank), title: p.unconstrained ? 'No clock constrains this path (it starts at an input port, which out of context has no input delay)' : '' },
          h('span.cp-slack', p.unconstrained ? 'free' : (p.slack >= 0 ? '+' : '') + p.slack.toFixed(3)),
          h('span.cp-d', ns(p.delay) + ' ns · ' + p.levels + ' levels · ' + Math.round(100 * p.route / Math.max(p.delay, 1e-9)) + '% routing'),
          h('span.cp-se', (start && (start.signal || start.object)) + ' → ' + (end && (end.signal || end.object))));
        row.addEventListener('click', () => this.select(i));
        this.list.append(row);
      });
      this.detail = h('div.cp-detail');
      fill(this.out, ...head, this.list, this.detail);
      this.select(0);
      this.app.showTab('analyse');
    }

    select(i) {
      const p = this.t.paths[i];
      if (!p) return;
      for (const r of this.list.children) r.classList.toggle('on', Number(r.getAttribute('data-rank')) === p.rank);
      const st = steps(p);
      const java = (this.app.gen && this.app.gen.className) ? this.app.gen.className + '.java' : null;
      const rows = st.map((s, k) => {
        const where = s.line ? s.file + ':' + s.line : '';
        const row = h('button.cp-step.' + s.quality, { type: 'button', title: s.hops.map((x) => (x.net ? 'net ' : x.prim + ' ') + x.object + '  ' + ns(x.incr) + ' ns').join('\n') },
          h('span.cp-k', String(k + 1)),
          // an operation's wire: what it is and the signal it feeds; a signal: its name and kind
          h('span.cp-sig', !s.signal ? s.hops[0].object : s.kind === 'temp' ? s.op + ' ' + s.name + ' → ' + s.signal : s.signal + (s.kind === 'reg' ? ' (register)' : s.kind === 'mem' ? ' (memory)' : '')),
          h('span.cp-ns', ns(s.delay) + ' ns'),
          h('span.cp-cells', (s.prims || 'route') + (s.route > 0 ? ' · ' + ns(s.route) + ' routing' : '') + ' · ' + s.qtext),
          where ? h('span.cp-where', where + (s.code ? '  ' + s.code : '')) : null);
        row.addEventListener('click', () => {
          if (s.line && java && s.file === java) this.app.code.goLine(s.line);
          else if (where) toast(where + (s.code ? ': ' + s.code : ''));
          this.light(p, [s]);
        });
        return row;
      });
      fill(this.detail,
        h('div.cp-sum', 'Path ' + p.rank + ': ' + ns(p.delay) + ' ns (logic ' + ns(p.logic) + ', routing ' + ns(p.route) + '), ' + p.levels + ' levels' + (p.levelsDetail ? ' (' + p.levelsDetail + ')' : '') + ', ' + (p.unconstrained ? 'not constrained by a clock' : 'slack ' + p.slack.toFixed(3) + ' ns') + '.'),
        this.irLevels(st), ...rows);
      this.light(p, st);
    }

    // the IR's estimate for the same endpoint, from the Logic levels list
    irLevels(st) {
      const end = [...st].reverse().find((s) => s.signal && s.kind === 'reg') || st[st.length - 1];
      const mods = (this.app.analyse && this.app.analyse.lastLevels) || null;
      if (!end || !end.signal) return null;
      if (!mods) return h('div.help', 'Press Logic levels above to compare with the IR’s estimate for ' + end.signal + '.');
      const name = end.signal.split('.').pop();
      for (const m of mods) for (const p of m.paths) if (p.end === name) return h('div.help.cp-ir', 'The IR’s estimate for ' + end.signal + ': ' + p.levels + ' level' + (p.levels === 1 ? '' : 's') + ' (operators, before mapping to LUTs).');
      return h('div.help', 'The Logic levels list has no path ending at ' + end.signal + '.');
    }

    // light the steps' parts and wires on the canvas, numbered in path order
    light(p, st) {
      const signals = [...new Set(st.map((s) => s.signal).filter(Boolean))];
      if (this.app.explorer) {
        const shown = this.app.explorer.showNamed(signals.map((x) => x.split('.').pop()));
        this.app.say(shown ? 'Drawn: ' + shown + ' of ' + signals.length + ' signals of the path' : 'None of the path’s signals is in the explorer’s list');
        return;
      }
      const gen = this.app.gen || {}, paths = gen.netPaths || {}, lines = gen.lines || {};
      const java = gen.className ? gen.className + '.java' : null;
      // a part several steps pass through gets one mark: its first step's number, and all its steps' delay
      const keys = [], marks = [], byPart = new Map();
      st.forEach((s, k) => {
        for (const [n, sp] of Object.entries(paths)) if (s.signal && (sp === s.signal || sp === 's:' + s.signal)) keys.push('w:' + n);
        if (s.line && s.file === java) for (const [part, ls] of Object.entries(lines)) {
          if (!ls.includes(s.line)) continue;
          let m = byPart.get(part);
          if (!m) { m = { part, n: k + 1, steps: [], ns: 0 }; byPart.set(part, m); marks.push(m); keys.push('p:' + part); }
          if (!m.steps.includes(k + 1)) { m.steps.push(k + 1); m.ns += s.delay; }
        }
      });
      if (!keys.length) { this.app.canvas.setMarks([]); this.app.say('None of these steps is drawn here.'); return; }
      this.app.canvas.selectOnly([...new Set(keys)]);
      this.app.canvas.setMarks(marks);
      this.app.say('Path ' + p.rank + ': ' + marks.length + ' parts and ' + keys.filter((k) => k[0] === 'w').length + ' wires lit, numbered in path order');
    }
  }

  S.Timing = Timing;
})(globalThis.Studio = globalThis.Studio || {});
