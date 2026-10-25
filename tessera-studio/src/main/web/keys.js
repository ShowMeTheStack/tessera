// Tessera Studio: the shortcuts. Each action has an id, a group, a name and default chords; the user's own
// chords (set in the Shortcuts sheet) are kept in the browser and override the defaults. A chord is written
// "Mod+Shift+Z": Mod is ⌘ on a Mac and Ctrl elsewhere; Shift is written only for letters and named keys
// (">" is already a shifted key). Keys that depend on what is selected (Tab and the arrows while wiring
// from the keyboard, Delete, Esc, the arrows that nudge) are fixed and listed separately.
(function (S) {
  'use strict';
  const { store } = S.UI;
  const MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

  // [id, group, name, default chords, run(app, e) → false when it does not apply]
  const ACTIONS = [
    ['undo', 'Edit', 'Undo', ['Mod+Z'], (a) => a.doc.undo()],
    ['redo', 'Edit', 'Redo', ['Mod+Shift+Z', 'Mod+Y'], (a) => a.doc.redo()],
    ['copy', 'Edit', 'Copy', ['Mod+C'], (a) => a.copy()],
    ['cut', 'Edit', 'Cut', ['Mod+X'], (a) => { a.copy(); a.deleteSel(); }],
    ['paste', 'Edit', 'Paste', ['Mod+V'], (a) => a.paste()],
    ['duplicate', 'Edit', 'Duplicate', ['Mod+D'], (a) => a.duplicate()],
    ['selectAll', 'Edit', 'Select all', ['Mod+A'], (a) => a.selectAll()],
    ['rotate', 'Edit', 'Turn', ['R'], (a) => a.doc.rotate(a.canvas.selectedParts(), 90)],
    ['rotateBack', 'Edit', 'Turn the other way', ['Shift+R'], (a) => a.doc.rotate(a.canvas.selectedParts(), -90)],
    ['mirror', 'Edit', 'Mirror', ['M'], (a) => a.doc.mirror(a.canvas.selectedParts())],
    ['block', 'Edit', 'Make a block of the selection', ['B'], (a) => a.makeBlockSel()],
    ['label', 'Edit', 'Name the wire', ['L'], (a) => { const n = a.canvas.selectedNets()[0]; if (!n) return false; a.labelNet(n); }],
    ['quickAdd', 'Edit', 'Add a part', ['/'], (a) => { const r = a.canvas.wrap.getBoundingClientRect(); a.quickAdd(a.canvas.toWorld(r.left + r.width / 2, r.top + r.height / 2)); }],
    ['fit', 'View', 'Fit', ['F'], (a) => a.canvas.fit()],
    ['fitSel', 'View', 'Fit the selection', ['Shift+F'], (a) => a.canvas.fit(true)],
    ['zoomIn', 'View', 'Zoom in', ['+', '='], (a) => a.canvas.zoomBy(1.25)],
    ['zoomOut', 'View', 'Zoom out', ['-', '_'], (a) => a.canvas.zoomBy(0.8)],
    ['focus', 'View', 'Hide the panels', ['\\'], (a) => a.root.classList.toggle('focus')],
    ['theme', 'View', 'Light or dark', ['T'], (a) => a.theme()],
    ['commands', 'View', 'Commands', ['Mod+K'], (a) => a.commands()],
    ['keys', 'View', 'Shortcuts', ['?'], (a) => a.keysSheet()],
    ['guide', 'View', 'The guide', ['F1'], (a) => a.guide()],
    ['runPause', 'Simulate', 'Run or pause', ['Enter'], (a) => a.runPause()],
    ['step', 'Simulate', 'One cycle', ['.'], (a) => a.sim({ cmd: 'step', n: 1 })],
    ['step10', 'Simulate', 'Ten cycles', ['>'], (a) => a.sim({ cmd: 'step', n: 10 })],
    ['until', 'Simulate', 'Run until a condition', ['U'], (a) => a.runUntil()],
    ['back', 'Simulate', 'One cycle back', ['Backspace'], (a) => { if (a.canvas.sel.size) return false; a.back(1); }],
    ['toZero', 'Simulate', 'To cycle 0', ['Shift+Backspace'], (a) => { if (a.canvas.sel.size) return false; a.goto(0); }],
    ['reset', 'Simulate', 'Reset', ['Mod+R'], (a) => a.sim({ cmd: 'reset' })],
    ['restart', 'Simulate', 'Restart from cycle 0', ['Mod+Shift+R'], (a) => a.sim({ cmd: 'restart' })],
    ['probe', 'Simulate', 'Wire to the waves', ['W'], (a) => { const n = a.canvas.selectedNets()[0]; if (!n) return false; a.probe(n); }],
    ['newDoc', 'Files', 'New design', [], (a) => a.newDoc()],
    ['save', 'Files', 'Save as .java', ['Mod+S'], (a) => a.save()],
    ['open', 'Files', 'Open', ['Mod+O'], (a) => a.open()],
  ];
  const FIXED = [
    ['Wire from the keyboard', [['Tab ⇧Tab', 'To a pin of the selected part'], ['↑↓←→', 'To the nearest pin that way'], ['Enter', 'Start a wire, then connect it'], ['Esc', 'Cancel']]],
    ['Always', [['Del', 'Delete (Backspace too, with a selection)'], ['↑↓←→', 'Nudge (⇧: by 10), or pan'], ['Space', 'Hold to pan'], ['Esc', 'Clear the selection; leave a block']]],
  ];

  // the chord of a key event, in the form above
  function chordOf(e) {
    let k = e.key;
    if (k === ' ') k = 'Space';
    if (['Shift', 'Control', 'Meta', 'Alt'].includes(k)) return null;
    const named = k.length > 1, letter = /^[a-z]$/i.test(k);
    if (letter) k = k.toUpperCase();
    const p = [];
    if (e.metaKey || e.ctrlKey) p.push('Mod');
    if (e.altKey) p.push('Alt');
    if (e.shiftKey && (named || letter)) p.push('Shift');
    p.push(k);
    return p.join('+');
  }

  // a chord as the keyboard shows it
  function show(c) {
    const parts = c === '+' ? ['+'] : c.endsWith('++') ? [...c.slice(0, -2).split('+'), '+'] : c.split('+');
    return parts.map((x) => x === 'Mod' ? (MAC ? '⌘' : 'Ctrl+') : x === 'Shift' ? '⇧' : x === 'Alt' ? (MAC ? '⌥' : 'Alt+') : x === 'Backspace' ? '⌫' : x === 'Enter' ? '↵' : x).join('');
  }

  class Keys {
    constructor(app) {
      this.app = app;
      this.load();
    }

    load() {
      const mine = store.get('keys', {}) || {};
      this.map = new Map();        // chord -> action id
      this.of = new Map();         // action id -> chords
      for (const [id, , , def] of ACTIONS) {
        const cs = Array.isArray(mine[id]) ? mine[id] : def;
        this.of.set(id, cs);
        for (const c of cs) this.map.set(c, id);
      }
    }

    // the action a key event runs, if any; true when it ran
    handle(e) {
      const c = chordOf(e);
      const id = c && this.map.get(c);
      if (!id) return false;
      const a = ACTIONS.find((x) => x[0] === id);
      if (a[4](this.app, e) === false) return false;
      e.preventDefault();
      return true;
    }

    chords(id) { return this.of.get(id) || []; }
    label(id) { return this.chords(id).map(show).join(' '); }

    // bind chord to id (alone, replacing its chords); a chord another action had is taken from it
    set(id, chord) {
      const mine = store.get('keys', {}) || {};
      const other = this.map.get(chord);
      if (other && other !== id) mine[other] = this.chords(other).filter((c) => c !== chord);
      mine[id] = [chord];
      store.set('keys', mine);
      this.load();
      this.app.refreshKeyTitles && this.app.refreshKeyTitles();
      return other && other !== id ? ACTIONS.find((x) => x[0] === other)[2] : null;
    }

    reset(id) {
      const mine = store.get('keys', {}) || {};
      if (id) delete mine[id]; else for (const k of Object.keys(mine)) delete mine[k];
      store.set('keys', mine);
      this.load();
      this.app.refreshKeyTitles && this.app.refreshKeyTitles();
    }

    // the sheet: every action with its keys; click the keys to record new ones
    sheet() {
      const { h } = S.UI;
      const body = h('div.keys');
      const render = () => {
        const groups = new Map();
        for (const [id, g, name] of ACTIONS) {
          if (!groups.has(g)) groups.set(g, []);
          const mine = (store.get('keys', {}) || {})[id];
          const keyBtn = h('button.keybtn', { type: 'button', title: 'Click, then press the new keys (Esc: keep these)', 'data-id': id },
            ...(this.chords(id).length ? this.chords(id).map((c) => h('kbd', show(c))) : [h('span.t', 'none')]));
          keyBtn.addEventListener('click', () => this.record(keyBtn, id, render));
          const row = h('div', h('span', name, mine ? h('button.linkish', { type: 'button', title: 'Back to ' + ACTIONS.find((x) => x[0] === id)[3].map(show).join(' '), onclick: () => { this.reset(id); render(); } }, ' ↺') : null), keyBtn);
          groups.get(g).push(row);
        }
        const secs = [...groups].map(([g, rows]) => h('section', h('h3', g), rows));
        for (const [g, rows] of FIXED) secs.push(h('section', h('h3', g), rows.map(([k, d]) => h('div', h('span', d), h('span', k.split(' ').map((x) => h('kbd', x)))))));
        body.replaceChildren(...secs);
      };
      render();
      const reset = S.UI.button('Back to the defaults', { onclick: () => { this.reset(); render(); S.UI.toast('Every shortcut is back to its default.'); } });
      S.UI.modal('Shortcuts', h('div.keys-sheet', h('div.help', 'Click an action’s keys, then press the keys you want. A chord another action had moves here.'), body, h('div', { style: { marginTop: '8px' } }, reset)), { ok: 'Close', wide: true });
    }

    record(btn, id, render) {
      btn.classList.add('rec');
      btn.replaceChildren(S.UI.h('span.t', 'press the keys…'));
      const on = (e) => {
        e.preventDefault(); e.stopPropagation();
        const c = chordOf(e);
        if (!c) return;
        removeEventListener('keydown', on, true);
        if (c === 'Escape') { render(); return; }
        const took = this.set(id, c);
        render();
        S.UI.toast(show(c) + ' is now “' + ACTIONS.find((x) => x[0] === id)[2] + '”' + (took ? '; “' + took + '” lost it.' : '.'), 'ok', 5000);
      };
      addEventListener('keydown', on, true);
    }
  }

  S.Keys = Keys;
  S.Keys.ACTIONS = ACTIONS;
  S.Keys.chordOf = chordOf;
  S.Keys.show = show;
})(globalThis.Studio = globalThis.Studio || {});
