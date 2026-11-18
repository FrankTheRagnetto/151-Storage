// Formati piccoli: vCard (.vcf), iCalendar (.ics), sottotitoli (.srt/.vtt), font (anteprima),
// e i ripieghi per i file dentro uno ZIP o allegati (testo, immagine, HTML).
export async function render(ctx) {
  const f = { vcf, ics, srt: subs, vtt: subs, font, text, image, htmlsrc }[ctx.fmt];
  return f(ctx);
}

/** Righe "piegate" (RFC 5545/6350) -> [{name, params, value}] */
function lines(t) {
  const out = [];
  for (const l of t.replace(/\r?\n[ \t]/g, "").split(/\r?\n/)) {
    if (!l.trim()) continue;
    const i = l.search(/:(?=(?:[^"]*"[^"]*")*[^"]*$)/);
    if (i < 0) continue;
    const [name, ...ps] = l.slice(0, i).split(";");
    const params = {};
    for (const p of ps) { const [k, v = ""] = p.split("="); params[k.toUpperCase()] = v.replace(/^"|"$/g, ""); }
    out.push({ name: name.toUpperCase().replace(/^item\d+\./i, ""), params, value: l.slice(i + 1) });
  }
  return out;
}
const unesc = v => v.replace(/\\n/gi, "\n").replace(/\\([,;\\])/g, "$1");
function qp(v, params) {
  if ((params.ENCODING || "").toUpperCase() !== "QUOTED-PRINTABLE") return v;
  const b = Uint8Array.from(v.replace(/=\r?\n/g, "").replace(/=([0-9A-F]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16))), c => c.charCodeAt(0));
  try { return new TextDecoder(params.CHARSET || "utf-8").decode(b); } catch { return v; }
}

// ------------------------------------------------------------------ vCard
function vcf({ root, bytes, util }) {
  const { el } = util;
  const all = lines(util.decodeText(bytes));
  const cards = [];
  let cur = null;
  for (const l of all) {
    if (l.name === "BEGIN" && /VCARD/i.test(l.value)) { cur = []; continue; }
    if (l.name === "END" && /VCARD/i.test(l.value)) { if (cur) cards.push(cur); cur = null; continue; }
    if (cur) cur.push(l);
  }
  if (!cards.length) throw Object.assign(new Error("No contacts in the file."), { user: true });
  const box = el("div", { class: "cards" });
  const TYPES = { CELL: "mobile", HOME: "home", WORK: "work", FAX: "fax", VOICE: "", PREF: "", INTERNET: "", OTHER: "other", MAIN: "main" };
  const typeOf = p => { const t = (p.TYPE || Object.keys(p).filter(k => TYPES[k] !== undefined).join(",")).split(",").map(x => TYPES[x.toUpperCase()] ?? x.toLowerCase()).filter(Boolean); return t.length ? ` (${t.join(", ")})` : ""; };
  for (const c of cards) {
    const get = n => c.find(l => l.name === n);
    const v = l => l ? unesc(qp(l.value, l.params)) : "";
    let name = v(get("FN"));
    const N = get("N");
    if (!name && N) name = v(N).split(";").slice(0, 2).reverse().join(" ").trim();
    const org = v(get("ORG")).replace(/;/g, " · "), title = v(get("TITLE"));
    const photo = get("PHOTO");
    let av;
    if (photo && (/^data:image\//.test(photo.value) || /B|BASE64/i.test(photo.params.ENCODING || ""))) {
      const src = photo.value.startsWith("data:") ? photo.value : `data:image/${(photo.params.TYPE || "jpeg").toLowerCase()};base64,${photo.value}`;
      av = el("img", { class: "avatar", src, alt: "" });
    } else av = el("div", { class: "avatar", text: (name || "?").trim().charAt(0).toUpperCase() });
    const card = el("div", { class: "card" }, el("div", { class: "card-head" }, av, el("div", null,
      el("div", { class: "card-name", text: name || "(no name)" }), (org || title) && el("div", { class: "card-sub", text: [title, org].filter(Boolean).join(" · ") }))));
    const kv = (k, val, href) => card.append(el("div", { class: "kv" }, el("b", { text: k }), href ? el("span", null, el("a", { href, text: val })) : el("span", { text: val })));
    for (const l of c) {
      const val = v(l);
      switch (l.name) {
        case "TEL": kv("Phone" + typeOf(l.params), val, "tel:" + val.replace(/[^\d+]/g, "")); break;
        case "EMAIL": kv("Email" + typeOf(l.params), val, "mailto:" + val); break;
        case "ADR": kv("Address" + typeOf(l.params), val.split(";").filter(Boolean).join(", ")); break;
        case "URL": kv("Website", val); break;
        case "BDAY": kv("Birthday", fmtDate(val) || val); break;
        case "NOTE": kv("Note", val); break;
        case "NICKNAME": kv("Nickname", val); break;
      }
    }
    box.append(card);
  }
  root.append(box);
  if (cards.length > 1) util.A.onFormat(`vCard · ${cards.length} contacts`);
}
function fmtDate(v) {
  const m = /^(\d{4})-?(\d\d)-?(\d\d)/.exec(v);
  if (!m) return null;
  return new Date(+m[1], +m[2] - 1, +m[3]).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" });
}

// ------------------------------------------------------------------ iCalendar
function icsDate(l) {
  if (!l) return null;
  const m = /^(\d{4})(\d\d)(\d\d)(?:T(\d\d)(\d\d)(\d\d)?(Z)?)?/.exec(l.value);
  if (!m) return null;
  const allDay = !m[4] || l.params.VALUE === "DATE";
  const d = m[7] ? new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4] || 0, +m[5] || 0, +m[6] || 0)) : new Date(+m[1], +m[2] - 1, +m[3], +m[4] || 0, +m[5] || 0, +m[6] || 0);
  return { d, allDay, tz: l.params.TZID };
}
function ics({ root, bytes, util }) {
  const { el } = util;
  const all = lines(util.decodeText(bytes));
  const items = [];
  let cur = null, depth = 0;
  for (const l of all) {
    if (l.name === "BEGIN") { if (/^(VEVENT|VTODO|VJOURNAL)$/i.test(l.value) && !cur) { cur = { kind: l.value.toUpperCase(), props: [] }; depth = 0; } else if (cur) depth++; continue; }
    if (l.name === "END") { if (cur && depth === 0 && /^(VEVENT|VTODO|VJOURNAL)$/i.test(l.value)) { items.push(cur); cur = null; } else if (cur) depth--; continue; }
    if (cur && depth === 0) cur.props.push(l);
  }
  if (!items.length) throw Object.assign(new Error("No events in the calendar."), { user: true });
  const get = (it, n) => it.props.find(p => p.name === n);
  items.sort((a, b) => (icsDate(get(a, "DTSTART"))?.d || 0) - (icsDate(get(b, "DTSTART"))?.d || 0));
  const box = el("div", { class: "cards" });
  const fmt = (x, withDate = true) => x.allDay ? x.d.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" })
    : x.d.toLocaleString("en-GB", withDate ? { weekday: "long", day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" } : { hour: "2-digit", minute: "2-digit" });
  for (const it of items) {
    const s = icsDate(get(it, "DTSTART")), e = icsDate(get(it, "DTEND")) || icsDate(get(it, "DUE"));
    let when = s ? fmt(s) : "";
    if (s && e) {
      const same = s.d.toDateString() === e.d.toDateString();
      if (!s.allDay) when += " – " + fmt(e, !same);
      else if ((e.d - s.d) > 86400000) when += " – " + fmt({ d: new Date(e.d - 86400000), allDay: true });
    }
    if (s && s.allDay) when += " (all day)";
    if (s && s.tz) when += ` · ${s.tz}`;
    const v = n => { const p = get(it, n); return p ? unesc(p.value) : ""; };
    const card = el("div", { class: "card" },
      el("div", { class: "ev-date", text: (it.kind === "VTODO" ? "Task · " : "") + (when || "no date") }),
      el("div", { class: "card-name", text: v("SUMMARY") || "(untitled)" }));
    const kv = (k, val) => val && card.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: val })));
    kv("Location", v("LOCATION"));
    kv("Repeats", rrule(v("RRULE")));
    kv("Organizer", v("ORGANIZER").replace(/^mailto:/i, ""));
    const att = it.props.filter(p => p.name === "ATTENDEE").map(p => p.params.CN || p.value.replace(/^mailto:/i, ""));
    kv("Attendees", att.join(", "));
    kv("Description", v("DESCRIPTION"));
    kv("Status", v("STATUS"));
    box.append(card);
  }
  root.append(box);
  util.A.onFormat(`Calendar · ${items.length} ${items.length === 1 ? "event" : "events"}`);
}
function rrule(r) {
  if (!r) return "";
  const p = Object.fromEntries(r.split(";").map(x => x.split("=")));
  const f = { DAILY: "every day", WEEKLY: "every week", MONTHLY: "every month", YEARLY: "every year" }[p.FREQ] || r;
  let s = p.INTERVAL && p.INTERVAL !== "1" ? `${f} (every ${p.INTERVAL})` : f;
  if (p.COUNT) s += `, ${p.COUNT} times`;
  if (p.UNTIL) s += `, until ${fmtDate(p.UNTIL) || p.UNTIL}`;
  return s;
}

// ------------------------------------------------------------------ sottotitoli
function subs({ root, bytes, util }) {
  const t = util.decodeText(bytes).replace(/\r/g, "").replace(/^﻿/, "");
  const blocks = t.split(/\n{2,}/);
  const box = util.el("div");
  let n = 0;
  for (const b of blocks) {
    const ls = b.split("\n");
    const ti = ls.findIndex(l => /-->/.test(l));
    if (ti < 0) continue;
    const idx = ti > 0 ? ls[ti - 1] : String(n + 1);
    const [a, z] = ls[ti].split("-->").map(x => x.trim().split(/\s/)[0].replace(",", "."));
    const cue = util.el("div", { class: "cue" }, util.el("div", { class: "cue-t" }, util.el("div", { class: "cue-n", text: "#" + idx }), util.el("div", { text: a }), util.el("div", { text: z })));
    const x = util.el("div", { class: "cue-x" });
    // solo <i> <b> <u> dei sottotitoli; il resto e' testo
    const txt = ls.slice(ti + 1).join("\n");
    for (const part of txt.split(/(<\/?[ibu]>)/i)) {
      if (/^<[ibu]>$/i.test(part)) { const e = document.createElement(part[1].toLowerCase()); x.append(e); x._cur = e; }
      else if (/^<\/[ibu]>$/i.test(part)) x._cur = null;
      else (x._cur || x).append(part.replace(/<[^>]+>/g, "").replace(/\{\\[^}]*\}/g, ""));
    }
    x.style.whiteSpace = "pre-wrap";
    cue.append(x);
    box.append(cue);
    n++;
  }
  if (!n) throw Object.assign(new Error("No subtitles recognized."), { user: true });
  root.append(box);
  util.A.onFormat(`Subtitles · ${n} cue${n === 1 ? "" : "s"}`);
}

// ------------------------------------------------------------------ font
function fontName(b) {
  try {
    const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
    const tag = String.fromCharCode(b[0], b[1], b[2], b[3]);
    let off = 0;
    if (tag === "ttcf") off = dv.getUint32(12);
    else if (tag === "wOFF" || tag === "wOF2") return null;
    const num = dv.getUint16(off + 4);
    for (let i = 0; i < num; i++) {
      const r = off + 12 + i * 16;
      if (String.fromCharCode(b[r], b[r + 1], b[r + 2], b[r + 3]) !== "name") continue;
      const t = dv.getUint32(r + 8);
      const count = dv.getUint16(t + 2), so = t + dv.getUint16(t + 4);
      const out = {};
      for (let k = 0; k < count; k++) {
        const e = t + 6 + k * 12;
        const pid = dv.getUint16(e), nid = dv.getUint16(e + 6), len = dv.getUint16(e + 8), o = dv.getUint16(e + 10);
        if (![1, 2, 4, 5, 0].includes(nid) || out[nid]) continue;
        const raw = b.subarray(so + o, so + o + len);
        out[nid] = pid === 3 || pid === 0 ? new TextDecoder("utf-16be").decode(raw) : new TextDecoder("latin1").decode(raw);
      }
      return out;
    }
  } catch {}
  return null;
}
async function font({ root, bytes, util }) {
  const { el } = util;
  const ff = new FontFace("anteprima151", bytes);
  await ff.load();
  document.fonts.add(ff);
  const meta = fontName(bytes) || {};
  const fam = [meta[1], meta[2]].filter(Boolean).join(" ") || util.NAME;
  root.append(el("div", { class: "font-meta" },
    el("div", { class: "card-name", text: fam }),
    meta[5] && el("div", { class: "card-sub", text: meta[5] }),
    meta[0] && el("div", { class: "small muted", text: meta[0] })));
  const s = el("div", { class: "font-sample", style: { fontFamily: "anteprima151" } });
  const pan = "The quick brown fox jumps over the lazy dog.";
  s.append(el("div", { class: "lbl", text: "Alphabet" }), el("div", { style: { fontSize: "28px" }, text: "ABCDEFGHIJKLMNOPQRSTUVWXYZ" }),
    el("div", { style: { fontSize: "28px" }, text: "abcdefghijklmnopqrstuvwxyz" }), el("div", { style: { fontSize: "28px" }, text: "0123456789 àèéìòù €$%&@?!" }));
  for (const px of [12, 16, 20, 28, 40, 56, 72]) s.append(el("div", { class: "lbl", text: px + " px" }), el("div", { style: { fontSize: px + "px" }, text: px >= 40 ? "Hello world" : pan }));
  root.append(s);
  util.A.onFormat("Font · " + fam.slice(0, 50));
}

// ------------------------------------------------------------------ ripieghi (file dentro ZIP / allegati)
async function text({ root, bytes, util, name }) {
  const t = util.decodeText(bytes.subarray(0, 4 * 1024 * 1024));
  const pre = util.el("pre", { class: "plain-text", text: t });
  root.append(pre);
  // v5: colori di sintassi anche per il codice dentro un archivio o allegato (estensione = linguaggio)
  const ext = (name || "").toLowerCase().split(".").pop();
  if (t.length < 512 * 1024 && ext && ext !== "txt") {
    try {
      await util.need("hljs");
      if (hljs.getLanguage(ext)) { pre.classList.add("hljs"); pre.innerHTML = hljs.highlight(t, { language: ext, ignoreIllegals: true }).value; }
    } catch {}
  }
}
function image({ root, bytes, util, name }) {
  const img = util.el("img", { class: "zip-img", alt: name });
  img.src = URL.createObjectURL(new Blob([bytes], { type: util.mimeOf(name) }));
  root.append(img);
}
async function htmlsrc({ root, bytes, util }) {
  await util.need("purify");
  const host = util.el("div", { class: "shadow-host" });
  const sh = host.attachShadow({ mode: "open" });
  const d = document.createElement("div");
  d.style.cssText = "background:#fff;color:#000;padding:8px;overflow-wrap:break-word";
  d.innerHTML = util.sanitize(util.decodeText(bytes), { FORBID_TAGS: ["link", "form", "input", "button", "iframe", "object", "embed", "base", "meta", "video", "audio"], ADD_TAGS: ["style"], FORCE_BODY: true });
  d.querySelectorAll("style").forEach(s => { s.textContent = s.textContent.replace(/@import[^;]+;/g, "").replace(/url\(\s*['"]?(?!data:)[^'")]*['"]?\s*\)/g, "none"); });
  sh.append(d);
  root.append(host);
  util.notice("HTML page inside an archive: scripts off, external resources not loaded");
}
