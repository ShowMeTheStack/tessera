// Property tests of the Studio's router on random netlists (node router-props.mjs [cases] [seed]).
// For every routed net: its wires are orthogonal; they never pass through a part (only a pin's own
// point and the step out of it touch a part's clearance); no two nets share a stretch of wire; a net
// is connected and reaches all its pins; and a junction dot sits exactly where three or more of its
// wire ends or bends meet.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, '../../tessera-studio/src/main/web/router.js'), 'utf8').split('(function (S)')[0];
const studioRoute = new Function(src + '\nreturn studioRoute;')();

const cases = Number(process.argv[2] || 200);
const dense = process.argv[4] === 'dense';   // parts closer together and more nets: the router has to work for it
let seed = Number(process.argv[3] || 1);
const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
const ri = (a, b) => a + Math.floor(rnd() * (b - a + 1));

function problem() {
  // parts on a loose grid, so that they do not overlap; pins on their left and right edges
  const parts = [], pins = [];
  const cols = ri(2, 5), rows = ri(1, 4);
  for (let c = 0; c < cols; c++) for (let r = 0; r < rows; r++) {
    if (rnd() < 0.2) continue;
    const w = ri(4, 8), h = ri(2, 4) * 2, x = c * (dense ? 13 : 18) + ri(0, 3), y = r * (dense ? 11 : 14) + ri(0, 3);
    const p = { x, y, w, h };
    parts.push(p);
    for (let k = 1; k < h; k += 2) { pins.push({ x, y: y + k, side: 'l', part: p }); pins.push({ x: x + w, y: y + k, side: 'r', part: p }); }
  }
  // nets: each takes a driver on a right edge and one to three readers on left edges
  const outs = pins.filter((q) => q.side === 'r'), ins = pins.filter((q) => q.side === 'l');
  const used = new Set(), nets = [];
  const take = (list) => { if (!list.length) return null; for (let t = 0; t < 20; t++) { const q = list[ri(0, list.length - 1)]; const k = q.x + ',' + q.y; if (!used.has(k)) { used.add(k); return q; } } return null; };
  const n = ri(1, Math.min(dense ? 30 : 10, outs.length));
  for (let i = 0; i < n; i++) {
    const d = take(outs); if (!d) break;
    const rs = []; for (let k = ri(1, 3); k > 0; k--) { const q = take(ins); if (q && q.part !== d.part) rs.push(q); }
    if (rs.length) nets.push({ id: 'n' + i, pins: [d, ...rs].map(({ x, y, side }) => ({ x, y, side })) });
  }
  return { parts, nets };
}

function check(pb, res) {
  const errs = [];
  const inside = (p, x, y) => x > p.x - 1 && x < p.x + p.w + 1 && y > p.y - 1 && y < p.y + p.h + 1;   // the clearance ring and the body
  const pinPts = new Set();
  for (const n of pb.nets) for (const q of n.pins) {
    pinPts.add(q.x + ',' + q.y);
    const d = { r: [1, 0], l: [-1, 0], t: [0, -1], b: [0, 1] }[q.side];
    pinPts.add((q.x + d[0]) + ',' + (q.y + d[1]));
  }
  const owner = new Map();   // unit edge -> net
  for (const n of pb.nets) {
    const r = res[n.id];
    if (!r) { errs.push(n.id + ': not routed'); continue; }
    if (r.failed) continue;                           // drawn dashed and reported as a problem; counted apart
    const deg = new Map(), adj = new Map();
    const add = (a, b) => { const k = a < b ? a + '|' + b : b + '|' + a; if (owner.has(k) && owner.get(k) !== n.id) errs.push(n.id + ' runs along ' + owner.get(k) + ' at ' + k); owner.set(k, n.id); };
    for (const [x1, y1, x2, y2] of r.segs) {
      if (x1 !== x2 && y1 !== y2) { errs.push(n.id + ': a diagonal segment'); continue; }
      const dx = Math.sign(x2 - x1), dy = Math.sign(y2 - y1), len = Math.abs(x2 - x1) + Math.abs(y2 - y1);
      for (let k = 0; k <= len; k++) {
        const x = x1 + dx * k, y = y1 + dy * k, key = x + ',' + y;
        if (!pinPts.has(key)) for (const p of pb.parts) if (inside(p, x, y)) { errs.push(n.id + ' passes through a part at ' + key); break; }
        if (k < len) {
          const a = key, b = (x + dx) + ',' + (y + dy);
          add(a, b);
          (adj.get(a) || adj.set(a, new Set()).get(a)).add(b); (adj.get(b) || adj.set(b, new Set()).get(b)).add(a);
        }
      }
    }
    // connected, and through every pin
    const start = n.pins[0].x + ',' + n.pins[0].y, seen = new Set([start]), st = [start];
    while (st.length) { const a = st.pop(); for (const b of adj.get(a) || []) if (!seen.has(b)) { seen.add(b); st.push(b); } }
    for (const q of n.pins) if (!seen.has(q.x + ',' + q.y)) errs.push(n.id + ': pin ' + q.x + ',' + q.y + ' not reached');
    // dots exactly where three or more wire directions meet
    const dots = new Set(r.dots.map(([x, y]) => x + ',' + y));
    for (const [pt, nb] of adj) if ((nb.size >= 3) !== dots.has(pt)) errs.push(n.id + (nb.size >= 3 ? ': a junction without a dot at ' : ': a dot that is not a junction at ') + pt);
  }
  return errs;
}

// the checker checks: planted faults must be found (node router-props.mjs selftest)
if (process.argv[2] === 'selftest') {
  const pb = { parts: [{ x: 10, y: 0, w: 4, h: 4 }], nets: [{ id: 'a', pins: [{ x: 0, y: 1, side: 'r' }, { x: 30, y: 1, side: 'l' }] }, { id: 'b', pins: [{ x: 0, y: 6, side: 'r' }, { x: 30, y: 6, side: 'l' }] }] };
  const faults = {
    'through a part': { a: { segs: [[0, 1, 30, 1]], dots: [] }, b: { segs: [[0, 6, 30, 6]], dots: [] } },
    'runs along': { a: { segs: [[0, 1, 0, 6], [0, 6, 30, 6], [30, 6, 30, 1]], dots: [] }, b: { segs: [[0, 6, 30, 6]], dots: [] } },
    'diagonal': { a: { segs: [[0, 1, 30, 2]], dots: [] }, b: { segs: [[0, 6, 30, 6]], dots: [] } },
    'not reached': { a: { segs: [[0, 1, 5, 1]], dots: [] }, b: { segs: [[0, 6, 30, 6]], dots: [] } },
    'not a junction': { a: { segs: [[0, 1, 0, -3], [0, -3, 30, -3], [30, -3, 30, 1]], dots: [[0, -3]] }, b: { segs: [[0, 6, 30, 6]], dots: [] } },
  };
  let ok = true;
  for (const [what, res] of Object.entries(faults)) { const e = check(pb, res); const hit = e.some((x) => x.includes(what)); console.log((hit ? 'found   ' : 'MISSED  ') + what + (hit ? '' : ' (' + e.join('; ') + ')')); ok &&= hit; }
  process.exit(ok ? 0 : 1);
}

let bad = 0, nets = 0, failedNets = 0;
const t0 = Date.now();
for (let c = 0; c < cases; c++) {
  const pb = problem();
  const res = studioRoute({ parts: pb.parts, nets: pb.nets });
  nets += pb.nets.length;
  failedNets += pb.nets.filter((n) => res[n.id] && res[n.id].failed).length;
  const errs = check(pb, res);
  if (errs.length) { bad++; if (bad <= 5) console.log('case ' + c + ':\n  ' + errs.slice(0, 6).join('\n  ')); }
}
console.log(`${cases} random netlists, ${nets} nets: ${cases - bad} clean, ${bad} with violations; ${failedNets} nets unroutable (drawn dashed); ${Date.now() - t0} ms`);
process.exit(bad ? 1 : 0);
