// Tessera Studio: where each part's pins are. Sizes and pin positions are in grid units (10 px);
// a part sits at (x, y), turned by rot (0, 90, 180, 270, clockwise) and mirrored left-right by mir.
// Pins lie on the box outline at whole grid points, so wires always start on the grid.
(function (S) {
  'use strict';
  const U = 10;

  // the label widths decide a box's width: ~0.62 em per character at 10.5 px
  const textW = (s) => String(s || '').length * 6.5;

  // the unrotated box and pins of a part: {w, h, pins: [{name, dir, side, label, x, y}]}
  function base(part, pinSpecs) {
    const t = part.type, sym = S.Parts.symbol(t);
    const ins = pinSpecs.filter((p) => p.side === 'l'), outs = pinSpecs.filter((p) => p.side === 'r'), bots = pinSpecs.filter((p) => p.side === 'b');
    const place = (list, x, y0, step) => list.map((p, k) => ({ ...p, x, y: y0 + k * step }));
    let w, hgt, pins;
    switch (sym) {
      case 'port': {
        const nm = (part.params && part.params.name) || '';
        const look = (part.params && part.params.look) || 'port', wd = Number((part.params || {}).width) || 1;
        if (look !== 'port') {
          // a board part: a panel with its name on top and one element a bit (or a digit per four bits)
          const n = look === 'hex' ? Math.ceil(Math.min(wd, 32) / 4) : Math.min(wd, 16);
          w = Math.max(6, Math.ceil((textW(nm) + 14) / U), (look === 'hex' ? 3 : 2) * n + 2); hgt = look === 'hex' ? 6 : 5;
          pins = t === 'in' ? place(outs, w, 3, 2) : place(ins, 0, 3, 2);
          break;
        }
        w = Math.max(6, Math.ceil((textW(nm) + 26) / U)); hgt = 2;
        pins = t === 'in' ? place(outs, w, 1, 2) : place(ins, 0, 1, 2);
        break;
      }
      case 'const': case 'timing': {
        const label = sym === 'const' ? String((part.params && part.params.value) ?? '0') : '';
        w = sym === 'timing' ? 8 : Math.max(4, Math.ceil((textW(label) + 16) / U)); hgt = 2;
        pins = place(outs, w, 1, 2);
        break;
      }
      case 'gate': {
        const n = ins.length; hgt = Math.max(4, 2 * n);
        w = 6 + (t === 'xor' || t === 'xnor' ? 1 : 0) + (t[0] === 'n' || t === 'xnor' ? 1 : 0);
        pins = [...place(ins, 0, (hgt - 2 * (n - 1)) / 2, 2), ...place(outs, w, hgt / 2, 2)];
        break;
      }
      case 'check': {
        const nm = (part.params && part.params.name) || '';
        w = Math.max(8, Math.ceil((textW(nm) + 40) / U)); hgt = 2;
        pins = place(ins, 0, 1, 2);
        break;
      }
      case 'not': w = 5; hgt = 2; pins = [...place(ins, 0, 1, 2), ...place(outs, w, 1, 2)]; break;
      case 'reduce': w = 5; hgt = 4; pins = [...place(ins, 0, 2, 2), ...place(outs, w, 2, 2)]; break;
      case 'mux': {
        const n = ins.length; hgt = 2 * n + 2; w = 4;
        pins = [...place(ins, 0, 2, 2), ...bots.map((p) => ({ ...p, x: 2, y: hgt })), ...place(outs, w, hgt / 2, 2)];
        break;
      }
      case 'reg': {
        hgt = Math.max(6, 2 * ins.length + 4);   // room under the inputs for the clock triangle
        const wd = Number((part.params || {}).width) || 1;
        w = Math.max(6, Math.ceil((textW('0x' + '0'.repeat(Math.ceil(wd / 4))) * 0.95 + 12) / U));   // wide enough for its value
        pins = [...place(ins, 0, 2, 2), ...place(outs, w, 2, 2)];
        break;
      }
      default: { // box; a part with a long title (logic drawn from Java) gets a header band above its pins
        const title = S.Parts.boxTitle(part);
        const head = (part.params && part.params.title) || ['block', 'mem', 'fsm', 'module', 'code'].includes(t) ? 2 : 0;
        const n = Math.max(ins.length, outs.length, 1); hgt = Math.max(4, 2 * n + 2) + head + (t === 'mem' || t === 'fsm' ? 2 : 0);
        // labels that are drawn: inputs always, outputs when there are several (or on a banded box)
        const banded = head > 0;
        const li = Math.max(...ins.map((p) => textW(p.label) * 0.95), 0), lo = outs.length > 1 || banded ? Math.max(...outs.map((p) => textW(p.label) * 0.95), 0) : 0;
        const lw = banded ? li + lo : li + lo + (li || lo ? textW(title) * 1.15 + 12 : 0);
        w = Math.max(6, Math.ceil((Math.max(textW(title) * (head ? 1.15 : 1) + 16 + (t === 'code' ? 36 : 0), lw + (head ? 30 : 20), t === 'mem' ? 100 : 0,
          t === 'fsm' ? Math.max(...String((part.params || {}).states || '').split(/\s+/).map(textW), 0) * 1.3 + 24 : 0)) / U / 2) * 2);
        pins = [...place(ins, 0, 2 + head, 2), ...place(outs, w, outs.length === 1 ? (head ? 2 + head : hgt / 2) : 2 + head, 2), ...bots.map((p, k) => ({ ...p, x: 2 + 2 * k, y: hgt }))];
      }
    }
    return { w, h: hgt, pins };
  }

  // turn a point of a w x h box by rot and mirror; returns [x, y] in the turned box
  function turn(x, y, w, h, rot, mir) {
    if (mir) x = w - x;
    switch (rot) {
      case 90: return [h - y, x];
      case 180: return [w - x, h - y];
      case 270: return [y, w - x];
      default: return [x, y];
    }
  }
  const SIDE_TURN = { 0: { l: 'l', r: 'r', t: 't', b: 'b' }, 90: { l: 't', t: 'r', r: 'b', b: 'l' }, 180: { l: 'r', r: 'l', t: 'b', b: 't' }, 270: { l: 'b', b: 'r', r: 't', t: 'l' } };

  // the placed geometry of a part: box {x, y, w, h} and pins with absolute positions and escape sides
  const cache = new Map();
  function of(part) {
    const specs = part.pins || S.Parts.pinsOf(part.type);
    const key = part.type + '|' + JSON.stringify(part.params || {}) + '|' + (part.rot || 0) + '|' + (part.mir ? 1 : 0) + '|' + specs.map((p) => p.name).join(',');
    let g = cache.get(key);
    if (!g) {
      const b = base(part, specs);
      const rot = part.rot || 0, mir = !!part.mir;
      const w = rot % 180 ? b.h : b.w, h = rot % 180 ? b.w : b.h;
      const pins = b.pins.map((p) => {
        const [x, y] = turn(p.x, p.y, b.w, b.h, rot, mir);
        let side = p.side;
        if (mir && (side === 'l' || side === 'r')) side = side === 'l' ? 'r' : 'l';
        return { ...p, x, y, side: SIDE_TURN[rot][side] };
      });
      g = { w, h, bw: b.w, bh: b.h, pins };
      if (cache.size > 4000) cache.clear();
      cache.set(key, g);
    }
    return { x: part.x, y: part.y, w: g.w, h: g.h, bw: g.bw, bh: g.bh, rot: part.rot || 0, mir: !!part.mir,
      pins: g.pins.map((p) => ({ ...p, ax: part.x + p.x, ay: part.y + p.y })) };
  }

  function pin(part, name) {
    return of(part).pins.find((p) => p.name === name);
  }

  S.Geom = { U, of, pin, base, turn, textW };
})(globalThis.Studio = globalThis.Studio || {});
