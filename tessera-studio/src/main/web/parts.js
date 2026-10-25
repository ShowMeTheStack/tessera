// Tessera Studio: the part catalogue (from the server) and how each part is drawn. Symbols are
// SVG markup in grid units scaled by U; each part draws in its unrotated box and is turned by a
// transform, with text kept upright and readable.
(function (S) {
  'use strict';
  let types = [], byType = new Map();

  async function load() {
    types = await S.Api.call('parts');
    byType = new Map(types.map((t) => [t.type, t]));
  }
  const get = (t) => byType.get(t);
  const symbol = (t) => (byType.get(t) || {}).symbol || 'box';   // blocks and parts drawn from Java are boxes
  const pinsOf = (t) => (byType.get(t) || {}).pins || [];
  const defaults = (t) => Object.fromEntries(((byType.get(t) || {}).params || []).map((p) => [p.name, p.default]));
  const categories = () => {
    const m = new Map();
    for (const t of types) { if (!m.has(t.category)) m.set(t.category, []); m.get(t.category).push(t); }
    return m;
  };

  // the title written inside a box symbol
  // the clock domain of a part that runs on one of its own, for its top label
  function domTag(p) {
    const d = p && p.domain;
    return d && d !== 'clock' ? '  ⏱ ' + d : '';
  }

  // seven-segment digits for v (null: dark), n digits of four bits, 30 px apart
  const SEG7 = [0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f, 0x77, 0x7c, 0x39, 0x5e, 0x79, 0x71];
  const SEGP = ['M3 1h12l-2 2H5z', 'M16 2v12l-2-1V4z', 'M16 17v12l-2-2v-9z', 'M3 30h12l-2-2H5z', 'M2 17v12l2-2v-9z', 'M2 2v12l2-1V4z', 'M3 15.5l2-1.5h8l2 1.5-2 1.5H5z'];
  function hexDigits(v, width, n) {
    let s = '';
    for (let d = 0; d < n; d++) {
      const nib = v == null ? -1 : Number((v >> BigInt(4 * (n - 1 - d))) & 15n), mask = nib < 0 ? 0 : SEG7[nib];
      s += `<g transform="translate(${d * 30 + 6} 0)">` + SEGP.map((path, i) => `<path class="seg${(mask >> i) & 1 ? ' on' : ''}" d="${path}"/>`).join('') + '</g>';
    }
    return s;
  }

  function boxTitle(part) {
    const p = part.params || {};
    if (p.title) return p.title;
    switch (part.type) {
      case 'add': return p.mode === 'wrap' ? '+ wrap' : '+';
      case 'sub': return p.mode === 'widen' ? '− widen' : '−';
      case 'mul': return '×';
      case 'cmp': return { eq: '==', ne: '!=', lt: '<', le: '<=', gt: '>', ge: '>=' }[p.op] || '==';
      case 'shift': return (p.dir === 'right' ? '>> ' : '<< ') + (p.amount ?? 1);
      case 'dshift': return p.dir === 'right' ? '>>' : '<<';
      case 'slice': return Number(p.hi) === Number(p.lo) ? '[' + p.hi + ']' : '[' + p.hi + ':' + p.lo + ']';
      case 'concat': return '{ }';
      case 'resize': return 'w' + (p.width ?? 8);
      case 'block': return p.block || 'block';
      case 'module': return p.module || 'module';
      case 'code': return p.cls || 'Code';
      case 'mem': return (p.name || 'm');
      case 'fsm': return (p.name || 'fsm');
      case 'table': return 'table';
      case 'counter': return '0‥' + (p.max ?? 9);
      case 'edge': return p.kind === 'falling' ? '↓ edge' : '↑ edge';
      case 'delay': return 'z⁻' + String(p.cycles ?? 2).replace(/\d/g, (d) => '⁰¹²³⁴⁵⁶⁷⁸⁹'[d]);
      case 'debounce': return 'debounce';
      case 'lfsr': return 'LFSR ' + (p.width ?? 8);
      case 'bitop': return p.op || 'popCount';
      case 'onehot': return 'one-hot ' + (p.n ?? 8);
      case 'prienc': return 'priority';
      case 'domain': return '⏱ ' + (p.name || 'fast') + ' ÷' + (p.period ?? 2);
      case 'sync': return 'sync ×' + (p.stages ?? 2);
      default: return (get(part.type) || {}).title || part.type;
    }
  }

  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');

  // how parts are drawn, as the user prefers (the Inspector's View section): an active-low name (rst_n,
  // cs_b, oe_l) as its base with a bar over it; ANSI or IEC symbols
  const prefs = { overbars: true, symbols: 'ansi' };
  const LOW = /^(.+?)_(n|b|l)$/i;
  function activeLow(name) { const m = prefs.overbars && LOW.exec(String(name || '')); return m ? m[1] : null; }

  // a name as SVG text: active low with an overbar (and the name as written in its tooltip)
  function nameText(x, y, name, cls, anchor) {
    const low = activeLow(name);
    const inner = low ? `<tspan text-decoration="overline">${esc(low)}</tspan><title>${esc(name)} (active low)</title>` : esc(name);
    return `<text class="${cls}" x="${x}" y="${y}" text-anchor="${anchor || 'middle'}" dominant-baseline="central" data-up>${inner}</text>`;
  }
  const U = 10;

  // body markup for a part in its unrotated box (bw x bh grid units); text uses data-up so it can be
  // counter-rotated. Pins are drawn as short leads by the canvas.
  function body(part, g) {
    const t = part.type, sym = symbol(t), w = g.bw * U, h = g.bh * U, p = part.params || {};
    const txt = (x, y, s, cls, anchor) => `<text class="${cls}" x="${x}" y="${y}" text-anchor="${anchor || 'middle'}" dominant-baseline="central" data-up>${esc(s)}</text>`;
    switch (sym) {
      case 'port': {
        const name = p.name || '', wd = Number(p.width) || 1;
        const look = p.look || 'port';
        if (look !== 'port') {
          // a board part: switches, push buttons, LEDs (bit 0 on the right, as on a board) or hex digits
          let s = `<rect class="body board-body" x="0" y="0" width="${w}" height="${h}" rx="4"/>` + nameText(w / 2, 9, name, 'board-name');
          const n = look === 'hex' ? Math.ceil(Math.min(wd, 32) / 4) : Math.min(wd, 16), cell = look === 'hex' ? 3 * U : 2 * U, x0 = (w - n * cell) / 2;
          if (look === 'hex') return s + `<g data-hex="${n}" transform="translate(${x0} ${2 * U})">${hexDigits(null, wd, n)}</g>`;
          for (let k = 0; k < n; k++) {
            const bit = n - 1 - k, cx = x0 + k * cell + cell / 2, cy = 3.2 * U;
            if (look === 'leds') s += `<circle class="bled" data-bit="${bit}" cx="${cx}" cy="${cy}" r="6"><title>${esc(name)}[${bit}]</title></circle>`;
            else if (look === 'switches') s += `<g class="bsw" data-bit="${bit}"><rect x="${cx - 5}" y="${cy - 9}" width="10" height="18" rx="3"/><rect class="knob" x="${cx - 4}" y="${cy - 8}" width="8" height="8" rx="2"/><title>${esc(name)}[${bit}]: click to flip</title></g>`;
            else s += `<g class="bbtn" data-bit="${bit}"><circle cx="${cx}" cy="${cy}" r="7"/><title>${esc(name)}[${bit}]: 1 while held</title></g>`;
          }
          return s;
        }
        const tip = 7, out = t === 'out';
        const d = out
          ? `M0 ${h / 2} L${tip} 0 H${w} V${h} H${tip} Z`
          : `M0 0 H${w - tip} L${w} ${h / 2} L${w - tip} ${h} H0 Z`;
        return `<path class="port-flag" d="${d}"/>` + nameText(out ? (tip + w) / 2 : (w - tip) / 2, h / 2, name, 'port-name') +
          (wd > 1 ? txt(out ? w - 3 : 3, -5, wd, 'pin-w', out ? 'end' : 'start') : '');
      }
      case 'const': return `<rect class="body" x="0" y="0" width="${w}" height="${h}" rx="5"/>` + txt(w / 2, h / 2, p.value ?? '0', 'const-text');
      case 'timing': {
        const s = t === 'blink' ? 'M6 14 H10 V6 H18 V14 H26 V6 H30' : 'M6 14 H14 V6 H16 V14 H30';
        return `<rect class="body" x="0" y="0" width="${w}" height="${h}" rx="5"/><path class="glyph" d="${s}" transform="translate(4 0)"/>` +
          txt(w - 4, h / 2, (p.seconds ?? '') + 's', 'pin-label', 'end');
      }
      case 'gate': {
        const neg = t === 'nand' || t === 'nor' || t === 'xnor', bw = neg ? w - U : w;
        const base = t.replace(/^n(?!ot)/, '').replace('xnor', 'xor');
        if (prefs.symbols === 'iec') {
          // IEC 60617: a rectangle with the function's qualifier; a negated output is a bubble
          const q = { and: '&', or: '≥1', xor: '=1' }[base] || base;
          let s = `<rect class="body" x="0" y="0" width="${bw}" height="${h}" rx="1"/>` + txt(bw / 2, h / 2, q, 'iec-q');
          if (neg) s += `<circle class="body" cx="${bw + 4}" cy="${h / 2}" r="4"/>`;
          for (const pin of g.pins0) if (pin.side !== 'l') { const end = neg ? bw + 8 : bw; if (w - end > 0.5) s += `<path class="lead" d="M${end} ${pin.y * U} H${w}"/>`; }
          return s;
        }
        let d;
        if (base === 'and') d = `M0 0 H${bw / 2} A${h / 2} ${h / 2} 0 0 1 ${bw / 2} ${h} H0 Z`;
        else {
          const off = base === 'xor' ? U : 0;
          d = `M${off} 0 Q${off + bw * 0.35} ${h / 2} ${off} ${h} Q${off + bw * 0.6} ${h} ${bw} ${h / 2} Q${off + bw * 0.6} 0 ${off} 0 Z`;
        }
        let s = `<path class="body" d="${d}"/>`;
        if (base === 'xor') s += `<path class="body-line" d="M0 0 Q${bw * 0.35} ${h / 2} 0 ${h}"/>`;
        if (neg) s += `<circle class="body" cx="${bw + 4}" cy="${h / 2}" r="4"/>`;
        // leads from the pins to the body: inputs reach the curved back of OR/XOR, the output the tip or bubble
        const off = base === 'xor' ? U : 0;
        for (const pin of g.pins0) {
          const y = pin.y * U;
          if (pin.side === 'l') {
            const tt = y / h, back = base === 'and' ? 0 : off + 2 * tt * (1 - tt) * 0.35 * bw;
            if (back > 0.5) s += `<path class="lead" d="M0 ${y} H${back.toFixed(1)}"/>`;
          } else {
            const tip = base === 'and' ? bw / 2 + h / 2 : bw;
            const end = neg ? bw + 8 : tip;
            if (w - end > 0.5) s += `<path class="lead" d="M${end} ${y} H${w}"/>`;
          }
        }
        return s;
      }
      case 'check': {
        const sign = { assert: '⊢', assume: '⊨', cover: '◎' }[t] || '?';
        return `<path class="body check-${t}" d="M6 0 H${w - 6} L${w} ${h / 2} L${w - 6} ${h} H6 L0 ${h / 2} Z"/>` +
          txt(13, h / 2, sign, 'op-text') + nameText(24, h / 2, p.name || '', 'port-name', 'start');
      }
      case 'not': return prefs.symbols === 'iec'
        ? `<rect class="body" x="0" y="0" width="${w - 12}" height="${h}" rx="1"/>` + txt((w - 12) / 2, h / 2, '1', 'iec-q') + `<circle class="body" cx="${w - 8}" cy="${h / 2}" r="4"/><path class="lead" d="M${w - 4} ${h / 2} H${w}"/>`
        : `<path class="body" d="M0 0 L${w - 12} ${h / 2} L0 ${h} Z"/><circle class="body" cx="${w - 8}" cy="${h / 2}" r="4"/><path class="lead" d="M${w - 4} ${h / 2} H${w}"/>`;
      case 'reduce': {
        const op = { or: '|', and: '&', xor: '^' }[p.op] || '|';
        return `<path class="body" d="M0 0 L${w} ${h / 2} L0 ${h} Z"/>` + txt(w * 0.32, h / 2, op, 'op-text');
      }
      case 'mux': {
        const s = `<path class="body" d="M0 0 L${w} ${U * 1.2} V${h - U * 1.2} L0 ${h} Z"/>`;
        let labels = '';
        for (const pin of g.pins0) if (pin.side === 'l') labels += txt(5, pin.y * U, pin.label, 'pin-label', 'start');
        return s + labels;
      }
      case 'reg': {
        const name = p.name || '';
        let s = `<rect class="body" x="0" y="0" width="${w}" height="${h}" rx="3"/>`;
        s += `<path class="body-line" d="M0 ${h - 13} L8 ${h - 8} L0 ${h - 3}"/>`;  // the clock triangle
        for (const pin of g.pins0) s += txt(pin.side === 'l' ? 5 : w - 5, pin.y * U, pin.label, 'pin-label', pin.side === 'l' ? 'start' : 'end');
        return s + nameText(w / 2, -7, name, 'part-name').replace('</text>', esc(domTag(p)) + '</text>') + `<text class="reg-val" x="${w / 2}" y="${h - 9}" text-anchor="middle" dominant-baseline="central" data-up data-regval></text>`
          + `<text class="reg-next" x="${w / 2}" y="${h + 11}" text-anchor="middle" dominant-baseline="central" data-up data-regnext></text>`;   // D: what it takes at the next edge
      }
      default: {
        const isBlock = ['block', 'mem', 'fsm', 'module', 'code'].includes(part.type);
        const head = p.title || isBlock ? 2 * U : 0;
        let s = `<rect class="body" x="0" y="0" width="${w}" height="${h}" rx="3"/>`;
        // a block: its class in a band, the instance name above, a double edge saying "there is more inside"
        if (isBlock) s += (part.type === 'block' ? `<rect class="body-line block-edge" x="3" y="3" width="${w - 6}" height="${h - 6}" rx="2" fill="none"/>` : '')
          + `<path class="body-line head" d="M0 ${head} H${w}"/>` + txt(w / 2, head / 2, boxTitle(part), 'box-title')
          + (part.type === 'code' ? txt(w - 14, head / 2, '{ }', 'pin-label') : '')
          + (part.type === 'module' && /Fifo/.test(p.expr || '') ? `<rect class="fifo-bg" x="8" y="${h - 9}" width="${w - 16}" height="5" rx="2"/><rect class="fifo-fill" data-fill data-w="${w - 16}" x="8" y="${h - 9}" width="0" height="5" rx="2"><title>How full the FIFO is</title></rect>` : '')
          + (part.type === 'fsm' ? `<text class="reg-val" x="${w / 2}" y="${h - 10}" text-anchor="middle" dominant-baseline="central" data-up data-fsmval></text>`
            + txt(w / 2, -9, String(p.states || '').trim().split(/\s+/).length + ' states' + domTag(p), 'part-name') : '')
          + (part.type === 'mem' ? txt(w / 2, h - 9, (p.depth ?? 16) + ' × ' + (p.width ?? 8) + (String(p.write) === 'false' ? ' ROM' : '') + (p.read === 'sync' ? ' · sync' : ''), 'pin-label')
            : part.type === 'fsm' ? '' : txt(w / 2, -9, (p.name || '') + domTag(p), 'part-name'));
        else s += (head ? `<path class="body-line head" d="M0 ${head} H${w}"/>` + txt(w / 2, head / 2, boxTitle(part), 'box-title head') : txt(w / 2, h / 2, boxTitle(part), 'box-title'))
          + (domTag(p) ? txt(w / 2, -9, domTag(p).trim(), 'part-name dom-tag') : '');
        if (part.type === 'mem' && domTag(p)) s += txt(w / 2, -9, (p.name || '') + domTag(p), 'part-name');
        for (const pin of g.pins0) {
          if (!pin.label) continue;
          if (pin.side === 'l') s += txt(isBlock ? 7 : 5, pin.y * U, pin.label, 'pin-label', 'start');
          else if (pin.side === 'r' && (isBlock || g.pins0.filter((q) => q.side === 'r').length > 1)) s += txt(w - (isBlock ? 7 : 5), pin.y * U, pin.label, 'pin-label', 'end');
          else if (pin.side === 'b') s += txt(pin.x * U, h - 6, pin.label, 'pin-label');
        }
        return s;
      }
    }
  }

  // full markup for a part: <g transform> with body, leads and pin hit areas
  function markup(part, cls) {
    const g = S.Geom.of(part);
    const b = S.Geom.base(part, part.pins || pinsOf(part.type));
    const gb = { ...g, pins0: b.pins };
    const W = b.w * U, H = b.h * U;
    // transform: mirror then rotate about the unrotated box, then place
    let tf = `translate(${part.x * U} ${part.y * U})`;
    const r = part.rot || 0;
    if (r === 90) tf += ` translate(${H} 0) rotate(90)`;
    else if (r === 180) tf += ` translate(${W} ${H}) rotate(180)`;
    else if (r === 270) tf += ` translate(0 ${W}) rotate(270)`;
    if (part.mir) tf += ` translate(${W} 0) scale(-1 1)`;
    let inner = body(part, gb);
    if (r || part.mir) {
      // keep text upright and readable: undo the turn (and mirror) about each text's anchor point
      inner = inner.replace(/<text class="([^"]*)" x="([-\d.]+)" y="([-\d.]+)" text-anchor="(\w+)"([^>]*?) data-up/g, (m, c, x, y, a, rest) => {
        const anchor = part.mir ? ({ start: 'end', end: 'start' }[a] || a) : a;
        const tfm = `translate(${x} ${y})${part.mir ? ' scale(-1 1)' : ''}${r ? ` rotate(${-r})` : ''} translate(${-x} ${-y})`;
        return `<text class="${c}" x="${x}" y="${y}" text-anchor="${anchor}"${rest} transform="${tfm}" data-up`;
      });
    }
    return `<g class="part ${cls || ''}" data-id="${esc(part.id)}" data-type="${part.type}" transform="${tf}">` + inner + `</g>`;
  }

  S.Parts = { load, get, symbol, pinsOf, defaults, categories, boxTitle, markup, all: () => types, prefs, activeLow, nameText, hexDigits,
    prefsKey: () => JSON.stringify(prefs) };
})(globalThis.Studio = globalThis.Studio || {});
