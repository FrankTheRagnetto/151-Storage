// Fumetti: CBZ (ZIP), CBR (RAR), CB7 (7z), CBT (tar). Le pagine sono le immagini dell'archivio in ordine
// "naturale" (pag2 prima di pag10), una sotto l'altra alla larghezza dello schermo; pinch = zoom nativo.
// Le immagini si decodificano solo quando stanno per entrare nello schermo (fumetti da centinaia di pagine).
import { entriesOf } from "./archive.mjs";

const IMG = /\.(png|jpe?g|gif|webp|bmp|avif|jxl|svg)$/i;

export async function render(ctx) {
  const { root, util } = ctx;
  const { entries } = await entriesOf(ctx);
  const pages = entries.filter(e => IMG.test(e.name) && !/(^|\/)(__MACOSX\/|\.)/.test(e.name))
    .sort((a, b) => a.name.localeCompare(b.name, "it", { numeric: true, sensitivity: "base" }));
  if (!pages.length) throw Object.assign(new Error("There are no images in this comic."), { user: true });
  util.A.onFormat(`Comic · ${pages.length} page${pages.length === 1 ? "" : "s"}`);
  util.mode("page", innerWidth || 412);
  document.body.classList.add("page-mode");
  const list = util.el("div", { class: "comic" });
  root.append(list);
  const urls = [];
  const io = new IntersectionObserver(async es => {
    for (const e of es) {
      if (!e.isIntersecting) continue;
      const box = e.target;
      io.unobserve(box);
      const pg = pages[Number(box.dataset.i)];
      try {
        const bytes = await pg.read();
        const u = URL.createObjectURL(new Blob([bytes], { type: util.mimeOf(pg.name) }));
        urls.push(u);
        const img = util.el("img", { class: "comic-page", alt: `Page ${Number(box.dataset.i) + 1}` });
        img.onload = () => { box.style.aspectRatio = ""; box.style.minHeight = ""; };
        img.src = u;
        box.replaceChildren(img);
      } catch (err) {
        box.replaceChildren(util.el("div", { class: "comic-err", text: `Page ${Number(box.dataset.i) + 1}: unreadable (${err.message || err})` }));
      }
    }
  }, { rootMargin: "150% 0px" });
  pages.forEach((p, i) => {
    const box = util.el("div", { class: "comic-box", "data-i": String(i), style: { aspectRatio: "2 / 3" } },
      util.el("div", { class: "comic-num", text: String(i + 1) }));
    list.append(box);
    io.observe(box);
  });
  const extra = entries.length - pages.length;
  if (extra > 0) util.notice(`${extra} non-page file${extra === 1 ? "" : "s"} (text, info) not shown`);
}
