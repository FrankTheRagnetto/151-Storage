// Prove su PC (Chromium headless, telefono Android emulato) delle pagine della v3: doc.html per ogni
// formato, text.html con evidenziazione, pagina HTML vera con script spenti/accesi.
// Uso:
//   VIEWER_EXTRA=/tmp/vault-samples python3 tools/web-test/serve.py 8151 &
//   PW_CORE=/percorso/playwright-core CHROME=/percorso/chrome node tools/web-test/run-v3.mjs [filtro]
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PW_CORE || "playwright-core");
const BASE = process.env.BASE || "http://127.0.0.1:8151";
const HERE = path.dirname(new URL(import.meta.url).pathname);
const SAMPLES = path.join(HERE, "..", "samples");
const SHOTS = path.join(HERE, "out", "shots-v3");
fs.mkdirSync(SHOTS, { recursive: true });
const FILTER = process.argv[2] || "";

let failures = 0, passes = 0;
const ok = (cond, label, extra = "") => {
  console.log(`${cond ? "PASS" : "FAIL"}  ${label}${extra ? "  — " + extra : ""}`);
  if (cond) passes++; else failures++;
};

const browser = await chromium.launch({ executablePath: process.env.CHROME, headless: true });
const mkCtx = (dark = false, js = true) => browser.newContext({
  viewport: { width: 412, height: 860 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, javaScriptEnabled: js,
  colorScheme: dark ? "dark" : "light",
  userAgent: "Mozilla/5.0 (Linux; Android 15; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
});
async function bridge(ctx) {
  await ctx.addInitScript(() => {
    window.__calls = [];
    window.Android = new Proxy({}, { get: (_, k) => (...a) => { window.__calls.push([k, ...a]); } });
  });
}
const ctxL = await mkCtx(false); await bridge(ctxL);
const ctxD = await mkCtx(true); await bridge(ctxD);

async function open(file, fmt, { dark = false, extra = "" } = {}) {
  const ctx = dark ? ctxD : ctxL;
  const p = await ctx.newPage();
  const errors = [], external = [];
  p.on("pageerror", e => errors.push(String(e)));
  p.on("console", m => { if (m.type() === "error" && !/favicon|rel\?p=x$/.test(m.location().url || "")) errors.push(m.text() + " @" + (m.location().url || "")); });
  // "uscita" = una richiesta fuori da BASE che parte davvero (anche se poi la CSP la blocca: nell'app non deve nemmeno nascere)
  p.on("request", r => { const u = r.url(); if (!u.startsWith(BASE) && !u.startsWith("blob:") && !u.startsWith("data:")) external.push(u); });
  const size = fs.existsSync(path.join(SAMPLES, file)) ? fs.statSync(path.join(SAMPLES, file)).size : -1;
  const t0 = Date.now();
  await p.goto(`${BASE}/assets/viewer/doc.html?src=/file/${encodeURIComponent(file)}/&size=${size}&name=${encodeURIComponent(file)}&fmt=${fmt}&theme=${dark ? "dark" : "light"}${extra}`);
  await p.waitForFunction(() => window.__calls.some(c => c[0] === "onLoaded" || c[0] === "onFailed"), null, { timeout: 30000 }).catch(() => {});
  const ms = Date.now() - t0;
  const calls = await p.evaluate(() => window.__calls);
  return { p, errors, external, calls, ms, failed: calls.find(c => c[0] === "onFailed"), loaded: calls.some(c => c[0] === "onLoaded"), format: (calls.find(c => c[0] === "onFormat") || [])[1] };
}
const shot = (p, name, full = false) => p.screenshot({ path: path.join(SHOTS, name + ".png"), fullPage: full });
const want = n => !FILTER || n.includes(FILTER);

// ------------------------------------------------------------------ Markdown sintetico con tutto
if (want("md")) {
  for (const dark of [false, true]) {
    const r = await open("nota-obsidian.md", "md", { dark });
    const { p } = r;
    await p.waitForTimeout(dark ? 3500 : 3500); // mermaid
    const q = sel => p.evaluate(s => document.querySelectorAll(s).length, sel);
    const tag = dark ? " (scuro)" : "";
    ok(r.loaded && !r.failed, "md: aperto" + tag, `${r.ms} ms, formato ${r.format}`);
    if (!dark) {
      ok(await q(".props .prop") >= 5, "md: frontmatter -> riquadro Proprietà", `${await q(".props .prop")} proprietà`);
      ok(await q(".props .tag") === 2, "md: tag nel frontmatter come pillole");
      ok(await q("h1") >= 1 && await q("h2") >= 5, "md: titoli");
      ok(await q("strong") >= 2 && await q("em") >= 2 && await q("del, s") >= 1, "md: grassetto/corsivo/barrato");
      ok(await q("mark") === 1, "md: ==evidenziato==");
      ok(await q("a.internal-link") === 2, "md: [[wikilink]] e [[x|alias]]");
      ok(await p.evaluate(() => [...document.querySelectorAll("a.internal-link")].map(a => a.textContent).join("|")) === "Wikilink|alias mostrato", "md: alias del wikilink mostrato");
      ok(await q("span.tag") >= 1, "md: #tag in linea");
      ok(await q("li.task-list-item input[type=checkbox][disabled]") === 4, "md: 4 attività con checkbox (sola lettura)");
      ok(await p.evaluate(() => [...document.querySelectorAll("li.task-list-item input")].filter(i => i.checked).length) === 3, "md: [x] [/] [-] spuntate, [ ] no");
      ok(await q("table th") === 3 && await q("table td") === 9, "md: tabella");
      ok(await q(".callout") === 4, "md: 4 callout", `${await q(".callout")}`);
      ok(await q("details.callout") === 2 && await p.evaluate(() => document.querySelectorAll("details.callout[open]").length) === 1, "md: callout pieghevoli (- chiuso, + aperto)");
      ok(await p.evaluate(() => document.querySelector('.callout[data-callout=danger] .callout-title-inner')?.textContent) === "Danger", "md: callout senza titolo prende il nome del tipo");
      ok(await q("pre.hljs .hljs-keyword") >= 1, "md: codice evidenziato (highlight.js)");
      ok(await q(".katex") >= 3, "md: formule KaTeX (in linea e a blocco)", `${await q(".katex")}`);
      ok(await q(".katex-display") >= 1, "md: formula $$ a blocco");
      ok(await q(".mermaid svg") === 1, "md: diagramma mermaid disegnato", `${await q(".mermaid svg")}`);
      ok(await q("sup.footnote-ref") === 2 && await q(".footnotes li") === 2, "md: note a piè di pagina");
      await p.evaluate(async () => { for (const i of document.querySelectorAll("article img")) { i.scrollIntoView(); await new Promise(r => setTimeout(r, 300)); } });
      ok(await p.evaluate(() => { const i = [...document.querySelectorAll("article img")]; return i.length === 2 && i.every(x => x.complete && x.naturalWidth === 200); }), "md: immagini relative ![]() e ![[x|120]] caricate", await p.evaluate(() => [...document.querySelectorAll("article img")].map(i => i.src + ":" + i.naturalWidth).join(" ")));
      ok(await p.evaluate(() => document.querySelector("img[data-embed]")?.style.width) === "120px", "md: ![[img|120]] larga 120 px");
      ok(await q(".img-missing") === 2, "md: immagine remota e immagine inesistente NON caricate, con avviso", await p.evaluate(() => [...document.querySelectorAll(".img-missing")].map(e => e.textContent).join(" / ")));
      ok(await p.evaluate(() => !document.body.innerHTML.includes("commento Obsidian")), "md: %%commento%% nascosto");
      ok(await p.evaluate(() => !document.querySelector("article script") && ![...document.querySelectorAll("article img")].some(i => i.getAttribute("onerror"))), "md: <script> e onerror tolti (DOMPurify)");
      ok(r.external.length === 0, "md: nessuna richiesta esterna", r.external.join(" "));
      ok(r.errors.length === 0, "md: nessun errore JS", r.errors.join(" | ").slice(0, 300));
    } else {
      ok(await p.evaluate(() => getComputedStyle(document.body).backgroundColor) === "rgb(30, 30, 30)", "md: tema scuro come Obsidian (#1e1e1e)");
    }
    await shot(p, "md-1-alto" + (dark ? "-scuro" : ""));
    await p.evaluate(() => document.querySelector(".callout").scrollIntoView());
    await shot(p, "md-2-callout" + (dark ? "-scuro" : ""));
    await p.evaluate(() => document.querySelector(".katex-display").scrollIntoView({ block: "center" }));
    await shot(p, "md-3-codice-formule" + (dark ? "-scuro" : ""));
    await p.evaluate(() => (document.querySelector(".mermaid") || document.querySelector("pre.mermaid-src"))?.scrollIntoView({ block: "center" }));
    await shot(p, "md-4-mermaid" + (dark ? "-scuro" : ""));
    if (!dark) {
      await p.click("details.callout:not([open]) summary");
      ok(await p.evaluate(() => document.querySelectorAll("details.callout[open]").length) === 2, "md: tocco sul callout chiuso lo apre");
      await shot(p, "md-5-intera", true);
    }
    await p.close();
  }
  // note vere del vault (copiate in /tmp, non nel repo)
  const vault = "/tmp/vault-samples";
  if (fs.existsSync(vault)) {
    for (const f of fs.readdirSync(vault).filter(f => f.endsWith(".md"))) {
      const r = await open(f, "md");
      await r.p.waitForTimeout(500);
      const info = await r.p.evaluate(() => ({ h: document.querySelectorAll("article h1,article h2,article h3").length, t: document.querySelectorAll("article table").length, c: document.querySelectorAll(".callout").length, props: document.querySelectorAll(".prop").length, code: document.querySelectorAll("article pre").length, tasks: document.querySelectorAll(".task-list-item").length, wl: document.querySelectorAll("a.internal-link").length }));
      ok(r.loaded && !r.failed && r.errors.length === 0, `md vault: ${f}`, JSON.stringify(info) + " " + r.errors.join("|").slice(0, 200));
      await shot(r.p, "vault-" + f.replace(/[^\w]+/g, "_").slice(0, 30), true);
      await r.p.close();
    }
  }
}

// ------------------------------------------------------------------ Office e simili
const CASES = [
  ["documento.docx", "docx", async p => ({ pages: await p.evaluate(() => document.querySelectorAll("section.docx").length), tables: await p.evaluate(() => document.querySelectorAll("section.docx table").length), imgs: await p.evaluate(() => [...document.querySelectorAll("section.docx img, section.docx image")].filter(i => i.naturalWidth > 0 || i.getAttribute("href")).length), fit: await p.evaluate(() => document.querySelector("section.docx").getBoundingClientRect().right <= innerWidth + 1), h: await p.evaluate(() => document.body.innerText.includes("Relazione di prova")) }), i => i.pages >= 1 && i.tables >= 1 && i.imgs >= 1 && i.h && i.fit],
  ["documento.doc", "doc", async p => ({ txt: await p.evaluate(() => document.body.innerText.includes("Relazione di prova") && document.body.innerText.includes("Mele")), td: await p.evaluate(() => document.querySelectorAll("td").length) }), i => i.txt && i.td >= 6],
  ["foglio.xlsx", "xlsx", async p => ({ tabs: await p.evaluate(() => document.querySelectorAll(".tabs button").length), cur: await p.evaluate(() => [...document.querySelectorAll("td")].some(t => /€/.test(t.textContent))), fill: await p.evaluate(() => [...document.querySelectorAll("td")].some(t => t.style.background)) }), i => i.tabs === 3 && i.cur],
  ["foglio.xls", "xls", async p => ({ tabs: await p.evaluate(() => document.querySelectorAll(".tabs button").length), cells: await p.evaluate(() => document.querySelectorAll("td").length) }), i => i.tabs === 3 && i.cells > 5],
  ["foglio.ods", "ods", async p => ({ tabs: await p.evaluate(() => document.querySelectorAll(".tabs button").length), cells: await p.evaluate(() => document.querySelectorAll("td").length) }), i => i.tabs === 3 && i.cells > 5],
  ["tabella.csv", "csv", async p => ({ cells: await p.evaluate(() => [...document.querySelectorAll("td")].map(t => t.textContent)) }), i => i.cells.includes("Rossi, Maria") && i.cells.includes("12,50")],
  ["presentazione.pptx", "pptx", async p => ({ slides: await p.evaluate(() => document.querySelectorAll(".slide").length), img: await p.evaluate(() => [...document.querySelectorAll(".slide img")].filter(i => i.naturalWidth).length), title: await p.evaluate(() => document.body.innerText.includes("Presentazione di prova")) }), i => i.slides === 3 && i.img >= 1 && i.title],
  ["presentazione.odp", "odp", async p => ({ slides: await p.evaluate(() => document.querySelectorAll(".slide").length), img: await p.evaluate(() => [...document.querySelectorAll(".slide img")].filter(i => i.naturalWidth).length) }), i => i.slides === 3 && i.img >= 1],
  ["presentazione.ppt", "ppt", async p => ({ n: await p.evaluate(() => document.querySelectorAll(".slide-text").length), t: await p.evaluate(() => document.body.innerText.includes("Rettangolo blu")) }), i => i.n === 3 && i.t],
  ["documento.odt", "odt", async p => ({ h: await p.evaluate(() => document.querySelectorAll("article h1, article h2").length), td: await p.evaluate(() => document.querySelectorAll("article td").length), img: await p.evaluate(() => [...document.querySelectorAll("article img")].filter(i => i.naturalWidth).length), b: await p.evaluate(() => [...document.querySelectorAll("article span")].some(s => s.style.fontWeight === "700")), ol: await p.evaluate(() => document.querySelectorAll("article ol").length), fn: await p.evaluate(() => document.querySelectorAll(".footnotes-odt div").length) }), i => i.h >= 3 && i.td >= 9 && i.img >= 1 && i.b && i.ol >= 1 && i.fn === 1],
  ["documento.rtf", "rtf", async p => ({ txt: await p.evaluate(() => document.body.innerText.includes("Relazione di prova")), b: await p.evaluate(() => [...document.querySelectorAll("article span")].some(s => s.style.fontWeight === "700")), td: await p.evaluate(() => document.querySelectorAll("article td").length), img: await p.evaluate(() => [...document.querySelectorAll("article img")].filter(i => i.naturalWidth).length) }), i => i.txt && i.b && i.td >= 6 && i.img >= 1],
  ["libro.epub", "epub", async p => ({ opts: await p.evaluate(() => document.querySelectorAll(".book-bar option").length), h: await p.evaluate(() => document.querySelector(".shadow-host").shadowRoot.querySelector("h1")?.textContent), img: await p.evaluate(() => [...document.querySelector(".shadow-host").shadowRoot.querySelectorAll("img")].filter(i => i.naturalWidth).length), script: await p.evaluate(() => document.querySelector(".shadow-host").shadowRoot.innerHTML.includes("SCRIPT")) }), i => i.opts === 2 && /Capitolo 1/.test(i.h) && i.img === 1 && !i.script],
  ["archivio.zip", "zip", async p => ({ n: await p.evaluate(() => document.querySelectorAll(".entry[role=button]").length) }), i => i.n === 5],
  ["email.eml", "eml", async p => ({ subj: await p.evaluate(() => document.querySelector(".mail-subject")?.textContent), from: await p.evaluate(() => document.querySelector(".mail-row span")?.textContent), cid: await p.evaluate(() => [...document.querySelector(".shadow-host").shadowRoot.querySelectorAll("img")].filter(i => i.naturalWidth).length), att: await p.evaluate(() => document.querySelectorAll(".attachments .entry").length), scr: await p.evaluate(() => !!document.querySelector(".shadow-host").shadowRoot.querySelector("script")) }), i => i.subj === "Prova email con allegato — è ok" && /Mario Rossi/.test(i.from) && i.cid === 1 && i.att === 1 && !i.scr],
  ["pagina.mhtml", "mhtml", async p => ({ h1: await p.evaluate(() => getComputedStyle(document.querySelector(".shadow-host").shadowRoot.querySelector("h1")).color), img: await p.evaluate(() => [...document.querySelector(".shadow-host").shadowRoot.querySelectorAll("img")].filter(i => i.naturalWidth).length) }), i => i.h1 === "rgb(0, 102, 204)" && i.img === 1],
  ["contatto.vcf", "vcf", async p => ({ n: await p.evaluate(() => document.querySelectorAll(".card").length), tel: await p.evaluate(() => document.body.innerText.includes("+39 333 000 0000")) }), i => i.n === 2 && i.tel],
  ["evento.ics", "ics", async p => ({ n: await p.evaluate(() => document.querySelectorAll(".card").length), rr: await p.evaluate(() => document.body.innerText.includes("every week, 4 times")), loc: await p.evaluate(() => document.body.innerText.includes("Sala A, primo piano")) }), i => i.n === 2 && i.rr && i.loc],
  ["sottotitoli.srt", "srt", async p => ({ n: await p.evaluate(() => document.querySelectorAll(".cue").length), it: await p.evaluate(() => document.querySelectorAll(".cue i").length) }), i => i.n === 3 && i.it === 1],
  ["carattere.ttf", "font", async p => ({ fam: await p.evaluate(() => document.querySelector(".card-name")?.textContent), loaded: await p.evaluate(() => document.fonts.check("20px anteprima151")) }), i => /Liberation|DejaVu/.test(i.fam || "") && i.loaded],
  // formato riconosciuto dai byte, senza estensione ne' tipo
  ["documento.docx", "auto", async p => ({ pages: await p.evaluate(() => document.querySelectorAll("section.docx").length) }), i => i.pages >= 1, "auto-docx"],
  ["presentazione.pptx", "auto", async p => ({ slides: await p.evaluate(() => document.querySelectorAll(".slide").length) }), i => i.slides === 3, "auto-pptx"],
  ["foglio.xls", "auto", async p => ({ tabs: await p.evaluate(() => document.querySelectorAll(".tabs button").length) }), i => i.tabs === 3, "auto-xls"],
];
for (const [file, fmt, probe, check, tag] of CASES) {
  const label = tag || fmt;
  if (!want(label)) continue;
  for (const dark of label.startsWith("auto") ? [false] : [false, true]) {
    const r = await open(file, fmt, { dark });
    await r.p.waitForTimeout(700);
    const info = await probe(r.p).catch(e => ({ err: String(e) }));
    if (!dark) {
      ok(r.loaded && !r.failed && check(info), `${label}: ${file}`, `${r.ms} ms · ${r.format} · ${JSON.stringify(info).slice(0, 220)}${r.failed ? " · FALLITO: " + r.failed[1] : ""}`);
      ok(r.external.length === 0, `${label}: nessuna richiesta esterna`, r.external.join(" "));
      if (r.errors.length) console.log("      errori JS:", r.errors.join(" | ").slice(0, 400));
    }
    await shot(r.p, `${label}${dark ? "-scuro" : ""}`);
    if (!dark && ["docx", "pptx", "odp", "odt", "rtf", "eml", "xlsx"].includes(label)) await shot(r.p, `${label}-intera`, true);
    if (!dark && label === "xlsx") {
      await r.p.click(".tabs button:nth-child(2)");
      await r.p.waitForTimeout(200);
      ok(await r.p.evaluate(() => document.body.innerText.includes("Bologna")), "xlsx: cambio scheda (foglio Clienti)");
      await shot(r.p, "xlsx-scheda2");
    }
    if (!dark && label === "epub") {
      await r.p.click(".book-nav button:nth-child(2)");
      await r.p.waitForTimeout(300);
      ok(/Capitolo 2/.test(await r.p.evaluate(() => document.querySelector(".shadow-host").shadowRoot.querySelector("h1")?.textContent || "")), "epub: «Successivo» porta al capitolo 2");
      await shot(r.p, "epub-cap2");
    }
    if (!dark && label === "zip") {
      for (const [n, sel, name] of [["nota-obsidian.md", ".callout", "zip-md"], ["documento.docx", "section.docx", "zip-docx"], ["immagine.png", "img.zip-img", "zip-png"]]) {
        await r.p.evaluate(n => [...document.querySelectorAll(".entry[role=button]")].find(e => e.textContent.includes(n)).click(), n);
        await r.p.waitForSelector(sel, { timeout: 15000 }).catch(() => {});
        ok(await r.p.evaluate(s => !!document.querySelector(s), sel), `zip: apre ${n} dall'interno`);
        await r.p.waitForTimeout(300);
        await shot(r.p, name);
        await r.p.click("#backBtn");
        await r.p.waitForTimeout(200);
      }
      ok(await r.p.evaluate(() => document.querySelectorAll(".entry[role=button]").length) === 5, "zip: «Torna all'elenco» riporta la lista");
    }
    if (!dark && label === "eml") {
      await r.p.evaluate(() => document.querySelector(".attachments .entry").click());
      await r.p.waitForTimeout(400);
      ok(await r.p.evaluate(() => document.body.innerText.includes("Questo è l'allegato")), "eml: apre l'allegato di testo");
      await r.p.click("#backBtn");
    }
    await r.p.close();
  }
}

// ------------------------------------------------------------------ text.html: evidenziazione e log
if (want("text")) {
  for (const [file, lang, sel] of [["config.yaml", "yaml", ".hljs-attr"], ["config.ini", "ini", ".hljs-section"], ["config.toml", "ini", ".hljs-section"], ["app.log", "log", ".lv-e"]]) {
    const p = await ctxL.newPage();
    const errs = []; p.on("pageerror", e => errs.push(String(e)));
    await p.goto(`${BASE}/assets/viewer/text.html?src=/file/${file}/&size=-1&max=8388608&name=${file}&lang=${lang}&theme=light`);
    await p.waitForSelector(sel, { timeout: 10000 }).catch(() => {});
    ok(await p.evaluate(s => document.querySelectorAll(s).length > 0, sel) && !errs.length, `testo ${file}: colori (${lang})`, errs.join("|"));
    await shot(p, "testo-" + file.replace(".", "-"));
    await p.close();
  }
}

// ------------------------------------------------------------------ HTML vero: script spenti / accesi
if (want("html")) {
  for (const js of [false, true]) {
    const ctx = await mkCtx(false, js);
    const p = await ctx.newPage();
    const ext = [];
    // qui la pagina e' del documento: la CSP blocca, conta cio' che riceve davvero una risposta
    p.on("requestfinished", r => { if (!r.url().startsWith(BASE) && !r.url().startsWith("data:")) ext.push(r.url()); });
    await p.goto(`${BASE}/h${js ? 1 : 0}/pagina.html`);
    await p.waitForTimeout(500);
    const t = await p.evaluate(() => document.getElementById("js").textContent).catch(() => null);
    const cssOk = await p.evaluate(() => getComputedStyle(document.querySelector(".esterno")).backgroundColor).catch(() => null);
    ok(js ? /ACCESO/.test(t || "") : /SPENTO/.test(t || document.body?.innerText || ""), `html: script ${js ? "accesi girano" : "spenti non girano"}`, t || "");
    ok(cssOk === "rgb(255, 224, 102)", "html: CSS accanto al file caricato", cssOk || "");
    ok(ext.length === 0, "html: nessuna richiesta esterna (CSP)", ext.join(" "));
    await shot(p, `html-script-${js ? "accesi" : "spenti"}`);
    await ctx.close();
  }
}

await browser.close();
console.log(`\n${passes} PASS, ${failures} FAIL`);
process.exit(failures ? 1 : 0);
