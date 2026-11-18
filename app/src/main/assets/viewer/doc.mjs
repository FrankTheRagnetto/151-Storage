// 151 Viewer — pagina unica per i documenti "ricchi": Markdown, Office, OpenDocument, RTF, EPUB,
// ZIP, email, MHTML, vCard, iCalendar, sottotitoli, font. Ogni formato e' un modulo in fmt/ caricato
// solo quando serve; le librerie (assets/lib/) si caricano solo quando il formato le chiede.
// Niente rete: tutto arriva da /assets/ o da /file/<token>/ (DocServer). La CSP lo ribadisce.
const P = new URLSearchParams(location.search);
const SRC = P.get("src") || "/file/none/";
const SIZE = Number(P.get("size") || -1);
const NAME = P.get("name") || "file";
const FMT = P.get("fmt") || "auto";
const A = window.Android || new Proxy({}, { get: (_, k) => (...a) => console.log("[Android." + String(k) + "]", ...a) });
const DARK = P.get("theme") === "dark";
document.documentElement.classList.toggle("theme-dark", DARK);

const doc = document.getElementById("doc");
const note = document.getElementById("note");
/** Oltre questa soglia un documento "ricco" non si impagina: meglio un messaggio che una WebView morta. */
const MAX_BYTES = 64 * 1024 * 1024;

// ------------------------------------------------------------------ librerie (script classici UMD)
const LIBS = {
  jszip: ["jszip/jszip.min.js"],
  docx: ["jszip/jszip.min.js", "docx-preview/docx-preview.min.js"],
  mammoth: ["mammoth/mammoth.browser.min.js"],
  xlsx: ["xlsx/xlsx.full.min.js"],
  md: ["markdown-it/markdown-it.umd.min.js", "markdown-it-footnote/markdown-it-footnote.min.js", "markdown-it-mark/markdown-it-mark.min.js"],
  hljs: ["highlight/highlight.min.js", "highlight/extra-languages.min.js"],
  katex: ["katex/katex.min.js"],
  purify: ["dompurify/purify.min.js"],
  mermaid: ["mermaid/mermaid.min.js"],
  utif: ["fflate/fflate.umd.js", "utif/UTIF.js"],
  fflate: ["fflate/fflate.umd.js"],
};
const CSS = {
  hljs: [DARK ? "highlight/github-dark.min.css" : "highlight/github.min.css"],
  katex: ["katex/katex.min.css"],
};
const loaded = new Map();
function script(src) {
  if (!loaded.has(src)) {
    loaded.set(src, new Promise((res, rej) => {
      const s = document.createElement("script");
      s.src = "/assets/lib/" + src;
      s.onload = res;
      s.onerror = () => rej(new Error("missing library: " + src));
      document.head.append(s);
    }));
  }
  return loaded.get(src);
}
async function need(name) {
  for (const c of CSS[name] || []) {
    if (!document.querySelector(`link[data-lib="${c}"]`)) {
      const l = document.createElement("link");
      l.rel = "stylesheet"; l.href = "/assets/lib/" + c; l.dataset.lib = c;
      document.head.append(l);
    }
  }
  for (const s of LIBS[name]) await script(s);
}

// ------------------------------------------------------------------ utilita' condivise dai moduli
export const util = {
  esc: s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])),
  el(tag, attrs, ...kids) {
    const e = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === "class") e.className = v;
      else if (k === "style" && typeof v === "object") Object.assign(e.style, v);
      else if (k === "text") e.textContent = v;
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? "" : v);
    }
    for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : String(k));
    return e;
  },
  decodeText(bytes) {
    if (bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) return new TextDecoder("utf-8").decode(bytes.subarray(3));
    if (bytes[0] === 0xFF && bytes[1] === 0xFE) return new TextDecoder("utf-16le").decode(bytes.subarray(2));
    if (bytes[0] === 0xFE && bytes[1] === 0xFF) return new TextDecoder("utf-16be").decode(bytes.subarray(2));
    try { return new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
    catch { return new TextDecoder("windows-1252").decode(bytes); }
  },
  fmtSize(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return Math.round(n / 1024) + " KB";
    return (n / 1024 / 1024).toFixed(1) + " MB";
  },
  mimeOf(name) {
    const e = name.toLowerCase().split(".").pop();
    return ({ png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", svg: "image/svg+xml",
      bmp: "image/bmp", avif: "image/avif", ico: "image/x-icon", css: "text/css", ttf: "font/ttf", otf: "font/otf", woff: "font/woff",
      woff2: "font/woff2", mp3: "audio/mpeg", mp4: "video/mp4", html: "text/html", xhtml: "application/xhtml+xml" })[e] || "application/octet-stream";
  },
  b64(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  },
  dataUrl(bytes, mime) { return `data:${mime};base64,${util.b64(bytes)}`; },
  /** Avviso in cima (niente rete, troncato, qualita' ridotta...). */
  notice(msg) {
    note.textContent = note.hidden ? msg : note.textContent + " · " + msg;
    note.hidden = false;
    document.body.classList.add("has-note");
  },
  /** Zoom: "reflow" = pinch cambia il carattere e il testo va a capo; "page" = zoom nativo su pagina larga W. */
  mode(kind, pageWidth) {
    const vp = document.getElementById("vp");
    if (kind === "page") {
      // pagina larga (Word, diapositive): CSS zoom per stare nella larghezza del telefono (il testo resta
      // nitido: e' impaginazione, non un'immagine ingrandita), poi zoom nativo della WebView col pinch.
      vp.setAttribute("content", "width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=6, user-scalable=yes");
      doc.style.zoom = String(Math.min(1, (innerWidth || 412) / pageWidth));
      pinchOff = true;
    } else {
      doc.style.zoom = "";
      vp.setAttribute("content", "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no");
      pinchOff = kind === "none";
    }
  },
  need,
  /** DOMPurify con le immagini gia' decise DENTRO il documento inerte del sanificatore: cosi' nessuna
   *  <img> remota viene mai creata nella pagina (niente tentativi di rete, nemmeno bloccati dalla CSP).
   *  mapSrc(src) -> nuovo src, oppure null per toglierlo (l'<img> resta, con data-blocked). */
  sanitize(html, cfg, mapSrc) {
    const hook = node => {
      if (node.nodeName !== "IMG" && node.nodeName !== "SOURCE" && node.nodeName !== "VIDEO" && node.nodeName !== "AUDIO" && node.nodeName !== "image") return;
      node.removeAttribute("srcset");
      for (const a of ["src", "href", "xlink:href", "poster"]) {
        if (!node.hasAttribute(a)) continue;
        const v = node.getAttribute(a);
        const n = mapSrc ? mapSrc(v, node) : (/^(data|blob):/i.test(v) ? v : null);
        if (n == null) { node.removeAttribute(a); node.setAttribute("data-blocked", v.slice(0, 300)); }
        else node.setAttribute(a, n);
      }
    };
    DOMPurify.addHook("afterSanitizeAttributes", hook);
    try { return DOMPurify.sanitize(html, cfg); } finally { DOMPurify.removeHook("afterSanitizeAttributes"); }
  },
  A, P, SRC, NAME, DARK,
  relUrl: p => `${SRC}rel?p=${encodeURIComponent(p)}`,
};

// ------------------------------------------------------------------ riconoscimento formato (ZIP/CFB/estensione)
const EXT_FMT = {
  md: "md", markdown: "md", mdown: "md", mkd: "md",
  docx: "docx", docm: "docx", dotx: "docx", dotm: "docx", doc: "doc", dot: "doc",
  xlsx: "xlsx", xlsm: "xlsx", xltx: "xlsx", xlsb: "xlsx", xls: "xls", ods: "ods", fods: "ods", csv: "csv", tsv: "tsv",
  pptx: "pptx", pptm: "pptx", ppsx: "pptx", potx: "pptx", ppt: "ppt", pps: "ppt", odp: "odp",
  odt: "odt", ott: "odt", rtf: "rtf", epub: "epub", zip: "zip", eml: "eml", mht: "mhtml", mhtml: "mhtml",
  vcf: "vcf", vcard: "vcf", ics: "ics", ical: "ics", srt: "srt", vtt: "vtt",
  ttf: "font", otf: "font", woff: "font", woff2: "font", ttc: "font",
  png: "image", jpg: "image", jpeg: "image", gif: "image", webp: "image", svg: "image", bmp: "image", avif: "image",
  pdf: "pdf", html: "htmlsrc", htm: "htmlsrc",
  // v5
  msg: "msg", pages: "iwork", numbers: "iwork", key: "iwork", xps: "xps", oxps: "xps",
  cbz: "comic", cbr: "comic", cb7: "comic", cbt: "comic", mobi: "mobi", azw: "mobi", azw3: "mobi", prc: "mobi", fb2: "fb2",
  "7z": "archive", rar: "archive", tar: "archive", gz: "archive", tgz: "archive", bz2: "archive", tbz2: "archive", tbz: "archive",
  xz: "archive", txz: "archive", zst: "archive", tzst: "archive", lzma: "archive",
  ipynb: "ipynb", geojson: "geo", gpx: "geo", kml: "geo", kmz: "geo", drawio: "drawio", dio: "drawio", excalidraw: "excalidraw",
  url: "link", webloc: "link", desktop: "link", torrent: "torrent", pem: "cert", crt: "cert", cer: "cert", der: "cert",
  p7m: "p7m", p7s: "p7m", p7b: "p7m", p7c: "p7m", tif: "tiff", tiff: "tiff", psd: "psd", psb: "psd", eps: "eps", epsf: "eps", ps: "eps", ai: "eps",
  djvu: "djvu", djv: "djvu",
};
const IMG_EXT = /\.(png|jpe?g|gif|webp|bmp|avif|jxl)$/i;
const P7_OID = [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x02];
function has7(b) {
  if (b[0] !== 0x30) return false;
  outer: for (let i = 1; i < Math.min(24, b.length - P7_OID.length); i++) {
    for (let k = 0; k < P7_OID.length; k++) if (b[i + k] !== P7_OID[k]) continue outer;
    return true;
  }
  return false;
}
/** Famiglia "archivio" dai primi byte (7z, RAR, gzip, bzip2, xz, zstd, tar), o null. */
export function archiveKind(b) {
  if (b[0] === 0x37 && b[1] === 0x7A && b[2] === 0xBC && b[3] === 0xAF) return "7z";
  if (b[0] === 0x52 && b[1] === 0x61 && b[2] === 0x72 && b[3] === 0x21 && b[4] === 0x1A && b[5] === 0x07) return "rar";
  if (b[0] === 0x1F && b[1] === 0x8B) return "gz";
  if (b[0] === 0x42 && b[1] === 0x5A && b[2] === 0x68) return "bz2";
  if (b[0] === 0xFD && b[1] === 0x37 && b[2] === 0x7A && b[3] === 0x58 && b[4] === 0x5A) return "xz";
  if (b[0] === 0x28 && b[1] === 0xB5 && b[2] === 0x2F && b[3] === 0xFD) return "zst";
  if (b.length > 262 && String.fromCharCode(...b.subarray(257, 262)) === "ustar") return "tar";
  return null;
}
const ascii = (b, from, len) => String.fromCharCode(...b.subarray(from, from + len));
export async function detect(bytes, name, hint) {
  const ext = name.toLowerCase().includes(".") ? name.toLowerCase().split(".").pop() : "";
  const isZip = bytes[0] === 0x50 && bytes[1] === 0x4B && (bytes[2] === 3 || bytes[2] === 5);
  const isCfb = bytes[0] === 0xD0 && bytes[1] === 0xCF && bytes[2] === 0x11 && bytes[3] === 0xE0;
  if (isZip) {
    await need("jszip");
    const z = await JSZip.loadAsync(bytes);
    const has = p => z.file(p) != null;
    if (has("word/document.xml")) return { fmt: "docx", zip: z };
    if (has("xl/workbook.xml") || has("xl/workbook.bin")) return { fmt: "xlsx", zip: z };
    if (has("ppt/presentation.xml")) return { fmt: "pptx", zip: z };
    const mt = z.file("mimetype") ? (await z.file("mimetype").async("string")).trim() : "";
    if (mt === "application/epub+zip" || has("META-INF/container.xml") && !has("content.xml")) return { fmt: "epub", zip: z };
    if (mt.includes("opendocument.text")) return { fmt: "odt", zip: z };
    if (mt.includes("opendocument.spreadsheet")) return { fmt: "ods", zip: z };
    if (mt.includes("opendocument.presentation")) return { fmt: "odp", zip: z };
    if (has("content.xml") && has("styles.xml")) return { fmt: "odt", zip: z };
    // v5
    const names = Object.keys(z.files);
    if (names.some(n => /\.fdseq$/i.test(n)) || /xps|fixedrepresentation/i.test(await z.file("[Content_Types].xml")?.async("string") || "") && names.some(n => /\.fpage$/i.test(n))) return { fmt: "xps", zip: z };
    if (names.some(n => /(^|\/)(Index\/Document\.iwa|Index\.zip|index\.apxl(\.gz)?)$/.test(n)) || (has("index.xml") && (has("QuickLook/Thumbnail.jpg") || has("QuickLook/Preview.pdf"))) || ["pages", "numbers", "key"].includes(ext) && (has("preview.jpg") || has("QuickLook/Thumbnail.jpg"))) return { fmt: "iwork", zip: z };
    if (has("doc.kml") || ext === "kmz" || names.filter(n => !z.files[n].dir).every(n => /\.kml$/i.test(n)) && names.length) return { fmt: "geo", zip: z };
    const files = names.filter(n => !z.files[n].dir && !/(^|\/)(__MACOSX\/|\.DS_Store$|Thumbs\.db$)/.test(n));
    if (ext === "cbz" || hint === "comic" || files.length >= 2 && files.every(n => IMG_EXT.test(n) || /\.(xml|txt|nfo|sfv)$/i.test(n)) && files.filter(n => IMG_EXT.test(n)).length >= 2) return { fmt: "comic", zip: z };
    if (has("AndroidManifest.xml") && has("classes.dex")) return { fmt: "zip", zip: z, apk: true };
    return { fmt: "zip", zip: z };
  }
  if (isCfb) {
    await need("xlsx");
    const cfb = XLSX.CFB.read(bytes, { type: "array" });
    const has = n => cfb.FileIndex.some(f => f.name === n);
    if (has("WordDocument")) return { fmt: "doc", cfb };
    if (has("Workbook") || has("Book")) return { fmt: "xls", cfb };
    if (has("PowerPoint Document")) return { fmt: "ppt", cfb };
    if (cfb.FileIndex.some(f => f.name.startsWith("__substg1.0_"))) return { fmt: "msg", cfb };
    if (has("EncryptedPackage")) return { fmt: "encrypted", cfb };
    return { fmt: "cfb", cfb };
  }
  const head = ascii(bytes, 0, Math.min(bytes.length, 64));
  if (head.startsWith("{\\rtf")) return { fmt: "rtf" };
  // v5: firme dai byte
  const ak = archiveKind(bytes);
  if (ak) return { fmt: ["cbr", "cb7", "cbt", "cbz"].includes(ext) || hint === "comic" ? "comic" : "archive", archive: ak };
  if (head.startsWith("II*\0") || head.startsWith("MM\0*") || head.startsWith("II+\0") || head.startsWith("MM\0+")) return { fmt: "tiff" };
  if (head.startsWith("8BPS")) return { fmt: "psd" };
  if (bytes[0] === 0xC5 && bytes[1] === 0xD0 && bytes[2] === 0xD3 && bytes[3] === 0xC6 || head.startsWith("%!PS")) return { fmt: "eps" };
  if (head.startsWith("AT&TFORM")) return { fmt: "djvu" };
  if (bytes.length > 68 && (ascii(bytes, 60, 8) === "BOOKMOBI" || ascii(bytes, 60, 8) === "TEXtREAd")) return { fmt: "mobi" };
  if (has7(bytes)) return { fmt: "p7m" };
  if (/^d(8:announce|4:info|13:announce-list|7:comment|10:created by)/.test(head)) return { fmt: "torrent" };
  if (head.startsWith("bplist00")) return { fmt: ext === "webloc" ? "link" : "bplist" };
  if (/^(wOFF|wOF2|OTTO|true|ttcf)/.test(head) || (bytes[0] === 0 && bytes[1] === 1 && bytes[2] === 0 && bytes[3] === 0 && bytes.length > 12)) return { fmt: "font" };
  if (bytes[0] === 0x89 && head.slice(1, 4) === "PNG" || bytes[0] === 0xFF && bytes[1] === 0xD8 || head.startsWith("GIF8") || head.startsWith("RIFF") && ascii(bytes, 8, 4) === "WEBP") return { fmt: "image" };
  if (head.startsWith("%PDF")) return { fmt: "pdf" };
  if ((hint === "md" || ext === "md") && /excalidraw-plugin\s*:/.test(util.decodeText(bytes.subarray(0, 64 * 1024)))) return { fmt: "excalidraw" };
  if (hint && hint !== "auto" && !["docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "zip", "doc", "xls", "ppt"].includes(hint)) return { fmt: hint };
  const t = util.decodeText(bytes.subarray(0, 4096));
  if (/<FictionBook[\s>]/.test(t)) return { fmt: "fb2" };
  if (/<(?:[\w-]+:)?FatturaElettronica[\s>]/.test(t)) return { fmt: "fattura" };
  if (/<gpx[\s>]/.test(t) || /<kml[\s>]/.test(t)) return { fmt: "geo" };
  if (/<mxfile[\s>]|^\s*<mxGraphModel/.test(t)) return { fmt: "drawio" };
  if (/"type"\s*:\s*"excalidraw"/.test(t)) return { fmt: "excalidraw" };
  if (/^\s*\{/.test(t) && /"nbformat"\s*:/.test(util.decodeText(bytes.subarray(Math.max(0, bytes.length - 4096))) + t)) return { fmt: "ipynb" };
  if (/^\s*-----BEGIN (PKCS7|CMS)/.test(t)) return { fmt: "p7m" };
  if (/^\s*-----BEGIN /.test(t)) return { fmt: "cert" };
  if (/^\s*\[(InternetShortcut|Desktop Entry)\]/i.test(t)) return { fmt: "link" };
  if (/^\s*BEGIN:VCARD/i.test(t)) return { fmt: "vcf" };
  if (/^\s*BEGIN:VCALENDAR/i.test(t)) return { fmt: "ics" };
  if (/^WEBVTT/.test(t)) return { fmt: "vtt" };
  if (/^\uFEFF?\s*\d+\r?\n\d{1,2}:\d\d:\d\d[,.]\d{1,3}\s*-->/.test(t)) return { fmt: "srt" };
  if (/^(?:[\w-]+:.*\r?\n(?:[ \t].*\r?\n)*){2,}/.test(t) && /^(mime-version|content-type):/im.test(t)) {
    return { fmt: /multipart\/related/i.test(t) && /text\/html/i.test(t) && !/^(received|return-path|delivered-to):/im.test(t) ? "mhtml" : "eml" };
  }
  if (EXT_FMT[ext]) return { fmt: EXT_FMT[ext] };
  if (hint && hint !== "auto") return { fmt: hint };
  return { fmt: "text" };
}

const LABEL = { md: "Markdown", docx: "Word", doc: "Word 97-2003", xlsx: "Excel", xls: "Excel 97-2003", ods: "OpenDocument Spreadsheet",
  csv: "CSV", tsv: "TSV", pptx: "PowerPoint", ppt: "PowerPoint 97-2003", odp: "OpenDocument Presentation", odt: "OpenDocument Text",
  rtf: "RTF", epub: "EPUB", zip: "ZIP", eml: "Email", mhtml: "MHTML", vcf: "vCard", ics: "Calendar", srt: "Subtitles", vtt: "Subtitles",
  font: "Font", text: "Text", image: "Image",
  msg: "Outlook email", iwork: "Apple iWork", xps: "XPS", comic: "Comic", archive: "Archive", mobi: "Ebook Kindle", fb2: "FictionBook",
  ipynb: "Jupyter Notebook", geo: "Map data", drawio: "draw.io diagram", excalidraw: "Excalidraw drawing", link: "Shortcut",
  torrent: "Torrent", fattura: "E-invoice (FatturaPA)", cert: "Certificate", p7m: "Digital signature", tiff: "TIFF", psd: "Photoshop", eps: "PostScript", pdf: "PDF" };

const MODULES = {
  md: "md", docx: "office", doc: "office", xlsx: "sheet", xls: "sheet", ods: "sheet", csv: "sheet", tsv: "sheet",
  pptx: "slides", ppt: "slides", odp: "slides", odt: "odt", rtf: "rtf", epub: "epub", zip: "zip",
  eml: "mail", mhtml: "mail", vcf: "small", ics: "small", srt: "small", vtt: "small", font: "small", text: "small", image: "small",
  htmlsrc: "small",
  // v5
  msg: "msg", iwork: "iwork", xps: "xps", comic: "comic", archive: "archive", mobi: "ebook2", fb2: "ebook2", ipynb: "notebook",
  geo: "geo", fattura: "fattura", drawio: "diagram", excalidraw: "diagram", link: "misc", torrent: "misc", cert: "crypto", p7m: "crypto",
  tiff: "raster", psd: "raster", eps: "raster", pdf: "pdfin",
};

// ------------------------------------------------------------------ rendering (anche per i file dentro uno ZIP/email)
const stack = []; // [{name, node, scroll}] per tornare all'elenco dello ZIP / all'email
const backBar = document.getElementById("back");
document.getElementById("backBtn").addEventListener("click", () => popInner());

export async function renderInto(root, bytes, name, hint, depth = 0) {
  const d = await detect(bytes, name, hint);
  const mod = MODULES[d.fmt];
  if (!mod) {
    const msg = { encrypted: "This document is password-protected and can't be opened without it.",
      cfb: "Unknown type of binary Office file.", bplist: "Apple property list (binary): can't be shown.",
      djvu: "DjVu isn't supported (the only JavaScript reader is GPL-licensed, which this app doesn't use)." }[d.fmt] || "Unrecognized format.";
    throw Object.assign(new Error(msg), { user: true });
  }
  const m = await import(`./fmt/${mod}.mjs`);
  if (depth === 0) A.onFormat(LABEL[d.fmt] || d.fmt);
  // dentro un altro file (ZIP, allegato, firma .p7m) il sottotitolo resta quello del contenitore
  const u = depth === 0 ? util : { ...util, A: { onFormat() {}, onLoaded() {}, onFailed() {}, toast: m => A.toast(m), openLink: x => A.openLink(x), setDocTextSize: x => A.setDocTextSize(x) } };
  await m.render({ root, bytes, name, fmt: d.fmt, zip: d.zip, cfb: d.cfb, archive: d.archive, apk: d.apk, util: u, openInner, depth });
  return d.fmt;
}

async function openInner(bytes, name) {
  stack.push({ node: [...doc.childNodes], scroll: scrollY, vp: document.getElementById("vp").getAttribute("content"), pinchOff, zoom: doc.style.zoom, page: document.body.classList.contains("page-mode") });
  doc.style.zoom = "";
  document.body.classList.remove("page-mode");
  doc.replaceChildren(loadingBox());
  backBar.hidden = false;
  document.getElementById("backName").textContent = name;
  scrollTo(0, 0);
  try {
    const box = document.createElement("div");
    doc.append(box); // gia' nel documento: i moduli misurano (larghezza pagina Word…)
    await renderInto(box, bytes, name, EXT_FMT[name.toLowerCase().split(".").pop()] || "auto", stack.length);
    doc.replaceChildren(box);
  } catch (e) {
    doc.replaceChildren(errorBox(e));
  }
}
function popInner() {
  const s = stack.pop();
  if (!s) return;
  doc.replaceChildren(...s.node);
  document.getElementById("vp").setAttribute("content", s.vp);
  pinchOff = s.pinchOff;
  doc.style.zoom = s.zoom;
  document.body.classList.toggle("page-mode", s.page);
  backBar.hidden = stack.length === 0;
  requestAnimationFrame(() => scrollTo(0, s.scroll));
}
function loadingBox() { return util.el("div", { class: "loading", text: "Opening…" }); }
function errorBox(e) {
  return util.el("div", { class: "error" },
    util.el("div", { class: "error-title", text: "Can't show this file." }),
    util.el("div", { class: "error-detail", text: e && e.user ? e.message : String(e && e.message || e) }));
}

async function main() {
  let bytes;
  try {
    if (SIZE > MAX_BYTES) throw Object.assign(new Error(`File too large to preview (${util.fmtSize(SIZE)}).`), { user: true });
    const r = await fetch(`${SRC}doc`, { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    bytes = new Uint8Array(await r.arrayBuffer());
  } catch (e) {
    doc.replaceChildren(errorBox(e));
    A.onFailed(e.user ? e.message : "Can't read the file.");
    return;
  }
  const t0 = performance.now();
  try {
    const box = document.createElement("div");
    doc.append(box);
    await renderInto(box, bytes, NAME, FMT, 0);
    doc.replaceChildren(box);
    A.onLoaded(0);
    console.log(`rendered in ${Math.round(performance.now() - t0)} ms`);
  } catch (e) {
    console.error(e && e.stack || e);
    doc.replaceChildren(errorBox(e));
    A.onFailed(e && e.user ? e.message : "The document seems damaged or in an unknown format (" + String(e && e.message || e).slice(0, 120) + ").");
  }
}

// ------------------------------------------------------------------ pinch = dimensione del carattere (modo "reflow")
let pinchOff = false;
let fs = Math.min(40, Math.max(10, Number(P.get("fs") || 16)));
document.documentElement.style.setProperty("--fs", fs + "px");
let pinch = null, raf = 0;
window.addEventListener("touchstart", e => {
  if (pinchOff || e.touches.length !== 2) return;
  const [a, b] = e.touches;
  pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), fs, anchor: scrollY / Math.max(1, document.documentElement.scrollHeight) };
}, { passive: true });
window.addEventListener("touchmove", e => {
  if (!pinch || e.touches.length !== 2) return;
  e.preventDefault();
  const [a, b] = e.touches;
  const next = Math.round(Math.min(40, Math.max(10, pinch.fs * Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / pinch.d)) * 2) / 2;
  if (next === fs) return;
  fs = next;
  cancelAnimationFrame(raf);
  raf = requestAnimationFrame(() => {
    document.documentElement.style.setProperty("--fs", fs + "px");
    if (pinch) scrollTo(0, pinch.anchor * document.documentElement.scrollHeight);
  });
}, { passive: false });
window.addEventListener("touchend", e => {
  if (pinch && e.touches.length < 2) { pinch = null; A.setDocTextSize(fs); }
}, { passive: true });

// link: gli esterni li gestisce l'app (conferma), quelli relativi non portano da nessuna parte
document.addEventListener("click", e => {
  const a = e.target.closest && e.target.closest("a[href]");
  if (!a || a.dataset.internal) return;
  const h = a.getAttribute("href");
  if (h.startsWith("#")) {
    e.preventDefault();
    const t = document.getElementById(decodeURIComponent(h.slice(1))) || document.querySelector(`[name="${CSS.escape(h.slice(1))}"]`);
    if (t) t.scrollIntoView({ block: "start" });
    return;
  }
  if (/^(https?:|mailto:|tel:|geo:)/i.test(h)) {
    e.preventDefault();
    A.openLink(a.href);
    return;
  }
  e.preventDefault(); // link relativo a un altro file: non raggiungibile da qui
}, true);

window.V = { state: () => ({ fs, fmt: FMT, stack: stack.length }), back: () => { if (stack.length) { popInner(); return true; } return false; } };
main();
