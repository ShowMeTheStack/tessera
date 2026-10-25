// Tessera Studio: the parts palette. Categories of real symbols (the same markup as on the canvas),
// a search box (Enter places the first match), drag onto the canvas or click to place in the middle.
(function (S) {
  'use strict';
  const { h } = S.UI;

  function thumb(type, t) {
    const isBlock = type.startsWith('block:');
    const p = isBlock ? { id: 'thumb', type: 'block', x: 0, y: 0, rot: 0, mir: false, params: { block: type.slice(6), name: '' }, pins: S.app.doc.blockPins(type.slice(6)) }
      : type === 'code:new' ? { id: 'thumb', type: 'code', x: 0, y: 0, rot: 0, mir: false, params: { cls: '{ Java }', name: '' }, pins: [] }
      : type.startsWith('module:') ? { id: 'thumb', type: 'module', x: 0, y: 0, rot: 0, mir: false, params: { module: t ? t.title : 'Module', name: '' }, pins: [] }
      : { id: 'thumb', type, x: 0, y: 0, rot: 0, mir: false, params: S.Parts.defaults(type), pins: S.Parts.pinsOf(type) };
    const g = S.Geom.of(p), U = S.Geom.U;
    const svg = document.createElementNS(S.UI.NS, 'svg');
    // a tight box around the symbol (registers carry their name above), with room for the pins
    const top = p.type === 'reg' ? -16 : -3;
    svg.setAttribute('viewBox', `${-4} ${top} ${g.w * U + 8} ${g.h * U - top + 4}`);
    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    svg.innerHTML = S.Parts.markup(p);
    return svg;
  }

  class Palette {
    constructor(host, app) {
      this.app = app;
      this.open = new Set(S.UI.store.get('palOpen', ['Ports', 'Logic', 'Arithmetic', 'Select', 'Bits', 'State', 'Timing']));
      this.q = h('input.input', { type: 'search', placeholder: 'Search parts  ( / )', 'aria-label': 'Search parts' });
      this.list = h('div');
      this.root = h('div.pal', h('div.search', this.q), this.list);
      host.append(this.root);
      this.q.addEventListener('input', S.UI.debounce(() => this.render(), 60));
      this.q.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { const first = this.matches()[0]; if (first) this.app.placeAtCentre(first.type); }
        if (e.key === 'Escape') { this.q.value = ''; this.render(); this.app.canvas.wrap.focus(); }
      });
      this.render();
    }

    matches() {
      const q = this.q.value.trim().toLowerCase();
      return S.Parts.all().filter((t) => !q || (t.title + ' ' + t.type + ' ' + t.category + ' ' + t.help).toLowerCase().includes(q));
    }

    render() {
      const q = this.q.value.trim();
      const ms = this.matches();
      const by = new Map();
      for (const t of ms) { if (!by.has(t.category)) by.set(t.category, []); by.get(t.category).push(t); }
      const out = [];
      for (const [cat, list] of by) {
        const open = q || this.open.has(cat);
        const hd = h('h3' + (open ? '' : '.closed'), { role: 'button', 'aria-expanded': String(!!open), tabindex: '0' }, S.UI.icon(open ? 'down' : 'right'), cat, h('span.n', ' ' + list.length));
        hd.addEventListener('click', () => { this.open.has(cat) ? this.open.delete(cat) : this.open.add(cat); S.UI.store.set('palOpen', [...this.open]); this.render(); });
        const grid = h('div.grid');
        for (const t of list) grid.append(this.card(t));
        out.push(hd, grid);
      }
      // modules written in Java: the standard library's and this project's
      const mods = [{ type: 'code:new', title: 'Java code', help: 'A module written in Java, by hand, inside the drawing: for what a drawing cannot say (loops, generics, the whole language).', category: 'Modules' },
        ...(this.app.modules || []).map((m, i) => ({ type: 'module:' + i, title: m.title, help: m.help + ' ' + m.expr, category: 'Modules' }))]
        .filter((m) => !q || (m.title + ' ' + m.help).toLowerCase().includes(q.toLowerCase()));
      if (mods.length) {
        const open = q || this.open.has('Modules');
        const hd = h('h3' + (open ? '' : '.closed'), { role: 'button', 'aria-expanded': String(!!open), tabindex: '0' }, S.UI.icon(open ? 'down' : 'right'), 'Modules', h('span.n', ' ' + mods.length));
        hd.addEventListener('click', () => { this.open.has('Modules') ? this.open.delete('Modules') : this.open.add('Modules'); S.UI.store.set('palOpen', [...this.open]); this.render(); });
        const grid = h('div.grid');
        for (const m of mods) grid.append(this.card(m));
        out.push(hd, grid);
      }
      // the drawing's own blocks
      const blocks = Object.keys((this.app.doc && this.app.doc.root.blocks) || {});
      if (!q || blocks.some((b) => b.toLowerCase().includes(q.toLowerCase()))) {
        const open = q || this.open.has('My blocks');
        const hd = h('h3' + (open ? '' : '.closed'), { role: 'button', 'aria-expanded': String(!!open), tabindex: '0' }, S.UI.icon(open ? 'down' : 'right'), 'My blocks', h('span.n', ' ' + blocks.length));
        hd.addEventListener('click', () => { this.open.has('My blocks') ? this.open.delete('My blocks') : this.open.add('My blocks'); S.UI.store.set('palOpen', [...this.open]); this.render(); });
        const grid = h('div.grid');
        for (const b of blocks.filter((x) => !q || x.toLowerCase().includes(q.toLowerCase()))) grid.append(this.card({ type: 'block:' + b, title: b, help: 'A block of this drawing: drag to use it again; double-click a use to open it.', block: b }));
        out.push(hd, blocks.length ? grid : h('div.help', 'Select some parts and press B to make a block; it appears here.'));
      }
      if (!ms.length && !blocks.length) out.push(h('div.empty', h('b', 'No part matches “' + q + '”'), h('span', 'Try a shorter word: mux, reg, add, slice.')));
      this.list.replaceChildren(...out);
    }

    card(t) {
      const c = h('div.card', { title: t.title + ': ' + t.help, role: 'button', tabindex: '0', 'data-type': t.type }, thumb(t.type, t), h('span.nm', t.title));
      let drag = null;
      c.addEventListener('pointerdown', (e) => {
        c.setPointerCapture(e.pointerId);
        drag = { sx: e.clientX, sy: e.clientY, ghost: null };
      });
      c.addEventListener('pointermove', (e) => {
        if (!drag) return;
        if (!drag.ghost && Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) > 6) {
          drag.ghost = h('div.drag-ghost', thumb(t.type, t));
          drag.ghost.querySelector('svg').style.width = '90px';
          document.body.append(drag.ghost);
          document.body.classList.add('dragging-part');
        }
        if (drag.ghost) { drag.ghost.style.left = e.clientX + 'px'; drag.ghost.style.top = e.clientY + 'px'; }
      });
      c.addEventListener('pointerup', (e) => {
        if (!drag) return;
        const d = drag; drag = null;
        if (d.ghost) {
          d.ghost.remove();
          document.body.classList.remove('dragging-part');
          const r = this.app.canvas.wrap.getBoundingClientRect();
          if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
            const pt = this.app.canvas.toWorld(e.clientX, e.clientY);
            this.app.placeAt(t.type, pt);
          }
        } else this.app.placeAtCentre(t.type);
      });
      c.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this.app.placeAtCentre(t.type); } });
      return c;
    }

    focus() { this.q.focus(); this.q.select(); }
  }

  S.Palette = Palette;
})(globalThis.Studio = globalThis.Studio || {});
