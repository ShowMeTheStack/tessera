// Tessera Studio: the waveform dock. Signals are listed on the left: drag to reorder, click to select
// (shift or cmd for several), G to gather the selection into a named group that folds, X to expand a
// bus into its bits, a radix per row. The waves are drawn on a Canvas 2D at device resolution from
// windows of the server's record: exact changes when there are few, one summary per pixel column
// when there are many, so a long run draws as fast as a short one.
//
// Cursor A (click) moves the whole studio to that cycle; B (shift-click) measures against it. ← and →
// jump to the previous and next change of the selected signal; M drops a named marker at A. Alt-drag
// zooms to a range. Reloads, forks, counterexamples and failed assertions are marked through all rows
// and listed in the notes strip, each a sentence; clicking one goes there.
(function (S) {
  'use strict';
  const { h, fill } = S.UI;
  const RH = 24, BUSH = 28, AXIS = 26;
  const RADIX = ['hex', 'dec', 'bin', 'signed'];

  class Waves {
    constructor(host, app) {
      this.app = app;
      this.rows = S.UI.store.get('waveRows', []);      // {path, label, radix, width, group?, bit?}
      this.groups = S.UI.store.get('waveGroups', {});  // name -> {open}
      this.markers = S.UI.store.get('waveMarkers', []); // {cycle, name}
      this.sel = new Set();                            // row keys
      this.t0 = 0; this.t1 = 100; this.follow = true;
      this.head = 0; this.cursor = null; this.cursorB = null;
      this.data = {};
      this.marks = [];
      this.hz = app.hz || 0;
      // signals are picked from a searchable list, grouped by where they are in the hierarchy
      const add = S.UI.button('Add signals', { icon: 'probe', title: 'Add signals to the waves (search by name)', onclick: () => this.picker() });
      add.classList.add('wv-add');
      this.addSel = add;
      this.info = h('span.wv-info', { 'aria-live': 'polite' });
      const btn = (ic, title, fn) => S.UI.button('', { icon: ic, title, onclick: fn });
      const tb = h('div.wv-tools', add,
        btn('zoomIn', 'Zoom in on time (ctrl and wheel)', () => this.zoom(0.5)),
        btn('zoomOut', 'Zoom out', () => this.zoom(2)),
        btn('fit', 'The whole run', () => { this.follow = true; this.fitAll(); }),
        h('span.sep'),
        btn('layers', 'Group the selected signals (G)', () => this.groupSel()),
        btn('marker', 'Marker at cursor A (M)', () => this.addMarker()),
        btn('search', 'Find when a condition holds (⌘F; F3 again)', () => this.search(1)),
        btn('chart', 'Measure the selected signal between cursors A and B', () => this.measure()),
        btn('export', 'Export: VCD, PNG of the window, CSV between the cursors', (e) => this.exportMenu(e.currentTarget)),
        this.info);
      this.names = h('div.wv-names', { role: 'listbox', 'aria-label': 'Signals', 'aria-multiselectable': 'true', tabindex: '0' });
      this.cv = h('canvas.wv-cv');
      this.tip = h('div.wv-tip', { role: 'tooltip' });
      this.plot = h('div.wv-plot', { tabindex: '0', 'aria-label': 'Waves: click to go to a cycle, shift-click for cursor B, arrows for the previous or next change' }, this.cv, this.tip);
      this.body = h('div.wv-body', this.names, this.plot);
      this.notes = h('div.wv-notes', { 'aria-live': 'polite' });
      this.root = h('div.wv', tb, this.body, this.notes);
      host.append(this.root);
      new ResizeObserver(() => this.draw()).observe(this.plot);
      this.bind();
      this.renderNames();
    }

    key(r) { return r.path + (r.bit != null ? '#' + r.bit : ''); }
    has(path) { return !!path && this.rows.some((r) => r.path === path && r.bit == null); }

    setSignals(list) {
      this.signals = list || {};
      const shown = new Set(this.rows.map((r) => r.path));
      const opts = Object.keys(this.signals).filter((p) => !shown.has(p)).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
      this.addSel.title = opts.length ? 'Add signals to the waves: ' + opts.length + ' more can be shown' : 'Every signal is shown';
      for (const r of this.rows) if (this.signals[r.path] && r.bit == null) r.width = this.signals[r.path];
    }

    // the valid/ready streams among the signals: X_valid, X_ready and X_bits together
    streams() {
      const sig = this.signals || {}, out = [];
      for (const p of Object.keys(sig)) if (p.endsWith('_valid')) { const b = p.slice(0, -6); if (sig[b + '_ready'] !== undefined && sig[b + '_bits'] !== undefined) out.push(b); }
      return out.sort();
    }

    // a stream as one row of transactions: a box per transfer (valid and ready) with its bits, stalls marked
    addStream(base) {
      const path = 'stream:' + base;
      if (this.has(path)) return;
      this.rows.push({ path, stream: base, label: base + ' ⇄', radix: S.UI.store.get('radix', 'hex'), width: (this.signals && this.signals[base + '_bits']) || 8 });
      this.saved();
      S.Api.call('sim', { cmd: 'record', paths: [base + '_valid', base + '_ready', base + '_bits'] }).catch(() => {});
      this.renderNames();
      this.fetch();
    }

    add(path, label) {
      if (this.has(path)) return;
      this.rows.push({ path, label: label || path, radix: S.UI.store.get('radix', 'hex'), width: (this.signals && this.signals[path]) || 1 });
      this.saved();
      S.Api.call('sim', { cmd: 'record', paths: [path] }).catch(() => {});
      this.setSignals(this.signals);
      this.renderNames();
      this.fetch();
    }

    remove(keys) {
      const ks = new Set(keys);
      this.rows = this.rows.filter((r) => !ks.has(this.key(r)) && !(r.bit != null && ks.has(r.path)));
      for (const k of ks) this.sel.delete(k);
      this.saved();
      this.setSignals(this.signals);
      this.renderNames();
      this.draw();
    }

    saved() { S.UI.store.set('waveRows', this.rows); S.UI.store.set('waveGroups', this.groups); S.UI.store.set('waveMarkers', this.markers); }

    rowH(r) { return r.width > 1 && r.bit == null ? BUSH : RH; }

    // rows in display order: groups as headers (folded groups hide their members)
    visible() {
      const out = [], seen = new Set();
      for (const r of this.rows) {
        if (r.group) {
          if (seen.has(r.group)) continue;
          seen.add(r.group);
          out.push({ header: r.group });
          if (this.groups[r.group] && this.groups[r.group].open === false) continue;
          for (const m of this.rows) if (m.group === r.group) out.push(m);
        } else out.push(r);
      }
      return out;
    }

    renderNames() {
      const els = [h('div.wv-hd', { style: { height: AXIS + 'px' } }, this.cursor != null ? (this.cursorB != null ? 'A · B−A' : 'value at A') : 'signal')];
      if (!this.rows.length) els.push(h('div.wv-empty', 'No signals yet. Select a wire and press W, drag a wire here, or add one from the list.'));
      for (const r of this.visible()) {
        if (r.header) {
          const open = !(this.groups[r.header] && this.groups[r.header].open === false);
          const hd = h('div.wv-group', { role: 'button', 'aria-expanded': String(open), style: { height: RH + 'px' } }, S.UI.icon(open ? 'down' : 'right'), r.header,
            h('span.n', ' ' + this.rows.filter((x) => x.group === r.header).length));
          hd.addEventListener('click', () => { this.groups[r.header] = { open: !open }; this.saved(); this.renderNames(); this.draw(); });
          hd.addEventListener('dblclick', async () => {
            const name = await S.UI.prompt('Rename the group', 'Name', r.header, (s) => s.trim() ? null : 'Give it a name.');
            if (name) { for (const x of this.rows) if (x.group === r.header) x.group = name.trim(); this.groups[name.trim()] = this.groups[r.header]; delete this.groups[r.header]; this.saved(); this.renderNames(); }
          });
          els.push(hd);
          continue;
        }
        const k = this.key(r);
        const radix = h('button.wv-radix', { type: 'button', title: 'Show as: hex, dec, bin or signed' }, r.width > 1 && r.bit == null ? (r.radix === 'signed' ? '±' : r.radix) : '');
        radix.addEventListener('click', (e) => { e.stopPropagation(); if (r.width < 2) return; r.radix = RADIX[(RADIX.indexOf(r.radix) + 1) % RADIX.length]; this.saved(); this.renderNames(); this.draw(); });
        const isInput = r.bit == null && ((this.app.gen && this.app.gen.ports) || []).some((q) => q.name === r.path && q.dir === 'in' && !q.clockOrReset);
        const pat = isInput ? h('button.wv-pat', { type: 'button', title: 'Stimulus: give ' + r.label + ' a pattern of values over the coming cycles' }, '∿') : null;
        if (pat) pat.addEventListener('click', (e) => { e.stopPropagation(); this.stimulus(r); });
        const x = h('button.wv-x', { type: 'button', title: 'Remove ' + r.label, 'aria-label': 'Remove ' + r.label }, '×');
        x.addEventListener('click', (e) => { e.stopPropagation(); this.remove([k]); });
        const exp = r.width > 1 && r.bit == null && !r.stream ? h('button.wv-exp', { type: 'button', title: this.expanded(r) ? 'Fold the bits (X)' : 'Expand into bits (X)' }, this.expanded(r) ? '−' : '+') : h('span.wv-exp');
        exp.addEventListener && exp.addEventListener('click', (e) => { e.stopPropagation(); this.toggleBits(r); });
        const sd = r.stream && this.data[r.path] && this.data[r.path].tx;
        const va = r.stream ? (sd ? sd.fires + '⇄ ' + sd.stalls + '⏸' : '') : this.valueAt(r, this.cursor ?? this.head);
        const vb = this.cursorB != null && !r.stream ? this.valueAt(r, this.cursorB) : null;
        const val = h('span.wv-val', { title: r.stream ? (sd ? sd.fires + ' transfers and ' + sd.stalls + ' stalled cycles in view' : '') : vb != null ? 'A ' + va + ' · B ' + vb : va }, va);
        const row = h('div.wv-name' + (this.sel.has(k) ? '.sel' : '') + (r.group ? '.in-group' : '') + (r.bit != null ? '.bit' : ''),
          { role: 'option', 'aria-selected': String(this.sel.has(k)), title: r.path + (r.bit != null ? ' bit ' + r.bit : ' (' + r.width + ' bit' + (r.width > 1 ? 's' : '') + ')') + ': drag to reorder, click to select', style: { height: this.rowH(r) + 'px' } },
          exp, h('span.wv-lb', r.bit != null ? '[' + r.bit + ']' : r.label), val, pat, radix, x);
        let drag = null;
        row.addEventListener('pointerdown', (e) => {
          if (e.target.closest('button')) return;
          row.setPointerCapture(e.pointerId);
          drag = { y: e.clientY, k, moved: false };
        });
        row.addEventListener('pointermove', (e) => {
          if (!drag) return;
          const dy = e.clientY - drag.y;
          if (Math.abs(dy) < RH * 0.6) return;
          drag.moved = true;
          const i = this.rows.findIndex((q) => this.key(q) === drag.k), j = Math.max(0, Math.min(this.rows.length - 1, i + Math.sign(dy)));
          if (j !== i) { const [m] = this.rows.splice(i, 1); this.rows.splice(j, 0, m); drag.y = e.clientY; this.saved(); this.renderNames(); this.draw(); }
        });
        row.addEventListener('pointerup', (e) => {
          const d = drag; drag = null;
          if (!d || d.moved) return;
          if (e.shiftKey || e.metaKey || e.ctrlKey) { this.sel.has(k) ? this.sel.delete(k) : this.sel.add(k); }
          else { this.sel.clear(); this.sel.add(k); }
          this.renderNames();
          this.draw();
        });
        els.push(row);
      }
      this.names.replaceChildren(...els);
      this.cv.style.height = (AXIS + this.visible().reduce((s, r) => s + (r.header ? RH : this.rowH(r)), 0) + 6) + 'px';
      this.renderNotes();
    }

    expanded(r) { return this.rows.some((q) => q.path === r.path && q.bit != null); }

    toggleBits(r) {
      if (this.expanded(r)) this.rows = this.rows.filter((q) => !(q.path === r.path && q.bit != null));
      else {
        const i = this.rows.indexOf(r);
        const bits = Array.from({ length: Math.min(r.width, 64) }, (_, b) => ({ path: r.path, label: r.label, bit: r.width - 1 - b, width: 1, group: r.group }));
        this.rows.splice(i + 1, 0, ...bits);
      }
      this.saved(); this.renderNames(); this.draw();
    }

    async groupSel() {
      const rows = this.rows.filter((r) => this.sel.has(this.key(r)));
      if (rows.length < 2) { S.UI.toast('Select two or more signals first (shift-click the names).'); return; }
      let n = 1; while (this.groups['group' + n]) n++;
      const name = await S.UI.prompt('Group the signals', 'Name', 'group' + n, (s) => s.trim() ? null : 'Give the group a name.');
      if (!name) return;
      const first = this.rows.indexOf(rows[0]);
      this.rows = this.rows.filter((r) => !rows.includes(r));
      for (const r of rows) r.group = name.trim();
      this.rows.splice(Math.min(first, this.rows.length), 0, ...rows);
      this.groups[name.trim()] = { open: true };
      this.saved(); this.renderNames(); this.draw();
    }

    async addMarker() {
      if (this.cursor == null) { S.UI.toast('Click the waves first to place cursor A; the marker goes there.'); return; }
      const name = await S.UI.prompt('Marker at cycle ' + this.cursor, 'Name', 'm' + (this.markers.length + 1), (s) => s.trim() ? null : 'Give it a name.');
      if (!name) return;
      this.markers.push({ cycle: this.cursor, name: name.trim() });
      this.saved(); this.draw(); this.renderNotes();
    }

    renderNotes() {
      const items = [
        ...this.marks.map((m) => ({ cycle: m.cycle, text: m.text, kind: m.kind })),
        ...this.markers.map((m) => ({ cycle: m.cycle, text: m.name, kind: 'marker' })),
      ].sort((a, b) => a.cycle - b.cycle).slice(-12);
      fill(this.notes, items.map((m) => {
        const b = h('button.wv-note.' + m.kind, { type: 'button', title: 'Go to cycle ' + m.cycle }, h('b', (m.kind === 'marker' ? '⚑ ' : m.kind === 'fail' ? '× ' : '') + 'cycle ' + m.cycle), ' ', m.text);
        b.addEventListener('click', () => { this.cursor = m.cycle; this.app.goto(m.cycle); this.centreOn(m.cycle); });
        if (m.kind === 'marker') b.addEventListener('contextmenu', (e) => { e.preventDefault(); this.markers = this.markers.filter((x) => !(x.cycle === m.cycle && x.name === m.text)); this.saved(); this.renderNotes(); this.draw(); });
        return b;
      }));
      this.notes.style.display = items.length ? '' : 'none';
    }

    centreOn(c) {
      const span = this.t1 - this.t0;
      if (c < this.t0 || c > this.t1) { this.t0 = Math.max(0, c - span / 2); this.t1 = this.t0 + span; this.follow = false; }
      this.fetchSoon();
    }

    // ---------------------------------------------------------------- data

    setHead(head) {
      this.head = head;
      if (this.follow) {
        const span = Math.max(this.t1 - this.t0, 40);
        if (head > this.t1 || this.t0 > 0 && head < this.t1) { this.t1 = Math.max(head, span); this.t0 = Math.max(0, this.t1 - span); }
        if (this.t1 < head) this.t1 = head;
      }
      this.fetchSoon();
    }

    fetchSoon() { if (this.fetching) { this.again = true; return; } this.fetch(); }

    async fetch() {
      if (!this.rows.length) { this.draw(); return; }
      this.fetching = true;
      try {
        const cols = Math.max(50, Math.floor(this.plot.clientWidth));
        const lo = Math.max(0, Math.floor(this.t0)), hi = Math.ceil(this.t1);
        const paths = [...new Set(this.rows.filter((x) => !x.stream).map((x) => x.path))];
        const r = await S.Api.call('waves', { paths, from: lo, to: hi, columns: cols });
        this.data = r.waves || {};
        // streams: their transactions in the window (a long window is left to the per-signal rows)
        for (const row of this.rows) if (row.stream) {
          try { this.data[row.path] = hi - lo <= 20000 ? { tx: await S.Api.call('transactions', { stream: row.stream, from: lo, to: hi }) } : { wide: true }; }
          catch (e) { this.data[row.path] = { missing: true }; }
        }
        this.marks = r.marks || [];
        this.scheduled = r.scheduled || {};
        this.head = r.head ?? this.head;
        this.draw();
        this.renderNames();
      } catch (e) { /* the server may be restarting */ }
      this.fetching = false;
      if (this.again) { this.again = false; setTimeout(() => this.fetch(), 30); }
    }

    rawAt(r, cycle) {
      const w = this.data[r.path];
      if (!w || cycle == null || w.missing) return null;
      let v = null;
      const list = w.exact ? w.changes : w.columns;
      let lo = 0, hi = list.length - 1;
      while (lo <= hi) { const m = (lo + hi) >> 1; if (list[m][0] <= cycle) { v = list[m][1]; lo = m + 1; } else hi = m - 1; }
      if (v == null) return null;
      let x = BigInt('0x' + v);
      if (r.bit != null) x = (x >> BigInt(r.bit)) & 1n;
      return x;
    }

    valueAt(r, cycle) { const x = this.rawAt(r, cycle); return x == null ? '' : this.fmt(x, r); }

    // the state names of a state machine's register, for its row in the waves
    enumOf(path) {
      const lv = this.app.doc && this.app.doc.d;
      const p = lv && lv.parts.find((q) => q.type === 'fsm' && (q.params.name || 'fsm') === path);
      return p ? String(p.params.states || '').trim().split(/\s+/) : null;
    }

    fmt(v, r) {
      const w = r.bit != null ? 1 : r.width;
      const names = r.bit == null && r.radix !== 'hex!' ? this.enumOf(r.path) : null;
      if (names && r.radix !== 'dec' && r.radix !== 'bin') return names[Number(v)] ?? '?' + v;
      if (w === 1) return v.toString();
      switch (r.radix) {
        case 'bin': return v.toString(2).padStart(w, '0');
        case 'dec': return v.toString(10);
        case 'signed': { const m = 1n << BigInt(w); return (v >= m / 2n ? v - m : v).toString(); }
        default: return v.toString(16).toUpperCase();
      }
    }

    // the cycles at which a row changes, within the loaded window
    changesOf(r) {
      const w = this.data[r.path];
      if (!w || !w.exact) return null;
      if (r.bit == null) return w.changes.map((c) => c[0]);
      const out = [];
      let last = null;
      for (const [c, v] of w.changes) { const b = (BigInt('0x' + v) >> BigInt(r.bit)) & 1n; if (b !== last) { out.push(c); last = b; } }
      return out;
    }

    jump(dir) {
      const r = this.rows.find((x) => this.sel.has(this.key(x)));
      if (!r) { S.UI.toast('Select a signal on the left first; arrows then go to its previous or next change.'); return; }
      const at = this.cursor ?? this.head;
      const ch = this.changesOf(r);
      if (!ch) { S.UI.toast('Zoom in a little: this stretch has too many changes to step through.'); return; }
      const t = dir > 0 ? ch.find((c) => c > at) : [...ch].reverse().find((c) => c < at);
      if (t == null) { S.UI.toast(dir > 0 ? 'No later change of ' + r.label + ' in view.' : 'No earlier change of ' + r.label + ' in view.'); return; }
      this.cursor = t;
      this.app.goto(t);
      this.centreOn(t);
      this.renderNames();
    }

    // ---------------------------------------------------------------- view

    zoom(f, atX) {
      const W = this.plot.clientWidth || 1;
      const at = atX == null ? (this.t0 + this.t1) / 2 : this.t0 + (atX / W) * (this.t1 - this.t0);
      const span = Math.max(4, Math.min((this.t1 - this.t0) * f, Math.max(this.head * 4, 100)));
      this.t0 = Math.max(0, at - (at - this.t0) * (span / (this.t1 - this.t0)));
      this.t1 = this.t0 + span;
      this.follow = this.t1 >= this.head && this.t0 <= 0;
      this.fetchSoon();
    }

    fitAll() { this.t0 = 0; this.t1 = Math.max(this.head, 40); this.fetchSoon(); }

    xOf(c) { return (c - this.t0) / (this.t1 - this.t0) * this.plot.clientWidth; }
    cOf(x) { return this.t0 + x / this.plot.clientWidth * (this.t1 - this.t0); }

    bind() {
      this.plot.addEventListener('wheel', (e) => {
        const r = this.plot.getBoundingClientRect();
        if (e.ctrlKey || e.metaKey || e.altKey) { e.preventDefault(); this.zoom(Math.exp(e.deltaY * 0.004), e.clientX - r.left); }
        else if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
          e.preventDefault();
          const span = this.t1 - this.t0, d = e.deltaX / r.width * span;
          this.t0 = Math.max(0, this.t0 + d); this.t1 = this.t0 + span; this.follow = false; this.fetchSoon();
        }
      }, { passive: false });
      let drag = null;
      this.plot.addEventListener('pointerdown', (e) => { this.plot.focus({ preventScroll: true }); this.plot.setPointerCapture(e.pointerId); drag = { x: e.clientX, t0: this.t0, t1: this.t1, moved: false, range: e.altKey }; });
      this.plot.addEventListener('pointermove', (e) => {
        const r = this.plot.getBoundingClientRect();
        this.hoverAt(e.clientX - r.left, e.clientY - r.top);
        if (!drag) return;
        const dx = e.clientX - drag.x;
        if (Math.abs(dx) > 4) drag.moved = true;
        if (!drag.moved) return;
        if (drag.range) { this.rangeSel = [drag.x - r.left, e.clientX - r.left]; this.draw(); return; }
        const span = drag.t1 - drag.t0, d = -dx / this.plot.clientWidth * span;
        this.t0 = Math.max(0, drag.t0 + d); this.t1 = this.t0 + span; this.follow = false; this.fetchSoon();
      });
      this.plot.addEventListener('pointerleave', () => { this.tip.style.display = 'none'; });
      this.plot.addEventListener('pointerup', (e) => {
        const d = drag; drag = null;
        if (!d) return;
        if (d.range && this.rangeSel) {
          const [a, b] = this.rangeSel.map((x) => this.cOf(x)).sort((p, q) => p - q);
          this.rangeSel = null;
          if (b - a >= 2) { this.t0 = Math.max(0, a); this.t1 = b; this.follow = false; this.fetchSoon(); }
          return;
        }
        if (d.moved) return;
        const r = this.plot.getBoundingClientRect();
        let c = Math.max(0, Math.min(this.head, this.cOf(e.clientX - r.left)));
        // snap to a change of any shown signal within 6 px
        const px = 6 * (this.t1 - this.t0) / this.plot.clientWidth;
        let best = null;
        for (const row of this.rows) { const ch = this.changesOf(row); if (ch) for (const t of ch) if (Math.abs(t - c) <= px && (best == null || Math.abs(t - c) < Math.abs(best - c))) best = t; }
        c = Math.round(best ?? c);
        if (e.shiftKey) { this.cursorB = c; this.draw(); this.renderNames(); return; }
        this.cursor = c;
        this.app.goto(c);
        this.draw();
        this.renderNames();
      });
      const keys = (e) => {
        if (e.key === 'F3') { e.preventDefault(); e.stopPropagation(); this.findAgain(e.shiftKey ? -1 : 1); return; }
        if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') { e.preventDefault(); e.stopPropagation(); this.jump(e.key === 'ArrowRight' ? 1 : -1); }
        else if (e.key === 'g' || e.key === 'G') { e.preventDefault(); e.stopPropagation(); this.groupSel(); }
        else if (e.key === 'm' || e.key === 'M') { e.preventDefault(); e.stopPropagation(); this.addMarker(); }
        else if (e.key === 'x' || e.key === 'X') { e.preventDefault(); e.stopPropagation(); const r = this.rows.find((q) => this.sel.has(this.key(q))); if (r && r.width > 1) this.toggleBits(r); }
        else if ((e.key === 'f' || e.key === 'F') && (e.ctrlKey || e.metaKey)) { e.preventDefault(); e.stopPropagation(); this.search(e.shiftKey ? -1 : 1); }
        else if (e.key === 'Delete' || e.key === 'Backspace') { if (this.sel.size) { e.preventDefault(); e.stopPropagation(); this.remove([...this.sel]); } }
      };
      this.plot.addEventListener('keydown', keys);
      this.names.addEventListener('keydown', keys);
    }

    // the next (or previous) cycle at which a condition holds, in what has been recorded
    async search(dir) {
      const text = await S.UI.prompt(dir > 0 ? 'Find the next time…' : 'Find the previous time…', 'Condition over recorded signals', this.lastFind || '', (v) => v.trim() ? null : 'Write a condition, such as valid && !ready.');
      if (!text) return;
      this.lastFind = text.trim();
      this.findAgain(dir);
    }

    async findAgain(dir) {
      if (!this.lastFind) return this.search(dir);
      try {
        const r = await S.Api.call('waves', { find: this.lastFind, from: this.cursor ?? (dir > 0 ? -1 : this.head + 1), dir });
        if (r.cycle == null) { S.UI.toast('“' + this.lastFind + '” does not hold ' + (dir > 0 ? 'after' : 'before') + ' cycle ' + (this.cursor ?? 0) + ' in the record.'); return; }
        this.cursor = r.cycle;
        this.app.goto(r.cycle);
        this.centreOn(r.cycle);
        this.renderNames();
        this.app.say('“' + this.lastFind + '” holds at cycle ' + r.cycle + '. F3 finds the next.');
      } catch (e) { S.UI.toast(e.message, 'err', 6000); }
    }

    hoverAt(x, y) {
      // a mark under the pointer explains itself
      const c = this.cOf(x), px = 5 * (this.t1 - this.t0) / this.plot.clientWidth;
      const all = [...this.marks.map((m) => ({ cycle: m.cycle, text: m.text })), ...this.markers.map((m) => ({ cycle: m.cycle, text: '⚑ ' + m.name }))];
      const m = all.find((q) => Math.abs(q.cycle - c) <= px);
      if (m) {
        this.tip.textContent = 'cycle ' + m.cycle + ': ' + m.text;
        this.tip.style.display = 'block';
        this.tip.style.left = Math.min(x + 10, this.plot.clientWidth - 260) + 'px';
        this.tip.style.top = (y + 12) + 'px';
      } else this.tip.style.display = 'none';
    }

    clearCursor() { this.cursor = null; this.draw(); this.renderNames(); }

    // ---------------------------------------------------------------- drawing

    // the waves on the canvas, or (for a picture) on another context of the same size: an SvgContext
    draw(into) {
      const cv = this.cv, W = this.plot.clientWidth, H = parseFloat(cv.style.height) || 100, dpr = devicePixelRatio || 1;
      if (!W) return;
      let g = into;
      if (!g) {
        cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); cv.style.width = W + 'px';
        g = cv.getContext('2d');
        g.setTransform(dpr, 0, 0, dpr, 0, 0);
      }
      const css = getComputedStyle(this.root), col = (n) => css.getPropertyValue(n).trim();
      g.clearRect(0, 0, W, H);
      const span = this.t1 - this.t0;
      let step = 1;
      outer: for (let e = 0; e < 13; e++) for (const m of [1, 2, 5]) { const s = m * 10 ** e; if (s / span * W >= 80) { step = s; break outer; } }
      g.font = '10.5px ' + col('--mono');
      g.textBaseline = 'middle';
      let lastX = -1e9;
      for (let c = Math.ceil(this.t0 / step) * step; c <= this.t1; c += step) {
        const x = Math.round(this.xOf(c)) + 0.5;
        g.strokeStyle = col('--line'); g.lineWidth = 1;
        g.beginPath(); g.moveTo(x, AXIS - 6); g.lineTo(x, H); g.stroke();
        const label = this.hz ? this.timeLabel(c, step) : String(c);
        const tw = g.measureText(label).width;
        // a label that would be cut at either edge is left out
        if (x - tw / 2 > Math.max(lastX + 8, 2) && x + tw / 2 < W) { g.fillStyle = col('--muted'); g.fillText(label, x - tw / 2, AXIS / 2); lastX = x + tw / 2; }
      }
      // marks: reloads (grey), forks (amber), counterexamples (accent), failures (red), markers (purple flags)
      const markColour = { reload: '--faint', fork: '--warn', trace: '--accent', fail: '--bad' };
      for (const m of this.marks) {
        const x = Math.round(this.xOf(m.cycle)) + 0.5;
        if (x < 0 || x > W) continue;
        g.setLineDash(m.kind === 'fail' ? [] : [3, 3]); g.strokeStyle = col(markColour[m.kind] || '--faint'); g.lineWidth = m.kind === 'fail' ? 1.6 : 1;
        g.beginPath(); g.moveTo(x, AXIS); g.lineTo(x, H); g.stroke(); g.setLineDash([]); g.lineWidth = 1;
        if (m.kind === 'fail') { g.fillStyle = col('--bad'); g.beginPath(); g.arc(x, AXIS - 4, 3.5, 0, 7); g.fill(); }
      }
      for (const m of this.markers) {
        const x = Math.round(this.xOf(m.cycle)) + 0.5;
        if (x < 0 || x > W) continue;
        g.strokeStyle = col('--c4'); g.beginPath(); g.moveTo(x, AXIS - 8); g.lineTo(x, H); g.stroke();
        g.fillStyle = col('--c4'); g.fillRect(x, AXIS - 10, 8, 6);
      }
      const hx = this.xOf(this.head);
      if (hx < W) { g.fillStyle = col('--panel2'); g.globalAlpha = 0.5; g.fillRect(Math.max(0, hx), AXIS, W - Math.max(0, hx), H - AXIS); g.globalAlpha = 1; }
      let y = AXIS;
      for (const r of this.visible()) {
        if (r.header) {
          g.fillStyle = col('--panel2'); g.fillRect(0, y, W, RH);
          y += RH;
          continue;
        }
        const rh = this.rowH(r), w = this.data[r.path];
        if (this.sel.has(this.key(r))) { g.fillStyle = col('--accent-soft'); g.globalAlpha = 0.5; g.fillRect(0, y, W, rh); g.globalAlpha = 1; }
        if (r.stream) this.drawStream(g, r, w, y, rh, W, col);
        else if (w && !w.missing) (r.width > 1 && r.bit == null ? this.drawBus : this.drawBit).call(this, g, r, w, y, rh, W, col);
        const sch = r.bit == null && this.scheduled && this.scheduled[r.path];
        if (sch && sch.length) {   // values still to come, dashed beyond the live head
          g.save(); g.setLineDash([4, 3]); g.strokeStyle = col('--accent'); g.fillStyle = col('--accent'); g.lineWidth = 1.4; g.font = '11px ' + col('--mono'); g.textBaseline = 'middle';
          const top = y + 5, bot = y + rh - 5;
          for (let i = 0; i < sch.length; i++) {
            const c0 = sch[i][0], c1 = i + 1 < sch.length ? sch[i + 1][0] : Math.max(c0 + 1, this.t1), x0 = this.xOf(c0), x1 = this.xOf(c1);
            if (x1 < 0 || x0 > W) continue;
            if (r.width === 1) { const yy = sch[i][1] === '0' ? bot : top; g.beginPath(); g.moveTo(x0, top); g.lineTo(x0, bot); g.moveTo(x0, yy); g.lineTo(Math.min(x1, W), yy); g.stroke(); }
            else { g.strokeRect(x0, top, Math.max(1, x1 - x0), bot - top); if (x1 - x0 > 24) g.fillText(this.fmt(BigInt('0x' + sch[i][1]), r), x0 + 4, (top + bot) / 2); }
          }
          g.restore();
        }
        g.strokeStyle = col('--line'); g.globalAlpha = 0.5; g.beginPath(); g.moveTo(0, y + rh + 0.5); g.lineTo(W, y + rh + 0.5); g.stroke(); g.globalAlpha = 1;
        y += rh;
      }
      if (this.rangeSel) { const [a, b] = this.rangeSel; g.fillStyle = col('--accent'); g.globalAlpha = 0.15; g.fillRect(Math.min(a, b), AXIS, Math.abs(b - a), H - AXIS); g.globalAlpha = 1; }
      const cur = (c, colour) => { if (c == null) return; const x = Math.round(this.xOf(c)) + 0.5; g.strokeStyle = colour; g.lineWidth = 1.4; g.beginPath(); g.moveTo(x, AXIS - 6); g.lineTo(x, H); g.stroke(); g.lineWidth = 1; };
      cur(this.cursor, col('--accent'));
      cur(this.cursorB, col('--c4'));
      if (into) return;
      const fmtc = (c) => this.hz ? c + ' (' + this.timeLabel(c) + ')' : String(c);
      this.info.textContent = this.cursor == null ? 'Click the waves to go to a cycle · shift-click: cursor B · alt-drag: zoom to a range'
        : 'A ' + fmtc(this.cursor) + (this.cursorB != null ? ' · B ' + fmtc(this.cursorB) + ' · Δ ' + Math.abs(this.cursorB - this.cursor) + ' cycles' : ' · shift-click for B');
    }

    // a time, in the unit that suits the spacing (a tick's step, or one cycle), with just enough decimals that
    // neighbouring labels differ
    timeLabel(c, step) {
      const sec = c / this.hz, stepSec = Math.max((step || 1) / this.hz, 1e-12), mag = Math.max(sec, stepSec);
      const [u, name] = mag >= 1 ? [1, 's'] : mag >= 1e-3 ? [1e-3, 'ms'] : mag >= 1e-6 ? [1e-6, 'µs'] : [1e-9, 'ns'];
      const dec = Math.min(6, Math.max(0, Math.ceil(-Math.log10(stepSec / u) - 1e-9)));
      return (sec / u).toFixed(dec) + ' ' + name;
    }

    // [c0, c1, hex | null (dense), changes]
    segments(w, bit) {
      const out = [];
      const end = Math.min(this.head, Math.ceil(this.t1)) + 1;
      const pick = (hex) => bit == null ? hex : ((BigInt('0x' + hex) >> BigInt(bit)) & 1n).toString(16);
      if (w.exact) {
        const ch = w.changes;
        for (let i = 0; i < ch.length; i++) {
          const c0 = Math.max(ch[i][0], this.t0), c1 = i + 1 < ch.length ? ch[i + 1][0] : end;
          if (c1 > c0) out.push([c0, c1, pick(ch[i][1]), 0]);
        }
      } else {
        const cols = w.columns;
        for (let i = 0; i < cols.length; i++) {
          const [c, first, n] = cols[i];
          const c1 = i + 1 < cols.length ? cols[i + 1][0] : end;
          if (c >= end) break;
          out.push([c, Math.min(c1, end), n ? null : pick(first), n]);
        }
      }
      return out;
    }

    // transfers as boxes, one per cycle when there is room (else one per run, with the count); a stall
    // (valid, not ready) as an amber hatch; ready with nothing to send as a thin line
    drawStream(g, r, w, y, rh, W, col) {
      const top = y + 4, bot = y + rh - 4, mid = (top + bot) / 2;
      g.font = '11px ' + col('--mono'); g.textBaseline = 'middle';
      if (!w || w.missing || w.wide) {
        g.fillStyle = col('--muted'); g.fillText(w && w.wide ? 'zoom in to 20,000 cycles or fewer to see transactions' : 'no record of ' + r.stream + '_valid, _ready, _bits', 8, mid);
        return;
      }
      const px = W / Math.max(1, this.t1 - this.t0);
      for (const [c0, c1, kind, bits] of w.tx.runs) {
        const x0 = this.xOf(c0), x1 = this.xOf(c1);
        if (x1 < 0 || x0 > W) continue;
        if (kind === 'starve') { g.strokeStyle = col('--faint'); g.beginPath(); g.moveTo(Math.max(0, x0), mid); g.lineTo(Math.min(W, x1), mid); g.stroke(); continue; }
        if (kind === 'stall') {
          g.fillStyle = col('--warn'); g.globalAlpha = 0.25; g.fillRect(x0, top + 3, Math.max(1, x1 - x0), bot - top - 6); g.globalAlpha = 1;
          g.strokeStyle = col('--warn'); g.strokeRect(x0 + 0.5, top + 3.5, Math.max(1, x1 - x0 - 1), bot - top - 7);
          continue;
        }
        const n = c1 - c0, per = px >= 18;
        for (let k = 0; k < (per ? n : 1); k++) {
          const a = per ? this.xOf(c0 + k) : x0, b = per ? this.xOf(c0 + k + 1) : x1;
          if (b < 0 || a > W) continue;
          g.fillStyle = col('--accent'); g.globalAlpha = 0.18; g.fillRect(a + 1, top, Math.max(1, b - a - 2), bot - top); g.globalAlpha = 1;
          g.strokeStyle = col('--accent'); g.lineWidth = 1.2; g.strokeRect(a + 1.5, top + 0.5, Math.max(1, b - a - 3), bot - top - 1); g.lineWidth = 1;
          const text = this.fmt(BigInt('0x' + bits), r) + (!per && n > 1 ? ' ×' + n : ''), tw = g.measureText(text).width;
          if (b - a - 8 >= tw) { g.fillStyle = col('--text'); g.fillText(text, (a + b) / 2 - tw / 2, mid + 0.5); }
        }
      }
    }

    drawBit(g, r, w, y, rh, W, col) {
      const top = y + 5, bot = y + rh - 5;
      g.strokeStyle = r.path === 'clock' ? col('--accent') : col('--v1');
      g.lineWidth = 1.6;
      g.beginPath();
      let started = false, lastY = null;
      for (const [c0, c1, v] of this.segments(w, r.bit)) {
        const x0 = this.xOf(c0), x1 = this.xOf(c1);
        if (x1 < 0 || x0 > W) continue;
        if (v == null) { g.stroke(); g.fillStyle = col('--v1'); g.globalAlpha = 0.35; g.fillRect(x0, top, Math.max(1, x1 - x0), bot - top); g.globalAlpha = 1; g.beginPath(); started = false; continue; }
        const yy = v === '0' ? bot : top;
        if (!started) { g.moveTo(Math.max(0, x0), yy); started = true; }
        else if (lastY !== yy) g.lineTo(x0, yy);
        g.lineTo(Math.min(W, x1), yy);
        lastY = yy;
      }
      g.stroke();
      g.lineWidth = 1;
    }

    drawBus(g, r, w, y, rh, W, col) {
      const top = y + 4, bot = y + rh - 4, mid = (top + bot) / 2, cut = 3;
      g.font = '11px ' + col('--mono');
      g.textBaseline = 'middle';
      for (const [c0, c1, v] of this.segments(w)) {
        const x0 = this.xOf(c0), x1 = this.xOf(c1);
        if (x1 < 0 || x0 > W) continue;
        if (v == null) { g.fillStyle = col('--vbus'); g.globalAlpha = 0.28; g.fillRect(x0, top, Math.max(1, x1 - x0), bot - top); g.globalAlpha = 1; continue; }
        g.beginPath();
        g.moveTo(x0, mid); g.lineTo(x0 + cut, top); g.lineTo(x1 - cut, top); g.lineTo(x1, mid); g.lineTo(x1 - cut, bot); g.lineTo(x0 + cut, bot); g.closePath();
        g.fillStyle = col('--vbus'); g.globalAlpha = 0.1; g.fill(); g.globalAlpha = 1;
        g.strokeStyle = col('--vbus'); g.lineWidth = 1.3; g.stroke(); g.lineWidth = 1;
        const text = this.fmt(BigInt('0x' + v), r), tw = g.measureText(text).width;
        const vx0 = Math.max(x0, 0) + 6, vx1 = Math.min(x1, W) - 6;
        if (vx1 - vx0 >= tw) { g.fillStyle = col('--text'); g.fillText(text, Math.max(vx0, Math.min((x0 + x1) / 2 - tw / 2, vx1 - tw)), mid + 0.5); }
      }
    }

    // a searchable list of the design's signals, grouped by instance (a.b.c is in a.b); Enter adds the first
    // match, a click adds one, and the list stays open to add more
    picker() {
      const shown = new Set(this.rows.map((r) => r.path));
      const all = Object.keys(this.signals || {}).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
      const q = h('input.input', { placeholder: 'Find a signal (name, or part of a path)', spellcheck: 'false', 'aria-label': 'Find a signal' });
      const list = h('div.wpick', { role: 'listbox' });
      const count = h('div.help');
      const render = () => {
        const t = q.value.trim().toLowerCase(), terms = t.split(/\s+/).filter(Boolean);
        const hits = all.filter((p) => terms.every((x) => p.toLowerCase().includes(x)));
        const groups = new Map();
        for (const p of hits.slice(0, 400)) { const i = p.lastIndexOf('.'); const g = i < 0 ? '(top)' : p.slice(0, i); if (!groups.has(g)) groups.set(g, []); groups.get(g).push(p); }
        const els = [];
        const streams = this.streams().filter((b) => terms.every((x) => b.toLowerCase().includes(x)));
        if (streams.length) {
          els.push(h('div.wpick-g', 'streams (transactions)', h('span.n', ' ' + streams.length)));
          for (const b of streams) {
            const on = shown.has('stream:' + b);
            const row = h('button.wpick-s' + (on ? '.on' : ''), { type: 'button', role: 'option', title: on ? 'Already in the waves' : 'Add the transactions of ' + b },
              h('span', b + ' ⇄'), h('span.t', 'valid · ready · bits'));
            row.addEventListener('click', () => { if (!on) { this.addStream(b); shown.add('stream:' + b); render(); } });
            els.push(row);
          }
        }
        for (const [g, ps] of groups) {
          els.push(h('div.wpick-g', g, h('span.n', ' ' + ps.length)));
          for (const p of ps) {
            const row = h('button.wpick-s' + (shown.has(p) ? '.on' : ''), { type: 'button', role: 'option', title: shown.has(p) ? 'Already in the waves' : 'Add ' + p },
              h('span', p.slice(p.lastIndexOf('.') + 1)), h('span.t', this.signals[p] > 1 ? this.signals[p] + ' bits' : '1 bit'));
            row.addEventListener('click', () => { if (!shown.has(p)) { this.add(p); shown.add(p); render(); } });
            els.push(row);
          }
        }
        fill(list, els.length ? els : h('div.help', 'No signal matches.'));
        count.textContent = hits.length + ' of ' + all.length + ' signals' + (hits.length > 400 ? ' (the first 400 are listed: narrow the search)' : '') + ' · ' + shown.size + ' in the waves';
        this.pickHits = hits;
      };
      q.addEventListener('input', render);
      q.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Enter') { const p = (this.pickHits || []).find((x) => !shown.has(x)); if (p) { this.add(p); shown.add(p); render(); } }
      });
      render();
      S.UI.modal('Add signals to the waves', h('div', q, count, list), { ok: 'Done' });
      setTimeout(() => q.focus(), 0);
    }

    // a stimulus lane: an input takes a list of values in turn, each held for a number of cycles, from a cycle on
    async stimulus(r) {
      const prev = (this.stim && this.stim[r.path]) || { from: this.head + 1, every: 4, values: '0 1', repeat: 8 };
      const f = (label, value, help) => { const i = h('input.input', { value, spellcheck: 'false' }); return [i, h('div.field', h('label', label), i, help ? h('div.help', help) : null)]; };
      const [from, fFrom] = f('From cycle', String(prev.from), 'The first value applies from this cycle (the past is not rewritten).');
      const [vals, fVals] = f('Values', prev.values, 'In turn, separated by spaces: decimal, 0x… or 0b….');
      const [every, fEvery] = f('Each held for (cycles)', String(prev.every));
      const [rep, fRep] = f('Repeat the list', String(prev.repeat), 'How many times; the input keeps the last value after.');
      const err = h('div.err');
      const ok = await S.UI.modal('Stimulus for ' + r.label, h('div', fFrom, fVals, fEvery, fRep, err), { ok: 'Apply' });
      if (!ok) return;
      try {
        const c0 = Number(from.value), n = Number(every.value), k = Number(rep.value);
        const vs = vals.value.trim().split(/[\s,]+/).filter(Boolean).map((v) => BigInt(v));
        if (!(c0 >= 0) || !(n >= 1) || !(k >= 1) || !vs.length) throw new Error('A cycle, at least one value, and whole numbers of at least 1.');
        const at = [];
        for (let t = 0; t < k && at.length < 20000; t++) for (const v of vs) at.push([c0 + at.length * n, v.toString(16)]);
        await S.Api.call('sim', { cmd: 'schedule', path: r.path, at });
        this.stim = { ...(this.stim || {}), [r.path]: { from: c0, every: n, values: vals.value, repeat: k } };
        this.app.say(r.label + ' takes ' + at.length + ' scheduled values from cycle ' + c0 + ' (dashed in the waves until the run reaches them).');
        this.fetchSoon();
      } catch (e) { S.UI.toast(e.message, 'err', 6000); }
    }

    exportMenu(anchor) {
      const r = anchor.getBoundingClientRect();
      S.UI.menu(r.left, r.bottom + 4, [
        { label: 'The shown signals as VCD (.vcd)', action: () => this.exportVcd() },
        { label: 'The shown signals as FST (.fst, GTKWave and Surfer)', action: () => this.exportFst() },
        { label: 'This window as a vector picture (.svg)', action: () => this.exportSvg() },
        { label: 'This window as a picture (.png)', action: () => this.exportPng() },
        { label: 'Values between cursors A and B (.csv)', action: () => this.exportCsv() },
      ]);
    }

    // the window as drawn, with the names column, as SVG: draw() runs on a context that records SVG
    exportSvg() {
      const W = this.plot.clientWidth, H = parseFloat(this.cv.style.height) || 100, nw = this.names.clientWidth;
      const css = getComputedStyle(this.root), col = (n) => css.getPropertyValue(n).trim();
      const g = new SvgContext();
      g.fillStyle = col('--panel') || '#111'; g.fillRect(0, 0, W + nw, H);
      g.font = '12px ' + col('--mono'); g.textBaseline = 'middle'; g.fillStyle = col('--text');
      let y = AXIS;
      for (const r of this.visible()) {
        const rh = r.header ? RH : this.rowH(r);
        g.fillText(r.header ? r.header.toUpperCase() : (r.bit != null ? '  [' + r.bit + ']' : r.label), 8, y + rh / 2);
        y += rh;
      }
      g.translate(nw, 0);
      g.clip(W, H);
      this.draw(g);
      const svg = g.svg(W + nw, H);
      S.UI.download((this.app.doc.d.name || 'waves') + '-waves.svg', svg, 'image/svg+xml');
      return svg;
    }

    // FST from the server (its writer is checked against GTKWave's own reader), on the clock's real time
    async exportFst() {
      if (!this.rows.length) { S.UI.toast('Add signals to the waves first.'); return; }
      const paths = [...new Set(this.rows.filter((x) => !x.stream).map((x) => x.path))];
      const r = await S.Api.call('fst', { paths });
      const bin = atob(r.fst), bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
      a.download = this.app.doc.d.name + '.fst';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 4000);
      S.UI.toast(paths.length + ' signals as FST, ' + r.perCycle + ' ' + r.unit + ' a cycle.');
      return r;
    }

    // the window as drawn, with the names column, at device resolution
    exportPng() {
      const names = this.names, W = this.cv.width, H = this.cv.height, dpr = devicePixelRatio || 1, nw = Math.round(names.clientWidth * dpr);
      const out = document.createElement('canvas');
      out.width = W + nw; out.height = H;
      const g = out.getContext('2d'), css = getComputedStyle(this.root);
      g.fillStyle = css.getPropertyValue('--panel').trim() || '#111'; g.fillRect(0, 0, out.width, out.height);
      g.drawImage(this.cv, nw, 0);
      g.scale(dpr, dpr);
      g.font = '12px ' + css.getPropertyValue('--mono'); g.textBaseline = 'middle'; g.fillStyle = css.getPropertyValue('--text').trim();
      let y = AXIS;
      for (const r of this.visible()) {
        const rh = r.header ? RH : this.rowH(r);
        g.fillText(r.header ? r.header.toUpperCase() : (r.bit != null ? '  [' + r.bit + ']' : r.label), 8, y + rh / 2);
        y += rh;
      }
      out.toBlob((b) => { const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = (this.app.doc.d.name || 'waves') + '-waves.png'; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 4000); }, 'image/png');
    }

    // every change of the shown signals between the cursors (or the whole run), one row per cycle that changed
    async exportCsv() {
      if (!this.rows.length) { S.UI.toast('Add signals to the waves first.'); return; }
      const a = Math.min(this.cursor ?? 0, this.cursorB ?? this.head), b = Math.max(this.cursor ?? 0, this.cursorB ?? this.head);
      const paths = [...new Set(this.rows.filter((r) => r.bit == null && !r.stream).map((r) => r.path))];
      const r = await S.Api.call('waves', { paths, from: a, to: b, columns: 1e9 });
      const cycles = new Set([a]);
      for (const p of paths) for (const [c] of (r.waves[p] || {}).changes || []) if (c >= a && c <= b) cycles.add(c);
      const sorted = [...cycles].sort((x, y) => x - y);
      const valAt = (p, c) => { let v = ''; for (const [cc, vv] of (r.waves[p] || {}).changes || []) { if (cc <= c) v = vv; else break; } return v; };
      const lines = ['cycle,' + paths.map((p) => '"' + p + '"').join(','), ...sorted.map((c) => c + ',' + paths.map((p) => '0x' + valAt(p, c)).join(','))];
      S.UI.download((this.app.doc.d.name || 'waves') + '-' + a + '-' + b + '.csv', lines.join('\n') + '\n', 'text/csv');
      S.UI.toast(sorted.length + ' rows, cycles ' + a + ' to ' + b + '.');
    }

    // period, frequency and duty of a bit between A and B; for a bus, its transitions and most common values
    async measure() {
      const r = this.rows.find((x) => this.sel.has(this.key(x)));
      if (!r) { S.UI.toast('Select a signal first, then place cursors A (click) and B (shift-click).'); return; }
      if (this.cursor == null || this.cursorB == null) { S.UI.toast('Place cursor A (click) and cursor B (shift-click) around what to measure.'); return; }
      const a = Math.min(this.cursor, this.cursorB), b = Math.max(this.cursor, this.cursorB);
      const res = await S.Api.call('waves', { paths: [r.path], from: a, to: b, columns: 1e9 });
      const ch = ((res.waves[r.path] || {}).changes || []).map(([c, v]) => [c, r.bit != null ? (BigInt('0x' + v) >> BigInt(r.bit)) & 1n : BigInt('0x' + v)]);
      // the changes inside (a, b], each with the value it changes to
      const seq = []; let last = null;
      for (const [c, v] of ch) { if (v !== last) { seq.push([Math.max(c, a), v]); last = v; } }
      const inside = seq.filter(([c]) => c > a && c <= b), span = b - a;
      const t = (cyc) => this.hz ? ' (' + this.timeLabel(cyc, 1) + ')' : '';
      let text;
      if ((r.bit != null ? 1 : r.width) === 1) {
        const rises = inside.filter(([, v]) => v === 1n).map(([c]) => c);
        let high = 0;
        for (let i = 0; i < seq.length; i++) { const c0 = Math.max(seq[i][0], a), c1 = i + 1 < seq.length ? Math.min(seq[i + 1][0], b) : b; if (seq[i][1] === 1n && c1 > c0) high += c1 - c0; }
        const periods = rises.slice(1).map((c, i) => c - rises[i]);
        const per = periods.length ? periods.reduce((x, y) => x + y, 0) / periods.length : null;
        text = r.label + (r.bit != null ? '[' + r.bit + ']' : '') + ', cycles ' + a + '–' + b + ': ' + inside.length + ' transitions, ' + rises.length + ' rising; '
          + (per ? 'period ' + (Number.isInteger(per) ? per : per.toFixed(2)) + ' cycles' + t(per) + (this.hz ? ', ' + (this.hz / per).toPrecision(4) + ' Hz' : '') + '; ' : 'no full period; ')
          + 'high ' + (100 * high / Math.max(1, span)).toFixed(1) + '% of the time.';
      } else {
        const count = new Map();
        for (let i = 0; i < seq.length; i++) { const c0 = Math.max(seq[i][0], a), c1 = i + 1 < seq.length ? Math.min(seq[i + 1][0], b) : b; if (c1 > c0) count.set(seq[i][1], (count.get(seq[i][1]) || 0) + (c1 - c0)); }
        const top = [...count.entries()].sort((x, y) => y[1] - x[1]).slice(0, 5).map(([v, n]) => this.fmt(v, r) + ' ' + (100 * n / Math.max(1, span)).toFixed(0) + '%');
        text = r.label + ', cycles ' + a + '–' + b + ': ' + inside.length + ' changes, ' + count.size + ' distinct values; most of the time ' + top.join(', ') + '.';
      }
      this.app.say(text);
      S.UI.toast(text, 'ok', 9000);
      return text;
    }

    async exportVcd() {
      if (!this.rows.length) { S.UI.toast('Add signals to the waves first.'); return; }
      const paths = [...new Set(this.rows.filter((x) => !x.stream).map((x) => x.path))];
      const r = await S.Api.call('waves', { paths, from: 0, to: this.head, columns: 1e9 });
      const ids = paths.map((_, i) => String.fromCharCode(33 + (i % 90)) + (i >= 90 ? String(Math.floor(i / 90)) : ''));
      // a cycle is 1/hz seconds, in the coarsest unit that holds it exactly
      const [unit, per] = timeUnit(this.hz);
      let s = '$date ' + new Date().toISOString() + ' $end\n$version Tessera Studio $end\n$timescale 1' + unit + ' $end\n$scope module ' + this.app.doc.d.name + ' $end\n';
      paths.forEach((p, i) => { s += `$var wire ${(this.signals && this.signals[p]) || 1} ${ids[i]} ${p.replace(/\W/g, '_')} $end\n`; });
      s += '$upscope $end\n$enddefinitions $end\n';
      const ev = new Map();
      paths.forEach((p, i) => {
        const w = (this.signals && this.signals[p]) || 1;
        for (const [c, v] of (r.waves[p] || {}).changes || []) {
          const bits = BigInt('0x' + v).toString(2);
          if (!ev.has(c)) ev.set(c, []);
          ev.get(c).push(w === 1 ? bits + ids[i] : 'b' + bits + ' ' + ids[i]);
        }
      });
      for (const c of [...ev.keys()].sort((a, b) => a - b)) s += '#' + (BigInt(c) * per) + '\n' + ev.get(c).join('\n') + '\n';
      S.UI.download(this.app.doc.d.name + '.vcd', s, 'text/plain');
    }
  }

  // [unit, units a cycle] for a clock of hz: s, ms, us, ns, ps or fs, the first that holds 1/hz exactly
  function timeUnit(hz) {
    const names = ['s', 'ms', 'us', 'ns', 'ps', 'fs'];
    const f = BigInt(Math.max(1, Math.round(hz || 1)));
    for (let k = 0; k < names.length; k++) { const p = 10n ** BigInt(3 * k); if (p % f === 0n) return [names[k], p / f]; }
    return ['fs', 10n ** 15n / f || 1n];
  }

  // the parts of the Canvas 2D interface the waves use, recorded as SVG
  class SvgContext {
    constructor() {
      this.out = []; this.path = ''; this.fillStyle = '#000'; this.strokeStyle = '#000'; this.lineWidth = 1; this.globalAlpha = 1;
      this.font = '11px monospace'; this.textBaseline = 'middle'; this.dash = []; this.stack = []; this.dx = 0; this.clipW = null;
      this.meter = document.createElement('canvas').getContext('2d');
    }
    esc(t) { return String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
    n(v) { return Math.round(v * 100) / 100; }
    setTransform() {} clearRect() {}
    translate(x) { this.dx += x; }
    clip(w, h) { this.clipW = [this.dx, w, h]; this.clipAt = this.out.length; }
    save() { this.stack.push([this.fillStyle, this.strokeStyle, this.lineWidth, this.globalAlpha, this.font, this.textBaseline, this.dash]); }
    restore() { const s = this.stack.pop(); if (s) [this.fillStyle, this.strokeStyle, this.lineWidth, this.globalAlpha, this.font, this.textBaseline, this.dash] = s; }
    setLineDash(d) { this.dash = d || []; }
    beginPath() { this.path = ''; }
    moveTo(x, y) { this.path += 'M' + this.n(x + this.dx) + ' ' + this.n(y); }
    lineTo(x, y) { this.path += 'L' + this.n(x + this.dx) + ' ' + this.n(y); }
    closePath() { this.path += 'Z'; }
    arc(x, y, r) { const cx = x + this.dx; this.path += `M${this.n(cx - r)} ${this.n(y)}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`; }
    alpha() { return this.globalAlpha < 1 ? ` opacity="${this.globalAlpha}"` : ''; }
    stroke() { if (this.path) this.out.push(`<path d="${this.path}" fill="none" stroke="${this.esc(this.strokeStyle)}" stroke-width="${this.lineWidth}"${this.dash.length ? ` stroke-dasharray="${this.dash.join(' ')}"` : ''}${this.alpha()}/>`); }
    fill() { if (this.path) this.out.push(`<path d="${this.path}" fill="${this.esc(this.fillStyle)}"${this.alpha()}/>`); }
    fillRect(x, y, w, h) { this.out.push(`<rect x="${this.n(x + this.dx)}" y="${this.n(y)}" width="${this.n(w)}" height="${this.n(h)}" fill="${this.esc(this.fillStyle)}"${this.alpha()}/>`); }
    strokeRect(x, y, w, h) { this.out.push(`<rect x="${this.n(x + this.dx)}" y="${this.n(y)}" width="${this.n(w)}" height="${this.n(h)}" fill="none" stroke="${this.esc(this.strokeStyle)}" stroke-width="${this.lineWidth}"${this.dash.length ? ` stroke-dasharray="${this.dash.join(' ')}"` : ''}${this.alpha()}/>`); }
    measureText(t) { this.meter.font = this.font; return this.meter.measureText(t); }
    fillText(t, x, y) {
      const m = /([\d.]+)px\s+(.*)/.exec(this.font) || [0, 11, 'monospace'];
      this.out.push(`<text x="${this.n(x + this.dx)}" y="${this.n(y)}" font-size="${m[1]}" font-family="${this.esc(m[2])}" dominant-baseline="${this.textBaseline === 'middle' ? 'central' : 'auto'}" fill="${this.esc(this.fillStyle)}"${this.alpha()}>${this.esc(t)}</text>`);
    }
    svg(W, H) {
      const [x0, w, h] = this.clipW || [0, W, H];
      // what the canvas would have cut at its edge is clipped the same way
      const at = this.clipW ? this.clipAt : this.out.length, head = this.out.slice(0, at), body = this.out.slice(at);
      return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><defs><clipPath id="plot"><rect x="${x0}" y="0" width="${w}" height="${h}"/></clipPath></defs>`
        + head.join('') + `<g clip-path="url(#plot)">` + body.join('') + '</g></svg>\n';
    }
  }

  S.Waves = Waves;
  S.SvgContext = SvgContext;
})(globalThis.Studio = globalThis.Studio || {});
