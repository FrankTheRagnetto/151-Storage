// Ebook che non sono EPUB: Mobipocket / Kindle (.mobi, .azw, .azw3/KF8, .prc) e FictionBook (.fb2).
// MOBI: database Palm; i record di testo sono compressi PalmDOC (LZ77) o HUFF/CDIC, il testo e' HTML
// "Mobipocket" (mbp:pagebreak, <img recindex>, filepos). KF8 (azw3): stesso contenitore, HTML moderno a
// pezzi, immagini "kindle:embed:XXXX". I libri con DRM di Amazon NON si aprono (e si dice).
// FB2: XML con <section>, <title>, <p>, <emphasis>, immagini base64 in <binary>.
// Lettura a capitoli come per l'EPUB: HTML sanificato (DOMPurify) in uno Shadow DOM, indice e avanti/indietro.
export async function render(ctx) {
  await ctx.util.need("purify");
  const book = ctx.fmt === "fb2" ? fb2(ctx) : mobi(ctx);
  ctx.util.A.onFormat((ctx.fmt === "fb2" ? "FictionBook" : book.kf8 ? "Kindle KF8 (AZW3)" : "Mobipocket") + (book.title ? " · " + book.title.slice(0, 50) : ""));
  showBook(ctx, book);
}

// ------------------------------------------------------------------ lettore (condiviso)
export function showBook({ root, util }, book) {
  const { chapters } = book;
  if (!chapters.length) throw Object.assign(new Error("There's no readable text in this book."), { user: true });
  const bar = util.el("div", { class: "book-bar" });
  const sel = util.el("select", { "aria-label": "Contents" });
  chapters.forEach((c, i) => sel.append(util.el("option", { value: String(i), text: c.label || `Part ${i + 1}` })));
  bar.append(sel);
  const head = util.el("div", { class: "book-title", text: [book.title, book.author].filter(Boolean).join(" · ") });
  const host = util.el("div", { class: "shadow-host" });
  const shadow = host.attachShadow({ mode: "open" });
  const prev = util.el("button", { class: "btn", type: "button", text: "‹ Previous" });
  const next = util.el("button", { class: "btn", type: "button", text: "Next ›" });
  root.append(bar, head, host, util.el("div", { class: "book-nav" }, prev, next));
  let cur = 0;
  const show = (i, anchor) => {
    if (i < 0 || i >= chapters.length) return;
    cur = i;
    const clean = util.sanitize(chapters[i].html, {
      FORBID_TAGS: ["style", "link", "form", "input", "button", "iframe", "object", "embed", "script"], ADD_ATTR: ["data-go"],
      ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|blob|data):|#|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i,
    }, src => /^(blob|data):/i.test(src) ? src : (book.image ? book.image(src) : null));
    const style = document.createElement("style");
    style.textContent = `:host{display:block}
.bk{font-size:var(--fs);line-height:1.6;max-width:44em;margin:0 auto;overflow-wrap:break-word;color:var(--fg)}
.bk img,.bk svg{max-width:100%;height:auto}.bk a{color:var(--link)}.bk h1,.bk h2,.bk h3{line-height:1.25}
.bk .epigraph,.bk blockquote{margin:1em 0 1em 1.5em;font-style:italic;color:var(--muted)}.bk .poem{margin:1em 0 1em 1.5em}
.bk table{display:block;overflow-x:auto;max-width:100%}.bk p{margin:0 0 .6em}.bk .center{text-align:center}`;
    const wrap = document.createElement("div"); wrap.className = "bk"; wrap.innerHTML = clean;
    shadow.replaceChildren(style, wrap);
    sel.value = String(i);
    prev.disabled = i === 0; next.disabled = i === chapters.length - 1;
    const t = anchor && (shadow.getElementById(anchor) || shadow.querySelector(`[name="${CSS.escape(anchor)}"]`));
    if (t) t.scrollIntoView(); else scrollTo(0, 0);
  };
  shadow.addEventListener("click", e => {
    const a = e.composedPath().find(n => n.tagName === "A");
    if (!a) return;
    const go = a.getAttribute("data-go") || (a.getAttribute("href") || "").replace(/^#/, "");
    if (!go || /^[a-z]+:/i.test(a.getAttribute("href") || "")) return;
    e.preventDefault(); e.stopPropagation();
    const i = book.find ? book.find(go) : chapters.findIndex(c => c.ids && c.ids.has(go));
    if (i >= 0) show(i, go);
  });
  sel.addEventListener("change", () => show(Number(sel.value)));
  prev.addEventListener("click", () => show(cur - 1));
  next.addEventListener("click", () => show(cur + 1));
  show(0);
}

// ------------------------------------------------------------------ FB2
function fb2({ bytes, util }) {
  let t = util.decodeText(bytes);
  const enc = /<\?xml[^>]*encoding=["']([\w-]+)/i.exec(t.slice(0, 200));
  if (enc && !/utf-?8/i.test(enc[1])) try { t = new TextDecoder(enc[1]).decode(bytes); } catch {}
  const d = new DOMParser().parseFromString(t, "application/xml");
  if (d.getElementsByTagName("parsererror").length) throw Object.assign(new Error("Invalid FB2 (broken XML)."), { user: true });
  const q = (el, n) => el ? [...el.getElementsByTagNameNS("*", n)] : [];
  const bin = new Map();
  for (const b of q(d, "binary")) {
    try {
      const raw = atob(b.textContent.replace(/\s+/g, ""));
      bin.set(b.getAttribute("id"), URL.createObjectURL(new Blob([Uint8Array.from(raw, c => c.charCodeAt(0))], { type: b.getAttribute("content-type") || "image/jpeg" })));
    } catch {}
  }
  const info = q(d, "title-info")[0];
  const title = q(info, "book-title")[0]?.textContent.trim() || "";
  const au = q(info, "author")[0];
  const author = au ? ["first-name", "middle-name", "last-name"].map(n => q(au, n)[0]?.textContent.trim()).filter(Boolean).join(" ") || q(au, "nickname")[0]?.textContent : "";
  const esc = util.esc;
  const href = el => el.getAttributeNS("http://www.w3.org/1999/xlink", "href") || el.getAttribute("l:href") || el.getAttribute("xlink:href") || el.getAttribute("href") || "";
  const conv = (el, depth) => {
    let out = "";
    for (const n of el.childNodes) {
      if (n.nodeType === 3) { out += esc(n.nodeValue); continue; }
      if (n.nodeType !== 1) continue;
      const id = n.getAttribute("id") ? ` id="${esc(n.getAttribute("id"))}"` : "";
      const inner = () => conv(n, depth);
      switch (n.localName) {
        case "p": out += `<p${id}>${inner()}</p>`; break;
        case "title": out += `<h${Math.min(6, depth + 1)}${id}>${[...n.children].map(c => conv(c, depth)).join("<br>") || inner()}</h${Math.min(6, depth + 1)}>`; break;
        case "subtitle": out += `<h${Math.min(6, depth + 2)} class="center"${id}>${inner()}</h${Math.min(6, depth + 2)}>`; break;
        case "emphasis": out += `<em>${inner()}</em>`; break;
        case "strong": out += `<strong>${inner()}</strong>`; break;
        case "strikethrough": out += `<s>${inner()}</s>`; break;
        case "sub": case "sup": case "code": out += `<${n.localName}>${inner()}</${n.localName}>`; break;
        case "empty-line": out += "<br>"; break;
        case "epigraph": case "cite": out += `<blockquote class="epigraph"${id}>${inner()}</blockquote>`; break;
        case "text-author": out += `<p class="center"><em>${inner()}</em></p>`; break;
        case "poem": out += `<div class="poem"${id}>${inner()}</div>`; break;
        case "stanza": out += `<p>${[...n.children].map(c => conv(c, depth)).join("<br>")}</p>`; break;
        case "v": out += inner(); break;
        case "a": { const h = href(n); out += h.startsWith("#") ? `<a href="#" data-go="${esc(h.slice(1))}">${inner()}</a>` : `<a href="${esc(h)}">${inner()}</a>`; break; }
        case "image": { const h = href(n).replace(/^#/, ""); if (bin.has(h)) out += `<p class="center"><img src="${bin.get(h)}" alt=""></p>`; break; }
        case "table": out += `<table>${inner()}</table>`; break;
        case "tr": out += `<tr>${inner()}</tr>`; break;
        case "td": case "th": out += `<${n.localName}>${inner()}</${n.localName}>`; break;
        case "section": out += `<div${id}>${conv(n, depth + 1)}</div>`; break;
        default: out += inner();
      }
    }
    return out;
  };
  const chapters = [];
  const bodies = q(d, "body");
  const cover = q(q(info, "coverpage")[0], "image")[0];
  bodies.forEach((body, bi) => {
    const notes = body.getAttribute("name") === "notes" || bi > 0;
    const secs = [...body.children].filter(c => c.localName === "section");
    const pre = [...body.children].filter(c => c.localName !== "section");
    const preHtml = conv({ childNodes: pre }, 0);
    if (bi === 0 && (preHtml.trim() || cover)) chapters.push({ label: title || "Start", html: (cover && bin.has(href(cover).replace(/^#/, "")) ? `<p class="center"><img src="${bin.get(href(cover).replace(/^#/, ""))}" alt=""></p>` : "") + preHtml });
    for (const s of secs.length ? secs : [body]) {
      const lbl = q(s, "title")[0]?.textContent.replace(/\s+/g, " ").trim().slice(0, 80);
      const ids = new Set([...s.querySelectorAll("[id]")].map(x => x.getAttribute("id")));
      if (s.getAttribute("id")) ids.add(s.getAttribute("id"));
      chapters.push({ label: (notes ? "Notes: " : "") + (lbl || `Part ${chapters.length + 1}`), html: conv(s.localName === "section" ? { childNodes: [s] } : s, 0), ids });
    }
  });
  // un'apertura con solo titolo/copertina non merita una pagina a se': va in testa al primo capitolo
  if (chapters.length > 1 && chapters[0].html.replace(/<[^>]+>/g, "").trim().length < 200) {
    const first = chapters.shift();
    chapters[0].html = first.html + chapters[0].html;
  }
  return { title, author, chapters };
}

// ------------------------------------------------------------------ MOBI / KF8
function mobi({ bytes, util }) {
  const b = bytes, dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const n = dv.getUint16(76);
  const recOff = i => dv.getUint32(78 + i * 8);
  const rec = i => b.subarray(recOff(i), i + 1 < n ? recOff(i + 1) : b.length);
  const r0 = rec(0), d0 = new DataView(r0.buffer, r0.byteOffset, r0.byteLength);
  const compression = d0.getUint16(0), textRecs = d0.getUint16(8), encryption = d0.getUint16(12);
  if (encryption !== 0) throw Object.assign(new Error("DRM-protected book (Kindle): it can only be opened in the Kindle app."), { user: true });
  const isMobi = String.fromCharCode(...r0.subarray(16, 20)) === "MOBI";
  const hlen = isMobi ? d0.getUint32(20) : 0;
  const encoding = isMobi && d0.getUint32(28) === 65001 ? "utf-8" : "windows-1252";
  const version = isMobi ? d0.getUint32(36) : 0;
  let firstImg = isMobi ? d0.getUint32(108) : 0xFFFFFFFF;
  const extraFlags = isMobi && hlen >= 0xE4 ? d0.getUint16(0xF2) : 0;
  // titolo e autore: EXTH (100 autore, 503 titolo), altrimenti "full name"
  let title = "", author = "", kf8Base = null;
  const dec = new TextDecoder(encoding);
  if (isMobi) {
    const fno = d0.getUint32(84), fnl = d0.getUint32(88);
    title = dec.decode(r0.subarray(fno, fno + fnl));
    if (d0.getUint32(128) & 0x40) {
      let p = 16 + hlen;
      if (String.fromCharCode(...r0.subarray(p, p + 4)) === "EXTH") {
        const cnt = d0.getUint32(p + 8); p += 12;
        for (let i = 0; i < cnt && p + 8 <= r0.length; i++) {
          const t = d0.getUint32(p), l = d0.getUint32(p + 4);
          const data = r0.subarray(p + 8, p + l);
          if (t === 100 && !author) author = dec.decode(data);
          if (t === 503) title = dec.decode(data);
          if (t === 121) kf8Base = new DataView(data.buffer, data.byteOffset, 4).getUint32(0);
          if (t === 401 || t === 208) { /* DRM "clipping limit" / watermark: non conta */ }
          p += l;
        }
      }
    }
  }
  // file "combinato" (MOBI 6 + KF8): leggo la parte vecchia, che e' HTML pieno e piu' semplice
  let base = 0, kf8 = version >= 8;
  if (kf8Base != null && kf8Base !== 0xFFFFFFFF && version < 8) kf8 = false;
  // testo
  const trailing = data => { // i record di testo possono avere byte "extra" in coda (flag del record 0)
    let size = data.length;
    for (let bit = 15; bit > 0; bit--) {
      if (extraFlags & (1 << bit)) {
        let v = 0, shift = 0, k = size - 1;
        for (let j = 0; j < 4 && k >= 0; j++, k--) { const x = data[k]; v |= (x & 0x7F) << shift; shift += 7; if (x & 0x80) break; }
        size -= v;
      }
    }
    if (extraFlags & 1) size -= (data[size - 1] & 3) + 1;
    return data.subarray(0, Math.max(0, size));
  };
  let huff = null;
  if (compression === 17480) huff = huffInit(b, dv, d0, rec);
  const parts = [];
  for (let i = 1; i <= textRecs && base + i < n; i++) {
    const raw = trailing(rec(base + i));
    parts.push(compression === 2 ? palmdoc(raw) : compression === 17480 ? huff(raw) : raw);
  }
  const all = new Uint8Array(parts.reduce((s, x) => s + x.length, 0));
  { let o = 0; for (const x of parts) { all.set(x, o); o += x.length; } }
  let html = dec.decode(all);
  // immagini: record da firstImg in poi (recindex 1-based per MOBI 6; kindle:embed base32 per KF8)
  const imgCache = new Map();
  const imgUrl = idx => {
    const i = firstImg + idx;
    if (!imgCache.has(i)) {
      let u = null;
      if (i > 0 && i < n) {
        const r = rec(i);
        const sig = String.fromCharCode(...r.subarray(0, 4));
        const mime = r[0] === 0xFF && r[1] === 0xD8 ? "image/jpeg" : sig === "\x89PNG" ? "image/png" : sig.startsWith("GIF") ? "image/gif" : sig.startsWith("BM") ? "image/bmp" : null;
        if (mime) u = URL.createObjectURL(new Blob([r], { type: mime }));
      }
      imgCache.set(i, u);
    }
    return imgCache.get(i);
  };
  const image = src => {
    let m = /^kindle:embed:([0-9A-V]+)/i.exec(src);
    if (m) return imgUrl(parseInt(m[1], 32) - 1);
    m = /^recindex:(\d+)$/.exec(src);
    if (m) return imgUrl(+m[1] - 1);
    return null;
  };
  // MOBI 6: <img recindex="00001"> -> src segnaposto risolto da image(); filepos -> ancore
  html = html.replace(/<img([^>]*?)\s(?:hi)?recindex=["']?(\d+)["']?([^>]*)>/gi, '<img$1 src="recindex:$2"$3>');
  const anchors = new Set();
  html.replace(/filepos=["']?(\d+)/gi, (_, p) => anchors.add(+p));
  if (anchors.size && !kf8) {
    // inserisco <a id="fpNNN"> alla posizione in byte: lavoro sui byte per non sbagliare con l'UTF-8
    const pos = [...anchors].sort((x, y) => y - x);
    let bytesAll = all;
    const chunks = []; let end = bytesAll.length;
    for (const p of pos) { if (p >= end) continue; chunks.push(bytesAll.subarray(p, end), new TextEncoder().encode(`<a id="fp${p}"></a>`)); end = p; }
    chunks.push(bytesAll.subarray(0, end));
    html = chunks.reverse().map(c => dec.decode(c)).join("")
      .replace(/<img([^>]*?)\s(?:hi)?recindex=["']?(\d+)["']?([^>]*)>/gi, '<img$1 src="recindex:$2"$3>');
    html = html.replace(/<a([^>]*?)\sfilepos=["']?(\d+)["']?([^>]*)>/gi, (m0, a, p, z) => `<a${a} href="#" data-go="fp${+p}"${z}>`);
  }
  // capitoli: mbp:pagebreak (MOBI 6) o i confini tra "skeleton" (KF8: ogni <html> nuovo)
  html = html.replace(/<\/?(?:mbp:)?(?:section|frameset|nu)\b[^>]*>/gi, "");
  let pieces = kf8 ? html.split(/(?=<\?xml|<html[\s>])/i) : html.split(/<mbp:pagebreak\s*\/?>/i);
  pieces = pieces.map(p => p.replace(/<\?xml[^>]*>|<!DOCTYPE[^>]*>/gi, "").replace(/<head>[\s\S]*?<\/head>/gi, "")
    .replace(/<\/?(html|body)\b[^>]*>/gi, "").replace(/<img[^>]*kindle:flow:[^>]*>/gi, "")).filter(p => p.replace(/<[^>]+>/g, "").trim() || /<img/i.test(p));
  // pezzi troppo corti (copertina, frontespizio) si accorpano; troppo lunghi si lasciano
  const chapters = [];
  for (const p of pieces) {
    const txt = p.replace(/<[^>]+>/g, " ").replace(/&[a-z#0-9]+;/gi, " ").replace(/\s+/g, " ").trim();
    const hm = /<h[1-3][^>]*>([\s\S]*?)<\/h[1-3]>/i.exec(p);
    const label = (hm ? hm[1].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim() : txt.slice(0, 50)) || `Part ${chapters.length + 1}`;
    const ids = new Set([...p.matchAll(/\sid=["']([^"']+)/g)].map(m => m[1]));
    const prev = chapters[chapters.length - 1];
    if (prev && (prev.txtLen < 200 || prev.html.length < 1500 && txt.length < 400)) {
      const c = prev; if (c.txtLen < 200 && txt.length > c.txtLen) c.label = label.slice(0, 80); c.html += p; c.txtLen += txt.length; ids.forEach(x => c.ids.add(x));
    } else chapters.push({ label: label.slice(0, 80), html: p, ids, txtLen: txt.length });
  }
  return { title, author, chapters, image, kf8 };
}

function palmdoc(src) {
  const out = [];
  for (let i = 0; i < src.length;) {
    const c = src[i++];
    if (c >= 1 && c <= 8) { for (let k = 0; k < c && i < src.length; k++) out.push(src[i++]); }
    else if (c < 128) out.push(c);
    else if (c >= 192) out.push(32, c ^ 0x80);
    else {
      const x = (c << 8) | src[i++];
      const dist = (x >> 3) & 0x7FF, len = (x & 7) + 3;
      for (let k = 0; k < len; k++) out.push(out[out.length - dist]);
    }
  }
  return Uint8Array.from(out);
}

// HUFF/CDIC (Mobipocket "huffdic"): tabelle nei record indicati dal record 0 (come KindleUnpack/calibre)
function huffInit(b, dv, d0, rec) {
  const huffRec = d0.getUint32(112), huffCount = d0.getUint32(116);
  const h = rec(huffRec), hd = new DataView(h.buffer, h.byteOffset, h.byteLength);
  const o1 = hd.getUint32(8), o2 = hd.getUint32(12);
  const dict1 = [];
  for (let i = 0; i < 256; i++) {
    const v = hd.getUint32(o1 + i * 4);
    const codelen = v & 0x1F, term = v & 0x80;
    let maxcode = BigInt(v >>> 8);
    if (term) maxcode = ((maxcode + 1n) << BigInt(32 - codelen)) - 1n;
    dict1.push({ codelen, term, maxcode });
  }
  const minc = [0n], maxc = [0n];
  for (let L = 1; L <= 32; L++) {
    minc.push(BigInt(hd.getUint32(o2 + (L - 1) * 8)) << BigInt(32 - L));
    maxc.push(((BigInt(hd.getUint32(o2 + (L - 1) * 8 + 4)) + 1n) << BigInt(32 - L)) - 1n);
  }
  const dict = [];
  for (let k = 1; k < huffCount; k++) {
    const c = rec(huffRec + k), cd = new DataView(c.buffer, c.byteOffset, c.byteLength);
    const hl = cd.getUint32(4), cnt = cd.getUint32(8), bits = cd.getUint32(12);
    const nn = Math.min(2 ** bits, cnt - dict.length);
    for (let i = 0; i < nn; i++) {
      const off = cd.getUint16(hl + i * 2), blen = cd.getUint16(hl + off);
      dict.push({ data: c.subarray(hl + off + 2, hl + off + 2 + (blen & 0x7FFF)), flag: blen & 0x8000 });
    }
  }
  const M32 = 0xFFFFFFFFn;
  const unpack = (data, depth = 0) => {
    const d = new Uint8Array(data.length + 8); d.set(data);
    const q64 = p => { let v = 0n; for (let i = 0; i < 8; i++) v = (v << 8n) | BigInt(d[p + i] || 0); return v; };
    let bitsleft = data.length * 8, pos = 0, x = q64(0), n = 32;
    const out = [];
    for (;;) {
      if (n <= 0) { pos += 4; x = q64(pos); n += 32; }
      const code = (x >> BigInt(n)) & M32;
      let { codelen, term, maxcode } = dict1[Number(code >> 24n)];
      if (!term) { while (codelen < 32 && code < minc[codelen]) codelen++; maxcode = maxc[codelen]; }
      n -= codelen; bitsleft -= codelen;
      if (bitsleft < 0 || !codelen) break;
      const r = Number((maxcode - code) >> BigInt(32 - codelen));
      const e = dict[r];
      if (!e) break;
      if (!e.flag) { if (depth > 20) break; e.data = unpack(e.data, depth + 1); e.flag = 1; }
      for (const c of e.data) out.push(c);
    }
    return Uint8Array.from(out);
  };
  return unpack;
}
