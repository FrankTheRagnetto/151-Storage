// node tools/emu-test/cdp.mjs <port> "<espressione JS>"  -> valuta nella WebView (serve app debuggable) e stampa errori/console
const [port, expr, reloadFlag] = process.argv.slice(2);
const list = await (await fetch(`http://localhost:${port}/json`)).json();
const ws = new WebSocket(list[0].webSocketDebuggerUrl);
let id = 0; const pend = new Map();
const send = (method, params = {}) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
ws.onmessage = e => { const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
  else if (m.method === 'Runtime.exceptionThrown') console.log('EXC', JSON.stringify(m.params.exceptionDetails).slice(0, 1500));
  else if (m.method === 'Runtime.consoleAPICalled') console.log('CONSOLE', m.params.type, m.params.args.map(a => a.value ?? a.description).join(' ').slice(0, 800));
  else if (m.method === 'Log.entryAdded') console.log('LOG', m.params.entry.level, m.params.entry.text.slice(0, 800), m.params.entry.url || '');
};
await new Promise(r => ws.onopen = r);
await send('Runtime.enable'); await send('Log.enable');
if (reloadFlag) { await send('Page.reload', { ignoreCache: true }); await new Promise(r => setTimeout(r, 5000)); }
const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
console.log('RESULT', JSON.stringify(r.result).slice(0, 3000));
await new Promise(r => setTimeout(r, 500)); ws.close(); process.exit(0);
