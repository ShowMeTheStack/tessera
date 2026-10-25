// Tessera Studio: the application. It lays out the toolbar, the panels, the canvas and the waves;
// keeps the server in step with the drawing (every change is generated, compiled, elaborated and
// hot-swapped into the running simulation); and routes the stream of values to the canvas, the
// inspector and the waves. Keyboard shortcuts and the command palette live here.
(function (S) {
  'use strict';
  const { h, button, icon, toast, store } = S.UI;

  const SAMPLES = {
    counter: () => ({ tessera_studio: 1, name: 'Counter', title: 'An 8-bit counter with enable', package: 'studio', next: 20,
      parts: [
        { id: 'p1', type: 'in', x: 2, y: 10, rot: 0, mir: false, params: { name: 'en', width: 1 } },
        { id: 'p2', type: 'reg', x: 24, y: 4, rot: 0, mir: false, params: { name: 'count', width: 8, init: '0', enable: true } },
        { id: 'p3', type: 'add', x: 12, y: -6, rot: 0, mir: false, params: { mode: 'wrap' } },
        { id: 'p4', type: 'const', x: 2, y: -4, rot: 0, mir: false, params: { value: '1', width: 8 } },
        { id: 'p5', type: 'out', x: 40, y: 5, rot: 0, mir: false, params: { name: 'value', width: 8 } },
      ],
      nets: [
        { id: 'w1', pins: [['p2', 'q'], ['p3', 'a'], ['p5', 'a']] },
        { id: 'w2', pins: [['p4', 'y'], ['p3', 'b']] },
        { id: 'w3', pins: [['p3', 'y'], ['p2', 'd']] },
        { id: 'w4', pins: [['p1', 'y'], ['p2', 'en']] },
      ], notes: [] }),
    blinky: () => ({ tessera_studio: 1, name: 'Blinky', title: 'A blinking LED that a button inverts', package: 'studio', next: 10,
      parts: [
        { id: 'p1', type: 'in', x: 0, y: 6, rot: 0, mir: false, params: { name: 'btn0', width: 1 } },
        { id: 'p2', type: 'blink', x: 0, y: 0, rot: 0, mir: false, params: { seconds: 0.5 } },
        { id: 'p3', type: 'xor', x: 14, y: 1, rot: 0, mir: false, params: { inputs: 2 } },
        { id: 'p4', type: 'out', x: 26, y: 2, rot: 0, mir: false, params: { name: 'led', width: 1 } },
      ],
      nets: [{ id: 'w1', pins: [['p2', 'y'], ['p3', 'in0']] }, { id: 'w2', pins: [['p1', 'y'], ['p3', 'in1']] }, { id: 'w3', pins: [['p3', 'y'], ['p4', 'a']] }], notes: [] }),
  };

  class App {
    async start() {
      document.documentElement.dataset.theme = store.get('theme', 'dark');
      await S.Parts.load();
      try { this.modules = await S.Api.call('modules', {}); } catch (e) { this.modules = []; }
      try { this.hz = (await S.Api.call('info', {})).hz || 0; if (this.waves) this.waves.hz = this.hz; } catch (e) { this.hz = 0; }
      // the run's speed is this page's setting, not whatever the last page left on the server
      S.Api.call('sim', { cmd: 'rate', rate: Number(store.get('rate', 10)) }).catch(() => {});
      S.Api.call('guide', {}).then((g) => { this.guideSections = g; }).catch(() => {});
      for (const p of Object.values(SAMPLES)) { /* pins are filled in when loaded */ }
      const saved = store.get('doc', null);
      this.doc = new S.Doc.Doc(this.fill(saved || SAMPLES.counter()));
      this.keys = new S.Keys(this);
      this.view = { overbars: true, heat: false, symbols: 'ansi', ...(store.get('view', {}) || {}) };
      Object.assign(S.Parts.prefs, { overbars: this.view.overbars, symbols: this.view.symbols });
      this.layout();
      this.bEdge.hidden = true;   // shown for a design with clocks of their own periods
      this.canvas.heatOn = !!this.view.heat;
      this.doc.on((why) => this.changed(why));
      this.sync();
      this.connect();
      S.Api.call('designs').then((l) => { this.designList = l; }).catch(() => {});
      this.bindKeys();
      this.updateBar();
    }

    // parts saved without their pins (samples, older files) get them now
    fill(d) {
      for (const b of Object.values(d.blocks || {})) this.fill(b);
      for (const p of d.parts) {
        p.params = { ...S.Parts.defaults(p.type), ...(p.params || {}) };
        if (!p.pins) p.pins = this.pinsFor(p);
      }
      return d;
    }

    pinsFor(p) {
      if (p.type === 'block') return [];   // the Doc fills these from the block (syncBlocks)
      if (p.type === 'module' || p.type === 'code') return S.App.modulePins(p.params.pins, p.params);
      // the catalogue's pins are for default params; parts whose pins depend on params are computed here
      const t = S.Parts.get(p.type);
      if (!t) return [];
      const needs = ['and', 'or', 'xor', 'nand', 'nor', 'xnor', 'mux', 'concat', 'reg', 'mem', 'fsm', 'table'].includes(p.type);
      if (!needs) return t.pins;
      return this.localPins(p) || t.pins;
    }

    // pins for parameterised parts, computed here so that editing stays instant (the server checks them again)
    localPins(p) {
      const n = Number(p.params.inputs) || 2, list = [];
      switch (p.type) {
        case 'and': case 'or': case 'xor': case 'nand': case 'nor': case 'xnor': case 'concat':
          for (let k = 0; k < n; k++) list.push({ name: 'in' + k, dir: 'in', side: 'l', label: '' });
          list.push({ name: 'y', dir: 'out', side: 'r', label: p.type === 'concat' ? '{}' : '' });
          return list;
        case 'mux':
          for (let k = 0; k < n; k++) list.push({ name: 'in' + k, dir: 'in', side: 'l', label: String(k) });
          list.push({ name: 'sel', dir: 'in', side: 'b', label: 'sel' }, { name: 'y', dir: 'out', side: 'r', label: '' });
          return list;
        case 'reg':
          list.push({ name: 'd', dir: 'in', side: 'l', label: 'D' });
          if (p.params.enable === true || p.params.enable === 'true') list.push({ name: 'en', dir: 'in', side: 'l', label: 'EN' });
          list.push({ name: 'q', dir: 'out', side: 'r', label: 'Q' });
          return list;
        case 'table':
          for (const x of String(p.params.inputs || '').trim().split(/\s+/).filter(Boolean)) list.push({ name: x, dir: 'in', side: 'l', label: x });
          for (const x of String(p.params.outputs || '').trim().split(/\s+/).filter(Boolean)) list.push({ name: x, dir: 'out', side: 'r', label: x });
          return list;
        case 'fsm': {
          for (const x of String(p.params.inputs || '').trim().split(/\s+/).filter(Boolean)) list.push({ name: x, dir: 'in', side: 'l', label: x });
          list.push({ name: 'state', dir: 'out', side: 'r', label: 'state' });
          for (const line of String(p.params.outputs || '').split(/\n/)) { const n = line.split(':')[0].trim(); if (n) list.push({ name: n, dir: 'out', side: 'r', label: n }); }
          return list;
        }
        case 'mem':
          list.push({ name: 'addr', dir: 'in', side: 'l', label: 'addr' });
          if (String(p.params.write) !== 'false') list.push({ name: 'wdata', dir: 'in', side: 'l', label: 'wdata' }, { name: 'we', dir: 'in', side: 'l', label: 'we' });
          list.push({ name: 'rdata', dir: 'out', side: 'r', label: 'rdata' });
          return list;
        default: return null;
      }
    }

    // ---------------------------------------------------------------- layout

    layout() {
      const left = h('aside.side.left', { 'aria-label': 'Parts' });
      const centre = h('section.centre');
      const right = h('aside.side.right', { 'aria-label': 'Inspector, code and problems' });
      const canvasHost = h('div', { style: { display: 'flex', flex: '1', minHeight: '0' } });
      this.dockH = store.get('dockH', 220);
      const dock = h('div.dock', { style: { height: this.dockH + 'px' } });
      const split = S.UI.splitter('h', () => this.dockH, (v) => { this.dockH = Math.max(80, Math.min(innerHeight - 260, v)); dock.style.height = this.dockH + 'px'; store.set('dockH', this.dockH); }, { invert: true });
      centre.append(canvasHost, split, dock);
      this.status = h('footer.status');
      this.sr = h('div.sr-only', { 'aria-live': 'assertive', role: 'status' });   // what keyboard wiring says
      this.toolbar = this.makeToolbar();
      const lw = store.get('leftW', 248), rw = store.get('rightW', 340);
      this.root = h('div.app', { style: { '--left': lw + 'px', '--right': rw + 'px' } }, this.toolbar,
        h('div.main', left, S.UI.splitter('v', () => parseInt(this.root.style.getPropertyValue('--left')), (v) => { v = Math.max(180, Math.min(420, v)); this.root.style.setProperty('--left', v + 'px'); store.set('leftW', v); }),
          centre,
          S.UI.splitter('v', () => parseInt(this.root.style.getPropertyValue('--right')), (v) => { v = Math.max(260, Math.min(640, v)); this.root.style.setProperty('--right', v + 'px'); store.set('rightW', v); }, { invert: true }),
          right),
        this.status, this.sr);
      document.getElementById('app').replaceWith(this.root);

      this.crumbBar = h('div.crumbs', { style: { display: 'none' } });
      canvasHost.style.position = 'relative';
      canvasHost.append(this.crumbBar);
      this.canvas = new S.Canvas(canvasHost, this.doc, {
        onSelect: () => { this.inspector.render(); this.code.mark(); this.updateBar(); },
        onNote: (m) => toast(m),
        onRouted: (r) => this.routeWarn(r),
        onEdit: (id) => {
          if (this.explorer) { this.explorer.traceDriver(id); return; }
          const part = this.doc.part(id);
          if (part && part.type === 'block') { this.openBlock(part); return; }
          this.showTab('inspect'); const f = this.inspector.root.querySelector('input, select'); f && f.focus();
        },
        onToggle: (part, bit) => this.toggle(part, bit),
        onPress: (part, bit, down) => this.press(part, bit, down),
        onMenu: (e, part, wire, pt) => this.contextMenu(e, part, wire, pt),
        onQuickAdd: (pt) => this.quickAdd(pt),
        onLabel: (net) => this.labelNet(net),
        onHover: (x) => this.hover(x),
        onDropOutside: (from, e) => { if (e.target.closest && e.target.closest('.dock') && from.net) this.probe(from.net); },
        onDropEmpty: (from, pt) => this.quickAdd(pt, from),
        onView: () => {},
        readOnly: () => this.viewMode,
      });
      const lt = this.tabs(left, [['parts', 'Parts', 'cpu']]);
      this.leftBody = lt.parts;
      this.leftTab = left.querySelector('.tab');
      this.palette = new S.Palette(lt.parts, this);
      const rt = this.tabs(right, [['inspect', 'Inspect', 'target'], ['code', 'Java', 'code'], ['problems', 'Problems', 'problem'], ['prove', 'Prove', 'prove'], ['board', 'Board', 'board'], ['analyse', 'Analyse', 'chart'], ['fpga', 'FPGA', 'cpu']], true);
      this.inspector = new S.Inspector(rt.inspect, this);
      this.code = new S.Code(rt.code, this);
      this.problems = new S.Problems(rt.problems, this);
      this.prove = new S.Prove(rt.prove, this);
      this.board = new S.Board(rt.board, this);
      this.analyse = new S.Analyse(rt.analyse, this);
      this.fpga = new S.FpgaPane(rt.fpga, this);
      const dt = this.tabs(dock, [['waves', 'Waves', 'wave'], ['bench', 'Testbench', 'ok'], ['pipe', 'Pipeline', 'step'], ['console', 'Console', 'console']]);
      dock.classList.add('tabbed');
      this.waves = new S.Waves(dt.waves, this);
      this.console = new S.Console(dt.console, this);
      this.bench = new S.Bench(dt.bench, this);
      this.pipeview = new S.PipeView(dt.pipe, this);
      S.UI.onToast = (text, kind) => this.console.log(text, kind === 'err' ? 'err' : kind === 'warn' ? 'warn' : 'info');
      this.probTab = this.rightTabs.problems;
    }

    tabs(host, list, isRight) {
      const bar = h('div.tabs', { role: 'tablist' }), bodies = {}, tabs = {};
      for (const [id, label, ic] of list) {
        const t = h('button.tab', { type: 'button', role: 'tab', 'data-tab': id }, icon(ic), h('span.lb', label));
        const b = h('div.tabbody', { role: 'tabpanel', hidden: true });
        t.addEventListener('click', () => this.showTab(id));
        bar.append(t);
        bodies[id] = b; tabs[id] = t;
      }
      host.append(bar, ...Object.values(bodies));
      this.tabSets = this.tabSets || [];
      this.tabSets.push({ tabs, bodies });
      if (isRight) this.rightTabs = tabs;
      this.showTab(list[0][0], true);
      return bodies;
    }

    showTab(id, quiet) {
      for (const set of this.tabSets || []) {
        if (!set.tabs[id]) continue;
        for (const [k, t] of Object.entries(set.tabs)) { t.classList.toggle('on', k === id); set.bodies[k].hidden = k !== id; }
      }
      if (!quiet && id === 'code') this.code.mark();
      if (id === 'console' && this.console && this.console.dirty) this.console.render();
      if (id === 'waves' && this.waves) requestAnimationFrame(() => this.waves.draw());
      if (id === 'pipe' && this.pipeview) this.pipeview.refresh();
    }

    makeToolbar() {
      this.docName = h('input.docname', { 'aria-label': 'Design name', spellcheck: 'false' });
      this.docName.addEventListener('change', () => {
        const v = this.docName.value.trim();
        if (!/^[A-Za-z_]\w*$/.test(v)) { toast('A design name is a Java class name: a letter or _, then letters, digits or _.', 'err'); this.docName.value = this.doc.d.name; return; }
        this.doc.edit('rename', () => { this.doc.d.name = v; });
      });
      this.bUndo = this.keyed(button('', { icon: 'undo', title: 'Undo', onclick: () => this.doc.undo() }), 'Undo', 'undo');
      this.bRedo = this.keyed(button('', { icon: 'redo', title: 'Redo', onclick: () => this.doc.redo() }), 'Redo', 'redo');
      this.bRun = this.keyed(button('Run', { icon: 'pause', title: 'Run or pause', primary: true, onclick: () => this.runPause() }), 'Run or pause', 'runPause');
      this.rate = h('select.input', { title: 'Speed: cycles per second', 'aria-label': 'Speed' },
        [1, 2, 5, 10, 50, 200, 1000, 10000, 100000, 0].map((r) => h('option', { value: r, selected: r === store.get('rate', 10) }, r === 0 ? 'As fast as possible' : r + ' cycles/s')));
      this.rate.addEventListener('change', () => { store.set('rate', Number(this.rate.value)); this.sim({ cmd: 'rate', rate: Number(this.rate.value) }); });
      this.cyclePill = h('span.pill.cycle', { title: 'The cycle shown' }, '—');
      this.liveDot = h('span.live-dot', { title: 'Connection to the studio server' });
      this.probBadge = h('button.btn', { type: 'button', title: 'Problems', onclick: () => this.showTab('problems') }, icon('problem'), h('span.lb', 'Problems'), h('span.badge.ok', '0'));
      // on a narrow screen the panels are drawers: these two buttons open them
      const drawer = (side, ic, title) => button('', { icon: ic, title, className: 'narrow-only', onclick: () => { this.root.classList.toggle('show-' + side); this.root.classList.remove('show-' + (side === 'left' ? 'right' : 'left')); } });
      return h('header.toolbar', { role: 'toolbar', 'aria-label': 'Studio' },
        drawer('left', 'cpu', 'Parts'),
        h('div.brand', h('span.logo'), 'Tessera Studio'), this.docName,
        h('span.edit-group', { style: { display: 'contents' } },
        this.keyed(button('', { icon: 'file', title: 'New design', onclick: () => this.newDoc() }), 'New design', 'newDoc'),
        this.keyed(button('', { icon: 'open', title: 'Open a .java file', onclick: () => this.open() }), 'Open a .java file', 'open'),
        this.keyed(button('', { icon: 'save', title: 'Save as .java', onclick: () => this.save() }), 'Save as .java', 'save'),
        button('', { icon: 'export', title: 'Export…', onclick: (e) => this.exportMenu(e.currentTarget) }),
        h('span.sep'), this.bUndo, this.bRedo, h('span.sep'),
        button('', { icon: 'rotate', title: 'Turn (R)', onclick: () => this.doc.rotate(this.canvas.selectedParts(), 90) }),
        button('', { icon: 'mirror', title: 'Mirror (M)', onclick: () => this.doc.mirror(this.canvas.selectedParts()) }),
        button('', { icon: 'trash', title: 'Delete (Del)', onclick: () => this.deleteSel() }),
        button('', { icon: 'block', title: 'Make a block of the selection (B)', onclick: () => this.makeBlockSel() }),
        button('', { icon: 'tidy', title: 'Tidy up: lay the drawing out again', onclick: () => this.tidy() })),
        button('Designs', { icon: 'cpu', title: 'Open a design written in Java and watch it run', onclick: (e) => this.designsMenu(e.currentTarget) }),
        h('span.grow'),
        h('div.runbar', this.liveDot, this.bRun,
          this.keyed(button('', { icon: 'step', title: 'One cycle', onclick: () => this.sim({ cmd: 'step', n: 1 }) }), 'One cycle', 'step'),
          this.keyed(button('', { icon: 'stepN', title: 'Ten cycles', onclick: () => this.sim({ cmd: 'step', n: 10 }) }), 'Ten cycles', 'step10'),
          this.bEdge = button('', { icon: 'clock', title: 'To the next edge of a clock', onclick: (e) => this.edgeMenu(e.currentTarget) }),
          this.keyed(button('', { icon: 'target', title: 'Run until a condition holds', onclick: () => this.runUntil() }), 'Run until a condition holds', 'until'),
          this.keyed(button('', { icon: 'back', title: 'One cycle back', onclick: () => this.back(1) }), 'One cycle back', 'back'),
          this.keyed(button('', { icon: 'reset', title: 'Reset', onclick: () => this.sim({ cmd: 'reset' }) }), 'Reset', 'reset'),
          this.rate, this.cyclePill),
        h('span.sep'), this.probBadge, drawer('right', 'target', 'Inspector, code, problems, prove, board'),
        this.keyed(button('', { icon: 'book', title: 'The guide', onclick: () => this.guide() }), 'The guide', 'guide'),
        this.keyed(button('', { icon: 'keyboard', title: 'Shortcuts', onclick: () => this.keysSheet() }), 'Shortcuts (change them here)', 'keys'),
        button('', { icon: 'sun', title: 'Light or dark (T)', onclick: () => this.theme() }));
    }

    updateBar() {
      if (!this.bUndo) return;
      this.bUndo.disabled = !this.doc.canUndo();
      this.bRedo.disabled = !this.doc.canRedo();
      if (document.activeElement !== this.docName) this.docName.value = this.doc.d.name;
      const c = this.problems ? this.problems.counts() : { errors: 0, warnings: 0 };
      const b = this.probBadge.querySelector('.badge');
      b.textContent = c.errors + c.warnings;
      b.className = 'badge' + (c.errors ? '' : c.warnings ? '.warn' : '.ok').replace('.', ' ');
      const sel = this.canvas.sel.size;
      if (this.console && this.message && this.message !== this.loggedMessage) {
        this.loggedMessage = this.message;
        this.console.log(this.message, /fail|error|not answering|cannot|Not run/i.test(this.message) ? 'err' : 'info');
      }
      this.status.replaceChildren(...[
        h('span.msg', this.message || ''),
        this.hoverText ? h('span.hover', this.hoverText) : null,
        sel ? h('span', h('b', sel), ' selected') : null,
        h('span', h('b', this.doc.d.parts.length), ' parts · ', h('b', this.doc.d.nets.length), ' wires'),
        h('span', 'zoom ', h('b', Math.round(this.canvas.view.z * 100) + '%'))].filter(Boolean));
    }

    // ---------------------------------------------------------------- server

    changed(why) {
      this.updateBar();
      if (this.prove) this.prove.render();
      if (this.viewMode) return;          // a view of Java code: the layout may move, nothing is generated
      this.autosave();
      if (why !== 'select') this.syncSoon();
    }

    // open a design written in Java: the server elaborates and runs it and sends its drawing
    async openDesign(name, program) {
      this.message = 'Elaborating ' + name + (program ? ' with ' + program : '') + '…';
      this.updateBar();
      let r;
      try { r = await S.Api.call('view', { design: name, program: program || '' }); } catch (e) { toast(e.message, 'err'); return; }
      this.designProgram = program || null;
      this.viewMode = true;
      this.root.classList.add('viewing');
      let d = r.doc;
      d.name = d.name || 'Design';
      this.fullView = JSON.parse(JSON.stringify(r.doc));
      // large designs open in the explorer: signals are drawn as they are picked and traced
      this.explorer = d.parts.length > 120 ? new S.Explorer(this, this.fullView) : null;
      if (this.explorer) { d = { ...d, parts: [], nets: [] }; this.explorer.panel(this.leftBody); this.leftTab.querySelector('.lb').textContent = 'Signals'; }
      this.doc.d = d;
      this.doc.undoStack = []; this.doc.redoStack = [];
      this.doc.reindex();
      const pos = S.Tidy.layout(d.parts, d.nets);
      for (const p of d.parts) { const q = pos.get(p.id); p.x = q.x; p.y = q.y; }
      this.gen = { netPaths: r.netPaths, netWidths: r.netWidths, ports: r.ports, problems: [], lines: {}, java: '// ' + name + ' is written in Java; this is a drawing of its signals.\n', elaborateMs: r.elaborateMs };
      this.boardPorts(r.ports);
      this.canvas.widths = r.netWidths || {};
      this.canvas.problemParts = new Map();
      this.canvas.sel.clear();
      this.waves.rows = []; this.waves.saved(); this.waves.data = {};
      this.doc.emit('open');
      this.problems.set([]);
      this.code.set(this.gen.java);
      this.inspector.render();
      const sig = await S.Api.call('waves', { list: true }).catch(() => null);
      if (sig) this.waves.setSignals(sig.signals);
      for (const p of r.ports || []) if (!p.clockOrReset && this.waves.rows.length < 12 && p.width > 1) this.waves.add(p.name);
      this.message = r.message + ' · ' + this.fullView.parts.length + ' parts, ' + this.fullView.nets.length + ' wires · elaborated in ' + r.elaborateMs + ' ms';
      if (this.explorer) this.canvas.empty.querySelector('.empty').replaceChildren(S.UI.icon('search'), h('b', this.fullView.name + ' is running'),
        h('span', this.explorer.signals.length + ' signals. Pick one on the left to draw it with what drives it; right-click a part to trace further.'));
      requestAnimationFrame(() => this.canvas.fit());
      this.updateBar();
    }

    async designsMenu(anchor) {
      let list;
      try { list = await S.Api.call('designs'); } catch (e) { toast(e.message, 'err'); return; }
      if (!list.length) { toast('No Java designs are registered (start the studio with --designs).'); return; }
      let progs = { cores: [], programs: [] };
      try { progs = await S.Api.call('programs', {}); } catch (e) { /* none */ }
      const r = anchor.getBoundingClientRect();
      S.UI.menu(r.left, r.bottom + 4, list.map((n) => ({ label: n + (progs.cores.includes(n) && progs.programs.length ? ' …' : ''), action: () => (progs.cores.includes(n) && progs.programs.length ? this.chooseProgram(n, progs.programs) : this.openDesign(n)) })));
    }

    // a core runs its benchmark loop, or a riscv-test (it passes when gp, x3, becomes 1 and the core halts)
    async chooseProgram(name, programs) {
      const sel = h('select.input', { 'aria-label': 'Program' }, h('option', { value: '' }, 'The benchmark loop (runs on and on)'), ...programs.map((p) => h('option', { value: p }, p)));
      const ok = await S.UI.modal('Open ' + name, h('div', h('div.field', h('label', 'Program'), sel,
        h('div.help', 'A riscv-test passes when gp (x3) becomes 1 and the core halts: run until core_halted == 1, then look at the register file (x3) in the inspector.'))), { ok: 'Open' });
      if (ok) this.openDesign(name, sel.value);
    }

    leaveView() {
      if (!this.viewMode) return;
      this.viewMode = false;
      this.explorer = null;
      this.root.classList.remove('viewing');
      this.leftBody.replaceChildren(this.palette.root);
      this.leftTab.querySelector('.lb').textContent = 'Parts';
      this.canvas.empty.querySelector('.empty').replaceChildren(S.UI.icon('cpu'), h('b', 'An empty sheet'), h('span', 'Drag a part from the palette, press / to search for one, or open a design.'));
    }

    autosave() { clearTimeout(this.saveT); this.saveT = setTimeout(() => store.set('doc', this.doc.json()), 300); }

    syncSoon() { this.lastSyncDone = false; clearTimeout(this.syncT); this.syncT = setTimeout(() => this.sync(), 180); }

    async sync() {
      const seq = this.syncSeq = (this.syncSeq || 0) + 1;
      this.lastSyncDone = false;   // tests wait for this: the last edit has been elaborated
      let r;
      try { r = await S.Api.call('elaborate', { doc: this.doc.json() }); }
      catch (e) { this.message = 'The studio server is not answering: ' + e.message; this.lastSyncDone = true; this.updateBar(); return; }
      if (seq !== this.syncSeq) return;   // a newer edit is already on its way
      this.gen = r;
      const lvl = this.doc.level();
      this.canvas.widths = (lvl ? (r.blockWidths || {})[lvl] : r.netWidths) || {};
      this.canvas.problemParts = new Map();
      for (const p of r.problems || []) for (const id of p.parts || []) if (this.canvas.problemParts.get(id) !== 'error') this.canvas.problemParts.set(id, p.severity);
      this.canvas.render();
      this.genProblems = r.problems || [];
      this.problems.set([...this.genProblems, ...(this.routeProblems || [])]);
      this.code.set(r.java);
      this.inspector.render();
      this.message = r.elaborated ? (r.message || 'elaborated') + (r.elaborateMs != null ? ' · ' + r.elaborateMs + ' ms' : '') : r.message;
      if (r.elaborated) {
        this.boardPorts(r.ports);
        this.setClocks(r.clocks || []);
        const sig = await S.Api.call('waves', { list: true }).catch(() => null);
        if (sig) {
          // rows of signals the running design no longer has are dropped
          const before = this.waves.rows.length;
          this.waves.rows = this.waves.rows.filter((x) => sig.signals[x.path] !== undefined);
          if (this.waves.rows.length !== before) this.waves.saved();
          this.waves.setSignals(sig.signals);
          this.waves.renderNames();
        }
        if (!this.wavesSeeded && !this.waves.rows.length) {
          this.wavesSeeded = true;
          for (const p of r.ports || []) if (!p.clockOrReset) this.waves.add(p.name);
          for (const part of this.doc.d.parts) if (part.type === 'reg') this.waves.add(part.params.name);
        }
      }
      if (seq === this.syncSeq) this.lastSyncDone = true;
      this.updateBar();
    }

    connect() {
      S.Api.stream((f) => this.frame(f), (st) => { this.liveDot.classList.toggle('on', st === 'live'); if (st === 'lost') this.message = 'Lost the studio server; reconnecting…'; });
    }

    frame(f) {
      if (f.type === 'assert') {
        toast('Stopped at cycle ' + f.cycle + ': ' + f.message, 'err', 7000);
        this.prove.simFailed(f.cycle, f.message);
        this.message = 'Stopped at cycle ' + f.cycle + ': ' + f.message;
        this.updateBar();
        return;
      }
      if (f.type === 'until') {
        toast(f.message.replace(/^s/, 'S') + '.', 'ok', 5000);
        this.message = f.message;
        this.updateBar();
        return;
      }
      if (f.type !== 'frame') return;
      this.cycle = f.cycle; this.head = f.head; this.running = f.running;
      const prefix = this.doc.instPrefix();
      const vals = new Map();
      for (const [k, v] of Object.entries(f.values)) {
        if (prefix) { if (k.startsWith(prefix) && !k.slice(prefix.length).includes('/')) vals.set(k.slice(prefix.length), BigInt('0x' + v)); }
        else if (!k.includes('/')) vals.set(k, BigInt('0x' + v));
      }
      this.canvas.setValues(vals, true);
      if (this.canvas.heatOn) this.fetchActivity();
      this.board.frame(f.values);
      this.pipeview.frame();
      this.inspector.values();
      if (this.explorer) this.explorer.values();
      this.cyclePill.textContent = 'cycle ' + f.cycle.toLocaleString('en');
      const past = f.cycle < f.head;
      this.cyclePill.title = past ? 'Looking at cycle ' + f.cycle + ' of ' + f.head + '; run or step to go back to the latest' : 'The latest cycle';
      this.liveDot.classList.toggle('past', past);
      this.bRun.replaceChildren(icon(f.running ? 'pause' : 'run'), h('span.lb', f.running ? 'Pause' : 'Run'));
      this.bRun.setAttribute('aria-pressed', String(!!f.running));
      if (!past) this.waves.clearCursor && this.waves.cursor != null && f.running && this.waves.clearCursor();
      this.waves.setHead(f.head);
    }

    // wires the router could not place are drawn dashed; the problems list says which
    routeWarn(routes) {
      const failed = Object.keys(routes || {}).filter((id) => routes[id].failed && this.doc.net(id));
      const before = JSON.stringify(this.routeProblems || []);
      this.routeProblems = failed.length ? [{ severity: 'warning', code: 'route', parts: [], nets: failed, fixes: [],
        message: failed.length + ' wire' + (failed.length > 1 ? 's' : '') + ' could not be routed and ' + (failed.length > 1 ? 'are' : 'is') + ' drawn dashed: ' + failed.slice(0, 6).map((id) => this.doc.net(id).name || id).join(', ') + (failed.length > 6 ? '…' : '') + '. Moving parts apart, or Tidy, gives the router room.' }] : [];
      if (JSON.stringify(this.routeProblems) === before) return;
      this.problems.set([...(this.genProblems || []), ...this.routeProblems]);
      this.updateBar();
    }

    boardPorts(ports) {
      const key = JSON.stringify(ports || []);
      if (key === this.boardKey) return;
      this.boardKey = key;
      this.board.render(ports);
    }

    // after looking at a counterexample: start the design again from reset
    restartLive() {
      if (this.viewMode && this.fullView) { this.openDesign(this.fullView.title || this.fullView.name); return; }
      this.sync();
    }

    async sim(cmd) {
      try {
        const r = await S.Api.call('sim', cmd);
        if (cmd.cmd === 'reset' || cmd.cmd === 'restart') this.say(r.message.replace(/^./, (c) => c.toUpperCase()) + '.');
        return r;
      } catch (e) { toast(e.message, 'err'); }
    }

    runPause() { this.sim({ cmd: this.running ? 'pause' : 'run' }); }

    // run until a condition over signals holds: count == 10 && valid
    async runUntil() {
      const last = S.UI.store.get('untilExpr', '');
      const text = await S.UI.prompt('Run until', 'Condition (signals, == != < > && || ! & | ^, 0x… numbers)', last, (v) => v.trim() ? null : 'Write a condition, such as count == 10 && valid.');
      if (!text) return;
      S.UI.store.set('untilExpr', text.trim());
      try { await S.Api.call('sim', { cmd: 'until', expr: text.trim() }); this.say('Running until ' + text.trim() + '…'); }
      catch (e) { toast(e.message, 'err', 6000); }
    }

    back(n) { if (this.cycle > 0) this.goto(this.cycle - n); }

    goto(c) { this.sim({ cmd: 'goto', cycle: c }); }

    toggle(part, bit) {
      const net = this.doc.netOf(part.id, 'y');
      const v = this.canvas.values.get('#' + part.params.name) ?? ((net && this.canvas.values.get(net)) || 0n);
      if (bit != null) { this.sim({ cmd: 'poke', path: part.params.name, value: (v ^ (1n << BigInt(bit))).toString(16) }); return; }
      this.sim({ cmd: 'poke', path: part.params.name, value: v === 1n ? '0' : '1' });
    }

    // a push button on the drawing: its bit is 1 while held
    press(part, bit, down) {
      const net = this.doc.netOf(part.id, 'y');
      const v = this.canvas.values.get('#' + part.params.name) ?? ((net && this.canvas.values.get(net)) || 0n), m = 1n << BigInt(bit);
      this.sim({ cmd: 'poke', path: part.params.name, value: (down ? v | m : v & ~m).toString(16) });
    }

    async pokeValue(part) {
      const w = Number(part.params.width) || 1, max = (1n << BigInt(w)) - 1n;
      const v = await S.UI.prompt('Set ' + part.params.name, 'Value (decimal, 0x… or 0b…)', '0', (s) => {
        try { const x = BigInt(s.trim()); if (x < 0n || x > max) return 'A ' + w + '-bit value is 0 to ' + max + '.'; } catch { return 'Give a number: decimal, 0x… or 0b….'; }
        return null;
      });
      if (v != null) this.sim({ cmd: 'poke', path: part.params.name, value: BigInt(v.trim()).toString(16) });
    }

    probe(net) {
      const path = this.gen && this.gen.netPaths && this.gen.netPaths[net];
      if (!path) { toast('This wire has no signal yet: the design must elaborate first.'); return; }
      if (this.waves.has(path)) { this.waves.remove([path]); toast('Removed ' + path + ' from the waves.'); }
      else { const n = this.doc.net(net); this.waves.add(path, n && n.name ? n.name : path); toast('Added ' + path + ' to the waves.', 'ok'); }
      this.inspector.render();
    }

    applyFix(fix) {
      if (fix.op === 'java') { toast('This fix edits Java code; open the Java tab to see the line (' + fix.label + ').'); return; }
      const said = this.doc.applyFix(fix);
      for (const p of this.doc.d.parts) if (!p.pins || !p.pins.length) p.pins = this.pinsFor(p);
      if (said) toast(said, 'ok');
    }

    setParams(id, params) {
      const p = this.doc.part(id);
      const next = { ...p, params: { ...p.params, ...params } };
      const pins = this.pinsFor(next);
      const gone = (p.pins || []).filter((q) => !pins.some((x) => x.name === q.name) && this.doc.netOf(id, q.name)).map((q) => q.name);
      this.doc.setParams(id, params, pins);
      if (gone.length) toast('Removed the wires on ' + gone.join(', ') + ': those pins no longer exist.');
    }

    // ---------------------------------------------------------------- placing and editing

    placeAt(type, pt) {
      if (this.viewMode) { toast('This drawing shows a design written in Java; change the Java to change it. Start a new design to draw.'); return null; }
      const U = S.Geom.U;
      let p;
      if (type.startsWith('module:')) { this.placeModule(this.modules[Number(type.slice(7))], pt); return null; }
      if (type === 'code:new') { this.placeCode(pt); return null; }
      if (type.startsWith('block:')) {
        const name = type.slice(6);
        if (this.doc.level() === name || this.doc.path.some((x) => x.block === name)) { toast('A block cannot contain itself.', 'err'); return null; }
        p = this.doc.addPart('block', pt.x / U, pt.y / U, { block: name, name: this.doc.freeInst(name) });
      } else p = this.doc.addPart(type, pt.x / U, pt.y / U);
      p.pins = this.pinsFor(p);
      // centre on the point, then step down until it overlaps nothing
      const g = S.Geom.of(p);
      p.x = Math.round(pt.x / U - g.w / 2); p.y = Math.round(pt.y / U - g.h / 2);
      for (let k = 0; k < 40 && this.overlaps(p); k++) p.y += 2;
      this.doc.reindex();
      this.canvas.selectOnly(['p:' + p.id]);
      this.doc.emit('place');
      return p;
    }

    placeAtCentre(type) {
      const r = this.canvas.wrap.getBoundingClientRect();
      return this.placeAt(type, this.canvas.toWorld(r.left + r.width / 2, r.top + r.height / 2));
    }

    overlaps(p) {
      const a = S.Geom.of(p);
      return this.doc.d.parts.some((q) => {
        if (q.id === p.id) return false;
        const b = S.Geom.of(q);
        return a.x < b.x + b.w + 1 && b.x < a.x + a.w + 1 && a.y < b.y + b.h + 1 && b.y < a.y + a.h + 1;
      });
    }

    // a Java module as a part: its pins come from elaborating its expression on the server
    async placeModule(m, pt, partId) {
      if (!m) return;
      const U = S.Geom.U;
      this.say('Elaborating ' + m.expr + '…');
      let r;
      try { r = await S.Api.call('module', { expr: m.expr, imports: m.imports || [] }); } catch (e) { toast(e.message, 'err'); return; }
      if (r.error) { toast(m.title + ': ' + r.error, 'err', 8000); this.say(''); return; }
      const params = { module: r.module || m.title, expr: m.expr, imports: m.imports || [], pins: r.pins, clock: !!r.clock, ...(partId ? {} : { bundle: true }) };
      if (partId) { this.setParams(partId, params); this.say('Updated ' + params.module + ': ' + r.pins.length + ' pins.'); return; }
      const used = new Set(this.doc.d.parts.map((q) => q.params && q.params.name));
      const base = (r.module || m.title).replace(/\W/g, '').replace(/^./, (c) => c.toLowerCase()) || 'u';
      let k = 1; while (used.has(base + k)) k++;
      const p = this.doc.addPart('module', pt.x / U, pt.y / U, { ...params, name: base + k });
      this.canvas.selectOnly(['p:' + p.id]);
      this.say('Placed ' + params.module + ' as ' + base + k + ': ' + r.pins.length + ' pins.');
    }

    // a Java code part: a class written by hand, compiled and probed for its pins
    async placeCode(pt) {
      const used = new Set(this.doc.d.parts.filter((q) => q.type === 'code').map((q) => q.params.cls));
      let k = 1; while (used.has('Code' + k)) k++;
      const cls = 'Code' + k;
      const source = ['public static class ' + cls + ' extends Module {', '    public final UInt a;', '    public final UInt y;', '',
        '    public ' + cls + '() {', '        a = input("a", UInt.w(8));', '        y = output("y", UInt.w(8));', '        y.set(a.addWrap(1));   // any Tessera Java', '    }', '}'].join('\n');
      const r = await S.Api.call('code', { cls, source }).catch((e) => ({ error: e.message }));
      if (r.error) { toast(r.error, 'err', 8000); return; }
      const U = S.Geom.U;
      const p = this.doc.addPart('code', pt.x / U, pt.y / U, { name: cls.toLowerCase(), cls, source, pins: r.pins });
      this.canvas.selectOnly(['p:' + p.id]);
      this.showTab('code');
      this.say('Placed ' + cls + ', a Java code part: edit its class in the code pane, then ⌘Enter.');
    }

    async applyCode(id, source) {
      const p = this.doc.part(id);
      if (!p) return 'The part is gone.';
      const m = /class\s+([A-Z]\w*)\s+extends\s+Module/.exec(source);
      if (!m) return 'The code must declare public static class Name extends Module.';
      const r = await S.Api.call('code', { cls: m[1], source }).catch((e) => ({ error: e.message }));
      if (r.error) return r.error;
      // a default name follows the class, without taking another part's name
      let name = null;
      if (p.params.name === String(p.params.cls || '').toLowerCase() && m[1] !== p.params.cls) {
        const used = new Set(this.doc.d.parts.filter((q) => q.id !== id).map((q) => q.params && q.params.name));
        const base = m[1].charAt(0).toLowerCase() + m[1].slice(1);
        name = base; for (let k = 1; used.has(name); k++) name = base + k;
      }
      this.setParams(id, { cls: m[1], source, pins: r.pins, ...(name ? { name } : {}) });
      this.say('Applied ' + m[1] + ': ' + r.pins.length + ' pins.');
      return null;
    }

    openBlock(part) {
      this.doc.enter(part.params.block, part.params.name);
      this.canvas.sel.clear();
      this.afterLevel();
    }

    leaveBlock() {
      if (!this.doc.leave()) return false;
      this.canvas.sel.clear();
      this.afterLevel();
      return true;
    }

    afterLevel() {
      const lvl = this.doc.level();
      this.canvas.widths = lvl ? ((this.gen && this.gen.blockWidths && this.gen.blockWidths[lvl]) || {}) : ((this.gen && this.gen.netWidths) || {});
      this.canvas.values = new Map();
      this.crumbs();
      this.palette.render();
      this.inspector.render();
      this.canvas.render();
      requestAnimationFrame(() => this.canvas.fit());
      this.sim({ cmd: 'refresh' });
    }

    crumbs() {
      const host = this.crumbBar;
      const path = this.doc.path;
      host.style.display = path.length ? '' : 'none';
      const items = [h('button.crumb', { type: 'button', onclick: () => { while (this.doc.path.length) this.doc.path.pop(); this.doc.reindex(); this.afterLevel(); } }, this.doc.root.name)];
      path.forEach((x, i) => {
        items.push(h('span.crumb-sep', '›'));
        items.push(h('button.crumb' + (i === path.length - 1 ? '.on' : ''), { type: 'button', onclick: () => { this.doc.path.length = i + 1; this.doc.reindex(); this.afterLevel(); } }, x.block, h('span.inst', ' ' + x.inst)));
      });
      items.push(h('span.crumb-note', 'Editing a block: every use of it follows. Values are those of ' + path.map((x) => x.inst).join('.') + '.'));
      host.replaceChildren(...items);
    }

    async makeBlockSel() {
      if (this.viewMode) return;
      const ids = this.canvas.selectedParts();
      if (!ids.length) { toast('Select the parts the block should hold first.'); return; }
      const blocks = this.doc.root.blocks || {};
      let n = 1; while (blocks['Block' + n]) n++;
      const name = await S.UI.prompt('Make a block', 'Name (a Java class name)', 'Block' + n, (v) => !/^[A-Z][A-Za-z0-9_]*$/.test(v.trim()) ? 'A class name: a capital letter, then letters, digits or _.' : blocks[v.trim()] ? 'There is already a block called ' + v.trim() + '.' : null);
      if (!name) return;
      const r = this.doc.makeBlock(ids, name.trim(), this.canvas.widths);
      if (typeof r === 'string') { toast(r, 'err'); return; }
      this.canvas.selectOnly(['p:' + r.id]);
      const pins = r.pins;
      toast('Made the block ' + name.trim() + ' (' + pins.filter((q) => q.dir === 'in').length + ' in, ' + pins.filter((q) => q.dir === 'out').length + ' out). Double-click it to open it.', 'ok');
      this.palette.render();
    }

    tidy() {
      const sel = this.canvas.selectedParts();
      S.Tidy.tidy(this.doc, sel.length > 1 ? sel : null);
      if (sel.length <= 1) requestAnimationFrame(() => this.canvas.fit());
    }

    deleteSel() {
      if (this.explorer) { this.explorer.hide(this.canvas.selectedParts()); return; }
      if (this.viewMode) { toast('This drawing shows a design written in Java; change the Java to change it.'); return; }
      const ps = this.canvas.selectedParts(), ns = this.canvas.selectedNets();
      if (ps.length || ns.length) this.doc.remove(ps, ns);
    }

    async labelNet(net) {
      const n = this.doc.net(net);
      const v = await S.UI.prompt('Name the wire', 'Name (becomes the Java variable; empty removes it)', n && n.name || '', (s) => s && !/^[A-Za-z_]\w*$/.test(s.trim()) ? 'A name is a letter or _, then letters, digits or _.' : null);
      if (v != null) this.doc.nameNet(net, v.trim());
    }

    // a part search at the cursor; when from is a pin, the new part is wired to it
    quickAdd(pt, from) {
      const box = h('div.cmdk', { role: 'dialog', 'aria-label': 'Add a part' });
      const input = h('input', { placeholder: from ? 'Add a part wired to this pin…' : 'Add a part…', 'aria-label': 'Part' });
      const list = h('div.list');
      box.append(input, list);
      document.body.append(box);
      let items = [], k = 0;
      const close = () => box.remove();
      const pick = (t) => {
        close();
        const p = this.placeAt(t.type, pt);
        if (!p) return;
        if (from && from.part) {
          const fromPin = S.Geom.pin(this.doc.part(from.part), from.pin);
          const g = S.Geom.of(p);
          const target = g.pins.find((q) => fromPin && q.dir !== fromPin.dir);
          if (target) this.doc.connect({ part: from.part, pin: from.pin }, { part: p.id, pin: target.name });
        }
      };
      const render = () => {
        const q = input.value.trim().toLowerCase();
        items = S.Parts.all().filter((t) => !q || (t.title + ' ' + t.type + ' ' + t.category).toLowerCase().includes(q)).slice(0, 12);
        k = Math.min(k, Math.max(0, items.length - 1));
        list.replaceChildren(...items.map((t, i) => {
          const b = h('button.it' + (i === k ? '.on' : ''), { type: 'button' }, h('span.kind', t.category), t.title);
          b.addEventListener('click', () => pick(t));
          return b;
        }));
      };
      input.addEventListener('input', () => { k = 0; render(); });
      input.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') { k = Math.min(items.length - 1, k + 1); render(); e.preventDefault(); }
        if (e.key === 'ArrowUp') { k = Math.max(0, k - 1); render(); e.preventDefault(); }
        if (e.key === 'Enter' && items[k]) pick(items[k]);
        if (e.key === 'Escape') close();
      });
      setTimeout(() => addEventListener('mousedown', function off(e) { if (!box.contains(e.target)) { close(); removeEventListener('mousedown', off); } }), 0);
      render();
      input.focus();
    }

    contextMenu(e, part, wire, pt) {
      const K = (id) => this.keys.label(id);
      const items = [];
      if (part && this.viewMode) {
        const ids = this.canvas.selectedParts();
        if (this.explorer) items.push({ label: 'Trace the driver', sc: 'double-click', action: () => this.explorer.traceDriver(part.id) },
          { label: 'Trace the loads', action: () => this.explorer.traceLoads(part.id) },
          { label: 'Remove from the view', sc: 'Del', action: () => this.explorer.hide(ids) }, { sep: true });
        items.push({ label: 'Inspect', action: () => this.showTab('inspect') });
        const net = part.pins.find((q) => q.dir === 'out') && this.doc.netOf(part.id, part.pins.find((q) => q.dir === 'out').name);
        if (net) items.push({ label: 'Add to the waves', sc: K('probe'), action: () => this.probe(net) });
        if (part.type === 'in' && Number(part.params.width) > 1) items.push({ label: 'Set the value…', action: () => this.pokeValue(part) });
        S.UI.menu(e.clientX, e.clientY, items);
        return;
      }
      if (part) {
        items.push({ label: 'Inspect', sc: 'double-click', action: () => this.showTab('inspect') });
        if (part.type === 'in' && Number(part.params.width) > 1) items.push({ label: 'Set the value…', action: () => this.pokeValue(part) });
        items.push({ label: 'Turn a quarter', sc: K('rotate'), action: () => this.doc.rotate(this.canvas.selectedParts(), 90) },
          { label: 'Mirror', sc: K('mirror'), action: () => this.doc.mirror(this.canvas.selectedParts()) },
          { label: 'Duplicate', sc: K('duplicate'), action: () => this.duplicate() },
          { label: 'Make a block of the selection', sc: K('block'), action: () => this.makeBlockSel() },
          ...(part.type === 'block' ? [{ label: 'Open the block', sc: 'double-click', action: () => this.openBlock(part) }] : []),
          { label: 'Show its Java', action: () => this.showTab('code') },
          { sep: true }, { label: 'Delete', sc: 'Del', action: () => this.deleteSel() });
      } else if (wire) {
        const path = this.gen && this.gen.netPaths && this.gen.netPaths[wire.net];
        items.push({ label: 'Name the wire…', sc: K('label'), action: () => this.labelNet(wire.net) },
          { label: this.waves.has(path) ? 'Remove from the waves' : 'Add to the waves', sc: K('probe'), action: () => this.probe(wire.net) },
          { sep: true }, { label: 'Delete the wire', sc: 'Del', action: () => this.doc.remove([], [wire.net]) });
      } else {
        items.push({ label: 'Add a part here…', sc: K('quickAdd'), action: () => this.quickAdd(pt) },
          { label: 'Paste', sc: K('paste'), action: () => this.paste(pt), disabled: !store.get('clip', null) },
          { label: 'Select all', sc: K('selectAll'), action: () => this.selectAll() },
          { label: 'Fit the drawing', sc: K('fit'), action: () => this.canvas.fit() },
          { label: 'Tidy up', action: () => this.tidy() });
      }
      S.UI.menu(e.clientX, e.clientY, items);
    }

    hover(x) {
      let t = '';
      if (x && x.kind === 'pin') {
        const w = x.net && this.canvas.widths[x.net];
        t = x.part + '.' + x.pin + (w ? (w === 1 ? ' · Bool' : ' · UInt<' + w + '>') : x.net ? '' : ' · open');
        if (x.net && this.canvas.values.has(x.net)) t += ' = ' + this.canvas.fmt(this.canvas.values.get(x.net), w || 1);
      } else if (x && x.kind === 'net') {
        const n = this.doc.net(x.net), w = this.canvas.widths[x.net];
        t = (n && n.name || x.net) + (w ? ' · ' + w + ' bit' + (w > 1 ? 's' : '') : '');
        if (this.canvas.values.has(x.net)) t += ' = ' + this.canvas.fmt(this.canvas.values.get(x.net), w || 1);
      }
      if (t !== this.hoverText) { this.hoverText = t; this.updateBar(); }
    }

    // ---------------------------------------------------------------- clipboard

    copy() {
      const ids = this.canvas.selectedParts();
      if (!ids.length) return;
      store.set('clip', this.doc.copy(ids));
      toast('Copied ' + ids.length + ' part' + (ids.length > 1 ? 's' : '') + '.');
    }

    paste(at) {
      const clip = store.get('clip', null);
      if (!clip) return;
      let dx = 2, dy = 2;
      if (at && clip.parts.length) {
        const x0 = Math.min(...clip.parts.map((p) => p.x)), y0 = Math.min(...clip.parts.map((p) => p.y));
        dx = Math.round(at.x / S.Geom.U) - x0; dy = Math.round(at.y / S.Geom.U) - y0;
      }
      const ids = this.doc.paste(clip, dx, dy);
      this.canvas.selectOnly(ids.map((i) => 'p:' + i));
    }

    duplicate() {
      const ids = this.canvas.selectedParts();
      if (!ids.length) return;
      const nids = this.doc.paste(this.doc.copy(ids), 3, 3);
      this.canvas.selectOnly(nids.map((i) => 'p:' + i));
    }

    selectAll() {
      this.canvas.selectOnly([...this.doc.d.parts.map((p) => 'p:' + p.id), ...this.doc.d.nets.map((n) => 'w:' + n.id)]);
    }

    // ---------------------------------------------------------------- files

    async newDoc() {
      if (this.doc.d.parts.length && !(await S.UI.modal('Start a new design?', h('p', 'The current drawing is saved in this browser until you start a new one; save it as .java to keep it.'), { ok: 'New design' }))) return;
      this.replace(S.Doc.blank('Design'));
    }

    replace(d) {
      this.leaveView();
      this.doc.d = this.fill(d);
      this.doc.undoStack = []; this.doc.redoStack = [];
      this.doc.reindex();
      this.waves.rows = []; this.waves.saved(); this.wavesSeeded = false; this.waves.data = {}; this.waves.renderNames(); this.waves.draw();
      this.canvas.sel.clear();
      this.doc.emit('open');
      requestAnimationFrame(() => this.canvas.fit());
    }

    async save() {
      const r = await S.Api.call('codegen', { doc: this.doc.json() });
      S.UI.download(r.className + '.java', r.java, 'text/x-java');
      toast('Saved ' + r.className + '.java: Tessera code with the drawing inside.', 'ok');
    }

    open() {
      const inp = h('input', { type: 'file', accept: '.java,.json' });
      inp.addEventListener('change', async () => {
        const f = inp.files[0];
        if (!f) return;
        const text = await f.text();
        try {
          const r = f.name.endsWith('.json') ? { doc: JSON.parse(text), report: [] } : await S.Api.call('open', { java: text });
          this.replace(this.placeNew(r.doc));
          toast('Opened ' + f.name + '.' + (r.report.length ? ' ' + r.report.length + ' run' + (r.report.length > 1 ? 's' : '') + ' of Java became code parts; see the console.' : ''), 'ok', 6000);
          for (const line of r.report) this.console && this.console.log(line, 'info');
        } catch (e) { toast(e.message, 'err'); }
      });
      inp.click();
    }

    // parts read from Java that have no place yet: all of them laid out afresh, or the new ones below the rest
    placeNew(d) {
      for (const b of Object.values(d.blocks || {})) this.placeNew(b);
      this.fill(d);
      const fresh = d.parts.filter((p) => p.x == null || p.y == null);
      if (!fresh.length) return d;
      const pos = S.Tidy.layout(d.parts, d.nets);
      const kept = d.parts.filter((p) => p.x != null && p.y != null);
      const y0 = kept.length ? Math.max(...kept.map((p) => p.y + S.Geom.of(p).h)) + 4 : 0;
      const qy = Math.min(...fresh.map((p) => pos.get(p.id).y));
      for (const p of fresh) { const q = pos.get(p.id); p.x = q.x; p.y = kept.length ? y0 + q.y - qy : q.y; p.rot = 0; p.mir = false; }
      return d;
    }

    // the Java pane's edit: read back into parts (one undo step), keeping the places of the parts still there
    async applyJava(java) {
      let r;
      try { r = await S.Api.call('read', { java, doc: this.doc.json() }); } catch (e) { return e.message; }
      const d = this.placeNew(r.doc);
      this.doc.edit('Java edit', () => {
        this.doc.d.parts = d.parts;
        this.doc.d.nets = d.nets;
        if (d.blocks) this.doc.d.blocks = d.blocks;
        if (d.title != null) this.doc.d.title = d.title;
        this.doc.d.name = d.name;
        this.doc.d.next = Math.max(this.doc.d.next || 0, d.next || 0);
      });
      for (const line of r.report) this.console && this.console.log(line, 'info');
      toast('The drawing follows the Java.' + (r.report.length ? ' ' + r.report.length + ' run' + (r.report.length > 1 ? 's' : '') + ' outside the dialect became code parts (see the console).' : ''), 'ok', 6000);
      return null;
    }

    exportMenu(anchor) {
      const K = (id) => this.keys.label(id);
      const r = anchor.getBoundingClientRect();
      S.UI.menu(r.left, r.bottom + 4, [
        { label: 'Picture of the drawing (.svg)', action: () => { const s = this.canvas.exportSvg(); if (s) S.UI.download(this.doc.d.name + '.svg', s, 'image/svg+xml'); } },
        { label: 'Waves of the shown signals (.vcd)', action: () => this.waves.exportVcd() },
        { label: 'Tessera Java (.java)', sc: K('save'), action: () => this.save() },
        { label: 'The drawing as JSON (.json)', action: () => S.UI.download(this.doc.d.name + '.json', JSON.stringify(this.doc.json(), null, 1), 'application/json') },
      ]);
    }

    theme() {
      const t = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = t;
      store.set('theme', t);
      this.canvas.render();
      this.waves.draw();
    }

    // ---------------------------------------------------------------- keys

    bindKeys() {
      addEventListener('keydown', (e) => {
        const tag = (e.target.tagName || '').toLowerCase();
        if (tag === 'input' || tag === 'textarea' || tag === 'select' || document.querySelector('.modal-back, .cmdk')) return;
        const k = e.key;
        const sel = this.canvas.selectedParts();
        const run = (fn) => { e.preventDefault(); fn(); };
        // what depends on the selection first: delete, and wiring from the keyboard
        if (k === 'Delete' || (k === 'Backspace' && this.canvas.sel.size)) return run(() => this.deleteSel());
        if (k === 'Tab' && (this.kpin || sel.length === 1) && !this.viewMode) return run(() => this.keyPinCycle(sel[0], e.shiftKey ? -1 : 1));
        if (this.kpin && k.startsWith('Arrow')) return run(() => this.keyPinMove(k));
        if (this.kpin && k === 'Enter') return run(() => this.keyPinEnter());
        if (k === 'Escape' && (this.kpin || this.kwire)) return run(() => this.keyPinCancel());
        if (k === 'Escape') return run(() => { if (this.canvas.sel.size) this.canvas.selectOnly([]); else this.leaveBlock(); });
        // the shortcuts, as the user has them
        if (this.keys.handle(e)) return;
        if (e.metaKey || e.ctrlKey) return;
        if (k.startsWith('Arrow')) {
          const dx = k === 'ArrowLeft' ? -1 : k === 'ArrowRight' ? 1 : 0, dy = k === 'ArrowUp' ? -1 : k === 'ArrowDown' ? 1 : 0, step = e.shiftKey ? 10 : 1;
          if (sel.length) return run(() => this.doc.movePartsBy(sel, dx * step, dy * step));
          return run(() => { this.canvas.view.x += dx * 40 / this.canvas.view.z; this.canvas.view.y += dy * 40 / this.canvas.view.z; this.canvas.applyView(); });
        }
      });
    }

    // ---------------------------------------------------------------- keyboard wiring

    pinWords(pp) {
      const p = this.doc.part(pp.part), q = p && S.Geom.pin(p, pp.pin), t = p && S.Parts.get(p.type);
      const net = this.doc.netOf(pp.part, pp.pin), w = net ? this.canvas.widths[net] : null;
      return (t ? t.title : p.type) + ' ' + (p.params.name || p.id) + ', pin ' + pp.pin + ' (' + (q && q.dir === 'out' ? 'output' : 'input') + (w ? ', ' + w + ' bit' + (w > 1 ? 's' : '') : '') + (net ? ', wired' : ', open') + ')';
    }

    say(text) { this.message = text; this.sr.textContent = text; this.updateBar(); }

    showKeyPin() {
      const kp = this.kpin && this.doc.part(this.kpin.part) && S.Geom.pin(this.doc.part(this.kpin.part), this.kpin.pin);
      this.canvas.hot = kp ? { part: this.kpin.part, pin: this.kpin.pin, ax: kp.ax, ay: kp.ay } : null;
      const src = this.kwire && this.doc.part(this.kwire.part) && S.Geom.pin(this.doc.part(this.kwire.part), this.kwire.pin);
      const U = S.Geom.U;
      this.canvas.ghost = src && kp ? `<path class="ghost kb" d="M${src.ax * U} ${src.ay * U}L${kp.ax * U} ${kp.ay * U}"/><circle class="pinhot" cx="${src.ax * U}" cy="${src.ay * U}" r="4.5"/>` : null;
      this.canvas.drawUi();
    }

    keyPinCycle(partId, dir) {
      const pid = this.kpin ? this.kpin.part : partId;
      const p = this.doc.part(pid);
      if (!p) return;
      const pins = S.Geom.of(p).pins;
      if (!pins.length) return;
      let i = this.kpin && this.kpin.part === pid ? pins.findIndex((q) => q.name === this.kpin.pin) : -1;
      i = (i + dir + pins.length) % pins.length;
      this.kpin = { part: pid, pin: pins[i].name };
      this.showKeyPin();
      this.say(this.pinWords(this.kpin) + (this.kwire ? '. Enter connects it.' : '. Enter starts a wire; arrows go to the nearest pin that way.'));
    }

    keyPinMove(key) {
      const p0 = this.doc.part(this.kpin.part), a = S.Geom.pin(p0, this.kpin.pin);
      const [dx, dy] = { ArrowRight: [1, 0], ArrowLeft: [-1, 0], ArrowDown: [0, 1], ArrowUp: [0, -1] }[key];
      let best = null, bc = Infinity;
      for (const p of this.doc.d.parts) for (const q of S.Geom.of(p).pins) {
        const vx = q.ax - a.ax, vy = q.ay - a.ay, along = vx * dx + vy * dy, across = Math.abs(vx * dy - vy * dx);
        if (along <= 0) continue;                     // only pins that way
        const c = along + 2 * across;                 // straight ahead first
        if (c < bc) { bc = c; best = { part: p.id, pin: q.name }; }
      }
      if (!best) { this.say('No pin further that way.'); return; }
      this.kpin = best;
      this.canvas.selectOnly(['p:' + best.part]);
      this.showKeyPin();
      this.say(this.pinWords(best) + (this.kwire ? '. Enter connects it.' : ''));
    }

    keyPinEnter() {
      if (!this.kwire) {
        this.kwire = { ...this.kpin };
        this.showKeyPin();
        this.say('Wiring from ' + this.pinWords(this.kwire) + '. Move to the other pin with the arrows or Tab, then press Enter; Escape cancels.');
        return;
      }
      if (this.kwire.part === this.kpin.part && this.kwire.pin === this.kpin.pin) { this.say('That is the pin the wire starts from; move to another one.'); return; }
      const id = this.doc.connect({ part: this.kwire.part, pin: this.kwire.pin }, { part: this.kpin.part, pin: this.kpin.pin });
      const from = this.pinWords(this.kwire);
      this.kwire = null;
      this.showKeyPin();
      if (id) { this.canvas.selectOnly(['w:' + id]); this.say('Connected ' + from + ' to ' + this.pinWords(this.kpin) + '.'); }
      else this.say('Those pins could not be connected.');
    }

    keyPinCancel() {
      if (this.kwire) { this.kwire = null; this.showKeyPin(); this.say('Wiring cancelled.'); return; }
      this.kpin = null;
      this.showKeyPin();
      this.say('');
    }

    // the guide in a drawer beside the work (or a section of it)
    guide(id) {
      if (!this.guideBox) {
        this.guideFrame = h('iframe.guide-frame', { title: 'Tessera Studio Guide', src: '/guide?theme=' + document.documentElement.dataset.theme });
        const close = S.UI.button('', { icon: 'x', title: 'Close the guide (Esc)', onclick: () => this.guideBox.classList.remove('open') });
        const pop = S.UI.button('', { icon: 'export', title: 'Open in its own tab', onclick: () => window.open('/guide' + (this.guideAt ? '#' + this.guideAt : ''), '_blank') });
        this.guideBox = h('aside.guide-drawer', { 'aria-label': 'Guide' }, h('div.guide-bar', h('b', 'Guide'), h('span.grow'), pop, close), this.guideFrame);
        document.body.append(this.guideBox);
        this.guideBox.addEventListener('keydown', (e) => { if (e.key === 'Escape') this.guideBox.classList.remove('open'); });
      }
      this.guideAt = id || null;
      if (id) this.guideFrame.src = '/guide?theme=' + document.documentElement.dataset.theme + '#' + id;
      this.guideBox.classList.add('open');
    }

    keysSheet() { this.keys.sheet(); }

    // how often each drawn net changed lately, for the heat colouring (at most three times a second)
    async fetchActivity() {
      const now = performance.now();
      if (this.activityBusy || now - (this.activityAt || 0) < 330 || !this.gen || !this.gen.netPaths) return;
      this.activityBusy = true; this.activityAt = now;
      try {
        const byPath = new Map();
        for (const [net, path] of Object.entries(this.gen.netPaths)) if (!byPath.has(path)) byPath.set(path, []), byPath.get(path).push(net); else byPath.get(path).push(net);
        const r = await S.Api.call('activity', { paths: [...byPath.keys()], span: 256 });
        const counts = new Map();
        for (const [path, c] of Object.entries(r.counts)) for (const net of byPath.get(path) || []) counts.set(net, c);
        this.canvas.setActivity(counts, r.span);
      } catch (e) { /* the next frame tries again */ }
      finally { this.activityBusy = false; }
    }

    setView(change) {
      Object.assign(this.view, change);
      store.set('view', this.view);
      Object.assign(S.Parts.prefs, { overbars: this.view.overbars, symbols: this.view.symbols });
      this.canvas.heatOn = !!this.view.heat;
      if (this.canvas.heatOn) this.fetchActivity(); else this.canvas.setActivity(new Map(), 1);
      if (change.symbols !== undefined) this.palette.render();
      this.canvas.render();
      this.canvas.drawWires && this.canvas.drawWires();
    }

    // a design with several clocks: a cycle is a tick, and each clock has an edge every period ticks
    setClocks(clocks) {
      this.clocks = clocks;
      const multi = clocks.length > 1 || clocks.some((c) => c.period !== 1 || c.phase !== 0);
      this.bEdge.hidden = !multi;
      if (multi) this.bEdge.title = 'To the next edge of a clock: ' + clocks.map((c) => c.name + ' every ' + c.period + ' tick' + (c.period > 1 ? 's' : '')).join(', ');
    }

    edgeMenu(anchor) {
      const r = anchor.getBoundingClientRect();
      S.UI.menu(r.left, r.bottom + 4, (this.clocks || []).map((c) => ({
        label: 'Next edge of ' + c.name + ' (every ' + c.period + ' tick' + (c.period > 1 ? 's' : '') + (c.phase ? ', from tick ' + c.phase : '') + ')',
        action: () => this.sim({ cmd: 'step', edge: c.port }),
      })));
    }

    // a button whose tooltip shows the action's keys as they are now set
    keyed(b, name, id) {
      b.dataset.keyId = id;
      b.dataset.keyName = name;
      this.keyTitle(b);
      return b;
    }

    keyTitle(b) {
      const k = this.keys.label(b.dataset.keyId), t = b.dataset.keyName + (k ? ' (' + k + ')' : '');
      b.title = t;
      b.setAttribute('aria-label', t);
    }

    refreshKeyTitles() { for (const b of document.querySelectorAll('[data-key-id]')) this.keyTitle(b); }

    commands() {
      const K = (id) => this.keys.label(id);
      const all = [
        ['Run or pause', K('runPause'), () => this.runPause()], ['One cycle', K('step'), () => this.sim({ cmd: 'step', n: 1 })], ['Reset', K('reset'), () => this.sim({ cmd: 'reset' })],
        ['Restart from cycle 0 (a new run; the waves start again)', K('restart'), () => this.sim({ cmd: 'restart' })],
        ['Fit the drawing', K('fit'), () => this.canvas.fit()], ['New design', '', () => this.newDoc()], ['Open…', K('open'), () => this.open()], ['Save as .java', K('save'), () => this.save()],
        ['Export the drawing (.svg)', '', () => { const s = this.canvas.exportSvg(); if (s) S.UI.download(this.doc.d.name + '.svg', s, 'image/svg+xml'); }],
        ['Tidy up the drawing', '', () => this.tidy()],
        ...((this.clocks || []).length > 1 ? this.clocks.map((c) => ['Step to the next edge of ' + c.name, '', () => this.sim({ cmd: 'step', edge: c.port })]) : []),
        ['Draw every signal (dense)', '', () => this.explorer && this.explorer.all()],
        ['Light or dark', K('theme'), () => this.theme()], ['Shortcuts (change them here)', K('keys'), () => this.keysSheet()], ['Show problems', '', () => this.showTab('problems')], ['Show the Java', '', () => this.showTab('code')],
        ['Open the sample: counter', '', () => this.replace(SAMPLES.counter())], ['Open the sample: blinky', '', () => this.replace(SAMPLES.blinky())],
        ...(this.designList || []).map((n) => ['Open design: ' + n, '', () => this.openDesign(n)]),
        ...S.Parts.all().map((t) => ['Add part: ' + t.title, '', () => this.placeAtCentre(t.type)]),
        ...((this.waves.signals && Object.keys(this.waves.signals)) || []).map((p) => ['Add to waves: ' + p, '', () => this.waves.add(p)]),
        ['The guide', K('guide'), () => this.guide()],
        ...(this.guideSections || []).map((g) => ['Guide: ' + g.title, '', () => this.guide(g.id)]),
      ];
      const box = h('div.cmdk', { role: 'dialog', 'aria-label': 'Commands' });
      const input = h('input', { placeholder: 'Type a command, a part or a signal…' });
      const list = h('div.list');
      box.append(input, list);
      document.body.append(box);
      let items = [], k = 0;
      const close = () => box.remove();
      const render = () => {
        const q = input.value.trim().toLowerCase();
        items = all.filter(([t]) => !q || q.split(/\s+/).every((w) => t.toLowerCase().includes(w))).slice(0, 40);
        k = Math.min(k, Math.max(0, items.length - 1));
        list.replaceChildren(...items.map(([t, sc, fn], i) => {
          const b = h('button.it' + (i === k ? '.on' : ''), { type: 'button' }, t, sc ? h('span.sc', h('kbd', sc)) : null);
          b.addEventListener('click', () => { close(); fn(); });
          return b;
        }));
      };
      input.addEventListener('input', () => { k = 0; render(); });
      input.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') { k = Math.min(items.length - 1, k + 1); render(); e.preventDefault(); }
        if (e.key === 'ArrowUp') { k = Math.max(0, k - 1); render(); e.preventDefault(); }
        if (e.key === 'Enter' && items[k]) { close(); items[k][2](); }
        if (e.key === 'Escape') close();
      });
      setTimeout(() => addEventListener('mousedown', function off(e) { if (!box.contains(e.target)) { close(); removeEventListener('mousedown', off); } }), 0);
      render();
      input.focus();
    }
  }

  App.SAMPLES = SAMPLES;   // for tests and the audit
  // a module's pins; with bundle set, X_valid, X_ready and X_bits are one stream pin X (as CodeGen.streams does)
  App.streams = (params) => {
    const out = new Map();
    if (!params || !(params.bundle === true || params.bundle === 'true')) return out;
    const dir = new Map((params.pins || []).map((q) => [q.name, q.dir])), unb = new Set(params.unbundle || []);
    for (const [n, v] of dir) {
      if (!n.endsWith('_valid')) continue;
      const x = n.slice(0, -6), r = dir.get(x + '_ready'), b = dir.get(x + '_bits');
      if (r && b && !unb.has(x) && r !== v && b === v) out.set(x, v);
    }
    return out;
  };
  App.modulePins = (pins, params) => {
    const st = App.streams(params || { pins }), out = [];
    for (const q of pins || []) {
      const base = q.name.replace(/_(valid|ready|bits)$/, '');
      if (base !== q.name && st.has(base)) {
        if (q.name.endsWith('_valid')) out.push({ name: base, dir: st.get(base), side: st.get(base) === 'in' ? 'l' : 'r', label: base + ' ⟫', stream: true });
        continue;
      }
      out.push({ name: q.name, dir: q.dir, side: q.dir === 'in' ? 'l' : 'r', label: q.name });
    }
    return out;
  };
  S.App = App;
  addEventListener('DOMContentLoaded', () => { S.app = new App(); S.app.start().catch((e) => { document.body.textContent = 'Tessera Studio could not start: ' + e.message; console.error(e); }); });
})(globalThis.Studio = globalThis.Studio || {});
