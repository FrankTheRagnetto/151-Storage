// Notebook Jupyter (.ipynb, nbformat 3 e 4): celle Markdown rese come le note (stesso modulo md, con
// formule KaTeX), celle di codice con i colori di sintassi e il numero di esecuzione, e gli output salvati:
// testo, flussi stdout/stderr, errori (i codici colore ANSI diventano colori), immagini PNG/JPEG/SVG,
// HTML (sanificato: niente script, come tutto il resto) e tabelle (pandas). Niente si ri-esegue.
export async function render(ctx) {
  const { root, bytes, util } = ctx;
  let nb;
  try { nb = JSON.parse(util.decodeText(bytes)); } catch (e) { throw Object.assign(new Error("The notebook isn't valid JSON."), { user: true }); }
  const cells = nb.cells || (nb.worksheets || []).flatMap(w => w.cells || []);
  if (!Array.isArray(cells)) throw Object.assign(new Error("There are no cells in this notebook."), { user: true });
  const lang = (nb.metadata?.language_info?.name || nb.metadata?.kernelspec?.language || "python").toLowerCase();
  await Promise.all(["hljs", "purify"].map(n => util.need(n)));
  const md = await import("./md.mjs");
  const src = s => Array.isArray(s) ? s.join("") : String(s ?? "");
  const box = util.el("div", { class: "nb" });
  root.append(box);
  const kernel = nb.metadata?.kernelspec?.display_name;
  box.append(util.el("div", { class: "nb-meta", text: [kernel || lang, `${cells.length} cell${cells.length === 1 ? "" : "s"}`].join(" · ") }));
  let code = 0;
  for (const c of cells) {
    const type = c.cell_type;
    const cell = util.el("div", { class: "nb-cell nb-" + type });
    box.append(cell);
    if (type === "markdown") {
      const holder = util.el("div", { class: "nb-md" });
      cell.append(holder);
      try { await md.render({ root: holder, bytes: new TextEncoder().encode(src(c.source)), util }); }
      catch { holder.append(util.el("pre", { class: "plain-text", text: src(c.source) })); }
      continue;
    }
    if (type === "raw") { cell.append(util.el("pre", { class: "nb-raw", text: src(c.source) })); continue; }
    if (type !== "code") continue;
    code++;
    const n = c.execution_count ?? c.prompt_number;
    const pre = util.el("pre", { class: "hljs nb-src" });
    const text = src(c.source ?? c.input);
    const l = hljs.getLanguage(lang) ? lang : "python";
    try { pre.innerHTML = hljs.highlight(text, { language: l, ignoreIllegals: true }).value; } catch { pre.textContent = text; }
    cell.append(util.el("div", { class: "nb-in" }, util.el("span", { class: "nb-n", text: `[${n ?? " "}]` }), pre));
    for (const o of c.outputs || []) {
      const out = util.el("div", { class: "nb-out" });
      cell.append(out);
      const t = o.output_type;
      if (t === "stream") { out.append(ansiPre(src(o.text), util, o.name === "stderr" ? "nb-stderr" : "")); continue; }
      if (t === "error" || t === "pyerr") { out.append(ansiPre((o.traceback || [`${o.ename}: ${o.evalue}`]).join("\n"), util, "nb-err")); continue; }
      const data = o.data || o; // nbformat 3: i tipi stanno direttamente nell'output
      const img = ["image/png", "image/jpeg", "image/gif", "png", "jpeg"].find(k => data[k]);
      if (data["text/html"] || data.html) {
        const h = util.el("div", { class: "nb-html" });
        h.innerHTML = util.sanitize(src(data["text/html"] || data.html), { FORBID_TAGS: ["script", "style", "iframe", "form", "input", "button", "object", "embed", "link"] });
        out.append(h);
      } else if (img) {
        const mime = img.includes("/") ? img : "image/" + img;
        out.append(util.el("img", { class: "nb-img", alt: "chart", src: `data:${mime};base64,${src(data[img]).replace(/\s+/g, "")}` }));
      } else if (data["image/svg+xml"] || data.svg) {
        const u = URL.createObjectURL(new Blob([src(data["image/svg+xml"] || data.svg)], { type: "image/svg+xml" }));
        out.append(util.el("img", { class: "nb-img", alt: "chart", src: u }));
      } else if (data["text/markdown"]) {
        const holder = util.el("div", { class: "nb-md" }); out.append(holder);
        await md.render({ root: holder, bytes: new TextEncoder().encode(src(data["text/markdown"])), util });
      } else if (data["text/latex"]) {
        const holder = util.el("div", { class: "nb-md" }); out.append(holder);
        await md.render({ root: holder, bytes: new TextEncoder().encode("$$\n" + src(data["text/latex"]).replace(/^\$+|\$+$/g, "") + "\n$$"), util });
      } else if (data["application/json"]) {
        out.append(util.el("pre", { class: "nb-text", text: JSON.stringify(data["application/json"], null, 2) }));
      } else if (data["text/plain"] || data.text) {
        out.append(ansiPre(src(data["text/plain"] || data.text), util, ""));
      }
      if (t === "execute_result" || t === "pyout") out.prepend(util.el("span", { class: "nb-n nb-outn", text: `[${o.execution_count ?? o.prompt_number ?? n ?? " "}]` }));
    }
  }
  util.A.onFormat(`Jupyter Notebook · ${cells.length} cell${cells.length === 1 ? "" : "s"}`);
  util.notice("Notebook: showing saved outputs, nothing is re-run");
}

// colori ANSI (tracebacks di IPython) -> span colorati; tutto il resto e' testo
const ANSI = { 30: "#555", 31: "#d33", 32: "#2a2", 33: "#b80", 34: "#36c", 35: "#a3a", 36: "#099", 37: "#999", 90: "#777", 91: "#e55", 92: "#4c4", 93: "#cb2", 94: "#59f", 95: "#c5c", 96: "#2bb", 97: "#ccc" };
function ansiPre(text, util, cls) {
  const pre = util.el("pre", { class: "nb-text " + cls });
  let color = null, bold = false;
  for (const part of text.split(/(\x1b\[[\d;]*m)/)) {
    const m = /^\x1b\[([\d;]*)m$/.exec(part);
    if (m) {
      for (const c of (m[1] || "0").split(";").map(Number)) {
        if (c === 0) { color = null; bold = false; } else if (c === 1) bold = true; else if (ANSI[c]) color = ANSI[c]; else if (c === 39) color = null;
      }
      continue;
    }
    if (!part) continue;
    if (!color && !bold) { pre.append(part); continue; }
    const s = util.el("span", { text: part });
    if (color) s.style.color = color;
    if (bold) s.style.fontWeight = "700";
    pre.append(s);
  }
  return pre;
}
