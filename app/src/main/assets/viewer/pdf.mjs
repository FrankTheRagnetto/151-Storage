// 151 Viewer — visualizzatore PDF basato sui componenti di pdf.js (Apache-2.0).
// Nessuna rete: il file arriva da /file/<token>/ servito dall'app (vedi DocServer.kt).
import "./compat.mjs"; // PRIMA di pdf.js: vedi compat.mjs
import "/assets/pdfjs/build/pdf.min.mjs";
import "/assets/pdfjs/web/pdf_viewer.mjs";

const pdfjsLib = globalThis.pdfjsLib;
const { EventBus, PDFViewer, PDFLinkService, PDFFindController, LinkTarget } = globalThis.pdfjsViewer;

const P = new URLSearchParams(location.search);
const SRC = P.get("src") || "/file/none/";
const SIZE = Number(P.get("size") || -1);
const SEEK = P.get("seek") === "1";
const START_PAGE = Math.max(1, Number(P.get("page") || 1));
const CHUNK = 512 * 1024;
// sotto questa soglia conviene una sola lettura completa; sopra si legge a pezzi (apertura istantanea)
const RANGE_THRESHOLD = 4 * 1024 * 1024;

// Ponte verso Kotlin. Fuori dall'app (prova su PC) diventa un logger.
const A = window.Android || new Proxy({}, { get: (_, k) => (...a) => console.log("[Android." + String(k) + "]", ...a) });

document.documentElement.classList.toggle("theme-dark", P.get("theme") === "dark");
document.documentElement.classList.toggle("pdf-dark", P.get("dark") === "1");

pdfjsLib.GlobalWorkerOptions.workerSrc = "/assets/pdfjs/build/pdf.worker.min.mjs";

const container = document.getElementById("viewerContainer");
const pill = document.getElementById("pill");
const thumb = document.getElementById("thumb");
const bar = document.getElementById("bar");
const barFill = document.getElementById("barFill");
const msg = document.getElementById("msg");

const eventBus = new EventBus();
const linkService = new PDFLinkService({
  eventBus,
  externalLinkTarget: LinkTarget.SELF, // l'app intercetta la navigazione e chiede conferma
  externalLinkRel: "noopener noreferrer nofollow",
});
const findController = new PDFFindController({ eventBus, linkService, updateMatchesCountOnProgress: true });
const pdfViewer = new PDFViewer({
  container,
  eventBus,
  linkService,
  findController,
  textLayerMode: 1, // testo selezionabile/copiabile + ricerca
  // Fase 1: solo lettura. Moduli mostrati come sono stampati, editor annotazioni spento.
  // Fase 2: AnnotationMode.ENABLE_FORMS + annotationEditorMode NONE e saveDocument().
  annotationMode: pdfjsLib.AnnotationMode.ENABLE,
  annotationEditorMode: pdfjsLib.AnnotationEditorType.DISABLE,
  enableHWA: true,
  supportsPinchToZoom: true,
});
linkService.setViewer(pdfViewer);

let pdfDocument = null;
let fitScale = 1;
let pendingPassword = null;

// ------------------------------------------------------------------ apertura

function commonParams() {
  return {
    cMapUrl: "/assets/pdfjs/cmaps/",
    cMapPacked: true,
    standardFontDataUrl: "/assets/pdfjs/standard_fonts/",
    wasmUrl: "/assets/pdfjs/wasm/",
    iccUrl: "/assets/pdfjs/iccs/",
    useWorkerFetch: false, // i dati binari li carica la pagina, non il worker
    useSystemFonts: false, // su Android i font di sistema sfalsano il testo (pdf.js issue 18210)
    isEvalSupported: false,
    enableXfa: true,
  };
}

async function fetchBytes(url) {
  const r = await fetch(url, { cache: "no-store" });
  if (!r.ok) throw new Error("HTTP " + r.status);
  return new Uint8Array(await r.arrayBuffer());
}

async function buildSource() {
  if (SEEK && SIZE > RANGE_THRESHOLD) {
    // Lettura a pezzi: pdf.js chiede solo i byte che servono per la pagina visibile.
    const first = await fetchBytes(`${SRC}range?start=0&end=${Math.min(SIZE, CHUNK)}`);
    const transport = new pdfjsLib.PDFDataRangeTransport(SIZE, first);
    transport.requestDataRange = (begin, end) => {
      fetchBytes(`${SRC}range?start=${begin}&end=${end}`).then(
        chunk => transport.onDataRange(begin, chunk),
        err => fail("Reading the file was interrupted.", err)
      );
    };
    return { range: transport, length: SIZE, rangeChunkSize: CHUNK, ...commonParams() };
  }
  bar.classList.add("on");
  return { url: `${SRC}doc`, disableRange: true, ...commonParams() };
}

async function open() {
  let src;
  try {
    src = await buildSource();
  } catch (e) {
    return fail("Can't read the file.", e);
  }
  const task = pdfjsLib.getDocument(src);
  task.onProgress = ({ loaded, total }) => {
    if (total > 0) barFill.style.width = Math.min(100, (loaded / total) * 100) + "%";
  };
  task.onPassword = (update, reason) => {
    pendingPassword = update;
    A.askPassword(reason === pdfjsLib.PasswordResponses.INCORRECT_PASSWORD);
  };
  try {
    pdfDocument = await task.promise;
  } catch (e) {
    const t = e?.name === "InvalidPDFException"
      ? "This doesn't look like a valid PDF, or it's damaged."
      : e?.name === "PasswordException" ? "Password-protected PDF."
      : "Can't open this PDF.";
    return fail(t, e);
  }
  bar.classList.remove("on");
  pdfViewer.setDocument(pdfDocument);
  linkService.setDocument(pdfDocument, null);
  findController.setDocument(pdfDocument);
}

function fail(text, err) {
  bar.classList.remove("on");
  msg.textContent = text;
  msg.hidden = false;
  A.onError(String(err?.message || err || text));
}

eventBus.on("pagesinit", () => {
  pdfViewer.currentScaleValue = "page-width";
  fitScale = pdfViewer.currentScale;
  if (START_PAGE > 1 && START_PAGE <= pdfDocument.numPages) pdfViewer.currentPageNumber = START_PAGE;
  A.onLoaded(pdfDocument.numPages);
  A.onPage(pdfViewer.currentPageNumber, pdfDocument.numPages);
  flashPill();
});

eventBus.on("pagechanging", ({ pageNumber }) => {
  A.onPage(pageNumber, pdfDocument?.numPages || 0);
  flashPill();
});

// al cambio di orientamento/dimensione: se eravamo "a larghezza pagina" restiamo li'
let resizeTimer = 0;
window.addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    if (!pdfDocument) return;
    const v = pdfViewer.currentScaleValue;
    const wasFit = v === "page-width" || Math.abs(pdfViewer.currentScale - fitScale) < 0.01;
    pdfViewer.currentScaleValue = "page-width";
    fitScale = pdfViewer.currentScale;
    if (!wasFit) pdfViewer.currentScaleValue = v;
  }, 120);
});

// ------------------------------------------------------------------ indicatore pagina + maniglia

let pillTimer = 0;
function flashPill() {
  if (!pdfDocument) return;
  pill.textContent = `${pdfViewer.currentPageNumber} / ${pdfDocument.numPages}`;
  pill.classList.add("on");
  if (pdfDocument.numPages > 3) thumb.classList.add("on");
  placeThumb();
  clearTimeout(pillTimer);
  pillTimer = setTimeout(() => {
    pill.classList.remove("on");
    if (!thumb.classList.contains("drag")) thumb.classList.remove("on");
  }, 1600);
}
pill.addEventListener("click", () => A.gotoPage());

function placeThumb() {
  const max = container.scrollHeight - container.clientHeight;
  const room = container.clientHeight - thumb.offsetHeight;
  const y = max > 0 ? (container.scrollTop / max) * room : 0;
  thumb.style.transform = `translateY(${y}px)`;
}
container.addEventListener("scroll", () => { flashPill(); }, { passive: true });

thumb.addEventListener("pointerdown", e => {
  thumb.setPointerCapture(e.pointerId);
  thumb.classList.add("drag", "on");
  e.preventDefault();
});
thumb.addEventListener("pointermove", e => {
  if (!thumb.classList.contains("drag")) return;
  const room = container.clientHeight - thumb.offsetHeight;
  const ratio = Math.min(1, Math.max(0, (e.clientY - thumb.offsetHeight / 2) / room));
  container.scrollTop = ratio * (container.scrollHeight - container.clientHeight);
});
const endDrag = () => { thumb.classList.remove("drag"); flashPill(); };
thumb.addEventListener("pointerup", endDrag);
thumb.addEventListener("pointercancel", endDrag);

// ------------------------------------------------------------------ zoom: pinch (come l'app di pdf.js) + doppio tocco

const MIN_SCALE = 0.25, MAX_SCALE = 10;
let unusedFactor = 1;
function accumulateFactor(prev, factor) {
  if (factor === 1) return 1;
  const target = Math.min(MAX_SCALE, Math.max(MIN_SCALE, prev * factor * unusedFactor));
  const newScale = Math.round(target * 100) / 100;
  unusedFactor = target / newScale;
  return newScale / prev;
}

new pdfjsLib.TouchManager({
  container: window,
  isPinchingDisabled: () => !pdfDocument,
  onPinching: (origin, prevDistance, distance, panX, panY) => {
    const f = accumulateFactor(pdfViewer.currentScale, distance / prevDistance);
    pdfViewer.updateScale({ drawingDelay: 300, scaleFactor: f, origin, pan: [panX, panY] });
  },
  onPinchEnd: () => { unusedFactor = 1; },
  onPanning: (dx, dy) => pdfViewer.panBy(dx, dy),
  signal: new AbortController().signal,
});

// doppio tocco: zoom x2.5 nel punto toccato, secondo doppio tocco torna a larghezza pagina
let lastTap = { t: 0, x: 0, y: 0 };
let tapStart = null;
container.addEventListener("touchstart", e => {
  tapStart = e.touches.length === 1 ? { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() } : null;
}, { passive: true });
container.addEventListener("touchmove", e => {
  if (tapStart && e.touches.length === 1) {
    const t = e.touches[0];
    if (Math.hypot(t.clientX - tapStart.x, t.clientY - tapStart.y) > 12) tapStart = null;
  } else tapStart = null;
}, { passive: true });
container.addEventListener("touchend", e => {
  if (!tapStart || e.touches.length || Date.now() - tapStart.t > 300) { tapStart = null; return; }
  const now = Date.now();
  const { x, y } = tapStart;
  tapStart = null;
  if (now - lastTap.t < 320 && Math.hypot(x - lastTap.x, y - lastTap.y) < 40) {
    lastTap = { t: 0, x: 0, y: 0 };
    if (!pdfDocument) return;
    if (e.cancelable) e.preventDefault();
    doubleTap(x, y);
  } else {
    lastTap = { t: now, x, y };
  }
});

function doubleTap(x, y) {
  const cur = pdfViewer.currentScale;
  if (cur < fitScale * 1.6) {
    const target = Math.min(MAX_SCALE, fitScale * 2.5);
    pdfViewer.updateScale({ drawingDelay: -1, scaleFactor: target / cur, origin: [x, y] });
  } else {
    pdfViewer.currentScaleValue = "page-width";
  }
}

// ------------------------------------------------------------------ ricerca

eventBus.on("updatefindmatchescount", ({ matchesCount }) => {
  A.onFind(matchesCount.current, matchesCount.total, -1);
});
eventBus.on("updatefindcontrolstate", ({ state, matchesCount }) => {
  A.onFind(matchesCount?.current || 0, matchesCount?.total || 0, state);
});

// ------------------------------------------------------------------ API per l'app (evaluateJavascript)

window.V = {
  find(query, previous = false, again = false) {
    eventBus.dispatch("find", {
      source: null, type: again ? "again" : "", query,
      caseSensitive: false, entireWord: false, highlightAll: true,
      findPrevious: previous, matchDiacritics: false,
    });
  },
  findClose() { eventBus.dispatch("findbarclose", { source: null }); },
  goto(n) { if (pdfDocument) pdfViewer.currentPageNumber = Math.min(pdfDocument.numPages, Math.max(1, n | 0)); },
  rotate() {
    if (!pdfDocument) return;
    const page = pdfViewer.currentPageNumber;
    pdfViewer.pagesRotation = (pdfViewer.pagesRotation + 90) % 360;
    pdfViewer.currentScaleValue = "page-width";
    fitScale = pdfViewer.currentScale;
    pdfViewer.currentPageNumber = page;
  },
  setDark(on) { document.documentElement.classList.toggle("pdf-dark", !!on); },
  password(pw) { const f = pendingPassword; pendingPassword = null; f?.(pw); },
  passwordCancel() { pendingPassword = null; fail("Password-protected PDF.", "password cancelled"); },
  // per le prove: stato corrente
  state() {
    return {
      pages: pdfDocument?.numPages || 0, page: pdfViewer.currentPageNumber,
      scale: pdfViewer.currentScale, fitScale, rotation: pdfViewer.pagesRotation,
    };
  },
};

open();
