// Tessera Studio: the JSON API of the studio server, and the stream of value frames.
(function (S) {
  'use strict';

  async function call(name, body) {
    const r = await fetch('/api/' + name, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const j = await r.json().catch(() => ({ error: 'The studio server sent something that is not JSON (status ' + r.status + ').' }));
    if (!r.ok || j.error) throw new Error(j.error || ('status ' + r.status));
    return j;
  }

  // frames: {type:'frame', cycle, head, running, rate, values:{net: hex}, full}
  function stream(onFrame, onState) {
    let es, retry = 500;
    const open = () => {
      es = new EventSource('/api/events');
      es.onopen = () => { retry = 500; onState && onState('live'); };
      es.onmessage = (e) => { try { onFrame(JSON.parse(e.data)); } catch (err) { console.error(err); } };
      es.onerror = () => {
        onState && onState('lost');
        es.close();
        setTimeout(open, retry);
        retry = Math.min(retry * 2, 8000);
      };
    };
    open();
    return { close: () => es && es.close() };
  }

  S.Api = { call, stream };
})(globalThis.Studio = globalThis.Studio || {});
