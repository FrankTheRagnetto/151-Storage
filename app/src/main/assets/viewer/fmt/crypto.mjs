// Certificati (.pem, .crt, .cer, .der) e firme digitali PKCS#7 / CAdES (.p7m, .p7s, .p7b): lettore ASN.1
// scritto qui. Certificato: a chi e' intestato, chi l'ha emesso, validita' (con "scaduto" / "non ancora
// valido"), chiave, usi, nomi alternativi, impronte SHA-256/SHA-1. Firma .p7m (quella dei documenti
// firmati in Italia, fatture comprese): chi ha firmato e quando, controllo dell'INTEGRITA' (il contenuto
// e' quello firmato: impronta + firma verificate con WebCrypto), e sotto il documento estratto, aperto con
// il visualizzatore giusto. La catena di fiducia (CA qualificate) NON si verifica: servirebbe internet.
import { renderInto } from "../doc.mjs";

export async function render(ctx) {
  const { bytes, util, fmt } = ctx;
  const der = toDerBlocks(bytes, util);
  if (fmt === "p7m" || der.some(b => b.label && /PKCS7|CMS/.test(b.label)) || der.length && isP7(der[0].bytes)) return p7m(ctx, der[0].bytes);
  return certs(ctx, der);
}

// ------------------------------------------------------------------ PEM / DER / base64
function toDerBlocks(bytes, util) {
  const t = new TextDecoder("latin1").decode(bytes.subarray(0, Math.min(bytes.length, 64)));
  if (/-----BEGIN /.test(util.decodeText(bytes.subarray(0, 4096)))) {
    const s = util.decodeText(bytes);
    const out = [];
    for (const m of s.matchAll(/-----BEGIN ([A-Z0-9 ]+)-----([\s\S]*?)-----END \1-----/g)) {
      const body = m[2].replace(/^[A-Za-z-]+:.*$/gm, "").replace(/[^A-Za-z0-9+/=]/g, "");
      try { out.push({ label: m[1], bytes: Uint8Array.from(atob(body), c => c.charCodeAt(0)) }); } catch { out.push({ label: m[1], bytes: new Uint8Array(0) }); }
    }
    return out;
  }
  // .p7m in base64 senza intestazioni (capita con alcune PEC)
  if (/^[A-Za-z0-9+/=\s]+$/.test(t) && !/^\s*$/.test(t)) {
    try { return [{ label: null, bytes: Uint8Array.from(atob(new TextDecoder("latin1").decode(bytes).replace(/\s+/g, "")), c => c.charCodeAt(0)) }]; } catch {}
  }
  return [{ label: null, bytes }];
}

// ------------------------------------------------------------------ ASN.1 (BER/DER)
function parse(b, p = 0, end = b.length, depth = 0) {
  const start = p;
  let tag = b[p++];
  const cls = tag >> 6, cons = !!(tag & 0x20);
  let num = tag & 0x1F;
  if (num === 0x1F) { num = 0; let x; do { x = b[p++]; num = num * 128 + (x & 0x7F); } while (x & 0x80); }
  let len = b[p++];
  if (len & 0x80) { const n = len & 0x7F; if (n === 0) len = -1; else { len = 0; for (let i = 0; i < n; i++) len = len * 256 + b[p++]; } }
  const node = { cls, cons, num, start, hs: p, children: null };
  if (len === -1) { // lunghezza indefinita (BER): figli fino a 00 00
    node.children = [];
    while (p < end && !(b[p] === 0 && b[p + 1] === 0)) { const c = parse(b, p, end, depth + 1); node.children.push(c); p = c.end; }
    node.he = p; node.end = p + 2;
  } else {
    node.he = p + len; node.end = p + len;
    if (node.end > end + 0) throw new Error("truncated ASN.1");
    if (cons && depth < 40) { node.children = []; let q = p; while (q < node.he) { const c = parse(b, q, node.he, depth + 1); node.children.push(c); q = c.end; } }
  }
  node.content = () => b.subarray(node.hs, node.he);
  node.raw = () => b.subarray(node.start, node.end);
  return node;
}
function oid(n) {
  const c = n.content(); const out = [Math.floor(c[0] / 40), c[0] % 40]; let v = 0;
  for (let i = 1; i < c.length; i++) { v = v * 128 + (c[i] & 0x7F); if (!(c[i] & 0x80)) { out.push(v); v = 0; } }
  return out.join(".");
}
function octets(n) { // OCTET STRING anche "a pezzi" (BER costruito)
  if (!n.cons) return n.content();
  const parts = n.children.map(octets), out = new Uint8Array(parts.reduce((s, x) => s + x.length, 0));
  let o = 0; for (const x of parts) { out.set(x, o); o += x.length; } return out;
}
function str(n) {
  const c = n.content();
  if (n.num === 30) { let s = ""; for (let i = 0; i + 1 < c.length; i += 2) s += String.fromCharCode((c[i] << 8) | c[i + 1]); return s; } // BMPString
  if (n.num === 12 || n.num === 19 || n.num === 22 || n.num === 26 || n.num === 20) try { return new TextDecoder("utf-8", { fatal: true }).decode(c); } catch { return new TextDecoder("latin1").decode(c); }
  return new TextDecoder("latin1").decode(c);
}
function time(n) {
  const s = str(n);
  const m = n.num === 23 ? /^(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?Z/.exec(s) : /^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?/.exec(s);
  if (!m) return null;
  let y = +m[1]; if (n.num === 23) y += y < 50 ? 2000 : 1900;
  return new Date(Date.UTC(y, +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)));
}
const hex = (u8, sep = ":") => [...u8].map(x => x.toString(16).padStart(2, "0").toUpperCase()).join(sep);
const NAMES = { "2.5.4.3": "CN", "2.5.4.6": "C", "2.5.4.7": "L", "2.5.4.8": "ST", "2.5.4.10": "O", "2.5.4.11": "OU", "2.5.4.5": "serialNumber",
  "2.5.4.4": "SN", "2.5.4.42": "GN", "2.5.4.46": "dnQualifier", "2.5.4.97": "organizationIdentifier", "2.5.4.12": "title", "2.5.4.9": "street",
  "2.5.4.17": "postalCode", "1.2.840.113549.1.9.1": "E", "0.9.2342.19200300.100.1.25": "DC" };
const ALGS = { "1.2.840.113549.1.1.1": "RSA", "1.2.840.113549.1.1.5": "SHA-1 with RSA", "1.2.840.113549.1.1.11": "SHA-256 with RSA", "1.2.840.113549.1.1.12": "SHA-384 with RSA",
  "1.2.840.113549.1.1.13": "SHA-512 with RSA", "1.2.840.113549.1.1.10": "RSA-PSS", "1.2.840.10045.2.1": "Elliptic curve", "1.2.840.10045.4.3.2": "ECDSA with SHA-256",
  "1.2.840.10045.4.3.3": "ECDSA with SHA-384", "1.2.840.10045.4.3.4": "ECDSA with SHA-512", "1.3.101.112": "Ed25519", "1.2.840.10040.4.1": "DSA",
  "2.16.840.1.101.3.4.2.1": "SHA-256", "2.16.840.1.101.3.4.2.2": "SHA-384", "2.16.840.1.101.3.4.2.3": "SHA-512", "1.3.14.3.2.26": "SHA-1" };
const CURVES = { "1.2.840.10045.3.1.7": "P-256", "1.3.132.0.34": "P-384", "1.3.132.0.35": "P-521" };
const EKU = { "1.3.6.1.5.5.7.3.1": "web server (TLS)", "1.3.6.1.5.5.7.3.2": "client authentication", "1.3.6.1.5.5.7.3.3": "code signing",
  "1.3.6.1.5.5.7.3.4": "email (S/MIME)", "1.3.6.1.5.5.7.3.8": "timestamping", "1.3.6.1.5.5.7.3.9": "OCSP", "1.3.6.1.4.1.311.10.3.12": "document signing" };
function dn(n) {
  const out = [];
  for (const rdn of n.children || []) for (const atv of rdn.children || []) {
    const [o, v] = atv.children;
    out.push([NAMES[oid(o)] || oid(o), str(v)]);
  }
  return out;
}
const dnGet = (d, k) => d.filter(x => x[0] === k).map(x => x[1]).join(", ");
const dnText = d => d.map(([k, v]) => `${k}=${v}`).join(", ");
const DIGEST = { "2.16.840.1.101.3.4.2.1": "SHA-256", "2.16.840.1.101.3.4.2.2": "SHA-384", "2.16.840.1.101.3.4.2.3": "SHA-512", "1.3.14.3.2.26": "SHA-1" };

function x509(der) {
  const c = parse(der);
  const tbs = c.children[0];
  let k = 0;
  const ver = tbs.children[0].cls === 2 && tbs.children[0].num === 0 ? (k++, tbs.children[0].children[0].content()[0] + 1) : 1;
  const serial = tbs.children[k++].content();
  const sigAlg = oid(tbs.children[k++].children[0]);
  const issuer = dn(tbs.children[k++]);
  const val = tbs.children[k++];
  const subject = dn(tbs.children[k++]);
  const spki = tbs.children[k++];
  const keyAlg = oid(spki.children[0].children[0]);
  let keyDesc = ALGS[keyAlg] || keyAlg;
  if (keyAlg === "1.2.840.113549.1.1.1") {
    try { const bs = spki.children[1].content().subarray(1); const rsa = parse(bs); let m = rsa.children[0].content(); while (m[0] === 0) m = m.subarray(1); keyDesc = `RSA ${m.length * 8} bit`; } catch {}
  } else if (keyAlg === "1.2.840.10045.2.1") keyDesc = `Elliptic curve ${CURVES[oid(spki.children[0].children[1])] || oid(spki.children[0].children[1])}`;
  const ext = {};
  const extNode = tbs.children.slice(k).find(n => n.cls === 2 && n.num === 3);
  if (extNode) for (const e of extNode.children[0].children) {
    const id = oid(e.children[0]);
    const v = parse(e.children[e.children.length - 1].content());
    try {
      if (id === "2.5.29.17") ext.san = v.children.map(g => g.num === 2 ? "DNS: " + str(g) : g.num === 1 ? "Email: " + str(g) : g.num === 6 ? "URI: " + str(g) : g.num === 7 ? "IP: " + [...g.content()].join(".") : null).filter(Boolean);
      if (id === "2.5.29.15") { const bits = v.content(); const f = bits[1] | (bits[2] << 8); ext.ku = ["digital signature", "non-repudiation", "key encipherment", "data encipherment", "key agreement", "certificate signing", "CRL signing"].filter((_, i) => f & (0x80 >> i) || (i === 8 && bits[2] & 0x80)); }
      if (id === "2.5.29.37") ext.eku = v.children.map(o => EKU[oid(o)] || oid(o));
      if (id === "2.5.29.19") ext.ca = v.children && v.children[0] && v.children[0].num === 1 && v.children[0].content()[0] !== 0;
      if (id === "1.3.6.1.5.5.7.1.3") ext.qc = true;
    } catch {}
  }
  return { der, ver, serial, sigAlg: ALGS[sigAlg] || sigAlg, issuer, subject, from: time(val.children[0]), to: time(val.children[1]), keyDesc, keyAlg, spki: spki.raw(), ext };
}
async function fingerprints(der) {
  if (!crypto.subtle) return {};
  return { sha256: hex(new Uint8Array(await crypto.subtle.digest("SHA-256", der))), sha1: hex(new Uint8Array(await crypto.subtle.digest("SHA-1", der))) };
}
function status(c, util) {
  const now = Date.now();
  if (c.to && now > c.to) return { t: "Expired on " + c.to.toLocaleDateString("en-GB", { dateStyle: "long" }), cls: "bad" };
  if (c.from && now < c.from) return { t: "Not yet valid (from " + c.from.toLocaleDateString("en-GB", { dateStyle: "long" }) + ")", cls: "bad" };
  return { t: "Valid", cls: "ok" };
}
async function certCard(c, util, title) {
  const { el } = util;
  const who = dnGet(c.subject, "CN") || [dnGet(c.subject, "GN"), dnGet(c.subject, "SN")].filter(Boolean).join(" ") || dnGet(c.subject, "O") || dnText(c.subject);
  const card = el("div", { class: "card" });
  if (title) card.append(el("div", { class: "card-sub", text: title }));
  card.append(el("div", { class: "card-name", text: who }));
  const org = dnGet(c.subject, "O");
  if (org && org !== who) card.append(el("div", { class: "card-sub", text: org }));
  const st = status(c, util);
  card.append(el("div", { class: "cert-status " + st.cls, text: st.t }));
  const kv = (k, v) => v && card.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: v })));
  const fmtD = d => d ? d.toLocaleString("en-GB", { dateStyle: "long", timeStyle: "short" }) : "";
  kv("Issued to", dnText(c.subject));
  kv("ID / serial no.", dnGet(c.subject, "serialNumber"));
  kv("Issued by", dnGet(c.issuer, "CN") ? `${dnGet(c.issuer, "CN")}${dnGet(c.issuer, "O") ? " (" + dnGet(c.issuer, "O") + ")" : ""}` : dnText(c.issuer));
  kv("Valid from", fmtD(c.from));
  kv("Valid until", fmtD(c.to));
  kv("Key", c.keyDesc);
  kv("Signature", c.sigAlg);
  kv("Uses", [...(c.ext.ku || []), ...(c.ext.eku || [])].join(", "));
  kv("Alternative names", (c.ext.san || []).slice(0, 30).join("\n"));
  kv("Type", [c.ext.ca ? "certificate authority (CA)" : "", c.ext.qc ? "qualified certificate (eIDAS)" : "", dnText(c.subject) === dnText(c.issuer) ? "self-signed" : ""].filter(Boolean).join(", "));
  kv("Serial number", hex(c.serial));
  const fp = await fingerprints(c.der);
  kv("SHA-256", fp.sha256);
  kv("SHA-1", fp.sha1);
  return card;
}

async function certs({ root, util }, blocks) {
  const box = util.el("div", { class: "cards" });
  root.append(box);
  let n = 0, keys = 0;
  for (const b of blocks) {
    if (b.label && /PRIVATE KEY/.test(b.label)) { keys++; box.append(util.el("div", { class: "card" }, util.el("div", { class: "card-name", text: "Private key" }), util.el("div", { class: "card-sub", text: `${b.label} · contents not shown (it's a secret)` }))); continue; }
    if (b.label && /PUBLIC KEY/.test(b.label)) {
      let d = "";
      try { const k = parse(b.bytes); const a = oid(k.children[0].children[0]); d = ALGS[a] || a; if (a === "1.2.840.113549.1.1.1") { const r = parse(k.children[1].content().subarray(1)); let m = r.children[0].content(); while (m[0] === 0) m = m.subarray(1); d = `RSA ${m.length * 8} bit`; } } catch {}
      box.append(util.el("div", { class: "card" }, util.el("div", { class: "card-name", text: "Public key" }), util.el("div", { class: "card-sub", text: d })));
      continue;
    }
    if (b.label && /REQUEST/.test(b.label)) {
      try { const r = parse(b.bytes); const subj = dn(r.children[0].children[1]); box.append(util.el("div", { class: "card" }, util.el("div", { class: "card-sub", text: "Certificate signing request (CSR)" }), util.el("div", { class: "card-name", text: dnGet(subj, "CN") || dnText(subj) }), util.el("div", { class: "kv" }, util.el("b", { text: "Subject" }), util.el("span", { text: dnText(subj) })))); } catch {}
      continue;
    }
    try { box.append(await certCard(x509(b.bytes), util, blocks.length > 1 ? `Certificate ${++n}` : null)); if (blocks.length === 1) n = 1; }
    catch (e) { box.append(util.el("div", { class: "card" }, util.el("div", { class: "card-name", text: b.label || "Unrecognized block" }), util.el("div", { class: "card-sub", text: "Not a readable certificate" }))); }
  }
  if (!box.children.length) throw Object.assign(new Error("No certificate in the file."), { user: true });
  util.A.onFormat(n ? `Certificate${n > 1 ? ` · ${n}` : ""}` : keys ? "Private key" : "PEM");
}

// ------------------------------------------------------------------ PKCS#7 / CMS SignedData (.p7m)
function isP7(b) { try { const n = parse(b); return oid(n.children[0]) === "1.2.840.113549.1.7.2"; } catch { return false; } }
async function p7m(ctx, der) {
  const { root, util, name } = ctx;
  let ci;
  try { ci = parse(der); } catch (e) { throw Object.assign(new Error("Damaged digital signature (invalid ASN.1)."), { user: true }); }
  if (oid(ci.children[0]) !== "1.2.840.113549.1.7.2") throw Object.assign(new Error(oid(ci.children[0]) === "1.2.840.113549.1.7.3" ? "Encrypted envelope (PKCS#7 enveloped): can't be opened without the private key." : "Not a PKCS#7 signature."), { user: true });
  const sd = ci.children[1].children[0].children;
  let i = 2;
  const encap = sd[i++];
  const contentType = oid(encap.children[0]);
  const content = encap.children[1] ? octets(encap.children[1].children[0]) : null;
  const certsNode = sd[i] && sd[i].cls === 2 && sd[i].num === 0 ? sd[i++] : null;
  if (sd[i] && sd[i].cls === 2 && sd[i].num === 1) i++;
  const signerSet = sd[i];
  const certs = (certsNode?.children || []).map(c => { try { return x509(c.raw()); } catch { return null; } }).filter(Boolean);
  const signers = [];
  for (const si of signerSet?.children || []) {
    const ch = si.children;
    let k = 1;
    const sid = ch[k++];
    let cert = null;
    if (sid.cls === 0) { const serial = hex(sid.children[1].content()); cert = certs.find(c => hex(c.serial) === serial); }
    else cert = certs[0];
    const digestAlg = oid(ch[k++].children[0]);
    let signedAttrs = null, sTime = null, mdAttr = null;
    if (ch[k].cls === 2 && ch[k].num === 0) {
      signedAttrs = ch[k++];
      for (const a of signedAttrs.children) {
        const t = oid(a.children[0]);
        if (t === "1.2.840.113549.1.9.5") sTime = time(a.children[1].children[0]);
        if (t === "1.2.840.113549.1.9.4") mdAttr = a.children[1].children[0].content();
      }
    }
    const sigAlg = oid(ch[k++].children[0]);
    const sig = ch[k++].content();
    signers.push({ cert, digestAlg, sigAlg, sig, signedAttrs, sTime, mdAttr });
  }
  // integrita': impronta del contenuto == messageDigest, e firma sugli attributi con la chiave del certificato
  for (const s of signers) {
    s.check = "can't be verified";
    try {
      const alg = DIGEST[s.digestAlg];
      if (!alg || !content || !crypto.subtle || !s.cert) continue;
      const md = new Uint8Array(await crypto.subtle.digest(alg, content));
      if (s.mdAttr && hex(md) !== hex(s.mdAttr)) { s.check = "NOT intact: the content was changed after signing"; s.bad = true; continue; }
      const data = s.signedAttrs ? Uint8Array.from(s.signedAttrs.raw()) : content;
      if (s.signedAttrs) data[0] = 0x31; // gli attributi si firmano come SET
      const ok = await verify(s.cert, alg, s.sig, data);
      s.check = ok === true ? "intact: the content is what was signed" : ok === false ? "NOT valid: the signature doesn't match" : "digest matches; signature algorithm can't be verified here";
      if (ok === false) s.bad = true;
    } catch (e) { console.warn("verifica firma", e); }
  }
  const inner = name.replace(/\.(p7m|p7s|p7c|p7b)$/i, "").replace(/\.(p7m)$/i, "") || "content";
  util.A.onFormat(`Digital signature · ${signers.length} ${signers.length === 1 ? "signer" : "signers"}`);
  const box = util.el("div", { class: "cards" });
  root.append(box);
  for (const s of signers) {
    const card = s.cert ? await certCard(s.cert, util, "Signed by") : util.el("div", { class: "card" }, util.el("div", { class: "card-name", text: "Unknown signer" }));
    const first = card.querySelector(".kv");
    const add = (k, v, cls) => { const r = util.el("div", { class: "kv" + (cls ? " " + cls : "") }, util.el("b", { text: k }), util.el("span", { text: v })); card.insertBefore(r, first); };
    if (s.sTime) add("Signed on", s.sTime.toLocaleString("en-GB", { dateStyle: "full", timeStyle: "short" }));
    add("Integrity", s.check, s.bad ? "bad" : /^intact/.test(s.check) ? "ok" : "");
    box.append(card);
  }
  if (!signers.length && certs.length) for (const c of certs) box.append(await certCard(c, util, "Included certificate"));
  util.notice("Integrity checked here; legal validity (CA chain, revocation) can only be checked online");
  if (!content) {
    root.append(util.el("p", { class: "muted", text: contentType === "1.2.840.113549.1.7.1" && !certs.length ? "Detached signature (.p7s): the signed document is a separate file." : certs.length && !signers.length ? "The file only contains certificates." : "The signed document isn't inside this file (detached signature)." }));
    return;
  }
  const head = util.el("div", { class: "p7m-head" }, util.el("h2", { text: `Signed document: ${inner}` }), util.el("span", { class: "muted", text: util.fmtSize(content.length) }));
  const btn = util.el("button", { class: "btn", type: "button", text: "Open full screen" });
  btn.addEventListener("click", () => ctx.openInner(content, inner));
  head.append(btn);
  root.append(head);
  const holder = util.el("div", { class: "p7m-doc" });
  root.append(holder);
  try { await renderInto(holder, content, inner, "auto", (ctx.depth || 0) + 1); }
  catch (e) { holder.append(util.el("p", { class: "muted", text: "The signed document can't be shown here: " + (e.message || e) })); }
}
async function verify(cert, hash, sig, data) {
  const s = crypto.subtle;
  if (cert.keyAlg === "1.2.840.113549.1.1.1") {
    const key = await s.importKey("spki", cert.spki, { name: "RSASSA-PKCS1-v1_5", hash }, false, ["verify"]);
    return s.verify("RSASSA-PKCS1-v1_5", key, sig, data);
  }
  if (cert.keyAlg === "1.2.840.10045.2.1") {
    const curve = /P-256/.test(cert.keyDesc) ? "P-256" : /P-384/.test(cert.keyDesc) ? "P-384" : /P-521/.test(cert.keyDesc) ? "P-521" : null;
    if (!curve) return null;
    const key = await s.importKey("spki", cert.spki, { name: "ECDSA", namedCurve: curve }, false, ["verify"]);
    const n = parse(sig), size = { "P-256": 32, "P-384": 48, "P-521": 66 }[curve];
    const fix = v => { v = v.content(); while (v.length > size && v[0] === 0) v = v.subarray(1); const o = new Uint8Array(size); o.set(v, size - v.length); return o; };
    const raw = new Uint8Array(size * 2); raw.set(fix(n.children[0])); raw.set(fix(n.children[1]), size);
    return s.verify({ name: "ECDSA", hash }, key, raw, data);
  }
  return null;
}
