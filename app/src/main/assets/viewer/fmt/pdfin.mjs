// PDF "dentro" un altro file (allegato di un'email, file di un archivio, contenuto di una firma .p7m,
// anteprima di Pages/Keynote): pagine disegnate con pdf.js su canvas, alla larghezza dello schermo,
// solo quando stanno per entrare nella vista. Il PDF aperto da solo usa invece pdf.html (ricerca, testo…).
import "../compat.mjs";

let libP = null;
async function lib() {
  if (!libP) libP = import("/assets/pdfjs/build/pdf.min.mjs").then(() => {
    const L = globalThis.pdfjsLib;
    L.GlobalWorkerOptions.workerSrc = "/assets/pdfjs/build/pdf.worker.min.mjs";
    return L;
  });
  return libP;
}

export async function render(ctx) {
  const n = await renderPdf(ctx.root, ctx.util, ctx.bytes);
  ctx.util.A.onFormat(`PDF · ${n} ${n === 1 ? "page" : "pages"}`);
}

/** Disegna le pagine di un PDF in root. Ritorna il numero di pagine. */
export async function renderPdf(root, util, bytes, { maxPages = 500, label } = {}) {
  const L = await lib();
  const pdf = await L.getDocument({
    data: bytes.slice(), cMapUrl: "/assets/pdfjs/cmaps/", cMapPacked: true, standardFontDataUrl: "/assets/pdfjs/standard_fonts/",
    wasmUrl: "/assets/pdfjs/wasm/", iccUrl: "/assets/pdfjs/iccs/", useWorkerFetch: false, useSystemFonts: false, isEvalSupported: false,
  }).promise;
  const list = util.el("div", { class: "pdfin" });
  if (label) list.append(util.el("div", { class: "pdfin-label", text: label }));
  root.append(list);
  const W = Math.min(900, (root.getBoundingClientRect().width || innerWidth || 400));
  const dpr = Math.min(3, devicePixelRatio || 2);
  const count = Math.min(pdf.numPages, maxPages);
  const io = new IntersectionObserver(async es => {
    for (const e of es) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      const i = Number(e.target.dataset.p);
      try {
        const page = await pdf.getPage(i);
        const vp0 = page.getViewport({ scale: 1 });
        const scale = W / vp0.width;
        const vp = page.getViewport({ scale: scale * dpr });
        const c = document.createElement("canvas");
        c.width = Math.round(vp.width); c.height = Math.round(vp.height);
        c.style.width = W + "px"; c.style.height = Math.round(vp0.height * scale) + "px";
        await page.render({ canvasContext: c.getContext("2d"), viewport: vp }).promise;
        e.target.replaceChildren(c);
        e.target.style.height = "";
      } catch (err) {
        e.target.replaceChildren(util.el("div", { class: "muted", text: `Page ${i}: can't be drawn` }));
      }
    }
  }, { rootMargin: "120% 0px" });
  const first = await pdf.getPage(1);
  const r = first.getViewport({ scale: 1 });
  for (let i = 1; i <= count; i++) {
    const box = util.el("div", { class: "pdfin-page", "data-p": String(i), style: { width: W + "px", height: Math.round(W * r.height / r.width) + "px" } });
    list.append(box);
    io.observe(box);
  }
  if (pdf.numPages > count) util.notice(`Showing the first ${count} pages of ${pdf.numPages}`);
  return pdf.numPages;
}
