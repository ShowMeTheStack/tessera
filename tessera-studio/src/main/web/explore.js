// Tessera Studio: exploring a large design written in Java. The full drawing of its signals is kept
// aside; the canvas shows only the part being looked at. Picking a signal draws it with the logic
// that drives it and the signals that logic reads; from there you trace drivers (what feeds a part)
// and loads (what a part feeds) one step at a time, as in a waveform debugger's schematic.
(function (S) {
  'use strict';
  const { h, fill } = S.UI;

  class Explorer {
    constructor(app, full) {
      this.app = app;
      this.full = full;
      this.byId = new Map(full.parts.map((p) => [p.id, p]));
      this.shown = new Set();
      // which net each pin is on, and each net's driver
      this.netOfPin = new Map();
      for (const n of full.nets) for (const [p, q] of n.pins) this.netOfPin.set(p + '.' + q, n);
      this.driverOf = new Map();
      for (const n of full.nets) for (const [p, q] of n.pins) { const pin = this.pinSpec(p, q); if (pin && pin.dir === 'out') this.driverOf.set(n.id, p); }
      // signal parts by name, for the list: ports, registers, and named logic
      this.signals = full.parts.filter((p) => p.type !== 'mem' && p.type !== 'inst' && (p.type !== 'expr' || p.params.signal && !p.params.signal.endsWith(' next')))
        .map((p) => ({ id: p.id, name: p.params.name || p.params.signal, kind: p.type === 'expr' ? 'wire' : p.type === 'in' ? 'input' : p.type === 'out' ? 'output' : 'register', width: p.params.width || 1 }))
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
    }

    pinSpec(part, pin) { const p = this.byId.get(part); return p && p.pins.find((x) => x.name === pin); }

    // the parts that feed part id: the drivers of its input pins (through one logic box, if any)
    drivers(id) {
      const p = this.byId.get(id), out = [];
      for (const pin of p.pins.filter((x) => x.dir === 'in')) {
        const n = this.netOfPin.get(id + '.' + pin.name);
        const d = n && this.driverOf.get(n.id);
        if (d && !out.includes(d)) out.push(d);
      }
      return out;
    }

    loads(id) {
      const p = this.byId.get(id), out = [];
      for (const pin of p.pins.filter((x) => x.dir === 'out')) {
        const n = this.netOfPin.get(id + '.' + pin.name);
        if (!n) continue;
        for (const [q] of n.pins) if (q !== id && !out.includes(q)) out.push(q);
      }
      return out;
    }

    // show a part with the logic that drives it and that logic's inputs
    show(id) {
      this.shown.add(id);
      for (const d of this.drivers(id)) {
        this.shown.add(d);
        if (this.byId.get(d).type === 'expr') for (const dd of this.drivers(d)) this.shown.add(dd);
      }
      this.redraw(id);
    }

    traceDriver(id) { this.show(id); }

    // draw the signals called names (a timing path's), each with what drives it; how many were found
    showNamed(names) {
      const want = new Set(names);
      const found = this.signals.filter((s) => want.has(s.name));
      for (const s of found) {
        this.shown.add(s.id);
        for (const d of this.drivers(s.id)) this.shown.add(d);
      }
      if (found.length) this.redraw(found[found.length - 1].id);
      return found.length;
    }

    traceLoads(id) {
      this.shown.add(id);
      for (const l of this.loads(id)) {
        this.shown.add(l);
        if (this.byId.get(l).type === 'expr') for (const ll of this.loads(l)) this.shown.add(ll);
      }
      this.redraw(id);
    }

    hide(ids) { for (const id of ids) this.shown.delete(id); this.redraw(); }

    all() { for (const p of this.full.parts) this.shown.add(p.id); this.redraw(); }

    clear() { this.shown.clear(); this.redraw(); }

    // the drawing of what is shown: those parts, and each net restricted to their pins
    redraw(focus) {
      const keep = new Map();
      for (const p of this.app.doc.d.parts) keep.set(p.id, [p.x, p.y]);
      const parts = this.full.parts.filter((p) => this.shown.has(p.id)).map((p) => ({ ...p }));
      // a net is drawn only from a driver that is shown; inputs whose source is not shown keep its name as their label
      const nets = this.full.nets.filter((n) => this.shown.has(this.driverOf.get(n.id)))
        .map((n) => ({ ...n, pins: n.pins.filter(([p]) => this.shown.has(p)) })).filter((n) => n.pins.length > 1);
      const pos = S.Tidy.layout(parts, nets);
      for (const p of parts) { const q = pos.get(p.id); p.x = q.x; p.y = q.y; }
      const d = this.app.doc.d;
      d.parts = parts; d.nets = nets;
      this.app.doc.reindex();
      this.app.doc.emit('explore');
      if (focus) this.app.canvas.selectOnly(['p:' + focus]);
      requestAnimationFrame(() => this.app.canvas.fit());
      this.renderList();
    }

    // the Signals panel
    panel(host) {
      this.q = h('input.input', { type: 'search', placeholder: 'Find a signal  ( ⌘F )', 'aria-label': 'Find a signal' });
      this.list = h('div.sigs', { role: 'list' });
      this.q.addEventListener('input', S.UI.debounce(() => this.renderList(), 60));
      this.q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { const first = this.matches()[0]; if (first) this.show(first.id); } });
      this.root = h('div.pal', h('div.search', this.q), h('div.sig-actions',
        S.UI.button('Everything', { title: 'Draw every signal at once (dense for large designs)', onclick: () => this.all() }),
        S.UI.button('Clear', { title: 'Empty the canvas', onclick: () => this.clear() })), this.list);
      host.replaceChildren(this.root);
      this.renderList();
    }

    matches() {
      const q = (this.q && this.q.value.trim().toLowerCase()) || '';
      return this.signals.filter((s) => !q || s.name.toLowerCase().includes(q));
    }

    renderList() {
      if (!this.list) return;
      const vals = this.app.canvas.values;
      const ms = this.matches().slice(0, 400);
      fill(this.list, ms.map((s) => {
        const net = 's:' + s.name, v = vals.get(net);
        const row = h('div.sig' + (this.shown.has(s.id) ? '.on' : ''), { role: 'listitem', tabindex: '0', title: s.kind + ', ' + s.width + ' bit' + (s.width > 1 ? 's' : '') + ': click to draw it and what drives it' },
          h('span.k.' + s.kind, s.kind[0].toUpperCase()), h('span.n', s.name), h('span.v', { 'data-net': net, 'data-w': s.width }, v === undefined ? '' : this.app.canvas.fmt(v, s.width)));
        row.addEventListener('click', () => this.show(s.id));
        row.addEventListener('keydown', (e) => { if (e.key === 'Enter') this.show(s.id); });
        return row;
      }), this.signals.length > 400 && ms.length === 400 ? h('div.help', 'Showing 400 of ' + this.signals.length + '; type to narrow.') : null);
    }

    values() {
      if (!this.list || !this.list.offsetParent) return;
      for (const el of this.list.querySelectorAll('.v[data-net]')) {
        const v = this.app.canvas.values.get(el.getAttribute('data-net'));
        if (v !== undefined) el.textContent = this.app.canvas.fmt(v, Number(el.getAttribute('data-w')) || 1);
      }
    }
  }

  S.Explorer = Explorer;
})(globalThis.Studio = globalThis.Studio || {});
