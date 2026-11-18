// XPS / OpenXPS: pagine fisse (FixedPage XAML) convertite in SVG, scritto qui. Tracciati (Path con la
// sintassi abbreviata, quasi identica a quella dell'SVG), Canvas annidati con trasformazioni, clip e
// opacita', colori pieni, sfumature, immagini (ImageBrush PNG/JPEG). Il TESTO (Glyphs) si disegna con i
// contorni veri dei glifi presi dal font incorporato (.odttf "offuscato" o .ttf): i font di XPS sono quasi
// sempre ridotti e ri-codificati, quindi l'UnicodeString non basta, servono gli indici dei glifi.
const NS = "http://www.w3.org/2000/svg";

export async function render({ root, zip, util }) {
  const read = async p => { const f = zip.file(p.replace(/^\//, "")) || zip.file(decodeURIComponent(p.replace(/^\//, ""))); return f ? f.async("uint8array") : null; };
  const text = async p => { const b = await read(p); return b ? util.decodeText(b) : null; };
  const xml = async p => { const t = await text(p); return t ? new DOMParser().parseFromString(t, "application/xml") : null; };
  const join = (base, rel) => {
    if (rel.startsWith("/")) return rel.slice(1);
    const parts = (base.slice(0, base.lastIndexOf("/") + 1) + rel).split("/"), out = [];
    for (const x of parts) { if (x === "..") out.pop(); else if (x && x !== ".") out.push(x); }
    return out.join("/");
  };
  // radice: _rels/.rels -> FixedDocumentSequence
  let seqPath = Object.keys(zip.files).find(n => /\.fdseq$/i.test(n));
  const rels = await xml("_rels/.rels");
  if (rels) for (const r of rels.getElementsByTagName("Relationship")) if (/fixedrepresentation$/i.test(r.getAttribute("Type"))) seqPath = r.getAttribute("Target").replace(/^\//, "");
  const seq = seqPath && await xml(seqPath);
  if (!seq) throw Object.assign(new Error("XPS without a document sequence."), { user: true });
  const pages = [];
  for (const dr of seq.getElementsByTagNameNS("*", "DocumentReference")) {
    const docPath = join(seqPath, dr.getAttribute("Source"));
    const fd = await xml(docPath);
    if (!fd) continue;
    for (const pc of fd.getElementsByTagNameNS("*", "PageContent")) pages.push(join(docPath, pc.getAttribute("Source")));
  }
  if (!pages.length) throw Object.assign(new Error("XPS without pages."), { user: true });
  util.A.onFormat(`XPS · ${pages.length} ${pages.length === 1 ? "page" : "pages"}`);

  const fonts = new Map(); // percorso -> font analizzato (o null)
  const images = new Map();
  const ctx = {
    util, join,
    async font(path) {
      if (!fonts.has(path)) {
        let b = await read(path);
        if (b && /\.odttf$/i.test(path)) b = deobfuscate(b, path);
        let f = null;
        try { f = b ? parseTTF(b) : null; } catch (e) { console.warn("font XPS", path, e); }
        fonts.set(path, f);
      }
      return fonts.get(path);
    },
    async image(path) {
      if (!images.has(path)) {
        const b = await read(path);
        images.set(path, b ? URL.createObjectURL(new Blob([b], { type: util.mimeOf(path) || "image/png" })) : null);
      }
      return images.get(path);
    },
    xml, uid: 0,
  };
  const list = util.el("div", { class: "slides xps" });
  root.append(list);
  let maxW = 0;
  const boxes = [];
  for (let i = 0; i < pages.length; i++) {
    const box = util.el("div", { class: "slide-box xps-page" });
    list.append(box, util.el("div", { class: "slide-num", text: `${i + 1} / ${pages.length}` }));
    boxes.push(box);
  }
  document.body.classList.add("page-mode");
  let partial = 0;
  for (let i = 0; i < pages.length; i++) {
    try {
      const doc = await xml(pages[i]);
      const fp = doc.documentElement;
      const W = +fp.getAttribute("Width") || 816, H = +fp.getAttribute("Height") || 1056;
      maxW = Math.max(maxW, W);
      const svg = document.createElementNS(NS, "svg");
      svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
      svg.setAttribute("width", W); svg.setAttribute("height", H);
      const defs = document.createElementNS(NS, "defs");
      svg.append(defs);
      const res = await resources(fp, pages[i], ctx);
      await drawChildren(fp, svg, { ...ctx, defs, res, page: pages[i] });
      boxes[i].style.width = W + "px"; boxes[i].style.height = H + "px";
      boxes[i].append(svg);
      if (i === 0) util.mode("page", W + 24);
    } catch (e) {
      console.warn("XPS page", i + 1, e);
      partial++;
      boxes[i].append(util.el("div", { class: "ph-missing", text: "Page can't be drawn" }));
      boxes[i].style.width = "816px"; boxes[i].style.height = "300px";
    }
  }
  util.mode("page", (maxW || 816) + 24);
  if (partial) util.notice(`${partial} page${partial === 1 ? "" : "s"} can't be drawn`);
  if ([...fonts.values()].some(f => f === null)) util.notice("Some of the document's fonts can't be read: part of the text may be missing");
}

// ------------------------------------------------------------------ risorse (ResourceDictionary)
async function resources(el, base, ctx) {
  const map = new Map();
  for (const r of [...el.children].filter(c => /\.Resources$/.test(c.localName))) {
    for (const rd of r.children) {
      if (rd.getAttribute("Source")) {
        const p = ctx.join(base, rd.getAttribute("Source"));
        const d = await ctx.xml(p);
        if (d) for (const k of d.documentElement.children) map.set(k.getAttribute("x:Key") || k.getAttributeNS("http://schemas.microsoft.com/xps/2005/06/resourcedictionary-key", "Key"), k);
      } else for (const k of rd.children) map.set(k.getAttribute("x:Key") || k.getAttribute("Key"), k);
    }
  }
  return map;
}
const staticRes = (v, ctx) => { const m = /^\{StaticResource\s+([^}]+)\}$/.exec((v || "").trim()); return m ? ctx.res.get(m[1].trim()) : null; };
function prop(el, name, ctx) {
  const a = el.getAttribute(name);
  if (a != null) return staticRes(a, ctx) || a;
  const c = [...el.children].find(x => x.localName === el.localName + "." + name);
  return c ? c.firstElementChild : null;
}

// ------------------------------------------------------------------ disegno
async function drawChildren(el, parent, ctx) {
  for (const c of el.children) {
    if (/\./.test(c.localName)) continue; // proprieta' (Canvas.RenderTransform…)
    if (c.localName === "Canvas") await canvas(c, parent, ctx);
    else if (c.localName === "Path") await path(c, parent, ctx);
    else if (c.localName === "Glyphs") await glyphs(c, parent, ctx);
  }
}
function matrix(v) {
  if (!v) return null;
  if (typeof v !== "string") v = v.getAttribute("Matrix");
  const m = (v || "").split(/[\s,]+/).map(Number);
  return m.length === 6 && m.every(isFinite) ? `matrix(${m.join(" ")})` : null;
}
function common(el, g, ctx) {
  const t = matrix(prop(el, "RenderTransform", ctx));
  if (t) g.setAttribute("transform", t);
  const op = el.getAttribute("Opacity");
  if (op != null && op !== "1") g.setAttribute("opacity", op);
  const clip = prop(el, "Clip", ctx);
  if (clip) {
    const id = "c" + ++ctx.uid;
    const cp = document.createElementNS(NS, "clipPath");
    cp.setAttribute("id", id);
    const p = document.createElementNS(NS, "path");
    const { d, rule, tf } = geometry(clip);
    p.setAttribute("d", d); if (rule) p.setAttribute("clip-rule", rule); if (tf) p.setAttribute("transform", tf);
    cp.append(p); ctx.defs.append(cp);
    // il clip e' nel sistema di coordinate dell'elemento: va sull'elemento, prima della sua trasformazione
    if (t) { const inner = document.createElementNS(NS, "g"); inner.setAttribute("clip-path", `url(#${id})`); g.append(inner); return inner; }
    g.setAttribute("clip-path", `url(#${id})`);
  }
  return g;
}
async function canvas(el, parent, ctx) {
  const g = document.createElementNS(NS, "g");
  parent.append(g);
  const res = await resources(el, ctx.page, ctx);
  const inner = common(el, g, ctx);
  await drawChildren(el, inner, res.size ? { ...ctx, res: new Map([...ctx.res, ...res]) } : ctx);
}
function geometry(v) {
  if (typeof v === "string") {
    const m = /^\s*F\s*([01])\s*/.exec(v);
    return { d: v.replace(/^\s*F\s*[01]\s*/, ""), rule: m ? (m[1] === "1" ? "nonzero" : "evenodd") : "evenodd" };
  }
  // <PathGeometry Figures="…" FillRule Transform> o con <PathFigure>
  const rule = v.getAttribute("FillRule") === "NonZero" ? "nonzero" : "evenodd";
  const tf = matrix(v.getAttribute("Transform") || [...v.children].find(c => /\.Transform$/.test(c.localName))?.firstElementChild);
  let d = v.getAttribute("Figures") ? geometry(v.getAttribute("Figures")).d : "";
  for (const f of v.getElementsByTagNameNS("*", "PathFigure")) {
    d += ` M ${f.getAttribute("StartPoint")}`;
    for (const s of f.children) {
      const pts = (s.getAttribute("Points") || s.getAttribute("Point") || "").trim();
      switch (s.localName) {
        case "PolyLineSegment": d += ` L ${pts}`; break;
        case "PolyBezierSegment": d += ` C ${pts}`; break;
        case "PolyQuadraticBezierSegment": d += ` Q ${pts}`; break;
        case "ArcSegment": d += ` A ${s.getAttribute("Size")} ${s.getAttribute("RotationAngle") || 0} ${s.getAttribute("IsLargeArc") === "true" ? 1 : 0} ${s.getAttribute("SweepDirection") === "Clockwise" ? 1 : 0} ${pts}`; break;
      }
    }
    if (f.getAttribute("IsClosed") === "true") d += " Z";
  }
  return { d, rule, tf };
}
function color(c) {
  c = (c || "").trim();
  let m = /^#([0-9a-f]{2})([0-9a-f]{6})$/i.exec(c);
  if (m) return { c: "#" + m[2], a: parseInt(m[1], 16) / 255 };
  m = /^#([0-9a-f]{6})$/i.exec(c);
  if (m) return { c: "#" + m[1], a: 1 };
  m = /^sc#\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?/i.exec(c);
  if (m) { const v = m[4] != null ? [m[1], m[2], m[3], m[4]].map(Number) : [1, m[1], m[2], m[3]].map(Number); const s = x => Math.round(255 * Math.min(1, Math.max(0, x > 0.0031308 ? 1.055 * x ** (1 / 2.4) - 0.055 : 12.92 * x))); return { c: `rgb(${s(v[1])},${s(v[2])},${s(v[3])})`, a: v[0] }; }
  return null;
}
async function paint(v, ctx, target, which) {
  if (v == null) { target.setAttribute(which, "none"); return; }
  if (typeof v === "string") { const c = color(v); target.setAttribute(which, c ? c.c : "none"); if (c && c.a < 1) target.setAttribute(which + "-opacity", c.a.toFixed(3)); return; }
  const op = v.getAttribute("Opacity");
  if (op && op !== "1") target.setAttribute(which + "-opacity", op);
  const id = "b" + ++ctx.uid;
  switch (v.localName) {
    case "SolidColorBrush": return paint(v.getAttribute("Color"), ctx, target, which);
    case "LinearGradientBrush": case "RadialGradientBrush": {
      const lin = v.localName === "LinearGradientBrush";
      const gr = document.createElementNS(NS, lin ? "linearGradient" : "radialGradient");
      gr.setAttribute("id", id);
      gr.setAttribute("gradientUnits", v.getAttribute("MappingMode") === "Absolute" || !v.getAttribute("MappingMode") ? "userSpaceOnUse" : "objectBoundingBox");
      const pt = a => (v.getAttribute(a) || "0,0").split(",").map(Number);
      if (lin) { const [x1, y1] = pt("StartPoint"), [x2, y2] = pt("EndPoint"); Object.entries({ x1, y1, x2, y2 }).forEach(([k, x]) => gr.setAttribute(k, x)); }
      else { const [cx, cy] = pt("Center"), [fx, fy] = pt("GradientOrigin"); Object.entries({ cx, cy, fx, fy, r: v.getAttribute("RadiusX") || 1 }).forEach(([k, x]) => gr.setAttribute(k, x)); }
      const tf = matrix(v.getAttribute("Transform") || [...v.children].find(c => /\.Transform$/.test(c.localName))?.firstElementChild);
      if (tf) gr.setAttribute("gradientTransform", tf);
      const sm = v.getAttribute("SpreadMethod"); if (sm) gr.setAttribute("spreadMethod", sm.toLowerCase());
      for (const s of v.getElementsByTagNameNS("*", "GradientStop")) {
        const c = color(s.getAttribute("Color")) || { c: "#000", a: 1 };
        const st = document.createElementNS(NS, "stop");
        st.setAttribute("offset", s.getAttribute("Offset") || 0); st.setAttribute("stop-color", c.c);
        if (c.a < 1) st.setAttribute("stop-opacity", c.a);
        gr.append(st);
      }
      ctx.defs.append(gr);
      target.setAttribute(which, `url(#${id})`);
      return;
    }
    case "ImageBrush": {
      const src = await ctx.image(ctx.join(ctx.page, v.getAttribute("ImageSource").replace(/^\{ColorConvertedBitmap\s+([^\s}]+).*\}$/, "$1")));
      if (!src) { target.setAttribute(which, "none"); return; }
      const [vbx, vby, vbw, vbh] = (v.getAttribute("Viewbox") || "0,0,1,1").split(",").map(Number);
      const [vpx, vpy, vpw, vph] = (v.getAttribute("Viewport") || "0,0,1,1").split(",").map(Number);
      const pat = document.createElementNS(NS, "pattern");
      pat.setAttribute("id", id);
      pat.setAttribute("patternUnits", "userSpaceOnUse");
      pat.setAttribute("x", vpx); pat.setAttribute("y", vpy);
      pat.setAttribute("width", vpw); pat.setAttribute("height", vph); // una piastrella = il riquadro (TileMode None: il tracciato di solito coincide)
      const tf = matrix(v.getAttribute("Transform") || [...v.children].find(c => /\.Transform$/.test(c.localName))?.firstElementChild);
      if (tf) pat.setAttribute("patternTransform", tf);
      const inner = document.createElementNS(NS, "svg");
      inner.setAttribute("x", 0); inner.setAttribute("y", 0); inner.setAttribute("width", vpw); inner.setAttribute("height", vph);
      inner.setAttribute("viewBox", `${vbx} ${vby} ${vbw} ${vbh}`); inner.setAttribute("preserveAspectRatio", "none");
      const im = document.createElementNS(NS, "image");
      im.setAttribute("href", src); im.setAttribute("x", vbx); im.setAttribute("y", vby); im.setAttribute("width", vbw); im.setAttribute("height", vbh);
      im.setAttribute("preserveAspectRatio", "none");
      inner.append(im); pat.append(inner); ctx.defs.append(pat);
      target.setAttribute(which, `url(#${id})`);
      return;
    }
    default: target.setAttribute(which, "#bbb"); // VisualBrush: segnaposto grigio
  }
}
async function path(el, parent, ctx) {
  const data = prop(el, "Data", ctx);
  if (!data) return;
  const g = document.createElementNS(NS, "g");
  parent.append(g);
  const inner = common(el, g, ctx);
  const { d, rule, tf } = geometry(data);
  const p = document.createElementNS(NS, "path");
  p.setAttribute("d", d);
  if (tf) p.setAttribute("transform", tf);
  p.setAttribute("fill-rule", rule);
  await paint(prop(el, "Fill", ctx), ctx, p, "fill");
  const stroke = prop(el, "Stroke", ctx);
  if (stroke) {
    await paint(stroke, ctx, p, "stroke");
    p.setAttribute("stroke-width", el.getAttribute("StrokeThickness") || 1);
    const j = el.getAttribute("StrokeLineJoin"); if (j) p.setAttribute("stroke-linejoin", { Miter: "miter", Bevel: "bevel", Round: "round" }[j] || "miter");
    const cap = el.getAttribute("StrokeStartLineCap"); if (cap) p.setAttribute("stroke-linecap", { Flat: "butt", Round: "round", Square: "square", Triangle: "square" }[cap] || "butt");
    const ml = el.getAttribute("StrokeMiterLimit"); if (ml) p.setAttribute("stroke-miterlimit", ml);
    const da = el.getAttribute("StrokeDashArray");
    if (da) { const sw = +(el.getAttribute("StrokeThickness") || 1); p.setAttribute("stroke-dasharray", da.trim().split(/\s+/).map(x => +x * sw).join(" ")); }
  }
  inner.append(p);
}
async function glyphs(el, parent, ctx) {
  const uri = el.getAttribute("FontUri");
  const size = +el.getAttribute("FontRenderingEmSize") || 12;
  const x0 = +el.getAttribute("OriginX") || 0, y0 = +el.getAttribute("OriginY") || 0;
  const str = el.getAttribute("UnicodeString") || "";
  const rtl = (+el.getAttribute("BidiLevel") || 0) % 2 === 1;
  const font = uri ? await ctx.font(ctx.join(ctx.page, uri)) : null;
  const g = document.createElementNS(NS, "g");
  parent.append(g);
  const inner = common(el, g, ctx);
  const p = document.createElementNS(NS, "path");
  await paint(prop(el, "Fill", ctx) || "#FF000000", ctx, p, "fill");
  const sim = el.getAttribute("StyleSimulations") || "";
  // Indices: [ (cluster) ] glifo [, avanzamento [, uOffset [, vOffset ]]] ; …  (avanzamento in centesimi di em)
  const idx = (el.getAttribute("Indices") || "").split(";").map(s => {
    const m = /^\s*(?:\(\d+(?::\d+)?\))?\s*([\d]*)\s*(?:,\s*([-\d.]*))?\s*(?:,\s*([-\d.]*))?\s*(?:,\s*([-\d.]*))?/.exec(s) || [];
    return { gid: m[1] ? +m[1] : null, adv: m[2] ? +m[2] : null, u: m[3] ? +m[3] : 0, v: m[4] ? +m[4] : 0 };
  });
  const chars = [...str];
  const n = Math.max(idx.length, chars.length);
  let x = x0, d = "";
  if (font) {
    const s = size / font.upm;
    for (let i = 0; i < n; i++) {
      const it = idx[i] || {};
      const gid = it.gid != null ? it.gid : chars[i] ? font.cmap(chars[i].codePointAt(0)) : 0;
      if (gid == null) continue;
      const adv = it.adv != null ? it.adv * size / 100 : font.advance(gid) * s;
      const gx = rtl ? x - adv : x;
      const outline = font.path(gid);
      if (outline) d += transformPath(outline, s, gx + (it.u || 0) * size / 100, y0 - (it.v || 0) * size / 100);
      x = rtl ? x - adv : x + adv;
    }
    p.setAttribute("d", d);
    if (/Italic/.test(sim)) p.setAttribute("transform", `translate(${x0} ${y0}) skewX(-20) translate(${-x0} ${-y0})`);
    if (/Bold/.test(sim)) { p.setAttribute("stroke", p.getAttribute("fill")); p.setAttribute("stroke-width", (size / 40).toFixed(2)); }
    inner.append(p);
  }
  // testo invisibile sopra (cerca / copia), solo se la stringa sembra testo vero
  if (str.trim() && (!font || /[aeiouAEIOU\s]/.test(str))) {
    const t = document.createElementNS(NS, "text");
    t.setAttribute("x", x0); t.setAttribute("y", y0); t.setAttribute("font-size", size);
    if (font) { t.setAttribute("fill", "transparent"); t.setAttribute("textLength", Math.abs(x - x0) || size); t.setAttribute("lengthAdjust", "spacingAndGlyphs"); }
    else { const c = p.getAttribute("fill"); t.setAttribute("fill", c && c !== "none" ? c : "#000"); t.setAttribute("font-family", "sans-serif"); }
    t.textContent = str;
    inner.append(t);
  }
}
/** Contorno del glifo in unita' del font (y in su) -> tracciato SVG in coordinate di pagina */
function transformPath(cmds, s, ox, oy) {
  let out = "";
  for (const c of cmds) {
    out += c[0];
    for (let k = 1; k < c.length; k += 2) out += (ox + c[k] * s).toFixed(2) + " " + (oy - c[k + 1] * s).toFixed(2) + " ";
  }
  return out;
}

// ------------------------------------------------------------------ font: .odttf e TrueType (glyf)
function deobfuscate(b, path) {
  const g = /([0-9a-f]{8})-?([0-9a-f]{4})-?([0-9a-f]{4})-?([0-9a-f]{4})-?([0-9a-f]{12})/i.exec(path.split("/").pop());
  if (!g) return b;
  const hex = g.slice(1).join("");
  const key = [];
  for (let i = 0; i < 16; i++) key.push(parseInt(hex.substr(i * 2, 2), 16));
  key.reverse();
  const out = b.slice();
  for (let i = 0; i < 32 && i < out.length; i++) out[i] ^= key[i % 16];
  return out;
}
function parseTTF(b) {
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  let off = 0;
  if (String.fromCharCode(b[0], b[1], b[2], b[3]) === "ttcf") off = dv.getUint32(12);
  const n = dv.getUint16(off + 4);
  const T = {};
  for (let i = 0; i < n; i++) { const r = off + 12 + i * 16; T[String.fromCharCode(b[r], b[r + 1], b[r + 2], b[r + 3])] = { o: dv.getUint32(r + 8), l: dv.getUint32(r + 12) }; }
  if (!T.glyf || !T.loca || !T.head) throw new Error("font without glyf (CFF?)");
  const upm = dv.getUint16(T.head.o + 18), longLoca = dv.getInt16(T.head.o + 50) === 1;
  const numGlyphs = T.maxp ? dv.getUint16(T.maxp.o + 4) : 65535;
  const loca = i => longLoca ? dv.getUint32(T.loca.o + i * 4) : dv.getUint16(T.loca.o + i * 2) * 2;
  const numH = T.hhea ? dv.getUint16(T.hhea.o + 34) : 0;
  const advance = gid => !T.hmtx || !numH ? upm / 2 : dv.getUint16(T.hmtx.o + 4 * Math.min(gid, numH - 1));
  // cmap (formato 4 e 12)
  let cmapFn = () => null;
  if (T.cmap) {
    const c = T.cmap.o, nt = dv.getUint16(c + 2);
    let best = null;
    for (let i = 0; i < nt; i++) {
      const pid = dv.getUint16(c + 4 + i * 8), eid = dv.getUint16(c + 6 + i * 8), so = c + dv.getUint32(c + 8 + i * 8), fmt = dv.getUint16(so);
      if ((fmt === 12 || fmt === 4) && (pid === 3 || pid === 0)) { if (!best || fmt === 12) best = { so, fmt, sym: pid === 3 && eid === 0 }; }
    }
    if (best && best.fmt === 4) {
      const so = best.so, seg = dv.getUint16(so + 6) / 2;
      cmapFn = cp => {
        for (let i = 0; i < seg; i++) {
          const end = dv.getUint16(so + 14 + i * 2);
          if (cp > end) continue;
          const start = dv.getUint16(so + 16 + seg * 2 + i * 2);
          if (cp < start) return best.sym && cp < 0x100 ? cmapFn(cp + 0xF000) : 0;
          const delta = dv.getInt16(so + 16 + seg * 4 + i * 2), ro = so + 16 + seg * 6 + i * 2, range = dv.getUint16(ro);
          if (!range) return (cp + delta) & 0xFFFF;
          const g = dv.getUint16(ro + range + (cp - start) * 2);
          return g ? (g + delta) & 0xFFFF : 0;
        }
        return 0;
      };
    } else if (best) {
      const so = best.so, ng = dv.getUint32(so + 12);
      cmapFn = cp => { for (let i = 0; i < ng; i++) { const s = dv.getUint32(so + 16 + i * 12), e = dv.getUint32(so + 20 + i * 12); if (cp >= s && cp <= e) return dv.getUint32(so + 24 + i * 12) + cp - s; } return 0; };
    }
  }
  const cache = new Map();
  const glyph = (gid, depth = 0) => {
    if (gid >= numGlyphs || depth > 6) return [];
    const a = loca(gid), z = loca(gid + 1);
    if (z <= a) return [];
    const g = T.glyf.o + a;
    const nc = dv.getInt16(g);
    if (nc < 0) { // composto
      let p = g + 10, out = [], flags;
      do {
        flags = dv.getUint16(p); const sub = dv.getUint16(p + 2); p += 4;
        let dx, dy;
        if (flags & 1) { dx = dv.getInt16(p); dy = dv.getInt16(p + 2); p += 4; } else { dx = dv.getInt8(p); dy = dv.getInt8(p + 1); p += 2; }
        let m = [1, 0, 0, 1];
        if (flags & 8) { const s = dv.getInt16(p) / 16384; m = [s, 0, 0, s]; p += 2; }
        else if (flags & 0x40) { m = [dv.getInt16(p) / 16384, 0, 0, dv.getInt16(p + 2) / 16384]; p += 4; }
        else if (flags & 0x80) { m = [dv.getInt16(p) / 16384, dv.getInt16(p + 2) / 16384, dv.getInt16(p + 4) / 16384, dv.getInt16(p + 6) / 16384]; p += 8; }
        for (const c of glyph(sub, depth + 1)) {
          const q = [c[0]];
          for (let k = 1; k < c.length; k += 2) q.push(m[0] * c[k] + m[2] * c[k + 1] + (flags & 2 ? dx : 0), m[1] * c[k] + m[3] * c[k + 1] + (flags & 2 ? dy : 0));
          out.push(q);
        }
      } while (flags & 0x20);
      return out;
    }
    const ends = []; for (let i = 0; i < nc; i++) ends.push(dv.getUint16(g + 10 + i * 2));
    const np = nc ? ends[nc - 1] + 1 : 0;
    let p = g + 10 + nc * 2; p += 2 + dv.getUint16(p);
    const fl = [];
    while (fl.length < np) { const f = b[p++]; fl.push(f); if (f & 8) { let r = b[p++]; while (r-- > 0) fl.push(f); } }
    const xs = [], ys = [];
    let v = 0;
    for (const f of fl) { if (f & 2) { v += f & 16 ? b[p] : -b[p]; p++; } else if (!(f & 16)) { v += dv.getInt16(p); p += 2; } xs.push(v); }
    v = 0;
    for (const f of fl) { if (f & 4) { v += f & 32 ? b[p] : -b[p]; p++; } else if (!(f & 32)) { v += dv.getInt16(p); p += 2; } ys.push(v); }
    const out = [];
    let s = 0;
    for (const e of ends) {
      const pts = []; for (let i = s; i <= e; i++) pts.push({ x: xs[i], y: ys[i], on: fl[i] & 1 });
      s = e + 1;
      if (!pts.length) continue;
      // punto iniziale "on": se il primo e' off, parto dal medio con l'ultimo (o dall'ultimo se on)
      let start = pts[0];
      if (!start.on) { const last = pts[pts.length - 1]; start = last.on ? last : { x: (start.x + last.x) / 2, y: (start.y + last.y) / 2, on: 1 }; }
      out.push(["M", start.x, start.y]);
      for (let i = 0; i < pts.length; i++) {
        const cur = pts[i], nxt = pts[(i + 1) % pts.length];
        if (cur === start) continue;
        if (cur.on) out.push(["L", cur.x, cur.y]);
        else out.push(["Q", cur.x, cur.y, ...(nxt.on ? [nxt.x, nxt.y] : [(cur.x + nxt.x) / 2, (cur.y + nxt.y) / 2])]);
        if (!cur.on && nxt.on) i++;
      }
      out.push(["Z"]);
    }
    return out;
  };
  return {
    upm, advance, cmap: cmapFn,
    path(gid) { if (!cache.has(gid)) { try { cache.set(gid, glyph(gid)); } catch { cache.set(gid, []); } } return cache.get(gid); },
  };
}
