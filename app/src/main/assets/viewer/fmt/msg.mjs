// Outlook .msg (formato MS-OXMSG): un file CFB (lo legge gia' SheetJS) con una proprieta' MAPI per flusso
// (__substg1.0_IIIITTTT: II = id, TT = tipo). Scritto qui, niente librerie in piu'. Corpo, in ordine di
// preferenza: HTML (0x1013) > HTML "incapsulato" nell'RTF compresso (0x1009, LZFu) > RTF vero (reso dal
// modulo RTF) > testo (0x1000). Allegati (anche messaggi allegati, convertiti in .eml), immagini cid:.
// La vista e' la stessa delle email .eml (mail.mjs).
import { showMail } from "./mail.mjs";

export async function render(ctx) {
  const { cfb } = ctx;
  const m = readMessage(cfb, "");
  const body = await bodyOf(m, ctx);
  const cls = m.str(0x001A) || "IPM.Note";
  const kind = /^IPM\.Appointment|^IPM\.Schedule/i.test(cls) ? "Outlook appointment" : /^IPM\.Contact/i.test(cls) ? "Outlook contact"
    : /^IPM\.Task/i.test(cls) ? "Outlook task" : /^IPM\.StickyNote/i.test(cls) ? "Outlook note" : "Outlook email";
  ctx.util.A.onFormat(kind);
  await showMail(ctx, headerOf(m, body));
}

function headerOf(m, body) {
  const fromName = m.str(0x0C1A) || m.str(0x0042);
  const fromMail = m.str(0x5D01) || m.str(0x0C1F) || m.str(0x0065);
  const from = fromName && fromMail && !fromName.includes(fromMail) && /@/.test(fromMail) ? `${fromName} <${fromMail}>` : fromName || fromMail;
  const date = m.time(0x0039) || m.time(0x0E06) || m.time(0x3007);
  const rows = [["From", from], ["To", recipients(m, 1) || m.str(0x0E04)], ["Cc", recipients(m, 2) || m.str(0x0E03)], ["Bcc", recipients(m, 3) || m.str(0x0E02)]];
  if (!m.str(0x0E04) && !recipients(m, 1)) rows[1] = ["To", ""];
  return {
    subject: m.str(0x0037) || m.str(0x0E1D), rows, date, dateRaw: null,
    html: body.html, plain: body.plain, inline: body.inline, attachments: body.attachments,
  };
}

function recipients(m, type) {
  const out = [];
  for (const r of m.recips) {
    if ((r.int(0x0C15) ?? 1) !== type) continue;
    const n = r.str(0x3001), e = r.str(0x39FE) || r.str(0x3003);
    out.push(n && e && n !== e && /@/.test(e) ? `${n} <${e}>` : n || e);
  }
  return out.filter(Boolean).join("; ");
}

// ------------------------------------------------------------------ lettura delle proprieta'
const CP = { 1250: "windows-1250", 1251: "windows-1251", 1252: "windows-1252", 1253: "windows-1253", 1254: "windows-1254", 1255: "windows-1255",
  1256: "windows-1256", 1257: "windows-1257", 1258: "windows-1258", 874: "windows-874", 932: "shift_jis", 936: "gbk", 949: "euc-kr", 950: "big5",
  20127: "us-ascii", 28591: "iso-8859-1", 28592: "iso-8859-2", 28605: "iso-8859-15", 50220: "iso-2022-jp", 51932: "euc-jp", 65001: "utf-8", 54936: "gb18030" };
const decoder = cp => { try { return new TextDecoder(CP[cp] || "windows-1252"); } catch { return new TextDecoder("windows-1252"); } };

/** Un "oggetto" MAPI (messaggio, allegato, destinatario) = i flussi sotto il prefisso di percorso. */
function readMessage(cfb, prefix) {
  const streams = new Map(); // "IIIITTTT" -> Uint8Array
  const subs = new Map(); // nome della sotto-cartella -> prefisso
  let props = null;
  cfb.FullPaths.forEach((p, i) => {
    const rel = p.replace(/^[^/]*\//, "");
    if (!rel.startsWith(prefix)) return;
    const rest = rel.slice(prefix.length);
    if (!rest) return;
    const slash = rest.indexOf("/");
    if (slash >= 0 && slash < rest.length - 1) return; // dentro una sotto-cartella: la legge l'oggetto figlio
    const e = cfb.FileIndex[i];
    if (slash === rest.length - 1) { subs.set(rest.slice(0, -1), prefix + rest); return; }
    const content = e.content ? (e.content instanceof Uint8Array ? e.content : new Uint8Array(e.content)) : new Uint8Array(0);
    const mm = /^__substg1\.0_([0-9A-F]{8})$/i.exec(rest);
    if (mm) streams.set(mm[1].toUpperCase(), content);
    else if (rest === "__properties_version1.0") props = content;
  });
  const top = prefix === "";
  const embedded = /__substg1\.0_3701000D\/$/i.test(prefix);
  // proprieta' a lunghezza fissa: intestazione di 32 byte (messaggio principale), 24 (messaggio allegato), 8 (allegati/destinatari)
  const fixed = new Map();
  if (props) {
    const hdr = top ? 32 : embedded ? 24 : 8;
    const dv = new DataView(props.buffer, props.byteOffset, props.byteLength);
    for (let o = hdr; o + 16 <= props.length; o += 16) {
      const tag = dv.getUint32(o, true);
      fixed.set(tag >>> 16, { type: tag & 0xFFFF, lo: dv.getUint32(o + 8, true), hi: dv.getUint32(o + 12, true), dv, o: o + 8 });
    }
  }
  const cpid = () => { const x = fixed.get(0x3FFD) || fixed.get(0x3FDE); return x ? x.lo : 1252; };
  const obj = {
    raw(id, type) { return streams.get(hex4(id) + type); },
    str(id) {
      const u = streams.get(hex4(id) + "001F");
      if (u) return new TextDecoder("utf-16le").decode(u).replace(/\0+$/, "");
      const a = streams.get(hex4(id) + "001E");
      if (a) return decoder(cpid()).decode(a).replace(/\0+$/, "");
      return "";
    },
    bin(id) { return streams.get(hex4(id) + "0102"); },
    int(id) { const x = fixed.get(id); return x ? (x.type === 0x000B ? (x.lo & 0xFF) : x.lo) : undefined; },
    time(id) {
      const x = fixed.get(id);
      if (!x || x.type !== 0x0040) return null;
      const ft = x.hi * 4294967296 + x.lo; // FILETIME: centinaia di ns dal 1601
      if (!ft) return null;
      return new Date(ft / 10000 - 11644473600000);
    },
    cpid, recips: [], atts: [],
  };
  for (const [name, pre] of subs) {
    if (name.startsWith("__recip_version1.0_")) obj.recips.push(readMessage(cfb, pre));
    else if (name.startsWith("__attach_version1.0_")) obj.atts.push(Object.assign(readMessage(cfb, pre), { _pre: pre, _subs: subs }));
  }
  obj.embedded = sub => [...subs.keys()].includes(sub) ? readMessage(cfb, subs.get(sub)) : null;
  return obj;
}
const hex4 = n => n.toString(16).toUpperCase().padStart(4, "0");

// ------------------------------------------------------------------ corpo e allegati
async function bodyOf(m, ctx) {
  const inline = new Map(), attachments = [];
  for (const a of m.atts) {
    const name = a.str(0x3707) || a.str(0x3704) || a.str(0x3001) || "attachment";
    const cid = a.str(0x3712);
    const mime = a.str(0x370E) || ctx.util.mimeOf(name);
    const hidden = a.int(0x7FFE) === 1;
    const nested = a.embedded("__substg1.0_3701000D");
    if (nested) { // messaggio allegato: lo converto in .eml, cosi' si apre con la vista email
      const nb = await bodyOf(nested, ctx);
      const h = headerOf(nested, nb);
      attachments.push({ name: (h.subject || "message").replace(/[\\/:*?"<>|]+/g, "_").slice(0, 80) + ".eml", bytes: toEml(h, nb) });
      continue;
    }
    const data = a.bin(0x3701);
    if (!data) continue;
    if (cid) inline.set(cid.replace(/^<|>$/g, ""), { type: mime, bytes: () => data });
    inline.set("name:" + name.toLowerCase(), { type: mime, bytes: () => data }); // Outlook 2003: <img src="1.jpg">
    if (!(hidden && cid)) attachments.push({ name, bytes: data, cid: cid.replace(/^<|>$/g, "") });
  }
  let html = null, plain = m.str(0x1000) || null;
  const hb = m.raw(0x1013, "0102") || null;
  if (hb) html = decoder(m.cpid() === 1252 && /charset=["']?utf-8/i.test(new TextDecoder("latin1").decode(hb.subarray(0, 2048))) ? 65001 : m.cpid()).decode(hb);
  else if (m.str(0x1013)) html = m.str(0x1013);
  if (!html) {
    const rc = m.bin(0x1009);
    if (rc) {
      try {
        const rtf = lzfu(rc);
        const txt = new TextDecoder("latin1").decode(rtf);
        if (/\\fromhtml1/.test(txt)) html = deencapsulate(txt);
        else if (!/\\fromtext/.test(txt) && !plain) {
          // RTF vero (niente testo semplice): lo disegna il modulo RTF dentro l'HTML del corpo
          const { render } = await import("./rtf.mjs");
          const box = document.createElement("div");
          render({ root: box, bytes: rtf, util: ctx.util });
          html = box.innerHTML;
        }
      } catch (e) { console.warn("unreadable compressed RTF", e); }
    }
  }
  // immagini citate dal corpo (cid:) non si ripetono tra gli allegati
  const lower = html ? html.toLowerCase() : "";
  const shown = html ? attachments.filter(a => !(a.cid && html.includes("cid:" + a.cid)) && !new RegExp(`src=["']?${a.name.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}["' >]`).test(lower)) : attachments;
  return { html, plain, inline, attachments: shown };
}

/** Messaggio allegato -> testo RFC 822 minimo (intestazioni + corpo), per aprirlo con la vista .eml */
function toEml(h, b) {
  const enc = s => /[^\x20-\x7e]/.test(s || "") ? `=?UTF-8?B?${btoa(unescape(encodeURIComponent(s)))}?=` : s || "";
  const lines = [`Subject: ${enc(h.subject)}`];
  for (const [k, v] of h.rows) if (v) lines.push(`${{ From: "From", To: "To", Cc: "Cc", Bcc: "Bcc" }[k]}: ${enc(v)}`);
  if (h.date) lines.push(`Date: ${h.date.toUTCString()}`);
  lines.push("MIME-Version: 1.0");
  const body = b.html || b.plain || "";
  lines.push(`Content-Type: ${b.html ? "text/html" : "text/plain"}; charset=utf-8`, "Content-Transfer-Encoding: base64", "");
  const u8 = new TextEncoder().encode(body);
  let bin = ""; for (let i = 0; i < u8.length; i += 0x8000) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return new TextEncoder().encode(lines.join("\r\n") + "\r\n" + btoa(bin).replace(/.{76}/g, "$&\r\n") + "\r\n");
}

// ------------------------------------------------------------------ RTF compresso (MS-OXRTFCP, "LZFu")
const PRE = "{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss \\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx";
function lzfu(src) {
  const dv = new DataView(src.buffer, src.byteOffset, src.byteLength);
  const compSize = dv.getUint32(0, true), rawSize = dv.getUint32(4, true), magic = dv.getUint32(8, true);
  if (magic === 0x414C454D) return src.subarray(16, 16 + rawSize); // "MELA": non compresso
  if (magic !== 0x75465A4C) throw new Error("unknown compressed RTF");
  const dict = new Uint8Array(4096);
  for (let i = 0; i < PRE.length; i++) dict[i] = PRE.charCodeAt(i);
  let wp = PRE.length;
  const out = new Uint8Array(rawSize + 16);
  let n = 0, p = 16;
  const end = Math.min(src.length, compSize + 4);
  while (p < end && n < rawSize) {
    const ctrl = src[p++];
    for (let bit = 0; bit < 8 && p < end; bit++) {
      if (ctrl & (1 << bit)) {
        const ref = (src[p] << 8) | src[p + 1]; p += 2;
        const off = ref >> 4, len = (ref & 15) + 2;
        if (off === wp) return out.subarray(0, n);
        for (let k = 0; k < len; k++) {
          const c = dict[(off + k) & 4095];
          if (n < out.length) out[n++] = c;
          dict[wp] = c; wp = (wp + 1) & 4095;
        }
      } else {
        const c = src[p++];
        if (n < out.length) out[n++] = c;
        dict[wp] = c; wp = (wp + 1) & 4095;
      }
    }
  }
  return out.subarray(0, n);
}

// ------------------------------------------------------------------ HTML incapsulato nell'RTF (MS-OXRTFEX)
function deencapsulate(rtf) {
  let out = "", i = 0;
  const stack = [];
  let st = { skip: false, htmltag: false, suppress: false, uc: 1 };
  let cp = "windows-1252";
  const m = /\\ansicpg(\d+)/.exec(rtf); if (m && CP[+m[1]]) cp = CP[+m[1]];
  const dec = new TextDecoder(cp);
  let pendingBytes = [];
  const flush = () => { if (pendingBytes.length) { out += dec.decode(Uint8Array.from(pendingBytes)); pendingBytes = []; } };
  const emit = s => { if (!st.skip && (st.htmltag || !st.suppress)) { flush(); out += s; } };
  const emitByte = b => { if (!st.skip && (st.htmltag || !st.suppress)) pendingBytes.push(b); };
  while (i < rtf.length) {
    const c = rtf[i];
    if (c === "{") {
      flush(); stack.push(st); st = { ...st, htmltag: st.htmltag }; i++;
      if (rtf.startsWith("\\*", i)) {
        const w = /^\\\*\\([a-zA-Z]+)(-?\d+)? ?/.exec(rtf.slice(i, i + 40));
        if (w && w[1] === "htmltag") { st.htmltag = true; st.skip = false; i += w[0].length; }
        else { st.skip = true; }
      }
      continue;
    }
    if (c === "}") { flush(); st = stack.pop() || st; i++; continue; }
    if (c === "\\") {
      const n = rtf[i + 1];
      if (n === "\\" || n === "{" || n === "}") { emit(n); i += 2; continue; }
      if (n === "'") { emitByte(parseInt(rtf.substr(i + 2, 2), 16)); i += 4; continue; }
      if (n === "~") { emit(" "); i += 2; continue; }
      if (n === "\r" || n === "\n") { i += 2; continue; }
      const w = /^\\([a-zA-Z]+)(-?\d+)? ?/.exec(rtf.slice(i, i + 40));
      if (!w) { i += 2; continue; }
      i += w[0].length;
      const word = w[1], arg = w[2] != null ? +w[2] : null;
      switch (word) {
        case "htmlrtf": st.suppress = arg !== 0; break;
        case "par": case "line": emit("\r\n"); break;
        case "tab": emit("\t"); break;
        case "u": { emit(String.fromCharCode(arg < 0 ? arg + 65536 : arg)); let k = st.uc; while (k-- > 0 && i < rtf.length) { if (rtf[i] === "\\" && rtf[i + 1] === "'") i += 4; else i++; } break; }
        case "uc": st.uc = arg ?? 1; break;
        case "fonttbl": case "colortbl": case "stylesheet": st.skip = true; break;
      }
      continue;
    }
    if (c === "\r" || c === "\n") { i++; continue; }
    emitByte(rtf.charCodeAt(i) & 0xFF); i++;
  }
  flush();
  return out;
}
