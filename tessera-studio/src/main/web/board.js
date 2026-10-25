// Tessera Studio: the board. The design's ports as a front panel: ports named led* light LEDs, sw* are
// switches, btn* are push buttons (1 while held), seg* drive a seven-segment digit (7 bits, a..g) and
// hex* show hexadecimal digits (4 bits each). Every other port is listed too: inputs take a value,
// outputs show theirs, so any design can be driven from here without drawing input parts.
(function (S) {
  'use strict';
  const { h, fill, toast } = S.UI;
  // segments a..g as bits 0..6, for the hex digits 0..F
  const HEX = [0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f, 0x77, 0x7c, 0x39, 0x5e, 0x79, 0x71];
  const SEGS = ['M14 8h32l-5 7H19z', 'M48 10v34l-6 4-1-31z', 'M48 56v34l-7-6 1-31z', 'M14 92h32l-5-7H19z', 'M12 56v34l7-6-1-31z', 'M12 10v34l6 4 1-31z', 'M14 50l5-4h22l5 4-5 4H19z'];

  function kindOf(p) {
    const n = p.name.toLowerCase();
    if (p.dir === 'out' && /^led/.test(n)) return 'led';
    if (p.dir === 'out' && /^seg/.test(n) && p.width === 7) return 'seg';
    if (p.dir === 'out' && /^hex/.test(n)) return 'hex';
    if (p.dir === 'in' && /^(sw|switch)/.test(n)) return 'switch';
    if (p.dir === 'in' && /^(btn|button|key)/.test(n)) return 'button';
    return p.dir === 'in' ? 'input' : 'output';
  }

  function digit(mask) {
    return '<svg class="seg7" viewBox="0 0 60 100" aria-hidden="true">' + SEGS.map((d, i) => `<path d="${d}" class="${(mask >> i) & 1 ? 'on' : ''}"/>`).join('') + '</svg>';
  }

  class Board {
    constructor(host, app) {
      this.app = app;
      this.vals = new Map();
      this.root = h('div.insp.board');
      this.uart = new Uart(app);
      host.append(this.root);
      this.render([]);
    }

    render(ports) {
      this.ports = (ports || []).filter((p) => !p.clockOrReset).map((p) => ({ ...p, dir: p.dir || (p.input ? 'in' : 'out'), kind: null }));
      for (const p of this.ports) p.kind = kindOf(p);
      const panel = this.ports.filter((p) => ['led', 'seg', 'hex', 'switch', 'button'].includes(p.kind));
      const rest = this.ports.filter((p) => !panel.includes(p));
      this.els = new Map();
      const items = panel.map((p) => this.item(p));
      fill(this.root,
        h('h2', S.UI.icon('board'), 'Board'),
        h('div.sub', 'The design’s ports as a front panel. Ports named led…, sw…, btn…, seg… (7 bits) and hex… (4 bits a digit) become LEDs, switches, buttons and displays.'),
        panel.length ? h('div.board-panel', items) : h('div.help', 'No port has a board name. Name an output led, seg or hex, or an input sw or btn, to see it here as a part of the board.'),
        rest.length ? h('div', h('div.sec', 'Other ports'), h('table.board-ports', rest.map((p) => this.row(p)))) : null,
        this.uart.panel(this.ports));
      this.values();
    }

    item(p) {
      const label = h('div.bl', p.name + (p.width > 1 ? '[' + (p.width - 1) + ':0]' : ''));
      let body;
      if (p.kind === 'led') body = h('div.leds', Array.from({ length: Math.min(p.width, 32) }, (_, i) => h('span.led', { 'data-bit': p.width - 1 - i, title: p.name + '[' + (p.width - 1 - i) + ']' })));
      else if (p.kind === 'seg' || p.kind === 'hex') body = h('div.disp');
      else if (p.kind === 'switch') body = h('div.switches', Array.from({ length: Math.min(p.width, 16) }, (_, i) => {
        const bit = p.width - 1 - i;
        const b = h('button.sw', { type: 'button', 'data-bit': bit, role: 'switch', 'aria-checked': 'false', 'aria-label': p.name + ' bit ' + bit, title: p.name + '[' + bit + ']: click to flip' });
        b.addEventListener('click', () => this.poke(p, (this.get(p) ^ (1n << BigInt(bit)))));
        return b;
      }));
      else {
        body = h('div.buttons', Array.from({ length: Math.min(p.width, 8) }, (_, i) => {
          const bit = p.width - 1 - i;
          const b = h('button.push', { type: 'button', 'data-bit': bit, 'aria-label': p.name + ' bit ' + bit, title: p.name + '[' + bit + ']: 1 while held' });
          const set = (on) => this.poke(p, on ? this.get(p) | (1n << BigInt(bit)) : this.get(p) & ~(1n << BigInt(bit)));
          b.addEventListener('pointerdown', (e) => { b.setPointerCapture(e.pointerId); b.classList.add('down'); set(true); });
          b.addEventListener('pointerup', () => { b.classList.remove('down'); set(false); });
          b.addEventListener('keydown', (e) => { if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) { e.preventDefault(); e.stopPropagation(); b.classList.add('down'); set(true); } });
          b.addEventListener('keyup', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.stopPropagation(); b.classList.remove('down'); set(false); } });
          return b;
        }));
      }
      const el = h('div.bitem.' + p.kind, body, label);
      this.els.set(p.name, el);
      return el;
    }

    row(p) {
      const v = h('td.v', '—');
      let ctl = null;
      if (p.dir === 'in') {
        if (p.width === 1) {
          ctl = h('button.sw.small', { type: 'button', role: 'switch', 'aria-checked': 'false', 'aria-label': p.name });
          ctl.addEventListener('click', () => this.poke(p, this.get(p) ^ 1n));
        } else {
          ctl = h('input.input.bval', { spellcheck: 'false', placeholder: '0x…', 'aria-label': p.name + ' value' });
          ctl.addEventListener('change', () => {
            let x;
            try { x = BigInt(ctl.value.trim()); } catch (e) { toast('A value is decimal, 0x… or 0b….', 'err'); return; }
            if (x < 0n || x >= 1n << BigInt(p.width)) { toast(p.name + ' is ' + p.width + ' bits: from 0 to ' + ((1n << BigInt(p.width)) - 1n) + '.', 'err'); return; }
            this.poke(p, x);
          });
        }
      }
      const tr = h('tr', h('td', (p.dir === 'in' ? '← ' : '→ ') + p.name), h('td.t', p.width === 1 ? 'Bool' : 'UInt<' + p.width + '>'), v, h('td', ctl));
      this.els.set(p.name, tr);
      return tr;
    }

    get(p) { return this.vals.get(p.name) ?? 0n; }

    poke(p, v) {
      this.vals.set(p.name, v);
      this.app.sim({ cmd: 'poke', path: p.name, value: v.toString(16) });
      this.values();
    }

    // a frame came: '#name' values are the ports'
    frame(values) {
      for (const [k, v] of Object.entries(values)) if (k[0] === '#') this.vals.set(k.slice(1), BigInt('0x' + v));
      this.uart.tick();
      if (!this.root.closest('[hidden]')) this.values();
    }

    values() {
      for (const p of this.ports || []) {
        const el = this.els && this.els.get(p.name);
        if (!el) continue;
        const v = this.get(p);
        if (p.kind === 'led') for (const l of el.querySelectorAll('.led')) l.classList.toggle('on', ((v >> BigInt(l.dataset.bit)) & 1n) === 1n);
        else if (p.kind === 'seg') el.querySelector('.disp').innerHTML = digit(Number(v & 0x7fn));
        else if (p.kind === 'hex') {
          let s = '';
          for (let d = Math.ceil(p.width / 4) - 1; d >= 0; d--) s += digit(HEX[Number((v >> BigInt(4 * d)) & 15n)]);
          el.querySelector('.disp').innerHTML = s;
        } else if (p.kind === 'switch') for (const b of el.querySelectorAll('.sw')) { const on = ((v >> BigInt(b.dataset.bit)) & 1n) === 1n; b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)); }
        else if (p.kind === 'button') { /* buttons show their own state while held */ }
        else {
          el.querySelector('td.v').textContent = p.width === 1 ? v.toString() : '0x' + v.toString(16) + (p.width <= 16 ? ' (' + v.toString(10) + ')' : '');
          const sw = el.querySelector('.sw');
          if (sw) { sw.classList.toggle('on', v === 1n); sw.setAttribute('aria-checked', String(v === 1n)); }
        }
      }
    }
  }

  // A serial terminal on the simulation: an output (tx…) is decoded as 8N1 at a chosen baud, cycle by cycle
  // from its recorded wave on the server; typed text goes to an input (rx…) as scheduled values, one bit
  // every hz/baud cycles (start 0, eight data bits least first, stop 1), after anything still to be sent.
  class Uart {
    constructor(app) {
      this.app = app;
      this.text = '';
      this.from = 0;
      this.pending = [];   // [cycle, bit] still to come on rx
      this.rxEnd = 0;
      this.busy = false;
      this.last = 0;
    }

    panel(ports) {
      const { h } = S.UI;
      const outs = ports.filter((p) => p.dir === 'out' && p.width === 1), ins = ports.filter((p) => p.dir === 'in' && p.width === 1);
      if (!outs.length && !ins.length) { this.root = null; return null; }
      const pick = (list, re, cur) => (list.find((p) => p.name === cur) || list.find((p) => re.test(p.name)) || {}).name || '';
      this.tx = pick(outs, /tx/i, this.tx);
      this.rx = pick(ins, /rx/i, this.rx);
      const hz = this.app.hz || 1000;
      if (!this.baud) this.baud = [115200, 9600, 1200, 300].find((b) => hz / b >= 8) || Math.max(1, Math.floor(hz / 8));
      const sel = (list, cur, none, on) => {
        const s = h('select.input', {}, h('option', { value: '' }, none), ...list.map((p) => h('option', { value: p.name, selected: p.name === cur }, p.name)));
        s.addEventListener('change', () => on(s.value));
        return s;
      };
      this.out = h('pre.uart-out', { 'aria-live': 'polite', 'aria-label': 'Received on ' + (this.tx || 'tx') });
      this.out.textContent = this.text;
      const baud = h('input.input.uart-baud', { value: String(this.baud), inputmode: 'numeric', 'aria-label': 'Baud' });
      const cpb = h('span.t');
      const showCpb = () => { cpb.textContent = (hz / this.baud >= 2 ? (hz / this.baud).toFixed(hz % this.baud ? 2 : 0) + ' cycles a bit' : 'too fast for a ' + hz + ' Hz clock'); };
      baud.addEventListener('change', () => { const b = Number(baud.value); if (b > 0) { this.baud = b; this.from = this.app.cycle || 0; showCpb(); } });
      showCpb();
      const line = h('input.input.uart-in', { placeholder: this.rx ? 'Type, Enter sends (with a newline)' : 'No 1-bit input to send on', disabled: !this.rx, spellcheck: 'false', 'aria-label': 'Send on ' + (this.rx || 'rx') });
      line.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Enter') { e.preventDefault(); this.send(line.value + '\n'); line.value = ''; }
      });
      const clear = S.UI.button('', { icon: 'trash', title: 'Clear the terminal', onclick: () => { this.text = ''; this.out.textContent = ''; } });
      this.root = h('div.uart',
        h('div.sec', 'Serial terminal (8N1)'),
        h('div.uart-bar', h('label', 'tx ', sel(outs, this.tx, '—', (v) => { this.tx = v; this.from = this.app.cycle || 0; })),
          h('label', 'rx ', sel(ins, this.rx, '—', (v) => { this.rx = v; this.idled = false; line.disabled = !v; })),
          h('label', 'baud ', baud), cpb, clear),
        this.out, line,
        h('div.help', 'Decoded from the simulation cycle by cycle: run it to see and send. rx is held at 1, the line’s idle level, until there is something to send. A frame with a bad stop bit shows as ⍰.'));
      return this.root;
    }

    // after a frame: fetch what tx sent since last time (at most five times a second)
    async tick() {
      if (!this.root || this.app.viewMode) return;
      // the run went back (a restart, a step back): decode and send from where it is now
      const cyc = this.app.cycle || 0;
      if (cyc < (this.seen || 0)) { this.from = Math.min(this.from, cyc); this.pending = []; this.rxEnd = 0; this.idled = false; }
      this.seen = cyc;
      if (this.rx && !this.idled && this.app.board && this.app.board.vals.get(this.rx) === 0n) { this.idled = true; this.app.board.poke({ name: this.rx }, 1n); }
      if (!this.tx || this.busy) return;
      const now = performance.now();
      if (now - this.last < 200) return;
      this.last = now;
      this.busy = true;
      try {
        const r = await S.Api.call('uart', { tx: this.tx, baud: this.baud, from: this.from });
        this.from = r.next;
        if (r.bytes.length) {
          for (const [, v, ok] of r.bytes) this.text += !ok ? '⍰' : v === 10 || v === 9 || (v >= 32 && v < 127) ? String.fromCharCode(v) : v === 13 ? '' : '\\x' + v.toString(16).padStart(2, '0');
          if (this.text.length > 20000) this.text = this.text.slice(-20000);
          this.out.textContent = this.text;
          this.out.scrollTop = this.out.scrollHeight;
        }
      } catch (e) { /* a design without the port yet: quiet until it has one */ }
      finally { this.busy = false; }
    }

    // text as rx bits after what is still pending
    async send(text) {
      if (!this.rx) return;
      const hz = this.app.hz || 1000, cpb = hz / this.baud;
      if (cpb < 2) { toast('At ' + this.baud + ' baud a bit is under 2 cycles of the ' + hz + ' Hz clock.', 'err'); return; }
      const now = (this.app.cycle || 0) + 1;
      this.pending = this.pending.filter(([c]) => c >= now);
      if (!this.pending.length) this.pending.push([now, 1]);   // idle high first, for a bit's time at least
      let start = Math.max(now + Math.ceil(cpb), this.rxEnd);
      for (const ch of new TextEncoder().encode(text)) {
        const bits = [0, ...Array.from({ length: 8 }, (_, i) => (ch >> i) & 1), 1];
        bits.forEach((b, i) => this.pending.push([start + Math.round(i * cpb), b]));
        start += Math.round(10 * cpb);
      }
      this.rxEnd = start;
      try { await S.Api.call('sim', { cmd: 'schedule', path: this.rx, at: this.pending.map(([c, b]) => [c, String(b)]) }); }
      catch (e) { toast(e.message, 'err'); return; }
      this.app.say('Sending ' + JSON.stringify(text) + ' on ' + this.rx + ': ' + new TextEncoder().encode(text).length + ' frames, until cycle ' + this.rxEnd + (this.app.running ? '.' : ' (run the simulation to send them).'));
    }
  }

  S.Board = Board;
  S.Uart = Uart;
})(globalThis.Studio = globalThis.Studio || {});
