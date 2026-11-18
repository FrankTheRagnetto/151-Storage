// Diagrammi: draw.io / diagrams.net (.drawio, .dio, anche compressi) ed Excalidraw (.excalidraw e le note
// .excalidraw.md del plugin di Obsidian, anche "compressed-json"). Disegnati qui in SVG, niente librerie:
// forme (rettangoli, arrotondati, ellissi, rombi, cilindri, testo, corsie, immagini incorporate), frecce
// con i punti intermedi, etichette. Excalidraw esce con tratto pulito (non "a mano libera").
const NS = "http://www.w3.org/2000/svg";

export async function render(ctx) {
  const { root, util, fmt } = ctx;
  await util.need("purify");
  const text = util.decodeText(ctx.bytes);
  const box = util.el("div", { class: "diagram" });
  root.append(box);
  util.mode("page", innerWidth || 412);
  document.body.classList.add("page-mode");
  if (fmt === "excalidraw") {
    const scene = excalidrawScene(text);
    const n = scene.elements.filter(e => !e.isDeleted).length;
    util.A.onFormat(`Excalidraw · ${n} element${n === 1 ? "" : "s"}`);
    box.append(excalidraw(scene, util));
    return;
  }
  const pages = await drawioPages(text);
  if (!pages.length) throw Object.assign(new Error("There's no diagram in this draw.io file."), { user: true });
  util.A.onFormat(`draw.io · ${pages.length} ${pages.length === 1 ? "page" : "pages"}`);
  for (const p of pages) {
    if (pages.length > 1) box.append(util.el("h2", { class: "diagram-title", text: p.name }));
    box.append(drawio(p.model, util));
  }
}

// ------------------------------------------------------------------ draw.io
async function inflateB64(s) {
  const bin = Uint8Array.from(atob(s.replace(/\s+/g, "")), c => c.charCodeAt(0));
  const ds = new DecompressionStream("deflate-raw");
  const out = await new Response(new Blob([bin]).stream().pipeThrough(ds)).arrayBuffer();
  return new TextDecoder().decode(out);
}
async function drawioPages(text) {
  const d = new DOMParser().parseFromString(text, "application/xml");
  if (d.getElementsByTagName("parsererror").length) throw Object.assign(new Error("Invalid draw.io file (broken XML)."), { user: true });
  const root = d.documentElement;
  if (root.localName === "mxGraphModel") return [{ name: "Page 1", model: root }];
  const out = [];
  for (const [i, dg] of [...d.getElementsByTagName("diagram")].entries()) {
    let model = dg.getElementsByTagName("mxGraphModel")[0];
    if (!model && dg.textContent.trim()) {
      try {
        let x = await inflateB64(dg.textContent.trim());
        try { x = decodeURIComponent(x); } catch {}
        model = new DOMParser().parseFromString(x, "application/xml").documentElement;
      } catch (e) { console.warn("draw.io page can't be decoded", e); }
    }
    if (model && model.localName === "mxGraphModel") out.push({ name: dg.getAttribute("name") || `Page ${i + 1}`, model });
  }
  return out;
}
function styleOf(s) {
  const st = {};
  for (const part of (s || "").split(";")) {
    if (!part) continue;
    const i = part.indexOf("=");
    if (i < 0) st.shape = st.shape || part; else st[part.slice(0, i)] = part.slice(i + 1);
  }
  return st;
}
function drawio(model, util) {
  const cells = new Map();
  for (const c of model.getElementsByTagName("mxCell")) cells.set(c.getAttribute("id"), c);
  // UserObject / object: l'mxCell sta dentro, l'etichetta e' nell'attributo label
  for (const u of [...model.getElementsByTagName("UserObject"), ...model.getElementsByTagName("object")]) {
    const c = u.getElementsByTagName("mxCell")[0];
    if (c) { c.setAttribute("id", u.getAttribute("id")); if (!c.getAttribute("value")) c.setAttribute("value", u.getAttribute("label") || ""); cells.set(u.getAttribute("id"), c); }
  }
  const geo = c => c.getElementsByTagName("mxGeometry")[0];
  const abs = new Map();
  const absOf = id => {
    if (abs.has(id)) return abs.get(id);
    const c = cells.get(id);
    let r = { x: 0, y: 0, w: 0, h: 0 };
    if (c && c.getAttribute("vertex") === "1") {
      const g = geo(c);
      const p = c.getAttribute("parent") && cells.get(c.getAttribute("parent"))?.getAttribute("vertex") === "1" ? absOf(c.getAttribute("parent")) : { x: 0, y: 0 };
      r = { x: p.x + +(g?.getAttribute("x") || 0), y: p.y + +(g?.getAttribute("y") || 0), w: +(g?.getAttribute("width") || 0), h: +(g?.getAttribute("height") || 0) };
    }
    abs.set(id, r);
    return r;
  };
  const verts = [...cells.values()].filter(c => c.getAttribute("vertex") === "1");
  const edges = [...cells.values()].filter(c => c.getAttribute("edge") === "1");
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  const grow = (x, y) => { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); };
  for (const v of verts) { const r = absOf(v.getAttribute("id")); grow(r.x, r.y); grow(r.x + r.w, r.y + r.h); }
  const edgePts = new Map();
  for (const e of edges) {
    const g = geo(e);
    const src = e.getAttribute("source") && absOf(e.getAttribute("source")), tgt = e.getAttribute("target") && absOf(e.getAttribute("target"));
    const pt = as => { const p = g && [...g.getElementsByTagName("mxPoint")].find(m => m.getAttribute("as") === as); return p ? { x: +p.getAttribute("x") || 0, y: +p.getAttribute("y") || 0 } : null; };
    const mid = g ? [...(g.getElementsByTagName("Array")[0]?.getElementsByTagName("mxPoint") || [])].map(p => ({ x: +p.getAttribute("x") || 0, y: +p.getAttribute("y") || 0 })) : [];
    const st = styleOf(e.getAttribute("style"));
    const c = r => ({ x: r.x + r.w / 2, y: r.y + r.h / 2 });
    let a = src && src.w ? c(src) : pt("sourcePoint"), b = tgt && tgt.w ? c(tgt) : pt("targetPoint");
    if (!a || !b) continue;
    let pts = [a, ...mid, b];
    if (!mid.length && /orthogonal|elbow/i.test(st.edgeStyle || "")) {
      if (Math.abs(a.x - b.x) > Math.abs(a.y - b.y)) { const mx = (a.x + b.x) / 2; pts = [a, { x: mx, y: a.y }, { x: mx, y: b.y }, b]; }
      else { const my = (a.y + b.y) / 2; pts = [a, { x: a.x, y: my }, { x: b.x, y: my }, b]; }
    }
    if (src && src.w) pts[0] = clipRect(pts[1], src);
    if (tgt && tgt.w) pts[pts.length - 1] = clipRect(pts[pts.length - 2], tgt);
    pts.forEach(p => grow(p.x, p.y));
    edgePts.set(e, pts);
  }
  if (!isFinite(x0)) { x0 = y0 = 0; x1 = y1 = 100; }
  const pad = 20;
  const svg = document.createElementNS(NS, "svg");
  const W = x1 - x0 + 2 * pad, H = y1 - y0 + 2 * pad;
  svg.setAttribute("viewBox", `${x0 - pad} ${y0 - pad} ${W} ${H}`);
  svg.setAttribute("class", "diagram-svg");
  svg.style.maxWidth = Math.max(W, 320) + "px";
  const defs = document.createElementNS(NS, "defs");
  defs.innerHTML = `<marker id="dgArrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse" markerUnits="userSpaceOnUse"><path d="M0,0 L10,5 L0,10 z" fill="#333"/></marker>`;
  svg.append(defs, bg(x0 - pad, y0 - pad, W, H));
  const el = (tag, a, parent = svg) => { const e = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(a)) if (v != null) e.setAttribute(k, v); parent.append(e); return e; };
  const col = (v, dflt) => !v || v === "default" ? dflt : v === "none" ? "none" : v;
  // prima i contenitori (disegnati in ordine di documento), poi le frecce, poi le etichette
  for (const v of verts) {
    const st = styleOf(v.getAttribute("style"));
    const r = absOf(v.getAttribute("id"));
    const shape = (st.shape || "").replace(/^mxgraph\.[^.]+\./, "");
    const isText = shape === "text" || st.text != null || (st.fillColor == null && st.strokeColor === "none");
    const fill = col(st.fillColor, isText ? "none" : "#ffffff"), stroke = col(st.strokeColor, isText ? "none" : "#000000");
    const common = { fill, stroke, "stroke-width": st.strokeWidth || 1, "stroke-dasharray": st.dashed === "1" ? "6 4" : null, opacity: st.opacity ? +st.opacity / 100 : null,
      transform: st.rotation ? `rotate(${st.rotation} ${r.x + r.w / 2} ${r.y + r.h / 2})` : null };
    if (shape === "image" || st.image) {
      let href = st.image || "";
      if (/^data:image\/[\w+]+,(?!.*;base64)/.test(href)) href = href.replace(/^(data:image\/[\w+]+),/, "$1;base64,");
      if (/^data:/.test(href)) el("image", { href, x: r.x, y: r.y, width: r.w, height: r.h, preserveAspectRatio: "xMidYMid meet" });
      else el("rect", { ...common, x: r.x, y: r.y, width: r.w, height: r.h, fill: "#eee", stroke: "#999", "stroke-dasharray": "4 3" });
    } else if (shape === "ellipse" || shape === "doubleEllipse" || st.ellipse != null) el("ellipse", { ...common, cx: r.x + r.w / 2, cy: r.y + r.h / 2, rx: r.w / 2, ry: r.h / 2 });
    else if (shape === "rhombus") el("polygon", { ...common, points: `${r.x + r.w / 2},${r.y} ${r.x + r.w},${r.y + r.h / 2} ${r.x + r.w / 2},${r.y + r.h} ${r.x},${r.y + r.h / 2}` });
    else if (shape === "triangle") el("polygon", { ...common, points: `${r.x},${r.y} ${r.x + r.w},${r.y + r.h / 2} ${r.x},${r.y + r.h}` });
    else if (/^cylinder/.test(shape)) { const ry = Math.min(r.h / 4, 15); el("path", { ...common, d: `M${r.x},${r.y + ry} a${r.w / 2},${ry} 0 0 0 ${r.w},0 a${r.w / 2},${ry} 0 0 0 ${-r.w},0 v${r.h - 2 * ry} a${r.w / 2},${ry} 0 0 0 ${r.w},0 v${-(r.h - 2 * ry)}` }); }
    else if (shape === "swimlane") { const hh = +(st.startSize || 23); el("rect", { ...common, x: r.x, y: r.y, width: r.w, height: r.h }); el("line", { x1: r.x, y1: r.y + hh, x2: r.x + r.w, y2: r.y + hh, stroke }); }
    else if (shape === "line") el("line", { x1: r.x, y1: r.y + r.h / 2, x2: r.x + r.w, y2: r.y + r.h / 2, stroke: stroke === "none" ? "#000" : stroke, "stroke-width": st.strokeWidth || 1 });
    else if (shape === "hexagon") { const k = r.w / 4; el("polygon", { ...common, points: `${r.x + k},${r.y} ${r.x + r.w - k},${r.y} ${r.x + r.w},${r.y + r.h / 2} ${r.x + r.w - k},${r.y + r.h} ${r.x + k},${r.y + r.h} ${r.x},${r.y + r.h / 2}` }); }
    else if (!isText) el("rect", { ...common, x: r.x, y: r.y, width: r.w, height: r.h, rx: st.rounded === "1" ? Math.min(r.w, r.h) * (+(st.arcSize || 15) / 100) : null });
  }
  for (const [e, pts] of edgePts) {
    const st = styleOf(e.getAttribute("style"));
    const stroke = col(st.strokeColor, "#000000");
    const p = el("polyline", { points: pts.map(q => `${q.x},${q.y}`).join(" "), fill: "none", stroke, "stroke-width": st.strokeWidth || 1,
      "stroke-dasharray": st.dashed === "1" ? "6 4" : null });
    if (st.endArrow !== "none") p.setAttribute("marker-end", "url(#dgArrow)");
    if (st.startArrow && st.startArrow !== "none") p.setAttribute("marker-start", "url(#dgArrow)");
  }
  const label = (value, r, st, center) => {
    if (!value || !value.trim()) return;
    const html = st.html === "1" || /<[a-z][\s\S]*>/i.test(value);
    const fo = el("foreignObject", { x: r.x - 2, y: r.y - 2, width: Math.max(r.w + 4, 40), height: Math.max(r.h + 4, 20), overflow: "visible" });
    const div = document.createElement("div");
    const va = st.verticalAlign || (st.shape === "swimlane" ? "top" : "middle");
    div.setAttribute("style", `width:100%;height:100%;display:flex;flex-direction:column;justify-content:${va === "top" ? "flex-start" : va === "bottom" ? "flex-end" : "center"};` +
      `text-align:${st.align || "center"};font:${st.fontStyle & 1 ? "bold " : ""}${+(st.fontSize || 12)}px ${st.fontFamily || "Helvetica, Arial, sans-serif"};color:${col(st.fontColor, "#000")};` +
      `overflow-wrap:break-word;line-height:1.2;${st.fontStyle & 2 ? "font-style:italic;" : ""}${center ? "background:rgba(255,255,255,.85);" : ""}`);
    const inner = document.createElement("div");
    if (html) inner.innerHTML = DOMPurify.sanitize(value, { ALLOWED_TAGS: ["b", "i", "u", "br", "div", "span", "p", "font", "strong", "em", "sub", "sup", "ul", "ol", "li", "table", "tr", "td", "th", "tbody", "hr"], ALLOWED_ATTR: ["style", "color"] });
    else inner.textContent = value;
    div.append(inner);
    fo.append(div);
  };
  for (const v of verts) { const st = styleOf(v.getAttribute("style")); const r = absOf(v.getAttribute("id")); label(v.getAttribute("value"), st.shape === "swimlane" ? { ...r, h: +(st.startSize || 23) } : r, st); }
  for (const [e, pts] of edgePts) {
    const st = styleOf(e.getAttribute("style"));
    const m = pts[Math.floor((pts.length - 1) / 2)], n = pts[Math.floor((pts.length - 1) / 2) + 1] || m;
    const cx = (m.x + n.x) / 2, cy = (m.y + n.y) / 2;
    label(e.getAttribute("value"), { x: cx - 60, y: cy - 10, w: 120, h: 20 }, st, true);
  }
  const wrap = util.el("div", { class: "diagram-page" });
  wrap.append(svg);
  return wrap;
}
function clipRect(from, r) {
  const cx = r.x + r.w / 2, cy = r.y + r.h / 2;
  const dx = from.x - cx, dy = from.y - cy;
  if (!dx && !dy) return { x: cx, y: cy };
  const s = Math.min(Math.abs((r.w / 2) / (dx || 1e-9)), Math.abs((r.h / 2) / (dy || 1e-9)));
  return { x: cx + dx * Math.min(1, s), y: cy + dy * Math.min(1, s) };
}
function bg(x, y, w, h) { const r = document.createElementNS(NS, "rect"); r.setAttribute("x", x); r.setAttribute("y", y); r.setAttribute("width", w); r.setAttribute("height", h); r.setAttribute("fill", "#ffffff"); return r; }

// ------------------------------------------------------------------ Excalidraw
function excalidrawScene(text) {
  const t = text.trimStart();
  if (t.startsWith("{")) return JSON.parse(text);
  // nota Obsidian (.excalidraw.md): blocco ```json … ``` o ```compressed-json … ``` sotto "# Drawing"
  let m = /```compressed-json\s*\n([\s\S]*?)```/.exec(text);
  if (m) { const j = lzDecompressB64(m[1].replace(/\s+/g, "")); if (j) return JSON.parse(j); }
  m = /```json\s*\n([\s\S]*?)```/.exec(text);
  if (m) return JSON.parse(m[1]);
  m = /%%\s*\n?#+\s*Drawing[\s\S]*?(\{[\s\S]*\})/.exec(text);
  if (m) return JSON.parse(m[1]);
  throw Object.assign(new Error("Can't find the drawing in this Excalidraw note."), { user: true });
}
function excalidraw(scene, util) {
  const els = (scene.elements || []).filter(e => !e.isDeleted);
  const files = scene.files || {};
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const e of els) {
    const pts = e.points && e.points.length ? e.points.map(([px, py]) => [e.x + px, e.y + py]) : [[e.x, e.y], [e.x + (e.width || 0), e.y + (e.height || 0)]];
    for (const [x, y] of pts) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
  }
  if (!isFinite(x0)) { x0 = y0 = 0; x1 = y1 = 100; }
  const pad = 24, W = x1 - x0 + 2 * pad, H = y1 - y0 + 2 * pad;
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", `${x0 - pad} ${y0 - pad} ${W} ${H}`);
  svg.setAttribute("class", "diagram-svg");
  svg.style.maxWidth = Math.max(W, 320) + "px";
  const defs = document.createElementNS(NS, "defs");
  defs.innerHTML = `<marker id="exArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="14" markerHeight="14" orient="auto-start-reverse" markerUnits="userSpaceOnUse"><path d="M0,0 L10,5 L0,10" fill="none" stroke="#1e1e1e" stroke-width="1.5"/></marker>`;
  svg.append(defs);
  const b = bg(x0 - pad, y0 - pad, W, H);
  b.setAttribute("fill", scene.appState?.viewBackgroundColor || "#ffffff");
  svg.append(b);
  const FONTS = { 1: "'Virgil','Comic Sans MS','Segoe Print',cursive", 2: "Helvetica, Arial, sans-serif", 3: "'Cascadia Code', monospace", 5: "'Excalifont','Comic Sans MS',cursive", 6: "Nunito, sans-serif", 7: "'Lilita One', sans-serif", 8: "'Comic Shanns', monospace" };
  let hatch = 0;
  for (const e of els) {
    const g = document.createElementNS(NS, "g");
    if (e.angle) g.setAttribute("transform", `rotate(${e.angle * 180 / Math.PI} ${e.x + e.width / 2} ${e.y + e.height / 2})`);
    if (e.opacity != null && e.opacity < 100) g.setAttribute("opacity", e.opacity / 100);
    svg.append(g);
    const add = (tag, a) => { const n = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(a)) if (v != null) n.setAttribute(k, v); g.append(n); return n; };
    const stroke = e.strokeColor || "#1e1e1e";
    let fill = !e.backgroundColor || e.backgroundColor === "transparent" ? "none" : e.backgroundColor;
    if (fill !== "none" && e.fillStyle && e.fillStyle !== "solid") {
      const id = "exH" + ++hatch;
      const p = document.createElementNS(NS, "pattern");
      p.setAttribute("id", id); p.setAttribute("patternUnits", "userSpaceOnUse"); p.setAttribute("width", 10); p.setAttribute("height", 10); p.setAttribute("patternTransform", "rotate(-45)");
      p.innerHTML = `<line x1="0" y1="0" x2="0" y2="10" stroke="${fill}" stroke-width="3"/>` + (e.fillStyle === "cross-hatch" ? `<line x1="0" y1="0" x2="10" y2="0" stroke="${fill}" stroke-width="3"/>` : "");
      defs.append(p);
      fill = `url(#${id})`;
    }
    const common = { stroke, fill, "stroke-width": e.strokeWidth || 1, "stroke-dasharray": e.strokeStyle === "dashed" ? "8 6" : e.strokeStyle === "dotted" ? "1.5 5" : null, "stroke-linecap": "round", "stroke-linejoin": "round" };
    switch (e.type) {
      case "rectangle": case "frame": case "magicframe":
        add("rect", { ...common, x: e.x, y: e.y, width: e.width, height: e.height, rx: e.roundness ? Math.min(e.width, e.height) * 0.12 : null });
        if (e.type !== "rectangle" && e.name) { const t = add("text", { x: e.x, y: e.y - 6, "font-size": 14, fill: "#888" }); t.textContent = e.name; }
        break;
      case "ellipse": add("ellipse", { ...common, cx: e.x + e.width / 2, cy: e.y + e.height / 2, rx: e.width / 2, ry: e.height / 2 }); break;
      case "diamond": add("polygon", { ...common, points: `${e.x + e.width / 2},${e.y} ${e.x + e.width},${e.y + e.height / 2} ${e.x + e.width / 2},${e.y + e.height} ${e.x},${e.y + e.height / 2}` }); break;
      case "line": case "arrow": {
        const pts = (e.points || []).map(([px, py]) => `${e.x + px},${e.y + py}`).join(" ");
        const pl = add(e.type === "line" && e.polygon ? "polygon" : "polyline", { ...common, fill: e.type === "line" && e.polygon ? common.fill : "none", points: pts });
        if (e.type === "arrow" && e.endArrowhead !== null) pl.setAttribute("marker-end", "url(#exArrow)");
        if (e.startArrowhead) pl.setAttribute("marker-start", "url(#exArrow)");
        break;
      }
      case "freedraw": {
        const d = (e.points || []).map(([px, py], i) => `${i ? "L" : "M"}${e.x + px} ${e.y + py}`).join(" ");
        add("path", { d, stroke, fill: "none", "stroke-width": (e.strokeWidth || 1) * 1.5, "stroke-linecap": "round", "stroke-linejoin": "round" });
        break;
      }
      case "image": {
        const f = files[e.fileId];
        if (f && /^data:image\//.test(f.dataURL || "")) add("image", { href: f.dataURL, x: e.x, y: e.y, width: e.width, height: e.height, preserveAspectRatio: "none" });
        else add("rect", { x: e.x, y: e.y, width: e.width, height: e.height, fill: "#eee", stroke: "#aaa", "stroke-dasharray": "4 3" });
        break;
      }
      case "text": {
        const fs = e.fontSize || 20, lh = (e.lineHeight || 1.25) * fs;
        const anchor = e.textAlign === "center" ? "middle" : e.textAlign === "right" ? "end" : "start";
        const tx = anchor === "middle" ? e.x + e.width / 2 : anchor === "end" ? e.x + e.width : e.x;
        const t = add("text", { x: tx, y: e.y, "font-size": fs, "font-family": FONTS[e.fontFamily] || FONTS[1], fill: stroke, "text-anchor": anchor });
        String(e.text ?? e.originalText ?? "").split("\n").forEach((line, i) => {
          const s = document.createElementNS(NS, "tspan");
          s.setAttribute("x", tx); s.setAttribute("y", e.y + fs * 0.9 + i * lh); s.textContent = line || " ";
          t.append(s);
        });
        break;
      }
    }
  }
  const wrap = util.el("div", { class: "diagram-page" });
  wrap.append(svg);
  return wrap;
}
// LZString.decompressFromBase64 (MIT, pieroxy) riscritto qui: serve alle note Excalidraw di Obsidian
function lzDecompressB64(input) {
  const KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
  const rev = {}; for (let i = 0; i < KEY.length; i++) rev[KEY[i]] = i;
  const length = input.length, resetValue = 32, getNext = i => rev[input.charAt(i)];
  const dict = [0, 1, 2]; let enlargeIn = 4, dictSize = 4, numBits = 3, entry = "", result = [], w, c;
  const data = { val: getNext(0), position: resetValue, index: 1 };
  const bits = n => {
    let r = 0, p = 1;
    while (p !== 1 << n) {
      const resb = data.val & data.position;
      data.position >>= 1;
      if (data.position === 0) { data.position = resetValue; data.val = getNext(data.index++); }
      r |= (resb > 0 ? 1 : 0) * p; p <<= 1;
    }
    return r;
  };
  switch (bits(2)) { case 0: c = String.fromCharCode(bits(8)); break; case 1: c = String.fromCharCode(bits(16)); break; case 2: return ""; }
  dict[3] = c; w = c; result.push(c);
  for (;;) {
    if (data.index > length) return "";
    let cc = bits(numBits);
    switch (cc) {
      case 0: dict[dictSize++] = String.fromCharCode(bits(8)); cc = dictSize - 1; enlargeIn--; break;
      case 1: dict[dictSize++] = String.fromCharCode(bits(16)); cc = dictSize - 1; enlargeIn--; break;
      case 2: return result.join("");
    }
    if (enlargeIn === 0) { enlargeIn = 2 ** numBits; numBits++; }
    if (dict[cc]) entry = dict[cc]; else if (cc === dictSize) entry = w + w.charAt(0); else return null;
    result.push(entry);
    dict[dictSize++] = w + entry.charAt(0);
    enlargeIn--;
    w = entry;
    if (enlargeIn === 0) { enlargeIn = 2 ** numBits; numBits++; }
  }
}
