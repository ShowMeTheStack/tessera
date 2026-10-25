// Tessera Studio: the drawing. A document holds parts (type, params, position, turn, mirror, pins)
// and nets (sets of pins, optional name). Every change goes through edit(), which records the inverse
// so that undo and redo work per gesture (a whole drag is one step) and the history stays small even
// for large designs.
(function (S) {
  'use strict';

  function blank(name) {
    return { tessera_studio: 1, name: name || 'Design', package: 'studio', parts: [], nets: [], notes: [], next: 1 };
  }

  class Doc {
    constructor(data) {
      this.root = data || blank();
      this.path = [];                 // the blocks opened, outermost first: [{block, inst}]
      this.root.notes = this.root.notes || [];
      this.root.next = this.root.next || 1;
      this.undoStack = [];
      this.redoStack = [];
      this.tx = null;
      this.listeners = new Set();
      this.version = 0;
      this.syncBlocks();
      this.reindex();
    }

    // the level being edited: the top of the drawing, or the block opened last
    get d() { return this.path.length ? this.root.blocks[this.path[this.path.length - 1].block] : this.root; }
    set d(v) { this.root = v; this.path = []; this.syncBlocks(); }
    level() { return this.path.length ? this.path[this.path.length - 1].block : null; }
    levelObj(name) { return name ? this.root.blocks[name] : this.root; }

    enter(block, inst) { this.path.push({ block, inst }); this.reindex(); this.emit('level'); }
    leave() { if (!this.path.length) return false; this.path.pop(); this.reindex(); this.emit('level'); return true; }
    // the instance path of the open block, for its live values ("u1/u2/")
    instPrefix() { return this.path.map((x) => x.inst + '/').join(''); }

    reindex() {
      this.partById = new Map(this.d.parts.map((p) => [p.id, p]));
      this.netById = new Map(this.d.nets.map((n) => [n.id, n]));
      this.pinNet = new Map();
      for (const n of this.d.nets) for (const [p, pin] of n.pins) this.pinNet.set(p + '.' + pin, n.id);
    }

    on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
    emit(why) { this.version++; for (const fn of this.listeners) fn(why); }

    fresh(prefix) {
      let id;
      do { id = prefix + this.root.next++; } while (this.partById.has(id) || this.netById.has(id));
      return id;
    }

    // ---------------------------------------------------------------- transactions and history

    // run fn as one undoable step named why
    edit(why, fn) {
      const outer = !this.tx;
      if (outer) this.tx = { why, level: this.level(), before: this.snapshotRefs() };
      try { fn(); } finally {
        if (outer) {
          const after = this.snapshotRefs();
          const patch = this.diff(this.tx.before, after);
          const level = this.tx.level;
          this.tx = null;
          if (patch.length) {
            this.syncBlocks();
            this.undoStack.push({ why, level, patch });
            if (this.undoStack.length > 500) this.undoStack.shift();
            this.redoStack.length = 0;
            this.reindex();
            this.emit(why);
          } else this.reindex();
        }
      }
    }

    // parts and nets by id as JSON strings: cheap to compare, and each change stores only what changed
    snapshotRefs() {
      const m = new Map();
      for (const p of this.d.parts) m.set('p:' + p.id, JSON.stringify(p));
      for (const n of this.d.nets) m.set('n:' + n.id, JSON.stringify(n));
      for (const n of this.d.notes) m.set('o:' + n.id, JSON.stringify(n));
      m.set('meta', JSON.stringify({ name: this.d.name, title: this.d.title, package: this.d.package }));
      if (!this.path.length) m.set('blocks', JSON.stringify(Object.keys(this.root.blocks || {})));
      for (const [k, b] of Object.entries(this.root.blocks || {})) if (k !== this.level()) m.set('b:' + k, JSON.stringify(b));
      return m;
    }

    diff(a, b) {
      const patch = [];
      for (const [k, v] of a) if (b.get(k) !== v) patch.push([k, v, b.has(k) ? b.get(k) : null]);
      for (const [k, v] of b) if (!a.has(k)) patch.push([k, null, v]);
      return patch;
    }

    apply(patch, dir, level) {
      const L = this.levelObj(level === undefined ? this.level() : level);
      for (const [k, before, after] of patch) {
        const v = dir < 0 ? before : after;
        if (k === 'meta') { Object.assign(L, JSON.parse(v)); continue; }
        if (k === 'blocks') continue;
        if (k.startsWith('b:')) { this.root.blocks = this.root.blocks || {}; if (v == null) delete this.root.blocks[k.slice(2)]; else this.root.blocks[k.slice(2)] = JSON.parse(v); continue; }
        const kind = k[0], id = k.slice(2);
        const list = kind === 'p' ? L.parts : kind === 'n' ? L.nets : L.notes;
        const i = list.findIndex((x) => x.id === id);
        if (v == null) { if (i >= 0) list.splice(i, 1); }
        else if (i >= 0) list[i] = JSON.parse(v);
        else list.push(JSON.parse(v));
      }
      this.reindex();
    }

    undo() { const s = this.undoStack.pop(); if (!s) return false; this.apply(s.patch, -1, s.level); this.syncBlocks(); this.reindex(); this.redoStack.push(s); this.emit('undo'); return true; }
    redo() { const s = this.redoStack.pop(); if (!s) return false; this.apply(s.patch, 1, s.level); this.syncBlocks(); this.reindex(); this.undoStack.push(s); this.emit('redo'); return true; }

    // ---------------------------------------------------------------- hierarchy

    // a block's pins: its input and output ports, top to bottom
    blockPins(name) {
      const b = this.root.blocks && this.root.blocks[name];
      if (!b) return [];
      const ports = (t) => b.parts.filter((p) => p.type === t).sort((a, c) => a.y - c.y);
      return [...ports('in').map((p) => ({ name: p.params.name, dir: 'in', side: 'l', label: p.params.name })),
        ...ports('out').map((p) => ({ name: p.params.name, dir: 'out', side: 'r', label: p.params.name }))];
    }

    // every block part, at every level, follows its block's ports
    syncBlocks() {
      const levels = [this.root, ...Object.values(this.root.blocks || {})];
      for (const L of levels) {
        for (const p of L.parts) {
          if (p.type !== 'block') continue;
          const pins = this.blockPins(p.params.block);
          if (JSON.stringify(pins) === JSON.stringify(p.pins)) continue;
          p.pins = pins;
          const names = new Set(pins.map((q) => q.name));
          for (const n of L.nets) n.pins = n.pins.filter(([pp, q]) => pp !== p.id || names.has(q));
          L.nets = L.nets.filter((n) => n.pins.length > 1);
        }
      }
    }

    // turn the selected parts into a block; returns the block part, or a sentence saying why not
    makeBlock(ids, name, widths) {
      const set = new Set(ids);
      if (!ids.length) return 'Select the parts the block should hold first.';
      const blocks = this.root.blocks = this.root.blocks || {};
      if (blocks[name]) return 'There is already a block called ' + name + '.';
      const inner = { name, parts: [], nets: [], notes: [] };
      let part = null;
      this.edit('block', () => {
        const sel = this.d.parts.filter((p) => set.has(p.id));
        const x0 = Math.min(...sel.map((p) => p.x)), y0 = Math.min(...sel.map((p) => p.y));
        const x1 = Math.max(...sel.map((p) => p.x + S.Geom.of(p).w)), y1 = Math.max(...sel.map((p) => p.y + S.Geom.of(p).h));
        for (const p of sel) inner.parts.push({ ...JSON.parse(JSON.stringify(p)), x: p.x - x0 + 12, y: p.y - y0 + 2 });
        const used = new Set(sel.map((p) => p.params && p.params.name).filter(Boolean));
        const free = (base) => { let n = base, k = 1; while (used.has(n)) n = base + k++; used.add(n); return n; };
        const outerLinks = [];   // [net id, port name]
        let yin = 2, yout = 2, kin = 0, kout = 0;
        for (const n of this.d.nets) {
          const ins = n.pins.filter(([p]) => set.has(p)), outs = n.pins.filter(([p]) => !set.has(p));
          if (!ins.length) continue;
          if (!outs.length) { inner.nets.push(JSON.parse(JSON.stringify(n))); continue; }
          // a wire crossing the boundary becomes a port: an output when its driver is inside
          const drv = n.pins.find(([p, q]) => { const pp = this.part(p); const pin = pp && S.Geom.pin(pp, q); return pin && pin.dir === 'out'; });
          const isOut = drv && set.has(drv[0]);
          const w = (widths && widths[n.id]) || 1;
          // the port takes the name of what drives the wire (a register, a named value), else a0, y0, ...
          const dp = drv && this.part(drv[0]);
          const dname = dp && dp.params && /^[a-z_][A-Za-z0-9_]*$/.test(dp.params.name || '') ? dp.params.name : null;
          const pname = free(n.name || dname || (isOut ? 'y' + kout++ : 'a' + kin++));
          const port = { id: this.fresh('p'), type: isOut ? 'out' : 'in', x: isOut ? (x1 - x0 + 22) : 0, y: isOut ? yout : yin, rot: 0, mir: false,
            params: { name: pname, width: w }, pins: S.Parts.pinsOf(isOut ? 'out' : 'in') };
          if (isOut) yout += 4; else yin += 4;
          inner.parts.push(port);
          inner.nets.push({ id: this.fresh('w'), pins: [...ins, [port.id, isOut ? 'a' : 'y']] });
          outerLinks.push([n.id, pname]);
        }
        blocks[name] = inner;
        part = { id: this.fresh('p'), type: 'block', x: Math.round((x0 + x1) / 2) - 4, y: Math.round((y0 + y1) / 2) - 3, rot: 0, mir: false,
          params: { block: name, name: free(name.charAt(0).toLowerCase() + name.slice(1) + '1') }, pins: this.blockPins(name) };
        this.d.parts = this.d.parts.filter((p) => !set.has(p.id));
        this.d.parts.push(part);
        for (const n of this.d.nets) n.pins = n.pins.filter(([p]) => !set.has(p));
        for (const [nid, pname] of outerLinks) this.d.nets.find((n) => n.id === nid).pins.push([part.id, pname]);
        this.d.nets = this.d.nets.filter((n) => n.pins.length > 1);
      });
      return part;
    }
    canUndo() { return this.undoStack.length > 0; }
    canRedo() { return this.redoStack.length > 0; }

    // ---------------------------------------------------------------- parts

    addPart(type, x, y, params) {
      const p = { id: this.fresh('p'), type, x: Math.round(x), y: Math.round(y), rot: 0, mir: false, params: { ...S.Parts.defaults(type), ...(params || {}) } };
      p.pins = S.app && S.app.pinsFor ? S.app.pinsFor(p) : S.Parts.pinsOf(type);   // pins that depend on params (and a module's) come from the app
      if (['in', 'out', 'reg'].includes(type) && !(params && params.name)) p.params.name = this.freeName(type);
      this.edit('add', () => this.d.parts.push(p));
      return p;
    }

    freeName(type) {
      const used = new Set(this.d.parts.map((p) => p.params && p.params.name));
      const seq = type === 'in' ? 'abcdefghijklmn' : type === 'out' ? 'yzuvwpqrst' : null;
      if (seq) for (const c of seq) if (!used.has(c)) return c;
      const stem = type === 'in' ? 'in' : type === 'out' ? 'out' : 'r';
      for (let k = type === 'reg' ? 0 : 1; ; k++) if (!used.has(stem + k)) return stem + k;
    }

    freeInst(block) {
      const used = new Set(this.d.parts.map((p) => p.params && p.params.name));
      const base = block.charAt(0).toLowerCase() + block.slice(1);
      for (let k = 1; ; k++) if (!used.has(base + k)) return base + k;
    }

    part(id) { return this.partById.get(id); }
    net(id) { return this.netById.get(id); }
    netOf(part, pin) { return this.pinNet.get(part + '.' + pin); }

    setParams(id, params, pins) {
      this.edit('edit', () => {
        const p = this.part(id);
        p.params = { ...p.params, ...params };
        if (pins) {
          p.pins = pins;
          const names = new Set(pins.map((q) => q.name));
          for (const n of this.d.nets) n.pins = n.pins.filter(([pp, pin]) => pp !== id || names.has(pin));
          this.d.nets = this.d.nets.filter((n) => n.pins.length > 1 || n.name);
        }
      });
    }

    movePartsBy(ids, dx, dy) {
      this.edit('move', () => { for (const id of ids) { const p = this.part(id); if (p) { p.x += dx; p.y += dy; } } });
    }

    rotate(ids, by) {
      this.edit('rotate', () => { for (const id of ids) { const p = this.part(id); if (p) p.rot = ((p.rot || 0) + by + 360) % 360; } });
    }

    mirror(ids) {
      this.edit('mirror', () => { for (const id of ids) { const p = this.part(id); if (p) p.mir = !p.mir; } });
    }

    remove(partIds, netIds) {
      const ps = new Set(partIds || []), ns = new Set(netIds || []);
      this.edit('delete', () => {
        this.d.parts = this.d.parts.filter((p) => !ps.has(p.id));
        this.d.notes = this.d.notes.filter((n) => !ps.has(n.id));
        this.d.nets = this.d.nets.filter((n) => !ns.has(n.id));
        for (const n of this.d.nets) n.pins = n.pins.filter(([p]) => !ps.has(p));
        this.d.nets = this.d.nets.filter((n) => n.pins.length > 1);
      });
    }

    // ---------------------------------------------------------------- wires

    // join two pins (or a pin and a net): merges nets as needed; returns the net id or null
    connect(a, b) {
      let id = null;
      this.edit('wire', () => {
        const na = a.net || this.netOf(a.part, a.pin), nb = b.net || this.netOf(b.part, b.pin);
        if (a.part && b.part && a.part === b.part && a.pin === b.pin) return;
        if (na && nb) {
          if (na === nb) { id = na; return; }
          const A = this.net(na), B = this.net(nb);
          A.pins.push(...B.pins.filter(([p, q]) => !A.pins.some(([p2, q2]) => p2 === p && q2 === q)));
          if (!A.name && B.name) A.name = B.name;
          this.d.nets = this.d.nets.filter((n) => n.id !== nb);
          id = na;
        } else if (na || nb) {
          const N = this.net(na || nb), other = na ? b : a;
          if (other.part) N.pins.push([other.part, other.pin]);
          id = N.id;
        } else if (a.part && b.part) {
          id = this.fresh('w');
          this.d.nets.push({ id, pins: [[a.part, a.pin], [b.part, b.pin]] });
        }
        this.reindex();
      });
      return id;
    }

    disconnect(part, pin) {
      this.edit('unwire', () => {
        const id = this.netOf(part, pin);
        if (!id) return;
        const n = this.net(id);
        n.pins = n.pins.filter(([p, q]) => !(p === part && q === pin));
        if (n.pins.length < 2) this.d.nets = this.d.nets.filter((x) => x.id !== id);
      });
    }

    setVia(id, via) {
      this.edit('bend', () => { const n = this.net(id); if (!n) return; if (via && via.length) n.via = via; else delete n.via; });
    }

    nameNet(id, name) {
      this.edit('label', () => { const n = this.net(id); if (!n) return; if (name) { n.name = name; n.show = true; } else { delete n.name; delete n.show; } });
    }

    // ---------------------------------------------------------------- fixes from the problems list

    // apply a fix {op, ...} produced by the code generator; returns a sentence for the status line
    applyFix(fix) {
      let said = fix.label;
      this.edit('fix', () => {
        if (fix.op === 'param') {
          const p = this.part(fix.part);
          p.params = { ...p.params, [fix.name]: fix.value };
        } else if (fix.op === 'insert') {
          // put a part between a pin and the wire that feeds it
          const p = this.part(fix.part), pin = S.Geom.pin(p, fix.pin);
          const netId = this.netOf(fix.part, fix.pin);
          const q = { id: this.fresh('p'), type: fix.type, x: 0, y: 0, rot: 0, mir: false, params: { ...S.Parts.defaults(fix.type), ...(fix.params || {}) }, pins: S.Parts.pinsOf(fix.type) };
          const g = S.Geom.of(q);
          q.x = pin.ax - g.w - 3; q.y = pin.ay - g.pins.find((x) => x.dir === 'out').y;
          this.d.parts.push(q);
          if (netId) {
            const n = this.net(netId);
            n.pins = n.pins.filter(([a, b]) => !(a === fix.part && b === fix.pin));
            n.pins.push([q.id, g.pins.find((x) => x.dir === 'in').name]);
          }
          this.d.nets.push({ id: this.fresh('w'), pins: [[q.id, g.pins.find((x) => x.dir === 'out').name], [fix.part, fix.pin]] });
        } else if (fix.op === 'tie') {
          const p = this.part(fix.part), pin = S.Geom.pin(p, fix.pin);
          const k = { id: this.fresh('p'), type: 'const', x: pin.ax - 8, y: pin.ay - 1, rot: 0, mir: false, params: { value: String(fix.value || 0), width: 0 }, pins: S.Parts.pinsOf('const') };
          this.d.parts.push(k);
          this.d.nets.push({ id: this.fresh('w'), pins: [[k.id, 'y'], [fix.part, fix.pin]] });
        } else if (fix.op === 'unwire') {
          for (const n of this.d.nets) n.pins = n.pins.filter(([a, b]) => !(a === fix.part && b === fix.pin));
          this.d.nets = this.d.nets.filter((n) => n.pins.length > 1);
        } else said = null;
      });
      return said;
    }

    // ---------------------------------------------------------------- clipboard

    copy(ids) {
      const set = new Set(ids);
      const parts = this.d.parts.filter((p) => set.has(p.id)).map((p) => JSON.parse(JSON.stringify(p)));
      const nets = this.d.nets.map((n) => ({ ...n, pins: n.pins.filter(([p]) => set.has(p)) })).filter((n) => n.pins.length > 1);
      return { studio_clip: 1, parts, nets };
    }

    paste(clip, dx, dy) {
      const map = new Map(), ids = [];
      this.edit('paste', () => {
        const names = new Set(this.d.parts.map((p) => p.params && p.params.name));
        for (const p of clip.parts) {
          const q = JSON.parse(JSON.stringify(p));
          q.id = this.fresh('p');
          q.x += dx; q.y += dy;
          if (q.params && q.params.name && names.has(q.params.name)) {
            let k = 1; while (names.has(q.params.name + '_' + k)) k++;
            q.params.name = q.params.name + '_' + k;
          }
          if (q.params && q.params.name) names.add(q.params.name);
          map.set(p.id, q.id);
          ids.push(q.id);
          this.d.parts.push(q);
        }
        for (const n of clip.nets) this.d.nets.push({ id: this.fresh('w'), pins: n.pins.map(([p, q]) => [map.get(p), q]) });
      });
      return ids;
    }

    json() { return JSON.parse(JSON.stringify(this.root)); }
  }

  S.Doc = { Doc, blank };
})(globalThis.Studio = globalThis.Studio || {});
