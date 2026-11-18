// Presentazioni. Nessuna libreria JS libera e decente per PPTX (pptx-preview ha il sorgente chiuso e una
// licenza d'uso che vieta di ridistribuirlo modificato; PPTXjs vuole jQuery ed e' fermo): il disegno e' qui.
// PPTX: forme posizionate, testo con stili (eredita' slide -> layout -> master -> tema), riempimenti,
//       bordi, immagini, tabelle, gruppi, sfondi. Grafici e SmartArt: riquadro segnaposto.
// ODP:  cornici posizionate, testo con stili automatici, immagini, forme, sfondo della pagina.
// PPT (97-2003): solo il testo, diapositiva per diapositiva.
const EMU_PX = 1 / 9525; // 914400 EMU = 1 pollice = 96 px

export async function render(ctx) {
  if (ctx.fmt === "ppt") return renderPpt(ctx);
  const deck = ctx.fmt === "odp" ? await readOdp(ctx) : await readPptx(ctx);
  const { root, util } = ctx;
  const list = util.el("div", { class: "slides" });
  root.append(list);
  const W = deck.w, H = deck.h;
  util.mode("page", W + 24);
  document.body.classList.add("page-mode");
  deck.slides.forEach((build, i) => {
    const box = util.el("div", { class: "slide-box", style: { width: W + "px", height: H + "px" } });
    const s = util.el("div", { class: "slide", style: { width: W + "px", height: H + "px" } });
    box.append(s);
    list.append(box, util.el("div", { class: "slide-num", text: `${i + 1} / ${deck.slides.length}` }));
    try { build(s); } catch (e) {
      console.warn("slide", i + 1, e);
      s.append(util.el("div", { class: "ph-missing", text: "Slide can't be drawn" }));
    }
  });
  if (deck.partial) util.notice(deck.partial);
}

// ================================================================== PPTX
const NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
const kids = (n, name) => n ? [...n.children].filter(c => c.localName === name) : [];
const kid = (n, name) => n ? [...n.children].find(c => c.localName === name) || null : null;
const path = (n, ...names) => { for (const nm of names) { n = kid(n, nm); if (!n) return null; } return n; };
const num = (v, d = 0) => v == null || v === "" ? d : Number(v);

function resolvePath(base, target) {
  if (target.startsWith("/")) return target.slice(1);
  const parts = base.split("/"); parts.pop();
  for (const seg of target.split("/")) {
    if (seg === "..") parts.pop(); else if (seg !== ".") parts.push(seg);
  }
  return parts.join("/");
}

async function readPptx({ zip, util }) {
  const xml = async p => { const f = zip.file(p); return f ? new DOMParser().parseFromString(await f.async("string"), "application/xml") : null; };
  const relsOf = async p => {
    const i = p.lastIndexOf("/");
    const d = await xml(p.slice(0, i) + "/_rels/" + p.slice(i + 1) + ".rels");
    const m = {};
    if (d) for (const r of d.getElementsByTagName("Relationship")) m[r.getAttribute("Id")] = { target: r.getAttribute("TargetMode") === "External" ? null : resolvePath(p, r.getAttribute("Target")), type: r.getAttribute("Type") };
    return m;
  };
  const pres = await xml("ppt/presentation.xml");
  const presRels = await relsOf("ppt/presentation.xml");
  const sz = pres.getElementsByTagNameNS("*", "sldSz")[0];
  const w = num(sz?.getAttribute("cx"), 9144000) * EMU_PX, h = num(sz?.getAttribute("cy"), 6858000) * EMU_PX;
  const defaultText = pres.getElementsByTagNameNS("*", "defaultTextStyle")[0];
  const ids = [...pres.getElementsByTagNameNS("*", "sldId")].map(e => e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") || e.getAttribute("r:id"));

  const cache = new Map();
  const part = async p => {
    if (!cache.has(p)) cache.set(p, (async () => ({ path: p, doc: await xml(p), rels: await relsOf(p) }))());
    return cache.get(p);
  };
  const media = new Map();
  const mediaUrl = async p => {
    if (!media.has(p)) {
      const f = zip.file(p);
      media.set(p, f ? URL.createObjectURL(new Blob([await f.async("uint8array")], { type: util.mimeOf(p) })) : null);
    }
    return media.get(p);
  };

  const slides = [];
  let partial = null;
  for (const id of ids) {
    const rel = presRels[id];
    if (!rel || !rel.target) continue;
    const slide = await part(rel.target);
    const layoutRel = Object.values(slide.rels).find(r => r.type.endsWith("/slideLayout"));
    const layout = layoutRel ? await part(layoutRel.target) : null;
    const masterRel = layout && Object.values(layout.rels).find(r => r.type.endsWith("/slideMaster"));
    const master = masterRel ? await part(masterRel.target) : null;
    const themeRel = master && Object.values(master.rels).find(r => r.type.endsWith("/theme"));
    const theme = themeRel ? await part(themeRel.target) : null;
    // immagini di tutte e tre le parti, in anticipo (il disegno e' sincrono)
    const urls = {};
    for (const p of [slide, layout, master].filter(Boolean)) {
      urls[p.path] = {};
      for (const [rid, r] of Object.entries(p.rels)) if (r.target && /\/image$|\/media$/.test(r.type)) urls[p.path][rid] = await mediaUrl(r.target);
    }
    const hasChart = Object.values(slide.rels).some(r => /\/(chart|diagramData)$/.test(r.type));
    if (hasChart) partial = "Charts and SmartArt aren't drawn (a placeholder is shown)";
    slides.push(el => drawPptxSlide(el, { slide, layout, master, theme, urls, defaultText, util }));
  }
  if (!slides.length) throw Object.assign(new Error("Presentation without slides."), { user: true });
  return { w, h, slides, partial };
}

// ------------------------------------------------------------------ colori
function themeColors(theme, master) {
  const map = {};
  const cs = theme && theme.doc.getElementsByTagNameNS(NS_A, "clrScheme")[0];
  if (cs) for (const c of cs.children) {
    const v = c.firstElementChild;
    if (!v) continue;
    map[c.localName] = v.localName === "sysClr" ? (v.getAttribute("lastClr") || "000000") : v.getAttribute("val");
  }
  const cm = master && master.doc.getElementsByTagNameNS("*", "clrMap")[0];
  const alias = { bg1: "lt1", tx1: "dk1", bg2: "lt2", tx2: "dk2" };
  if (cm) for (const k of Object.keys(alias)) alias[k] = cm.getAttribute(k) || alias[k];
  for (const [k, v] of Object.entries(alias)) map[k] = map[v];
  map.phClr = map.phClr || map.accent1;
  return map;
}
const PRST = { black: "000000", white: "FFFFFF", red: "FF0000", green: "008000", blue: "0000FF", yellow: "FFFF00", gray: "808080", darkGray: "404040", lightGray: "D3D3D3" };
function rgb2hsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
  let h = 0, s = 0; const l = (mx + mn) / 2;
  if (mx !== mn) {
    const d = mx - mn;
    s = l > .5 ? d / (2 - mx - mn) : d / (mx + mn);
    h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h /= 6;
  }
  return [h, s, l];
}
function hsl2rgb(h, s, l) {
  if (s === 0) return [l, l, l].map(v => Math.round(v * 255));
  const q = l < .5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  const f = t => { t = (t + 1) % 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < .5 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
  return [f(h + 1 / 3), f(h), f(h - 1 / 3)].map(v => Math.round(v * 255));
}
/** Nodo che CONTIENE un colore (solidFill, fontRef...) -> "rgba(...)" o null. */
function color(node, ctx, phClr) {
  if (!node) return null;
  const c = [...node.children].find(x => /^(srgbClr|schemeClr|sysClr|prstClr|scrgbClr|hslClr)$/.test(x.localName));
  if (!c) return null;
  let hex;
  switch (c.localName) {
    case "srgbClr": hex = c.getAttribute("val"); break;
    case "sysClr": hex = c.getAttribute("lastClr") || "000000"; break;
    case "prstClr": hex = PRST[c.getAttribute("val")] || "000000"; break;
    case "schemeClr": { const v = c.getAttribute("val"); hex = v === "phClr" && phClr ? phClr : ctx.colors[v] || "000000"; break; }
    case "scrgbClr": hex = [c.getAttribute("r"), c.getAttribute("g"), c.getAttribute("b")].map(v => Math.round(num(v) / 100000 * 255).toString(16).padStart(2, "0")).join(""); break;
    default: hex = "000000";
  }
  if (!/^[0-9a-f]{6}$/i.test(hex || "")) hex = "000000";
  let r = parseInt(hex.slice(0, 2), 16), g = parseInt(hex.slice(2, 4), 16), b = parseInt(hex.slice(4, 6), 16), a = 1;
  for (const m of c.children) {
    const v = num(m.getAttribute("val")) / 100000;
    if (m.localName === "alpha") a = v;
    else if (m.localName === "lumMod" || m.localName === "lumOff") {
      const [h, s, l] = rgb2hsl(r, g, b);
      const nl = m.localName === "lumMod" ? l * v : l + v;
      [r, g, b] = hsl2rgb(h, s, Math.min(1, Math.max(0, nl)));
    } else if (m.localName === "tint") { r = Math.round(r + (255 - r) * (1 - v)); g = Math.round(g + (255 - g) * (1 - v)); b = Math.round(b + (255 - b) * (1 - v)); }
    else if (m.localName === "shade") { r = Math.round(r * v); g = Math.round(g * v); b = Math.round(b * v); }
  }
  return a < 1 ? `rgba(${r},${g},${b},${a.toFixed(3)})` : `rgb(${r},${g},${b})`;
}
function fillCss(spPr, ctx, styleNode, urls) {
  if (!spPr) spPr = null;
  const f = spPr && [...spPr.children].find(x => /^(noFill|solidFill|gradFill|blipFill|pattFill|grpFill)$/.test(x.localName));
  if (f) {
    if (f.localName === "noFill") return "transparent";
    if (f.localName === "solidFill") return color(f, ctx);
    if (f.localName === "gradFill") {
      const stops = [...f.getElementsByTagNameNS(NS_A, "gs")].map(gs => `${color(gs, ctx)} ${num(gs.getAttribute("pos")) / 1000}%`);
      const lin = kid(f, "lin");
      const ang = lin ? num(lin.getAttribute("ang")) / 60000 + 90 : 180;
      return stops.length ? `linear-gradient(${ang}deg, ${stops.join(", ")})` : null;
    }
    if (f.localName === "blipFill") {
      const blip = kid(f, "blip");
      const rid = blip && (blip.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed"));
      const u = rid && urls && urls[rid];
      return u ? `center / cover no-repeat url("${u}")` : null;
    }
    if (f.localName === "pattFill") return color(kid(f, "fgClr"), ctx);
    return null;
  }
  const ref = styleNode && kid(styleNode, "fillRef");
  if (ref && num(ref.getAttribute("idx")) > 0) return color(ref, ctx);
  return null;
}

// ------------------------------------------------------------------ segnaposto ed ereditarieta'
function phOf(sp) {
  const nv = kid(sp, "nvSpPr") || kid(sp, "nvPicPr") || kid(sp, "nvGraphicFramePr") || kid(sp, "nvGrpSpPr");
  const ph = nv && path(nv, "nvPr", "ph");
  return ph ? { type: ph.getAttribute("type") || "body", idx: ph.getAttribute("idx") } : null;
}
function findPh(part, ph) {
  if (!part || !ph) return null;
  const tree = part.doc.getElementsByTagNameNS("*", "spTree")[0];
  if (!tree) return null;
  const all = [...tree.getElementsByTagNameNS("*", "sp")];
  const norm = t => t === "ctrTitle" ? "title" : t === "subTitle" ? "body" : t;
  return all.find(s => { const p = phOf(s); return p && ph.idx != null && p.idx === ph.idx; })
    || all.find(s => { const p = phOf(s); return p && p.type === ph.type; })
    || all.find(s => { const p = phOf(s); return p && norm(p.type) === norm(ph.type); })
    || null;
}
function xfrmOf(sp) {
  const spPr = kid(sp, "spPr") || kid(sp, "grpSpPr");
  const x = spPr ? kid(spPr, "xfrm") : kid(sp, "xfrm");
  if (!x) return null;
  const off = kid(x, "off"), ext = kid(x, "ext");
  if (!off || !ext) return null;
  return {
    x: num(off.getAttribute("x")), y: num(off.getAttribute("y")), cx: num(ext.getAttribute("cx")), cy: num(ext.getAttribute("cy")),
    rot: num(x.getAttribute("rot")) / 60000, flipH: x.getAttribute("flipH") === "1", flipV: x.getAttribute("flipV") === "1",
    chOff: kid(x, "chOff"), chExt: kid(x, "chExt"),
  };
}

function drawPptxSlide(el, c) {
  const ctx = { colors: themeColors(c.theme, c.master), util: c.util, c };
  // sfondo: slide > layout > master
  for (const p of [c.slide, c.layout, c.master]) {
    if (!p) continue;
    const bg = p.doc.getElementsByTagNameNS("*", "bg")[0];
    if (!bg) continue;
    const bgPr = kid(bg, "bgPr");
    let css = null;
    if (bgPr) css = fillCss(bgPr, ctx, null, c.urls[p.path]);
    else { const ref = kid(bg, "bgRef"); if (ref) css = color(ref, ctx); }
    if (css) { el.style.background = css; break; }
  }
  if (!el.style.background) el.style.background = "#fff";
  const showMaster = p => !p || p.doc.documentElement.getAttribute("showMasterSp") !== "0";
  // forme decorative di master e layout (non segnaposto), poi la slide
  if (c.master && showMaster(c.slide) && showMaster(c.layout)) drawTree(el, c.master, ctx, true);
  if (c.layout && showMaster(c.slide)) drawTree(el, c.layout, ctx, true);
  drawTree(el, c.slide, ctx, false);
}

function drawTree(el, part, ctx, decorOnly) {
  const tree = part.doc.getElementsByTagNameNS("*", "spTree")[0];
  if (!tree) return;
  const tf = { ox: 0, oy: 0, sx: 1, sy: 1, cox: 0, coy: 0 };
  for (const n of tree.children) drawNode(el, n, part, ctx, decorOnly, tf);
}

function place(x, tf) {
  const L = (tf.ox + (x.x - tf.cox) * tf.sx) * EMU_PX, T = (tf.oy + (x.y - tf.coy) * tf.sy) * EMU_PX;
  return { left: L, top: T, width: x.cx * tf.sx * EMU_PX, height: x.cy * tf.sy * EMU_PX };
}

function drawNode(el, n, part, ctx, decorOnly, tf) {
  const name = n.localName;
  if (!/^(sp|pic|graphicFrame|grpSp|cxnSp|AlternateContent)$/.test(name)) return;
  if (name === "AlternateContent") {
    const fb = kid(n, "Fallback") || kid(n, "Choice");
    if (fb) for (const k of fb.children) drawNode(el, k, part, ctx, decorOnly, tf);
    return;
  }
  const ph = phOf(n);
  if (decorOnly && ph) return;
  let x = xfrmOf(n);
  const inh = [];
  if (ph && !decorOnly) {
    const lph = findPh(ctx.c.layout, ph), mph = findPh(ctx.c.master, ph);
    if (!x) x = (lph && xfrmOf(lph)) || (mph && xfrmOf(mph));
    inh.push(lph, mph);
  }
  if (name === "graphicFrame" && !x) {
    const gx = kid(n, "xfrm");
    if (gx) x = xfrmOf({ children: [gx] }) || x;
  }
  if (!x) return;
  if (name === "grpSp") {
    const chOff = x.chOff, chExt = x.chExt;
    const ntf = {
      ox: tf.ox + (x.x - tf.cox) * tf.sx, oy: tf.oy + (x.y - tf.coy) * tf.sy,
      sx: tf.sx * (chExt && num(chExt.getAttribute("cx")) ? x.cx / num(chExt.getAttribute("cx")) : 1),
      sy: tf.sy * (chExt && num(chExt.getAttribute("cy")) ? x.cy / num(chExt.getAttribute("cy")) : 1),
      cox: chOff ? num(chOff.getAttribute("x")) : 0, coy: chOff ? num(chOff.getAttribute("y")) : 0,
    };
    for (const k of n.children) drawNode(el, k, part, ctx, decorOnly, ntf);
    return;
  }
  const box = place(x, tf);
  const d = document.createElement("div");
  d.className = "sh";
  Object.assign(d.style, { left: box.left + "px", top: box.top + "px", width: Math.max(1, box.width) + "px", height: Math.max(1, box.height) + "px" });
  const tr = [];
  if (x.rot) tr.push(`rotate(${x.rot}deg)`);
  if (x.flipH) tr.push("scaleX(-1)");
  if (x.flipV) tr.push("scaleY(-1)");
  if (tr.length) d.style.transform = tr.join(" ");
  const urls = ctx.c.urls[part.path] || {};

  if (name === "pic") {
    const blip = n.getElementsByTagNameNS(NS_A, "blip")[0];
    const rid = blip && blip.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
    const u = rid && urls[rid];
    if (u && !/\.(emf|wmf)$/i.test(part.rels[rid]?.target || "")) {
      const img = document.createElement("img");
      img.src = u; img.alt = "";
      const src = n.getElementsByTagNameNS(NS_A, "srcRect")[0];
      if (src) {
        const l = num(src.getAttribute("l")) / 1000, t = num(src.getAttribute("t")) / 1000, r = num(src.getAttribute("r")) / 1000, b = num(src.getAttribute("b")) / 1000;
        if (l || t || r || b) {
          const W = 100 / (100 - l - r) * 100, H = 100 / (100 - t - b) * 100;
          Object.assign(img.style, { width: W + "%", height: H + "%", left: -l * W / 100 + "%", top: -t * H / 100 + "%" });
        }
      }
      d.style.overflow = "hidden";
      d.append(img);
    } else {
      d.append(ctx.util.el("div", { class: "ph-missing", text: u ? "EMF/WMF image" : "Image" }));
    }
    el.append(d);
    return;
  }
  if (name === "graphicFrame") {
    const tbl = n.getElementsByTagNameNS(NS_A, "tbl")[0];
    if (tbl) d.append(pptxTable(tbl, ctx));
    else {
      const uri = n.getElementsByTagNameNS(NS_A, "graphicData")[0]?.getAttribute("uri") || "";
      d.append(ctx.util.el("div", { class: "ph-missing", text: /chart/.test(uri) ? "Chart (not drawn)" : /diagram/.test(uri) ? "SmartArt (not drawn)" : "Embedded object" }));
    }
    el.append(d);
    return;
  }
  // sp / cxnSp
  const spPr = kid(n, "spPr");
  const style = kid(n, "style");
  let fill = fillCss(spPr, ctx, style, urls);
  if (fill == null) for (const s of inh) { if (s && (fill = fillCss(kid(s, "spPr"), ctx, kid(s, "style"), urls)) != null) break; }
  if (fill && fill !== "transparent" && name !== "cxnSp") d.style.background = fill;
  // bordo
  const ln = spPr && kid(spPr, "ln");
  let lnColor = null, lnW = 0;
  if (ln && !kid(ln, "noFill")) {
    lnColor = color(kid(ln, "solidFill"), ctx);
    lnW = Math.max(1, num(ln.getAttribute("w"), 12700) * EMU_PX);
  }
  if (!lnColor && !(ln && kid(ln, "noFill")) && style) {
    const ref = kid(style, "lnRef");
    if (ref && num(ref.getAttribute("idx")) > 0) { lnColor = color(ref, ctx); lnW = 1; }
  }
  const geom = spPr && kid(spPr, "prstGeom");
  const prst = geom ? geom.getAttribute("prst") : "rect";
  if (name === "cxnSp" || /^(line|straightConnector1|bentConnector\d|curvedConnector\d)$/.test(prst)) {
    if (lnColor) {
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" style="position:absolute;inset:0;overflow:visible"><line x1="0" y1="0" x2="100%" y2="100%" stroke="${lnColor}" stroke-width="${lnW}"/></svg>`;
      d.innerHTML = svg;
    }
    el.append(d);
    return;
  }
  if (lnColor) d.style.border = `${lnW}px solid ${lnColor}`;
  if (prst === "ellipse" || prst === "circle") d.style.borderRadius = "50%";
  else if (prst === "roundRect") d.style.borderRadius = Math.min(box.width, box.height) * 0.1667 + "px";
  else if (prst === "triangle") d.style.clipPath = "polygon(50% 0, 100% 100%, 0 100%)";
  else if (prst === "rtTriangle") d.style.clipPath = "polygon(0 0, 100% 100%, 0 100%)";
  else if (prst === "diamond") d.style.clipPath = "polygon(50% 0, 100% 50%, 50% 100%, 0 50%)";
  else if (prst === "rightArrow") d.style.clipPath = "polygon(0 25%, 65% 25%, 65% 0, 100% 50%, 65% 100%, 65% 75%, 0 75%)";
  else if (prst === "leftArrow") d.style.clipPath = "polygon(100% 25%, 35% 25%, 35% 0, 0 50%, 35% 100%, 35% 75%, 100% 75%)";
  else if (prst === "chevron") d.style.clipPath = "polygon(0 0, 75% 0, 100% 50%, 75% 100%, 0 100%, 25% 50%)";
  else if (prst === "hexagon") d.style.clipPath = "polygon(25% 0, 75% 0, 100% 50%, 75% 100%, 25% 100%, 0 50%)";
  const tx = kid(n, "txBody");
  if (tx) d.append(textBody(tx, ctx, ph, inh, style));
  el.append(d);
}

// ------------------------------------------------------------------ testo
function lvlProps(lst, lvl) {
  if (!lst) return null;
  return kid(lst, `lvl${lvl + 1}pPr`) || null;
}
function textBody(tx, ctx, ph, inh, style) {
  const c = ctx.c;
  const bodyPr = kid(tx, "bodyPr");
  const inhBody = inh.map(s => s && path(s, "txBody", "bodyPr")).filter(Boolean);
  const bp = k => { for (const b of [bodyPr, ...inhBody]) { const v = b && b.getAttribute(k); if (v != null) return v; } return null; };
  const t = document.createElement("div");
  t.className = "tx";
  const ins = (k, d) => num(bp(k), d) * EMU_PX;
  t.style.padding = `${ins("tIns", 45720)}px ${ins("rIns", 91440)}px ${ins("bIns", 45720)}px ${ins("lIns", 91440)}px`;
  const anchor = bp("anchor") || (ph && /title/i.test(ph.type) ? "ctr" : "t");
  t.style.justifyContent = anchor === "ctr" ? "center" : anchor === "b" ? "flex-end" : "flex-start";
  if (bp("wrap") === "none") t.style.whiteSpace = "nowrap";
  const vert = bp("vert");
  if (vert && vert !== "horz") t.style.writingMode = "vertical-rl";
  const auto = bodyPr && kid(bodyPr, "normAutofit");
  const scale = auto ? num(auto.getAttribute("fontScale"), 100000) / 100000 : 1;
  const lnRed = auto ? num(auto.getAttribute("lnSpcReduction"), 0) / 100000 : 0;

  // catena degli stili di lista: forma -> layout -> master -> txStyles del master -> default presentazione
  const kind = !ph ? "other" : /title/i.test(ph.type) ? "title" : /^(body|obj|subTitle|)$/.test(ph.type) || ph.idx != null && !/^(dt|ftr|sldNum)$/.test(ph.type) ? "body" : "other";
  const txStyles = c.master && c.master.doc.getElementsByTagNameNS("*", "txStyles")[0];
  const mStyle = txStyles && kid(txStyles, kind === "title" ? "titleStyle" : kind === "body" ? "bodyStyle" : "otherStyle");
  const chain = [kid(tx, "lstStyle"), ...inh.map(s => s && path(s, "txBody", "lstStyle")), ph ? mStyle : null, c.defaultText].filter(Boolean);
  const fontRefColor = style && color(kid(style, "fontRef"), ctx);

  const autoNum = {};
  for (const p of kids(tx, "p")) {
    const pPr = kid(p, "pPr");
    const lvl = num(pPr && pPr.getAttribute("lvl"), 0);
    const levels = [pPr, ...chain.map(l => lvlProps(l, lvl))].filter(Boolean);
    const pp = k => { for (const l of levels) { const v = l.getAttribute(k); if (v != null) return v; } return null; };
    const pk = k => { for (const l of levels) { const v = kid(l, k); if (v) return v; } return null; };
    const defR = levels.map(l => kid(l, "defRPr")).filter(Boolean);
    const para = document.createElement("p");
    const algn = pp("algn");
    para.style.textAlign = { ctr: "center", r: "right", just: "justify", dist: "justify" }[algn] || "left";
    const marL = num(pp("marL"), 0) * EMU_PX, indent = num(pp("indent"), 0) * EMU_PX;
    para.style.paddingLeft = marL + "px";
    para.style.textIndent = indent + "px";
    const spcBef = pk("spcBef"), spcAft = pk("spcAft"), lnSpc = pk("lnSpc");
    const pts = n => n && kid(n, "spcPts") ? num(kid(n, "spcPts").getAttribute("val")) / 100 * 4 / 3 : 0;
    if (spcBef) para.style.marginTop = pts(spcBef) + "px";
    if (spcAft) para.style.marginBottom = pts(spcAft) + "px";
    if (lnSpc && kid(lnSpc, "spcPct")) para.style.lineHeight = Math.max(.6, num(kid(lnSpc, "spcPct").getAttribute("val")) / 100000 * (1 - lnRed)) * 1.12;

    const runProps = rPr => {
      const all = [rPr, ...defR].filter(Boolean);
      const g = k => { for (const r of all) { const v = r.getAttribute(k); if (v != null) return v; } return null; };
      const gk = k => { for (const r of all) { const v = kid(r, k); if (v) return v; } return null; };
      const sz = num(g("sz"), kind === "title" ? 4400 : 1800) / 100 * scale;
      const css = { fontSize: sz * 4 / 3 + "px" };
      if (g("b") === "1") css.fontWeight = "700";
      if (g("i") === "1") css.fontStyle = "italic";
      const u = g("u");
      const deco = [];
      if (u && u !== "none") deco.push("underline");
      if (g("strike") && g("strike") !== "noStrike") deco.push("line-through");
      if (deco.length) css.textDecoration = deco.join(" ");
      const col = color(gk("solidFill"), ctx) || fontRefColor || (kind === "title" ? `rgb(${hex3(ctx.colors.tx1)})` : null);
      css.color = col || `rgb(${hex3(ctx.colors.tx1 || "000000")})`;
      const latin = gk("latin");
      const face = latin && latin.getAttribute("typeface");
      if (face && !face.startsWith("+")) css.fontFamily = `"${face}", Calibri, Carlito, "Liberation Sans", Arial, sans-serif`;
      const base = g("baseline");
      if (base && num(base)) { css.verticalAlign = num(base) > 0 ? "super" : "sub"; css.fontSize = sz * 4 / 3 * .7 + "px"; }
      const cap = g("cap");
      if (cap === "all") css.textTransform = "uppercase";
      return { css, sz };
    };

    let firstSz = null, any = false;
    for (const r of p.children) {
      if (r.localName === "r" || r.localName === "fld") {
        const { css, sz } = runProps(kid(r, "rPr"));
        if (firstSz == null) firstSz = { css, sz };
        const s = document.createElement("span");
        Object.assign(s.style, css);
        s.textContent = kid(r, "t")?.textContent || "";
        para.append(s);
        any = true;
      } else if (r.localName === "br") {
        para.append(document.createElement("br"));
      }
    }
    const endProps = runProps(kid(p, "endParaRPr"));
    if (!any) { para.style.fontSize = endProps.css.fontSize; para.innerHTML = "&#8203;"; }
    // punto elenco
    if (any && !pk("buNone")) {
      const ch = pk("buChar"), an = pk("buAutoNum");
      let bullet = null;
      if (ch) bullet = ch.getAttribute("char");
      else if (an) {
        const key = lvl + (an.getAttribute("type") || "");
        autoNum[key] = (autoNum[key] || num(an.getAttribute("startAt"), 1) - 1) + 1;
        const n = autoNum[key], ty = an.getAttribute("type") || "arabicPeriod";
        bullet = (/alphaLc/.test(ty) ? String.fromCharCode(96 + n) : /alphaUc/.test(ty) ? String.fromCharCode(64 + n) : /romanLc/.test(ty) ? roman(n).toLowerCase() : /romanUc/.test(ty) ? roman(n) : n) + (/Paren/.test(ty) ? ")" : ".");
      }
      if (bullet) {
        const b = document.createElement("span");
        const bc = color(pk("buClr"), ctx);
        Object.assign(b.style, { display: "inline-block", minWidth: Math.max(-indent, 0) + "px", textIndent: "0", fontSize: firstSz ? firstSz.css.fontSize : "", color: bc || (firstSz ? firstSz.css.color : "") });
        b.textContent = bullet + " ";
        const bf = pk("buFont");
        // Wingdings/Symbol/OpenSymbol: il carattere e' nell'area privata del font, qui non esiste
        if (bf && /Wingdings|Symbol/i.test(bf.getAttribute("typeface") || "") || /[\uE000-\uF8FF]/.test(bullet)) b.textContent = /[\uF0A7\uF0FC\u2713]/.test(bullet) ? "✓ " : /[\uF0D8\uF0E0]/.test(bullet) ? "➢ " : /[\uF0A8]/.test(bullet) ? "▪ " : "• ";
        para.prepend(b);
      }
    } else if (any) { Object.values(autoNum); }
    t.append(para);
  }
  return t;
}
const hex3 = h => h ? [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)).join(",") : "0,0,0";
function roman(n) {
  const m = [[1000, "M"], [900, "CM"], [500, "D"], [400, "CD"], [100, "C"], [90, "XC"], [50, "L"], [40, "XL"], [10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  let s = ""; for (const [v, r] of m) while (n >= v) { s += r; n -= v; } return s;
}

function pptxTable(tbl, ctx) {
  const t = document.createElement("table");
  const cols = kids(kid(tbl, "tblGrid"), "gridCol").map(g => num(g.getAttribute("w")) * EMU_PX);
  const cg = document.createElement("colgroup");
  cols.forEach(w => { const c = document.createElement("col"); c.style.width = w + "px"; cg.append(c); });
  t.append(cg);
  const tblPr = kid(tbl, "tblPr");
  const firstRow = tblPr && tblPr.getAttribute("firstRow") === "1", band = tblPr && tblPr.getAttribute("bandRow") === "1";
  kids(tbl, "tr").forEach((tr, ri) => {
    const row = document.createElement("tr");
    row.style.height = num(tr.getAttribute("h")) * EMU_PX + "px";
    for (const tc of kids(tr, "tc")) {
      if (tc.getAttribute("hMerge") === "1" || tc.getAttribute("vMerge") === "1") continue;
      const td = document.createElement("td");
      if (tc.getAttribute("gridSpan")) td.colSpan = num(tc.getAttribute("gridSpan"));
      if (tc.getAttribute("rowSpan")) td.rowSpan = num(tc.getAttribute("rowSpan"));
      const tcPr = kid(tc, "tcPr");
      let bg = tcPr && fillCss(tcPr, ctx);
      if (!bg || bg === "transparent") bg = firstRow && ri === 0 ? `rgb(${hex3(ctx.colors.accent1)})` : band && ri % 2 ? "rgba(0,0,0,.06)" : bg;
      if (bg) td.style.background = bg;
      td.style.border = "1px solid rgba(255,255,255,.9)";
      if (!tcPr || !fillCss(tcPr, ctx)) td.style.border = "1px solid #bbb";
      const txb = kid(tc, "txBody");
      if (txb) {
        const body = textBody(txb, ctx, null, [], null);
        body.style.position = "static"; body.style.padding = "0";
        if (firstRow && ri === 0) body.querySelectorAll("span").forEach(s => { if (!kid(tc, "txBody").getElementsByTagNameNS(NS_A, "solidFill").length) s.style.color = "#fff"; s.style.fontWeight = "700"; });
        td.append(body);
      }
      row.append(td);
    }
    t.append(row);
  });
  return t;
}

// ================================================================== ODP
const NS = {
  draw: "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", svg: "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0",
  style: "urn:oasis:names:tc:opendocument:xmlns:style:1.0", fo: "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0",
  text: "urn:oasis:names:tc:opendocument:xmlns:text:1.0", xlink: "http://www.w3.org/1999/xlink",
  pres: "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0", table: "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
};
export function odfLen(v) {
  if (!v) return 0;
  const m = /^(-?[\d.]+)(cm|mm|in|pt|pc|px)?$/.exec(v.trim());
  if (!m) return 0;
  const n = Number(m[1]);
  return n * ({ cm: 37.7953, mm: 3.77953, in: 96, pt: 4 / 3, pc: 16, px: 1 }[m[2] || "cm"]);
}
export function odfStyles(docs) {
  const map = {};
  for (const d of docs) {
    if (!d) continue;
    for (const s of d.getElementsByTagNameNS(NS.style, "style")) map[(s.getAttributeNS(NS.style, "family") || "") + ":" + s.getAttributeNS(NS.style, "name")] = s;
    for (const s of d.getElementsByTagNameNS(NS.style, "default-style")) map[(s.getAttributeNS(NS.style, "family") || "") + ":"] = map[(s.getAttributeNS(NS.style, "family") || "") + ":"] || s;
  }
  return map;
}
/** Proprieta' risolte (catena parent-style-name) di uno stile ODF, come CSS. */
export function odfCss(styles, family, name, depth = 0) {
  const s = name ? styles[family + ":" + name] : null;
  const base = depth > 8 ? {} : s ? odfCss(styles, family, s.getAttributeNS(NS.style, "parent-style-name"), depth + 1) : (depth === 0 && styles[family + ":"] ? propsCss(styles[family + ":"]) : {});
  return s ? Object.assign({}, base, propsCss(s)) : base;
}
function propsCss(s) {
  const css = {};
  const tp = s.getElementsByTagNameNS(NS.style, "text-properties")[0];
  const pp = s.getElementsByTagNameNS(NS.style, "paragraph-properties")[0];
  const gp = s.getElementsByTagNameNS(NS.style, "graphic-properties")[0] || s.getElementsByTagNameNS(NS.style, "drawing-page-properties")[0];
  const cp = s.getElementsByTagNameNS(NS.style, "table-cell-properties")[0];
  const fo = (n, k) => n && n.getAttributeNS(NS.fo, k);
  const st = (n, k) => n && n.getAttributeNS(NS.style, k);
  if (tp) {
    if (fo(tp, "font-weight")) css.fontWeight = fo(tp, "font-weight") === "bold" ? "700" : fo(tp, "font-weight");
    if (fo(tp, "font-style")) css.fontStyle = fo(tp, "font-style");
    if (fo(tp, "font-size") && !fo(tp, "font-size").endsWith("%")) css.fontSize = odfLen(fo(tp, "font-size")) + "px";
    if (fo(tp, "color")) css.color = fo(tp, "color");
    if (fo(tp, "background-color") && fo(tp, "background-color") !== "transparent") css.backgroundColor = fo(tp, "background-color");
    const deco = [];
    if (st(tp, "text-underline-style") && st(tp, "text-underline-style") !== "none") deco.push("underline");
    if (st(tp, "text-line-through-style") && st(tp, "text-line-through-style") !== "none") deco.push("line-through");
    if (deco.length) css.textDecoration = deco.join(" ");
    if (st(tp, "font-name")) css.fontFamily = `"${st(tp, "font-name")}", "Liberation Serif", serif`;
    if (st(tp, "text-position") && /^super/.test(st(tp, "text-position"))) css.verticalAlign = "super";
    if (st(tp, "text-position") && /^sub/.test(st(tp, "text-position"))) css.verticalAlign = "sub";
    if (fo(tp, "text-transform") && fo(tp, "text-transform") !== "none") css.textTransform = fo(tp, "text-transform");
  }
  if (pp) {
    const a = fo(pp, "text-align");
    if (a) css.textAlign = { start: "left", end: "right", left: "left", right: "right", center: "center", justify: "justify" }[a] || a;
    if (fo(pp, "margin-left")) css.marginLeft = odfLen(fo(pp, "margin-left")) + "px";
    if (fo(pp, "margin-top")) css.marginTop = odfLen(fo(pp, "margin-top")) + "px";
    if (fo(pp, "margin-bottom")) css.marginBottom = odfLen(fo(pp, "margin-bottom")) + "px";
    if (fo(pp, "text-indent")) css.textIndent = odfLen(fo(pp, "text-indent")) + "px";
    if (fo(pp, "background-color") && fo(pp, "background-color") !== "transparent") css.backgroundColor = fo(pp, "background-color");
  }
  if (gp) {
    const fill = gp.getAttributeNS(NS.draw, "fill");
    if (fill === "solid" && gp.getAttributeNS(NS.draw, "fill-color")) css.background = gp.getAttributeNS(NS.draw, "fill-color");
    if (fill === "none") css.background = "transparent";
    const stroke = gp.getAttributeNS(NS.draw, "stroke");
    if (stroke && stroke !== "none") css.border = `${Math.max(1, odfLen(gp.getAttributeNS(NS.svg, "stroke-width") || "0.03cm"))}px solid ${gp.getAttributeNS(NS.svg, "stroke-color") || "#3465a4"}`;
    if (stroke === "none") css.border = "none";
    const va = gp.getAttributeNS(NS.draw, "textarea-vertical-align");
    if (va) css._va = va;
  }
  if (cp) {
    if (fo(cp, "background-color") && fo(cp, "background-color") !== "transparent") css.backgroundColor = fo(cp, "background-color");
    for (const side of ["", "-top", "-bottom", "-left", "-right"]) {
      const b = fo(cp, "border" + side);
      if (b && b !== "none") css["border" + (side ? side[1].toUpperCase() + side.slice(2) : "")] = b.replace(/([\d.]+)(pt|cm|mm|in)/, (m) => Math.max(1, odfLen(m)).toFixed(1) + "px");
    }
    if (fo(cp, "padding")) css.padding = odfLen(fo(cp, "padding")) + "px";
    const va = st(cp, "vertical-align"); if (va) css.verticalAlign = va === "middle" ? "middle" : va;
  }
  return css;
}
export function applyCss(el, css) { for (const [k, v] of Object.entries(css)) if (!k.startsWith("_")) el.style[k] = v; }

async function readOdp({ zip, util }) {
  const parse = async p => { const f = zip.file(p); return f ? new DOMParser().parseFromString(await f.async("string"), "application/xml") : null; };
  const content = await parse("content.xml"), styles = await parse("styles.xml");
  if (!content) throw Object.assign(new Error("Presentation without content.xml"), { user: true });
  const st = odfStyles([styles, content]);
  let w = 25.4 * 37.7953, h = 14.29 * 37.7953;
  // la misura giusta e' quella del layout usato dalla pagina master della prima diapositiva
  // (il primo layout del file e' spesso quello delle note o degli stampati, in verticale)
  const firstPage = content.getElementsByTagNameNS(NS.draw, "page")[0];
  const mName = firstPage && firstPage.getAttributeNS(NS.draw, "master-page-name");
  const mp = styles && [...styles.getElementsByTagNameNS(NS.style, "master-page")].find(m => m.getAttributeNS(NS.style, "name") === mName);
  const plName = mp && mp.getAttributeNS(NS.style, "page-layout-name");
  const plEl = styles && [...styles.getElementsByTagNameNS(NS.style, "page-layout")].find(l => l.getAttributeNS(NS.style, "name") === plName);
  const pl = plEl ? plEl.getElementsByTagNameNS(NS.style, "page-layout-properties")[0] : styles && styles.getElementsByTagNameNS(NS.style, "page-layout-properties")[0];
  if (pl) { w = odfLen(pl.getAttributeNS(NS.fo, "page-width")) || w; h = odfLen(pl.getAttributeNS(NS.fo, "page-height")) || h; }
  const pics = {};
  for (const n of content.getElementsByTagNameNS(NS.draw, "image")) {
    const href = n.getAttributeNS(NS.xlink, "href");
    if (href && !pics[href] && zip.file(href)) pics[href] = URL.createObjectURL(new Blob([await zip.file(href).async("uint8array")], { type: util.mimeOf(href) }));
  }
  const masters = {};
  if (styles) for (const m of styles.getElementsByTagNameNS(NS.style, "master-page")) masters[m.getAttributeNS(NS.style, "name")] = m;
  const pages = [...content.getElementsByTagNameNS(NS.draw, "page")];
  const slides = pages.map(pg => el => {
    const bg = odfCss(st, "drawing-page", pg.getAttributeNS(NS.draw, "style-name"));
    const master = masters[pg.getAttributeNS(NS.draw, "master-page-name")];
    const mbg = master ? odfCss(st, "drawing-page", master.getAttributeNS(NS.draw, "style-name")) : {};
    el.style.background = bg.background || mbg.background || "#fff";
    for (const n of pg.children) drawOdf(el, n, st, pics, util);
  });
  return { w, h, slides };
}

function drawOdf(el, n, st, pics, util) {
  const ln = n.localName;
  if (!/^(frame|custom-shape|rect|ellipse|g|line|connector)$/.test(ln)) return;
  if (ln === "g") { for (const k of n.children) drawOdf(el, k, st, pics, util); return; }
  const d = document.createElement("div");
  d.className = "sh";
  const x = odfLen(n.getAttributeNS(NS.svg, "x")), y = odfLen(n.getAttributeNS(NS.svg, "y"));
  const w = odfLen(n.getAttributeNS(NS.svg, "width")), h = odfLen(n.getAttributeNS(NS.svg, "height"));
  Object.assign(d.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" });
  const gcss = Object.assign({}, odfCss(st, "presentation", n.getAttributeNS(NS.pres, "style-name")), odfCss(st, "graphic", n.getAttributeNS(NS.draw, "style-name")));
  if (ln !== "frame") applyCss(d, gcss);
  if (ln === "ellipse") d.style.borderRadius = "50%";
  if (ln === "custom-shape") {
    const g = n.getElementsByTagNameNS(NS.draw, "enhanced-geometry")[0];
    const t = g && g.getAttributeNS(NS.draw, "type");
    if (t === "ellipse") d.style.borderRadius = "50%";
    if (t === "round-rectangle") d.style.borderRadius = "12px";
  }
  const img = [...n.children].find(c => c.localName === "image");
  const tb = [...n.children].find(c => c.localName === "text-box");
  if (img) {
    const u = pics[img.getAttributeNS(NS.xlink, "href")];
    if (u) { const i = document.createElement("img"); i.src = u; d.append(i); }
    else d.append(util.el("div", { class: "ph-missing", text: "Image" }));
  } else if (n.getElementsByTagNameNS(NS.table, "table")[0]) {
    d.append(util.el("div", { class: "ph-missing", text: "Table" }));
  }
  const textHost = tb || (ln !== "frame" ? n : null);
  if (textHost) {
    const t = document.createElement("div");
    t.className = "tx";
    t.style.padding = "4px 8px";
    const va = gcss._va || (ln !== "frame" ? "middle" : "top");
    t.style.justifyContent = va === "middle" ? "center" : va === "bottom" ? "flex-end" : "flex-start";
    const pcls = n.getAttributeNS(NS.pres, "class");
    odfParas(textHost, t, st, pcls, 0);
    d.append(t);
  }
  el.append(d);
}
function odfParas(host, out, st, pcls, lvl) {
  for (const c of host.children) {
    if (c.localName === "p" || c.localName === "h") {
      const p = document.createElement("p");
      const css = odfCss(st, "paragraph", c.getAttributeNS(NS.text, "style-name"));
      if (!css.fontSize) css.fontSize = (pcls === "title" ? 44 : 18) * 4 / 3 + "px";
      applyCss(p, css);
      if (lvl > 0) { const b = document.createElement("span"); b.textContent = "• "; p.prepend(b); p.style.paddingLeft = (lvl * 24) + "px"; p.style.textIndent = "-14px"; }
      odfInline(c, p, st);
      if (!p.textContent) p.innerHTML = "&#8203;";
      out.append(p);
    } else if (c.localName === "list") {
      for (const li of c.children) if (li.localName === "list-item" || li.localName === "list-header") odfParas(li, out, st, pcls, lvl + 1);
    }
  }
}
export function odfInline(node, out, st) {
  for (const c of node.childNodes) {
    if (c.nodeType === 3) { out.append(c.data); continue; }
    if (c.nodeType !== 1) continue;
    switch (c.localName) {
      case "span": { const s = document.createElement("span"); applyCss(s, odfCss(st, "text", c.getAttributeNS(NS.text, "style-name"))); odfInline(c, s, st); out.append(s); break; }
      case "a": { const a = document.createElement("a"); a.href = c.getAttributeNS(NS.xlink, "href") || "#"; odfInline(c, a, st); out.append(a); break; }
      case "s": out.append(" ".repeat(Number(c.getAttributeNS(NS.text, "c") || 1))); break;
      case "tab": out.append("\t"); break;
      case "line-break": out.append(document.createElement("br")); break;
      case "note": case "annotation": case "bookmark": case "bookmark-start": case "bookmark-end": case "soft-page-break": break;
      default: odfInline(c, out, st);
    }
  }
}

// ================================================================== PPT 97-2003: testo + immagini
// Testo: dalla SlideListWithText o dalle forme di ogni diapositiva. v5: anche le IMMAGINI di ogni diapositiva:
// ogni forma dice quale immagine usa (proprieta' pib), e le immagini stanno nel BStore del documento (dentro il
// record o nel flusso "Pictures"). Le posizioni non si ricostruiscono: testo e immagini, diapositiva per diapositiva.
function renderPpt({ root, cfb, util }) {
  const f = cfb.FileIndex.find(x => x.name === "PowerPoint Document");
  const b = f.content instanceof Uint8Array ? f.content : new Uint8Array(f.content);
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const picF = cfb.FileIndex.find(x => x.name === "Pictures");
  const pics = picF ? (picF.content instanceof Uint8Array ? picF.content : new Uint8Array(picF.content)) : null;
  // Il testo sta o nella SlideListWithText (PowerPoint) o dentro le forme di ogni Slide (LibreOffice):
  // raccolgo entrambi e tengo il piu' ricco. Note (0x03F0) e master (0x03F8) non sono diapositive.
  const fromList = [], fromSlides = [], slideImgs = [], bstore = [];
  let curL = null, curS = null, curImg = null;
  const rd = (type, body, len) => type === 0x0FA0 ? new TextDecoder("utf-16le").decode(b.subarray(body, body + len)) : new TextDecoder("windows-1252").decode(b.subarray(body, body + len));
  const walk = (start, end, inList, inSlide) => {
    let p = start;
    while (p + 8 <= end) {
      const verInst = dv.getUint16(p, true), type = dv.getUint16(p + 2, true), len = dv.getUint32(p + 4, true);
      const body = p + 8;
      if (body + len > end) break;
      if (type === 0xF007) { // FBSE: un'immagine del BStore
        const cbName = b[body + 33], foDelay = dv.getUint32(body + 28, true);
        bstore.push({ inner: len > 36 + cbName ? [body + 36 + cbName, body + len] : null, foDelay });
      } else if ((verInst & 0xF) === 0xF) {
        if (type === 0x03F0 || type === 0x03F8 || type === 0x0FF0 && (verInst >> 4) !== 0) { p = body + len; continue; }
        let s2 = inSlide;
        if (type === 0x03EE) { curS = []; fromSlides.push(curS); curImg = []; slideImgs.push(curImg); s2 = true; }
        walk(body, body + len, inList || type === 0x0FF0, s2);
      } else if (inList && type === 0x03F3) { curL = []; fromList.push(curL); }
      else if (type === 0x0FA0 || type === 0x0FA8) {
        const t = rd(type, body, len).replace(/\r/g, "\n").replace(/\x0B/g, "\n");
        if (inList && curL) curL.push(t); else if (inSlide && curS) curS.push(t);
      } else if (type === 0xF00B && inSlide && curImg) { // OfficeArtFOPT: proprieta' della forma
        const n = verInst >> 4;
        for (let i = 0; i < n && body + i * 6 + 6 <= body + len; i++) {
          const id = dv.getUint16(body + i * 6, true) & 0x3FFF, v = dv.getUint32(body + i * 6 + 2, true);
          if (id === 0x0104 && v > 0) curImg.push(v); // pib: immagine (1 = la prima del BStore)
        }
      }
      p = body + len;
    }
  };
  walk(0, b.length, false, false);
  const blipUrl = new Map();
  const blip = pib => {
    if (blipUrl.has(pib)) return blipUrl.get(pib);
    let u = null;
    const e = bstore[pib - 1];
    const src = e && (e.inner ? b : pics);
    const at = e && (e.inner ? e.inner[0] : e.foDelay);
    if (src && at != null && at + 8 < src.length) {
      const type = new DataView(src.buffer, src.byteOffset, src.byteLength).getUint16(at + 2, true);
      const len = new DataView(src.buffer, src.byteOffset, src.byteLength).getUint32(at + 4, true);
      const end = Math.min(src.length, at + 8 + len);
      const mime = { 0xF01D: "image/jpeg", 0xF02A: "image/jpeg", 0xF01E: "image/png", 0xF01F: "image/bmp", 0xF029: "image/tiff" }[type];
      if (mime && mime !== "image/tiff") {
        // dopo l'intestazione: 1 o 2 UID da 16 byte e un byte "tag"; cerco la firma vera del file
        for (let k = at + 8; k < Math.min(end, at + 8 + 60); k++) {
          const png = src[k] === 0x89 && src[k + 1] === 0x50 && src[k + 2] === 0x4E, jpg = src[k] === 0xFF && src[k + 1] === 0xD8 && src[k + 2] === 0xFF;
          if (png || jpg) { u = URL.createObjectURL(new Blob([src.subarray(k, end)], { type: png ? "image/png" : "image/jpeg" })); break; }
        }
        if (!u && mime === "image/bmp") { // DIB senza intestazione di file: la aggiungo (14 byte)
          const k = at + 8 + ((dv.getUint16(at, true) >> 4) & 1 ? 33 : 17);
          const dib = src.subarray(k, end), hdr = new Uint8Array(14), hv = new DataView(hdr.buffer);
          hdr[0] = 0x42; hdr[1] = 0x4D; hv.setUint32(2, 14 + dib.length, true); hv.setUint32(10, 14 + new DataView(dib.buffer, dib.byteOffset).getUint32(0, true) + (dib[14] <= 8 ? 4 << dib[14] : 0), true);
          u = URL.createObjectURL(new Blob([hdr, dib], { type: "image/bmp" }));
        }
      }
    }
    blipUrl.set(pib, u);
    return u;
  };
  const count = l => l.reduce((n, s) => n + s.join("").length, 0);
  const useSlides = count(fromSlides) > count(fromList);
  const slides = useSlides ? fromSlides : fromList;
  const nSlides = Math.max(slides.length, slideImgs.filter(x => x.length).length ? slideImgs.length : 0);
  if (!slides.some(s => s.length) && !slideImgs.some(x => x.length)) throw Object.assign(new Error("No text or images found in this PowerPoint 97-2003 presentation."), { user: true });
  let nImg = 0;
  for (let i = 0; i < nSlides; i++) {
    const s = slides[i] || [];
    const box = util.el("div", { class: "slide-text" }, util.el("h3", { text: `Slide ${i + 1}` }));
    s.forEach((t, k) => box.append(util.el("p", { style: k === 0 ? { fontWeight: "700", fontSize: "1.15em" } : { whiteSpace: "pre-wrap" }, text: t })));
    // le immagini si legano alle diapositive vere (SlideContainer) nello stesso ordine
    for (const pib of new Set(slideImgs[i] || [])) {
      const u = blip(pib);
      if (u) { box.append(util.el("img", { class: "slide-pic", src: u, alt: "" })); nImg++; }
    }
    root.append(box);
  }
  util.notice("PowerPoint 97-2003: text and images of each slide (without layout)" + (nImg ? "" : ""));
}
