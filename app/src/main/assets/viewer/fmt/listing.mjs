// Elenco dei file di un archivio (ZIP, 7z, RAR, tar…), raggruppati per cartella. Un tocco apre il file
// interno con il visualizzatore giusto (openInner). entries: [{ name: "cartella/file.txt", size, read: async () => Uint8Array }]
export const MAX_INNER = 48 * 1024 * 1024;

export function showListing({ root, util, openInner }, entries, { title, note } = {}) {
  const files = entries.filter(f => f.name && !/(^|\/)(__MACOSX\/|\.DS_Store$)/.test(f.name));
  const list = util.el("div", { class: "list" });
  const total = files.reduce((s, f) => s + (f.size || 0), 0);
  list.append(util.el("h2", { text: (title ? title + " · " : "") + `${files.length} file${files.length === 1 ? "" : "s"}` + (total ? ` · ${util.fmtSize(total)} uncompressed` : "") }));
  if (note) util.notice(note);
  const byDir = new Map();
  for (const f of files) {
    const i = f.name.lastIndexOf("/");
    const dir = i >= 0 ? f.name.slice(0, i + 1) : "";
    if (!byDir.has(dir)) byDir.set(dir, []);
    byDir.get(dir).push(f);
  }
  const dirs = [...byDir.keys()].sort((a, b) => a.localeCompare(b, "it"));
  for (const d of dirs) {
    if (d) list.append(util.el("div", { class: "entry dir-row" }, util.el("span", { class: "ico dir", text: "DIR" }), util.el("span", { class: "nm", text: d })));
    for (const f of byDir.get(d).sort((a, b) => a.name.localeCompare(b.name, "it", { numeric: true }))) {
      const nm = f.name.slice(d.length);
      const ext = (nm.includes(".") ? nm.split(".").pop() : "").slice(0, 4).toUpperCase() || "FILE";
      const row = util.el("div", { class: "entry", role: "button", tabindex: "0" },
        util.el("span", { class: colorOf(ext) === "var(--h-archive)" ? "ico ink-dark" : "ico", style: { background: colorOf(ext) }, text: ext }),
        util.el("span", { class: "nm", text: nm }),
        util.el("span", { class: "sz", text: f.size != null ? util.fmtSize(f.size) : "" }));
      row.addEventListener("click", async () => {
        if (f.size > MAX_INNER) { util.A.toast("File too large to open inside the archive."); return; }
        try {
          const bytes = await f.read();
          openInner(bytes, nm);
        } catch (e) {
          util.A.toast("Can't extract this file: " + (e.message || e));
        }
      });
      list.append(row);
    }
  }
  if (!files.length) list.append(util.el("p", { class: "muted", text: "The archive is empty." }));
  root.append(list);
}

// Tinte di sezione del manuale (fonts.css --h-*, stesse linguette di Library.Cat / Sections.kt).
export function colorOf(ext) {
  if (/^(PDF)$/.test(ext)) return "var(--h-pdf)";
  if (/^(DOC|DOCX|ODT|RTF|PAGE)$/.test(ext)) return "var(--h-word)";
  if (/^(XLS|XLSX|ODS|CSV|TSV|NUMB)$/.test(ext)) return "var(--h-excel)";
  if (/^(PPT|PPTX|ODP|KEY)$/.test(ext)) return "var(--h-ppt)";
  if (/^(MD|TXT|LOG|JSON|XML|YAML|YML|INI|TOML|JS|PY|KT|JAVA|C|H|CPP|SH|HTML|CSS|SQL)$/.test(ext)) return "var(--h-text)";
  if (/^(MP3|M4A|AAC|OGG|OPUS|FLAC|WAV)$/.test(ext)) return "var(--h-audio)";
  if (/^(ZIP|7Z|RAR|TAR|GZ|TGZ|BZ2|XZ|APK)$/.test(ext)) return "var(--h-archive)";
  return "var(--h-other)";
}
