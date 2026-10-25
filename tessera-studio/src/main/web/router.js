// Tessera Studio: the wire router. Wires run on grid points, orthogonally, around parts with a
// clearance of one grid unit. A net is grown as a tree: each pin is joined to the part of the net
// already routed by an A* search whose cost counts length, bends and wires running close to
// other nets. Two nets may cross only at right angles, where neither bends, and never share a
// stretch. Points where three or more branches of a net meet are junctions (drawn as dots).
//
// route({parts: [{x,y,w,h}], nets: [{id, pins: [{x,y,side}]}]}) ->
//   {id: {segs: [[x1,y1,x2,y2], ...], dots: [[x,y], ...], failed: bool}}
// The function is self-contained so that it also runs in a Web Worker.
function studioRoute(problem, onProgress) {
  'use strict';
  const T0 = Date.now(), BUDGET = problem.budgetMs || 25000;
  const BEND = 6, NEAR = 0.7, CROSS = 2, JOIN = 2;
  const parts = problem.parts, nets = problem.nets;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  const grow = (x, y) => { if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y; };
  for (const p of parts) { grow(p.x, p.y); grow(p.x + p.w, p.y + p.h); }
  for (const n of nets) for (const q of n.pins) grow(q.x, q.y);
  const out = {};
  if (!isFinite(x0)) return out;
  const M = 6;
  x0 -= M; y0 -= M; x1 += M; y1 += M;
  const W = x1 - x0 + 1, H = y1 - y0 + 1, N = W * H;
  const idx = (x, y) => (y - y0) * W + (x - x0);
  const blocked = new Uint8Array(N);          // 1: inside a part or its clearance ring
  const ownH = new Int32Array(N), ownV = new Int32Array(N), node = new Int32Array(N);  // net + 1
  for (const p of parts) {
    for (let y = p.y - 1; y <= p.y + p.h + 1; y++)
      for (let x = p.x - 1; x <= p.x + p.w + 1; x++)
        if (x >= x0 && x <= x1 && y >= y0 && y <= y1) blocked[idx(x, y)] = 1;
  }
  const DX = [1, 0, -1, 0], DY = [0, 1, 0, -1];  // 0 right, 1 down, 2 left, 3 up
  const SIDE = { r: 0, b: 1, l: 2, t: 3, n: -1 };   // n: a free point (a manual bend), left in any direction
  // each pin may use its own point and the one outside it (its escape), whatever is blocked
  const pinOwner = new Map();
  nets.forEach((n, k) => n.pins.forEach((q) => {
    const d = SIDE[q.side] ?? 0;
    pinOwner.set(idx(q.x, q.y), k + 1);
    if (d < 0) return;
    const ex = q.x + DX[d], ey = q.y + DY[d];
    if (ex >= x0 && ex <= x1 && ey >= y0 && ey <= y1 && !pinOwner.has(idx(ex, ey))) pinOwner.set(idx(ex, ey), k + 1);
  }));

  // a binary heap of [cost, state]
  const heapC = [], heapS = [];
  const push = (c, s) => {
    let i = heapC.length; heapC.push(c); heapS.push(s);
    while (i > 0) { const p = (i - 1) >> 1; if (heapC[p] <= c) break; heapC[i] = heapC[p]; heapS[i] = heapS[p]; i = p; }
    heapC[i] = c; heapS[i] = s;
  };
  const pop = () => {
    const s = heapS[0], lc = heapC.pop(), ls = heapS.pop();
    if (heapC.length) {
      let i = 0;
      for (;;) {
        let l = 2 * i + 1, m = i;
        if (l < heapC.length && heapC[l] < lc) m = l;
        if (l + 1 < heapC.length && heapC[l + 1] < (m === i ? lc : heapC[m])) m = l + 1;
        if (m === i) break;
        heapC[i] = heapC[m]; heapS[i] = heapS[m]; i = m;
      }
      heapC[i] = lc; heapS[i] = ls;
    }
    return s;
  };
  const dist = new Float64Array(N * 4), prev = new Int32Array(N * 4), stamp = new Int32Array(N * 4);
  let gen = 0;
  const ownerOf = new Int32Array(N);       // pin owners as a flat array: net + 1
  for (const [i, n] of pinOwner) ownerOf[i] = n;

  function free(i, net, dir) {
    const own = ownerOf[i];
    if (own && own !== net) return false;
    if (blocked[i] && own !== net) return false;
    const h = dir === 0 || dir === 2;
    const same = h ? ownH[i] : ownV[i];
    if (same && same !== net) return false;               // never run along another net
    return true;
  }

  // route from pin (px, py, escape dir d) to any point of tree (a Set of indices); returns indices or null
  // search within a window around the pin and the tree, widened when nothing is found
  function search(net, px, py, d, tree, tb, avoid) {
    for (const m of [12, 40, 1e9]) {
      const r = searchIn(net, px, py, d, tree, tb, Math.min(px, tb.x0) - m, Math.min(py, tb.y0) - m, Math.max(px, tb.x1) + m, Math.max(py, tb.y1) + m, avoid);
      if (r) return r;
    }
    return null;
  }

  // avoid: points of the net's earlier legs that this leg must not run over (manual bends)
  function searchIn(net, px, py, d, tree, tb, wx0, wy0, wx1, wy1, avoid) {
    gen++;
    heapC.length = 0; heapS.length = 0;
    const start = idx(px, py);
    const hEst = (x, y) => Math.max(0, tb.x0 - x, x - tb.x1) + Math.max(0, tb.y0 - y, y - tb.y1);
    for (const d0 of d < 0 ? [0, 1, 2, 3] : [d]) {
      const s0 = start * 4 + d0;
      dist[s0] = 0; prev[s0] = -1; stamp[s0] = gen;
      push(hEst(px, py), s0);
    }
    let steps = 0;
    while (heapC.length) {
      const sc = heapC[0], s = pop(), i = s >> 2, dir = s & 3;
      const c = dist[s];
      if (++steps > 600000) return null;
      if (tree.has(i) && i !== start) {
        const path = [];
        for (let t = s; t !== -1; t = prev[t]) path.push(t >> 2);
        return path.reverse();
      }
      const x = (i % W) + x0, y = Math.floor(i / W) + y0;
      if (sc > c + hEst(x, y) + 1e-9) continue;          // a stale entry: this state was reached more cheaply since
      for (let nd = 0; nd < 4; nd++) {
        if (nd === ((dir + 2) & 3)) continue;
        if (i === start && d >= 0 && nd !== d) continue;    // leave a pin straight out
        const nx = x + DX[nd], ny = y + DY[nd];
        if (nx < x0 || nx > x1 || ny < y0 || ny > y1 || nx < wx0 || nx > wx1 || ny < wy0 || ny > wy1) continue;
        const j = idx(nx, ny);
        if (!free(j, net, nd)) continue;
        if (avoid && avoid.has(j) && !tree.has(j)) continue;
        const h = nd === 0 || nd === 2;
        const other = h ? ownV[j] : ownH[j];
        let cost = 1;
        if (nd !== dir && i !== start) {
          if (ownH[i] && ownH[i] !== net || ownV[i] && ownV[i] !== net) continue;   // no bends on another net
          cost += BEND;
        }
        if (other && other !== net) {
          if (node[j] && node[j] !== net) continue;          // never cross at another net's bend or branch
          if (tree.has(j)) continue;
          cost += CROSS;
        }
        if (tree.has(j) && !pinOwner.has(j)) cost += JOIN;
        for (let k = 0; k < 4; k++) {
          const ax = nx + DX[k], ay = ny + DY[k];
          if (ax < x0 || ax > x1 || ay < y0 || ay > y1) continue;
          const a = idx(ax, ay);
          if ((ownH[a] && ownH[a] !== net) || (ownV[a] && ownV[a] !== net)) { cost += NEAR; break; }
        }
        const ns = j * 4 + nd, nc = c + cost;
        if (stamp[ns] !== gen || nc < dist[ns]) {
          stamp[ns] = gen; dist[ns] = nc; prev[ns] = s;
          push(nc + hEst(nx, ny), ns);
        }
      }
    }
    return null;
  }

  // order: two-pin nets first, then by size
  const order = nets.map((n, k) => k).sort((a, b) => {
    const span = (n) => { let a0 = Infinity, a1 = -Infinity, b0 = Infinity, b1 = -Infinity; for (const q of n.pins) { a0 = Math.min(a0, q.x); a1 = Math.max(a1, q.x); b0 = Math.min(b0, q.y); b1 = Math.max(b1, q.y); } return a1 - a0 + b1 - b0; };
    const na = nets[a].pins.length, nb = nets[b].pins.length;
    return (na === 2 ? 0 : 1) - (nb === 2 ? 0 : 1) || span(nets[a]) - span(nets[b]);
  });

  // rip-up and retry: when nets fail, route again with them first (up to three passes), keep the best pass
  let best = null, bestFailed = Infinity, ord = order;
  for (let passNo = 0; passNo < 3; passNo++) {
    const res = routeOnce(ord, passNo === 0);
    const failedIds = Object.keys(res).filter((id) => res[id].failed);
    if (failedIds.length < bestFailed) { best = res; bestFailed = failedIds.length; }
    if (!failedIds.length || Date.now() - T0 > BUDGET * 0.6) break;
    const f = new Set(failedIds);
    ord = [...ord.filter((k) => f.has(nets[k].id)), ...ord.filter((k) => !f.has(nets[k].id))];
  }
  return best;

  function routeOnce(order, report) {
  ownH.fill(0); ownV.fill(0); node.fill(0);
  const out = {};
  let done = 0, lastPost = Date.now();
  for (const k of order) {
    const n = nets[k], net = k + 1;
    const res = { segs: [], dots: [], failed: false };
    out[n.id] = res;
    if (report && onProgress && Date.now() - lastPost > 250) { onProgress(out, done, nets.length); lastPost = Date.now(); }
    done++;
    if (n.pins.length < 2) continue;
    if (Date.now() - T0 > BUDGET) {                       // out of time: draw the rest as straight L-shapes
      res.failed = true; res.loose = [];
      for (let i = 1; i < n.pins.length; i++) res.loose.push([n.pins[i].x, n.pins[i].y, n.pins[0].x, n.pins[0].y]);
      continue;
    }
    const pins = [...n.pins];
    // start from the leftmost pin (usually the driver), then join the nearest pin each time; a net with
    // manual bends is joined in its given order, driver, bends, reader
    if (!n.ordered) pins.sort((a, b) => a.x - b.x || a.y - b.y);
    const first = pins.shift();
    let last = first;
    const tree = new Set([idx(first.x, first.y)]);
    const edges = new Set();
    const tb = { x0: first.x, x1: first.x, y0: first.y, y1: first.y };
    const addPoint = (i) => {
      tree.add(i);
      const x = (i % W) + x0, y = Math.floor(i / W) + y0;
      tb.x0 = Math.min(tb.x0, x); tb.x1 = Math.max(tb.x1, x); tb.y0 = Math.min(tb.y0, y); tb.y1 = Math.max(tb.y1, y);
    };
    // the first pin's escape point belongs to the tree, so branches leave the pin straight
    {
      const d = SIDE[first.side] ?? 0, ex = first.x + DX[Math.max(0, d)], ey = first.y + DY[Math.max(0, d)];
      if (d >= 0 && ex >= x0 && ex <= x1 && ey >= y0 && ey <= y1) {
        const a = idx(first.x, first.y), b = idx(ex, ey);
        addPoint(b);
        edges.add(Math.min(a, b) + ':' + Math.max(a, b));
        (d === 0 || d === 2 ? ownH : ownV)[a] = net; (d === 0 || d === 2 ? ownH : ownV)[b] = net;
        node[a] = net;
      }
    }
    while (pins.length) {
      let bi = 0, bd = Infinity;
      if (n.ordered) bd = -1; else pins.forEach((q, i) => { const dd = Math.max(0, tb.x0 - q.x, q.x - tb.x1) + Math.max(0, tb.y0 - q.y, q.y - tb.y1); if (dd < bd) { bd = dd; bi = i; } });
      const q = pins.splice(bi, 1)[0];
      // an ordered net (manual bends) goes from one point to the next, never to the nearest branch
      let target = tree, ttb = tb;
      const strict = n.ordered && last !== first;   // the first leg joins the driver and its escape as usual
      if (strict) { target = new Set([idx(last.x, last.y)]); ttb = { x0: last.x, x1: last.x, y0: last.y, y1: last.y }; }
      const path = search(net, q.x, q.y, SIDE[q.side] ?? 0, target, ttb, strict ? tree : null);
      last = q;
      if (!path) { res.failed = true; res.loose = res.loose || []; res.loose.push([q.x, q.y, first.x, first.y]); continue; }
      for (let i = 0; i < path.length; i++) {
        const a = path[i];
        if (i + 1 < path.length) {
          const b = path[i + 1];
          const horiz = Math.abs(a - b) === 1;
          (horiz ? ownH : ownV)[a] = net; (horiz ? ownH : ownV)[b] = net;
          edges.add(Math.min(a, b) + ':' + Math.max(a, b));
        }
        if (i === 0 || i === path.length - 1) node[a] = net;
        else {
          const pa = path[i - 1], nb = path[i + 1];
          if (Math.abs(pa - a) !== Math.abs(nb - a)) node[a] = net;   // a bend
        }
        addPoint(a);
      }
    }
    // edges to segments: merge straight runs, find points of degree >= 3
    const deg = new Map(), adj = new Map();
    for (const e of edges) {
      const [a, b] = e.split(':').map(Number);
      deg.set(a, (deg.get(a) || 0) + 1); deg.set(b, (deg.get(b) || 0) + 1);
      (adj.get(a) || adj.set(a, []).get(a)).push(b); (adj.get(b) || adj.set(b, []).get(b)).push(a);
    }
    const pt = (i) => [(i % W) + x0, Math.floor(i / W) + y0];
    for (const [i, d] of deg) if (d >= 3) { res.dots.push(pt(i)); node[i] = net; }
    const used = new Set();
    for (const e of edges) {
      if (used.has(e)) continue;
      const [a, b] = e.split(':').map(Number);
      const step = b - a;   // 1 (horizontal) or W (vertical)
      let s = a, t = b;
      while (deg.get(s) === 2 && adj.get(s).includes(s - step) && !used.has((s - step) + ':' + s)) s -= step;
      while (deg.get(t) === 2 && adj.get(t).includes(t + step) && !used.has(t + ':' + (t + step))) t += step;
      for (let u = s; u < t; u += step) used.add(u + ':' + (u + step));
      const [ax, ay] = pt(s), [bx, by] = pt(t);
      res.segs.push([ax, ay, bx, by]);
    }
  }
  return out;
  }
}
(function (S) {
  'use strict';
  // run the router in a worker; fall back to the page when workers are unavailable
  let worker = null, seq = 0;
  const waiting = new Map();
  try {
    const src = studioRoute.toString() + '\nonmessage = (e) => postMessage({ id: e.data.id, result: studioRoute(e.data.problem, (part, done, total) => postMessage({ id: e.data.id, partial: part, done, total })) });';
    worker = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })));
    worker.onmessage = (e) => {
      const r = waiting.get(e.data.id);
      if (!r) return;
      if (e.data.partial) { r.progress && r.progress(e.data.partial, e.data.done, e.data.total); return; }
      waiting.delete(e.data.id); r.resolve(e.data.result);
    };
  } catch { worker = null; }
  // progress(partial, done, total) is called while a large problem is being routed
  function route(problem, progress) {
    if (!worker) return Promise.resolve(studioRoute(problem));
    return new Promise((resolve) => { const id = ++seq; waiting.set(id, { resolve, progress }); worker.postMessage({ id, problem }); });
  }
  S.Router = { route, routeNow: studioRoute };
})(globalThis.Studio = globalThis.Studio || {});
