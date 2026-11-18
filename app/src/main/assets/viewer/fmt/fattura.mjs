// Fattura elettronica italiana (FatturaPA / FPR12 / FPA12, anche "semplificata"): arriva come .xml o
// dentro una firma .xml.p7m. Si mostra come una fattura leggibile: chi la emette e chi la riceve, numero e
// data, righe, riepilogo IVA, totale, pagamento, allegati (apribili). Sotto, l'XML originale.
export async function render({ root, bytes, util, openInner }) {
  const { el } = util;
  const d = new DOMParser().parseFromString(util.decodeText(bytes), "application/xml");
  if (d.getElementsByTagName("parsererror").length) throw Object.assign(new Error("Invalid e-invoice (broken XML)."), { user: true });
  const q = (e, n) => e ? [...e.getElementsByTagName(n)] : [];
  const one = (e, path) => { let cur = e; for (const n of path.split("/")) { cur = q(cur, n)[0]; if (!cur) return ""; } return cur.textContent.trim(); };
  const who = e => one(e, "Denominazione") || [one(e, "Nome"), one(e, "Cognome")].filter(Boolean).join(" ");
  const eur = v => v === "" || v == null ? "" : Number(v).toLocaleString("en-GB", { style: "currency", currency: "EUR" });
  const date = v => { const m = /^(\d{4})-(\d\d)-(\d\d)/.exec(v || ""); return m ? new Date(+m[1], +m[2] - 1, +m[3]).toLocaleDateString("en-GB", { dateStyle: "long" }) : v; };
  const head = q(d, "FatturaElettronicaHeader")[0];
  const ced = q(head, "CedentePrestatore")[0], ces = q(head, "CessionarioCommittente")[0];
  const bodies = q(d, "FatturaElettronicaBody");
  const TIPI = { TD01: "Invoice", TD02: "Advance on invoice", TD03: "Advance on fee note", TD04: "Credit note", TD05: "Debit note", TD06: "Fee note",
    TD07: "Simplified invoice", TD08: "Simplified credit note", TD16: "Reverse charge supplement", TD17: "Self-invoice for services from abroad", TD24: "Deferred invoice", TD25: "Deferred invoice (art. 21 c. 4 b)", TD26: "Sale of depreciable assets", TD27: "Self-consumption" };
  const PAG = { MP01: "cash", MP02: "cheque", MP05: "bank transfer", MP08: "payment card", MP12: "RIBA", MP19: "SEPA Direct Debit", MP23: "PagoPA" };
  const party = (title, e) => {
    const c = el("div", { class: "card" }, el("div", { class: "card-sub", text: title }), el("div", { class: "card-name", text: who(e) || "—" }));
    const kv = (k, v) => v && c.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: v })));
    kv("VAT number", [one(e, "IdFiscaleIVA/IdPaese"), one(e, "IdFiscaleIVA/IdCodice")].join(""));
    kv("Tax code", one(e, "CodiceFiscale"));
    const sede = q(e, "Sede")[0];
    if (sede) kv("Address", [one(sede, "Indirizzo") + (one(sede, "NumeroCivico") ? " " + one(sede, "NumeroCivico") : ""), [one(sede, "CAP"), one(sede, "Comune"), one(sede, "Provincia") && `(${one(sede, "Provincia")})`].filter(Boolean).join(" ")].filter(Boolean).join(", "));
    kv("Email", one(e, "Contatti/Email"));
    return c;
  };
  const box = el("div", { class: "cards fattura" });
  root.append(box);
  let total = 0;
  bodies.forEach((b, i) => {
    const g = q(b, "DatiGeneraliDocumento")[0];
    const tipo = one(g, "TipoDocumento");
    const tot = one(g, "ImportoTotaleDocumento");
    const card = el("div", { class: "card" },
      el("div", { class: "card-sub", text: (TIPI[tipo] || tipo || "Document") + (bodies.length > 1 ? ` (${i + 1} of ${bodies.length})` : "") }),
      el("div", { class: "card-name", text: `No. ${one(g, "Numero")} of ${date(one(g, "Data"))}` }));
    const kv = (k, v) => v && card.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: v })));
    kv("Total", eur(tot));
    for (const c of q(g, "Causale")) kv("Reason", c.textContent.trim());
    if (i === 0) { box.append(card, party("Issued by", ced), party("Billed to", ces)); } else box.append(card);
    // righe
    const righe = q(b, "DettaglioLinee");
    const beni = q(b, "DatiBeniServizi")[0];
    if (righe.length) {
      const t = el("table", { class: "doc-table fattura-righe" });
      t.append(el("tr", null, ...["Description", "Qty", "Price", "VAT", "Amount"].map(h => el("th", { text: h }))));
      for (const r of righe) t.append(el("tr", null, el("td", { text: one(r, "Descrizione") }), el("td", { class: "n", text: one(r, "Quantita") ? Number(one(r, "Quantita")).toLocaleString("en-GB") + (one(r, "UnitaMisura") ? " " + one(r, "UnitaMisura") : "") : "" }),
        el("td", { class: "n", text: eur(one(r, "PrezzoUnitario")) }), el("td", { class: "n", text: one(r, "AliquotaIVA") ? Number(one(r, "AliquotaIVA")) + "%" : one(r, "Natura") }), el("td", { class: "n", text: eur(one(r, "PrezzoTotale")) })));
      box.append(el("div", { class: "card fattura-tab" }, el("div", { class: "card-sub", text: `Lines (${righe.length})` }), t));
    } else if (q(b, "DatiBeniServizi").length && one(beni, "Descrizione")) { // semplificata
      box.append(el("div", { class: "card" }, el("div", { class: "card-sub", text: "Description" }), el("div", { text: one(beni, "Descrizione") }), el("div", { class: "kv" }, el("b", { text: "Amount" }), el("span", { text: eur(one(beni, "Importo")) }))));
    }
    const riep = q(b, "DatiRiepilogo");
    if (riep.length) {
      const c = el("div", { class: "card" }, el("div", { class: "card-sub", text: "VAT summary" }));
      let imp = 0, iva = 0;
      for (const r of riep) {
        imp += +one(r, "ImponibileImporto") || 0; iva += +one(r, "Imposta") || 0;
        c.append(el("div", { class: "kv" }, el("b", { text: one(r, "AliquotaIVA") ? Number(one(r, "AliquotaIVA")) + "%" : one(r, "Natura") }), el("span", { text: `taxable ${eur(one(r, "ImponibileImporto"))} · VAT ${eur(one(r, "Imposta"))}` + (one(r, "RiferimentoNormativo") ? " · " + one(r, "RiferimentoNormativo") : "") })));
      }
      c.append(el("div", { class: "kv" }, el("b", { text: "Total" }), el("span", { text: eur(tot || imp + iva) })));
      box.append(c);
      total += +(tot || imp + iva);
    }
    for (const p of q(b, "DettaglioPagamento")) {
      const c = el("div", { class: "card" }, el("div", { class: "card-sub", text: "Payment" }));
      const kv2 = (k, v) => v && c.append(el("div", { class: "kv" }, el("b", { text: k }), el("span", { text: v })));
      kv2("Method", PAG[one(p, "ModalitaPagamento")] || one(p, "ModalitaPagamento"));
      kv2("Amount", eur(one(p, "ImportoPagamento")));
      kv2("Due date", date(one(p, "DataScadenzaPagamento")));
      kv2("IBAN", one(p, "IBAN"));
      kv2("Payee", one(p, "Beneficiario"));
      box.append(c);
    }
    const att = q(b, "Allegati");
    if (att.length) {
      const l = el("div", { class: "attachments list" }, el("h2", { text: `Attachments (${att.length})` }));
      for (const a of att) {
        const nm = one(a, "NomeAttachment") || "attachment";
        let data = null;
        try { data = Uint8Array.from(atob(one(a, "Attachment").replace(/\s+/g, "")), c => c.charCodeAt(0)); } catch {}
        const r = el("div", { class: "entry", role: "button", tabindex: "0" }, el("span", { class: "ico", text: (nm.split(".").pop() || "FILE").slice(0, 4).toUpperCase() }), el("span", { class: "nm", text: nm + (one(a, "DescrizioneAttachment") ? " — " + one(a, "DescrizioneAttachment") : "") }), el("span", { class: "sz", text: data ? util.fmtSize(data.length) : "" }));
        if (data) r.addEventListener("click", () => openInner(data, nm));
        l.append(r);
      }
      box.append(l);
    }
  });
  util.A.onFormat("E-invoice" + (total ? " · " + eur(total) : ""));
  await util.need("hljs");
  const det = el("details", { class: "geo-src" }, el("summary", { text: "Original XML" }));
  const pre = el("pre", { class: "hljs nb-text" });
  const src = util.decodeText(bytes);
  try { pre.innerHTML = hljs.highlight(src.slice(0, 600000), { language: "xml", ignoreIllegals: true }).value; } catch { pre.textContent = src; }
  det.append(pre);
  root.append(det);
}
