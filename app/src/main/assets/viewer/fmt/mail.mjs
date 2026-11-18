// Email (.eml, RFC 822/MIME) e pagine web salvate (.mhtml/.mht). Parser MIME scritto qui:
// multipart annidati, quoted-printable, base64, intestazioni RFC 2047 (=?UTF-8?B?…?=), charset.
// EML: intestazioni, corpo HTML sanificato (o testo), immagini incorporate (cid:), allegati apribili.
// MHTML: la pagina con i suoi CSS e le sue immagini presi dall'archivio. Script SEMPRE spenti.
// In entrambi i casi niente rete: immagini e CSS remoti non si caricano (e il mittente non sa che hai letto).
const dec = new TextDecoder("latin1");

export async function render(ctx) {
  const msg = parse(ctx.bytes);
  if (ctx.fmt === "mhtml") return renderMhtml(ctx, msg);
  return renderEml(ctx, msg);
}

// ------------------------------------------------------------------ MIME
function parse(bytes) {
  // separa intestazioni e corpo sui byte (il corpo puo' essere binario)
  let i = 0, hEnd = -1;
  for (; i < bytes.length - 1; i++) {
    if (bytes[i] === 10 && bytes[i + 1] === 10) { hEnd = i; break; }
    if (bytes[i] === 13 && bytes[i + 1] === 10 && bytes[i + 2] === 13 && bytes[i + 3] === 10) { hEnd = i; break; }
  }
  const headText = dec.decode(hEnd >= 0 ? bytes.subarray(0, hEnd) : bytes);
  const body = hEnd >= 0 ? bytes.subarray(hEnd + (bytes[hEnd] === 13 ? 4 : 2)) : new Uint8Array(0);
  const headers = {};
  const order = [];
  for (const line of headText.replace(/\r?\n[ \t]+/g, " ").split(/\r?\n/)) {
    const m = /^([^:\s]+):\s*(.*)$/.exec(line);
    if (!m) continue;
    const k = m[1].toLowerCase();
    (headers[k] = headers[k] || []).push(m[2]);
    order.push(k);
  }
  const h = k => headers[k] ? headers[k][0] : "";
  const ct = h("content-type") || "text/plain";
  const type = ct.split(";")[0].trim().toLowerCase();
  const params = paramsOf(ct);
  const disp = h("content-disposition");
  const part = {
    headers, h, type, params, disp: disp.split(";")[0].trim().toLowerCase(),
    filename: decodeWords(paramsOf(disp).filename || params.name || ""),
    cid: h("content-id").replace(/^<|>$/g, "").trim(), location: h("content-location").trim(),
    children: [], raw: body,
  };
  if (type.startsWith("multipart/") && params.boundary) {
    for (const chunk of splitParts(body, params.boundary)) part.children.push(parse(chunk));
  } else if (type === "message/rfc822" && part.disp !== "attachment") {
    part.children.push(parse(decodeBody(body, h("content-transfer-encoding"))));
  }
  return part;
}
function paramsOf(v) {
  const out = {};
  const re = /;\s*([\w*-]+)\s*=\s*("(?:[^"\\]|\\.)*"|[^;]*)/g;
  let m;
  while ((m = re.exec(v || ""))) {
    let k = m[1].toLowerCase(), val = m[2].trim().replace(/^"|"$/g, "").replace(/\\(.)/g, "$1");
    if (k.endsWith("*")) { // RFC 2231: charset''valore%XX
      k = k.replace(/\*\d*\*?$/, "");
      const mm = /^([^']*)'[^']*'(.*)$/.exec(val);
      try { val = mm ? new TextDecoder(mm[1] || "utf-8").decode(Uint8Array.from(unescape(mm[2]), c => c.charCodeAt(0))) : decodeURIComponent(val); } catch {}
      out[k] = (out[k] && !out[k + "#done"] ? out[k] : "") + val;
    } else if (!(k in out)) out[k] = val;
  }
  return out;
}
function splitParts(body, boundary) {
  const s = dec.decode(body);
  const delim = "--" + boundary;
  const parts = [];
  let pos = s.indexOf(delim);
  while (pos >= 0) {
    let start = s.indexOf("\n", pos);
    if (start < 0) break;
    start++;
    if (s.startsWith("--", pos + delim.length)) break;
    let next = s.indexOf("\n" + delim, start);
    if (next < 0) next = s.length;
    let end = next;
    if (s[end - 1] === "\r") end--;
    parts.push(body.subarray(start, Math.max(start, end)));
    pos = next < s.length ? next + 1 : -1;
  }
  return parts;
}
function decodeBody(raw, enc) {
  enc = (enc || "").toLowerCase().trim();
  if (enc === "base64") {
    const b = dec.decode(raw).replace(/[^A-Za-z0-9+/]/g, "");
    const bin = atob(b.slice(0, b.length - b.length % 4));
    return Uint8Array.from(bin, c => c.charCodeAt(0));
  }
  if (enc === "quoted-printable") {
    const s = dec.decode(raw).replace(/=\r?\n/g, "");
    const out = new Uint8Array(s.length);
    let n = 0;
    for (let i = 0; i < s.length; i++) {
      if (s[i] === "=" && /^[0-9A-Fa-f]{2}$/.test(s.substr(i + 1, 2))) { out[n++] = parseInt(s.substr(i + 1, 2), 16); i += 2; }
      else out[n++] = s.charCodeAt(i) & 0xFF;
    }
    return out.subarray(0, n);
  }
  return raw;
}
function textOf(p) {
  const b = decodeBody(p.raw, p.h("content-transfer-encoding"));
  let cs = (p.params.charset || "").toLowerCase();
  if (!cs && p.type === "text/html") { const m = /<meta[^>]+charset=["']?([\w-]+)/i.exec(dec.decode(b.subarray(0, 2048))); if (m) cs = m[1].toLowerCase(); }
  try { return new TextDecoder(cs || "utf-8").decode(b); } catch { return new TextDecoder("utf-8").decode(b); }
}
export function decodeWords(s) {
  return String(s || "").replace(/(=\?[^?]+\?[bqBQ]\?[^?]*\?=)\s+(?==\?)/g, "$1").replace(/=\?([^?]+)\?([bqBQ])\?([^?]*)\?=/g, (_, cs, e, t) => {
    try {
      let bytes;
      if (e.toUpperCase() === "B") bytes = Uint8Array.from(atob(t), c => c.charCodeAt(0));
      else bytes = Uint8Array.from(t.replace(/_/g, " ").replace(/=([0-9A-Fa-f]{2})/g, (m, h) => String.fromCharCode(parseInt(h, 16))), c => c.charCodeAt(0));
      return new TextDecoder(cs.replace(/\*.*$/, "")).decode(bytes);
    } catch { return t; }
  });
}
function walk(p, fn) { fn(p); p.children.forEach(c => walk(c, fn)); }

// ------------------------------------------------------------------ EML
async function renderEml(ctx, msg) {
  const H = k => decodeWords(msg.h(k));
  // corpo: il miglior "alternative" (html > testo), allegati = tutto il resto
  let html = null, plain = null;
  const attachments = [], inline = new Map();
  const pickBody = p => {
    if (p.type === "multipart/alternative") {
      const kids = p.children;
      const h = [...kids].reverse().find(k => k.type === "text/html" || k.type.startsWith("multipart/"));
      const t = kids.find(k => k.type === "text/plain");
      if (t && !plain) plain = textOf(t);
      if (h) { if (h.type === "text/html") { if (!html) html = textOf(h); } else pickBody(h); }
      for (const k of kids) if (k !== h && k !== t) pickBody(k);
      return;
    }
    if (p.type.startsWith("multipart/")) { p.children.forEach(pickBody); return; }
    if (p.type === "message/rfc822" && p.children.length && p.disp !== "attachment") { attachments.push(p); return; }
    const isAtt = p.disp === "attachment" || (p.filename && !(p.cid && p.type.startsWith("image/")));
    if (!isAtt && p.type === "text/html" && !html) { html = textOf(p); return; }
    if (!isAtt && p.type === "text/plain" && !plain) { plain = textOf(p); return; }
    if (p.cid) inline.set(p.cid, { type: p.type, bytes: () => decodeBody(p.raw, p.h("content-transfer-encoding")) });
    if (isAtt || !p.cid) attachments.push(p);
  };
  pickBody(msg);
  const date = msg.h("date");
  await showMail(ctx, {
    subject: H("subject"), rows: [["From", H("from")], ["To", H("to")], ["Cc", H("cc")], ["Bcc", H("bcc")]],
    date: date ? new Date(date) : null, dateRaw: date, html, plain, inline,
    attachments: attachments.map(a => {
      const isMsg = a.type === "message/rfc822";
      const name = a.filename || (isMsg ? decodeWords(a.children[0]?.h("subject") || "message") + ".eml" : "attachment" + (a.type.startsWith("image/") ? "." + a.type.split("/")[1] : ""));
      return { name, bytes: decodeBody(a.raw, a.h("content-transfer-encoding")) };
    }),
  });
}

/**
 * Vista di un messaggio (EML e Outlook .msg): intestazioni, corpo HTML sanificato (o testo), immagini
 * incorporate (cid:), allegati apribili. m = { subject, rows: [[etichetta, valore]], date, dateRaw, html, plain,
 * inline: Map(cid -> { type, bytes() }), attachments: [{ name, bytes }] }
 */
export async function showMail({ root, util, openInner }, m) {
  await util.need("purify");
  const head = util.el("div", { class: "mail-head" }, util.el("div", { class: "mail-subject", text: m.subject || "(no subject)" }));
  const row = (label, v) => v && head.append(util.el("div", { class: "mail-row" }, util.el("b", { text: label }), util.el("span", { text: v })));
  for (const [k, v] of m.rows) row(k, v);
  if (m.date || m.dateRaw) row("Date", m.date && !isNaN(m.date) ? m.date.toLocaleString("en-GB", { dateStyle: "full", timeStyle: "short" }) : m.dateRaw);
  root.append(head);
  const { html, plain, inline } = m;

  const bodyBox = util.el("div", { class: "mail-body" });
  const tools = util.el("div", { class: "mail-tools" });
  let showHtml = !!html;
  const draw = () => {
    bodyBox.replaceChildren();
    if (showHtml && html) {
      let remote = 0;
      const cidUrl = new Map();
      const clean = util.sanitize(html, { WHOLE_DOCUMENT: false, FORBID_TAGS: ["form", "input", "button", "iframe", "object", "embed", "link", "meta", "base", "video", "audio"], ADD_TAGS: ["style"], FORCE_BODY: true, ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|tel|cid|data|blob):|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i }, src => {
        if (/^cid:/i.test(src)) {
          const id = decodeURIComponent(src.slice(4));
          const p = inline.get(id) || inline.get(id.replace(/@.*$/, ""));
          if (!p) return null;
          if (!cidUrl.has(id)) cidUrl.set(id, URL.createObjectURL(new Blob([p.bytes()], { type: p.type })));
          return cidUrl.get(id);
        }
        if (/^data:image\//i.test(src)) return src;
        const byName = !/^[a-z][a-z0-9+.-]*:/i.test(src) && inline.get("name:" + decodeURIComponent(src).toLowerCase());
        if (byName) {
          if (!cidUrl.has(src)) cidUrl.set(src, URL.createObjectURL(new Blob([byName.bytes()], { type: byName.type })));
          return cidUrl.get(src);
        }
        remote++;
        return null;
      });
      const host = util.el("div", { class: "shadow-host" });
      const sh = host.attachShadow({ mode: "open" });
      const wrap = document.createElement("div");
      wrap.innerHTML = clean;
      for (const img of wrap.querySelectorAll("img[data-blocked]")) img.alt = img.alt || "[remote image]";
      for (const s of wrap.querySelectorAll("style")) s.textContent = s.textContent.replace(/@import[^;]+;/g, "").replace(/(^|[\s,}])(html|body)(?=[\s,{.#:[])/g, "$1.mb");
      const base = document.createElement("style");
      base.textContent = `:host{display:block}.mb{font-size:var(--fs);line-height:1.5;overflow-wrap:anywhere;background:#fff;color:#111;padding:12px;border-radius:6px}.mb img{max-width:100%;height:auto}.mb table{max-width:100%}.mb a{color:#2a5bd7}`;
      const mb = document.createElement("div"); mb.className = "mb";
      mb.append(...wrap.childNodes);
      sh.append(base, mb);
      bodyBox.append(host);
      if (remote) util.notice(`${remote} remote image${remote === 1 ? "" : "s"} not loaded (no network)`);
    } else {
      bodyBox.append(util.el("pre", { class: "plain", text: plain != null ? plain : html ? htmlToText(html) : "(empty message)" }));
    }
  };
  if (html && plain) {
    const bH = util.el("button", { class: "btn", type: "button", text: "Formatted", "aria-pressed": "true" });
    const bT = util.el("button", { class: "btn", type: "button", text: "Plain text", "aria-pressed": "false" });
    bH.onclick = () => { showHtml = true; bH.setAttribute("aria-pressed", "true"); bT.setAttribute("aria-pressed", "false"); draw(); };
    bT.onclick = () => { showHtml = false; bT.setAttribute("aria-pressed", "true"); bH.setAttribute("aria-pressed", "false"); draw(); };
    tools.append(bH, bT);
    root.append(tools);
  }
  root.append(bodyBox);
  draw();

  if (m.attachments.length) {
    const box = util.el("div", { class: "attachments list" }, util.el("h2", { text: `Attachments (${m.attachments.length})` }));
    for (const a of m.attachments) {
      const ext = (a.name.includes(".") ? a.name.split(".").pop() : "").slice(0, 4).toUpperCase() || "FILE";
      const r = util.el("div", { class: "entry", role: "button", tabindex: "0" },
        util.el("span", { class: "ico", text: ext }), util.el("span", { class: "nm", text: a.name }), util.el("span", { class: "sz", text: util.fmtSize(a.bytes.length) }));
      r.addEventListener("click", () => openInner(a.bytes, a.name));
      box.append(r);
    }
    root.append(box);
  }
}
function htmlToText(h) {
  const d = new DOMParser().parseFromString(h, "text/html");
  return d.body ? d.body.innerText || d.body.textContent : h;
}

// ------------------------------------------------------------------ MHTML
async function renderMhtml({ root, util }, msg) {
  await util.need("purify");
  const parts = [];
  walk(msg, p => { if (!p.type.startsWith("multipart/")) parts.push(p); });
  const rootPart = parts.find(p => p.type === "text/html") || parts[0];
  if (!rootPart || rootPart.type !== "text/html") throw Object.assign(new Error("There's no HTML page in this MHTML archive."), { user: true });
  const byLoc = new Map(), byCid = new Map();
  for (const p of parts) { if (p.location) byLoc.set(p.location, p); if (p.cid) byCid.set(p.cid, p); }
  const dataOf = new Map();
  const resolve = (u, baseUrl) => {
    if (!u) return null;
    let p = null;
    if (/^cid:/i.test(u)) p = byCid.get(u.slice(4));
    else {
      let abs = u;
      try { abs = new URL(u, baseUrl || rootPart.location || undefined).href; } catch {}
      p = byLoc.get(abs) || byLoc.get(u);
    }
    if (!p) return null;
    if (!dataOf.has(p)) dataOf.set(p, util.dataUrl(decodeBody(p.raw, p.h("content-transfer-encoding")), p.type));
    return dataOf.get(p);
  };
  const fixCss = (t, baseUrl) => t.replace(/@import[^;]+;/g, "").replace(/url\(\s*['"]?([^'")]+)['"]?\s*\)/g, (m, u) => /^data:/.test(u) ? m : `url("${resolve(u, baseUrl) || "about:invalid"}")`)
    .replace(/(^|[\s,}>+~])(html|body)(?=[\s,{.#:[>+~])/g, "$1.mh");
  const html = textOf(rootPart);
  const d = new DOMParser().parseFromString(html, "text/html");
  let css = "";
  for (const l of d.querySelectorAll('link[rel~="stylesheet"][href]')) {
    const p = byLoc.get(new URL(l.getAttribute("href"), rootPart.location || "https://x/").href) || byLoc.get(l.getAttribute("href"));
    if (p) css += fixCss(textOf(p), p.location) + "\n";
  }
  for (const s of d.querySelectorAll("style")) css += fixCss(s.textContent, rootPart.location) + "\n";
  let missing = 0;
  for (const img of d.querySelectorAll("img[src], input[src]")) {
    const u = resolve(img.getAttribute("src"));
    if (u) img.setAttribute("src", u); else { img.removeAttribute("src"); missing++; }
  }
  for (const n of d.querySelectorAll("[style]")) n.setAttribute("style", fixCss(n.getAttribute("style"), rootPart.location));
  const clean = util.sanitize(d.body ? d.body.innerHTML : html, { FORBID_TAGS: ["style", "link", "form", "input", "button", "iframe", "object", "embed", "base", "meta", "video", "audio"], ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|tel|data):|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i });
  const host = util.el("div", { class: "shadow-host" });
  const sh = host.attachShadow({ mode: "open" });
  const st = document.createElement("style");
  st.textContent = `:host{display:block}.mh{background:#fff;color:#000;font:16px/1.4 serif;padding:8px;overflow-wrap:break-word}.mh img{max-width:100%;height:auto}\n${css}`;
  const wrap = document.createElement("div"); wrap.className = "mh";
  const bodyCls = d.body && d.body.getAttribute("class"); if (bodyCls) wrap.className += " " + bodyCls;
  wrap.innerHTML = clean;
  sh.append(st, wrap);
  root.append(host);
  util.mode("reflow");
  util.notice("Saved web page (MHTML): the page's scripts don't run" + (missing ? `; ${missing} image${missing === 1 ? " was" : "s were"} not in the archive` : ""));
  const title = d.title || decodeWords(msg.h("subject"));
  if (title) util.A.onFormat("MHTML · " + title.slice(0, 60));
}
