// Apple Pages / Numbers / Keynote. Sono ZIP (o cartelle-pacchetto zippate) con dentro un'anteprima fatta
// da Apple: preview.jpg (dal 2013) o QuickLook/Preview.pdf e Thumbnail.jpg (iWork '09). Si mostra quella.
// In piu', per Pages e Keynote dal 2013, il TESTO: gli .iwa sono protobuf compressi con Snappy, e il testo
// sta nei messaggi TSWP.StorageArchive (tipo 2001, campo 3). Scritto qui, niente librerie.
import { renderPdf } from "./pdfin.mjs";

export async function render({ root, zip, util, name }) {
  // pacchetto zippato con la cartella dentro ("Presentazione.key/…"): si guarda dentro la cartella
  let z = zip, pre = "";
  const top = Object.keys(z.files).map(n => n.split("/")[0]).filter((v, i, a) => a.indexOf(v) === i);
  if (top.length === 1 && /\.(pages|numbers|key)$/i.test(top[0])) pre = top[0] + "/";
  const f = p => z.file(pre + p);
  const kind = /\.numbers$/i.test(name) || f("Index/Tables/") ? "Numbers" : /\.key$/i.test(name) || Object.keys(z.files).some(n => /Slide-\d+\.iwa$|index\.apxl/.test(n)) ? "Keynote" : "Pages";
  util.A.onFormat(`Apple ${kind}`);
  let iwaZip = z, iwaPre = pre;
  if (f("Index.zip")) { iwaZip = await JSZip.loadAsync(await f("Index.zip").async("uint8array")); iwaPre = ""; }

  let shown = false;
  const pdf = f("QuickLook/Preview.pdf");
  if (pdf) {
    await renderPdf(root, util, await pdf.async("uint8array"), { label: "Preview saved in the document" });
    shown = true;
  } else {
    const img = f("preview.jpg") || f("QuickLook/Thumbnail.jpg") || f("preview-web.jpg");
    if (img) {
      const u = URL.createObjectURL(new Blob([await img.async("uint8array")], { type: "image/jpeg" }));
      root.append(util.el("div", { class: "iwork-prev" },
        util.el("div", { class: "pdfin-label", text: kind === "Keynote" ? "Preview saved in the document (first slide)" : kind === "Numbers" ? "Preview saved in the document (first sheet)" : "Preview saved in the document (first page)" }),
        util.el("img", { class: "zip-img iwork-img", src: u, alt: "Preview" })));
      shown = true;
    }
  }

  // testo (Pages/Keynote 2013+)
  let texts = [];
  try { texts = await iwaTexts(iwaZip, iwaPre, kind); } catch (e) { console.warn("iWork text not extracted", e); }
  if (!texts.length) {
    const xml = f("index.xml") || f("index.apxl");
    if (xml) texts = xmlTexts(await xml.async("string"));
  }
  if (texts.length) {
    const art = util.el("article", { class: "md iwork-text" }, util.el("h2", { text: kind === "Keynote" ? "Slide text" : "Document text" }));
    for (const t of texts) {
      const sec = util.el("div", { class: "iwork-block" });
      for (const para of t.split(/\n/)) sec.append(util.el("p", { text: para.replace(/￼/g, "").trim() || " " }));
      art.append(sec);
    }
    root.append(art);
  }
  // nessuna anteprima: le immagini del documento, se ci sono
  if (!shown) {
    const imgs = Object.keys(z.files).filter(n => n.startsWith(pre + "Data/") && /\.(jpe?g|png|gif)$/i.test(n) && !/\/(mt|st)-|-small-/.test(n)).slice(0, 60);
    if (imgs.length) {
      const g = util.el("div", { class: "iwork-gallery" }, util.el("h2", { text: "Document images" }));
      for (const n of imgs) g.append(util.el("img", { class: "zip-img", alt: n, src: URL.createObjectURL(new Blob([await z.file(n).async("uint8array")], { type: util.mimeOf(n) })) }));
      root.append(g);
    }
  }
  if (!shown && !texts.length) throw Object.assign(new Error(`This ${kind} file has no readable preview.`), { user: true });
  util.notice(shown ? "Preview made by Apple when the file was saved, not the editable document" + (texts.length ? "; full text below" : "")
    : "The file has no preview; text below");
}

// ------------------------------------------------------------------ IWA: Snappy + protobuf
function snappy(src, p, end) {
  let len = 0, shift = 0, b;
  do { b = src[p++]; len += (b & 0x7f) * 2 ** shift; shift += 7; } while (b & 0x80);
  const out = new Uint8Array(len); let o = 0;
  while (p < end && o < len) {
    const tag = src[p++], t = tag & 3;
    if (t === 0) {
      let l = tag >> 2;
      if (l >= 60) { const nb = l - 59; l = 0; for (let k = 0; k < nb; k++) l |= src[p++] << (8 * k); }
      l++;
      out.set(src.subarray(p, p + l), o); o += l; p += l;
    } else {
      let l, off;
      if (t === 1) { l = ((tag >> 2) & 7) + 4; off = ((tag >> 5) << 8) | src[p++]; }
      else if (t === 2) { l = (tag >> 2) + 1; off = src[p] | (src[p + 1] << 8); p += 2; }
      else { l = (tag >> 2) + 1; off = (src[p] | (src[p + 1] << 8) | (src[p + 2] << 16) | (src[p + 3] << 24)) >>> 0; p += 4; }
      if (!off || off > o) throw new Error("snappy: invalid reference");
      for (let k = 0; k < l && o < len; k++) { out[o] = out[o - off]; o++; }
    }
  }
  return out.subarray(0, o);
}
function iwa(b) {
  const parts = []; let p = 0;
  while (p + 4 <= b.length) {
    const n = b[p + 1] | (b[p + 2] << 8) | (b[p + 3] << 16);
    parts.push(b[p] === 0 ? snappy(b, p + 4, p + 4 + n) : b.subarray(p + 4, p + 4 + n));
    p += 4 + n;
  }
  const out = new Uint8Array(parts.reduce((s, x) => s + x.length, 0));
  let o = 0; for (const x of parts) { out.set(x, o); o += x.length; }
  return out;
}
function varint(b, st) { let v = 0, m = 1, x; do { x = b[st.p++]; v += (x & 0x7f) * m; m *= 128; } while (x & 0x80 && st.p < b.length); return v; }
function fields(b, s, e) {
  const st = { p: s }, out = [];
  while (st.p < e) {
    const key = varint(b, st), f = Math.floor(key / 8), w = key & 7;
    if (w === 0) out.push({ f, w, v: varint(b, st) });
    else if (w === 2) { const l = varint(b, st); out.push({ f, w, s: st.p, e: st.p + l }); st.p += l; }
    else if (w === 1) st.p += 8; else if (w === 5) st.p += 4; else break;
  }
  return out;
}
function* messages(d) {
  const st = { p: 0 };
  while (st.p < d.length) {
    const l = varint(d, st); const ai = fields(d, st.p, st.p + l); st.p += l;
    for (const mi of ai.filter(x => x.f === 2)) {
      const mf = fields(d, mi.s, mi.e);
      const type = mf.find(x => x.f === 1)?.v, len = mf.find(x => x.f === 3)?.v || 0;
      yield { type, s: st.p, e: st.p + len };
      st.p += len;
    }
  }
}
async function iwaTexts(z, pre, kind) {
  const dec = new TextDecoder();
  const files = Object.keys(z.files).filter(n => n.startsWith(pre + "Index/") && (kind === "Keynote" ? /\/Slide-\d+\.iwa$/.test(n) : /\/Document\.iwa$/.test(n)))
    .sort((a, b) => a.localeCompare(b, "en", { numeric: true }));
  if (kind === "Numbers") return [];
  const out = [];
  for (const n of files) {
    const d = iwa(await z.file(n).async("uint8array"));
    const here = [];
    for (const m of messages(d)) {
      if (m.type !== 2001) continue;
      const t = fields(d, m.s, m.e).filter(x => x.f === 3 && x.w === 2).map(x => dec.decode(d.subarray(x.s, x.e))).join("");
      if (t.replace(/[￼\s]/g, "")) here.push(t);
    }
    if (kind === "Pages") here.sort((a, b) => b.length - a.length); // il corpo prima delle caselle di testo
    out.push(...(kind === "Keynote" && here.length ? [here.join("\n")] : here));
  }
  return out;
}
/** iWork '09: il testo sta nell'XML (sf:p / sf:span) */
function xmlTexts(x) {
  const d = new DOMParser().parseFromString(x, "application/xml");
  const ps = [...d.getElementsByTagNameNS("*", "p")].map(p => p.textContent.trim()).filter(Boolean);
  return ps.length ? [ps.join("\n")] : [];
}
