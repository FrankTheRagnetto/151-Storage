// Piccoli file "di servizio":
//  - collegamenti: .url (Windows), .webloc (macOS, XML o plist binario), .desktop (Linux). Si mostra il
//    link e basta: si apre solo se lo tocchi, e anche allora l'app chiede conferma. Mai in automatico.
//  - .torrent: metadati (nome, file, dimensioni, tracker, hash dell'info) letti dal bencode. Niente rete.
export async function render(ctx) {
  if (ctx.fmt === "torrent") return torrent(ctx);
  return link(ctx);
}

function link({ root, bytes, util, name }) {
  const { el } = util;
  let url = "", title = "", extra = [];
  const ext = name.toLowerCase().split(".").pop();
  if (String.fromCharCode(...bytes.subarray(0, 8)) === "bplist00") {
    // plist binario: prendo la prima stringa che sembra un URL
    const s = new TextDecoder("latin1").decode(bytes);
    const m = /(https?|ftp|file|mailto|smb|afp):[^\x00-\x1f\x7f-\xff"'<> ]+/i.exec(s);
    url = m ? m[0] : "";
    title = "macOS shortcut (.webloc)";
  } else {
    const t = util.decodeText(bytes);
    if (/<plist/i.test(t)) {
      const d = new DOMParser().parseFromString(t, "application/xml");
      const keys = [...d.getElementsByTagName("key")];
      const k = keys.find(k => k.textContent === "URL");
      url = k?.nextElementSibling?.textContent.trim() || "";
      title = "macOS shortcut (.webloc)";
    } else {
      // INI: [InternetShortcut] URL=…  |  [Desktop Entry] Type=Link URL=… / Exec=…
      let sec = "";
      const kv = {};
      for (const line of t.split(/\r?\n/)) {
        const s = /^\s*\[([^\]]+)\]/.exec(line);
        if (s) { sec = s[1]; continue; }
        const m = /^\s*([^=#;]+?)\s*=\s*(.*)$/.exec(line);
        if (m && (sec === "InternetShortcut" || sec === "Desktop Entry" || sec === "DEFAULT")) kv[m[1]] = kv[m[1]] ?? m[2];
      }
      url = kv.URL || kv.url || "";
      if (ext === "desktop" || kv.Type) {
        title = kv.Type === "Link" ? "Linux shortcut (.desktop)" : "Linux menu entry (.desktop)";
        if (kv.Name) extra.push(["Name", kv.Name]);
        if (kv.Comment) extra.push(["Description", kv.Comment]);
        if (kv.Exec) extra.push(["Command", kv.Exec]);
        if (kv.Icon) extra.push(["Icon", kv.Icon]);
        if (kv.Categories) extra.push(["Categories", kv.Categories]);
      } else {
        title = "Windows shortcut (.url)";
        if (kv.IconFile) extra.push(["Icon", kv.IconFile]);
      }
    }
  }
  util.A.onFormat(title);
  const card = el("div", { class: "card link-card" }, el("div", { class: "card-sub", text: title }));
  if (url) {
    let host = "";
    try { host = new URL(url).host; } catch {}
    card.append(el("div", { class: "card-name", text: host || url }), el("div", { class: "link-url", text: url }));
    const safe = /^(https?|mailto|tel|geo):/i.test(url);
    const b = el("button", { class: "btn link-open", type: "button", text: safe ? "Open link…" : "Link type not supported" });
    if (safe) b.addEventListener("click", () => util.A.openLink(url)); else b.disabled = true;
    card.append(b);
  } else card.append(el("div", { class: "card-name", text: "No address in the file" }));
  for (const [k, v] of extra) card.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: v })));
  root.append(el("div", { class: "cards" }, card));
  util.notice("The link doesn't open by itself: tap it and the app will ask you to confirm");
}

// ------------------------------------------------------------------ torrent (bencode)
function bdecode(b) {
  let p = 0;
  const dec = new TextDecoder("utf-8", { fatal: false });
  const read = () => {
    const c = b[p];
    if (c === 0x64) { p++; const o = {}; const raws = {}; while (b[p] !== 0x65) { const k = dec.decode(read()); const s = p; o[k] = read(); raws[k] = [s, p]; } p++; Object.defineProperty(o, "__raw", { value: raws }); return o; }
    if (c === 0x6C) { p++; const a = []; while (b[p] !== 0x65) a.push(read()); p++; return a; }
    if (c === 0x69) { p++; let e = p; while (b[e] !== 0x65) e++; const v = Number(new TextDecoder().decode(b.subarray(p, e))); p = e + 1; return v; }
    if (c >= 0x30 && c <= 0x39) { let e = p; while (b[e] !== 0x3A) e++; const n = Number(new TextDecoder().decode(b.subarray(p, e))); p = e + 1; const s = b.subarray(p, p + n); p += n; return s; }
    throw new Error("invalid bencode at " + p);
  };
  return read();
}
async function torrent({ root, bytes, util }) {
  const { el } = util;
  let t;
  try { t = bdecode(bytes); } catch (e) { throw Object.assign(new Error("Damaged .torrent file."), { user: true }); }
  const s = v => v instanceof Uint8Array ? new TextDecoder().decode(v) : v == null ? "" : String(v);
  const info = t.info || {};
  const name = s(info["name.utf-8"] || info.name);
  const files = Array.isArray(info.files) ? info.files.map(f => ({ path: (f["path.utf-8"] || f.path || []).map(s).join("/"), size: f.length || 0 })) : [{ path: name, size: info.length || 0 }];
  const total = files.reduce((a, f) => a + f.size, 0);
  let hash = "";
  if (t.__raw && t.__raw.info && crypto.subtle) {
    const [a, z] = t.__raw.info;
    const h = new Uint8Array(await crypto.subtle.digest("SHA-1", bytes.subarray(a, z)));
    hash = [...h].map(x => x.toString(16).padStart(2, "0")).join("");
  }
  const trackers = [s(t.announce), ...((t["announce-list"] || []).flat().map(s))].filter((v, i, a) => v && a.indexOf(v) === i);
  util.A.onFormat(`Torrent · ${files.length} file${files.length === 1 ? "" : "s"}`);
  const card = el("div", { class: "card" }, el("div", { class: "card-name", text: name || "(no name)" }));
  const kv = (k, v) => v && card.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: v })));
  kv("Size", `${util.fmtSize(total)} in ${files.length} file${files.length === 1 ? "" : "s"}`);
  if (info["piece length"]) kv("Pieces", `${Math.round((info.pieces?.length || 0) / 20).toLocaleString("en-GB")} of ${util.fmtSize(info["piece length"])}`);
  kv("Hash (info)", hash);
  kv("Created", t["creation date"] ? new Date(t["creation date"] * 1000).toLocaleString("en-GB", { dateStyle: "long", timeStyle: "short" }) : "");
  kv("Created with", s(t["created by"]));
  kv("Comment", s(t.comment));
  kv("Private", info.private === 1 ? "yes" : "");
  if (hash) kv("Magnet", `magnet:?xt=urn:btih:${hash}` + (name ? `&dn=${encodeURIComponent(name)}` : ""));
  const box = el("div", { class: "cards" }, card);
  if (trackers.length) {
    const tc = el("div", { class: "card" }, el("div", { class: "card-sub", text: `Trackers (${trackers.length})` }));
    for (const tr of trackers.slice(0, 50)) tc.append(el("div", { class: "kv" }, el("span", { text: tr })));
    box.append(tc);
  }
  root.append(box);
  const list = el("div", { class: "list" }, el("h2", { text: `Files (${files.length})` }));
  for (const f of files.slice(0, 2000)) list.append(el("div", { class: "entry" }, el("span", { class: "ico", text: (f.path.split(".").pop() || "").slice(0, 4).toUpperCase() || "FILE" }), el("span", { class: "nm", text: f.path }), el("span", { class: "sz", text: util.fmtSize(f.size) })));
  root.append(list);
  util.notice("Torrent details only: this app doesn't download anything");
}
