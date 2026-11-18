#!/usr/bin/env bash
# Scarica pdf.js (pdfjs-dist, build "legacy") UNA volta e lo copia negli asset.
# Il risultato e' versionato nel repo: la build Android non usa mai la rete.
# Uso: bash tools/fetch-pdfjs.sh            (versione fissata qui sotto)
set -euo pipefail

VERSION="6.3.289"
# integrity dichiarata dal registry npm per pdfjs-dist-$VERSION.tgz
SHA512_B64="ZHjSVpDa3D6izMq8/04lvkhkATUmL9px6ChPaXc1k6nU2Mrhlg1/7F0bdUqCwUjw3NsPTfPZsMDUU6ZIcRaeQw=="

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/pdfjs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -sSfL "https://registry.npmjs.org/pdfjs-dist/-/pdfjs-dist-$VERSION.tgz" -o "$TMP/p.tgz"
GOT="$(openssl dgst -sha512 -binary "$TMP/p.tgz" | base64 -w0)"
if [ "$GOT" != "$SHA512_B64" ]; then
  echo "ERRORE: integrity non combacia ($GOT)" >&2
  exit 1
fi
tar xzf "$TMP/p.tgz" -C "$TMP"
P="$TMP/package"

rm -rf "$DEST"
mkdir -p "$DEST/build" "$DEST/web" "$DEST/wasm"
# build "legacy": gira anche su WebView non aggiornatissime (minSdk 26)
cp "$P/legacy/build/pdf.min.mjs" "$P/legacy/build/pdf.worker.min.mjs" "$DEST/build/"
cp "$P/legacy/web/pdf_viewer.mjs" "$P/legacy/web/pdf_viewer.css" "$DEST/web/"
cp -r "$P/legacy/web/images" "$DEST/web/"
cp -r "$P/cmaps" "$P/standard_fonts" "$P/iccs" "$DEST/"
# decoder wasm (JPEG2000, JBIG2, profili colore). Niente quickjs: lo scripting PDF resta spento.
cp "$P/wasm/openjpeg.wasm" "$P/wasm/jbig2.wasm" "$P/wasm/qcms_bg.wasm" "$P/wasm"/LICENSE_* "$DEST/wasm/"
cp "$P/LICENSE" "$DEST/LICENSE"
echo "$VERSION" > "$DEST/VERSION"
du -sh "$DEST"
echo "pdf.js $VERSION copiato in $DEST"
