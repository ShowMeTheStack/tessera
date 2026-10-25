// Tessera Studio: the pipeline diagram (the dock's Pipeline tab), as Konata draws one. Each row is an
// instruction, each column a cycle, and a cell names the stage the instruction was in: amber where it
// waited (a stall), crossed out where a redirect or halt flushed it, green where it left the last stage.
// It is read from the run's record, so it follows time travel: it shows the cycles up to the one being
// looked at, and clicking a cycle goes there. Above it, the Level-2 generator's own account of the
// schedule: what it forwards from where, when it stalls, where redirects resolve.
(function (S) {
  'use strict';
  const { h, fill, button } = S.UI;

  class PipeView {
    constructor(host, app) {
      this.app = app;
      this.span = 40;          // cycles shown
      this.at = null;          // the last cycle shown, or null to follow the cycle being looked at
      this.info = h('span.wv-info');
      this.sched = h('details.pv-sched', h('summary', 'What the generator inferred'));
      this.body = h('div.pv-body');
      this.root = h('div.pv',
        h('div.wv-tools', h('b', 'Pipeline'),
          button('Earlier', { title: 'Show earlier cycles', onclick: () => this.move(-this.span / 2) }),
          button('Later', { title: 'Show later cycles', onclick: () => this.move(this.span / 2) }),
          this.bFollow = button('Follow', { title: 'Keep showing the cycles up to the one being looked at', onclick: () => { this.at = null; this.refresh(); } }),
          this.info),
        this.sched, h('div.pv-main', this.body));
      host.append(this.root);
      fill(this.body, h('div.help', 'Open a core (Designs) to see its instructions go through the stages.'));
    }

    shown() { return !this.root.parentElement.hidden; }

    // a new frame from the run: redraw at most every 150 ms, and only when the tab is open
    frame() {
      if (!this.shown() || this.timer) return;
      this.timer = setTimeout(() => { this.timer = null; this.refresh(); }, 150);
    }

    move(d) {
      const now = this.app.cycle || 0;
      this.at = Math.max(this.span - 1, Math.min(now, (this.at ?? now) + d));
      if (this.at >= now) this.at = null;
      this.refresh();
    }

    async refresh() {
      // as many cycles as fit beside the instructions
      const w = this.root.clientWidth;
      if (w > 0) this.span = Math.max(16, Math.min(120, Math.floor((w - 290) / (this.cellW || 27))));
      const to = this.at ?? this.app.cycle ?? 0;
      let r;
      try { r = await S.Api.call('pipeview', { from: Math.max(0, to - this.span + 1), to }); } catch (e) { fill(this.body, h('div.help', e.message)); return; }
      this.render(r);
    }

    render(r) {
      this.bFollow.classList.toggle('on', this.at == null);
      this.info.textContent = 'cycles ' + r.from + '–' + r.to + (this.at == null ? '' : ' (not following)');
      const sch = r.schedule || [];
      this.sched.hidden = !sch.length;
      this.sched.replaceChildren(this.sched.firstChild, ...sch.map((s) => h('ul.pv-lines', ...s.lines.map((l) => h('li', l)))));
      if (!r.pipes.length) {
        fill(this.body, h('div.help', 'The running design has no pipeline built with Tessera’s Pipeline, so there is nothing to draw. (The out-of-order core C7 is written without it.)'));
        return;
      }
      fill(this.body, ...r.pipes.map((p) => this.table(p, r)));
      const sc = this.body.closest('.pv-main');
      // a cycle's real width (two lanes write MEM·0, wider than MEM): when the window overflows, size it again
      const cells = r.to - r.from + 1, tw = this.body.scrollWidth, fw = this.root.clientWidth;
      if (cells > 0 && tw > fw + 8) {
        const cw = Math.ceil((tw - 290) / cells) + 1;
        if (cw > (this.cellW || 27)) { this.cellW = cw; this.refresh(); return; }
      }
      if (this.at == null && sc) { sc.scrollTop = sc.scrollHeight; sc.scrollLeft = sc.scrollWidth; }
    }

    table(p, r) {
      const cols = [];
      for (let c = r.from; c <= r.to; c++) cols.push(c);
      const head = h('tr', h('th.pv-ins', p.name + ' · ' + p.stages.join(' ') + (p.lanes > 1 ? ' · ' + p.lanes + ' lanes' : '')),
        ...cols.map((c) => {
          const th = h('th.pv-cy' + (c === r.cycle ? '.now' : ''), { title: 'Go to cycle ' + c }, c % 5 === 0 ? String(c) : '');
          th.addEventListener('click', () => this.app.goto(c));
          return th;
        }));
      const rows = p.items.map((it) => {
        const at = new Map(it.cells.map((c) => [c[0], c]));
        const last = it.cells[it.cells.length - 1];
        const what = it.end === 'flushed' ? 'flushed at cycle ' + it.endCycle : it.end === 'retired' ? 'left the last stage at cycle ' + it.endCycle : 'still in flight';
        const label = h('td.pv-ins', { title: (it.pc != null ? 'pc 0x' + it.pc + ': ' : '') + what + (it.early ? '; in flight before the first cycle shown' : '') },
          h('span.pv-pc', it.pc != null ? it.pc.padStart(4, '0') : '#' + it.id), ' ', it.text || '');
        return h('tr.pv-row.' + it.end, label, ...cols.map((c) => {
          const cell = at.get(c);
          if (!cell) return h('td');
          let cls = 'td.pv-c';
          if (cell[3]) cls += '.st';
          if (cell === last && it.end === 'flushed') cls += '.fl';
          if (cell === last && it.end === 'retired') cls += '.rt';
          const name = p.stages[cell[1]] + (p.lanes > 1 ? '·' + cell[2] : '');
          return h(cls, { title: 'cycle ' + c + ': ' + p.stages[cell[1]] + (p.lanes > 1 ? ', lane ' + cell[2] : '') + (cell[3] ? ' (waiting)' : '') }, name);
        }));
      });
      const t = h('table.pv-t', h('thead', head), h('tbody', ...rows));
      return h('div.pv-pipe', t, p.truncated ? h('div.help', 'Only the first ' + p.items.length + ' instructions are drawn; show fewer cycles.') : null,
        h('div.pv-key', h('span.pv-c', 'ID'), ' in a stage ', h('span.pv-c.st', 'ID'), ' waiting (stall) ', h('span.pv-c.fl', 'EX'), ' flushed ', h('span.pv-c.rt', 'WB'), ' left the last stage'));
    }
  }

  S.PipeView = PipeView;
})(globalThis.Studio = globalThis.Studio || {});
