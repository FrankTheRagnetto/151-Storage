// Markdown "come in Obsidian": markdown-it + note a pie' di pagina + ==evidenziato==, piu' le estensioni
// di Obsidian scritte qui: proprieta' (frontmatter), callout (anche pieghevoli), [[wikilink]] e ![[embed]],
// #tag, %%commenti%%, liste di attivita', formule $…$ / $$…$$ (KaTeX), diagrammi mermaid.
// Tutto l'HTML passa da DOMPurify; formule e diagrammi si disegnano DOPO la sanificazione.
const IMG_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|avif)$/i;

export async function render({ root, bytes, util }) {
  const { el, esc } = util;
  let text = util.decodeText(bytes);
  const needs = ["md", "purify"];
  const hasCode = /^\s*(```|~~~)/m.test(text);
  const hasMath = /\$[^\s$][^$]*\$|\$\$/.test(text);
  const hasMermaid = /^\s*(```|~~~)\s*mermaid/m.test(text);
  if (hasCode) needs.push("hljs");
  if (hasMath) needs.push("katex");
  await Promise.all(needs.map(n => util.need(n)));

  // ---- frontmatter
  let props = null;
  const fm = /^﻿?---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(text);
  if (fm) { props = fm[1]; text = text.slice(fm[0].length); }

  // ---- %%commenti%% su piu' righe (fuori dai blocchi di codice)
  text = stripComments(text);

  const md = window.markdownit({
    html: true, linkify: true, breaks: true, typographer: false,
    highlight(str, lang) {
      lang = (lang || "").trim().toLowerCase();
      if (lang === "mermaid") return `<pre class="mermaid-src"><code>${esc(str)}</code></pre>`;
      const label = lang ? `<span class="lang">${esc(lang)}</span>` : "";
      if (window.hljs && lang && hljs.getLanguage(lang)) {
        try { return `<pre class="hljs">${label}<code class="hljs">${hljs.highlight(str, { language: lang, ignoreIllegals: true }).value}</code></pre>`; } catch {}
      }
      return `<pre>${label}<code>${esc(str)}</code></pre>`;
    },
  });
  md.use(window.markdownitFootnote).use(window.markdownitMark);
  md.use(mathPlugin).use(wikiPlugin).use(tagPlugin);
  md.linkify.set({ fuzzyLink: false, fuzzyEmail: false });

  const html = md.render(text);
  const clean = util.sanitize(html, {
    FORBID_TAGS: ["style", "form", "input", "button", "textarea", "select", "iframe", "object", "embed", "video", "audio", "source"],
    ADD_ATTR: ["data-tex", "data-display", "data-embed", "data-target", "data-width"],
  }, src => /^data:image\//i.test(src) ? src : null); // gli altri src li decide images(), dopo
  const art = el("article", { class: "md" });
  art.innerHTML = clean;

  // ---- dopo la sanificazione: solo DOM costruito da noi
  callouts(art, el);
  tasks(art);
  images(art, util);
  headingIds(art);
  if (hasMath) mathRender(art);
  if (props != null) art.prepend(properties(props, util));
  root.append(art);
  if (hasMermaid) mermaidRender(art, util); // puo' metterci un attimo: il resto e' gia' visibile
}

// ------------------------------------------------------------------ frontmatter -> riquadro "Proprieta'"
function properties(src, { el }) {
  const box = el("div", { class: "props" }, el("div", { class: "props-title", text: "Properties" }));
  let parsed;
  try { parsed = parseYamlFlat(src); } catch { parsed = null; }
  if (!parsed || !parsed.length) {
    box.append(el("pre", { text: src }));
    return box;
  }
  for (const [k, v] of parsed) {
    const val = el("div", { class: "prop-val" });
    const isTags = /^(tags?|aliases|alias)$/i.test(k);
    const items = Array.isArray(v) ? v : (isTags && typeof v === "string" && v.includes(",") ? v.split(",").map(s => s.trim()) : null);
    if (items) {
      for (const it of items) val.append(el("span", { class: /^tags?$/i.test(k) ? "tag" : "chip", text: /^tags?$/i.test(k) ? "#" + String(it).replace(/^#/, "") : String(it) }));
    } else if (v === true || v === false) {
      const cb = el("input", { type: "checkbox", disabled: true });
      cb.checked = v;
      val.append(cb);
      cb.style.margin = "0";
    } else if (/^(tags?)$/i.test(k) && v) {
      val.append(el("span", { class: "tag", text: "#" + String(v).replace(/^#/, "") }));
    } else if (/^\[\[.*\]\]$/.test(String(v))) {
      val.append(el("a", { class: "internal-link", href: "#", "data-internal": "1", text: String(v).slice(2, -2) }));
    } else {
      val.textContent = v == null ? "" : String(v);
    }
    box.append(el("div", { class: "prop" }, el("div", { class: "prop-key", text: k }), val));
  }
  return box;
}

/** YAML "da proprieta' di Obsidian": chiave: valore, liste inline [a, b] e a trattini. Niente oggetti annidati. */
function parseYamlFlat(src) {
  const out = [];
  const lines = src.split(/\r?\n/);
  const scalar = s => {
    s = s.trim();
    if (s === "") return null;
    if (/^(true|false)$/i.test(s)) return s.toLowerCase() === "true";
    if (/^(null|~)$/.test(s)) return null;
    if (/^".*"$|^'.*'$/.test(s)) return s.slice(1, -1);
    return s;
  };
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (!l.trim() || l.trim().startsWith("#")) continue;
    const m = /^([^\s:#][^:]*):(?:\s+(.*)|\s*)$/.exec(l);
    if (!m) { if (/^\s/.test(l)) continue; throw new Error("yaml"); }
    const key = m[1].trim();
    let v = m[2] != null ? m[2].trim() : "";
    if (v.startsWith("[") && v.endsWith("]")) {
      out.push([key, v.slice(1, -1).split(",").map(scalar).filter(x => x != null)]);
    } else if (v === "" || v === "|" || v === ">") {
      const list = [], block = [];
      while (i + 1 < lines.length && (/^\s+/.test(lines[i + 1]) || /^-\s/.test(lines[i + 1]))) {
        const n = lines[++i];
        const li = /^\s*-\s+(.*)$/.exec(n);
        if (li && v === "") list.push(scalar(li[1])); else block.push(n.trim());
      }
      out.push([key, list.length ? list : (block.length ? block.join(v === ">" ? " " : "\n") : null)]);
    } else {
      out.push([key, scalar(v)]);
    }
  }
  return out;
}

function stripComments(t) {
  const parts = t.split(/(^(?:```|~~~)[\s\S]*?^(?:```|~~~)[ \t]*$)/m);
  return parts.map((p, i) => i % 2 ? p : p.replace(/%%[\s\S]*?%%/g, "")).join("");
}

// ------------------------------------------------------------------ plugin markdown-it
function mathPlugin(md) {
  // $$ … $$ su righe proprie
  md.block.ruler.before("fence", "math_block", (state, start, end, silent) => {
    let pos = state.bMarks[start] + state.tShift[start];
    let max = state.eMarks[start];
    if (state.src.slice(pos, pos + 2) !== "$$") return false;
    let first = state.src.slice(pos + 2, max);
    let next = start, found = false, body = "";
    if (first.trim().endsWith("$$") && first.trim().length > 2) {
      body = first.trim().slice(0, -2); found = true;
    } else {
      body = first;
      for (next = start + 1; next < end; next++) {
        pos = state.bMarks[next] + state.tShift[next];
        max = state.eMarks[next];
        const line = state.src.slice(pos, max);
        if (line.trim().endsWith("$$")) { body += "\n" + line.trim().slice(0, -2); found = true; break; }
        body += "\n" + state.src.slice(state.bMarks[next], max);
      }
    }
    if (!found) return false;
    if (silent) return true;
    state.line = next + 1;
    const tok = state.push("math_block", "div", 0);
    tok.content = body.trim();
    tok.map = [start, state.line];
    return true;
  }, { alt: ["paragraph", "reference", "blockquote", "list"] });
  // $ … $ in linea (come Obsidian: niente spazio subito dentro i dollari)
  md.inline.ruler.after("escape", "math_inline", (state, silent) => {
    const s = state.src, p = state.pos;
    if (s[p] !== "$") return false;
    if (s[p + 1] === "$") { // $$…$$ in linea
      const e = s.indexOf("$$", p + 2);
      if (e < 0) return false;
      if (!silent) { const t = state.push("math_inline", "span", 0); t.content = s.slice(p + 2, e); t.meta = { display: true }; }
      state.pos = e + 2;
      return true;
    }
    if (/\s/.test(s[p + 1] || " ")) return false;
    let e = p + 1;
    while ((e = s.indexOf("$", e)) >= 0) {
      if (s[e - 1] === "\\") { e++; continue; }
      if (/\s/.test(s[e - 1])) return false;
      if (/\d/.test(s[e + 1] || "")) return false; // "$5 e $6": prezzi, non formule
      break;
    }
    if (e < 0 || e === p + 1) return false;
    if (!silent) { const t = state.push("math_inline", "span", 0); t.content = s.slice(p + 1, e); }
    state.pos = e + 1;
    return true;
  });
  const e = md.utils.escapeHtml;
  md.renderer.rules.math_inline = (t, i) => `<span class="math" data-tex="${e(t[i].content)}"${t[i].meta && t[i].meta.display ? ' data-display="1"' : ""}></span>`;
  md.renderer.rules.math_block = (t, i) => `<div class="math" data-tex="${e(t[i].content)}" data-display="1"></div>\n`;
}

function wikiPlugin(md) {
  md.inline.ruler.before("link", "wikilink", (state, silent) => {
    const s = state.src, p = state.pos;
    const embed = s[p] === "!" && s[p + 1] === "[" && s[p + 2] === "[";
    if (!embed && !(s[p] === "[" && s[p + 1] === "[")) return false;
    const start = p + (embed ? 3 : 2);
    const end = s.indexOf("]]", start);
    if (end < 0 || s.slice(start, end).includes("\n")) return false;
    if (!silent) {
      const raw = s.slice(start, end);
      const [target, alias] = raw.split("|");
      const t = state.push(embed ? "wiki_embed" : "wiki_link", "", 0);
      t.meta = { target: target.trim(), alias: alias != null ? alias.trim() : null };
    }
    state.pos = end + 2;
    return true;
  });
  const e = md.utils.escapeHtml;
  md.renderer.rules.wiki_link = (t, i) => {
    const { target, alias } = t[i].meta;
    const shown = alias || target.replace(/#\^?/, " › ");
    return `<a class="internal-link" href="#" data-internal="1" data-target="${e(target)}">${e(shown)}</a>`;
  };
  md.renderer.rules.wiki_embed = (t, i) => {
    const { target, alias } = t[i].meta;
    const file = target.split("#")[0];
    if (IMG_EXT.test(file)) {
      const w = alias && /^\d+(x\d+)?$/.test(alias) ? alias.split("x")[0] : null;
      return `<img data-embed="${e(file)}" alt="${e(alias && !w ? alias : file)}"${w ? ` data-width="${w}"` : ""}>`;
    }
    return `<div class="embed">Embedded note: <b>${e(target)}</b></div>`;
  };
}

function tagPlugin(md) {
  md.inline.ruler.after("wikilink", "obsidian_tag", (state, silent) => {
    const s = state.src, p = state.pos;
    if (s[p] !== "#") return false;
    if (p > 0 && !/[\s(\[,;]/.test(s[p - 1])) return false;
    const m = /^#([\p{L}\p{N}_\-/]+)/u.exec(s.slice(p));
    if (!m || /^\d+$/.test(m[1])) return false;
    if (!silent) { const t = state.push("obs_tag", "span", 0); t.content = m[1]; }
    state.pos = p + m[0].length;
    return true;
  });
  md.renderer.rules.obs_tag = (t, i) => `<span class="tag">#${md.utils.escapeHtml(t[i].content)}</span>`;
}

// ------------------------------------------------------------------ trasformazioni sul DOM (dopo DOMPurify)
const ICONS = {
  info: "M12 16v-4M12 8h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z",
  warn: "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01",
  ok: "M20 6 9 17l-5-5",
  x: "M18 6 6 18M6 6l12 12",
  q: "M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z",
  flame: "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.4-.5-2-1-3-1.1-2.1-.2-4 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.2.4-2.3 1-3.2.4 1.4 1.6 2.7 2.5 2.7z",
  pencil: "M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z",
  quote: "M3 21c3 0 7-1 7-8V5H3v7h4c0 3-1 5-4 5M14 21c3 0 7-1 7-8V5h-7v7h4c0 3-1 5-4 5",
  list: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
  bug: "M8 2l1.9 1.9M16 2l-1.9 1.9M9 7.1V6a3 3 0 0 1 6 0v1.1M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6zM12 20v-9M6.5 13H3M21 13h-3.5",
};
const ICON_OF = { note: "pencil", abstract: "list", summary: "list", tldr: "list", info: "info", todo: "ok", tip: "flame", hint: "flame",
  important: "flame", success: "ok", check: "ok", done: "ok", question: "q", help: "q", faq: "q", warning: "warn", caution: "warn",
  attention: "warn", failure: "x", fail: "x", missing: "x", danger: "warn", error: "x", bug: "bug", example: "list", quote: "quote", cite: "quote" };

function callouts(art, el) {
  // dal piu' interno al piu' esterno, cosi' i callout annidati funzionano
  const bqs = [...art.querySelectorAll("blockquote")].reverse();
  for (const bq of bqs) {
    const p = bq.firstElementChild;
    if (!p || p.tagName !== "P") continue;
    const first = p.firstChild;
    if (!first || first.nodeType !== 3) continue;
    const m = /^\s*\[!([\w-]+)\]([+-]?)[ \t]*/.exec(first.data);
    if (!m) continue;
    const type = m[1].toLowerCase();
    first.data = first.data.slice(m[0].length);
    // titolo = tutto fino al primo <br> (o fine riga) del primo paragrafo
    const titleNodes = [];
    let n = p.firstChild;
    while (n) {
      const nx = n.nextSibling;
      if (n.nodeName === "BR") { n.remove(); break; }
      if (n.nodeType === 3 && n.data.includes("\n")) {
        const i = n.data.indexOf("\n");
        const rest = n.data.slice(i + 1);
        n.data = n.data.slice(0, i);
        titleNodes.push(n);
        if (rest) p.insertBefore(document.createTextNode(rest), nx);
        break;
      }
      titleNodes.push(n);
      n = nx;
    }
    const titleInner = el("div", { class: "callout-title-inner" });
    titleNodes.forEach(t => titleInner.append(t));
    if (!titleInner.textContent.trim()) titleInner.textContent = type.charAt(0).toUpperCase() + type.slice(1);
    const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    icon.setAttribute("viewBox", "0 0 24 24"); icon.setAttribute("class", "callout-icon");
    icon.setAttribute("fill", "none"); icon.setAttribute("stroke", "currentColor"); icon.setAttribute("stroke-width", "2");
    icon.setAttribute("stroke-linecap", "round"); icon.setAttribute("stroke-linejoin", "round");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", ICONS[ICON_OF[type] || "pencil"]);
    icon.append(path);
    if (!p.textContent.trim() && !p.querySelector("img")) p.remove();
    const content = el("div", { class: "callout-content" });
    while (bq.firstChild) content.append(bq.firstChild);
    const foldable = m[2] !== "";
    const box = el(foldable ? "details" : "div", { class: "callout", "data-callout": type });
    const title = el(foldable ? "summary" : "div", { class: "callout-title" }, icon, titleInner);
    if (foldable && m[2] === "+") box.open = true;
    box.append(title);
    if (content.childNodes.length) box.append(content);
    bq.replaceWith(box);
  }
}

function tasks(art) {
  for (const li of art.querySelectorAll("li")) {
    let host = li.firstChild;
    if (host && host.nodeType === 1 && host.tagName === "P") host = host.firstChild;
    if (!host || host.nodeType !== 3) continue;
    const m = /^\[(.)\][ \t]?/.exec(host.data);
    if (!m) continue;
    host.data = host.data.slice(m[0].length);
    const cb = document.createElement("input");
    cb.type = "checkbox"; cb.disabled = true; cb.checked = m[1] !== " ";
    host.parentNode.insertBefore(cb, host);
    li.classList.add("task-list-item");
    li.dataset.task = m[1];
    li.parentElement && li.parentElement.classList.add("contains-task-list");
  }
}

function images(art, util) {
  for (const img of art.querySelectorAll("img")) {
    const embed = img.getAttribute("data-embed");
    const src = embed || img.getAttribute("src") || img.getAttribute("data-blocked") || "";
    img.removeAttribute("data-blocked");
    const w = img.getAttribute("data-width");
    if (w) img.style.width = w + "px";
    if (/^(https?:)?\/\//i.test(src)) {
      img.replaceWith(util.el("span", { class: "img-missing", text: `Remote image not loaded (no network): ${img.alt || src}` }));
      continue;
    }
    if (/^data:image\//i.test(src)) continue;
    if (!src) { img.remove(); continue; }
    let rel = src;
    try { rel = decodeURIComponent(src); } catch {}
    img.src = util.relUrl(rel);
    img.loading = "lazy";
    img.addEventListener("error", () => {
      img.replaceWith(util.el("span", { class: "img-missing", text: `Image not reachable from here: ${rel}` }));
    }, { once: true });
  }
}

function headingIds(art) {
  const seen = new Set();
  for (const h of art.querySelectorAll("h1,h2,h3,h4,h5,h6")) {
    if (h.id) continue;
    let id = h.textContent.trim().toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "") || "h";
    let k = id, i = 1;
    while (seen.has(k)) k = id + "-" + i++;
    seen.add(k);
    h.id = k;
  }
}

function mathRender(art) {
  for (const n of art.querySelectorAll(".math[data-tex]")) {
    try {
      katex.render(n.getAttribute("data-tex"), n, { displayMode: n.hasAttribute("data-display"), throwOnError: false, strict: "ignore", trust: false, output: "htmlAndMathml" });
    } catch (e) {
      n.className = "math-error";
      n.textContent = n.getAttribute("data-tex");
    }
  }
}

async function mermaidRender(art, util) {
  const blocks = [...art.querySelectorAll("pre.mermaid-src")];
  if (!blocks.length) return;
  try {
    await util.need("mermaid");
    mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: util.DARK ? "dark" : "default", fontFamily: "system-ui, sans-serif" });
    let i = 0;
    for (const pre of blocks) {
      const src = pre.textContent;
      try {
        const { svg } = await mermaid.render("mmd-" + (i++), src);
        const box = util.el("div", { class: "mermaid" });
        box.innerHTML = svg; // SVG prodotto (e sanificato) da mermaid in modalita' strict
        pre.replaceWith(box);
      } catch (e) {
        pre.after(util.el("div", { class: "mermaid-note", text: "Invalid mermaid diagram, showing the code. (" + String(e.message || e).split("\n")[0].slice(0, 100) + ")" }));
        document.querySelectorAll("#dmmd-" + (i - 1) + ", #mmd-" + (i - 1)).forEach(n => n.remove());
      }
    }
  } catch (e) {
    for (const pre of blocks) pre.after(util.el("div", { class: "mermaid-note", text: "Can't draw the mermaid diagram; here's the code." }));
  }
}
