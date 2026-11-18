// Prove su PC (Chromium headless, telefono Android emulato) dei formati NUOVI della v5, sulle stesse pagine
// dell'APK (doc.html, text.html). Campioni: tools/samples/v5/ (sintetici, make-samples-v5.py) e
// tools/samples-real/ (reali, fetch-real-samples.sh). Screenshot in tools/web-test/out/shots-v5/.
// Uso:
//   python3 tools/web-test/serve.py 8151 &
//   PW_CORE=/percorso/playwright-core CHROME=/percorso/chrome node tools/web-test/run-v5.mjs [filtro]
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PW_CORE || "playwright-core");
const BASE = process.env.BASE || "http://127.0.0.1:8151";
const HERE = path.dirname(new URL(import.meta.url).pathname);
const DIRS = [path.join(HERE, "..", "samples", "v5"), path.join(HERE, "..", "samples-real"), path.join(HERE, "..", "samples")];
const SHOTS = path.join(HERE, "out", "shots-v5");
fs.mkdirSync(SHOTS, { recursive: true });
const FILTER = process.argv[2] || "";
const sizeOf = f => { for (const d of DIRS) { const p = path.join(d, f); if (fs.existsSync(p)) return fs.statSync(p).size; } return -1; };

let failures = 0, passes = 0;
const ok = (cond, label, extra = "") => {
  console.log(`${cond ? "PASS" : "FAIL"}  ${label}${extra ? "  — " + extra : ""}`);
  if (cond) passes++; else failures++;
};
const browser = await chromium.launch({ executablePath: process.env.CHROME, headless: true });
const mkCtx = dark => browser.newContext({
  viewport: { width: 412, height: 860 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, colorScheme: dark ? "dark" : "light",
  userAgent: "Mozilla/5.0 (Linux; Android 15; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
});
const ctxL = await mkCtx(false), ctxD = await mkCtx(true);
for (const c of [ctxL, ctxD]) await c.addInitScript(() => { window.__calls = []; window.Android = new Proxy({}, { get: (_, k) => (...a) => { window.__calls.push([k, ...a]); } }); });

async function open(file, fmt, { dark = false, name = file, page = "doc", extra = "" } = {}) {
  const p = await (dark ? ctxD : ctxL).newPage();
  const errors = [], external = [];
  p.on("pageerror", e => errors.push(String(e)));
  p.on("console", m => { if (m.type() === "error" && !/favicon/.test(m.location().url || "") && !/404 \(Not Found\)/.test(m.text())) errors.push(m.text()); });
  p.on("request", r => { const u = r.url(); if (!u.startsWith(BASE) && !u.startsWith("blob:") && !u.startsWith("data:")) external.push(u); });
  const t0 = Date.now();
  const q = `src=/file/${encodeURIComponent(file)}/&size=${sizeOf(file)}&name=${encodeURIComponent(name)}&theme=${dark ? "dark" : "light"}`;
  await p.goto(page === "doc" ? `${BASE}/assets/viewer/doc.html?${q}&fmt=${fmt}${extra}` : `${BASE}/assets/viewer/text.html?${q}&max=8388608&lang=${fmt}${extra}`);
  await p.waitForFunction(() => window.__calls.some(c => c[0] === "onLoaded" || c[0] === "onFailed"), null, { timeout: 30000 }).catch(() => {});
  await p.waitForTimeout(900);
  const calls = await p.evaluate(() => window.__calls);
  const formats = calls.filter(c => c[0] === "onFormat").map(c => c[1]);
  return { p, errors, external, ms: Date.now() - t0, failed: calls.find(c => c[0] === "onFailed"), loaded: calls.some(c => c[0] === "onLoaded"), format: formats[formats.length - 1] };
}
const shot = (p, name, full = false) => p.screenshot({ path: path.join(SHOTS, name + ".png"), fullPage: full });
const want = n => !FILTER || n.includes(FILTER);
const ev = (p, f, ...a) => p.evaluate(f, ...a);
const txt = p => ev(p, () => document.body.innerText);
const imgsOk = p => ev(p, () => [...document.querySelectorAll("img")].filter(i => i.complete && i.naturalWidth > 0).length);
const canv = p => ev(p, () => [...document.querySelectorAll("canvas")].filter(c => c.width > 0 && (() => { const d = c.getContext("2d").getImageData(0, 0, Math.min(c.width, 400), Math.min(c.height, 400)).data; let n = 0; for (let i = 0; i < d.length; i += 16) if (d[i] < 200) n++; return n > 20; })()).length);
const scrollAll = async p => { await ev(p, async () => { for (let y = 0; y < document.documentElement.scrollHeight; y += 600) { scrollTo(0, y); await new Promise(r => setTimeout(r, 120)); } scrollTo(0, 0); }); await p.waitForTimeout(600); };

// [etichetta, file, fmt (quello che passa l'app), prova -> oggetto, controllo, opzioni]
const CASES = [
  // email Outlook
  ["msg", "testMSG.msg", "msg", async p => ({ subj: await ev(p, () => document.querySelector(".mail-subject")?.textContent), from: await ev(p, () => document.querySelector(".mail-row span")?.textContent), body: (await txt(p)).includes("type detection") }), i => i.subj === "MIME registry use cases" && /Jukka Zitting/.test(i.from) && i.body],
  ["msg-rtf-html", "test-outlook2003.msg", "msg", async p => { await scrollAll(p); return { html: await ev(p, () => !!document.querySelector(".shadow-host")), imgs: await ev(p, () => [...document.querySelector(".shadow-host").shadowRoot.querySelectorAll("img")].filter(i => i.naturalWidth > 0).length), att: await ev(p, () => document.querySelectorAll(".attachments .entry").length) }; }, i => i.html && i.imgs >= 5 && i.att === 0],
  ["msg-allegati", "testMSG_att_doc.msg", "auto", async p => ({ att: await ev(p, () => [...document.querySelectorAll(".attachments .entry .nm")].map(e => e.textContent)) }), i => i.att.includes("test-unicode.doc") && i.att.includes("pj1.txt")],
  // Word / PowerPoint 97-2003 piu' ricchi
  ["doc-immagini", "testWORD_3imgs.doc", "doc", async p => ({ imgs: await imgsOk(p), t: (await txt(p)).includes("Alfresco logo") }), i => i.imgs === 3 && i.t],
  ["doc-stili", "testWORD_various.doc", "doc", async p => ({ b: await ev(p, () => document.querySelectorAll("article b").length), i: await ev(p, () => document.querySelectorAll("article i").length), u: await ev(p, () => document.querySelectorAll("article u").length), sup: await ev(p, () => document.querySelectorAll("article sup").length), a: await ev(p, () => document.querySelectorAll("article a[href^=http]").length), td: await ev(p, () => document.querySelectorAll("article td").length), li: await ev(p, () => document.querySelectorAll("article li").length) }), i => i.b >= 1 && i.i >= 1 && i.u >= 1 && i.sup >= 1 && i.a >= 1 && i.td >= 6 && i.li >= 3],
  ["doc-lo", "documento.doc", "doc", async p => ({ h: await ev(p, () => document.querySelectorAll("article h1,article h2").length), img: await imgsOk(p), red: await ev(p, () => [...document.querySelectorAll("article span")].some(s => /rgb\(2\d\d, 0, 0\)|#ff0000|red/.test(s.style.color))) }), i => i.h >= 3 && i.img >= 1],
  ["ppt-immagini", "testPPT_2imgs.ppt", "ppt", async p => ({ imgs: await imgsOk(p), t: (await txt(p)).includes("quick brown fox") }), i => i.imgs >= 2 && i.t],
  // Apple iWork
  ["pages", "testPages2013.pages", "iwork", async p => ({ img: await imgsOk(p), t: (await txt(p)).includes("Some plain text to parse") }), i => i.img >= 1 && i.t],
  ["numbers", "testNumbers2013.numbers", "iwork", async p => ({ img: await imgsOk(p) }), i => i.img >= 1],
  ["keynote", "testKeynote2013.key", "iwork", async p => ({ t: (await txt(p)).includes("A sample presentation") }), i => i.t],
  ["keynote-pacchetto", "testKeynote2018.key", "iwork", async p => ({ t: (await txt(p)).includes("Libreoffice 6.2") }), i => i.t],
  ["pages-09-pdf", "testPages.pages", "iwork", async p => { await scrollAll(p); return { c: await canv(p), t: (await txt(p)).includes("Lorem ipsum") }; }, i => i.c >= 1 && i.t],
  // XPS
  ["xps", "testXPS_various.xps", "xps", async p => ({ glyphs: await ev(p, () => document.querySelectorAll(".xps-page svg path[d^='M']").length), img: await ev(p, () => document.querySelectorAll(".xps-page svg pattern image").length), t: (await txt(p)).includes("testPDF.pdf") }), i => i.glyphs > 10 && i.img >= 2 && i.t],
  ["xps-3pagine", "testPPT.xps", "xps", async p => ({ pages: await ev(p, () => document.querySelectorAll(".xps-page svg").length) }), i => i.pages === 3],
  // ebook
  ["mobi", "alice.mobi", "mobi", async p => ({ ch: await ev(p, () => document.querySelectorAll(".book-bar option").length), t: await ev(p, () => document.querySelector(".shadow-host").shadowRoot.textContent.includes("Alice")) }), i => i.ch >= 5 && i.t],
  ["azw3", "alice.azw3", "mobi", async p => ({ t: await ev(p, () => document.querySelector(".shadow-host").shadowRoot.textContent.includes("Wonderland")) }), i => i.t],
  ["fb2", "test.fb2", "fb2", async p => ({ ch: await ev(p, () => document.querySelectorAll(".book-bar option").length), t: await ev(p, () => document.querySelector(".shadow-host").shadowRoot.textContent.length) }), i => i.ch >= 2 && i.t > 100],
  // fumetti e archivi
  ["cbz", "codedread.cbz", "comic", async p => { await scrollAll(p); return { pages: await ev(p, () => document.querySelectorAll(".comic-box").length), loaded: await imgsOk(p) }; }, i => i.pages === 6 && i.loaded >= 2],
  ["cbr", "fumetto.cbr", "comic", async p => { await scrollAll(p); return { pages: await ev(p, () => document.querySelectorAll(".comic-box").length), loaded: await imgsOk(p), order: await ev(p, () => document.querySelectorAll(".comic-page")[1]?.alt) }; }, i => i.pages === 11 && i.loaded >= 3 && i.order === "Page 2"],
  ["cb7", "fumetto.cb7", "comic", async p => ({ pages: await ev(p, () => document.querySelectorAll(".comic-box").length) }), i => i.pages === 11],
  ["7z", "archivio.7z", "archive", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 3],
  ["rar", "test-documents.rar", "archive", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 9],
  ["tar", "test-documents.tar", "archive", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 9],
  ["tgz", "archivio.tar.gz", "archive", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 3],
  ["tar-bz2", "archivio.tar.bz2", "archive", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 3],
  ["tar-xz", "archivio.tar.xz", "archive", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 3],
  ["gz-singolo", "server.log.gz", "archive", async p => ({ t: (await txt(p)).includes("evento numero 59") }), i => i.t],
  // dati e diagrammi
  ["ipynb", "analisi.ipynb", "ipynb", async p => ({ code: await ev(p, () => document.querySelectorAll(".nb-src").length), md: await ev(p, () => document.querySelectorAll(".nb-md h1").length), katex: await ev(p, () => document.querySelectorAll(".nb-md .katex").length), img: await imgsOk(p), table: await ev(p, () => document.querySelectorAll(".nb-html td").length), err: await ev(p, () => document.querySelectorAll(".nb-err span").length) }), i => i.code === 5 && i.md === 1 && i.katex >= 1 && i.img >= 1 && i.table >= 4 && i.err >= 1],
  ["gpx", "giro-in-bici.gpx", "geo", async p => ({ line: await ev(p, () => document.querySelectorAll(".geo-line").length), prof: await ev(p, () => document.querySelectorAll(".geo-area").length), t: await txt(p) }), i => i.line === 1 && i.prof === 1 && /7\.19 km/.test(i.t) && /Fontana/.test(i.t)],
  ["kml", "testKML.kml", "geo", async p => ({ t: await txt(p), poly: await ev(p, () => document.querySelectorAll(".geo-poly").length) }), i => /Simple placemark/.test(i.t) && i.poly >= 1],
  ["kmz", "testKMZ.kmz", "geo", async p => ({ t: await txt(p) }), i => /Simple placemark/.test(i.t)],
  ["geojson", "luoghi.geojson", "geo", async p => ({ pts: await ev(p, () => document.querySelectorAll(".geo-pt").length), poly: await ev(p, () => document.querySelectorAll(".geo-poly").length) }), i => i.pts === 1 && i.poly === 1],
  ["drawio", "flusso.drawio", "drawio", async p => ({ pages: await ev(p, () => document.querySelectorAll(".diagram-svg").length), ell: await ev(p, () => document.querySelectorAll(".diagram-svg ellipse").length), edges: await ev(p, () => document.querySelectorAll(".diagram-svg polyline").length), lbl: (await txt(p)).includes("Controllo") }), i => i.pages === 2 && i.ell === 2 && i.edges === 6 && i.lbl],
  ["excalidraw", "lavagna.excalidraw", "excalidraw", async p => ({ n: await ev(p, () => document.querySelectorAll(".diagram-svg g").length), t: (await txt(p)).includes("Idea di prova") }), i => i.n === 5 && i.t],
  ["excalidraw-obsidian", "lavagna.excalidraw.md", "md", async p => ({ n: await ev(p, () => document.querySelectorAll(".diagram-svg g").length) }), i => i.n === 5],
  // collegamenti, torrent
  ["url", "sito.url", "link", async p => ({ t: await txt(p), btn: await ev(p, () => !!document.querySelector(".link-open:not([disabled])")) }), i => i.t.includes("https://example.org/pagina") && i.btn],
  ["webloc", "pagina.webloc", "link", async p => ({ t: await txt(p) }), i => i.t.includes("https://example.com/articolo")],
  ["desktop", "editor.desktop", "link", async p => ({ t: await txt(p) }), i => i.t.includes("Editor di prova") && i.t.includes("editor %F")],
  ["torrent", "raccolta.torrent", "torrent", async p => ({ t: await txt(p) }), i => /8053ffc4305a99ff788b8da522ece96b974baa60/.test(i.t) && /promessi-sposi/.test(i.t)],
  // certificati e firme
  ["pem", "isrg-root-x1.pem", "cert", async p => ({ t: await txt(p) }), i => /ISRG Root X1/.test(i.t) && /RSA 4096 bit/.test(i.t) && /SHA-256/.test(i.t)],
  ["der", "testCERT.der", "cert", async p => ({ t: await txt(p) }), i => /Apache Tika/.test(i.t)],
  ["p7m-fattura", "IT01234567890_00001.xml.p7m", "p7m", async p => ({ t: await txt(p), ok: await ev(p, () => !!document.querySelector(".kv.ok")) }), i => /Mario Rossi/.test(i.t) && i.ok && /Officina di Prova/.test(i.t) && /183\.00/.test(i.t)],
  ["p7m-pdf", "contratto.pdf.p7m", "p7m", async p => { await scrollAll(p); return { c: await canv(p), ok: await ev(p, () => !!document.querySelector(".kv.ok")) }; }, i => i.c >= 1 && i.ok],
  ["p7m-reale", "testPKCS17Sig.xml.p7m", "p7m", async p => ({ t: await txt(p), ok: await ev(p, () => !!document.querySelector(".kv.ok")) }), i => /Alessandro De Angelis/.test(i.t) && /Expired/.test(i.t) && i.ok],
  ["fattura-xml", "IT01234567890_00001.xml", "fattura", async p => ({ t: await txt(p), rows: await ev(p, () => document.querySelectorAll(".fattura-righe tr").length) }), i => i.rows === 3 && /Due date/.test(i.t) && /IT60X0542811101000000123456/.test(i.t)],
  // documenti-immagine
  ["tiff-fax", "fax-multipagina.tif", "tiff", async p => { await scrollAll(p); return { pages: await ev(p, () => document.querySelectorAll(".raster-box").length), c: await canv(p) }; }, i => i.pages === 3 && i.c >= 2],
  ["tiff-jpeg", "testTIFF_multipage.tif", "tiff", async p => { await scrollAll(p); return { pages: await ev(p, () => document.querySelectorAll(".raster-box").length), c: await canv(p) }; }, i => i.pages === 2 && i.c >= 1],
  ["psd", "grafica.psd", "psd", async p => ({ c: await canv(p) }), i => i.c === 1],
  ["psd-livelli", "testPSD2.psd", "psd", async p => ({ c: await ev(p, () => document.querySelectorAll("canvas").length), t: await txt(p) }), i => i.c === 1 && /Calque 2/.test(i.t)],
  ["eps-anteprima", "logo-anteprima.eps", "eps", async p => ({ c: await canv(p) }), i => i.c >= 1],
  ["eps-senza", "vettoriale-senza-anteprima.eps", "eps", async p => ({ t: await txt(p) }), i => /AGPL/.test(i.t) && /141 × 119 mm/.test(i.t)],
  ["ai-pdf", "illustrazione.ai", "auto", async p => { await scrollAll(p); return { c: await canv(p) }; }, i => i.c >= 1],
  // riconoscimento dai byte, senza estensione ne' tipo (arrivano come application/octet-stream)
  ["auto-msg", "testMSG.msg", "auto", async p => ({ s: await ev(p, () => document.querySelector(".mail-subject")?.textContent) }), i => i.s === "MIME registry use cases", { name: "allegato" }],
  ["auto-7z", "archivio.7z", "auto", async p => ({ n: await ev(p, () => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 3, { name: "file" }],
  ["auto-mobi", "alice.mobi", "auto", async p => ({ t: await ev(p, () => !!document.querySelector(".shadow-host")) }), i => i.t, { name: "file" }],
  ["auto-p7m", "contratto.pdf.p7m", "auto", async p => ({ ok: await ev(p, () => !!document.querySelector(".kv.ok")) }), i => i.ok, { name: "file" }],
  ["auto-tiff", "fax-multipagina.tif", "auto", async p => ({ n: await ev(p, () => document.querySelectorAll(".raster-box").length) }), i => i.n === 3, { name: "file" }],
  ["auto-xps", "testPPT.xps", "auto", async p => ({ n: await ev(p, () => document.querySelectorAll(".xps-page svg").length) }), i => i.n === 3, { name: "file" }],
  ["auto-pages", "testPages2013.pages", "auto", async p => ({ t: (await txt(p)).includes("Some plain text") }), i => i.t, { name: "file.pages" }],
  // non supportato, con messaggio chiaro
  ["djvu", "testDJVU.djvu", "auto", async p => ({}), (i, r) => r.failed && /GPL/.test(r.failed[1]), { expectFail: true }],
  ["zst", "testZSTD.zst", "archive", async p => ({}), (i, r) => r.failed && /Zstandard/.test(r.failed[1]), { expectFail: true }],
];
for (const [label, file, fmt, probe, check, opt = {}] of CASES) {
  if (!want(label)) continue;
  if (sizeOf(file) < 0) { ok(false, `${label}: campione mancante ${file}`); continue; }
  const r = await open(file, fmt, { name: opt.name || file });
  const info = await probe(r.p).catch(e => ({ err: String(e) }));
  const good = opt.expectFail ? check(info, r) : r.loaded && !r.failed && check(info, r);
  ok(good, `${label}: ${file}`, `${r.ms} ms · ${r.format || ""} · ${JSON.stringify(info).slice(0, 200)}${r.failed ? " · FALLITO: " + r.failed[1] : ""}`);
  ok(r.external.length === 0, `${label}: nessuna richiesta esterna`, r.external.join(" "));
  if (r.errors.length && !opt.expectFail) console.log("      errori JS:", r.errors.join(" | ").slice(0, 300));
  await shot(r.p, label);
  if (["msg-rtf-html", "doc-stili", "ipynb", "gpx", "p7m-fattura", "fattura-xml", "xps", "drawio"].includes(label)) await shot(r.p, label + "-intera", true);
  await r.p.close();
  // tema scuro: una volta per famiglia
  if (["msg", "doc-lo", "pages", "xps", "mobi", "cbz", "7z", "ipynb", "gpx", "drawio", "url", "pem", "p7m-fattura", "tiff-fax", "psd"].includes(label)) {
    const d = await open(file, fmt, { dark: true });
    await shot(d.p, label + "-scuro");
    await d.p.close();
  }
}

// ------------------------------------------------------------------ interazione: file dentro un 7z / un .msg
if (want("interno")) {
  const r = await open("archivio.7z", "archive");
  await ev(r.p, () => [...document.querySelectorAll(".entry[role=button]")].find(e => e.textContent.includes("script.py")).click());
  await r.p.waitForSelector("pre.hljs", { timeout: 10000 }).catch(() => {});
  ok(await ev(r.p, () => !!document.querySelector("pre.hljs .hljs-keyword")), "interno: .py dentro un 7z con i colori di sintassi");
  await shot(r.p, "interno-7z-py");
  await r.p.click("#backBtn"); await r.p.waitForTimeout(200);
  ok(await ev(r.p, () => document.querySelectorAll(".entry[role=button]").length) === 3, "interno: «Torna all'elenco» riporta la lista del 7z");
  await r.p.close();
  const m = await open("testMSG_att_doc.msg", "msg");
  await ev(m.p, () => [...document.querySelectorAll(".attachments .entry")].find(e => e.textContent.includes("test-unicode.doc")).click());
  await m.p.waitForSelector("article.doc97", { timeout: 15000 }).catch(() => {});
  ok(await ev(m.p, () => !!document.querySelector("article.doc97")), "interno: allegato .doc di un .msg aperto col visualizzatore Word");
  await shot(m.p, "interno-msg-doc");
  await m.p.close();
  const rar = await open("test-documents.rar", "archive");
  await ev(rar.p, () => [...document.querySelectorAll(".entry[role=button]")].find(e => e.textContent.includes("testPDF.pdf")).click());
  await rar.p.waitForTimeout(2500);
  ok(await canv(rar.p) >= 1, "interno: PDF dentro un RAR disegnato con pdf.js");
  await shot(rar.p, "interno-rar-pdf");
  await rar.p.close();
}

// ------------------------------------------------------------------ text.html: codice e dati
if (want("testo")) {
  for (const [file, lang, sel] of [["query.sql", "sql", ".hljs-keyword"], ["modifica.diff", "diff", ".hljs-addition"], ["articolo.tex", "latex", ".hljs-keyword"],
    ["Dockerfile", "dockerfile", ".hljs-keyword"], ["Makefile", "makefile", ".hljs-section, .hljs-title"], ["build.gradle", "groovy", ".hljs-string"],
    ["esempio.rs", "rust", ".hljs-keyword"], ["esempio.go", "go", ".hljs-keyword"], ["esempio.kt", "kotlin", ".hljs-keyword"], ["app.properties", "ini", ".hljs-attr"],
    ["config.env", "ini", ".hljs-attr"], ["manuale.adoc", "asciidoc", ".hljs-section"], ["testBIBTEX.bib", "latex", ".hljs-keyword, .hljs-built_in, .hljs-symbol"],
    ["eventi.jsonl", "json", ".hljs-attr"]]) {
    const r = await open(file, lang, { page: "text" });
    await r.p.waitForSelector(sel, { timeout: 8000 }).catch(() => {});
    const has = await ev(r.p, s => document.querySelectorAll(s).length, sel);
    const extra = file.endsWith(".jsonl") ? await ev(r.p, () => document.getElementById("note")?.textContent || "") : "";
    ok(has > 0 && !r.errors.length && (!file.endsWith(".jsonl") || /20 record/.test(extra)), `testo ${file}: colori (${lang})`, `${has} ${extra} ${r.errors.join("|")}`);
    await shot(r.p, "testo-" + file.replace(/\./g, "-"));
    await r.p.close();
  }
}

await browser.close();
console.log(`\n${passes} PASS, ${failures} FAIL`);
process.exit(failures ? 1 : 0);
