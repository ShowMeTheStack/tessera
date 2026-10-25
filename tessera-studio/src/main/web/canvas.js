// Tessera Studio: the schematic canvas. One SVG holds the wires, the parts and a layer for what is
// transient (selection, the wire being drawn, the marquee); an HTML overlay holds tags that keep their
// size on screen (bus values). The view is a viewBox over grid coordinates; the dot grid is a CSS
// background kept in step with it. Wires are routed in a worker; while a wire is being drawn the ghost
// shows the real route once a target pin is within reach.
(function (S) {
  'use strict';
  const { h } = S.UI;
  const U = S.Geom.U, NS = S.UI.NS;
  const REACH = 0.9, ZMIN = 0.1, ZMAX = 8;

  class Canvas {
    constructor(host, doc, opts) {
      this.doc = doc;
      this.opts = opts || {};
      this.view = { x: -40, y: -40, z: 1 };
      this.sel = new Set();           // 'p:id' or 'w:id'
      this.routes = {};               // net id -> {segs, dots, failed}
      this.values = new Map();        // net id -> BigInt
      this.changed = new Map();       // net id -> time of last change
      this.radix = S.UI.store.get('radix', 'hex');
      this.problemParts = new Map();  // part id -> 'error' | 'warning'
      this.widths = {};
      this.live = false;
      this.wrap = h('div.cv', { tabindex: '0', 'aria-label': 'Schematic. Drag from a pin to draw a wire; drag a part to move it; space or middle button to pan; ctrl and wheel to zoom.' });
      this.svg = document.createElementNS(NS, 'svg');
      this.svg.setAttribute('class', 'cv-svg');
      this.svg.setAttribute('role', 'img');
      this.gWires = document.createElementNS(NS, 'g');
      this.gWires.setAttribute('class', 'wires');
      this.gParts = document.createElementNS(NS, 'g');
      this.gParts.setAttribute('class', 'parts');
      this.gUi = document.createElementNS(NS, 'g');
      this.gUi.setAttribute('class', 'ui');
      this.svg.append(this.gWires, this.gParts, this.gUi);
      this.tags = h('div.cv-tags');
      this.empty = h('div.cv-empty', h('div.empty', S.UI.icon('cpu'), h('b', 'An empty sheet'), h('span', 'Drag a part from the palette, press / to search for one, or open a design.')));
      this.zoomBar = h('div.cv-zoom',
        S.UI.button('', { icon: 'zoomIn', title: 'Zoom in (+)', onclick: () => this.zoomBy(1.25) }),
        S.UI.button('', { icon: 'zoomOut', title: 'Zoom out (−)', onclick: () => this.zoomBy(0.8) }),
        S.UI.button('', { icon: 'fit', title: 'Fit the drawing (F)', onclick: () => this.fit() }));
      this.mini = document.createElementNS(NS, 'svg');
      this.mini.setAttribute('class', 'cv-mini');
      this.wrap.append(this.svg, this.tags, this.empty, this.zoomBar, this.mini);
      host.append(this.wrap);
      this.partEls = new Map();
      this.partSig = new Map();
      this.bind();
      new ResizeObserver(() => { if (!this.sized) { this.sized = true; this.fit(); } else this.applyView(); }).observe(this.wrap);
      doc.on((why) => this.changedDoc(why));
      this.render();
    }

    // ---------------------------------------------------------------- view

    applyView() {
      const r = this.wrap.getBoundingClientRect(), v = this.view;
      this.svg.setAttribute('viewBox', `${v.x} ${v.y} ${r.width / v.z} ${r.height / v.z}`);
      const g = U * v.z;
      this.wrap.style.backgroundSize = `${g}px ${g}px`;
      this.wrap.style.backgroundPosition = `${-v.x * v.z % g}px ${-v.y * v.z % g}px`;
      this.wrap.classList.toggle('far', v.z < 0.45);
      this.wrap.classList.toggle('lod-small', v.z < 1 - 1e-6);
      this.wrap.classList.toggle('lod-none', v.z < 11 / 12 - 1e-6);
      this.placeTags();
      this.drawMini();
      this.opts.onView && this.opts.onView(v);
    }

    toWorld(cx, cy) {
      const r = this.wrap.getBoundingClientRect();
      return { x: this.view.x + (cx - r.left) / this.view.z, y: this.view.y + (cy - r.top) / this.view.z };
    }

    toScreen(x, y) {
      return { x: (x - this.view.x) * this.view.z, y: (y - this.view.y) * this.view.z };
    }

    zoomBy(f, cx, cy) {
      const r = this.wrap.getBoundingClientRect();
      if (cx === undefined) { cx = r.left + r.width / 2; cy = r.top + r.height / 2; }
      const before = this.toWorld(cx, cy);
      this.view.z = Math.min(ZMAX, Math.max(ZMIN, this.view.z * f));
      this.view.x = before.x - (cx - r.left) / this.view.z;
      this.view.y = before.y - (cy - r.top) / this.view.z;
      this.applyView();
    }

    bounds(ids) {
      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      for (const p of this.doc.d.parts) {
        if (ids && !ids.has('p:' + p.id)) continue;
        const g = S.Geom.of(p);
        x0 = Math.min(x0, g.x); y0 = Math.min(y0, g.y - 2); x1 = Math.max(x1, g.x + g.w); y1 = Math.max(y1, g.y + g.h + 1);
      }
      // the whole drawing includes its wires, which may loop round outside the parts (a register's feedback)
      if (!ids && isFinite(x0)) for (const r of Object.values(this.routes || {})) for (const [a, b, c, e] of r.segs || []) {
        x0 = Math.min(x0, a, c); x1 = Math.max(x1, a, c); y0 = Math.min(y0, b, e); y1 = Math.max(y1, b, e);
      }
      return isFinite(x0) ? { x0: x0 * U, y0: y0 * U, x1: x1 * U, y1: y1 * U } : null;
    }

    fit(selOnly) {
      const b = this.bounds(selOnly && this.sel.size ? this.sel : null);
      const r = this.wrap.getBoundingClientRect();
      if (!b || !r.width) { this.view = { x: -r.width / 2, y: -r.height / 2, z: 1 }; this.applyView(); return; }
      const pad = 40;
      const z = Math.min(2.2, Math.max(0.06, Math.min((r.width - 2 * pad) / (b.x1 - b.x0 || 1), (r.height - 2 * pad) / (b.y1 - b.y0 || 1))));
      this.view = { z, x: (b.x0 + b.x1) / 2 - r.width / 2 / z, y: (b.y0 + b.y1) / 2 - r.height / 2 / z };
      this.applyView();
    }

    centreOn(partId) {
      const p = this.doc.part(partId);
      if (!p) return;
      const g = S.Geom.of(p), r = this.wrap.getBoundingClientRect();
      this.view.x = (g.x + g.w / 2) * U - r.width / 2 / this.view.z;
      this.view.y = (g.y + g.h / 2) * U - r.height / 2 / this.view.z;
      this.applyView();
    }

    // ---------------------------------------------------------------- rendering

    changedDoc(why) {
      // drop selections of things that are gone
      for (const k of [...this.sel]) {
        const id = k.slice(2);
        if (k[0] === 'p' ? !this.doc.part(id) : !this.doc.net(id)) this.sel.delete(k);
      }
      this.render();
      this.opts.onSelect && this.opts.onSelect(this.sel);
    }

    // nets that carry a state machine's state: their tags show the state's name
    computeEnumNets() {
      this.enumNets = new Map();
      for (const p of this.doc.d.parts) {
        if (p.type !== 'fsm') continue;
        const n = this.doc.netOf(p.id, 'state');
        if (n) this.enumNets.set(n, String(p.params.states || '').trim().split(/\s+/));
      }
    }

    render() {
      this.computeEnumNets();
      const parts = this.doc.d.parts;
      this.empty.style.display = parts.length ? 'none' : '';
      // parts: keyed; a part is re-drawn only when what it looks like changed
      const seen = new Set();
      for (const p of parts) {
        seen.add(p.id);
        const sig = JSON.stringify([p.type, p.params, p.rot, p.mir, p.x, p.y, p.pins && p.pins.map((q) => q.name), S.Parts.prefsKey()]);
        let el = this.partEls.get(p.id);
        if (!el || this.partSig.get(p.id) !== sig) {
          const tmp = document.createElementNS(NS, 'g');
          tmp.innerHTML = S.Parts.markup(p);
          const nel = tmp.firstChild;
          if (el) el.replaceWith(nel); else this.gParts.append(nel);
          el = nel;
          this.partEls.set(p.id, el);
          this.partSig.set(p.id, sig);
        }
        el.classList.toggle('sel', this.sel.has('p:' + p.id));
        const pr = this.problemParts.get(p.id);
        el.classList.toggle('err', pr === 'error');
        el.classList.toggle('warn', pr === 'warning');
      }
      for (const [id, el] of this.partEls) if (!seen.has(id)) { el.remove(); this.partEls.delete(id); this.partSig.delete(id); }
      this.reroute();
      this.drawUi();
      this.drawMini();
    }

    routeProblem(extra) {
      const parts = [], nets = [];
      for (const p of this.doc.d.parts) { const g = S.Geom.of(p); parts.push({ x: g.x, y: g.y, w: g.w, h: g.h }); }
      for (const n of this.doc.d.nets) {
        if (n.logic) continue;                 // dependencies through logic are drawn as curves, not routed
        const pins = [];
        let drv = -1;
        for (const [pid, pin] of n.pins) {
          const p = this.doc.part(pid);
          const q = p && S.Geom.pin(p, pin);
          if (q) { if (q.dir === 'out') drv = pins.length; pins.push({ x: q.ax, y: q.ay, side: q.side }); }
        }
        // manual bends: driver, then the bends in order, then the reader
        if (n.via && n.via.length && pins.length === 2) {
          const [a, b] = drv === 1 ? [pins[1], pins[0]] : pins;
          nets.push({ id: n.id, ordered: true, pins: [a, ...n.via.map(([x, y]) => ({ x, y, side: 'n' })), b] });
          continue;
        }
        nets.push({ id: n.id, pins });
      }
      if (extra) nets.push(extra);
      return { parts, nets };
    }

    reroute() {
      const ver = ++this.routeVer;
      const t0 = performance.now();
      S.Router.route(this.routeProblem(), (partial, done, total) => {
        if (ver !== this.routeVer) return;
        this.routes = partial;
        this.drawWires();
        this.opts.onRouting && this.opts.onRouting(done, total);
      }).then((r) => {
        if (ver !== this.routeVer) return;
        this.routes = r;
        this.drawWires();
        this.routeMs = Math.round(performance.now() - t0);
        this.opts.onRouted && this.opts.onRouted(r, this.routeMs);
      });
    }

    drawWires() {
      let s = '';
      const labels = this.wireLabels = [];
      for (const n of this.doc.d.nets) {
        if (n.logic) { s += this.logicCurves(n); continue; }
        const r = this.routes[n.id];
        if (!r) continue;
        const w = this.widths[n.id] || 1, bus = w > 1;
        const stream = this.isStream(n);
        const cls = 'wire' + (bus ? ' bus' : '') + (stream ? ' stream' : '') + (n.logic ? ' logic' : '') + (this.sel.has('w:' + n.id) ? ' sel' : '') + (n.logic ? '' : this.valueClass(n.id, w));
        const d = r.segs.map(([a, b, c, e]) => `M${a * U} ${b * U}L${c * U} ${e * U}`).join('');
        s += `<path class="${cls}" data-net="${n.id}" d="${d}"/>`;
        for (const [x, y] of r.dots) s += `<circle class="dot${this.valueClass(n.id, w)}" data-net="${n.id}" cx="${x * U}" cy="${y * U}" r="${bus ? 4 : 3.2}"/>`;
        if (r.loose) for (const [a, b, c, e] of r.loose) s += `<path class="wire loose" data-net="${n.id}" d="M${a * U} ${b * U}H${c * U}V${e * U}"/>`;
        if (bus && r.segs.length) {
          // a width mark on the longest horizontal stretch
          const seg = r.segs.filter(([a, b, c, e]) => b === e).sort((p, q) => Math.abs(q[2] - q[0]) - Math.abs(p[2] - p[0]))[0];
          if (seg && Math.abs(seg[2] - seg[0]) >= 3) {
            // near the start of the stretch, so that it does not meet the value tag at the middle; on a
            // short stretch the number goes under the wire instead
            const x0 = Math.min(seg[0], seg[2]) * U, len = Math.abs(seg[2] - seg[0]) * U, my = seg[1] * U;
            const mx = len >= 60 ? x0 + 14 : x0 + len / 2, below = len < 60;
            s += `<path class="slash" d="M${mx - 4} ${my + 5}L${mx + 4} ${my - 5}"/><text class="wire-w" x="${mx + 6}" y="${below ? my + 15 : my - 7}">${w}</text>`;
            labels.push({ x: mx - 5, y: below ? my + 6 : my - 14, w: 12 + 7 * String(w).length, h: 14 });   // what a value tag must not cover
          }
        }
        if (n.via && this.sel.has('w:' + n.id))
          for (const [x, y] of n.via) s += `<path class="via" data-net="${n.id}" d="M${x * U} ${y * U - 5}l5 5l-5 5l-5 -5z"><title>A manual bend: Alt-drag to move it, Alt-click to remove it</title></path>`;
        if (n.name && n.show && r.segs.length) {
          const seg = r.segs.find(([a, b, c, e]) => b === e && Math.abs(c - a) >= 3) || r.segs[0];
          const low = S.Parts.activeLow(n.name), nm = String(n.name).replace(/&/g, '&amp;').replace(/</g, '&lt;');
          s += `<text class="net-name" x="${Math.min(seg[0], seg[2]) * U + 6}" y="${seg[1] * U - 6}">${low ? `<tspan text-decoration="overline">${low.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</tspan><title>${nm} (active low)</title>` : nm}</text>`;
        }
      }
      this.gWires.innerHTML = s;
      if (this.hoverNet) for (const el of this.gWires.querySelectorAll(`[data-net="${CSS.escape(this.hoverNet)}"]`)) el.classList.add('hover');
      this.placeTags();
    }

    // a wire between stream pins (it carries valid, ready and bits)
    isStream(n) {
      return n.pins.some(([pid, pin]) => { const p = this.doc.part(pid); return p && (p.pins || []).some((q) => q.name === pin && q.stream); });
    }

    // a dependency through logic: one curve from each signal read to the input it feeds
    logicCurves(n) {
      const [rp, rq] = n.pins[0];
      const reader = this.doc.part(rp), rpin = reader && S.Geom.pin(reader, rq);
      if (!rpin) return '';
      const rx = rpin.ax * U, ry = rpin.ay * U;
      let d = '';
      for (const [sp, sq] of n.pins.slice(1)) {
        const src = this.doc.part(sp), spin = src && S.Geom.pin(src, sq);
        if (!spin) continue;
        const sx = spin.ax * U, sy = spin.ay * U, dx = Math.max(40, Math.abs(rx - sx) * 0.45);
        d += `M${sx} ${sy}C${sx + dx} ${sy} ${rx - dx} ${ry} ${rx} ${ry}`;
      }
      return `<path class="wire logic${this.sel.has('w:' + n.id) ? ' sel' : ''}" data-net="${n.id}" d="${d}"/>`;
    }

    valueClass(net, w) {
      // activity heat, when on, colours the wire by how busy it was; the value classes still mark 0 and 1
      const heat = this.heatOn && this.heat && this.heat.has(net) ? ' heat' + this.heat.get(net) : '';
      return this.valueClassOnly(net, w) + heat;
    }

    valueClassOnly(net, w) {
      if (!this.live || !this.values.has(net)) return '';
      const v = this.values.get(net);
      const flash = performance.now() - (this.changed.get(net) || -1e9) < 450 ? ' flash' : '';
      if (w > 1) return ' vbus' + flash;
      return (v === 0n ? ' v0' : ' v1') + flash;
    }

    // activity: net -> changes in the last span cycles, as five levels (0: none, 4: a quarter of cycles or more)
    setActivity(counts, span) {
      this.heat = new Map();
      for (const [net, c] of counts) {
        const f = c / Math.max(1, span);
        this.heat.set(net, c === 0 ? 0 : f < 0.01 ? 1 : f < 0.05 ? 2 : f < 0.25 ? 3 : 4);
      }
      this.setValues(new Map(), this.live);
    }

    // values: Map net -> BigInt; only classes and tags change
    setValues(vals, live) {
      const now = performance.now();
      for (const [k, v] of vals) { if (this.values.get(k) !== v) this.changed.set(k, now); this.values.set(k, v); }
      this.live = live !== false;
      for (const el of this.gWires.querySelectorAll('[data-net]')) {
        const id = el.getAttribute('data-net'), w = this.widths[id] || 1;
        const base = el.tagName === 'circle' ? 'dot' : 'wire' + (w > 1 ? ' bus' : '') + (el.classList.contains('stream') ? ' stream' : '') + (this.sel.has('w:' + id) ? ' sel' : '') + (el.classList.contains('loose') ? ' loose' : '');
        el.setAttribute('class', base + this.valueClass(id, w));
      }
      // board parts: LEDs, switches and buttons by bit, hex displays by digit
      for (const p of this.doc.d.parts) {
        const look = (p.type === 'in' || p.type === 'out') && p.params.look;
        if (!look || look === 'port') continue;
        const el = this.partEls.get(p.id), net = el && this.doc.netOf(p.id, p.type === 'in' ? 'y' : 'a');
        const key = this.values.has('#' + p.params.name) ? '#' + p.params.name : net;   // the port's own value, wired or not
        const v = key && this.live && this.values.has(key) ? this.values.get(key) : null;
        if (!el) continue;
        for (const b of el.querySelectorAll('[data-bit]')) b.classList.toggle('on', v != null && ((v >> BigInt(b.getAttribute('data-bit'))) & 1n) === 1n);
        const hx = el.querySelector('[data-hex]');
        if (hx) { const sig = v == null ? '' : v.toString(16); if (hx.getAttribute('data-shown') !== sig) { hx.setAttribute('data-shown', sig); hx.innerHTML = S.Parts.hexDigits(v, Number(p.params.width) || 4, Number(hx.getAttribute('data-hex'))); } }
      }
      // register contents inside their symbols
      for (const p of this.doc.d.parts) {
        if (p.type !== 'reg') continue;
        const el = this.partEls.get(p.id), t = el && el.querySelector('[data-regval]'), nx = el && el.querySelector('[data-regnext]');
        const net = this.doc.netOf(p.id, 'q'), dnet = this.doc.netOf(p.id, 'd'), w = Number(p.params.width) || 1;
        const q = net && this.values.has(net) ? this.values.get(net) : undefined;
        const text = this.live && q !== undefined ? this.fmt(q, w) : '';
        if (t) {
          // the hand-over: Q takes D at the edge, and its value flashes as it arrives
          if (text && t.textContent && t.textContent !== text && performance.now() - (this.changed.get(net) || -1e9) < 450) {
            t.classList.remove('handover'); void t.getBBox; requestAnimationFrame(() => t.classList.add('handover'));
          }
          t.textContent = text;
        }
        if (nx) {
          // the next value, greyed, while it differs from what the register holds (with the enable, only when enabled)
          const d = dnet && this.values.get(dnet), ennet = this.doc.netOf(p.id, 'en'), en = ennet ? this.values.get(ennet) : 1n;
          nx.textContent = this.live && d !== undefined && q !== undefined && d !== q && en !== 0n ? 'next ' + this.fmt(d, w) : '';
        }
      }
      // streams: valid without ready is backpressure (amber); valid and ready is a beat (a pulse)
      for (const p of this.doc.d.parts) {
        if (p.type !== 'module') continue;
        const names = new Set((p.pins || []).map((q) => q.name));
        for (const v of names) {
          if (!v.endsWith('_valid')) continue;
          const base = v.slice(0, -6);
          if (!names.has(base + '_ready')) continue;
          const nv = this.doc.netOf(p.id, v), nr = this.doc.netOf(p.id, base + '_ready'), nb = this.doc.netOf(p.id, base + '_bits');
          const vv = nv && this.values.get(nv), rr = nr && this.values.get(nr);
          const stall = this.live && vv === 1n && rr === 0n, beat = this.live && vv === 1n && rr === 1n;
          for (const id of [nv, nr, nb]) if (id) for (const el of this.gWires.querySelectorAll(`[data-net="${CSS.escape(id)}"]`)) { el.classList.toggle('stall', stall); el.classList.toggle('beat', beat); }
        }
        for (const nid of new Set((p.pins || []).filter((q) => q.stream).map((q) => this.doc.netOf(p.id, q.name)).filter(Boolean))) {
          const vv = this.values.get(nid + ':v'), rr = this.values.get(nid + ':r');
          const stall = this.live && vv === 1n && rr === 0n, beat = this.live && vv === 1n && rr === 1n;
          for (const el of this.gWires.querySelectorAll(`[data-net="${CSS.escape(nid)}"]`)) { el.classList.toggle('stall', stall); el.classList.toggle('beat', beat); }
        }
        // a FIFO's fill level, from its count and the depth in its expression
        const fillEl = this.partEls.get(p.id) && this.partEls.get(p.id).querySelector('[data-fill]');
        const m = /Fifo<?>?\s*\([^,]+,\s*(\d+)/.exec(p.params.expr || '');
        const cn = this.doc.netOf(p.id, 'count');
        if (fillEl && m) {
          const c = cn ? this.values.get(cn) : undefined, depth = Number(m[1]);
          const f = this.live && c !== undefined ? Math.min(1, Number(c) / depth) : 0;
          fillEl.setAttribute('width', String(Number(fillEl.getAttribute('data-w')) * f));
          fillEl.classList.toggle('full', f >= 1);
        }
      }
      // state machines show the state they are in
      for (const p of this.doc.d.parts) {
        if (p.type !== 'fsm') continue;
        const el = this.partEls.get(p.id), t = el && el.querySelector('[data-fsmval]');
        const v = this.values.get('@' + p.id), states = String(p.params.states || '').trim().split(/\s+/);
        if (t) t.textContent = this.live && v !== undefined ? (states[Number(v)] ?? '?' + v) : '';
      }
      // input ports show their level
      for (const p of this.doc.d.parts) {
        if (p.type !== 'in' && p.type !== 'out') continue;
        const el = this.partEls.get(p.id);
        const net = this.doc.netOf(p.id, p.type === 'in' ? 'y' : 'a');
        const w = Number(p.params.width) || 1;
        if (!el) continue;
        el.classList.toggle('lv1', this.live && w === 1 && this.values.get(net) === 1n);
        el.classList.toggle('lv0', this.live && w === 1 && this.values.get(net) === 0n);
      }
      this.placeTags();
    }

    fmt(v, w) {
      if (w === 1) return v.toString();
      switch (this.radix) {
        case 'bin': return v.toString(2).padStart(w, '0');
        case 'dec': return v.toString(10);
        case 'signed': { const m = 1n << BigInt(w); return (v >= m / 2n ? v - m : v).toString(10); }
        default: return '0x' + v.toString(16).padStart(Math.ceil(w / 4), '0');
      }
    }

    // value tags on buses: on a long stretch of the wire, at the first of a few places along it that covers no
    // other tag, no width label and no part; when none is free, where it covers least
    placeTags() {
      if (!this.tags) return;
      if (!this.live) { this.tags.replaceChildren(); return; }
      const frag = [], placed = [];
      const toS = (r) => { const a = this.toScreen(r.x, r.y); return { x: a.x, y: a.y, w: r.w * this.view.z, h: r.h * this.view.z }; };
      for (const l of this.wireLabels || []) placed.push(toS(l));
      for (const p of this.doc.d.parts) { const g = S.Geom.of(p); placed.push(toS({ x: g.x * U - 2, y: g.y * U - 2, w: g.w * U + 4, h: g.h * U + 4 })); }
      const overlap = (a, b) => Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)) * Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
      for (const n of this.doc.d.nets) {
        const w = this.widths[n.id] || 1, r = this.routes[n.id];
        const sv = this.values.get(n.id + ':v');
        if (sv !== undefined && r && r.segs.length) {   // a stream: its bits, and whether this cycle is a transfer or a stall
          const sr = this.values.get(n.id + ':r'), sb = this.values.get(n.id + ':b');
          this.values.set(n.id, sb ?? 0n);
        } else if (w < 2 || !r || !this.values.has(n.id) || !r.segs.length) continue;
        const names = this.enumNets && this.enumNets.get(n.id), v = this.values.get(n.id);
        const text = sv !== undefined ? (sv === 1n ? this.fmt(this.values.get(n.id + ':b') ?? 0n, w) + (this.values.get(n.id + ':r') === 1n ? ' ✓' : ' ⏸') : '—')
          : names ? (names[Number(v)] ?? '?' + v) : this.fmt(v, w);
        const tw = text.length * 6.8 + 12, th = 18;
        let best = null, bestCost = Infinity;
        const segs = [...r.segs].sort((p, q) => (Math.abs(q[2] - q[0]) + Math.abs(q[3] - q[1])) - (Math.abs(p[2] - p[0]) + Math.abs(p[3] - p[1]))).slice(0, 3);
        for (const seg of segs) for (const f of [0.5, 0.3, 0.7, 0.15, 0.85]) {
          const sp = this.toScreen((seg[0] + (seg[2] - seg[0]) * f) * U, (seg[1] + (seg[3] - seg[1]) * f) * U);
          const box = { x: sp.x - tw / 2, y: sp.y - th - 4, w: tw, h: th };
          let cost = 0;
          for (const o of placed) cost += overlap(box, o);
          if (cost < bestCost) { bestCost = cost; best = { sp, box }; }
          if (cost === 0) break;
        }
        if (!best) continue;
        placed.push(best.box);
        const t = h('div.vtag' + (performance.now() - (this.changed.get(n.id) || -1e9) < 450 ? '.flash' : '') + (names ? '.enum' : ''), text);
        if (names) t.style.setProperty('--ev', 'var(--c' + (Number(v) % 6 + 1) + ')');
        t.style.transform = `translate(${Math.round(best.sp.x)}px, ${Math.round(best.sp.y)}px) translate(-50%, -130%)`;
        frag.push(t);
      }
      this.tags.replaceChildren(...frag);
    }


    drawUi() {
      let s = '';
      for (const k of this.sel) {
        if (k[0] !== 'p') continue;
        const p = this.doc.part(k.slice(2));
        if (!p) continue;
        const g = S.Geom.of(p);
        s += `<rect class="selbox" x="${g.x * U - 5}" y="${g.y * U - 5}" width="${g.w * U + 10}" height="${g.h * U + 10}" rx="4"/>`;
      }
      // a timing path's parts, numbered in path order with their delays
      for (const m of this.cpMarks || []) {
        const p = this.doc.part(m.part);
        if (!p) continue;
        // the number on the box's top-left corner, the steps and their delay beside it
        const g = S.Geom.of(p), x = g.x * U - 4, y = g.y * U - 4, steps = m.steps && m.steps.length > 1 ? 'steps ' + m.steps.join(', ') + ': ' : '';
        s += `<g class="cpmark"><rect class="cpbox" x="${x}" y="${y}" width="${g.w * U + 8}" height="${g.h * U + 8}" rx="4"/>`
          + `<circle cx="${x}" cy="${y}" r="8"/><text x="${x}" y="${y + 3.5}">${m.n}</text>`
          // a line above the part's name (registers and blocks write it just over the box)
          + `<text class="cpns" x="${x + 12}" y="${y - 16}">${steps}${m.ns.toFixed(2)} ns</text></g>`;
      }
      if (this.hot) s += `<circle class="pinhot" cx="${this.hot.ax * U}" cy="${this.hot.ay * U}" r="4.5"/>`;
      if (this.ghost) s += this.ghost;
      if (this.bendGhost) { const [x, y] = this.bendGhost; s += `<path class="via ghost" d="M${x * U} ${y * U - 6}l6 6l-6 6l-6 -6z"/>`; }
      // hovering a wire lights all of it, every branch and the pins it joins
      if (this.hoverNet && !this.ghost) {
        const n = this.doc.net(this.hoverNet);
        if (n) for (const [pid, pin] of n.pins) { const p = this.doc.part(pid), q = p && S.Geom.pin(p, pin); if (q) s += `<circle class="pinhot net" cx="${q.ax * U}" cy="${q.ay * U}" r="4"/>`; }
      }
      if (this.marquee) {
        const m = this.marquee;
        s += `<rect class="marquee" x="${Math.min(m.x0, m.x1)}" y="${Math.min(m.y0, m.y1)}" width="${Math.abs(m.x1 - m.x0)}" height="${Math.abs(m.y1 - m.y0)}"/>`;
      }
      this.gUi.innerHTML = s;
    }

    drawMini() {
      const b = this.bounds(), r = this.wrap.getBoundingClientRect();
      if (!b || this.doc.d.parts.length < 2) { this.mini.style.display = 'none'; return; }
      const v = this.view, vw = r.width / v.z, vh = r.height / v.z;
      const inside = b.x0 >= v.x && b.y0 >= v.y && b.x1 <= v.x + vw && b.y1 <= v.y + vh;
      this.mini.style.display = inside ? 'none' : '';
      if (inside) return;
      const X0 = Math.min(b.x0, v.x), Y0 = Math.min(b.y0, v.y), X1 = Math.max(b.x1, v.x + vw), Y1 = Math.max(b.y1, v.y + vh);
      this.mini.setAttribute('viewBox', `${X0} ${Y0} ${X1 - X0} ${Y1 - Y0}`);
      let s = '';
      for (const p of this.doc.d.parts) { const g = S.Geom.of(p); s += `<rect class="mm-part" x="${g.x * U}" y="${g.y * U}" width="${g.w * U}" height="${g.h * U}"/>`; }
      s += `<rect class="mm-view" x="${v.x}" y="${v.y}" width="${vw}" height="${vh}"/>`;
      this.mini.innerHTML = s;
      this.miniBox = { X0, Y0, X1, Y1 };
    }

    // ---------------------------------------------------------------- hit testing

    pinNear(pt, reach) {
      let best = null, bd = (reach || REACH) * U;
      for (const p of this.doc.d.parts) {
        const g = S.Geom.of(p);
        if (pt.x < (g.x - 2) * U || pt.x > (g.x + g.w + 2) * U || pt.y < (g.y - 2) * U || pt.y > (g.y + g.h + 2) * U) continue;
        for (const q of g.pins) {
          const d = Math.hypot(q.ax * U - pt.x, q.ay * U - pt.y);
          if (d < bd) { bd = d; best = { part: p.id, pin: q.name, ax: q.ax, ay: q.ay, dir: q.dir }; }
        }
      }
      return best;
    }

    partAt(pt) {
      const ps = this.doc.d.parts;
      for (let i = ps.length - 1; i >= 0; i--) {
        const g = S.Geom.of(ps[i]);
        if (pt.x >= g.x * U - 2 && pt.x <= (g.x + g.w) * U + 2 && pt.y >= g.y * U - 2 && pt.y <= (g.y + g.h) * U + 2) return ps[i];
      }
      return null;
    }

    wireAt(pt) {
      const tol = 5 / Math.max(0.5, this.view.z);
      for (const [id, r] of Object.entries(this.routes)) {
        for (const [a, b, c, e] of r.segs) {
          const x0 = a * U, y0 = b * U, x1 = c * U, y1 = e * U;
          const t = Math.max(0, Math.min(1, ((pt.x - x0) * (x1 - x0) + (pt.y - y0) * (y1 - y0)) / ((x1 - x0) ** 2 + (y1 - y0) ** 2 || 1)));
          const px = x0 + t * (x1 - x0), py = y0 + t * (y1 - y0);
          if (Math.hypot(px - pt.x, py - pt.y) <= tol) return { net: id, x: Math.round(px / U), y: Math.round(py / U) };
        }
      }
      return null;
    }

    // ---------------------------------------------------------------- input

    bind() {
      const w = this.wrap;
      let act = null, spaceDown = false;
      w.addEventListener('keydown', (e) => { if (e.code === 'Space' && !e.repeat && document.activeElement === w) { spaceDown = true; w.classList.add('panning'); e.preventDefault(); } });
      w.addEventListener('keyup', (e) => { if (e.code === 'Space') { spaceDown = false; w.classList.remove('panning'); } });
      w.addEventListener('wheel', (e) => {
        e.preventDefault();
        if (e.ctrlKey || e.metaKey) this.zoomBy(Math.exp(-e.deltaY * 0.01), e.clientX, e.clientY);
        else if (e.deltaMode !== 0) this.zoomBy(Math.exp(-e.deltaY * 0.05), e.clientX, e.clientY);
        else {
          this.view.x += (e.shiftKey ? e.deltaY : e.deltaX) / this.view.z;
          this.view.y += (e.shiftKey ? 0 : e.deltaY) / this.view.z;
          this.applyView();
        }
      }, { passive: false });
      this.mini.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        const go = (ev) => {
          const r = this.mini.getBoundingClientRect(), m = this.miniBox;
          if (!m) return;
          const x = m.X0 + (ev.clientX - r.left) / r.width * (m.X1 - m.X0), y = m.Y0 + (ev.clientY - r.top) / r.height * (m.Y1 - m.Y0);
          const wr = this.wrap.getBoundingClientRect();
          this.view.x = x - wr.width / 2 / this.view.z; this.view.y = y - wr.height / 2 / this.view.z;
          this.applyView();
        };
        go(e);
        this.mini.setPointerCapture(e.pointerId);
        const up = () => { this.mini.removeEventListener('pointermove', go); this.mini.removeEventListener('pointerup', up); };
        this.mini.addEventListener('pointermove', go);
        this.mini.addEventListener('pointerup', up);
      });
      w.addEventListener('pointerdown', (e) => {
        if (e.target.closest('.cv-zoom, .cv-mini, .vtag')) return;
        w.focus({ preventScroll: true });
        const pt = this.toWorld(e.clientX, e.clientY);
        const sx = e.clientX, sy = e.clientY;
        w.setPointerCapture(e.pointerId);
        if (e.button === 1 || spaceDown) { act = { kind: 'pan', sx, sy, vx: this.view.x, vy: this.view.y }; return; }
        if (e.button === 2) return;
        const now = performance.now();
        const dbl = this.lastDown && now - this.lastDown.t < 450 && Math.hypot(sx - this.lastDown.x, sy - this.lastDown.y) < 8;
        this.lastDown = { t: now, x: sx, y: sy };
        const pin = this.pinNear(pt, e.pointerType === 'touch' ? 1.4 : REACH);
        const part = this.partAt(pt);
        if (dbl && part && !pin) { this.opts.onEdit && this.opts.onEdit(part.id); return; }
        if (dbl && !part) { const wire = this.wireAt(pt); if (wire) { this.opts.onLabel && this.opts.onLabel(wire.net); return; } this.opts.onQuickAdd && this.opts.onQuickAdd(pt); return; }
        if (pin && !(this.opts.readOnly && this.opts.readOnly())) { act = { kind: 'wire', from: pin, sx, sy }; return; }
        // a switch flips its bit when clicked; a push button is 1 while held
        const bitEl = part && this.live && part.type === 'in' && !e.shiftKey && e.target.closest && e.target.closest('[data-bit]');
        if (bitEl && part.params.look === 'buttons') {
          const bit = Number(bitEl.getAttribute('data-bit'));
          this.opts.onPress && this.opts.onPress(part, bit, true);
          act = { kind: 'release', part, bit };
          return;
        }
        if (bitEl && part.params.look === 'switches') {
          act = { kind: 'toggle', part, bit: Number(bitEl.getAttribute('data-bit')), sx, sy, start: pt };
          return;
        }
        if (part && this.live && part.type === 'in' && Number(part.params.width) === 1 && !e.shiftKey) {
          act = { kind: 'toggle', part, sx, sy, start: pt };
          return;
        }
        if (part) {
          const key = 'p:' + part.id;
          if (e.shiftKey || e.metaKey || e.ctrlKey) { this.sel.has(key) ? this.sel.delete(key) : this.sel.add(key); }
          else if (!this.sel.has(key)) { this.sel.clear(); this.sel.add(key); }
          const ids = [...this.sel].filter((k) => k[0] === 'p').map((k) => k.slice(2));
          act = { kind: 'move', ids, start: pt, orig: ids.map((id) => { const p = this.doc.part(id); return [id, p.x, p.y]; }), moved: false };
          this.selChanged();
          return;
        }
        const wire = this.wireAt(pt);
        if (e.altKey && !(this.opts.readOnly && this.opts.readOnly())) {
          const gx = Math.round(pt.x / U), gy = Math.round(pt.y / U);
          const viaNet = this.doc.d.nets.find((n) => (n.via || []).some(([x, y]) => Math.abs(x - gx) <= 1 && Math.abs(y - gy) <= 1));
          if (viaNet) {
            const k = viaNet.via.findIndex(([x, y]) => Math.abs(x - gx) <= 1 && Math.abs(y - gy) <= 1);
            act = { kind: 'bend', net: viaNet.id, index: k, sx, sy, moved: false };
            return;
          }
          if (wire) {
            const n = this.doc.net(wire.net);
            if (n && n.pins.length !== 2) { this.opts.onNote && this.opts.onNote('Manual bends are for wires between two pins; this one has ' + n.pins.length + '.'); return; }
            act = { kind: 'bend', net: wire.net, index: -1, sx, sy, moved: false };
            return;
          }
        }
        if (wire) { act = { kind: 'wirepress', wire, sx, sy, shift: e.shiftKey }; return; }
        act = { kind: 'marquee', x0: pt.x, y0: pt.y, x1: pt.x, y1: pt.y, add: e.shiftKey, sx, sy };
      });
      w.addEventListener('pointermove', (e) => {
        const pt = this.toWorld(e.clientX, e.clientY);
        if (!act) {
          const pin = this.pinNear(pt);
          const k = pin ? pin.part + '.' + pin.pin : '';
          if (k !== (this.hot ? this.hot.part + '.' + this.hot.pin : '')) { this.hot = pin; w.classList.toggle('on-pin', !!pin); this.drawUi(); }
          this.hoverInfo(pt, pin);
          return;
        }
        if (act.kind === 'pan') {
          this.view.x = act.vx - (e.clientX - act.sx) / this.view.z;
          this.view.y = act.vy - (e.clientY - act.sy) / this.view.z;
          this.applyView();
        } else if (act.kind === 'move') {
          const dx = Math.round((pt.x - act.start.x) / U), dy = Math.round((pt.y - act.start.y) / U);
          if (dx === act.dx && dy === act.dy) return;
          act.dx = dx; act.dy = dy; act.moved = true;
          for (const [id, x, y] of act.orig) { const p = this.doc.part(id); p.x = x + dx; p.y = y + dy; }
          this.lazyRender();
        } else if (act.kind === 'toggle') {
          if (Math.hypot(e.clientX - act.sx, e.clientY - act.sy) > 5) {
            const p = act.part;
            this.sel.clear(); this.sel.add('p:' + p.id);
            act = { kind: 'move', ids: [p.id], start: act.start, orig: [[p.id, p.x, p.y]], moved: false };
          }
        } else if (act.kind === 'wirepress') {
          if (Math.hypot(e.clientX - act.sx, e.clientY - act.sy) > 6) act = { kind: 'wire', from: { net: act.wire.net, ax: act.wire.x, ay: act.wire.y }, sx: act.sx, sy: act.sy };
        } else if (act.kind === 'bend') {
          if (Math.hypot(e.clientX - act.sx, e.clientY - act.sy) > 4) act.moved = true;
          act.at = [Math.round(pt.x / U), Math.round(pt.y / U)];
          this.bendGhost = act.moved ? act.at : null;
          this.drawUi();
        } else if (act.kind === 'wire') {
          this.ghostTo(act, pt);
        } else if (act.kind === 'marquee') {
          act.x1 = pt.x; act.y1 = pt.y;
          this.marquee = act;
          this.drawUi();
        }
      });
      const finish = (e) => {
        if (!act) return;
        const a = act; act = null;
        const pt = this.toWorld(e.clientX, e.clientY);
        if (a.kind === 'move') {
          if (a.moved) {
            const dx = a.dx, dy = a.dy;
            for (const [id, x, y] of a.orig) { const p = this.doc.part(id); p.x = x; p.y = y; }   // let the history see the move
            this.doc.movePartsBy(a.ids, dx, dy);
          }
        } else if (a.kind === 'bend') {
          this.bendGhost = null;
          const n = this.doc.net(a.net);
          if (n) {
            const via = (n.via || []).map((q) => [...q]);
            if (!a.moved && a.index >= 0) via.splice(a.index, 1);                 // alt-click a bend: remove it
            else if (a.moved && a.index >= 0) via[a.index] = a.at;               // alt-drag a bend: move it
            else if (a.moved) via.splice(this.bendSlot(n, via, a.at), 0, a.at);  // alt-drag a wire: a new bend
            this.doc.setVia(a.net, via);
            this.sel.clear(); this.sel.add('w:' + a.net); this.selChanged();
          }
          this.drawUi();
        } else if (a.kind === 'toggle') {
          this.opts.onToggle && this.opts.onToggle(a.part, a.bit);
        } else if (a.kind === 'release') {
          this.opts.onPress && this.opts.onPress(a.part, a.bit, false);
        } else if (a.kind === 'wirepress') {
          const key = 'w:' + a.wire.net;
          if (!a.shift) this.sel.clear();
          this.sel.has(key) && a.shift ? this.sel.delete(key) : this.sel.add(key);
          this.selChanged();
        } else if (a.kind === 'wire') {
          this.ghost = null;
          const target = this.pinNear(pt, 1.2);
          const r = this.wrap.getBoundingClientRect();
          const outside = e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
          if (outside && this.opts.onDropOutside) this.opts.onDropOutside(a.from, e);
          else if (target && !(a.from.part === target.part && a.from.pin === target.pin)) {
            const id = this.doc.connect(a.from.net ? { net: a.from.net } : { part: a.from.part, pin: a.from.pin }, { part: target.part, pin: target.pin });
            if (id) { this.sel.clear(); this.sel.add('w:' + id); this.selChanged(); }
          } else if (!target && a.from.part && Math.hypot(e.clientX - a.sx, e.clientY - a.sy) > 12) {
            this.opts.onDropEmpty && this.opts.onDropEmpty(a.from, pt);
          }
          this.drawUi();
        } else if (a.kind === 'marquee') {
          this.marquee = null;
          if (Math.hypot(e.clientX - a.sx, e.clientY - a.sy) < 3) { if (!a.add) this.sel.clear(); }
          else {
            if (!a.add) this.sel.clear();
            const X0 = Math.min(a.x0, a.x1), X1 = Math.max(a.x0, a.x1), Y0 = Math.min(a.y0, a.y1), Y1 = Math.max(a.y0, a.y1);
            for (const p of this.doc.d.parts) {
              const g = S.Geom.of(p);
              if (g.x * U >= X0 && (g.x + g.w) * U <= X1 && g.y * U >= Y0 && (g.y + g.h) * U <= Y1) this.sel.add('p:' + p.id);
            }
            for (const n of this.doc.d.nets) if (n.pins.every(([p]) => this.sel.has('p:' + p))) this.sel.add('w:' + n.id);
          }
          this.selChanged();
        }
      };
      w.addEventListener('pointerup', finish);
      w.addEventListener('pointercancel', () => { act = null; this.ghost = null; this.marquee = null; this.drawUi(); });
      w.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        const pt = this.toWorld(e.clientX, e.clientY);
        const part = this.partAt(pt), wire = part ? null : this.wireAt(pt);
        if (part && !this.sel.has('p:' + part.id)) { this.sel.clear(); this.sel.add('p:' + part.id); this.selChanged(); }
        if (wire && !this.sel.has('w:' + wire.net)) { this.sel.clear(); this.sel.add('w:' + wire.net); this.selChanged(); }
        this.opts.onMenu && this.opts.onMenu(e, part, wire, pt);
      });
      w.addEventListener('pointerleave', () => { if (this.hot) { this.hot = null; this.drawUi(); } this.setHoverNet(null); this.opts.onHover && this.opts.onHover(null); });
    }

    // where a new bend goes in a wire's list: the slot that adds the least length to driver, bends..., reader
    bendSlot(n, via, at) {
      const pts = n.pins.map(([pid, pin]) => { const p = this.doc.part(pid), q = p && S.Geom.pin(p, pin); return q ? { x: q.ax, y: q.ay, out: q.dir === 'out' } : null; }).filter(Boolean);
      if (pts.length !== 2) return via.length;
      const [a, b] = pts[1].out ? [pts[1], pts[0]] : pts;
      const chain = [[a.x, a.y], ...via, [b.x, b.y]];
      const d = (p, q) => Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]);
      let best = 0, bc = Infinity;
      for (let i = 0; i + 1 < chain.length; i++) { const c = d(chain[i], at) + d(at, chain[i + 1]) - d(chain[i], chain[i + 1]); if (c < bc) { bc = c; best = i; } }
      return best;
    }

    setHoverNet(id) {
      if (id === this.hoverNet) return;
      if (this.hoverNet) for (const el of this.gWires.querySelectorAll(`[data-net="${CSS.escape(this.hoverNet)}"]`)) el.classList.remove('hover');
      this.hoverNet = id;
      if (id) for (const el of this.gWires.querySelectorAll(`[data-net="${CSS.escape(id)}"]`)) el.classList.add('hover');
      this.wrap.classList.toggle('net-hover', !!id);
      this.drawUi();
    }

    hoverInfo(pt, pin) {
      const wireHere = pin ? null : (this.partAt(pt) ? null : this.wireAt(pt));
      this.setHoverNet(pin ? this.doc.netOf(pin.part, pin.pin) : wireHere ? wireHere.net : null);
      if (!this.opts.onHover) return;
      if (pin) { this.opts.onHover({ kind: 'pin', ...pin, net: this.doc.netOf(pin.part, pin.pin) }); return; }
      const wire = this.wireAt(pt);
      if (wire) { this.opts.onHover({ kind: 'net', net: wire.net }); return; }
      const part = this.partAt(pt);
      this.opts.onHover(part ? { kind: 'part', part: part.id } : null);
    }

    lazyRender() {
      if (this.pending) return;
      this.pending = requestAnimationFrame(() => { this.pending = 0; this.render(); });
    }

    // the wire being drawn: dashed to the cursor, or the real route when a pin is within reach
    ghostTo(act, pt) {
      const from = act.from;
      const target = this.pinNear(pt, 1.2);
      const fx = from.ax * U, fy = from.ay * U;
      if (target && !(target.part === from.part && target.pin === from.pin)) {
        const pins = [];
        if (from.net) {
          const n = this.doc.net(from.net);
          for (const [pid, pn] of n.pins) { const q = S.Geom.pin(this.doc.part(pid), pn); if (q) pins.push({ x: q.ax, y: q.ay, side: q.side }); }
        } else { const q = S.Geom.pin(this.doc.part(from.part), from.pin); pins.push({ x: q.ax, y: q.ay, side: q.side }); }
        const tq = S.Geom.pin(this.doc.part(target.part), target.pin);
        pins.push({ x: tq.ax, y: tq.ay, side: tq.side });
        const prob = this.routeProblem({ id: '__ghost', pins });
        if (from.net) prob.nets = prob.nets.filter((n) => n.id !== from.net);
        const r = S.Router.routeNow(prob).__ghost;
        if (r && !r.failed) {
          this.ghost = `<path class="ghost on" d="${r.segs.map(([a, b, c, e]) => `M${a * U} ${b * U}L${c * U} ${e * U}`).join('')}"/>`;
          this.hot = target;
          this.drawUi();
          return;
        }
      }
      this.hot = null;
      this.ghost = `<path class="ghost" d="M${fx} ${fy}H${pt.x}V${pt.y}"/>`;
      this.drawUi();
    }

    // the parts of a timing path, numbered (Timing); cleared when nothing is selected
    setMarks(marks) {
      this.cpMarks = marks;
      this.drawUi();
    }

    selChanged() {
      if (!this.sel.size) this.cpMarks = [];
      this.render();
      this.opts.onSelect && this.opts.onSelect(this.sel);
    }

    selectOnly(keys) {
      this.sel = new Set(keys);
      this.selChanged();
    }

    selectedParts() { return [...this.sel].filter((k) => k[0] === 'p').map((k) => k.slice(2)); }
    selectedNets() { return [...this.sel].filter((k) => k[0] === 'w').map((k) => k.slice(2)); }

    // an SVG of the drawing with the theme's colours written in (for export)
    exportSvg() {
      const b = this.bounds();
      if (!b) return null;
      const clone = this.svg.cloneNode(true);
      clone.querySelector('.ui').remove();
      const src = this.svg.querySelectorAll('*'), dst = clone.querySelectorAll('*');
      const props = ['fill', 'stroke', 'stroke-width', 'stroke-dasharray', 'font-family', 'font-size', 'font-weight', 'font-style', 'opacity', 'stroke-linecap', 'stroke-linejoin'];
      src.forEach((el, i) => {
        const cs = getComputedStyle(el);
        dst[i].setAttribute('style', props.map((p) => `${p}:${cs.getPropertyValue(p)}`).join(';'));
        dst[i].removeAttribute('class');
      });
      const pad = 20;
      clone.setAttribute('viewBox', `${b.x0 - pad} ${b.y0 - pad - 10} ${b.x1 - b.x0 + 2 * pad} ${b.y1 - b.y0 + 2 * pad + 10}`);
      clone.setAttribute('xmlns', NS);
      const bg = document.createElementNS(NS, 'rect');
      bg.setAttribute('x', b.x0 - pad); bg.setAttribute('y', b.y0 - pad - 10); bg.setAttribute('width', b.x1 - b.x0 + 2 * pad); bg.setAttribute('height', b.y1 - b.y0 + 2 * pad + 10);
      bg.setAttribute('fill', getComputedStyle(this.wrap).getPropertyValue('--paper'));
      clone.insertBefore(bg, clone.firstChild);
      return new XMLSerializer().serializeToString(clone);
    }
  }
  Canvas.prototype.routeVer = 0;

  S.Canvas = Canvas;
})(globalThis.Studio = globalThis.Studio || {});
