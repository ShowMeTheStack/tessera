// Tessera Studio: the small UI toolkit every panel uses. h() builds DOM ("tag.class#id", attrs,
// ...children); icons are 16px stroke drawings; toasts, menus with keyboard navigation, modal
// dialogs with a focus trap, splitters and safe browser storage.
(function (S) {
  'use strict';
  const NS = 'http://www.w3.org/2000/svg';

  function h(sel, attrs, ...kids) {
    const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(sel) || [];
    const el = document.createElement(m[1] || 'div');
    for (const part of (m[2] || '').match(/[.#][\w-]+/g) || []) {
      if (part[0] === '.') el.classList.add(part.slice(1)); else el.id = part.slice(1);
    }
    if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) { kids.unshift(attrs); attrs = null; }
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'text') el.textContent = v;
      else if (k === 'html') el.innerHTML = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    return el;
  }

  // 16x16 stroke icons on a 20 grid; drawn for Studio
  const ICONS = {
    run: 'M6 4l10 6-10 6z', pause: 'M6 4v12M14 4v12', step: 'M5 4l8 6-8 6zM15 4v12', stepN: 'M3 4l6 6-6 6zM9 4l6 6-6 6zM17 4v12',
    back: 'M15 4L7 10l8 6zM5 4v12', reset: 'M4 10a6 6 0 1 0 2-4.5M4 3v4h4', undo: 'M8 5L4 9l4 4M4 9h8a4 4 0 0 1 0 8H9', redo: 'M12 5l4 4-4 4M16 9H8a4 4 0 0 0 0 8h3',
    zoomIn: 'M9 4a5 5 0 1 0 0 10a5 5 0 0 0 0-10zM16 16l-3.5-3.5M9 7v4M7 9h4', zoomOut: 'M9 4a5 5 0 1 0 0 10a5 5 0 0 0 0-10zM16 16l-3.5-3.5M7 9h4',
    fit: 'M4 8V4h4M16 8V4h-4M4 12v4h4M16 12v4h-4', file: 'M6 3h6l4 4v10H6zM12 3v4h4', open: 'M3 6h5l2 2h7v8H3z', save: 'M4 4h10l2 2v10H4zM7 4v4h6V4M7 16v-5h6v5',
    rotate: 'M15 10a5 5 0 1 1-2-4M15 3v4h-4', mirror: 'M10 3v14M7 6L3 10l4 4zM13 6l4 4-4 4z', block: 'M4 4h12v12H4zM8 8h4v4H8z', tidy: 'M4 5h12M4 10h8M4 15h10',
    trash: 'M5 6h10M8 6V4h4v2M6 6l1 10h6l1-10', note: 'M5 4h10v8l-4 4H5zM11 16v-4h4', probe: 'M10 3v6M7 9h6l-1 5h-4zM10 14v3', wave: 'M2 12h3V6h4v8h4V6h3v6h2',
    problem: 'M10 3l8 14H2zM10 8v4M10 14.5v.5', ok: 'M4 10l4 4 8-8', code: 'M7 6l-4 4 4 4M13 6l4 4-4 4', search: 'M9 4a5 5 0 1 0 0 10a5 5 0 0 0 0-10zM16 16l-3.5-3.5',
    sun: 'M10 6a4 4 0 1 0 0 8a4 4 0 0 0 0-8zM10 2v2M10 16v2M2 10h2M16 10h2M4.5 4.5l1.4 1.4M14.1 14.1l1.4 1.4M4.5 15.5l1.4-1.4M14.1 5.9l1.4-1.4',
    moon: 'M15 12.5A6 6 0 1 1 7.5 5 5 5 0 0 0 15 12.5z', keyboard: 'M3 6h14v9H3zM6 9h1M9 9h1M12 9h1M6 12h8', panel: 'M3 4h14v12H3zM8 4v12', down: 'M6 8l4 4 4-4',
    right: 'M8 6l4 4-4 4', close: 'M5 5l10 10M15 5L5 15', plus: 'M10 4v12M4 10h12', export: 'M10 3v10M6 7l4-4 4 4M4 13v4h12v-4', prove: 'M4 10l4 4 8-8M10 2l7 3v5c0 4-3 6.5-7 8-4-1.5-7-4-7-8V5z',
    book: 'M4 4h5a2 2 0 0 1 2 2v10a2 2 0 0 0-2-2H4zM16 4h-5a2 2 0 0 0-2 2v10a2 2 0 0 1 2-2h5z', x: 'M5 5l10 10M15 5L5 15',
    chart: 'M4 16V9M8 16V5M12 16v-4M16 16V7', console: 'M3 4h14v12H3zM6 8l3 2-3 2M10 13h4',
    board: 'M3 5h14v10H3zM6 8h2v2H6zM11 8h3M11 11h3', search: 'M8.5 3a5.5 5.5 0 1 0 0 11a5.5 5.5 0 0 0 0-11zM12.5 12.5L17 17', target: 'M10 4a6 6 0 1 0 0 12a6 6 0 0 0 0-12zM10 8a2 2 0 1 0 0 4a2 2 0 0 0 0-4z', layers: 'M10 3l7 4-7 4-7-4zM3 11l7 4 7-4',
    marker: 'M6 3h8v14l-4-3-4 3z', guide: 'M4 4h5a2 2 0 0 1 2 2v10a2 2 0 0 0-2-2H4zM16 4h-5a2 2 0 0 0-2 2v10a2 2 0 0 1 2-2h5z',
    clock: 'M10 4a6 6 0 1 0 0 12a6 6 0 0 0 0-12zM10 7v3l2 2', cpu: 'M6 6h8v8H6zM8 3v3M12 3v3M8 14v3M12 14v3M3 8h3M3 12h3M14 8h3M14 12h3', edge: 'M3 14h5V6h5v8h4M13 6l-2 2',
  };
  function icon(name, cls) {
    const s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 20 20');
    s.setAttribute('class', 'ic' + (cls ? ' ' + cls : ''));
    s.setAttribute('aria-hidden', 'true');
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', ICONS[name] || ICONS.problem);
    s.append(p);
    return s;
  }

  function button(label, opts = {}) {
    const b = h('button.btn' + (opts.primary ? '.primary' : '') + (opts.icon && !label ? '.icon' : '') + (opts.ghost ? '.ghost' : ''),
      { type: 'button', title: opts.title || label, 'aria-label': opts.title || label, id: opts.id });
    if (opts.icon) b.append(icon(opts.icon));
    if (label) b.append(h('span.lb', label));
    if (opts.className) b.classList.add(...opts.className.split(' '));
    if (opts.onclick) b.addEventListener('click', opts.onclick);
    return b;
  }

  // toasts
  let toastHost;
  function toast(text, kind, ms) {
    if (S.UI.onToast) S.UI.onToast(text, kind);   // the console keeps what the toasts said
    if (!toastHost) { toastHost = h('div.toasts', { role: 'status', 'aria-live': 'polite' }); document.body.append(toastHost); }
    const t = h('div.toast' + (kind ? '.' + kind : ''), text);
    toastHost.append(t);
    setTimeout(() => t.remove(), ms || (kind === 'err' ? 5500 : 3200));
  }

  // menus: items {label, sc, action, disabled, sep, checked}
  let openMenu = null;
  function closeMenu() { if (openMenu) { openMenu.remove(); openMenu = null; } }
  function menu(x, y, items) {
    closeMenu();
    const m = h('div.menu', { role: 'menu' });
    const buttons = [];
    for (const it of items) {
      if (it.sep) { m.append(h('hr')); continue; }
      const b = h('button.mi', { type: 'button', role: 'menuitem', disabled: it.disabled }, it.checked ? '✓ ' : '', it.label, it.sc ? h('span.sc', it.sc) : null);
      b.addEventListener('click', () => { closeMenu(); it.action && it.action(); });
      buttons.push(b);
      m.append(b);
    }
    document.body.append(m);
    const r = m.getBoundingClientRect();
    m.style.left = Math.max(4, Math.min(x, innerWidth - r.width - 4)) + 'px';
    m.style.top = Math.max(4, Math.min(y, innerHeight - r.height - 4)) + 'px';
    openMenu = m;
    let k = -1;
    m.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        k = (k + (e.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
        buttons[k].focus();
      } else if (e.key === 'Escape') { closeMenu(); }
    });
    setTimeout(() => buttons[0] && buttons[0].focus(), 0);
    return m;
  }
  addEventListener('mousedown', (e) => { if (openMenu && !openMenu.contains(e.target)) closeMenu(); }, true);

  // modal dialog with a focus trap; resolves with the value of onOk or null
  function modal(title, body, opts = {}) {
    return new Promise((resolve) => {
      const prev = document.activeElement;
      const back = h('div.modal-back');
      const ok = button(opts.ok || 'OK', { primary: true });
      const cancel = button('Cancel');
      const box = h('div.modal' + (opts.wide ? '.wide' : ''), { role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
        h('h2', title), body, h('div.actions', cancel, ok));
      back.append(box);
      document.body.append(back);
      const done = (v) => { back.remove(); prev && prev.focus && prev.focus(); resolve(v); };
      ok.addEventListener('click', () => { const v = opts.onOk ? opts.onOk() : true; if (v !== undefined && v !== false) done(v); });
      cancel.addEventListener('click', () => done(null));
      back.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') { e.preventDefault(); done(null); }
        if (e.key === 'Enter' && e.target.tagName !== 'TEXTAREA') { e.preventDefault(); ok.click(); }
        if (e.key === 'Tab') {
          const f = [...box.querySelectorAll('button, input, select, textarea, [tabindex]')].filter((x) => !x.disabled);
          if (!f.length) return;
          if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
          else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
        }
      });
      setTimeout(() => { const f = box.querySelector('input, select, textarea') || ok; f.focus(); f.select && f.select(); }, 0);
    });
  }

  async function prompt(title, label, value, validate) {
    const input = h('input.input', { value: value || '', 'aria-label': label });
    const err = h('div.err', { role: 'alert' });
    const body = h('div.field', h('label', label), input, err);
    return modal(title, body, {
      onOk: () => {
        const msg = validate ? validate(input.value) : null;
        if (msg) { err.textContent = msg; input.classList.add('bad'); return false; }
        return input.value;
      },
    });
  }

  // a splitter between two panes; get/set read and write the size in px
  function splitter(dir, get, set, opts = {}) {
    const el = h('div.splitter.' + dir, { role: 'separator', 'aria-orientation': dir === 'h' ? 'horizontal' : 'vertical', tabindex: '0', title: 'Drag to resize' });
    el.addEventListener('pointerdown', (e) => {
      el.setPointerCapture(e.pointerId);
      el.classList.add('drag');
      document.body.classList.add('resizing');
      document.body.style.cursor = dir === 'h' ? 'row-resize' : 'col-resize';
      const start = dir === 'h' ? e.clientY : e.clientX, s0 = get();
      const move = (ev) => set(s0 + ((dir === 'h' ? ev.clientY : ev.clientX) - start) * (opts.invert ? -1 : 1));
      const up = () => { el.classList.remove('drag'); document.body.classList.remove('resizing'); document.body.style.cursor = ''; el.removeEventListener('pointermove', move); el.removeEventListener('pointerup', up); };
      el.addEventListener('pointermove', move);
      el.addEventListener('pointerup', up);
    });
    el.addEventListener('keydown', (e) => {
      const step = e.shiftKey ? 40 : 10;
      const dec = dir === 'h' ? 'ArrowUp' : 'ArrowLeft', inc = dir === 'h' ? 'ArrowDown' : 'ArrowRight';
      if (e.key === dec || e.key === inc) { e.preventDefault(); set(get() + (e.key === inc ? step : -step) * (opts.invert ? -1 : 1)); }
    });
    return el;
  }

  // storage that never throws
  const store = {
    get(k, d) { try { const v = localStorage.getItem('tstudio.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('tstudio.' + k, JSON.stringify(v)); } catch { /* storage unavailable */ } },
  };

  function download(name, data, type) {
    const a = h('a', { href: URL.createObjectURL(new Blob([data], { type: type || 'application/octet-stream' })), download: name });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }

  function debounce(fn, ms) {
    let t;
    return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  }

  // replace an element's children, skipping null and false
  function fill(el, ...kids) { el.replaceChildren(...kids.flat().filter((k) => k != null && k !== false)); return el; }

  S.UI = { h, fill, icon, button, toast, menu, closeMenu, modal, prompt, splitter, store, download, debounce, NS };
})(globalThis.Studio = globalThis.Studio || {});
