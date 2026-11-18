// Testo OpenDocument (LibreOffice Writer): titoli, paragrafi con stili (grassetto, colori, allineamento,
// rientri), elenchi puntati e numerati, tabelle con bordi e sfondi, immagini, caselle di testo, link,
// note a pie' di pagina. Il testo va a capo sul telefono (niente impaginazione a pagine).
import { odfStyles, odfCss, applyCss, odfLen } from "./slides.mjs";

const NS = {
  text: "urn:oasis:names:tc:opendocument:xmlns:text:1.0", table: "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
  draw: "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", svg: "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0",
  xlink: "http://www.w3.org/1999/xlink", style: "urn:oasis:names:tc:opendocument:xmlns:style:1.0", office: "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
};

export async function render({ root, zip, util }) {
  const parse = async p => { const f = zip.file(p); return f ? new DOMParser().parseFromString(await f.async("string"), "application/xml") : null; };
  const content = await parse("content.xml"), styles = await parse("styles.xml");
  if (!content) throw Object.assign(new Error("Document without content.xml"), { user: true });
  const st = odfStyles([styles, content]);
  const listStyles = {};
  for (const d of [styles, content]) if (d) for (const l of d.getElementsByTagNameNS(NS.text, "list-style")) listStyles[l.getAttributeNS(NS.style, "name")] = l;
  const pics = {};
  for (const n of content.getElementsByTagNameNS(NS.draw, "image")) {
    const href = n.getAttributeNS(NS.xlink, "href");
    if (href && !pics[href] && zip.file(href)) pics[href] = URL.createObjectURL(new Blob([await zip.file(href).async("uint8array")], { type: util.mimeOf(href) }));
    // immagine incorporata come dati (file "flat")
    const bin = n.getElementsByTagNameNS(NS.office, "binary-data")[0];
    if (bin) n.setAttribute("data-inline", "data:image/png;base64," + bin.textContent.replace(/\s+/g, ""));
  }
  const body = content.getElementsByTagNameNS(NS.office, "text")[0];
  if (!body) throw Object.assign(new Error("The file isn't a text document."), { user: true });
  const art = util.el("article", { class: "md paper" });
  const notes = [];
  const ctx = { st, listStyles, pics, notes, util };
  blocks(body, art, ctx, null, 0);
  if (notes.length) {
    const fn = util.el("div", { class: "footnotes-odt" });
    notes.forEach((n, i) => { const p = util.el("div", { id: "odtnote-" + (i + 1) }, util.el("sup", { text: String(i + 1) + " " })); n.forEach(x => p.append(x)); fn.append(p); });
    art.append(fn);
  }
  root.append(art);
}

function blocks(node, out, ctx, listName, depth) {
  for (const c of node.children) {
    switch (c.localName) {
      case "h": {
        const lvl = Math.min(6, Math.max(1, Number(c.getAttributeNS(NS.text, "outline-level") || 1)));
        const h = document.createElement("h" + lvl);
        const css = odfCss(ctx.st, "paragraph", c.getAttributeNS(NS.text, "style-name"));
        delete css.fontFamily;
        applyCss(h, css);
        inline(c, h, ctx);
        out.append(h);
        break;
      }
      case "p": {
        const p = document.createElement("p");
        applyCss(p, odfCss(ctx.st, "paragraph", c.getAttributeNS(NS.text, "style-name")));
        inline(c, p, ctx);
        out.append(p);
        break;
      }
      case "list": {
        const name = c.getAttributeNS(NS.text, "style-name") || listName;
        const ls = name && ctx.listStyles[name];
        const lvlStyle = ls && [...ls.children].find(l => Number(l.getAttributeNS(NS.text, "level")) === depth + 1);
        const numbered = lvlStyle && lvlStyle.localName === "list-level-style-number" && (lvlStyle.getAttributeNS(NS.style, "num-format") || "") !== "";
        const list = document.createElement(numbered ? "ol" : "ul");
        if (numbered) {
          const f = lvlStyle.getAttributeNS(NS.style, "num-format");
          list.type = { a: "a", A: "A", i: "i", I: "I" }[f] || "1";
        }
        for (const li of c.children) {
          if (li.localName !== "list-item" && li.localName !== "list-header") continue;
          const item = document.createElement("li");
          if (li.localName === "list-header") item.style.listStyle = "none";
          blocks(li, item, ctx, name, depth + 1);
          list.append(item);
        }
        out.append(list);
        break;
      }
      case "table": out.append(table(c, ctx)); break;
      case "section": case "index-body": case "table-of-content": case "illustration-index": case "alphabetical-index": case "user-index": case "bibliography":
      case "list-item": case "deletion":
        if (c.localName !== "deletion") blocks(c, out, ctx, listName, depth);
        break;
      case "frame": { const f = frame(c, ctx); if (f) out.append(f); break; }
      case "tracked-changes": case "sequence-decls": case "variable-decls": case "user-field-decls": case "forms": case "index-title-template": case "table-of-content-source": break;
      default: if (c.children.length) blocks(c, out, ctx, listName, depth);
    }
  }
}

function inline(node, out, ctx) {
  for (const c of node.childNodes) {
    if (c.nodeType === 3) { out.append(c.data); continue; }
    if (c.nodeType !== 1) continue;
    switch (c.localName) {
      case "span": { const s = document.createElement("span"); applyCss(s, odfCss(ctx.st, "text", c.getAttributeNS(NS.text, "style-name"))); inline(c, s, ctx); out.append(s); break; }
      case "a": { const a = document.createElement("a"); a.href = c.getAttributeNS(NS.xlink, "href") || "#"; inline(c, a, ctx); out.append(a); break; }
      case "s": out.append(" ".repeat(Number(c.getAttributeNS(NS.text, "c") || 1))); break;
      case "tab": out.append(" "); break;
      case "line-break": out.append(document.createElement("br")); break;
      case "note": {
        const cit = c.getElementsByTagNameNS(NS.text, "note-citation")[0];
        const nb = c.getElementsByTagNameNS(NS.text, "note-body")[0];
        const holder = document.createElement("span");
        if (nb) blocks(nb, holder, ctx, null, 0);
        ctx.notes.push([...holder.childNodes]);
        const k = ctx.notes.length;
        out.append(ctx.util.el("sup", null, ctx.util.el("a", { href: "#odtnote-" + k, text: cit ? cit.textContent : String(k) })));
        break;
      }
      case "frame": { const f = frame(c, ctx); if (f) out.append(f); break; }
      case "annotation": case "bookmark": case "bookmark-start": case "bookmark-end": case "soft-page-break": case "reference-mark": case "reference-mark-start": case "reference-mark-end": break;
      default: inline(c, out, ctx);
    }
  }
}

function frame(c, ctx) {
  const w = odfLen(c.getAttributeNS(NS.svg, "width")), h = odfLen(c.getAttributeNS(NS.svg, "height"));
  const img = [...c.children].find(x => x.localName === "image");
  if (img) {
    const src = img.getAttribute("data-inline") || ctx.pics[img.getAttributeNS(NS.xlink, "href")];
    if (!src) return ctx.util.el("span", { class: "img-missing", text: "Linked image not available" });
    const i = document.createElement("img");
    i.src = src;
    if (w) { i.style.width = w + "px"; i.style.maxWidth = "100%"; }
    i.style.height = "auto";
    i.style.verticalAlign = "middle";
    return i;
  }
  const tb = [...c.children].find(x => x.localName === "text-box");
  if (tb) {
    const d = document.createElement("div");
    d.style.border = "1px solid var(--border)"; d.style.padding = "6px 10px"; d.style.margin = "6px 0";
    if (w) d.style.maxWidth = w + "px";
    blocks(tb, d, ctx, null, 0);
    return d;
  }
  return null;
}

function table(t, ctx) {
  const tb = document.createElement("table");
  applyCss(tb, odfCss(ctx.st, "table", t.getAttributeNS(NS.table, "style-name")));
  tb.style.marginLeft = ""; tb.style.width = "";
  const rows = [];
  const collect = n => { for (const c of n.children) { if (c.localName === "table-row") rows.push(c); else if (/^table-(header-rows|rows|row-group)$/.test(c.localName)) collect(c); } };
  collect(t);
  for (const r of rows) {
    const tr = document.createElement("tr");
    for (const c of r.children) {
      if (c.localName === "covered-table-cell") continue;
      if (c.localName !== "table-cell") continue;
      const td = document.createElement("td");
      applyCss(td, odfCss(ctx.st, "table-cell", c.getAttributeNS(NS.table, "style-name")));
      if (!td.style.border && !td.style.borderTop) td.style.border = "1px solid var(--border)";
      if (!td.style.padding) td.style.padding = "4px 8px";
      const cs = Number(c.getAttributeNS(NS.table, "number-columns-spanned") || 1), rs = Number(c.getAttributeNS(NS.table, "number-rows-spanned") || 1);
      if (cs > 1) td.colSpan = cs;
      if (rs > 1) td.rowSpan = rs;
      blocks(c, td, ctx, null, 0);
      if (td.style.backgroundColor) td.style.color = "#000";
      const rep = Math.min(50, Number(c.getAttributeNS(NS.table, "number-columns-repeated") || 1));
      tr.append(td);
      for (let i = 1; i < rep; i++) tr.append(td.cloneNode(true));
    }
    tb.append(tr);
  }
  return tb;
}
