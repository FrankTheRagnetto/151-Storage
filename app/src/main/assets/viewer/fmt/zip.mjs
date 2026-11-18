// ZIP: elenco dei file (per cartella) e apertura di un file interno con il visualizzatore giusto.
import { showListing } from "./listing.mjs";

export async function render(ctx) {
  const { zip, util, apk } = ctx;
  const files = Object.values(zip.files).filter(f => !f.dir);
  const entries = files.map(f => ({ name: f.name, size: f._data && f._data.uncompressedSize, read: () => f.async("uint8array") }));
  const odd = files.some(f => f.options && f.options.encrypted || (f._data && f._data.compression == null));
  if (apk) util.A.onFormat("Android package (contents)");
  showListing(ctx, entries, { note: odd ? "Some files are encrypted or compressed in an unsupported way" : null });
}
