// RTF -> DOM, scritto qui (nessuna dipendenza). Copre: paragrafi e allineamento, grassetto/corsivo/
// sottolineato/barrato, dimensione e colore del carattere, evidenziazione, apice/pedice, caratteri
// Unicode (\u) ed esadecimali (\'hh, con la code page del documento), tabelle (\trowd…\cell…\row),
// immagini PNG/JPEG (\pict), link (HYPERLINK). Salta tabelle dei font, stili, info, intestazioni.
const SKIP = new Set(["fonttbl", "stylesheet", "info", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl",
  "themedata", "colorschememapping", "datastore", "latentstyles", "mmathPr", "pgdsctbl", "header", "headerl", "headerr", "headerf",
  "footer", "footerl", "footerr", "footerf", "object", "objdata", "nonshppict", "bkmkstart", "bkmkend", "fldinst", "revtbl",
  "filetbl", "private", "shprslt", "shptxt", "xe", "tc", "listpicture", "pnseclvl", "wgrffmtfilter", "docvar", "userprops", "operator", "author", "title", "company"]);

export function render({ root, bytes, util }) {
  const src = new TextDecoder("latin1").decode(bytes);
  if (!src.startsWith("{\\rtf")) throw Object.assign(new Error("Not an RTF file."), { user: true });
  const art = util.el("article", { class: "md paper rtf" });
  const colors = [];
  let codepage = "windows-1252";
  const fonts = {};
  const footnotes = [];

  let st = fresh();
  function fresh() { return { b: 0, i: 0, u: 0, s: 0, fs: 24, cf: 0, hl: 0, sup: 0, sub: 0, uc: 1, skip: 0, align: "left", intbl: 0, f: null, li: 0, fi: 0, sb: 0, sa: 0, link: null, pict: null, fn: 0, _fld: null, _fldOwner: false }; }
  const stack = [];
  let para = null, run = null, runKey = "";
  let table = null, row = null, cell = null;
  let fldHref = null;
  let lastSn = ""; // nome dell'ultima proprieta' di forma (\sn): l'immagine vera e' "pib", non "fillBlip"

  const container = () => st.fn ? footnotes[footnotes.length - 1] : (cell || art);
  const openPara = () => {
    if (para) return para;
    para = document.createElement("p");
    para.style.textAlign = st.align;
    if (st.li) para.style.marginLeft = st.li / 15 + "px";
    if (st.fi) para.style.textIndent = st.fi / 15 + "px";
    if (st.sb) para.style.marginTop = st.sb / 15 + "px";
    if (st.sa) para.style.marginBottom = st.sa / 15 + "px";
    if (st.intbl && !cell) {
      if (!table) { table = document.createElement("table"); art.append(table); }
      if (!row) { row = document.createElement("tr"); table.append(row); }
      cell = document.createElement("td");
      cell.style.border = "1px solid #999"; cell.style.padding = "4px 8px";
      row.append(cell);
    }
    container().append(para);
    run = null;
    return para;
  };
  const key = () => [st.b, st.i, st.u, st.s, st.fs, st.cf, st.hl, st.sup, st.sub, st.link, st.f].join("|");
  const text = t => {
    if (st._fld) { st._fld.s += t; return; }
    if (st.skip || !t) return;
    if (st.pict) { st.pict.hex += t; return; }
    if (st._sn) { lastSn += t; return; }
    if (st.noText) return; // proprieta' delle forme (\sn/\sv): non e' testo del documento
    if (!st.intbl && table) { table = null; row = null; cell = null; }
    openPara();
    const k = key();
    if (!run || k !== runKey) {
      const tag = st.link ? "a" : "span";
      run = document.createElement(tag);
      if (st.link) run.href = st.link;
      const s = run.style;
      if (st.b) s.fontWeight = "700";
      if (st.i) s.fontStyle = "italic";
      const deco = [];
      if (st.u) deco.push("underline");
      if (st.s) deco.push("line-through");
      if (deco.length) s.textDecoration = deco.join(" ");
      if (st.fs !== 24) s.fontSize = (st.fs / 2) * 4 / 3 + "px";
      if (st.cf && colors[st.cf]) s.color = colors[st.cf];
      if (st.hl && colors[st.hl]) s.backgroundColor = colors[st.hl];
      if (st.sup) s.verticalAlign = "super";
      if (st.sub) s.verticalAlign = "sub";
      if (st.f != null && fonts[st.f]) s.fontFamily = `"${fonts[st.f]}", serif`;
      para.append(run);
      runKey = k;
    }
    run.append(t);
  };
  const endPara = () => { if (!para && !st.skip) openPara(); para = null; run = null; };

  let i = 0, uSkip = 0;
  const n = src.length;
  let destPending = false; // dopo "{\*": la destinazione e' ignorabile
  let fontTbl = null, colorTbl = null, curColor = [null, null, null];
  while (i < n) {
    const ch = src[i];
    if (ch === "{") {
      stack.push(st); st = Object.assign({}, st, { _onEnd: null, _fldOwner: false });
      if (st.pict) st.skip = 1; // gruppi dentro \pict (blipuid ecc.)
      i++;
      if (src.startsWith("\\*", i)) { destPending = true; i += 2; }
      continue;
    }
    if (ch === "}") {
      if (st.pict && !stack[stack.length - 1]?.pict) finishPict(st.pict);
      if (st._fldOwner) { const m = /HYPERLINK\s+"([^"]+)"/.exec(st._fld.s); fldHref = m ? m[1] : null; }
      const end = st._onEnd;
      st = stack.pop() || fresh();
      if (end) end();
      i++;
      run = null;
      continue;
    }
    if (ch === "\\") {
      const nx = src[i + 1];
      if (/[a-zA-Z]/.test(nx)) {
        let j = i + 1;
        while (j < n && /[a-zA-Z]/.test(src[j])) j++;
        const word = src.slice(i + 1, j);
        let param = null;
        const m = /^-?\d+/.exec(src.slice(j, j + 12));
        if (m) { param = Number(m[0]); j += m[0].length; }
        if (src[j] === " ") j++;
        i = j;
        if (uSkip > 0) { uSkip--; continue; }
        control(word, param);
        continue;
      }
      i += 2;
      if (uSkip > 0 && nx !== "'") { uSkip--; continue; }
      switch (nx) {
        case "'": {
          const hex = src.substr(i, 2); i += 2;
          if (uSkip > 0) { uSkip--; break; }
          if (fontTbl) break;
          text(new TextDecoder(codepage).decode(new Uint8Array([parseInt(hex, 16)])));
          break;
        }
        case "~": text(" "); break;
        case "-": break;
        case "_": text("‑"); break;
        case "\n": case "\r": endPara(); break;
        case "*": destPending = true; break;
        default: text(nx);
      }
      continue;
    }
    if (ch === "\r" || ch === "\n") { i++; continue; }
    // testo semplice fino al prossimo carattere speciale
    let j = i;
    while (j < n && src[j] !== "\\" && src[j] !== "{" && src[j] !== "}" && src[j] !== "\r" && src[j] !== "\n") j++;
    let chunk = src.slice(i, j);
    i = j;
    if (uSkip > 0) { const k = Math.min(uSkip, chunk.length); chunk = chunk.slice(k); uSkip -= k; }
    if (fontTbl) { fontTbl.name += chunk; if (chunk.includes(";")) { fonts[fontTbl.id] = fontTbl.name.replace(/;.*$/, "").trim(); fontTbl.name = ""; } continue; }
    if (colorTbl) {
      if (chunk.includes(";")) {
        for (const _ of chunk.split("").filter(c => c === ";")) {
          colors.push(curColor[0] == null ? null : `rgb(${curColor[0]},${curColor[1] || 0},${curColor[2] || 0})`);
          curColor = [null, null, null];
        }
      }
      continue;
    }
    if (chunk && (!st.skip || st._fld)) text(new TextDecoder(codepage).decode(Uint8Array.from(chunk, c => c.charCodeAt(0) & 0xFF)));
  }

  function control(w, p) {
    if (destPending) {
      destPending = false;
      if (!["shppict", "fldinst", "footnote", "shpinst"].includes(w)) { st.skip = 1; return; }
    }
    if (w === "fldinst") { st._fld = { s: "" }; st._fldOwner = true; return; }
    if (st.skip || st._fld) return;
    switch (w) {
      case "ansicpg": if (p && p !== 65001) try { new TextDecoder("windows-" + p); codepage = "windows-" + p; } catch {} break;
      case "fonttbl": fontTbl = { id: null, name: "" }; st._onEnd = () => { fontTbl = null; }; break;
      case "colortbl": colorTbl = true; st._onEnd = () => { colorTbl = null; }; break;
      case "f": if (fontTbl) fontTbl.id = p; else st.f = p; break;
      case "red": curColor[0] = p; break;
      case "green": curColor[1] = p; break;
      case "blue": curColor[2] = p; break;
      case "par": endPara(); break;
      case "sect": endPara(); break;
      case "page": endPara(); art.append(document.createElement("hr")); break;
      case "line": openPara(); para.append(document.createElement("br")); run = null; break;
      case "tab": text(" "); break;
      case "pard": Object.assign(st, { align: "left", intbl: 0, li: 0, fi: 0, sb: 0, sa: 0 }); break;
      case "plain": Object.assign(st, { b: 0, i: 0, u: 0, s: 0, fs: 24, cf: 0, hl: 0, sup: 0, sub: 0, f: null }); break;
      case "b": st.b = p === 0 ? 0 : 1; break;
      case "i": st.i = p === 0 ? 0 : 1; break;
      case "ul": case "uld": case "uldb": case "ulw": case "ulwave": st.u = p === 0 ? 0 : 1; break;
      case "ulnone": st.u = 0; break;
      case "strike": case "striked": st.s = p === 0 ? 0 : 1; break;
      case "fs": st.fs = p || 24; break;
      case "cf": st.cf = p || 0; break;
      case "highlight": case "cb": case "chcbpat": st.hl = p || 0; break;
      case "super": st.sup = 1; st.sub = 0; break;
      case "sub": st.sub = 1; st.sup = 0; break;
      case "nosupersub": st.sup = st.sub = 0; break;
      case "ql": st.align = "left"; break;
      case "qr": st.align = "right"; break;
      case "qc": st.align = "center"; break;
      case "qj": st.align = "justify"; break;
      case "li": st.li = p || 0; break;
      case "fi": st.fi = p || 0; break;
      case "sb": st.sb = p || 0; break;
      case "sa": st.sa = p || 0; break;
      case "uc": st.uc = p || 0; break;
      case "u": { let c = p < 0 ? p + 65536 : p; text(String.fromCharCode(c)); uSkip = st.uc; break; }
      case "emdash": text("—"); break;
      case "endash": text("–"); break;
      case "bullet": text("•"); break;
      case "lquote": text("‘"); break;
      case "rquote": text("’"); break;
      case "ldblquote": text("“"); break;
      case "rdblquote": text("”"); break;
      case "intbl": st.intbl = 1; break;
      case "trowd": if (!table) { table = document.createElement("table"); art.append(table); } row = null; cell = null; para = null; break;
      case "cell": if (!cell) openPara(); para = null; cell = null; run = null; break;
      case "row": row = null; cell = null; para = null; break;
      case "pict": st.pict = { hex: "", type: null, w: 0, h: 0 }; break;
      case "pngblip": if (st.pict) st.pict.type = "image/png"; break;
      case "jpegblip": if (st.pict) st.pict.type = "image/jpeg"; break;
      case "emfblip": case "wmetafile": case "macpict": if (st.pict) st.pict.type = "x"; break;
      case "picwgoal": if (st.pict) st.pict.w = p; break;
      case "pichgoal": if (st.pict) st.pict.h = p; break;
      case "picscalex": if (st.pict) st.pict.sx = p; break;
      case "bin": st.skip = 1; break;
      case "fldrslt": if (fldHref && /^(https?:|mailto:)/i.test(fldHref)) st.link = fldHref; break;
      case "chftn": break;
      case "shpinst": st.noText = 1; break;
      case "sn": st._sn = true; lastSn = ""; break;
      case "footnote": {
        const k = footnotes.length + 1;
        openPara(); para.append(Object.assign(document.createElement("sup"), { textContent: k }));
        const mainPara = para;
        const d = document.createElement("div"); d.className = "small muted"; d.append(k + ". ");
        footnotes.push(d);
        st.fn = 1; para = null; run = null;
        st._onEnd = () => { para = mainPara; run = null; };
        break;
      }
      default:
        if (SKIP.has(w)) st.skip = 1;
    }
  }
  function finishPict(pc) {
    if (st.noText && lastSn.trim() !== "pib") return;
    if (!pc.type || pc.type === "x") {
      if (pc.type === "x") { openPara(); para.append(util.el("span", { class: "img-missing", text: "WMF/EMF image can't be shown" })); }
      return;
    }
    const hex = pc.hex.replace(/[^0-9a-fA-F]/g, "");
    const bin = new Uint8Array(hex.length >> 1);
    for (let k = 0; k < bin.length; k++) bin[k] = parseInt(hex.substr(k * 2, 2), 16);
    const img = document.createElement("img");
    img.src = URL.createObjectURL(new Blob([bin], { type: pc.type }));
    if (pc.w) img.style.width = (pc.w / 15) * (pc.sx ? pc.sx / 100 : 1) + "px";
    img.style.maxWidth = "100%"; img.style.height = "auto";
    const saved = st.pict; st.pict = null;
    openPara(); para.append(img); run = null;
    st.pict = saved;
  }
  for (const f of footnotes) art.append(f);
  root.append(art);
}
