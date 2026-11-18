// Fogli di calcolo: XLSX/XLSM/XLSB, XLS, ODS con SheetJS (Community Edition, Apache-2.0); CSV/TSV qui.
// Una tabella per foglio con intestazioni fisse (A, B, C… e numeri di riga), schede in basso,
// valori gia' formattati (valute, date, percentuali), celle unite, larghezze colonne, colori di sfondo.
// Pinch = zoom della tabella.
const MAX_ROWS = 3000, MAX_COLS = 150;

export async function render({ root, bytes, fmt, util, depth }) {
  let sheets;
  if (fmt === "csv" || fmt === "tsv") {
    const text = util.decodeText(bytes);
    sheets = [{ name: util.NAME.replace(/\.[^.]+$/, "") || "CSV", grid: csvGrid(text, fmt === "tsv" ? "\t" : null) }];
  } else {
    await util.need("xlsx");
    const wb = XLSX.read(bytes, { type: "array", cellStyles: true, cellNF: true, cellDates: false, dense: false, sheetStubs: false, WTF: false });
    sheets = wb.SheetNames.map(n => ({ name: n, ws: wb.Sheets[n], hidden: (wb.Workbook?.Sheets || []).find(s => s.name === n)?.Hidden }));
  }
  if (!sheets.length) throw Object.assign(new Error("The file contains no sheets."), { user: true });
  util.mode("none");

  const wrap = util.el("div", { class: "sheet-wrap" });
  const tabs = util.el("div", { class: "tabs", role: "tablist" });
  root.append(wrap);
  if (sheets.length > 1 || depth === 0) root.append(tabs);
  if (depth > 0) { wrap.style.top = "46px"; }
  let zoom = 1;

  const show = i => {
    const s = sheets[i];
    const t0 = performance.now();
    const table = s.grid ? gridTable(s.grid, util) : wsTable(s.ws, util);
    table.style.zoom = zoom;
    wrap.replaceChildren(table);
    wrap.scrollTo(0, 0);
    [...tabs.children].forEach((b, k) => b.setAttribute("aria-selected", String(k === i)));
    console.log(`sheet ${s.name}: ${Math.round(performance.now() - t0)} ms`);
  };
  sheets.forEach((s, i) => {
    tabs.append(util.el("button", { type: "button", role: "tab", text: s.name + (s.hidden ? " (nascosto)" : ""), onclick: () => show(i) }));
  });
  const first = Math.max(0, sheets.findIndex(s => !s.hidden));
  show(first);

  // pinch sulla tabella
  let p0 = null;
  wrap.addEventListener("touchstart", e => {
    if (e.touches.length === 2) { const [a, b] = e.touches; p0 = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z: zoom }; }
  }, { passive: true });
  wrap.addEventListener("touchmove", e => {
    if (!p0 || e.touches.length !== 2) return;
    e.preventDefault();
    const [a, b] = e.touches;
    zoom = Math.min(3, Math.max(0.3, p0.z * Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / p0.d));
    const t = wrap.firstElementChild;
    if (t) t.style.zoom = zoom;
  }, { passive: false });
  wrap.addEventListener("touchend", e => { if (e.touches.length < 2) p0 = null; }, { passive: true });
}

function colName(c) {
  let s = "";
  for (c++; c > 0; c = Math.floor((c - 1) / 26)) s = String.fromCharCode(65 + (c - 1) % 26) + s;
  return s;
}

function wsTable(ws, util) {
  const ref = ws["!ref"];
  const table = util.el("table", { class: "sheet" });
  if (!ref) { table.append(util.el("tr", null, util.el("td", { class: "muted", text: "Empty sheet" }))); return table; }
  const r = XLSX.utils.decode_range(ref);
  const r1 = Math.min(r.e.r, r.s.r + MAX_ROWS - 1), c1 = Math.min(r.e.c, r.s.c + MAX_COLS - 1);
  if (r1 < r.e.r || c1 < r.e.c) util.notice(`Large sheet: showing ${r1 - r.s.r + 1} rows and ${c1 - r.s.c + 1} columns`);
  // celle unite: l'angolo in alto a sinistra prende rowspan/colspan, le altre spariscono
  const span = new Map(), covered = new Set();
  for (const m of ws["!merges"] || []) {
    span.set(m.s.r + ":" + m.s.c, [m.e.r - m.s.r + 1, m.e.c - m.s.c + 1]);
    for (let y = m.s.r; y <= m.e.r; y++) for (let x = m.s.c; x <= m.e.c; x++) if (y !== m.s.r || x !== m.s.c) covered.add(y + ":" + x);
  }
  const cols = ws["!cols"] || [], rows = ws["!rows"] || [];
  const head = util.el("tr", null, util.el("th", { class: "rn" }));
  const colgroup = util.el("colgroup", null, util.el("col"));
  for (let c = r.s.c; c <= c1; c++) {
    head.append(util.el("th", { text: colName(c) }));
    const ci = cols[c];
    const px = ci ? (ci.wpx || (ci.wch ? ci.wch * 7 + 5 : ci.width ? ci.width * 7 : 0)) : 0;
    colgroup.append(util.el("col", px ? { style: { width: Math.round(Math.min(480, Math.max(24, px))) + "px" } } : null));
    if (ci && ci.hidden) colgroup.lastChild.style.visibility = "collapse";
  }
  table.append(colgroup, util.el("thead", null, head));
  const body = util.el("tbody");
  const frag = document.createDocumentFragment();
  for (let y = r.s.r; y <= r1; y++) {
    const tr = document.createElement("tr");
    if (rows[y] && rows[y].hidden) tr.style.display = "none";
    if (rows[y] && rows[y].hpx) tr.style.height = rows[y].hpx + "px";
    const rn = document.createElement("th"); rn.className = "rn"; rn.textContent = y + 1; tr.append(rn);
    for (let x = r.s.c; x <= c1; x++) {
      const key = y + ":" + x;
      if (covered.has(key)) continue;
      const td = document.createElement("td");
      const sp = span.get(key);
      if (sp) { if (sp[0] > 1) td.rowSpan = sp[0]; if (sp[1] > 1) td.colSpan = sp[1]; }
      const cell = ws[XLSX.utils.encode_cell({ r: y, c: x })];
      if (cell) {
        let txt = cell.w != null ? cell.w : cell.v != null ? String(cell.v) : "";
        if (cell.t === "e") txt = cell.w || "#ERR";
        td.textContent = txt;
        if (cell.t === "n" || cell.t === "d") td.className = "n";
        else if (cell.t === "b") td.className = "b";
        if (typeof txt === "string" && txt.includes("\n")) td.classList.add("wrap");
        if (cell.f) td.title = "=" + cell.f;
        const s = cell.s;
        if (s) {
          const fg = s.fgColor && s.fgColor.rgb;
          if (fg && s.patternType && s.patternType !== "none" && /^[0-9A-F]{6,8}$/i.test(fg)) td.style.background = "#" + fg.slice(-6);
          if (s.bold || (s.font && s.font.bold)) td.style.fontWeight = "700";
          if (s.alignment && s.alignment.horizontal) td.style.textAlign = s.alignment.horizontal === "centerContinuous" ? "center" : s.alignment.horizontal;
          if (s.alignment && s.alignment.wrapText) td.classList.add("wrap");
          if (fg && td.style.background) td.style.color = "#000";
        }
      }
      tr.append(td);
    }
    frag.append(tr);
  }
  body.append(frag);
  table.append(body);
  return table;
}

// ------------------------------------------------------------------ CSV
function csvGrid(text, sep) {
  if (!sep) {
    const first = text.split(/\r?\n/, 1)[0] || "";
    const counts = [";", ",", "\t", "|"].map(c => [c, first.split(c).length]);
    counts.sort((a, b) => b[1] - a[1]);
    sep = counts[0][1] > 1 ? counts[0][0] : ",";
  }
  const rows = [];
  let row = [], cell = "", q = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) {
      if (ch === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else q = false; }
      else cell += ch;
    } else if (ch === '"' && cell === "") q = true;
    else if (ch === sep) { row.push(cell); cell = ""; }
    else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      row.push(cell); rows.push(row); row = []; cell = "";
      if (rows.length >= MAX_ROWS) break;
    } else cell += ch;
  }
  if (cell !== "" || row.length) { row.push(cell); rows.push(row); }
  return rows;
}

function gridTable(rows, util) {
  const ncol = Math.min(MAX_COLS, Math.max(0, ...rows.map(r => r.length)));
  const table = util.el("table", { class: "sheet" });
  const head = util.el("tr", null, util.el("th", { class: "rn" }));
  for (let c = 0; c < ncol; c++) head.append(util.el("th", { text: colName(c) }));
  table.append(util.el("thead", null, head));
  const body = util.el("tbody");
  rows.forEach((r, y) => {
    const tr = document.createElement("tr");
    const rn = document.createElement("th"); rn.className = "rn"; rn.textContent = y + 1; tr.append(rn);
    for (let c = 0; c < ncol; c++) {
      const td = document.createElement("td");
      const v = r[c] ?? "";
      td.textContent = v;
      if (/^-?[\d.\s]+([.,]\d+)?\s*[€%$]?$/.test(v.trim()) && v.trim()) td.className = "n";
      if (y === 0) td.style.fontWeight = "600";
      tr.append(td);
    }
    body.append(tr);
  });
  table.append(body);
  if (rows.length >= MAX_ROWS) util.notice(`Large CSV: showing the first ${MAX_ROWS} rows`);
  return table;
}
