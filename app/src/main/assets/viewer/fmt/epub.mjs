// EPUB 2/3, lettura a capitoli. Scritto qui su JSZip invece di epub.js: epub.js vuole iframe con script
// e URL blob verso se stesso, cioe' proprio le cose che la CSP di questa pagina spegne apposta.
// Ogni capitolo: XHTML sanificato (DOMPurify, niente script), CSS del libro chiuso in uno Shadow DOM
// (non tocca la pagina), immagini dal pacchetto, link interni che portano al capitolo giusto.
export async function render({ root, zip, util }) {
  await util.need("purify");
  const xml = async p => { const f = zip.file(p); return f ? new DOMParser().parseFromString(await f.async("string"), "application/xml") : null; };
  const container = await xml("META-INF/container.xml");
  const opfPath = container && container.getElementsByTagName("rootfile")[0]?.getAttribute("full-path");
  if (!opfPath) throw Object.assign(new Error("EPUB without an index (container.xml)."), { user: true });
  const opf = await xml(opfPath);
  const base = opfPath.includes("/") ? opfPath.slice(0, opfPath.lastIndexOf("/") + 1) : "";
  const join = (b, h) => {
    const parts = (b + h).split("/"); const out = [];
    for (const p of parts) { if (p === "..") out.pop(); else if (p !== "." && p !== "") out.push(p); }
    return out.join("/");
  };
  const manifest = {};
  for (const it of opf.getElementsByTagNameNS("*", "item")) manifest[it.getAttribute("id")] = { href: join(base, decodeURIComponent(it.getAttribute("href"))), type: it.getAttribute("media-type"), props: it.getAttribute("properties") || "" };
  const spine = [...opf.getElementsByTagNameNS("*", "itemref")].filter(r => r.getAttribute("linear") !== "no").map(r => manifest[r.getAttribute("idref")]).filter(Boolean);
  if (!spine.length) throw Object.assign(new Error("EPUB without chapters."), { user: true });
  const title = opf.getElementsByTagNameNS("*", "title")[0]?.textContent || "";
  const author = opf.getElementsByTagNameNS("*", "creator")[0]?.textContent || "";
  if (opf.getElementsByTagNameNS("*", "encryption").length || zip.file("META-INF/encryption.xml")) util.notice("This book may be DRM-protected: some parts may not show");

  // indice: nav.xhtml (EPUB 3) o toc.ncx (EPUB 2); in mancanza, la spina
  let toc = [];
  const nav = Object.values(manifest).find(m => /\bnav\b/.test(m.props));
  if (nav) {
    const d = await xml(nav.href);
    const navEl = d && [...d.getElementsByTagNameNS("*", "nav")].find(n => /toc/.test(n.getAttributeNS("http://www.idpf.org/2007/ops", "type") || n.getAttribute("epub:type") || "toc")) || d?.getElementsByTagNameNS("*", "nav")[0];
    const navBase = nav.href.slice(0, nav.href.lastIndexOf("/") + 1);
    if (navEl) for (const a of navEl.getElementsByTagNameNS("*", "a")) {
      const depth = countAncestors(a, "li") - 1;
      toc.push({ label: a.textContent.trim(), href: join(navBase, decodeURIComponent(a.getAttribute("href") || "")), depth });
    }
  }
  if (!toc.length) {
    const ncxId = opf.getElementsByTagNameNS("*", "spine")[0]?.getAttribute("toc");
    const ncx = ncxId && manifest[ncxId] ? await xml(manifest[ncxId].href) : null;
    if (ncx) {
      const nb = manifest[ncxId].href.slice(0, manifest[ncxId].href.lastIndexOf("/") + 1);
      for (const np of ncx.getElementsByTagNameNS("*", "navPoint")) {
        toc.push({ label: np.getElementsByTagNameNS("*", "text")[0]?.textContent.trim() || "", href: join(nb, decodeURIComponent(np.getElementsByTagNameNS("*", "content")[0]?.getAttribute("src") || "")), depth: countAncestors(np, "navPoint") });
      }
    }
  }
  if (!toc.length) toc = spine.map((s, i) => ({ label: `Chapter ${i + 1}`, href: s.href, depth: 0 }));

  const media = new Map();
  const dataOf = async p => {
    if (!media.has(p)) {
      const f = zip.file(p);
      media.set(p, f ? URL.createObjectURL(new Blob([await f.async("uint8array")], { type: manifest[Object.keys(manifest).find(k => manifest[k].href === p)]?.type || util.mimeOf(p) })) : null);
    }
    return media.get(p);
  };

  const bar = util.el("div", { class: "book-bar" });
  const sel = util.el("select", { "aria-label": "Contents" });
  toc.forEach((t, i) => sel.append(util.el("option", { value: String(i), text: " ".repeat(Math.min(3, t.depth)) + (t.label || `Chapter ${i + 1}`) })));
  bar.append(sel);
  const head = util.el("div", { class: "book-title", text: [title, author].filter(Boolean).join(" · ") });
  const host = util.el("div", { class: "shadow-host" });
  const shadow = host.attachShadow({ mode: "open" });
  const prev = util.el("button", { class: "btn", type: "button", text: "‹ Previous" });
  const next = util.el("button", { class: "btn", type: "button", text: "Next ›" });
  const navBar = util.el("div", { class: "book-nav" }, prev, next);
  root.append(bar, head, host, navBar);

  let cur = -1;
  const idxOf = href => spine.findIndex(s => s.href === href.split("#")[0]);
  async function show(i, frag) {
    if (i < 0 || i >= spine.length) return;
    cur = i;
    const item = spine[i];
    const raw = await zip.file(item.href)?.async("string");
    if (raw == null) { shadow.replaceChildren(util.el("p", { text: "Chapter missing from the file." })); return; }
    let d = new DOMParser().parseFromString(raw, "application/xhtml+xml");
    if (d.getElementsByTagName("parsererror").length) d = new DOMParser().parseFromString(raw, "text/html");
    const dir = item.href.slice(0, item.href.lastIndexOf("/") + 1);
    let css = "";
    for (const l of d.querySelectorAll('link[rel~="stylesheet"]')) {
      const p = join(dir, decodeURIComponent(l.getAttribute("href") || ""));
      const t = await zip.file(p)?.async("string");
      if (t) css += await fixCss(t, p.slice(0, p.lastIndexOf("/") + 1)) + "\n";
    }
    for (const s of d.querySelectorAll("style")) css += await fixCss(s.textContent, dir) + "\n";
    // risorse: immagini (anche <image> dentro SVG) dal pacchetto
    for (const img of d.querySelectorAll("img[src]")) {
      const p = join(dir, decodeURIComponent(img.getAttribute("src")));
      const u = await dataOf(p);
      if (u) img.setAttribute("src", u); else img.removeAttribute("src");
    }
    for (const im of d.getElementsByTagNameNS("http://www.w3.org/2000/svg", "image")) {
      const h = im.getAttributeNS("http://www.w3.org/1999/xlink", "href") || im.getAttribute("href");
      if (h) { const u = await dataOf(join(dir, decodeURIComponent(h))); if (u) { im.setAttribute("href", u); im.removeAttributeNS("http://www.w3.org/1999/xlink", "href"); } }
    }
    for (const a of d.querySelectorAll("a[href]")) {
      const h = a.getAttribute("href");
      if (/^[a-z]+:/i.test(h)) continue;
      a.setAttribute("href", "#");
      a.setAttribute("data-epub", h.startsWith("#") ? item.href + h : join(dir, decodeURIComponent(h)));
    }
    const body = d.body || d.getElementsByTagName("body")[0] || d.documentElement;
    const clean = util.sanitize(new XMLSerializer().serializeToString(body).replace(/^<body[^>]*>|<\/body>$/g, ""), {
      ADD_ATTR: ["data-epub"], FORBID_TAGS: ["style", "link", "form", "input", "button", "iframe", "object", "embed"], ADD_URI_SAFE_ATTR: ["href"],
      ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|blob|data):|#|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i,
    }, src => /^(blob|data):/i.test(src) ? src : null);
    const style = document.createElement("style");
    style.textContent = `:host{display:block;color:inherit}
.bk{font-size:var(--fs);line-height:1.6;max-width:44em;margin:0 auto;overflow-wrap:break-word}
.bk img,.bk svg{max-width:100%;height:auto}
.bk a{color:var(--link)}
.bk table{max-width:100%;overflow-x:auto;display:block}
.bk pre{white-space:pre-wrap}
${css}
.bk{color:var(--fg)!important;background:transparent!important}`;
    const wrap = document.createElement("div");
    wrap.className = "bk";
    wrap.innerHTML = clean;
    shadow.replaceChildren(style, wrap);
    const ti = toc.findIndex(t => t.href.split("#")[0] === item.href);
    if (ti >= 0) sel.value = String(ti);
    prev.disabled = i === 0; next.disabled = i === spine.length - 1;
    if (frag) { const t = shadow.getElementById(frag) || shadow.querySelector(`[name="${CSS.escape(frag)}"]`); if (t) { t.scrollIntoView(); return; } }
    scrollTo(0, 0);
  }
  // CSS del libro: url(...) relativi -> blob; selettori html/body -> contenitore del capitolo
  async function fixCss(t, dir) {
    const urls = [...t.matchAll(/url\(\s*['"]?([^'")]+)['"]?\s*\)/g)].map(m => m[1]);
    for (const u of urls) {
      if (/^(data|https?|blob):/i.test(u)) continue;
      const b = await dataOf(join(dir, decodeURIComponent(u)));
      t = t.split(u).join(b || "about:invalid");
    }
    return t.replace(/@import[^;]+;/g, "").replace(/(^|[\s,}>+~])(html|body)(?=[\s,{.#:[>+~])/g, "$1.bk");
  }
  shadow.addEventListener("click", e => {
    const a = e.composedPath().find(n => n.tagName === "A");
    if (!a) return;
    const h = a.getAttribute("data-epub");
    if (!h) return;
    e.preventDefault(); e.stopPropagation();
    const [file, frag] = h.split("#");
    const i = idxOf(file);
    if (i >= 0) show(i, frag);
  });
  sel.addEventListener("change", () => { const t = toc[Number(sel.value)]; const i = idxOf(t.href); if (i >= 0) show(i, t.href.split("#")[1]); });
  prev.addEventListener("click", () => show(cur - 1));
  next.addEventListener("click", () => show(cur + 1));
  await show(0);
}

function countAncestors(n, name) {
  let k = 0;
  for (let p = n.parentNode; p && p.nodeType === 1; p = p.parentNode) if (p.localName === name) k++;
  return k;
}
