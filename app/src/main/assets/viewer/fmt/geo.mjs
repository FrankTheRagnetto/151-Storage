// Dati geografici: GPX (tracce di GPS/orologi), KML e KMZ (Google Earth / My Maps), GeoJSON.
// Niente mappe: le mappe vorrebbero la rete. Si mostrano un riepilogo (punti, tracce, lunghezza, dislivello,
// tempi), il DISEGNO della traccia (SVG, proiezione semplice, niente sfondo), il profilo altimetrico,
// l'elenco dei luoghi con nome, e sotto il file originale formattato.
export async function render(ctx) {
  const { root, util, zip, name } = ctx;
  let text, kind;
  if (zip) { // KMZ
    const kml = zip.file("doc.kml") || Object.values(zip.files).find(f => /\.kml$/i.test(f.name) && !f.dir);
    if (!kml) throw Object.assign(new Error("KMZ without a KML file inside."), { user: true });
    text = util.decodeText(await kml.async("uint8array")); kind = "KMZ";
  } else text = util.decodeText(ctx.bytes);
  const t = text.trimStart();
  let g;
  if (t.startsWith("{") || t.startsWith("[")) { kind = "GeoJSON"; g = geojson(JSON.parse(text)); }
  else {
    const d = new DOMParser().parseFromString(text, "application/xml");
    if (d.getElementsByTagName("parsererror").length) throw Object.assign(new Error("Invalid map file (broken XML)."), { user: true });
    if (d.getElementsByTagNameNS("*", "gpx").length) { kind = "GPX"; g = gpx(d); } else { kind = kind || "KML"; g = kml(d); }
  }
  util.A.onFormat(`${kind} · ${g.lines.length} ${g.lines.length === 1 ? "track" : "tracks"}, ${g.points.length} point${g.points.length === 1 ? "" : "s"}`);
  const box = util.el("div", { class: "geo" });
  root.append(box);
  if (g.name) box.append(util.el("h2", { class: "geo-title", text: g.name }));
  // riepilogo
  const all = g.lines.flat();
  const km = g.lines.reduce((s, l) => s + length(l), 0);
  const ele = all.map(p => p.ele).filter(v => v != null && isFinite(v));
  let up = 0, down = 0;
  for (const l of g.lines) for (let i = 1; i < l.length; i++) { const a = l[i - 1].ele, b = l[i].ele; if (a != null && b != null) { if (b > a) up += b - a; else down += a - b; } }
  const times = all.map(p => p.time).filter(Boolean).sort((a, b) => a - b);
  const kv = (k, v) => v && card.append(util.el("div", { class: "kv" }, util.el("b", { text: k }), util.el("span", { text: v })));
  const card = util.el("div", { class: "card" });
  box.append(card);
  const nf = (x, d = 1) => x.toLocaleString("en-GB", { maximumFractionDigits: d, minimumFractionDigits: d });
  kv("Tracks", g.lines.length ? `${g.lines.length} (${all.length.toLocaleString("en-GB")} points)` : "");
  kv("Length", km ? `${nf(km, km < 10 ? 2 : 1)} km` : "");
  kv("Elevation gain/loss", ele.length > 1 ? `+${Math.round(up)} m / −${Math.round(down)} m` : "");
  kv("Elevation", ele.length ? `min ${Math.round(Math.min(...ele))} m · max ${Math.round(Math.max(...ele))} m` : "");
  if (times.length > 1) {
    const dur = (times[times.length - 1] - times[0]) / 1000;
    kv("Start", times[0].toLocaleString("en-GB", { dateStyle: "full", timeStyle: "short" }));
    kv("Duration", `${Math.floor(dur / 3600)} h ${String(Math.floor(dur / 60) % 60).padStart(2, "0")} min`);
    if (km && dur) kv("Average", `${nf(km / (dur / 3600))} km/h`);
  }
  kv("Places", g.points.length ? String(g.points.length) : "");
  kv("Polygons", g.polys.length ? String(g.polys.length) : "");
  const bb = bbox([...all, ...g.points, ...g.polys.flat()]);
  if (bb) kv("Area", `${fmtLat(bb.s)} – ${fmtLat(bb.n)}, ${fmtLon(bb.w)} – ${fmtLon(bb.e)}`);
  // disegno
  if (bb) box.append(sketch(g, bb, util));
  if (ele.length > 2 && g.lines.length) box.append(profile(g.lines, util));
  // luoghi con nome
  const named = g.points.filter(p => p.name);
  if (named.length) {
    const l = util.el("div", { class: "list geo-list" }, util.el("h2", { text: `Places (${named.length})` }));
    for (const p of named.slice(0, 500)) l.append(util.el("div", { class: "entry" }, util.el("span", { class: "ico", style: { background: "#1a73e8" }, text: "•" }),
      util.el("span", { class: "nm", text: p.name + (p.desc ? " — " + p.desc.replace(/<[^>]+>/g, " ").trim().slice(0, 160) : "") }),
      util.el("span", { class: "sz", text: `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}` })));
    box.append(l);
  }
  // sorgente formattato
  await util.need("hljs");
  const det = util.el("details", { class: "geo-src" }, util.el("summary", { text: "Original data" }));
  const pre = util.el("pre", { class: "hljs nb-text" });
  const src = kind === "GeoJSON" ? JSON.stringify(JSON.parse(text), null, 2) : text;
  if (src.length < 800000) try { pre.innerHTML = hljs.highlight(src, { language: kind === "GeoJSON" ? "json" : "xml", ignoreIllegals: true }).value; } catch { pre.textContent = src; }
  else pre.textContent = src.slice(0, 800000) + "\n…";
  det.append(pre);
  box.append(det);
  util.notice("No map (it would need internet): track drawing and data");
}

const num = v => v == null || v === "" ? null : Number(v);
function gpx(d) {
  const q = (el, n) => [...el.getElementsByTagNameNS("*", n)];
  const pt = e => ({ lat: +e.getAttribute("lat"), lon: +e.getAttribute("lon"), ele: num(q(e, "ele")[0]?.textContent), time: q(e, "time")[0] ? new Date(q(e, "time")[0].textContent) : null, name: q(e, "name")[0]?.textContent.trim(), desc: q(e, "desc")[0]?.textContent });
  const lines = [...q(d, "trkseg").map(s => q(s, "trkpt").map(pt)), ...q(d, "rte").map(r => q(r, "rtept").map(pt))].filter(l => l.length);
  const name = q(d, "metadata")[0] && q(q(d, "metadata")[0], "name")[0]?.textContent || q(d, "trk")[0] && q(q(d, "trk")[0], "name")[0]?.textContent;
  return { name, lines, points: q(d, "wpt").map(pt), polys: [] };
}
function kml(d) {
  const q = (el, n) => [...el.getElementsByTagNameNS("*", n)];
  const coords = s => (s || "").trim().split(/\s+/).map(c => c.split(",").map(Number)).filter(a => a.length >= 2 && a.every(isFinite)).map(([lon, lat, ele]) => ({ lat, lon, ele: ele ?? null }));
  const lines = [], points = [], polys = [];
  for (const pm of q(d, "Placemark")) {
    const name = [...pm.children].find(c => c.localName === "name")?.textContent.trim();
    const desc = [...pm.children].find(c => c.localName === "description")?.textContent;
    for (const p of q(pm, "Point")) { const c = coords(q(p, "coordinates")[0]?.textContent)[0]; if (c) points.push({ ...c, name, desc }); }
    for (const l of q(pm, "LineString")) lines.push(coords(q(l, "coordinates")[0]?.textContent));
    for (const p of q(pm, "Polygon")) polys.push(coords(q(q(p, "outerBoundaryIs")[0] || p, "coordinates")[0]?.textContent));
    for (const tr of q(pm, "Track")) { // gx:Track
      const when = q(tr, "when").map(w => new Date(w.textContent));
      lines.push(q(tr, "coord").map((c, i) => { const [lon, lat, ele] = c.textContent.trim().split(/\s+/).map(Number); return { lat, lon, ele: ele ?? null, time: when[i] || null }; }));
    }
  }
  const docName = q(d, "Document")[0] && [...q(d, "Document")[0].children].find(c => c.localName === "name")?.textContent;
  return { name: docName, lines: lines.filter(l => l.length > 1), points, polys: polys.filter(p => p.length) };
}
function geojson(j) {
  const lines = [], points = [], polys = [];
  const P = (c, props) => ({ lon: c[0], lat: c[1], ele: c[2] ?? null, name: props?.name || props?.title || props?.Name, desc: props?.description });
  const geom = (g, props) => {
    if (!g) return;
    const c = g.coordinates;
    switch (g.type) {
      case "Point": points.push(P(c, props)); break;
      case "MultiPoint": c.forEach(x => points.push(P(x, props))); break;
      case "LineString": lines.push(c.map(x => P(x))); break;
      case "MultiLineString": c.forEach(l => lines.push(l.map(x => P(x)))); break;
      case "Polygon": polys.push(c[0].map(x => P(x))); break;
      case "MultiPolygon": c.forEach(p => polys.push(p[0].map(x => P(x)))); break;
      case "GeometryCollection": g.geometries.forEach(x => geom(x, props)); break;
    }
  };
  const feats = j.type === "FeatureCollection" ? j.features : j.type === "Feature" ? [j] : [{ geometry: j }];
  for (const f of feats || []) geom(f.geometry, f.properties);
  return { name: j.name, lines, points, polys };
}

function hav(a, b) {
  const R = 6371, r = Math.PI / 180;
  const dl = (b.lat - a.lat) * r, dn = (b.lon - a.lon) * r;
  const x = Math.sin(dl / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(x)));
}
const length = l => { let s = 0; for (let i = 1; i < l.length; i++) s += hav(l[i - 1], l[i]); return s; };
function bbox(ps) {
  const v = ps.filter(p => isFinite(p.lat) && isFinite(p.lon));
  if (!v.length) return null;
  return { s: Math.min(...v.map(p => p.lat)), n: Math.max(...v.map(p => p.lat)), w: Math.min(...v.map(p => p.lon)), e: Math.max(...v.map(p => p.lon)) };
}
const fmtLat = v => `${Math.abs(v).toFixed(4)}° ${v >= 0 ? "N" : "S"}`;
const fmtLon = v => `${Math.abs(v).toFixed(4)}° ${v >= 0 ? "E" : "W"}`;

function sketch(g, bb, util) {
  const NS = "http://www.w3.org/2000/svg";
  const W = 1000, pad = 30;
  const k = Math.cos(((bb.s + bb.n) / 2) * Math.PI / 180) || 1;
  const spanX = Math.max(1e-6, (bb.e - bb.w) * k), spanY = Math.max(1e-6, bb.n - bb.s);
  const H = Math.round(Math.min(1400, Math.max(300, W * spanY / spanX)));
  const sc = Math.min((W - 2 * pad) / spanX, (H - 2 * pad) / spanY);
  const X = p => pad + ((p.lon - bb.w) * k) * sc + ((W - 2 * pad) - spanX * sc) / 2;
  const Y = p => H - pad - (p.lat - bb.s) * sc - ((H - 2 * pad) - spanY * sc) / 2;
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("class", "geo-svg");
  const add = (tag, a) => { const e = document.createElementNS(NS, tag); for (const [x, y] of Object.entries(a)) e.setAttribute(x, y); svg.append(e); return e; };
  add("rect", { x: 0, y: 0, width: W, height: H, class: "geo-bg" });
  for (const p of g.polys) add("polygon", { points: p.map(q => `${X(q).toFixed(1)},${Y(q).toFixed(1)}`).join(" "), class: "geo-poly" });
  g.lines.forEach((l, i) => {
    const step = Math.max(1, Math.floor(l.length / 5000));
    const pts = l.filter((_, j) => j % step === 0 || j === l.length - 1).map(q => `${X(q).toFixed(1)},${Y(q).toFixed(1)}`).join(" ");
    add("polyline", { points: pts, class: "geo-line", style: `stroke:${["#e8710a", "#1a73e8", "#d93025", "#188038", "#9334e6"][i % 5]}` });
    add("circle", { cx: X(l[0]), cy: Y(l[0]), r: 9, class: "geo-start" });
    add("circle", { cx: X(l[l.length - 1]), cy: Y(l[l.length - 1]), r: 9, class: "geo-end" });
  });
  for (const p of g.points.slice(0, 2000)) add("circle", { cx: X(p), cy: Y(p), r: 6, class: "geo-pt" });
  // scala
  const kmW = (W - 2 * pad) / sc * 111.32 * 1; // km per larghezza (circa)
  const nice = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000].find(v => v >= kmW / 5) || 1000;
  const px = nice / 111.32 * sc;
  add("line", { x1: pad, y1: H - 12, x2: pad + px, y2: H - 12, class: "geo-scale" });
  const t = add("text", { x: pad, y: H - 18, class: "geo-scale-t" }); t.textContent = nice < 1 ? `${nice * 1000} m` : `${nice} km`;
  const wrap = util.el("div", { class: "geo-sketch" });
  wrap.append(svg);
  return wrap;
}
function profile(lines, util) {
  const NS = "http://www.w3.org/2000/svg";
  const pts = []; let d = 0;
  for (const l of lines) for (let i = 0; i < l.length; i++) { if (i) d += hav(l[i - 1], l[i]); if (l[i].ele != null) pts.push([d, l[i].ele]); }
  const W = 1000, H = 220, pad = 34;
  const maxD = Math.max(1e-6, pts[pts.length - 1][0]), e0 = Math.min(...pts.map(p => p[1])), e1 = Math.max(...pts.map(p => p[1]));
  const X = x => pad + x / maxD * (W - 2 * pad), Y = y => H - pad - (y - e0) / Math.max(1, e1 - e0) * (H - 2 * pad);
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`); svg.setAttribute("class", "geo-svg");
  const step = Math.max(1, Math.floor(pts.length / 2000));
  const poly = pts.filter((_, i) => i % step === 0).map(p => `${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`);
  const area = document.createElementNS(NS, "polygon");
  area.setAttribute("points", `${X(0)},${H - pad} ${poly.join(" ")} ${X(maxD)},${H - pad}`); area.setAttribute("class", "geo-area");
  svg.append(area);
  for (const [y, lbl] of [[e0, `${Math.round(e0)} m`], [e1, `${Math.round(e1)} m`]]) {
    const t = document.createElementNS(NS, "text"); t.setAttribute("x", 2); t.setAttribute("y", Y(y) + 5); t.setAttribute("class", "geo-scale-t"); t.textContent = lbl; svg.append(t);
  }
  const t = document.createElementNS(NS, "text"); t.setAttribute("x", W - pad); t.setAttribute("y", H - 8); t.setAttribute("text-anchor", "end"); t.setAttribute("class", "geo-scale-t");
  t.textContent = `${maxD.toLocaleString("en-GB", { maximumFractionDigits: 1 })} km`; svg.append(t);
  return util.el("div", { class: "geo-sketch" }, util.el("div", { class: "pdfin-label", text: "Elevation profile" }), svg);
}
