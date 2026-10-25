// Tessera Studio: the Analyse pane and the console.
//
// Analyse: the simulator is cycle-based (no gate delays, so no glitches or setup times to show), so this
// shows what can be known honestly: the levels of logic on each module's longest combinational paths,
// from the IR, and the resources Yosys's synth_xilinx estimates when it is installed. Clicking a path
// lights its signals on the canvas.
//
// Console: everything the studio said (reloads, assertion failures, run-until stops, elaboration
// times, errors), as a log with a filter, so nothing that flashed past in a toast is lost.
(function (S) {
  'use strict';
  const { h, fill, button, icon, toast } = S.UI;

  class Analyse {
    constructor(host, app) {
      this.app = app;
      this.out = h('div');
      this.res = h('div');
      this.root = h('div.insp',
        h('h2', icon('chart'), 'Analyse'),
        h('div.sub', 'Tessera simulates cycles, not gate delays, so there are no glitches or setup times to show. These are the numbers that do mean something before a build.'),
        h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap' } },
          button('Logic levels', { icon: 'chart', title: 'The longest combinational paths, from the IR', onclick: () => this.run(false) }),
          button('Estimate resources', { icon: 'cpu', title: 'yosys synth_xilinx on the emitted SystemVerilog (a few seconds)', onclick: () => this.run(true) })),
        h('div.sec', 'Logic levels'), this.out,
        h('div.sec', 'Resources'), this.res,
        this.schSec = h('div.sec', { hidden: true }, 'Pipeline schedule'), this.sch = h('div'));
      host.append(this.root);
      this.timing = new S.Timing(this.root, app);
      fill(this.out, h('div.help', 'Press Logic levels.'));
      fill(this.res, h('div.help', 'Press Estimate resources (needs Yosys on the PATH).'));
    }

    async run(yosys) {
      if (yosys) fill(this.res, h('div.help', 'Running yosys synth_xilinx…'));
      let r;
      try { r = await S.Api.call('analyse', { yosys }); } catch (e) { toast(e.message, 'err'); return; }
      this.lastLevels = r.modules || [];
      this.levels(r.modules || []);
      if (yosys) this.resources(r.resources || {});
      this.schedule(r.schedule || []);
    }

    // a Level-2 pipeline's hazard logic, in the generator's words
    schedule(list) {
      this.schSec.hidden = !list.length;
      fill(this.sch, list.length ? [h('ul.pv-lines.an-sched', ...list.flatMap((s) => s.lines.map((l) => h('li', l)))),
        h('div.help', 'What the pipeline generator inferred from the model and the schedule: forwarding, stalls, redirects and the halt. The Pipeline tab shows it at work.')] : []);
    }

    levels(mods) {
      const els = [];
      for (const m of mods) {
        els.push(h('div.an-mod', h('b', m.instance), ' ', h('span.t', m.module), h('span.an-lv', m.levels + ' level' + (m.levels === 1 ? '' : 's'))));
        const max = Math.max(1, ...m.paths.map((p) => p.levels));
        for (const p of m.paths) {
          const row = h('button.an-path', { type: 'button', title: 'Light this path on the canvas: ' + p.path.join(' → ') },
            h('span.an-bar', { style: { width: Math.round(100 * p.levels / max) + '%' } }),
            h('span.an-n', String(p.levels)), h('span.an-end', (p.kind === 'register' ? '▣ ' : '→ ') + p.end),
            h('span.an-via', p.path.length > 1 ? 'from ' + p.path[0] + (p.path.length > 2 ? ' via ' + p.path.slice(1, -1).slice(-3).join(', ') : '') : ''));
          row.addEventListener('click', () => this.light(m.prefix, p.path));
          els.push(row);
        }
      }
      els.push(h('div.help', 'A level is one operator (an add, a compare, a mux, a gate); slices, concatenations and constant shifts are wiring. A path starts at a register, an input, or a child module’s output, and ends at a register or an output.'));
      fill(this.out, els);
    }

    // the nets of a path, by the signal paths they show
    light(prefix, path) {
      const want = new Set(path.map((s) => prefix + s));
      const paths = (this.app.gen && this.app.gen.netPaths) || {};
      const nets = Object.entries(paths).filter(([, p]) => want.has(p) || want.has('s:' + p)).map(([n]) => n);
      if (this.app.explorer) {
        // the explorer knows signals by name (show() takes part ids); a path inside a block may be named without its prefix
        let n = this.app.explorer.showNamed(path.map((s) => prefix + s));
        if (!n && prefix) n = this.app.explorer.showNamed(path);
        toast(n ? 'Shown in the explorer: ' + n + ' of ' + path.length + ' signals of ' + path.join(' → ')
          : 'None of these signals is in the explorer: ' + path.join(' → '));
        return;
      }
      if (!nets.length) { toast('None of these signals is drawn here: ' + path.join(' → ')); return; }
      this.app.canvas.selectOnly(nets.map((n) => 'w:' + n));
      this.app.say('Path of ' + path.length + ' signals: ' + path.join(' → '));
    }

    resources(r) {
      if (r.error) { fill(this.res, h('div.help', r.error)); return; }
      const cell = (k, v, what) => h('div.an-cell', h('b', String(v)), h('span', what));
      fill(this.res,
        h('div.an-cells', cell('luts', r.luts, 'LUTs'), cell('ffs', r.ffs, 'flip-flops'), cell('brams', r.brams, 'block RAMs'), cell('dsps', r.dsps, 'DSPs'),
          cell('lutrams', r.lutrams, 'LUT RAM / SRL'), cell('carries', r.carries, 'CARRY4')),
        h('div.help', 'An estimate by yosys synth_xilinx (flattened) in ' + (r.ms / 1000).toFixed(1) + ' s. Vivado usually maps the same design to fewer LUTs; a build gives the real figures.'));
    }
  }

  class Console {
    constructor(host, app) {
      this.app = app;
      this.lines = [];
      this.level = S.UI.store.get('consoleLevel', 'all');
      this.list = h('div.con-list', { role: 'log', 'aria-live': 'off' });
      const sel = h('select.input', { 'aria-label': 'Show' }, ['all', 'warnings and errors', 'errors'].map((x) => h('option', { value: x, selected: x === this.level }, x)));
      sel.addEventListener('change', () => { this.level = sel.value; S.UI.store.set('consoleLevel', sel.value); this.render(); });
      const clear = S.UI.button('', { icon: 'trash', title: 'Clear the console', onclick: () => { this.lines = []; this.render(); } });
      this.root = h('div.con', h('div.wv-tools', h('b', 'Console'), sel, clear, h('span.wv-info', 'Everything the studio has said, newest last')), this.list);
      host.append(this.root);
    }

    log(text, kind) {
      if (!text) return;
      const last = this.lines[this.lines.length - 1];
      if (last && last.text === text) { last.n++; last.at = new Date(); }
      else this.lines.push({ text, kind: kind || 'info', at: new Date(), cycle: this.app.cycle, n: 1 });
      if (this.lines.length > 2000) this.lines.splice(0, this.lines.length - 2000);
      this.render();
    }

    render() {
      if (this.root.closest('[hidden]')) { this.dirty = true; return; }
      const show = this.lines.filter((l) => this.level === 'all' || (this.level === 'errors' ? l.kind === 'err' : l.kind === 'err' || l.kind === 'warn'));
      fill(this.list, show.slice(-400).map((l) => h('div.con-line.' + l.kind,
        h('span.con-t', l.at.toLocaleTimeString('en-GB')), h('span.con-c', l.cycle != null ? 'cycle ' + l.cycle : ''), h('span.con-m', l.text + (l.n > 1 ? '  ×' + l.n : '')))));
      this.list.scrollTop = this.list.scrollHeight;
      this.dirty = false;
    }
  }

  S.Analyse = Analyse;
  S.Console = Console;
})(globalThis.Studio = globalThis.Studio || {});

// Tessera Studio: the FPGA pane. The drawing on the ZedBoard: its led…, sw… and btn… ports on the
// board's LEDs, switches and buttons, built with Vivado on the host the board is attached to and, when
// asked, programmed over JTAG. The pane shows the plan first, then the build as it goes.
(function (S) {
  'use strict';
  const { h, fill, button, icon, toast } = S.UI;

  class FpgaPane {
    constructor(host, app) {
      this.app = app;
      this.plan = h('div');
      this.out = h('div');
      this.log = h('pre.fpga-log', { 'aria-live': 'polite' });
      this.root = h('div.insp',
        h('h2', icon('cpu'), 'FPGA'),
        h('div.sub', 'The drawing on the ZedBoard’s programmable logic, at 100 MHz: led… on LD0–7, sw… on SW0–7, btn… on the push buttons, with a power-on reset. Vivado builds it on the host the board is on.'),
        h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap' } },
          button('Check the ports', { onclick: () => this.check() }),
          button('Build', { icon: 'tidy', onclick: () => this.build(false) }),
          button('Build and program', { icon: 'run', primary: true, onclick: () => this.build(true) })),
        h('div.sec', 'Ports on the board'), this.plan,
        h('div.sec', 'Build'), this.out, this.log);
      host.append(this.root);
      fill(this.plan, h('div.help', 'Check the ports to see where each one goes.'));
    }

    async check() {
      if (this.app.viewMode) { toast('The FPGA pane builds drawings; a design written in Java builds with the project’s Gradle tasks.'); return null; }
      let r;
      try { r = await S.Api.call('fpga', { action: 'plan', doc: this.app.doc.json() }); } catch (e) { fill(this.plan, h('div.help', e.message)); return null; }
      fill(this.plan, h('table', r.map.map((m) => h('tr', h('td', (m.dir === 'in' ? '← ' : '→ ') + m.port), h('td.t', m.width + ' bit' + (m.width > 1 ? 's' : '')), h('td', m.board)))),
        ...r.warnings.map((w) => h('div.help', '⚠ ' + w)), h('div.help', 'Built on ' + r.host + '.'));
      return r;
    }

    async build(program) {
      const plan = await this.check();
      if (!plan || !plan.ok) return;
      let j;
      try { j = await S.Api.call('fpga', { action: 'build', program, doc: this.app.doc.json() }); } catch (e) { toast(e.message, 'err', 7000); return; }
      this.timed = false;
      this.show(j);
      clearInterval(this.poll);
      this.poll = setInterval(async () => {
        let s;
        try { s = await S.Api.call('fpga', { action: 'status', job: j.job }); } catch (e) { clearInterval(this.poll); return; }
        this.show(s);
        if (s.state !== 'running' && s.result && s.result.timing && !this.timed) { this.timed = true; this.app.analyse.timing.show(s.result.timing, s.result); }
        if (s.state !== 'running' && !(program && s.state === 'built')) {
          clearInterval(this.poll);
          toast(s.state === 'programmed' ? 'The board is programmed.' : s.state === 'built' ? 'Built.' : 'The build failed; see the log.', s.state === 'failed' ? 'err' : 'ok', 6000);
        }
      }, 2000);
    }

    show(s) {
      const r = s.result || {};
      const cell = (v, what) => h('div.an-cell', h('b', String(v ?? '—')), h('span', what));
      fill(this.out, h('div.an-cells', cell({ running: '…', built: 'built', programmed: 'on the board', failed: 'failed' }[s.state], 'state'),
        cell(r.luts, 'LUTs'), cell(r.ffs, 'flip-flops'), cell(r.wns != null ? r.wns.toFixed(2) + ' ns' : null, 'slack at 100 MHz'), cell(r.buildSeconds != null ? r.buildSeconds + ' s' : null, 'Vivado')));
      this.log.textContent = (s.log || []).filter((l) => l.trim() && !/^\s*\*\*/.test(l)).slice(-14).join('\n');
      this.log.scrollTop = this.log.scrollHeight;
    }
  }

  S.FpgaPane = FpgaPane;
})(globalThis.Studio = globalThis.Studio || {});
