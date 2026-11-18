// Prova su PC (Chromium headless, emulazione telefono Android con touch) delle pagine web del viewer.
// NON e' la prova sul telefono: verifica la logica JS (pdf.js, range, ricerca, zoom, testo, svg).
// Uso:
//   python3 tools/web-test/serve.py 8151 &
//   PW_CORE=/percorso/playwright-core CHROME=/percorso/chrome node tools/web-test/run.mjs
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PW_CORE || "playwright-core");
const BASE = process.env.BASE || "http://127.0.0.1:8151";
const OUT = path.join(path.dirname(new URL(import.meta.url).pathname), "out");
const SHOTS = path.join(OUT, "shots");
fs.mkdirSync(SHOTS, { recursive: true });

let failures = 0;
const ok = (cond, label, extra = "") => {
  console.log(`${cond ? "PASS" : "FAIL"}  ${label}${extra ? "  — " + extra : ""}`);
  if (!cond) failures++;
};
const size = f => fs.statSync(path.join(OUT, f)).size;
const stats = async () => (await fetch(BASE + "/stats")).json();
const reset = () => fetch(BASE + "/stats/reset");

const browser = await chromium.launch({ executablePath: process.env.CHROME, headless: true });
const ctx = await browser.newContext({
  viewport: { width: 412, height: 800 },
  deviceScaleFactor: 2.625,
  isMobile: true,
  hasTouch: true,
  userAgent: "Mozilla/5.0 (Linux; Android 15; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36",
});
// finto ponte Android: registra le chiamate
await ctx.addInitScript(() => {
  window.__calls = [];
  window.Android = new Proxy({}, { get: (_, k) => (...a) => { window.__calls.push([k, ...a]); } });
});
const calls = (page, name) => page.evaluate(n => window.__calls.filter(c => c[0] === n), name);
const waitCall = (page, name, timeout = 20000) =>
  page.waitForFunction(n => window.__calls.some(c => c[0] === n), name, { timeout });
const errors = [];

async function newPage() {
  const p = await ctx.newPage();
  p.on("pageerror", e => errors.push(String(e)));
  p.on("console", m => { if (m.type() === "error") errors.push(m.text()); });
  p.on("request", r => { if (!r.url().startsWith(BASE) && !r.url().startsWith("blob:") && !r.url().startsWith("data:")) errors.push("RICHIESTA ESTERNA: " + r.url()); });
  return p;
}

async function pinch(page, cx, cy, from, to) {
  const cdp = await ctx.newCDPSession(page);
  const pts = d => [{ x: cx - d / 2, y: cy, id: 0 }, { x: cx + d / 2, y: cy, id: 1 }];
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: pts(from) });
  for (let i = 1; i <= 12; i++) {
    await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: pts(from + ((to - from) * i) / 12) });
    await page.waitForTimeout(16);
  }
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
}

// ------------------------------------------------ 1. PDF piccolo (lettura completa)
{
  await reset();
  const p = await newPage();
  const t0 = Date.now();
  await p.goto(`${BASE}/assets/viewer/pdf.html?src=/file/text.pdf/&size=${size("text.pdf")}&seek=1&page=1&theme=light`);
  await waitCall(p, "onLoaded");
  await p.waitForSelector(".page[data-page-number='1'] canvas", { timeout: 10000 });
  const tLoad = Date.now() - t0;
  const loaded = await calls(p, "onLoaded");
  ok(loaded[0][1] === 40, "PDF piccolo: 40 pagine riconosciute", `onLoaded(${loaded[0][1]}) in ${tLoad} ms`);
  const s = await stats();
  ok(s.doc === 1 && s.range === 0, "PDF piccolo: una sola lettura completa, nessun range", JSON.stringify(s));
  const tl = await p.evaluate(() => document.querySelector(".page[data-page-number='1'] .textLayer")?.textContent || "");
  ok(tl.includes("pagina 1 di 40"), "PDF piccolo: text layer presente (testo selezionabile)", JSON.stringify(tl.slice(0, 40)));
  await p.waitForTimeout(400);
  await p.screenshot({ path: path.join(SHOTS, "pdf-1-apertura.png") });

  // ricerca
  await p.evaluate(() => V.find("zanzibar", false, false));
  await p.waitForFunction(() => window.__calls.some(c => c[0] === "onFind" && c[2] === 3), null, { timeout: 10000 });
  const f = (await calls(p, "onFind")).filter(c => c[2] === 3).pop();
  ok(f[2] === 3, "Ricerca: 'zanzibar' trovato 3 volte (maiuscole ignorate)", `onFind(${f.slice(1).join(", ")})`);
  await p.waitForTimeout(500);
  // pagina del risultato evidenziato come "selected", e che sia davvero a schermo
  const sel = () => p.evaluate(() => {
    const h = document.querySelector(".textLayer .highlight.selected");
    if (!h) return null;
    const r = h.getBoundingClientRect();
    return { page: Number(h.closest(".page").dataset.pageNumber), visible: r.top >= 0 && r.bottom <= innerHeight };
  });
  const s1f = await sel();
  ok(s1f?.page === 3 && s1f.visible, "Ricerca: primo risultato (pag. 3) evidenziato e portato a schermo", JSON.stringify(s1f));
  await p.screenshot({ path: path.join(SHOTS, "pdf-2-ricerca.png") });
  await p.evaluate(() => V.find("zanzibar", false, true));
  await p.waitForTimeout(700);
  const s2f = await sel();
  ok(s2f?.page === 17 && s2f.visible, "Ricerca: 'successivo' va al risultato di pag. 17", JSON.stringify(s2f));
  await p.evaluate(() => V.find("zanzibar", true, true));
  await p.waitForTimeout(700);
  const s3f = await sel();
  ok(s3f?.page === 3 && s3f.visible, "Ricerca: 'precedente' torna al risultato di pag. 3", JSON.stringify(s3f));
  await p.evaluate(() => V.findClose());

  // vai a pagina
  await p.evaluate(() => V.goto(25));
  await p.waitForTimeout(500);
  const pg = await calls(p, "onPage");
  ok(pg.at(-1)[1] === 25, "Vai a pagina 25 + indicatore aggiornato", `onPage(${pg.at(-1).slice(1).join(", ")}), pill="${await p.textContent("#pill")}"`);

  // doppio tocco
  const s0 = (await p.evaluate(() => V.state())).scale;
  await p.touchscreen.tap(200, 400);
  await p.waitForTimeout(80);
  await p.touchscreen.tap(200, 400);
  await p.waitForTimeout(600);
  const s1 = (await p.evaluate(() => V.state())).scale;
  ok(s1 > s0 * 2, "Doppio tocco: zoom x2.5", `scala ${s0.toFixed(2)} -> ${s1.toFixed(2)}`);
  await p.screenshot({ path: path.join(SHOTS, "pdf-3-doppio-tocco.png") });
  await p.touchscreen.tap(200, 400);
  await p.waitForTimeout(80);
  await p.touchscreen.tap(200, 400);
  await p.waitForTimeout(600);
  const s2 = (await p.evaluate(() => V.state())).scale;
  ok(Math.abs(s2 - s0) < 0.02, "Doppio tocco di nuovo: torna a larghezza pagina", `scala ${s2.toFixed(2)}`);

  // pinch
  await pinch(p, 206, 400, 80, 260);
  await p.waitForTimeout(700);
  const s3 = (await p.evaluate(() => V.state())).scale;
  ok(s3 > s0 * 1.8, "Pinch-zoom (due dita che si allontanano): scala aumenta", `scala ${s0.toFixed(2)} -> ${s3.toFixed(2)}`);
  await p.screenshot({ path: path.join(SHOTS, "pdf-4-pinch.png") });
  await pinch(p, 206, 400, 260, 60);
  await p.waitForTimeout(700);
  const s4 = (await p.evaluate(() => V.state())).scale;
  ok(s4 < s3, "Pinch-zoom inverso: scala diminuisce", `scala ${s3.toFixed(2)} -> ${s4.toFixed(2)}`);

  // rotazione + modalita' scura
  await p.evaluate(() => { V.rotate(); V.setDark(true); });
  await p.waitForTimeout(800);
  const st = await p.evaluate(() => V.state());
  ok(st.rotation === 90, "Ruota: pagine a 90°", JSON.stringify(st));
  ok(await p.evaluate(() => document.documentElement.classList.contains("pdf-dark")), "Pagine scure attive");
  await p.screenshot({ path: path.join(SHOTS, "pdf-5-ruotato-scuro.png") });
  await p.close();
}

// ------------------------------------------------ 2. PDF grande (100 MB) letto a pezzi
{
  await reset();
  const p = await newPage();
  const big = size("big.pdf");
  const t0 = Date.now();
  await p.goto(`${BASE}/assets/viewer/pdf.html?src=/file/big.pdf/&size=${big}&seek=1&page=1&theme=dark`);
  await waitCall(p, "onLoaded", 30000);
  const tLoaded = Date.now() - t0;
  await p.waitForSelector(".page[data-page-number='1'] canvas", { timeout: 20000 });
  const tCanvas = Date.now() - t0;
  const s = await stats();
  const pages = (await calls(p, "onLoaded"))[0][1];
  ok(s.doc === 0 && s.range > 0, `PDF grande (${(big / 1e6).toFixed(0)} MB): letto a pezzi, mai intero`, JSON.stringify(s));
  ok(s.range_bytes < big / 4, "PDF grande: all'apertura letta solo una piccola parte del file",
    `${(s.range_bytes / 1e6).toFixed(1)} MB letti su ${(big / 1e6).toFixed(0)} MB; onLoaded ${tLoaded} ms, prima pagina ${tCanvas} ms (${pages} pagine)`);
  await p.waitForTimeout(500);
  await p.screenshot({ path: path.join(SHOTS, "pdf-6-grande-tema-scuro.png") });
  await p.evaluate(() => V.goto(60));
  await p.waitForSelector(".page[data-page-number='60'] canvas", { timeout: 20000 });
  await p.waitForTimeout(800);
  ok((await p.evaluate(() => V.state().page)) === 60, "PDF grande: salto a pagina 60 (immagine da 2400x1800)");
  await p.screenshot({ path: path.join(SHOTS, "pdf-7-grande-pag60.png") });
  await p.close();
}

// ------------------------------------------------ 3. PDF corrotto / non PDF
{
  const p = await newPage();
  await p.goto(`${BASE}/assets/viewer/pdf.html?src=/file/note.txt/&size=${size("note.txt")}&seek=0`);
  await waitCall(p, "onError");
  const m = await p.textContent("#msg");
  ok(!(await p.isHidden("#msg")) && m.includes("PDF"), "File non-PDF passato al viewer PDF: messaggio chiaro, niente crash", m);
  await p.close();
}

// ------------------------------------------------ 4. Testo
{
  const p = await newPage();
  await p.goto(`${BASE}/assets/viewer/text.html?src=/file/note.txt/&size=${size("note.txt")}&max=8388608&name=note.txt&json=0&fs=14&theme=light`);
  await waitCall(p, "onLoaded");
  const txt = await p.evaluate(() => document.getElementById("doc").textContent);
  ok(txt.includes("àèìòù e €"), "Testo UTF-8: accenti ed euro corretti");
  ok(txt.includes("riga 5000:"), "Testo: tutte le 5000+ righe presenti", `${await p.evaluate(() => V.state().blocks)} blocchi`);
  const ws = await p.evaluate(() => getComputedStyle(document.querySelector("#doc pre")).whiteSpace + " / " + getComputedStyle(document.querySelector("#doc pre")).fontFamily);
  ok(ws.startsWith("pre-wrap"), "Testo: a capo automatico + monospazio", ws);
  await p.screenshot({ path: path.join(SHOTS, "testo-1.png") });
  await pinch(p, 206, 400, 100, 200);
  await p.waitForTimeout(300);
  const fsz = (await p.evaluate(() => V.state())).fs;
  ok(fsz > 20, "Testo: pinch ingrandisce il carattere (e il testo si riadatta)", `font ${fsz}px, setTextSize=${JSON.stringify(await calls(p, "setTextSize"))}`);
  await p.screenshot({ path: path.join(SHOTS, "testo-2-pinch.png") });
  await p.close();

  const p2 = await newPage();
  await p2.goto(`${BASE}/assets/viewer/text.html?src=/file/note.txt/&size=${size("note.txt")}&max=50000&name=note.txt&theme=dark`);
  await waitCall(p2, "onLoaded");
  const note = await p2.textContent("#note");
  ok(!(await p2.isHidden("#note")) && note.includes("first"), "Testo troncato: avviso visibile", note);
  await p2.screenshot({ path: path.join(SHOTS, "testo-3-troncato-scuro.png") });
  await p2.close();

  const p3 = await newPage();
  await p3.goto(`${BASE}/assets/viewer/text.html?src=/file/dati.json/&size=${size("dati.json")}&max=8388608&name=dati.json&json=1&theme=light`);
  await waitCall(p3, "onLoaded");
  const j = await p3.evaluate(() => document.getElementById("doc").textContent);
  ok(j.includes('\n  "nome": "151 Viewer"'), "JSON su una riga: riformattato leggibile");
  await p3.screenshot({ path: path.join(SHOTS, "testo-4-json.png") });
  await p3.close();
}

// ------------------------------------------------ 5. SVG
{
  const p = await newPage();
  await p.goto(`${BASE}/assets/viewer/svg.html?src=/file/disegno.svg/&theme=light`);
  await p.waitForFunction(() => document.getElementById("img").complete && document.getElementById("img").naturalWidth > 0);
  ok(true, "SVG caricato come immagine");
  ok((await p.title()) === "SVG", "SVG: lo <script> dentro il file NON gira", `title="${await p.title()}"`);
  await p.screenshot({ path: path.join(SHOTS, "svg-1.png") });
  await p.close();
}

const ext = errors.filter(e => e.startsWith("RICHIESTA ESTERNA"));
ok(ext.length === 0, "Nessuna richiesta fuori dall'origine locale", ext.join(" | "));
const jsErr = errors.filter(e => !e.startsWith("RICHIESTA ESTERNA") && !/Failed to load resource.*(404|500)/.test(e));
ok(jsErr.length === 0, "Nessun errore JavaScript nelle pagine", jsErr.slice(0, 5).join(" | "));

await browser.close();
console.log(failures ? `\n${failures} FALLITE` : "\nTUTTE PASSATE");
process.exit(failures ? 1 : 0);
