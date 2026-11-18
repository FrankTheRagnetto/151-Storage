// 151 Viewer — testo / codice / JSON / XML / CSV / Markdown, a capo e monospazio.
// La ricerca la fa la WebView (findAllAsync) dal lato app.
const P = new URLSearchParams(location.search);
const SRC = P.get("src") || "/file/none/";
const SIZE = Number(P.get("size") || -1);
const MAX = Number(P.get("max") || 8 * 1024 * 1024);
const IS_JSON = P.get("json") === "1";
/** Evidenziazione: nome di linguaggio highlight.js, oppure "log" (righe colorate per livello). */
const LANG = P.get("lang") || "";
const MAX_HL = 1536 * 1024;
const A = window.Android || new Proxy({}, { get: (_, k) => (...a) => console.log("[Android." + String(k) + "]", ...a) });

document.documentElement.classList.toggle("theme-dark", P.get("theme") === "dark");
let fs = Math.min(32, Math.max(9, Number(P.get("fs") || 14)));
document.documentElement.style.setProperty("--fs", fs + "px");

const doc = document.getElementById("doc");
const note = document.getElementById("note");
const LINES_PER_BLOCK = 400;

function decode(bytes) {
  // BOM
  if (bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) return new TextDecoder("utf-8").decode(bytes.subarray(3));
  if (bytes[0] === 0xFF && bytes[1] === 0xFE) return new TextDecoder("utf-16le").decode(bytes.subarray(2));
  if (bytes[0] === 0xFE && bytes[1] === 0xFF) return new TextDecoder("utf-16be").decode(bytes.subarray(2));
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    // se abbiamo troncato a meta' di un carattere multibyte, riprova togliendo la coda
    try { return new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(0, Math.max(0, bytes.length - 3))); } catch {}
    return new TextDecoder("windows-1252").decode(bytes); // file vecchi Windows / Latin-1
  }
}

function fmtSize(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + " KB";
  return (n / 1024 / 1024).toFixed(1) + " MB";
}

function loadScript(src) {
  return new Promise((res, rej) => {
    const s = document.createElement("script");
    s.src = src; s.onload = res; s.onerror = () => rej(new Error(src));
    document.head.append(s);
  });
}

async function highlight(text) {
  const l = document.createElement("link");
  l.rel = "stylesheet";
  l.href = "/assets/lib/highlight/" + (P.get("theme") === "dark" ? "github-dark.min.css" : "github.min.css");
  document.head.append(l);
  await loadScript("/assets/lib/highlight/highlight.min.js");
  await loadScript("/assets/lib/highlight/extra-languages.min.js").catch(() => {});
  if (!hljs.getLanguage(LANG)) return; // linguaggio sconosciuto: resta il testo semplice
  const html = hljs.highlight(text, { language: LANG, ignoreIllegals: true }).value;
  doc.textContent = "";
  const pre = document.createElement("pre");
  pre.className = "hljs hl";
  pre.innerHTML = html; // prodotto da highlight.js: testo del file gia' "escapato"
  doc.append(pre);
}

const esc = s => s.replace(/[&<>]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
const LOG_RE = /\b(FATAL|CRIT(?:ICAL)?|ERROR|ERR|SEVERE|WARN(?:ING)?|INFO|NOTICE|DEBUG|TRACE|VERBOSE)\b|(?:^|\s)([EWIDVF])\//;
const TS_RE = /^(\[?\d{4}-\d\d-\d\d[T ][\d:.,]+(?:Z|[+-]\d\d:?\d\d)?\]?|\[?\d\d:\d\d:\d\d(?:[.,]\d+)?\]?|\w{3} [ \d]\d \d\d:\d\d:\d\d)/;
function logLine(line) {
  const m = LOG_RE.exec(line);
  let lvl = m ? (m[1] || m[2]).toUpperCase() : "";
  lvl = /^(FATAL|CRIT|SEVERE|ERR|E|F)/.test(lvl) ? "e" : /^(WARN|W)/.test(lvl) ? "w" : /^(INFO|NOTICE|I)/.test(lvl) ? "i" : /^(DEBUG|TRACE|VERBOSE|D|V)/.test(lvl) ? "d" : "";
  const ts = TS_RE.exec(line);
  const body = ts ? `<span class="ts">${esc(ts[0])}</span>${esc(line.slice(ts[0].length))}` : esc(line);
  return lvl ? `<span class="lv-${lvl}">${body}</span>` : body;
}

function render(text) {
  doc.textContent = "";
  if (!text.length) {
    const p = document.createElement("div");
    p.className = "empty";
    p.textContent = "The file is empty.";
    doc.append(p);
    return;
  }
  const lines = text.split(/\r\n|\n|\r/);
  const frag = document.createDocumentFragment();
  for (let i = 0; i < lines.length; i += LINES_PER_BLOCK) {
    const pre = document.createElement("pre");
    const end = Math.min(lines.length, i + LINES_PER_BLOCK);
    if (LANG === "log") pre.innerHTML = lines.slice(i, end).map(logLine).join("\n") + (end < lines.length ? "\n" : "");
    else pre.textContent = lines.slice(i, end).join("\n") + (end < lines.length ? "\n" : "");
    frag.append(pre);
  }
  doc.append(frag);
}

async function main() {
  let bytes;
  try {
    const r = await fetch(`${SRC}doc?max=${MAX}`, { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    bytes = new Uint8Array(await r.arrayBuffer());
  } catch (e) {
    doc.innerHTML = '<div class="empty">Can\'t read the file.</div>';
    A.onError(String(e));
    return;
  }
  let text = decode(bytes);
  const NAME = (P.get("name") || "").toLowerCase();
  if (/\.(jsonl|ndjson)$/.test(NAME) && bytes.length < 5 * 1024 * 1024) {
    // v5: JSON Lines: un record per riga, ognuno formattato, numerato
    const lines = text.split(/\r?\n/).filter(l => l.trim());
    let ok = 0;
    const out = lines.map((l, i) => { try { const v = JSON.stringify(JSON.parse(l), null, 2); ok++; return `// ${i + 1}\n${v}`; } catch { return `// ${i + 1} (not valid JSON)\n${l}`; } });
    if (ok) { text = out.join("\n\n"); note.textContent = `JSON Lines: ${lines.length} record${lines.length === 1 ? "" : "s"}` + (ok < lines.length ? `, ${lines.length - ok} invalid` : ""); note.hidden = false; }
  } else if (IS_JSON && bytes.length < 5 * 1024 * 1024) {
    try { text = JSON.stringify(JSON.parse(text), null, 2); } catch { /* JSON non valido: mostralo com'e' */ }
  }
  const truncated = (SIZE > 0 && SIZE > bytes.length) || bytes.length >= MAX;
  if (truncated) {
    note.textContent = `Large file: showing the first ${fmtSize(bytes.length)}` + (SIZE > 0 ? ` of ${fmtSize(SIZE)}.` : ".");
    note.hidden = false;
  }
  if (LANG && LANG !== "log" && text.length <= MAX_HL && text.length) {
    render(text); // subito visibile, poi i colori
    try { await highlight(text); } catch (e) { console.warn("highlighting failed", e); }
  } else {
    if (LANG && LANG !== "log" && text.length > MAX_HL) {
      note.textContent = (note.hidden ? "" : note.textContent + " · ") + "Large file: no syntax colors.";
      note.hidden = false;
    }
    render(text);
  }
  A.onLoaded(0);
}

// pinch = dimensione del carattere (il testo si riadatta, niente scroll orizzontale)
let pinch = null;
window.addEventListener("touchstart", e => {
  if (e.touches.length === 2) {
    const [a, b] = e.touches;
    pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), fs, anchor: scrollY / Math.max(1, document.documentElement.scrollHeight) };
  }
}, { passive: true });
let raf = 0;
window.addEventListener("touchmove", e => {
  if (!pinch || e.touches.length !== 2) return;
  e.preventDefault();
  const [a, b] = e.touches;
  const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
  const next = Math.round(Math.min(32, Math.max(9, pinch.fs * d / pinch.d)) * 2) / 2;
  if (next === fs) return;
  fs = next;
  cancelAnimationFrame(raf);
  raf = requestAnimationFrame(() => {
    document.documentElement.style.setProperty("--fs", fs + "px");
    scrollTo(0, pinch ? pinch.anchor * document.documentElement.scrollHeight : scrollY);
  });
}, { passive: false });
window.addEventListener("touchend", e => {
  if (pinch && e.touches.length < 2) { pinch = null; A.setTextSize(fs); }
}, { passive: true });

window.V = { state: () => ({ fs, blocks: doc.children.length }) };
main();
