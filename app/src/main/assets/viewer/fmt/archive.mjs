// Archivi che non sono ZIP: tar e gzip scritti qui (gzip con DecompressionStream del motore web, niente
// librerie), 7z / RAR 4-5 / bzip2 / xz / zstd con libarchive.js (libarchive in WebAssembly, MIT + BSD-2;
// il lettore RAR di libarchive e' suo, non unrar). Elenco per cartelle, un tocco apre il file interno.
import { showListing } from "./listing.mjs";
import { archiveKind } from "../doc.mjs";

const MAX_OUT = 256 * 1024 * 1024; // un .gz che si gonfia oltre questo e' una bomba o comunque troppo

export async function render(ctx) {
  const { util, name } = ctx;
  const { entries, label, note, single } = await entriesOf(ctx);
  util.A.onFormat(label);
  if (single) { // .gz / .bz2 / .xz di un file solo: si apre direttamente quello che c'e' dentro
    const bytes = await single.read();
    return ctx.openInnerHere ? ctx.openInnerHere(bytes, single.name) : renderSingle(ctx, bytes, single.name);
  }
  showListing(ctx, entries, { note });
}

async function renderSingle(ctx, bytes, innerName) {
  const { renderInto } = await import("../doc.mjs");
  const box = ctx.util.el("div");
  ctx.root.append(box);
  ctx.util.notice(`Compressed: contains “${innerName}” (${ctx.util.fmtSize(bytes.length)})`);
  await renderInto(box, bytes, innerName, "auto", (ctx.depth || 0) + 1);
}

/** Voci di QUALUNQUE archivio (anche ZIP): [{name, size, read}]. Serve anche ai fumetti. */
export async function entriesOf(ctx) {
  const { bytes, name, zip } = ctx;
  if (zip) {
    const files = Object.values(zip.files).filter(f => !f.dir);
    return { entries: files.map(f => ({ name: f.name, size: f._data && f._data.uncompressedSize, read: () => f.async("uint8array") })), label: "ZIP" };
  }
  const kind = ctx.archive || archiveKind(bytes);
  if (kind === "tar") return { entries: tarEntries(bytes), label: "TAR archive" };
  if (kind === "gz") {
    const out = await gunzip(bytes);
    if (archiveKind(out) === "tar") return { entries: tarEntries(out), label: "TAR.GZ archive" };
    return { entries: [], single: { name: innerName(name, /\.(gz|gzip)$/i), read: async () => out }, label: "GZIP compressed file" };
  }
  return libarchive(bytes, name, kind);
}

function innerName(name, re) {
  const n = name.replace(re, "");
  if (n !== name && n) return /\.(tgz)$/i.test(name) ? n + ".tar" : n;
  return (name.replace(/\.[^.]+$/, "") || "file") + (/\.tgz$/i.test(name) ? ".tar" : "");
}

async function gunzip(bytes) {
  const ds = new DecompressionStream("gzip");
  const w = ds.writable.getWriter();
  w.write(bytes).catch(() => {}); w.close().catch(() => {});
  const r = ds.readable.getReader();
  const parts = []; let n = 0;
  for (;;) {
    const { done, value } = await r.read();
    if (done) break;
    n += value.length;
    if (n > MAX_OUT) { r.cancel(); throw Object.assign(new Error(`The decompressed file is over ${MAX_OUT / 1048576} MB.`), { user: true }); }
    parts.push(value);
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

// ------------------------------------------------------------------ tar (ustar, GNU nomi lunghi, pax)
function tarEntries(b) {
  const out = [];
  const dec = new TextDecoder();
  const str = (o, l) => { let e = o; while (e < o + l && b[e]) e++; return dec.decode(b.subarray(o, e)); };
  const oct = (o, l) => {
    if (b[o] & 0x80) { let v = 0; for (let i = o + 1; i < o + l; i++) v = v * 256 + b[i]; return v; } // base-256 (file > 8 GB)
    return parseInt(str(o, l).trim() || "0", 8) || 0;
  };
  let p = 0, longName = null, paxPath = null;
  while (p + 512 <= b.length) {
    if (b[p] === 0 && b[p + 1] === 0 && b.subarray(p, p + 512).every(x => x === 0)) break;
    let nm = str(p, 100);
    const size = oct(p + 124, 12);
    const type = String.fromCharCode(b[p + 156] || 48);
    if (str(p + 257, 5) === "ustar") { const pre = str(p + 345, 155); if (pre) nm = pre + "/" + nm; }
    const data = p + 512;
    const next = data + Math.ceil(size / 512) * 512;
    if (type === "L") { longName = str(data, size); p = next; continue; }
    if (type === "x") {
      const m = /\d+ path=([^\n]*)\n/.exec(dec.decode(b.subarray(data, data + size)));
      if (m) paxPath = m[1];
      p = next; continue;
    }
    if (type === "g") { p = next; continue; }
    const full = paxPath || longName || nm;
    longName = paxPath = null;
    if (type === "0" || type === "\0" || type === "7") {
      const start = data, end = Math.min(b.length, data + size);
      out.push({ name: full.replace(/^\.\//, ""), size, read: async () => b.subarray(start, end) });
    }
    p = next;
  }
  return out;
}

// ------------------------------------------------------------------ libarchive.js (7z, RAR, bz2, xz, zstd…)
let libP = null;
async function libarchive(bytes, name, kind) {
  if (!libP) libP = import("/assets/lib/libarchive/libarchive.js").then(m => { m.Archive.init({ workerUrl: "/assets/lib/libarchive/worker-bundle.js" }); return m.Archive; });
  const Archive = await libP;
  const arc = await Archive.open(new File([bytes], name || "archive"));
  let enc = null;
  try { enc = await arc.hasEncryptedData(); } catch {}
  let arr = [];
  try { arr = await arc.getFilesArray(); } catch (e) { console.warn("libarchive", e); }
  if (!arr.length && ["bz2", "xz", "zst"].includes(kind)) {
    throw Object.assign(new Error(kind === "zst" ? "Zstandard compression isn't supported (the archive library doesn't include it)."
      : `${kind === "bz2" ? "bzip2" : "xz"} compressed file without an archive inside: only .tar.${kind} files can be opened.`), { user: true });
  }
  const label = { "7z": "7-Zip archive", rar: "RAR archive", bz2: "BZIP2 archive", xz: "XZ archive", zst: "Zstandard archive" }[kind] || "Archive";
  const entries = arr.map(({ file, path }) => ({
    name: (path || "") + file.name, size: file.size,
    read: async () => new Uint8Array(await (await file.extract()).arrayBuffer()),
  }));
  // bz2/xz/zst di un file solo: libarchive lo presenta come un'unica voce "data"
  if (entries.length === 1 && ["bz2", "xz", "zst"].includes(kind) && !/\.tar$/i.test(entries[0].name)) {
    const n = innerName(name, /\.(bz2|xz|zst)$/i);
    return { entries: [], single: { name: n, read: entries[0].read }, label: label.replace("archive", "compressed file") };
  }
  const lbl = ["bz2", "xz", "zst"].includes(kind) ? `TAR.${kind.toUpperCase()} archive` : label;
  return { entries, label: lbl, note: enc ? "Password-protected archive: encrypted files can't be opened" : null };
}
