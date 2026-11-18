// Word. DOCX: docx-preview (pagine vere: stili, tabelle, immagini, intestazioni, note), mammoth come ripiego.
// DOC (Word 97-2003, binario): v5 non piu' solo testo. Dalla "piece table" il testo, dalle FKP dei
// caratteri (CHPX) grassetto/corsivo/sottolineato/barrato/dimensione/colore/apice/pedice, dalle FKP dei
// paragrafi (PAPX) titoli (stili 1-9), allineamento e tabelle, dai campi HYPERLINK i link, dal flusso "Data"
// le immagini in linea (PNG/JPEG dentro il PICF). Le immagini "mobili" si mettono in fondo. Scritto qui.
export async function render(ctx) {
  if (ctx.fmt === "doc") return renderDoc(ctx);
  return renderDocx(ctx);
}

async function renderDocx({ root, bytes, util }) {
  await util.need("docx");
  const host = util.el("div", { class: "docx-host" });
  root.append(host);
  const styles = util.el("div");
  host.append(styles);
  const body = util.el("div");
  host.append(body);
  try {
    await docx.renderAsync(bytes, body, styles, {
      className: "docx", inWrapper: true, ignoreWidth: false, ignoreHeight: false, ignoreFonts: false,
      breakPages: true, ignoreLastRenderedPageBreak: true, experimental: true, trimXmlDeclaration: true,
      useBase64URL: true, renderHeaders: true, renderFooters: true, renderFootnotes: true, renderEndnotes: true,
      renderComments: false, renderChanges: false, debug: false,
    });
    // punti elenco dei font Symbol/Wingdings: sono nell'area privata Unicode, qui uscirebbero come quadratini
    const PUA = { "\uF0B7": "•", "\uF0A7": "▪", "\uF0D8": "➢", "\uF0FC": "✓", "\uF076": "❖", "\uF0A8": "□", "\uF06E": "■", "\uF0E0": "➔" };
    for (const st of host.querySelectorAll("style")) {
      if (/[\uF000-\uF0FF]/.test(st.textContent)) st.textContent = st.textContent.replace(/[\uF000-\uF0FF]/g, c => PUA[c] || "•");
    }
    // pagina larga come il foglio: zoom nativo del telefono, adattata alla larghezza all'apertura
    const sec = body.querySelector("section.docx");
    const w = sec ? sec.getBoundingClientRect().width : 816;
    document.body.classList.add("page-mode");
    util.mode("page", w + 24);
    // fuori rete: le immagini remote collegate non si caricano
    body.querySelectorAll("img").forEach(img => { if (/^https?:/i.test(img.getAttribute("src") || "")) img.removeAttribute("src"); });
  } catch (e) {
    console.warn("docx-preview failed, trying mammoth", e);
    host.remove();
    await util.need("mammoth");
    const res = await mammoth.convertToHtml({ arrayBuffer: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
    await util.need("purify");
    const art = util.el("article", { class: "md paper" });
    art.innerHTML = DOMPurify.sanitize(res.value);
    root.append(art);
    util.notice("Simplified preview (layout not exact)");
  }
}

// ------------------------------------------------------------------ Word 97-2003
function renderDoc(ctx) {
  try { return renderDoc97(ctx); }
  catch (e) { console.warn("DOC: rich reading failed, text only", e); ctx.root.replaceChildren(); return renderDocText(ctx); }
}

function renderDocText({ root, bytes, cfb, util }) {
  const find = n => cfb.FileIndex.find(f => f.name === n);
  const wd = find("WordDocument");
  if (!wd) throw Object.assign(new Error("Word document with no readable content."), { user: true });
  const w = u8(wd.content);
  const dv = new DataView(w.buffer, w.byteOffset, w.byteLength);
  if (dv.getUint16(0, true) !== 0xA5EC) throw Object.assign(new Error("Not a valid Word 97-2003 document."), { user: true });
  const flags = dv.getUint16(0x0A, true);
  if (flags & 0x0100) throw Object.assign(new Error("The document is password-protected."), { user: true });
  const table = find(flags & 0x0200 ? "1Table" : "0Table");
  const ccpText = dv.getInt32(0x4C, true);
  const fcClx = dv.getUint32(0x1A2, true), lcbClx = dv.getUint32(0x1A6, true);
  let text = "";
  if (table && lcbClx > 0) {
    const t = u8(table.content);
    const tv = new DataView(t.buffer, t.byteOffset, t.byteLength);
    let p = fcClx;
    while (t[p] === 0x01) p += 3 + tv.getUint16(p + 1, true); // Prc (formattazione) da saltare
    if (t[p] !== 0x02) throw new Error("piece table missing");
    const lcb = tv.getUint32(p + 1, true);
    const base = p + 5;
    const n = (lcb - 4) / 12;
    const cps = [];
    for (let i = 0; i <= n; i++) cps.push(tv.getUint32(base + i * 4, true));
    for (let i = 0; i < n && text.length < ccpText; i++) {
      const pcd = base + (n + 1) * 4 + i * 8;
      let fc = tv.getUint32(pcd + 2, true);
      const len = cps[i + 1] - cps[i];
      if (fc & 0x40000000) {
        fc = (fc & ~0x40000000) >>> 1;
        text += new TextDecoder("windows-1252").decode(w.subarray(fc, fc + len));
      } else {
        text += new TextDecoder("utf-16le").decode(w.subarray(fc, fc + len * 2));
      }
    }
  } else {
    // file molto vecchi (Word 6/95): testo contiguo a partire da fcMin
    const fcMin = dv.getUint32(0x18, true), fcMac = dv.getUint32(0x1C, true);
    text = new TextDecoder("windows-1252").decode(w.subarray(fcMin, fcMac));
  }
  text = text.slice(0, Math.max(0, ccpText));
  // campi: \x13 codice \x14 risultato \x15 -> tieni il risultato
  text = text.replace(/\x13[^\x13\x14\x15]*\x14([^\x13\x15]*)\x15/g, "$1").replace(/\x13[^\x13\x15]*\x15/g, "");
  const art = util.el("article", { class: "md paper" });
  let table2 = null, row = null;
  const paras = text.split("\r");
  for (const raw of paras) {
    // \x07 = fine cella; una riga di tabella finisce con una cella vuota (\x07\x07)
    if (raw.includes("\x07")) {
      // ogni cella finisce con \x07 e ogni riga con un \x07 in piu': le colonne le dice la prima riga
      if (!table2) { table2 = util.el("table", { class: "doc-table" }); art.append(table2); }
      const cells = raw.split("\x07");
      const tail = cells.pop(); // testo dopo l'ultimo segno (paragrafo normale dopo la tabella)
      let ncol = cells.indexOf("");
      if (ncol <= 0) ncol = cells.length;
      for (let i = 0; i < cells.length; i += ncol + 1) {
        row = util.el("tr");
        for (const c of cells.slice(i, i + ncol)) row.append(util.el("td", { style: { border: "1px solid var(--border)", padding: "4px 8px" } }, clean(c)));
        table2.append(row);
      }
      if (tail) { table2 = null; art.append(util.el("p", null, clean(tail))); }
      continue;
    }
    table2 = null; row = null;
    art.append(util.el("p", null, clean(raw)));
  }
  root.append(art);
  util.notice("Word 97-2003: text only (no styles or images)");
}

// ------------------------------------------------------------------ Word 97-2003 "ricco"
const ICO = [null, "#000000", "#0000ff", "#00ffff", "#00ff00", "#ff00ff", "#ff0000", "#ffff00", "#ffffff", "#000080", "#008080", "#008000", "#800080", "#800000", "#808000", "#808080", "#c0c0c0"];
function sprms(buf, p, end, fn) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  while (p + 2 <= end) {
    const op = dv.getUint16(p, true); p += 2;
    const spra = op >> 13;
    let len = [1, 1, 2, 4, 2, 2, 0, 3][spra];
    let at = p;
    if (spra === 6) { if (op === 0xD608) { len = dv.getUint16(p, true) - 1; at = p + 2; p += 2; } else { len = buf[p]; at = p + 1; p += 1; } }
    if (at + len > buf.length) break;
    fn(op, at, len, dv);
    p = at + len;
  }
}
function renderDoc97({ root, cfb, util }) {
  const find = n => cfb.FileIndex.find(f => f.name === n);
  const wd = find("WordDocument");
  if (!wd) throw Object.assign(new Error("Word document with no readable content."), { user: true });
  const w = u8(wd.content);
  const dv = new DataView(w.buffer, w.byteOffset, w.byteLength);
  if (dv.getUint16(0, true) !== 0xA5EC) throw Object.assign(new Error("Not a valid Word 97-2003 document."), { user: true });
  const flags = dv.getUint16(0x0A, true);
  if (flags & 0x0100) throw Object.assign(new Error("The document is password-protected."), { user: true });
  if (dv.getUint16(0x02, true) < 0xC1) throw new Error("Word 6/95: text only");
  const tb = u8(find(flags & 0x0200 ? "1Table" : "0Table").content);
  const tv = new DataView(tb.buffer, tb.byteOffset, tb.byteLength);
  const dataS = find("Data") ? u8(find("Data").content) : null;
  const ccpText = dv.getInt32(0x4C, true);
  const fcClx = dv.getUint32(0x1A2, true);
  // piece table
  let p = fcClx;
  while (tb[p] === 0x01) p += 3 + tv.getUint16(p + 1, true);
  if (tb[p] !== 0x02) throw new Error("piece table missing");
  const lcb = tv.getUint32(p + 1, true), base = p + 5, n = (lcb - 4) / 12;
  const pieces = [];
  for (let i = 0; i < n; i++) {
    const cp0 = tv.getUint32(base + i * 4, true), cp1 = tv.getUint32(base + (i + 1) * 4, true);
    let fc = tv.getUint32(base + (n + 1) * 4 + i * 8 + 2, true);
    const comp = !!(fc & 0x40000000);
    if (comp) fc = (fc & ~0x40000000) >>> 1;
    pieces.push({ cp0, cp1, fc, comp });
  }
  // FKP -> [{a, b, off}] (intervalli di FC, ordinati)
  const fkps = (fcPlc, lcbPlc, papx) => {
    const out = [];
    if (!lcbPlc) return out;
    const k = (lcbPlc - 4) / 8;
    for (let i = 0; i < k; i++) {
      const pn = tv.getUint32(fcPlc + (k + 1) * 4 + i * 4, true) & 0x3FFFFF;
      const pg = pn * 512;
      if (pg + 512 > w.length) continue;
      const crun = w[pg + 511];
      for (let r = 0; r < crun; r++) {
        const a = dv.getUint32(pg + r * 4, true), b = dv.getUint32(pg + (r + 1) * 4, true);
        const bo = papx ? w[pg + (crun + 1) * 4 + r * 13] : w[pg + (crun + 1) * 4 + r];
        out.push({ a, b, off: bo ? pg + bo * 2 : 0 });
      }
    }
    out.sort((x, y) => x.a - y.a);
    return out;
  };
  const chpx = fkps(dv.getUint32(0xFA, true), dv.getUint32(0xFE, true), false);
  const papx = fkps(dv.getUint32(0x102, true), dv.getUint32(0x106, true), true);
  const look = (arr, fc) => { let lo = 0, hi = arr.length - 1; while (lo <= hi) { const m = (lo + hi) >> 1; if (fc < arr[m].a) hi = m - 1; else if (fc >= arr[m].b) lo = m + 1; else return arr[m]; } return null; };
  const chCache = new Map(), paCache = new Map();
  const charProps = fc => {
    const r = look(chpx, fc);
    const key = r ? r.off : 0;
    if (chCache.has(key)) return chCache.get(key);
    const pr = {};
    if (r && r.off) {
      const cb = w[r.off];
      sprms(w, r.off + 1, r.off + 1 + cb, (op, at, len, d) => {
        const v = w[at];
        switch (op) {
          case 0x0835: pr.b = v === 1 || v === 129; break;
          case 0x0836: pr.i = v === 1 || v === 129; break;
          case 0x0837: pr.s = v === 1 || v === 129; break;
          case 0x2A3E: pr.u = v !== 0; break;
          case 0x4A43: pr.size = d.getUint16(at, true) / 2; break;
          case 0x2A42: if (ICO[v]) pr.color = ICO[v]; break;
          case 0x6870: if (!(w[at + 3] & 0xFF)) pr.color = `rgb(${w[at]},${w[at + 1]},${w[at + 2]})`; break;
          case 0x2A48: pr.va = v === 1 ? "super" : v === 2 ? "sub" : null; break;
          case 0x6A03: pr.pic = d.getInt32(at, true); break;
          case 0x0855: pr.spec = v === 1; break;
          case 0x2A0C: if (v) pr.hl = ICO[v]; break;
        }
      });
    }
    chCache.set(key, pr);
    return pr;
  };
  const paraProps = fc => {
    const r = look(papx, fc);
    const key = r ? r.off : 0;
    if (paCache.has(key)) return paCache.get(key);
    const pr = { istd: 0 };
    if (r && r.off) {
      let cb = w[r.off], st = r.off + 1;
      if (cb === 0) { cb = w[r.off + 1] * 2; st = r.off + 2; } else cb = cb * 2 - 1;
      pr.istd = dv.getUint16(st, true);
      sprms(w, st + 2, st + cb, (op, at, len) => {
        const v = w[at];
        if (op === 0x2416) pr.inTable = v === 1;
        if (op === 0x2417) pr.ttp = v === 1;
        if (op === 0x2403 || op === 0x2461) pr.jc = v;
        if (op === 0x460B) pr.list = new DataView(w.buffer, w.byteOffset).getUint16(at, true) > 0;
        if (op === 0x260A) pr.lvl = v;
        if (op === 0x6649) pr.itap = v; // profondita' tabella (Word 2000+)
      });
    }
    paCache.set(key, pr);
    return pr;
  };
  // immagine in linea: PICF nel flusso Data, dentro un'immagine PNG o JPEG da cercare
  const used = new Set();
  const picAt = off => {
    if (!dataS || off < 0 || off + 0x44 > dataS.length) return null;
    const dd = new DataView(dataS.buffer, dataS.byteOffset, dataS.byteLength);
    const lcbP = dd.getUint32(off, true);
    const end = Math.min(dataS.length, off + lcbP);
    return scanImage(dataS, off + 0x44, end, used, off);
  };
  // testo, carattere per carattere (solo il corpo: note e intestazioni vengono dopo ccpText)
  const paras = [];
  let cur = { runs: [] };
  const fieldStack = [];
  const push = (text, pr, extra) => {
    const href = fieldStack.length ? fieldStack[fieldStack.length - 1].href : null;
    const last = cur.runs[cur.runs.length - 1];
    if (!extra && last && !last.img && last.pr === pr && last.href === href) last.t += text;
    else cur.runs.push({ t: text, pr, href, ...extra });
  };
  let floating = 0;
  for (const pc of pieces) {
    if (pc.cp0 >= ccpText) break;
    const cpEnd = Math.min(pc.cp1, ccpText);
    for (let cp = pc.cp0; cp < cpEnd; cp++) {
      const fc = pc.fc + (cp - pc.cp0) * (pc.comp ? 1 : 2);
      const code = pc.comp ? w[fc] : dv.getUint16(fc, true);
      const ch = pc.comp ? String.fromCharCode(cp1252(code)) : String.fromCharCode(code);
      // campi: \x13 codice \x14 risultato \x15 (HYPERLINK -> link)
      if (code === 0x13) { fieldStack.push({ code: "", mode: 0, href: null }); continue; }
      if (code === 0x14 && fieldStack.length) {
        const f = fieldStack[fieldStack.length - 1]; f.mode = 1;
        const m = /^\s*HYPERLINK\s+(?:\\l\s+)?"([^"]+)"/i.exec(f.code);
        if (m) f.href = /^(https?|mailto):/i.test(m[1]) ? m[1] : null;
        continue;
      }
      if (code === 0x15 && fieldStack.length) { fieldStack.pop(); continue; }
      if (fieldStack.length && fieldStack[fieldStack.length - 1].mode === 0) { fieldStack[fieldStack.length - 1].code += ch; continue; }
      if (code === 0x0D || code === 0x07 || code === 0x0C) {
        cur.end = code; cur.pp = paraProps(fc);
        paras.push(cur); cur = { runs: [] };
        continue;
      }
      const pr = charProps(fc);
      if (code === 0x01 && pr.pic != null) { const im = picAt(pr.pic); if (im) push("", pr, { img: im }); continue; }
      if (code === 0x08) { floating++; push("", pr, { float: true }); continue; }
      if (code === 0x0B) { push("\n", pr); continue; }
      if (code === 0x1E) { push("-", pr); continue; }
      if (code < 0x20 && code !== 0x09) continue;
      push(ch, pr);
    }
  }
  if (cur.runs.length) { cur.pp = { istd: 0 }; paras.push(cur); }
  // oggetti "mobili" (ancorati con \x08): le immagini si cercano nei flussi e si mettono agli ancoraggi, in ordine
  const extra = [];
  if (floating || !used.size) {
    for (const src of [dataS, w, tb]) if (src) for (let im; (im = scanImage(src, 0, src.length, used)); ) extra.push(im);
  }
  for (const para of paras) for (const r of para.runs) if (r.float && extra.length) { r.img = extra.shift(); r.floatImg = true; }
  // disegno
  const art = util.el("article", { class: "md paper doc97" });
  const runEl = r => {
    if (r.float && !r.img) return document.createTextNode("");
    if (r.img) { const i = util.el("img", { src: r.img, alt: "", class: r.floatImg ? "doc-float" : null }); return i; }
    let e = document.createTextNode(r.t);
    const pr = r.pr;
    const wrap = (tag, el) => { const x = document.createElement(tag); x.append(el); return x; };
    if (pr.b) e = wrap("b", e);
    if (pr.i) e = wrap("i", e);
    if (pr.u) e = wrap("u", e);
    if (pr.s) e = wrap("s", e);
    if (pr.va) e = wrap(pr.va === "super" ? "sup" : "sub", e);
    if (pr.color || pr.size || pr.hl) {
      const sp = document.createElement("span");
      if (pr.color && pr.color !== "#000000") sp.style.color = pr.color;
      if (pr.size && pr.size !== 12) sp.style.fontSize = (pr.size / 12).toFixed(2) + "em";
      if (pr.hl) sp.style.background = pr.hl;
      sp.append(e); e = sp;
    }
    if (r.href) { const a = document.createElement("a"); a.href = r.href; a.append(e); e = a; }
    return e;
  };
  const fill = (el, para) => { for (const r of para.runs) el.append(runEl(r)); if (!para.runs.length) el.append("\u00a0"); return el; };
  const JC = ["left", "center", "right", "justify"];
  let table = null, row = null, cellParas = [];
  let list = null;
  for (const para of paras) {
    const pp = para.pp || {};
    if (pp.inTable || para.end === 0x07) {
      list = null;
      if (!table) { table = util.el("table", { class: "doc-table" }); art.append(table); }
      if (para.end !== 0x07) { cellParas.push(para); continue; }
      if (pp.ttp || (!cellParas.length && !para.runs.length && row && row.children.length)) { row = null; cellParas = []; continue; } // fine riga
      if (!row) { row = util.el("tr"); table.append(row); }
      const td = util.el("td", { style: { border: "1px solid var(--border)", padding: "4px 8px", verticalAlign: "top" } });
      for (const cp of [...cellParas, para]) td.append(fill(util.el("p", { style: { margin: "0" } }), cp));
      row.append(td);
      cellParas = [];
      continue;
    }
    table = null; row = null;
    const text = para.runs.map(r => r.t || "").join("").trim();
    const h = pp.istd >= 1 && pp.istd <= 9 && text ? Math.min(6, pp.istd) : 0;
    if (pp.list && text && !h) {
      if (!list) { list = util.el("ul"); art.append(list); }
      list.append(fill(util.el("li"), para));
      continue;
    }
    list = null;
    const el = fill(util.el(h ? "h" + h : "p"), para);
    if (pp.jc && JC[pp.jc]) el.style.textAlign = JC[pp.jc];
    art.append(el);
  }
  // immagini avanzate (piu' immagini che ancoraggi): in fondo al documento
  if (extra.length) {
    art.append(util.el("h3", { class: "muted", text: `More images from the document (${extra.length})` }));
    for (const src of extra) art.append(util.el("p", null, util.el("img", { src, alt: "" })));
  }
  root.append(art);
  util.notice("Word 97-2003: text, character styles, headings, tables and images; the layout isn't exact");
}
const W1252 = { 0x80: 0x20AC, 0x82: 0x201A, 0x83: 0x0192, 0x84: 0x201E, 0x85: 0x2026, 0x86: 0x2020, 0x87: 0x2021, 0x88: 0x02C6, 0x89: 0x2030, 0x8A: 0x0160, 0x8B: 0x2039, 0x8C: 0x0152, 0x8E: 0x017D,
  0x91: 0x2018, 0x92: 0x2019, 0x93: 0x201C, 0x94: 0x201D, 0x95: 0x2022, 0x96: 0x2013, 0x97: 0x2014, 0x98: 0x02DC, 0x99: 0x2122, 0x9A: 0x0161, 0x9B: 0x203A, 0x9C: 0x0153, 0x9E: 0x017E, 0x9F: 0x0178 };
const cp1252 = c => W1252[c] || c;
/** Prima immagine PNG/JPEG in [from, to) non ancora usata -> URL blob (o null). "key" = da dove parte (per non ripeterla). */
function scanImage(b, from, to, used, key) {
  for (let i = from; i + 8 < to; i++) {
    const png = b[i] === 0x89 && b[i + 1] === 0x50 && b[i + 2] === 0x4E && b[i + 3] === 0x47 && b[i + 4] === 0x0D;
    const jpg = b[i] === 0xFF && b[i + 1] === 0xD8 && b[i + 2] === 0xFF && (b[i + 3] & 0xF0) === 0xE0 || b[i] === 0xFF && b[i + 1] === 0xD8 && b[i + 2] === 0xFF && b[i + 3] === 0xDB;
    if (!png && !jpg) continue;
    const id = (b.byteOffset || 0) + ":" + i + ":" + b.length;
    if (used.has(id)) { continue; }
    let end = to;
    if (png) { for (let k = i + 8; k + 8 <= to; k++) if (b[k] === 0x49 && b[k + 1] === 0x45 && b[k + 2] === 0x4E && b[k + 3] === 0x44) { end = k + 8; break; } }
    else { for (let k = to - 2; k > i; k--) if (b[k] === 0xFF && b[k + 1] === 0xD9) { end = k + 2; break; } }
    used.add(id);
    if (end - i < 64) continue;
    return URL.createObjectURL(new Blob([b.subarray(i, end)], { type: png ? "image/png" : "image/jpeg" }));
  }
  return null;
}

const clean = s => s.replace(/\x0B/g, "\n").replace(/\x0C/g, "\n").replace(/[\x00-\x08\x0E-\x1F]/g, "").replace(/ /g, " ");
const u8 = c => c instanceof Uint8Array ? c : new Uint8Array(c);
