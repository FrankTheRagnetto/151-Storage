// "Documenti-immagine" che non sono foto: TIFF (anche multipagina: scansioni, fax G3/G4) con UTIF.js
// (MIT, di Photopea); PSD (Photoshop: l'immagine "unita" salvata nel file, o la miniatura, piu' i nomi dei
// livelli) ed EPS/PostScript (l'anteprima incorporata TIFF o EPSI; il PostScript vero servirebbe
// Ghostscript, che e' AGPL). Pagine su canvas, decodificate quando stanno per entrare nello schermo.
const MAX_PX = 40e6;

export async function render(ctx) {
  const { fmt } = ctx;
  if (fmt === "psd") return psd(ctx);
  if (fmt === "eps") return eps(ctx);
  return tiff(ctx, ctx.bytes);
}

function pageMode(util) { util.mode("page", innerWidth || 412); document.body.classList.add("page-mode"); }
function canvasOf(w, h, rgba) {
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  const g = c.getContext("2d");
  const id = g.createImageData(w, h);
  id.data.set(rgba.subarray(0, w * h * 4));
  g.putImageData(id, 0, 0);
  c.className = "raster-page";
  return c;
}

// ------------------------------------------------------------------ TIFF
async function tiff({ root, util }, bytes, { label, silent } = {}) {
  await util.need("utif");
  self.pako = self.pako || { inflateRaw: d => fflate.inflateSync(d) }; // UTIF usa pako solo per il deflate
  const buf = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  let ifds = UTIF.decode(buf);
  // via le miniature (NewSubfileType = 1) quando ci sono anche le pagine vere
  const pages = ifds.filter(i => !(i.t254 && (i.t254[0] & 1)));
  if (pages.length) ifds = pages;
  if (!ifds.length) throw Object.assign(new Error("TIFF without images."), { user: true });
  if (!silent) util.A.onFormat(`TIFF · ${ifds.length} ${ifds.length === 1 ? "page" : "pages"}`);
  pageMode(util);
  const list = util.el("div", { class: "raster" });
  if (label) list.append(util.el("div", { class: "pdfin-label", text: label }));
  root.append(list);
  const io = new IntersectionObserver(es => {
    for (const e of es) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      const k = Number(e.target.dataset.i), ifd = ifds[k];
      setTimeout(() => {
        try {
          if (ifd.width * ifd.height > MAX_PX) throw new Error(`image too large (${ifd.width}×${ifd.height})`);
          UTIF.decodeImage(buf, ifd, ifds);
          const rgba = UTIF.toRGBA8(ifd);
          e.target.replaceChildren(canvasOf(ifd.width, ifd.height, rgba));
          e.target.style.aspectRatio = "";
        } catch (err) {
          e.target.replaceChildren(util.el("div", { class: "comic-err", text: `Page ${k + 1}: ${err.message || err}` }));
        }
      }, 0);
    }
  }, { rootMargin: "120% 0px" });
  ifds.forEach((ifd, i) => {
    const w = (ifd.t256 || [1])[0], h = (ifd.t257 || [1])[0];
    const box = util.el("div", { class: "raster-box", "data-i": String(i), style: { aspectRatio: `${w} / ${h}` } }, util.el("div", { class: "comic-num", text: String(i + 1) }));
    list.append(box);
    if (ifds.length > 1) list.append(util.el("div", { class: "slide-num", text: `${i + 1} / ${ifds.length}` }));
    io.observe(box);
  });
}

// ------------------------------------------------------------------ PSD / PSB
function psd({ root, bytes: b, util }) {
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const ver = dv.getUint16(4), psb = ver === 2;
  const ch = dv.getUint16(12), H = dv.getUint32(14), W = dv.getUint32(18), depth = dv.getUint16(22), mode = dv.getUint16(24);
  let p = 26;
  const cmLen = dv.getUint32(p); const cmData = b.subarray(p + 4, p + 4 + cmLen); p += 4 + cmLen;
  const resLen = dv.getUint32(p); let q = p + 4; const resEnd = q + resLen; p = resEnd;
  let thumb = null;
  while (q + 12 <= resEnd) {
    if (String.fromCharCode(b[q], b[q + 1], b[q + 2], b[q + 3]) !== "8BIM") break;
    const id = dv.getUint16(q + 4);
    let nl = b[q + 6]; let r = q + 7 + nl; if ((nl + 1) % 2) r++;
    const size = dv.getUint32(r); const data = b.subarray(r + 4, r + 4 + size);
    if ((id === 1036 || id === 1033) && size > 28) thumb = data.subarray(28);
    q = r + 4 + size + (size % 2);
  }
  // livelli: solo i nomi
  const layers = [];
  const lmLen = psb ? Number(dv.getBigUint64(p)) : dv.getUint32(p);
  const lmStart = p + (psb ? 8 : 4);
  try {
    let r = lmStart;
    const liLen = psb ? Number(dv.getBigUint64(r)) : dv.getUint32(r); r += psb ? 8 : 4;
    if (liLen > 0) {
      const count = Math.abs(dv.getInt16(r)); r += 2;
      for (let i = 0; i < count && i < 2000; i++) {
        r += 16; const nch = dv.getUint16(r); r += 2 + nch * (psb ? 10 : 6);
        r += 12;
        const extra = dv.getUint32(r); r += 4; const exEnd = r + extra;
        const mask = dv.getUint32(r); r += 4 + mask;
        const br = dv.getUint32(r); r += 4 + br;
        const nl = b[r]; const nm = new TextDecoder("windows-1252").decode(b.subarray(r + 1, r + 1 + nl));
        layers.push(nm);
        r = exEnd;
      }
    }
  } catch {}
  p = lmStart + lmLen;
  util.A.onFormat(`Photoshop · ${W}×${H}` + (layers.length ? ` · ${layers.length} layer${layers.length === 1 ? "" : "s"}` : ""));
  pageMode(util);
  const box = util.el("div", { class: "raster" });
  root.append(box);
  let drawn = false;
  try {
    if (W * H > MAX_PX) throw new Error("too large");
    const comp = dv.getUint16(p); p += 2;
    const bpc = depth === 16 ? 2 : depth === 32 ? 4 : 1;
    const planes = [];
    const n = Math.min(ch, mode === 4 ? 5 : mode === 3 ? 4 : 2);
    if (comp === 0) {
      for (let c = 0; c < n; c++) planes.push(b.subarray(p + c * W * H * bpc, p + (c + 1) * W * H * bpc));
    } else if (comp === 1) {
      const cntSize = psb ? 4 : 2;
      const counts = [];
      for (let i = 0; i < ch * H; i++) counts.push(psb ? dv.getUint32(p + i * 4) : dv.getUint16(p + i * 2));
      let d = p + ch * H * cntSize;
      for (let c = 0; c < ch; c++) {
        const out = c < n ? new Uint8Array(W * H * bpc) : null;
        for (let y = 0; y < H; y++) {
          const len = counts[c * H + y];
          if (out) packbits(b, d, d + len, out, y * W * bpc, W * bpc);
          d += len;
        }
        if (out) planes.push(out);
      }
    } else throw new Error("ZIP compression not supported");
    if (depth === 1 || mode === 0) throw new Error("1-bit bitmap");
    const px = new Uint8ClampedArray(W * H * 4);
    const v = (pl, i) => bpc === 1 ? pl[i] : bpc === 2 ? pl[i * 2] : Math.round(Math.min(1, new DataView(pl.buffer, pl.byteOffset).getFloat32(i * 4)) * 255);
    for (let i = 0; i < W * H; i++) {
      let r, g, bl, a = 255;
      if (mode === 3) { r = v(planes[0], i); g = v(planes[1], i); bl = v(planes[2], i); if (planes[3] && ch > 3) a = v(planes[3], i); }
      else if (mode === 4) { const C = v(planes[0], i), M = v(planes[1], i), Y = v(planes[2], i), K = v(planes[3], i); r = C * K / 255; g = M * K / 255; bl = Y * K / 255; }
      else if (mode === 2) { const k = v(planes[0], i); r = cmData[k]; g = cmData[256 + k]; bl = cmData[512 + k]; }
      else { r = g = bl = v(planes[0], i); if (planes[1] && ch > 1 && mode === 1) a = v(planes[1], i); }
      px[i * 4] = r; px[i * 4 + 1] = g; px[i * 4 + 2] = bl; px[i * 4 + 3] = a;
    }
    const c = canvasOf(W, H, px);
    c.classList.add("checker");
    box.append(c);
    drawn = true;
  } catch (e) {
    console.warn("PSD: merged image not readable", e);
  }
  if (!drawn && thumb) {
    box.append(util.el("img", { class: "zip-img", alt: "Thumbnail", src: URL.createObjectURL(new Blob([thumb], { type: "image/jpeg" })) }));
    util.notice("Showing the thumbnail saved in the file (the full image can't be read here)");
    drawn = true;
  }
  if (!drawn) throw Object.assign(new Error("This PSD has no readable merged image (saved without “Maximize Compatibility”?)."), { user: true });
  const MODES = { 1: "grayscale", 2: "indexed color", 3: "RGB", 4: "CMYK", 7: "multichannel", 8: "duotone", 9: "Lab" };
  const info = util.el("div", { class: "card raster-info" },
    util.el("div", { class: "kv" }, util.el("b", { text: "Dimensions" }), util.el("span", { text: `${W} × ${H} px · ${MODES[mode] || "mode " + mode} · ${depth} bit` })));
  if (layers.length) info.append(util.el("div", { class: "kv" }, util.el("b", { text: `Layers (${layers.length})` }), util.el("span", { text: layers.slice().reverse().join("\n") })));
  root.append(info);
  if (mode === 4) util.notice("CMYK converted to RGB in a simple way: colors may look a little different");
}
function packbits(src, p, end, out, o, max) {
  const lim = o + max;
  while (p < end && o < lim) {
    const n = src[p++];
    if (n < 128) { const l = n + 1; out.set(src.subarray(p, p + l), o); o += l; p += l; }
    else if (n > 128) { const l = 257 - n; out.fill(src[p++], o, Math.min(lim, o + l)); o += l; }
  }
}

// ------------------------------------------------------------------ EPS / PostScript
async function eps(ctx) {
  const { root, bytes: b, util } = ctx;
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  let ps = b, tiffPrev = null;
  if (b[0] === 0xC5 && b[1] === 0xD0 && b[2] === 0xD3 && b[3] === 0xC6) {
    const po = dv.getUint32(4, true), pl = dv.getUint32(8, true), to = dv.getUint32(20, true), tl = dv.getUint32(24, true);
    ps = b.subarray(po, po + pl);
    if (to && tl) tiffPrev = b.subarray(to, to + tl);
  }
  const head = new TextDecoder("latin1").decode(ps.subarray(0, Math.min(ps.length, 256 * 1024)));
  const dsc = k => (new RegExp(`^%%${k}:\\s*(.*)$`, "m").exec(head) || [])[1]?.trim();
  const isAi = /Adobe Illustrator/i.test(dsc("Creator") || "") || /\.ai$/i.test(ctx.name);
  util.A.onFormat((isAi ? "Illustrator (PostScript)" : /EPSF/.test(head.slice(0, 40)) ? "EPS" : "PostScript") + (dsc("Title") ? " · " + dsc("Title").replace(/^\(|\)$/g, "").slice(0, 40) : ""));
  const card = util.el("div", { class: "card raster-info" });
  const kv = (k, v) => v && card.append(util.el("div", { class: "kv" }, util.el("b", { text: k }), util.el("span", { text: v.replace(/^\(|\)$/g, "") })));
  kv("Title", dsc("Title")); kv("Created with", dsc("Creator")); kv("Author", dsc("For")); kv("Date", dsc("CreationDate"));
  const bb = dsc("HiResBoundingBox") || dsc("BoundingBox");
  if (bb) { const [x0, y0, x1, y1] = bb.split(/\s+/).map(Number); if ([x0, y0, x1, y1].every(isFinite)) kv("Size", `${Math.round((x1 - x0) / 72 * 25.4)} × ${Math.round((y1 - y0) / 72 * 25.4)} mm`); }
  kv("Pages", dsc("Pages"));
  let shown = false;
  if (tiffPrev) {
    try { await tiff({ root, util }, tiffPrev, { label: "Embedded preview (low resolution)", silent: true }); shown = true; } catch (e) { console.warn("EPS TIFF preview", e); }
  }
  if (!shown) {
    const m = /%%BeginPreview:\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*\r?\n([\s\S]*?)%%EndPreview/.exec(head);
    if (m) {
      const [w, h, depth] = [+m[1], +m[2], +m[3]];
      const hexs = m[5].replace(/%/g, "").replace(/[^0-9a-fA-F]/g, "");
      const bytesPrev = Uint8Array.from(hexs.match(/../g) || [], x => parseInt(x, 16));
      const rowBytes = Math.ceil(w * depth / 8);
      const px = new Uint8ClampedArray(w * h * 4);
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const bit = x * depth, byte = bytesPrev[y * rowBytes + (bit >> 3)] || 0;
        const val = (byte >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
        const g = 255 - Math.round(val * 255 / ((1 << depth) - 1)); // EPSI: 1 = nero
        px.set([g, g, g, 255], (y * w + x) * 4);
      }
      pageMode(util);
      const box = util.el("div", { class: "raster" }, util.el("div", { class: "pdfin-label", text: "Embedded preview (EPSI, low resolution)" }), canvasOf(w, h, px));
      root.append(box);
      shown = true;
    }
  }
  root.append(card);
  if (!shown) {
    root.append(util.el("p", { class: "muted", text: "This PostScript file has no preview. Drawing it would need a PostScript interpreter (Ghostscript), which is AGPL-licensed, so this app doesn't include it. The source is below." }));
    const det = util.el("details", { class: "geo-src" }, util.el("summary", { text: "PostScript source" }), util.el("pre", { class: "nb-text", text: head.slice(0, 200000) }));
    root.append(det);
  }
  util.notice(shown ? "Preview saved in the file: the real (vector) drawing isn't interpreted here" : "No preview in the file");
}
