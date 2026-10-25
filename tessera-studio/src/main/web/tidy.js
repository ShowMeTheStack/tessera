// Tessera Studio: automatic layout ("Tidy up"). Parts are put in columns by their distance from the
// inputs (signals flow left to right; a wire into a register's input is feedback and does not count),
// ordered within each column to keep wires short and uncrossed (barycentre sweeps), and then placed
// so that each part's first input lines up with what drives it, without overlapping. The router then
// draws the wires.
(function (S) {
  'use strict';

  function layout(parts, nets, opts = {}) {
    const GAPX = opts.gapx ?? 8, GAPY = opts.gapy ?? 3;
    const geo = new Map(parts.map((p) => [p.id, S.Geom.of({ ...p, x: 0, y: 0 })]));
    const dirOf = (pid, pin) => { const g = geo.get(pid); const q = g && g.pins.find((x) => x.name === pin); return q && q.dir; };
    // edges driver part -> reader part, with the reader's pin
    const succ = new Map(parts.map((p) => [p.id, []])), pred = new Map(parts.map((p) => [p.id, []]));
    for (const n of nets) {
      const drv = n.pins.find(([p, q]) => dirOf(p, q) === 'out');
      if (!drv) continue;
      for (const [p, q] of n.pins) {
        if (p === drv[0] || dirOf(p, q) !== 'in') continue;
        succ.get(drv[0]).push(p);
        pred.get(p).push(drv[0]);
      }
    }
    // break remaining cycles (combinational loops) by DFS
    const state = new Map(), back = new Set();
    const dfs = (u) => {
      state.set(u, 1);
      for (const v of succ.get(u)) {
        if (state.get(v) === 1) back.add(u + '>' + v);
        else if (!state.get(v)) dfs(v);
      }
      state.set(u, 2);
    };
    // start from the inputs, then the other sources, so that the edges found to close a loop are the feedback ones
    const srcFirst = [...parts].sort((a, b) => (a.type === 'in' ? 0 : pred.get(a.id).length ? 2 : 1) - (b.type === 'in' ? 0 : pred.get(b.id).length ? 2 : 1));
    for (const p of srcFirst) if (!state.get(p.id)) dfs(p.id);
    // longest path layering
    const layer = new Map();
    const order = topo(parts.map((p) => p.id), (u) => succ.get(u).filter((v) => !back.has(u + '>' + v)));
    for (const u of order) {
      const ps = pred.get(u).filter((v) => !back.has(v + '>' + u));
      layer.set(u, ps.length ? Math.max(...ps.map((v) => layer.get(v) + 1)) : 0);
    }
    // outputs in the last column, inputs in the first
    const maxL = Math.max(0, ...layer.values());
    for (const p of parts) {
      if (p.type === 'out') layer.set(p.id, maxL + (parts.some((q) => q.type !== 'out' && layer.get(q.id) === maxL) ? 1 : 0));
      if (p.type === 'in') layer.set(p.id, 0);
    }
    let L = Math.max(0, ...layer.values());
    let cols = Array.from({ length: L + 1 }, () => []);
    for (const p of parts) cols[layer.get(p.id)].push(p.id);
    // wrap columns that would be much taller than the drawing is wide into several
    // the column height that makes the drawing about as wide as it is tall (a little wider)
    let area = 0;
    for (const p of parts) { const g = geo.get(p.id); area += (g.w + GAPX) * (g.h + GAPY + 1); }
    const MAXH = opts.maxColumnHeight ?? Math.max(60, Math.ceil(Math.sqrt(area) * 0.85));
    const wrapped = [];
    for (const c of cols) {
      let cur = [], hgt = 0;
      for (const id of c) {
        const g = geo.get(id);
        if (cur.length && hgt + g.h > MAXH) { wrapped.push(cur); cur = []; hgt = 0; }
        cur.push(id); hgt += g.h + GAPY + 1;
      }
      wrapped.push(cur);
    }
    if (wrapped.length !== cols.length) {
      cols = wrapped;
      L = cols.length - 1;
      cols.forEach((c, l) => c.forEach((id) => layer.set(id, l)));
    }
    // ordering: barycentre sweeps
    const pos = new Map();
    const setPos = () => cols.forEach((c) => c.forEach((id, i) => pos.set(id, i)));
    setPos();
    const bary = (id, nbrs) => { const xs = nbrs.map((v) => pos.get(v)).filter((x) => x !== undefined); return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : pos.get(id); };
    for (let it = 0; it < 6; it++) {
      for (let l = 1; l <= L; l++) cols[l].sort((a, b) => bary(a, pred.get(a)) - bary(b, pred.get(b))), cols[l].forEach((id, i) => pos.set(id, i));
      for (let l = L - 1; l >= 0; l--) cols[l].sort((a, b) => bary(a, succ.get(a)) - bary(b, succ.get(b))), cols[l].forEach((id, i) => pos.set(id, i));
    }
    // x: columns by their widest part
    const xs = [];
    let x = 0;
    for (let l = 0; l <= L; l++) { xs.push(x); x += Math.max(4, ...cols[l].map((id) => geo.get(id).w)) + GAPX; }
    // y: each part wants its first input level with its driver's output; keep column order, no overlap
    const yOf = new Map();
    const pinY = (id, name) => { const g = geo.get(id), q = g.pins.find((p) => p.name === name); return q ? q.y : 0; };
    for (let l = 0; l <= L; l++) {
      let floor = -Infinity;
      for (const id of cols[l]) {
        const g = geo.get(id);
        let want = floor === -Infinity ? 0 : floor;
        const firstIn = g.pins.find((p) => p.dir === 'in');
        if (firstIn) {
          const net = nets.find((n) => n.pins.some(([p, q]) => p === id && q === firstIn.name));
          const drv = net && net.pins.find(([p, q]) => dirOf(p, q) === 'out');
          if (drv && yOf.has(drv[0])) want = yOf.get(drv[0]) + pinY(drv[0], drv[1]) - firstIn.y;
        }
        const y = Math.max(want, floor === -Infinity ? want : floor);
        yOf.set(id, y);
        floor = y + g.h + GAPY + 1;
      }
    }
    const out = new Map();
    for (const p of parts) out.set(p.id, { x: xs[layer.get(p.id)] + Math.round((Math.max(4, ...cols[layer.get(p.id)].map((id) => geo.get(id).w)) - geo.get(p.id).w) / 2), y: Math.round(yOf.get(p.id)) });
    return out;
  }

  function topo(ids, next) {
    const indeg = new Map(ids.map((i) => [i, 0]));
    for (const u of ids) for (const v of next(u)) indeg.set(v, (indeg.get(v) || 0) + 1);
    const q = ids.filter((i) => !indeg.get(i)), out = [];
    while (q.length) {
      const u = q.shift();
      out.push(u);
      for (const v of next(u)) { indeg.set(v, indeg.get(v) - 1); if (!indeg.get(v)) q.push(v); }
    }
    for (const i of ids) if (!out.includes(i)) out.push(i);
    return out;
  }

  // tidy the whole drawing (or the given part ids) as one undo step
  function tidy(doc, ids) {
    const set = ids && ids.length ? new Set(ids) : null;
    const parts = doc.d.parts.filter((p) => !set || set.has(p.id));
    const nets = doc.d.nets.map((n) => ({ ...n, pins: n.pins.filter(([p]) => !set || set.has(p)) })).filter((n) => n.pins.length > 1);
    if (!parts.length) return;
    const pos = layout(parts, nets);
    const x0 = set ? Math.min(...parts.map((p) => p.x)) : 0, y0 = set ? Math.min(...parts.map((p) => p.y)) : 0;
    doc.edit('tidy', () => {
      for (const p of parts) { const q = pos.get(p.id); p.x = x0 + q.x; p.y = y0 + q.y; p.rot = 0; p.mir = false; }
      // manual bends belong to the old placement: the wires they shape are routed afresh
      for (const n of doc.d.nets) if (n.via && n.pins.some(([pid]) => !set || set.has(pid))) delete n.via;
    });
  }

  S.Tidy = { layout, tidy };
})(globalThis.Studio = globalThis.Studio || {});
(function (S) {
  'use strict';
  // The register-transfer overview of a drawing made from Java: logic boxes are folded away and each
  // input they fed is joined, by one dashed tree, to the signals the logic read. Values on those
  // trees are not shown (they are not one signal); the inspector still has each expression.
  function overview(d) {
    const exprs = new Map(d.parts.filter((p) => p.type === 'expr').map((p) => [p.id, p]));
    if (!exprs.size) return d;
    const netOfPin = new Map();
    for (const n of d.nets) for (const [p, q] of n.pins) netOfPin.set(p + '.' + q, n);
    const sources = new Map();   // expr id -> [[part, pin] of the drivers of its inputs]
    const drvOf = (n) => n.pins.find(([p, q]) => { const part = d.parts.find((x) => x.id === p); const pin = part && part.pins.find((x) => x.name === q); return pin && pin.dir === 'out'; });
    const parts = d.parts.filter((p) => !exprs.has(p.id));
    const nets = [];
    for (const n of d.nets) {
      const drv = drvOf(n);
      if (drv && exprs.has(drv[0])) continue;            // the output of a logic box: replaced below
      const pins = n.pins.filter(([p]) => !exprs.has(p));
      if (pins.length > 1) nets.push({ ...n, pins });
    }
    // for every pin a logic box drove: one tree from the drivers of the box's inputs
    let k = 0;
    for (const n of d.nets) {
      const drv = drvOf(n);
      if (!drv || !exprs.has(drv[0])) continue;
      const e = exprs.get(drv[0]);
      const from = [];
      for (const pin of e.pins.filter((x) => x.dir === 'in')) {
        const m = netOfPin.get(e.id + '.' + pin.name);
        const src = m && drvOf(m);
        if (src && !exprs.has(src[0]) && !from.some(([a, b]) => a === src[0] && b === src[1])) from.push(src);
      }
      for (const [p, q] of n.pins) {
        if (exprs.has(p)) continue;
        const pins = [[p, q], ...from];
        if (pins.length > 1) nets.push({ id: 'l' + k++, logic: true, expr: e.params.expr, pins });
      }
    }
    return { ...d, parts, nets, overview: true };
  }
  S.Tidy.overview = overview;
})(globalThis.Studio = globalThis.Studio || {});
