// Tessera Studio: the inspector. What is selected, edited in place: a part's parameters (checked as
// you type, applied as one undo step), its pins with widths and live values, and the Java it makes;
// a wire's name, width, driver and readers; with nothing selected, the design itself.
(function (S) {
  'use strict';
  const { h, fill } = S.UI;

  class Inspector {
    constructor(host, app) {
      this.app = app;
      this.root = h('div.insp');
      host.append(this.root);
      this.render();
    }

    render() {
      const sel = this.app.canvas ? this.app.canvas.sel : new Set();
      const parts = [...sel].filter((k) => k[0] === 'p').map((k) => k.slice(2));
      const nets = [...sel].filter((k) => k[0] === 'w').map((k) => k.slice(2));
      if (parts.length === 1) this.part(this.app.doc.part(parts[0]));
      else if (!parts.length && nets.length === 1) this.net(nets[0]);
      else if (parts.length > 1 || nets.length > 1) this.many(parts, nets);
      else this.design();
    }

    // live values change often: update only the value cells
    values() {
      for (const td of this.root.querySelectorAll('td.v[data-net]')) {
        const net = td.getAttribute('data-net'), w = Number(td.getAttribute('data-w')) || 1;
        const v = this.app.canvas.values.get(net);
        td.textContent = v === undefined || !this.app.canvas.live ? '—' : this.app.canvas.fmt(v, w);
      }
      if (this.mem && this.mem.id === this.partId) this.memFetch(false);
      // a state machine lights its state and the transition it takes next
      const fd = this.root.querySelector('.fsm-d');
      if (fd && this.fsm && this.fsm.id === this.partId) {
        const v = this.app.canvas.values.get('@' + this.partId), live = this.app.canvas.live && v !== undefined;
        for (const g of fd.querySelectorAll('.fsm-s')) g.classList.toggle('now', live && BigInt(g.getAttribute('data-s')) === v);
        let next = -1;
        if (live) {
          const cur = this.fsm.states[Number(v)];
          const val = (name) => { const net = this.app.doc.netOf(this.partId, name); const x = net ? this.app.canvas.values.get(net) : undefined; return x === undefined ? undefined : x !== 0n; };
          next = this.fsm.trans.findIndex((t) => t.from === cur && Inspector.cond(t.cond, val) === true);
        }
        for (const el of fd.querySelectorAll('[data-t]')) el.classList.toggle('next', Number(el.getAttribute('data-t')) === next);
      }
      // a truth table lights the row of its input now
      const tt = this.root.querySelector('table.tt');
      if (tt && this.partId) {
        const p = this.app.doc.part(this.partId), ins = p ? String(p.params.inputs || '').trim().split(/\s+/).filter(Boolean) : [];
        let k = 0, known = this.app.canvas.live && ins.length > 0;
        for (const x of ins) {
          const net = this.app.doc.netOf(this.partId, x), v = net ? this.app.canvas.values.get(net) : undefined;
          if (v === undefined) { known = false; break; }
          k = k * 2 + Number(v & 1n);
        }
        for (const tr of tt.querySelectorAll('tr[data-row]')) tr.classList.toggle('now', known && Number(tr.getAttribute('data-row')) === k);
      }
    }

    design() {
      const d = this.app.doc.d, gen = this.app.gen || {};
      const name = h('input.input', { value: d.name, 'aria-label': 'Class name' });
      const err = h('div.err');
      name.addEventListener('change', () => {
        if (!/^[A-Za-z_]\w*$/.test(name.value)) { err.textContent = 'A Java class name: a letter or _, then letters, digits or _.'; name.classList.add('bad'); return; }
        err.textContent = ''; name.classList.remove('bad');
        this.app.doc.edit('rename', () => { d.name = name.value; });
      });
      const title = h('input.input', { value: d.title || '', placeholder: 'What it does, in a few words' });
      title.addEventListener('change', () => this.app.doc.edit('title', () => { d.title = title.value; }));
      const pkg = h('input.input', { value: d.package || 'studio' });
      pkg.addEventListener('change', () => this.app.doc.edit('package', () => { d.package = pkg.value || 'studio'; }));
      const ports = (gen.ports || []).filter((p) => !p.clockOrReset);
      fill(this.root, 
        h('h2', S.UI.icon('cpu'), d.name), h('div.sub', 'The module this drawing makes'),
        h('div.field', h('label', 'Class'), name, err), h('div.field', h('label', 'Description'), title), h('div.field', h('label', 'Package'), pkg),
        h('div.sec', 'Ports'),
        ports.length ? h('table', ports.map((p) => h('tr', h('td', p.dir === 'in' ? '→ ' + p.name : p.name + ' →'), h('td.t', p.width === 1 ? 'Bool' : 'UInt<' + p.width + '>')))) : h('div.help', 'No ports yet: add an input and an output from the palette.'),
        h('div.sec', 'Size'),
        h('table', h('tr', h('td', 'Parts'), h('td.t', d.parts.length)), h('tr', h('td', 'Wires'), h('td.t', d.nets.length)),
          h('tr', h('td', 'Elaborated in'), h('td.t', gen.elaborateMs != null ? gen.elaborateMs + ' ms' : '—'))),
        this.viewPrefs());
    }

    // how drawings look, for this browser: overbars, activity heat, symbol style
    viewPrefs() {
      const v = this.app.view;
      const check = (key, label, help) => {
        const c = h('input', { type: 'checkbox', checked: !!v[key] });
        c.addEventListener('change', () => this.app.setView({ [key]: c.checked }));
        return h('div.field', h('label.check', c, label), h('div.help', help));
      };
      const sym = h('select.input', ['ansi', 'iec'].map((x) => h('option', { value: x, selected: v.symbols === x }, x === 'ansi' ? 'ANSI (distinctive shapes)' : 'IEC 60617 (rectangles with &, ≥1, =1)')));
      sym.addEventListener('change', () => this.app.setView({ symbols: sym.value }));
      return h('div', h('div.sec', 'View'),
        check('overbars', 'Active-low names with a bar', 'rst_n, cs_b and oe_l are drawn as their base name with a bar over it; the name as written is in the tooltip.'),
        check('heat', 'Activity heat', 'Wires are tinted by how often their value changed in the last 256 cycles: cool when quiet, warm when busy.'),
        h('div.field', h('label', 'Gate symbols'), sym));
    }

    part(p) {
      this.partId = p.id;
      const t = S.Parts.get(p.type) || { title: p.type, params: [], help: '' };
      const form = h('div');
      for (const spec of t.params) form.append(this.field(p, spec));
      const g = S.Geom.of(p), widths = this.app.canvas.widths;
      const rows = g.pins.map((q) => {
        const net = this.app.doc.netOf(p.id, q.name), w = net ? widths[net] : undefined;
        return h('tr', h('td', (q.dir === 'in' ? '← ' : '→ ') + q.name), h('td.t', w ? (w === 1 ? 'Bool' : 'UInt<' + w + '>') : net ? '' : 'open'),
          h('td.v', { 'data-net': net || '', 'data-w': w || 1 }, '—'));
      });
      const lines = (this.app.gen && this.app.gen.lines && this.app.gen.lines[p.id]) || [];
      const javaLines = (this.app.gen && this.app.gen.java || '').split('\n');
      const code = p.params.expr ? p.params.expr : lines.map((n) => javaLines[n - 1].trim()).join('\n');
      const probs = (this.app.gen && this.app.gen.problems || []).filter((x) => (x.parts || []).includes(p.id));
      const view = this.app.viewMode;
      fill(this.root,
        h('h2', p.params.signal || p.params.name || t.title, h('span.pill', p.id)),
        h('div.sub', view ? (p.type === 'expr' ? 'The logic that drives ' + (p.params.signal || 'this value')
          : (p.type === 'reg' ? 'Register' : p.type === 'in' ? 'Input port' : p.type === 'out' ? 'Output port' : p.type === 'mem' ? 'Memory' : 'Part') + ', ' + (p.params.width || 1) + ' bit' + ((p.params.width || 1) > 1 ? 's' : '') + '. Double-click to trace what drives it; right-click for more.') : t.help),
        view ? null : form,
        p.type === 'table' && !view ? this.truthTable(p) : null,
        p.type === 'fsm' ? this.fsmDiagram(p) : null,
        p.type === 'module' && !view ? this.moduleForm(p) : null,
        p.type === 'mem' ? this.memView(p) : null,
        h('div.sec', 'Pins'), h('table', rows),
        probs.length ? h('div', h('div.sec', 'Problems'), probs.map((x) => this.app.problems.item(x))) : null,
        code ? h('div', h('div.sec', p.params.expr ? 'Expression' : 'Java'), h('pre.code', code)) : null,
        h('div.sec', 'Place'),
        h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap' } },
          S.UI.button('Turn', { icon: 'rotate', title: 'Turn a quarter clockwise (R)', onclick: () => this.app.doc.rotate([p.id], 90) }),
          S.UI.button('Mirror', { icon: 'mirror', title: 'Mirror left to right (M)', onclick: () => this.app.doc.mirror([p.id]) }),
          S.UI.button('Delete', { icon: 'trash', title: 'Delete (Del)', onclick: () => this.app.doc.remove([p.id]) })));
      this.values();
    }

    // a truth table part as a grid of every input combination: an output cell toggles on click (rows are then
    // written out in full); a table with don't-cares (-) is shown evaluated and edited as text
    truthTable(p) {
      const ins = String(p.params.inputs || '').trim().split(/\s+/).filter(Boolean), outs = String(p.params.outputs || '').trim().split(/\s+/).filter(Boolean);
      const rows = String(p.params.rows || '').split('\n').map((l) => l.split('|')).filter((x) => x.length === 2)
        .map(([a, b]) => [a.trim().split(/\s+/), b.trim().split(/\s+/)]).filter(([a, b]) => a.length === ins.length && b.length === outs.length);
      const n = ins.length;
      if (!n || n > 8 || !outs.length) return h('div', h('div.sec', 'Truth table'), h('div.help', n > 8 ? 'More than 8 inputs: edit the rows as text.' : 'Name its inputs and outputs first.'));
      const dontCare = rows.some(([a]) => a.includes('-'));
      const value = (k) => {     // the outputs for input combination k (inputs left to right, first most significant)
        const bits = ins.map((_, i) => (k >> (n - 1 - i)) & 1);
        const r = rows.find(([a]) => a.every((x, i) => x === '-' || Number(x) === bits[i]));
        return r ? r[1].map(Number) : outs.map(() => 0);
      };
      const write = (k, j) => {
        const lines = [];
        for (let c = 0; c < 1 << n; c++) {
          const v = value(c);
          if (c === k) v[j] ^= 1;
          if (v.some((x) => x)) lines.push(ins.map((_, i) => (c >> (n - 1 - i)) & 1).join(' ') + ' | ' + v.join(' '));
        }
        this.app.setParams(p.id, { rows: lines.join('\n') });
      };
      const tbody = h('tbody');
      for (let k = 0; k < 1 << n; k++) {
        const v = value(k);
        tbody.append(h('tr', { 'data-row': k },
          ...ins.map((_, i) => h('td.t', String((k >> (n - 1 - i)) & 1))),
          ...v.map((x, j) => h('td', dontCare ? h('span.tt-bit.ro' + (x ? '.on' : ''), String(x))
            : h('button.tt-bit' + (x ? '.on' : ''), { type: 'button', title: outs[j] + ' = ' + (x ? 0 : 1) + ' here', onclick: () => write(k, j) }, String(x))))));
      }
      return h('div', h('div.sec', 'Truth table'),
        h('table.tt', h('thead', h('tr', ...ins.map((x) => h('th', x)), ...outs.map((x) => h('th.o', x)))), tbody),
        h('div.help', dontCare ? 'The rows use don’t-cares (-), so this grid shows the function they give; edit the rows above.' : 'Click an output to flip it. While the design runs, the row of the inputs now is lit.'));
    }

    // a memory's contents at the cycle shown, as a hex grid that follows the run; changed words flash
    memView(p) {
      const prefix = this.app.doc.path.map((x) => x.inst + '.').join('');
      const path = prefix + (p.params.name || 'm'), w = Number(p.params.width) || 8, depth = Number(p.params.depth) || 16;
      const perRow = w <= 8 ? 16 : w <= 16 ? 8 : w <= 32 ? 4 : 2, digits = Math.ceil(w / 4), rows = 16;
      const at = h('input.input.bval', { value: '0', spellcheck: 'false', 'aria-label': 'First address shown', title: 'First address shown (decimal or 0x…)' });
      const grid = h('div.memgrid', { style: { '--cols': perRow } });
      const note = h('div.help');
      const box = h('div', h('div.sec', 'Contents'), h('div', { style: { display: 'flex', gap: '6px', alignItems: 'center', marginBottom: '6px' } },
        h('span', { style: { color: 'var(--muted)' } }, 'From'), at, h('span', { style: { color: 'var(--faint)' } }, depth + ' words of ' + w + ' bits')), grid, note);
      this.mem = { id: p.id, path, from: 0, count: perRow * rows, perRow, digits, grid, note, last: null, t: 0 };
      at.addEventListener('change', () => {
        let a; try { a = Number(BigInt(at.value.trim())); } catch (e) { a = NaN; }
        if (!(a >= 0 && a < depth)) { note.textContent = 'An address from 0 to ' + (depth - 1) + '.'; return; }
        this.mem.from = Math.floor(a / perRow) * perRow; this.mem.last = null; this.memFetch(true);
      });
      this.memFetch(true);
      return box;
    }

    // write one word now; the history keeps it, so going back and forward again replays it
    async memWrite(m, r, addr, cur) {
      const max = (1n << BigInt(r.width)) - 1n;
      const v = await S.UI.prompt('Write ' + m.path + '[' + addr + ']', 'Value (decimal, 0x… or 0b…)', '0x' + cur, (t) => {
        try { const x = BigInt(t.trim()); if (x < 0n || x > max) return 'A ' + r.width + '-bit word is 0 to ' + max + '.'; } catch { return 'Give a number: decimal, 0x… or 0b….'; }
        return null;
      });
      if (v == null) return;
      try { await S.Api.call('sim', { cmd: 'memwrite', path: m.path, addr, value: BigInt(v.trim()).toString(16) }); }
      catch (e) { S.UI.toast(e.message, 'err', 6000); return; }
      this.memFetch(true);
    }

    async memFetch(force) {
      const m = this.mem;
      if (!m || m.id !== this.partId) return;
      const now = performance.now();
      if (!force && (m.busy || now - m.t < 200)) return;   // five times a second is enough to watch
      m.busy = true; m.t = now;
      let r;
      try { r = await S.Api.call('mem', { path: m.path, from: m.from, count: m.count }); }
      catch (e) { m.busy = false; m.note.textContent = e.message; return; }
      m.busy = false;
      if (this.mem !== m) return;
      const rows = [];
      for (let i = 0; i < r.words.length; i += m.perRow) {
        const cells = r.words.slice(i, i + m.perRow).map((v, k) => {
          const changed = m.last && m.last[i + k] !== undefined && m.last[i + k] !== v;
          const addr = r.from + i + k;
          const cell = h('span.mw' + (changed ? '.chg' : '') + (v === '0' ? '.z' : ''), { title: 'address ' + addr + ' (0x' + addr.toString(16) + '): double-click to write it', tabindex: '-1' }, v.padStart(m.digits, '0'));
          cell.addEventListener('dblclick', () => this.memWrite(m, r, addr, v));
          return cell;
        });
        rows.push(h('span.ma', (r.from + i).toString(16).padStart(4, '0')), ...cells);
      }
      fill(m.grid, rows);
      m.note.textContent = 'At cycle ' + r.cycle + '. Words that changed since the last look are lit; double-click a word to write it.';
      m.last = r.words;
    }

    // a Java module: the expression that makes it (elaborated again when it changes) and what it needs to import
    moduleForm(p) {
      const expr = h('input.input', { value: p.params.expr || '', spellcheck: 'false', 'aria-label': 'Java expression' });
      const imps = h('input.input', { value: (p.params.imports || []).join(' '), spellcheck: 'false', 'aria-label': 'Imports' });
      const name = h('input.input', { value: p.params.name || '', spellcheck: 'false', 'aria-label': 'Instance name' });
      const err = h('div.err', { role: 'alert' });
      const again = () => this.app.placeModule({ title: p.params.module, expr: expr.value.trim(), imports: imps.value.trim().split(/\s+/).filter(Boolean) }, null, p.id);
      expr.addEventListener('change', again);
      imps.addEventListener('change', again);
      name.addEventListener('change', () => {
        if (!/^[A-Za-z_]\w*$/.test(name.value)) { err.textContent = 'A name is a letter or _, then letters, digits or _.'; return; }
        err.textContent = '';
        this.app.setParams(p.id, { name: name.value });
      });
      // streams: one pin each, or separate wires (valid, ready, bits) to wire them bit by bit
      const all = S.App.streams({ ...p.params, bundle: true, unbundle: [] });
      const streams = all.size ? h('div.field', h('label', 'Streams'), ...[...all.keys()].map((x) => {
        const on = (p.params.bundle === true || p.params.bundle === 'true') && !(p.params.unbundle || []).includes(x);
        const cb = h('input', { type: 'checkbox', checked: on });
        cb.addEventListener('change', () => {
          const unb = new Set(p.params.unbundle || []);
          if (cb.checked) unb.delete(x); else unb.add(x);
          this.app.setParams(p.id, { bundle: true, unbundle: [...unb] });
        });
        return h('label.check', cb, x + ' as one stream pin (' + (all.get(x) === 'in' ? 'in' : 'out') + ')');
      }), h('div.help', 'A stream wire joins an output stream to an input stream: valid and bits go forward, ready comes back. Untick to wire a stream bit by bit.')) : null;
      return h('div',
        h('div.field', h('label', 'Instance name'), name, err),
        streams,
        h('div.field', h('label', 'Made by (Java)'), expr, h('div.help', 'Any expression that makes a Module; its pins are found by elaborating it. clk is the design’s clock.')),
        h('div.field', h('label', 'Imports'), imps, h('div.help', 'Classes the expression names, separated by spaces.')));
    }

    // a state machine drawn as states on a ring and arrows between them; the state it is in is lit, and so is
    // the transition that the next clock edge takes
    fsmDiagram(p) {
      const states = String(p.params.states || '').trim().split(/\s+/).filter(Boolean);
      const trans = String(p.params.transitions || '').split('\n').map((l) => /^\s*(\w+)\s*->\s*(\w+)\s*(?::\s*(.*))?$/.exec(l)).filter(Boolean)
        .map((m) => ({ from: m[1], to: m[2], cond: (m[3] || '1').trim() }));
      const n = Math.max(1, states.length), W = 340, R = n === 1 ? 0 : Math.min(95, 40 + n * 12), r = 22, H = 2 * R + 2 * r + 40;
      const pos = new Map(states.map((st, i) => { const a = -Math.PI / 2 + 2 * Math.PI * i / n; return [st, [W / 2 + R * Math.cos(a), H / 2 + R * Math.sin(a)]]; }));
      const esc = (x) => String(x).replace(/&/g, '&amp;').replace(/</g, '&lt;');
      let arrows = '', labels = '', x0 = W / 2 - R - r - 8, x1 = W / 2 + R + r + 8;
      const ext = (a, b) => { x0 = Math.min(x0, a - 4); x1 = Math.max(x1, b + 4); };
      trans.forEach((t, k) => {
        const a = pos.get(t.from), b = pos.get(t.to);
        if (!a || !b) return;
        const cls = 'fsm-t" data-t="' + k;
        if (t.from === t.to) {   // a loop above the state, away from the centre
          const dx = a[0] - W / 2, dy = a[1] - H / 2, L = Math.hypot(dx, dy) || 1, ux = dx / L || 0, uy = L === 1 ? -1 : dy / L;
          const cx = a[0] + ux * (r + 14), cy = a[1] + uy * (r + 14);
          arrows += `<path class="${cls}" d="M${a[0] + ux * r - uy * 8} ${a[1] + uy * r + ux * 8} Q${cx + -uy * 26} ${cy + ux * 26} ${cx} ${cy} Q${cx - -uy * 26} ${cy - ux * 26} ${a[0] + ux * r + uy * 8} ${a[1] + uy * r - ux * 8}" marker-end="url(#fsm-arrow)"/>`;
          // the label sits beyond the loop, anchored so that it grows away from the ring and stays in view
          const anchor = ux > 0.35 ? 'start' : ux < -0.35 ? 'end' : 'middle';
          const lx = cx + ux * 16, tw = t.cond.length * 6.2;
          ext(anchor === 'start' ? lx : anchor === 'end' ? lx - tw : lx - tw / 2, anchor === 'start' ? lx + tw : anchor === 'end' ? lx : lx + tw / 2);
          labels += `<text class="fsm-l" data-t="${k}" x="${lx}" y="${cy + uy * 16}" text-anchor="${anchor}" dominant-baseline="central">${esc(t.cond)}</text>`;
          return;
        }
        const dx = b[0] - a[0], dy = b[1] - a[1], L = Math.hypot(dx, dy), ux = dx / L, uy = dy / L;
        const back = trans.some((q) => q.from === t.to && q.to === t.from);
        const bend = back ? 18 : 0, mx = (a[0] + b[0]) / 2 - uy * bend, my = (a[1] + b[1]) / 2 + ux * bend;
        const s0 = [a[0] + ux * r, a[1] + uy * r], s1 = [b[0] - ux * (r + 3), b[1] - uy * (r + 3)];
        arrows += `<path class="${cls}" d="M${s0[0]} ${s0[1]} Q${mx * 2 - (s0[0] + s1[0]) / 2} ${my * 2 - (s0[1] + s1[1]) / 2} ${s1[0]} ${s1[1]}" marker-end="url(#fsm-arrow)"/>`;
        const tw = (t.cond === '1' ? 0 : t.cond.length) * 6.2;
        ext(mx - uy * 9 - tw / 2, mx - uy * 9 + tw / 2);
        labels += `<text class="fsm-l" data-t="${k}" x="${mx - uy * 9}" y="${my + ux * 9}" text-anchor="middle" dominant-baseline="central">${esc(t.cond === '1' ? '' : t.cond)}</text>`;
      });
      let nodes = '';
      states.forEach((st, i) => {
        const [x, y] = pos.get(st);
        nodes += `<g class="fsm-s" data-s="${i}"><circle cx="${x}" cy="${y}" r="${r}"/>${i === 0 ? `<circle class="fsm-reset" cx="${x}" cy="${y}" r="${r - 4}"/>` : ''}<text x="${x}" y="${y}" text-anchor="middle" dominant-baseline="central">${esc(st.length > 6 ? st.slice(0, 5) + '…' : st)}</text><title>${esc(st)}${i === 0 ? ' (after reset)' : ''}</title></g>`;
      });
      const svg = h('div.fsm-d');
      svg.innerHTML = `<svg viewBox="${x0} 0 ${x1 - x0} ${H}" width="100%" style="max-height:${H * 1.6}px" role="img" aria-label="State diagram"><defs><marker id="fsm-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="context-stroke"/></marker></defs>${arrows}${nodes}${labels}</svg>`;
      this.fsm = { id: p.id, states, trans, inputs: String(p.params.inputs || '').trim().split(/\s+/).filter(Boolean) };
      return h('div', h('div.sec', 'State diagram'), svg, h('div.help', 'The double ring is the state after reset. While it runs, the state it is in is lit, and so is the transition the next clock edge takes.'));
    }

    // evaluate a transition condition with the inputs' values now (the same grammar as the Java side)
    static cond(text, val) {
      let i = 0;
      const sk = () => { while (i < text.length && /\s/.test(text[i])) i++; };
      const eat = (t) => { sk(); if (text.startsWith(t, i)) { i += t.length; return true; } return false; };
      const or = () => { let a = xor(); while (eat('||') || eat('|')) { const b = xor(); a = a || b; } return a; };
      const xor = () => { let a = and(); while (eat('^')) { const b = and(); a = a !== b; } return a; };
      const and = () => { let a = not(); while (eat('&&') || eat('&')) { const b = not(); a = a && b; } return a; };
      const not = () => {
        if (eat('!') || eat('~')) return !not();
        if (eat('(')) { const e = or(); eat(')'); return e; }
        sk(); const m = /^\w+/.exec(text.slice(i)); if (!m) return null; i += m[0].length;
        if (m[0] === '1' || m[0] === 'true') return true;
        if (m[0] === '0' || m[0] === 'false') return false;
        const v = val(m[0]); return v === undefined ? null : v;
      };
      try { const r = or(); return r === null ? null : r; } catch (e) { return null; }
    }

    field(p, spec) {
      const v = p.params[spec.name];
      const err = h('div.err', { role: 'alert' });
      let input;
      const apply = (val) => {
        const msg = this.check(p, spec, val);
        err.textContent = msg || '';
        input.classList.toggle('bad', !!msg);
        if (msg) return;
        this.app.setParams(p.id, { [spec.name]: val });
      };
      if (spec.kind === 'bool') {
        input = h('input', { type: 'checkbox', checked: v === true || v === 'true' });
        input.addEventListener('change', () => apply(input.checked));
        return h('div.field', h('label.check', input, spec.label), spec.help ? h('div.help', spec.help) : null, err);
      }
      if (spec.name === 'domain') {   // the clock domains this drawing declares
        const names = this.app.doc.d.parts.filter((q) => q.type === 'domain' && q.params.name && q.params.name !== 'clock').map((q) => q.params.name);
        if (v && !names.includes(v)) names.push(v);
        input = h('select.input', h('option', { value: '', selected: !v }, 'the design’s clock'), names.map((c) => h('option', { value: c, selected: v === c }, c)));
        input.addEventListener('change', () => apply(input.value));
      } else if (spec.kind === 'enum') {
        input = h('select.input', (spec.choices || []).map((c) => h('option', { value: c, selected: String(v) === c }, c)));
        input.addEventListener('change', () => apply(input.value));
      } else if (spec.kind === 'lines') {
        input = h('textarea.input.lines', { spellcheck: 'false', rows: Math.max(3, String(v ?? '').split('\n').length + 1) }, v ?? '');
        input.addEventListener('change', () => apply(input.value));
        input.addEventListener('keydown', (e) => e.stopPropagation());   // typing here is not a canvas shortcut
      } else {
        input = h('input.input', { value: v ?? '', inputmode: spec.kind === 'int' || spec.kind === 'seconds' ? 'decimal' : null, spellcheck: 'false' });
        const conv = (s) => spec.kind === 'int' ? (s.trim() === '' ? NaN : Number(s)) : spec.kind === 'seconds' ? Number(s) : s;
        input.addEventListener('input', () => { const msg = this.check(p, spec, conv(input.value)); err.textContent = msg || ''; input.classList.toggle('bad', !!msg); });
        input.addEventListener('change', () => apply(conv(input.value)));
      }
      return h('div.field', h('label', spec.label), input, spec.help ? h('div.help', spec.help) : null, err);
    }

    check(p, spec, v) {
      if (spec.kind === 'int') {
        if (!Number.isInteger(v)) return 'Give a whole number.';
        if (spec.min != null && v < spec.min) return 'At least ' + spec.min + '.';
        if (spec.max != null && v > spec.max) return 'At most ' + spec.max + '.';
      }
      if (spec.kind === 'seconds' && !(v > 0)) return 'Give a time in seconds, more than 0.';
      if (spec.kind === 'name') {
        if (!/^[A-Za-z_]\w*$/.test(v)) return 'A name is a letter or _, then letters, digits or _.';
        const clash = this.app.doc.d.parts.find((q) => q.id !== p.id && ['in', 'out', 'reg', 'mem'].includes(q.type) && q.params.name === v);
        if (clash) return 'Another part is already called ' + v + '.';
      }
      const num = /^(0x[0-9a-f_]+|0b[01_]+|[0-9_]+)$/i;
      if (spec.kind === 'text' && (spec.name === 'value' || spec.name === 'init') && !['mem'].includes(p.type) && !num.test(String(v).trim())) return 'A number: decimal, 0x… hex or 0b… binary.';
      if (spec.kind === 'text' && p.type === 'mem' && spec.name === 'init') {
        const bad = String(v).trim().split(/[\s,;]+/).filter(Boolean).find((x) => !num.test(x));
        if (bad) return '“' + bad + '” is not a number: use decimal, 0x… or 0b…, separated by spaces or commas.';
      }
      return null;
    }

    net(id) {
      const n = this.app.doc.net(id);
      if (!n) { this.design(); return; }
      const w = this.app.canvas.widths[id];
      const name = h('input.input', { value: n.name || '', placeholder: 'unnamed' });
      const err = h('div.err');
      name.addEventListener('change', () => {
        const v = name.value.trim();
        if (v && !/^[A-Za-z_]\w*$/.test(v)) { err.textContent = 'A Java name: a letter or _, then letters, digits or _.'; return; }
        err.textContent = '';
        this.app.doc.nameNet(id, v);
      });
      const pins = n.pins.map(([pid, pin]) => {
        const p = this.app.doc.part(pid), spec = p && S.Geom.pin(p, pin);
        const t = p && S.Parts.get(p.type);
        const label = p ? (t ? t.title : p.type) + (p.params.name ? ' ' + p.params.name : ' ' + p.id) + ' · ' + pin : pid + '.' + pin;
        return h('tr', h('td', spec && spec.dir === 'out' ? 'driver' : 'reads'), h('td', label));
      });
      const path = this.app.gen && this.app.gen.netPaths && this.app.gen.netPaths[id];
      fill(this.root, 
        h('h2', S.UI.icon('wave'), n.name || 'Wire', h('span.pill', id)),
        h('div.sub', w ? (w === 1 ? 'One bit (Bool)' : w + ' bits (UInt<' + w + '>)') : 'Width not known yet'),
        h('div.field', h('label', 'Name (becomes the Java variable)'), name, err),
        h('table', h('tr', h('td', 'Value'), h('td.v', { 'data-net': id, 'data-w': w || 1 }, '—')), h('tr', h('td', 'Signal'), h('td.t', path || '—'))),
        h('div.sec', 'Connections'), h('table', pins),
        h('div', { style: { display: 'flex', gap: '6px', marginTop: '10px' } },
          S.UI.button(this.app.waves && this.app.waves.has(path) ? 'In the waves' : 'Add to waves', { icon: 'probe', title: 'Probe this wire (W)', onclick: () => this.app.probe(id) }),
          S.UI.button('Delete', { icon: 'trash', onclick: () => this.app.doc.remove([], [id]) })));
      this.values();
    }

    many(parts, nets) {
      fill(this.root, h('h2', parts.length + ' parts, ' + nets.length + ' wires'), h('div.sub', 'Selected'),
        h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap' } },
          S.UI.button('Turn', { icon: 'rotate', onclick: () => this.app.doc.rotate(parts, 90) }),
          S.UI.button('Mirror', { icon: 'mirror', onclick: () => this.app.doc.mirror(parts) }),
          S.UI.button('Delete', { icon: 'trash', onclick: () => this.app.doc.remove(parts, nets) })));
    }
  }

  S.Inspector = Inspector;
})(globalThis.Studio = globalThis.Studio || {});
