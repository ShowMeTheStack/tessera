// Tessera Studio: the problems list and the code view.
(function (S) {
  'use strict';
  const { h } = S.UI;

  class Problems {
    constructor(host, app) {
      this.app = app;
      this.root = h('div.probs', { 'aria-live': 'polite' });
      host.append(this.root);
      this.list = [];
    }

    set(list) {
      this.list = list || [];
      if (!this.list.length) {
        this.root.replaceChildren(h('div.none', S.UI.icon('ok'), 'No problems: every input is driven, every wire has one driver, widths agree, and the design elaborates.'));
        return;
      }
      const order = { error: 0, warning: 1 };
      this.root.replaceChildren(...[...this.list].sort((a, b) => order[a.severity] - order[b.severity]).map((p) => this.item(p)));
    }

    item(p) {
      const b = h('div.prob' + (p.severity === 'warning' ? '.warning' : ''), { role: 'button', tabindex: '0' },
        h('span.sev', p.severity === 'warning' ? '!' : '×'), h('span', p.message));
      if (p.fixes && p.fixes.length) {
        const fx = h('div.fixes');
        for (const f of p.fixes) {
          fx.append(S.UI.button(f.label, { onclick: (e) => { e.stopPropagation(); this.app.applyFix(f); } }));
        }
        b.append(fx);
      }
      const go = () => {
        const keys = [...(p.parts || []).map((x) => 'p:' + x), ...(p.nets || []).map((x) => 'w:' + x)];
        if (keys.length) { this.app.canvas.selectOnly(keys); if (p.parts && p.parts[0]) this.app.canvas.centreOn(p.parts[0]); }
      };
      b.addEventListener('click', go);
      b.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
      return b;
    }

    counts() {
      return { errors: this.list.filter((p) => p.severity === 'error').length, warnings: this.list.filter((p) => p.severity === 'warning').length };
    }
  }

  // Java highlighting: keywords, types, strings, numbers, comments, method names
  const KW = new Set('package import public private protected class extends implements new return final static void var if else for while switch case default this super true false null'.split(' '));
  function highlight(line) {
    const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    if (/^\s*\/\//.test(line) || /^\s*\/\*\*/.test(line)) return `<span class="k-com">${esc(line)}</span>`;
    return line.replace(/("(?:[^"\\]|\\.)*")|(\b\d[\w.]*\b)|(\b[A-Za-z_]\w*\b)(\s*\()?|([^"\w]+)/g, (m, str, num, word, paren, other) => {
      if (str) return `<span class="k-str">${esc(str)}</span>`;
      if (num) return `<span class="k-num">${num}</span>`;
      if (word) {
        if (KW.has(word)) return `<span class="k-kw">${word}</span>` + (paren || '');
        if (/^[A-Z]/.test(word)) return `<span class="k-ty">${word}</span>` + (paren || '');
        if (paren) return `<span class="k-fn">${word}</span>${paren}`;
        return word;
      }
      return esc(other || '');
    });
  }

  const SV_WORDS = /\b(module|endmodule|input|output|logic|wire|reg|always_ff|always_comb|always|assign|begin|end|if|else|case|endcase|posedge|negedge|localparam|parameter|initial|for|signed|unsigned)\b/g;
  function svHighlight(l) {
    const esc = l.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    const c = esc.indexOf('//');
    const code = c >= 0 ? esc.slice(0, c) : esc, cm = c >= 0 ? '<span class="tc">' + esc.slice(c) + '</span>' : '';
    return code.replace(/(\d+'[sS]?[bhdBHD][0-9a-fA-FxXzZ_]+)/g, '<span class="tn">$1</span>').replace(SV_WORDS, '<span class="tk">$1</span>') + cm;
  }

  class Code {
    constructor(host, app) {
      this.app = app;
      this.pre = h('pre.codeview', { tabindex: '0', 'aria-label': 'Generated Java' });
      this.svPre = h('pre.codeview', { tabindex: '0', 'aria-label': 'Emitted SystemVerilog', hidden: true });
      this.view = 'java';
      const seg = (id, label) => { const b = h('button.seg-b' + (id === 'java' ? '.on' : ''), { type: 'button', 'data-v': id }, label); b.addEventListener('click', () => this.show(id)); return b; };
      this.segs = h('div.segs', seg('java', 'Java'), seg('changes', 'Last change'), seg('sv', 'SystemVerilog'), seg('part', 'Part code'));
      this.diffPre = h('pre.codeview.diffview', { tabindex: '0', 'aria-label': 'What the last edit changed in the Java', hidden: true });
      // a Java code part's class, editable; ⌘Enter applies it (compiled and probed for its pins)
      this.ed = h('textarea.code-ed', { spellcheck: 'false', 'aria-label': 'The class of the selected Java code part' });
      this.edErr = h('div.err', { role: 'alert' });
      this.edBox = h('div.code-edbox.part-edbox', { hidden: true }, h('div.help', 'The class of the selected Java code part. It may use all of Java and Tessera; its public fields that hold ports become pins. ⌘Enter applies it.'),
        this.ed, h('div', { style: { display: 'flex', gap: '6px', alignItems: 'center' } }, S.UI.button('Apply', { primary: true, onclick: () => this.apply() }), this.edErr));
      this.ed.addEventListener('keydown', (e) => {
        e.stopPropagation();   // typing is not a shortcut
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); this.apply(); }
        if (e.key === 'Tab') { e.preventDefault(); const a = this.ed.selectionStart; this.ed.setRangeText('    ', a, this.ed.selectionEnd, 'end'); }
      });
      // the Java itself, editable: applied, it is read back into the drawing
      this.jEd = h('textarea.code-ed.java-ed', { spellcheck: 'false', 'aria-label': 'The Java of the drawing, to edit' });
      this.jErr = h('div.err', { role: 'alert' });
      this.jBox = h('div.code-edbox', { hidden: true },
        h('div.help', 'Edit the Java; ⌘Enter reads it back into the drawing. Statements in the studio’s own style become parts (parts still there keep their places); anything else Java can say becomes a Java code part.'),
        this.jEd, h('div', { style: { display: 'flex', gap: '6px', alignItems: 'center' } },
          S.UI.button('Apply to the drawing', { primary: true, onclick: () => this.applyJava() }), S.UI.button('Cancel', { onclick: () => this.editJava(false) }), this.jErr));
      this.jEd.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); this.applyJava(); }
        if (e.key === 'Escape') { e.preventDefault(); this.editJava(false); }
        if (e.key === 'Tab') { e.preventDefault(); const a = this.jEd.selectionStart; this.jEd.setRangeText('    ', a, this.jEd.selectionEnd, 'end'); }
      });
      this.editBtn = S.UI.button('Edit', { icon: 'code', title: 'Edit the Java and read it back into the drawing', onclick: () => this.editJava(true) });
      this.segs.append(this.editBtn);
      this.root = h('div.code-pane', this.segs, this.pre, this.jBox, this.diffPre, this.svPre, this.edBox);
      this.segs.querySelector('[data-v="part"]').hidden = true;
      host.append(this.root);
      this.pre.addEventListener('click', (e) => {
        const ln = e.target.closest('.ln');
        if (!ln) return;
        const n = Number(ln.getAttribute('data-n'));
        const lines = (this.app.gen && this.app.gen.lines) || {};
        const part = Object.keys(lines).find((k) => lines[k].includes(n));
        if (part) { this.app.canvas.selectOnly(['p:' + part]); this.app.canvas.centreOn(part); }
      });
    }

    // the emitted SystemVerilog, read-only, fetched when shown
    async show(id) {
      this.view = id;
      for (const b of this.segs.children) b.classList.toggle('on', b.getAttribute('data-v') === id);
      if (this.editing && id !== 'java') this.editJava(false);
      this.pre.hidden = id !== 'java' || !!this.editing;
      this.diffPre.hidden = id !== 'changes';
      this.svPre.hidden = id !== 'sv';
      if (id === 'changes') this.renderDiff();
      this.edBox.hidden = id !== 'part';
      if (id === 'sv') await this.loadSv();
    }

    async loadSv() {
      let r;
      try { r = await S.Api.call('sv', {}); } catch (e) { this.svPre.textContent = '// ' + e.message; return; }
      const lines = r.sv.replace(/\n$/, '').split('\n');
      this.svPre.innerHTML = lines.map((l, i) => `<span class="ln" data-n="${i + 1}">${svHighlight(l) || ' '}</span>`).join('');
    }

    // the lines the last edit added and removed, with three lines of context (an LCS over lines)
    renderDiff() {
      const strip = (t) => (t || '').split('\n').filter((l) => !l.startsWith('// tessera-studio:'));
      const a = strip(this.prevJava), b = strip(this.java);
      if (!this.prevJava) { this.diffPre.textContent = '// No change yet: edit the drawing, and what it did to the Java shows here.'; return; }
      const n = a.length, m = b.length;
      if (n * m > 4e6) { this.diffPre.textContent = '// The design is too large to compare line by line here.'; return; }
      const L = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
      for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = a[i] === b[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
      const ops = [];
      let i = 0, j = 0;
      while (i < n && j < m) { if (a[i] === b[j]) { ops.push([' ', b[j], j + 1]); i++; j++; } else if (L[i + 1][j] >= L[i][j + 1]) ops.push(['-', a[i++], null]); else { ops.push(['+', b[j], j + 1]); j++; } }
      while (i < n) ops.push(['-', a[i++], null]);
      while (j < m) { ops.push(['+', b[j], j + 1]); j++; }
      const keep = new Set();
      ops.forEach((o, k) => { if (o[0] !== ' ') for (let d = -3; d <= 3; d++) keep.add(k + d); });
      if (!keep.size) { this.diffPre.textContent = '// The last edit did not change the Java (the drawing only moved).'; return; }
      const out = [];
      let gap = false;
      ops.forEach((o, k) => {
        if (!keep.has(k)) { if (!gap) out.push('<span class="ln dgap">⋯</span>'); gap = true; return; }
        gap = false;
        const cls = o[0] === '+' ? ' dadd' : o[0] === '-' ? ' ddel' : '';
        out.push(`<span class="ln${cls}"${o[2] ? ' data-n="' + o[2] + '"' : ''}>${o[0]} ${highlight(o[1]) || ' '}</span>`);
      });
      this.diffPre.innerHTML = out.join('');
    }

    set(java) {
      // a change of the code itself (not of the drawing's trailer, which moves with every part) becomes the last change
      const code = (t) => (t || '').split('\n').filter((l) => !l.startsWith('// tessera-studio:')).join('\n');
      if (code(java) !== code(this.java)) this.prevJava = this.java;
      this.java = java;
      if (this.view === 'changes') this.renderDiff();
      if (this.view === 'sv') this.loadSv();
      const lines = (java || '').replace(/\n$/, '').split('\n');
      this.pre.innerHTML = lines.map((l, i) => {
        const trailer = l.startsWith('// tessera-studio:');
        return `<span class="ln${trailer ? ' trailer' : ''}" data-n="${i + 1}">${trailer ? '// tessera-studio: the drawing, compressed (' + l.length + ' characters)' : highlight(l) || ' '}</span>`;
      }).join('');
      this.mark();
    }

    // show the Java with line n lit (a timing path's step)
    goLine(n) {
      this.app.showTab('code');
      this.show('java');
      let el = null;
      for (const x of this.pre.children) { const on = Number(x.getAttribute('data-n')) === n; x.classList.toggle('hl', on); if (on) el = x; }
      if (el) el.scrollIntoView({ block: 'center' });
    }

    editJava(on) {
      if (on && this.app.viewMode) { S.UI.toast('This is a view of a design written in Java; open its file to edit it as a drawing.'); return; }
      if (on && this.view !== 'java') this.show('java');
      this.editing = on;
      this.jBox.hidden = !on;
      this.pre.hidden = on || this.view !== 'java';
      this.editBtn.hidden = on;
      if (on) {
        this.jEd.value = (this.java || '').split('\n').filter((l) => !l.startsWith('// tessera-studio:')).join('\n').replace(/\n+$/, '\n');
        this.jErr.textContent = '';
        this.jEd.focus();
      }
    }

    async applyJava() {
      this.jErr.textContent = 'Compiling and reading…';
      const err = await this.app.applyJava(this.jEd.value);
      if (err) { this.jErr.textContent = err; return; }
      this.editJava(false);
    }

    async apply() {
      if (!this.edPart) return;
      this.edErr.textContent = 'Compiling…';
      const err = await this.app.applyCode(this.edPart, this.ed.value);
      this.edErr.textContent = err || '';
    }

    // highlight the lines of the selected parts; a selected code part opens in the editor
    mark() {
      const sel = this.app.canvas ? this.app.canvas.selectedParts() : [];
      const cp = sel.length === 1 && this.app.doc.part(sel[0]);
      const partSeg = this.segs.querySelector('[data-v="part"]');
      if (cp && cp.type === 'code') {
        partSeg.hidden = false;
        if (this.edPart !== cp.id) { this.edPart = cp.id; this.ed.value = cp.params.source || ''; this.edErr.textContent = ''; this.show('part'); }
      } else {
        partSeg.hidden = true;
        this.edPart = null;
        if (this.view === 'part') this.show('java');
      }
      const lines = (this.app.gen && this.app.gen.lines) || {};
      const want = new Set(sel.flatMap((p) => lines[p] || []));
      let first = null;
      for (const el of this.pre.children) {
        const on = want.has(Number(el.getAttribute('data-n')));
        el.classList.toggle('hl', on);
        if (on && !first) first = el;
      }
      if (first && this.root.offsetParent) first.scrollIntoView({ block: 'nearest' });
    }
  }

  S.Problems = Problems;
  S.Code = Code;
})(globalThis.Studio = globalThis.Studio || {});
