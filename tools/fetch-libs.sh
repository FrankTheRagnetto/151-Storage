#!/usr/bin/env bash
# Scarica UNA volta le librerie JS della v3 e le copia in app/src/main/assets/lib/<nome>/.
# Il risultato e' versionato nel repo: la build Android non usa mai la rete.
# Ogni pacchetto npm e' controllato con l'integrity sha512 dichiarata dal registry.
# SheetJS non sta su npm (la versione su npm e' ferma e vulnerabile): si prende dal CDN ufficiale
# cdn.sheetjs.com e lo sha256 qui sotto e' quello misurato al primo download (30/09/2026).
# Uso: bash tools/fetch-libs.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/lib"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# nome | versione | url | sha512 (base64, npm) o sha256:<hex>
LIBS=(
"markdown-it|15.0.2|https://registry.npmjs.org/markdown-it/-/markdown-it-15.0.2.tgz|q4IGxMv56jCqT4OCRCADBoDP3LO4MhmTXjFbphHPXs4g3j9Xg5RDnxqN8IF/3vIWEU+VCnUq+7JUg/cfy2E6Qw=="
"markdown-it-footnote|4.0.0|https://registry.npmjs.org/markdown-it-footnote/-/markdown-it-footnote-4.0.0.tgz|WYJ7urf+khJYl3DqofQpYfEYkZKbmXmwxQV8c8mO/hGIhgZ1wOe7R4HLFNwqx7TjILbnC98fuyeSsin19JdFcQ=="
"markdown-it-mark|4.0.0|https://registry.npmjs.org/markdown-it-mark/-/markdown-it-mark-4.0.0.tgz|YLhzaOsU9THO/cal0lUjfMjrqSMPjjyjChYM7oyj4DnyaXEzA8gnW6cVJeyCrCVeyesrY2PlEdUYJSPFYL4Nkg=="
"highlight|11.12.0|https://registry.npmjs.org/@highlightjs/cdn-assets/-/cdn-assets-11.12.0.tgz|KvOKXODaiFmId9xaq3xc5xCL66wVLUuOngDbO9B/kewbFTqdGbn2nJxNhN3H5R1cgDTVj6R8vH0zgiNDEGjpDw=="
"katex|0.18.9|https://registry.npmjs.org/katex/-/katex-0.18.9.tgz|8ad9RyoKsb/g8/yLFE+KAlP+DhbCTRUNi/V9XGsxn0R+trJJltNwzcDNo0q/DEkOy5fQUQTAQyCXCYSE+OakTQ=="
"dompurify|3.4.16|https://registry.npmjs.org/dompurify/-/dompurify-3.4.16.tgz|sqo+pNp3qRhCIpbgRi1y8Tgk27Bo2Ry7w0dC1NBeNTdZChWjz9Xb/KOoZbRP/R6pQZ80Qw8YhXw13hWWBbMRnQ=="
"mermaid|12.0.0|https://registry.npmjs.org/mermaid/-/mermaid-12.0.0.tgz|/wQXC9iBxoGV8p3erbvaXs9h77VyLDBH6GdayVjj3hEcSQhFU4N1WUhUppotCEqlIxI2pRMwjwBSwTB1MfZBgQ=="
"jszip|3.10.2|https://registry.npmjs.org/jszip/-/jszip-3.10.2.tgz|3l+rb15IOWtUhU0H5MFqES/T6Kh7abYwjosBey/vD6hDt8zoEffkSC5Ws5SGtgVw3gBx2NEbhTeSW1+kWkpyTQ=="
"docx-preview|0.4.1|https://registry.npmjs.org/docx-preview/-/docx-preview-0.4.1.tgz|Dv4g+LeE0swHpPvNC0lAT0YsR5Zq5pAxw7d3vHQ8EChBLgIrqqe9VPL5nVt2HBJap8bEZbXUI0TJob7kBeNU9w=="
"mammoth|1.13.0|https://registry.npmjs.org/mammoth/-/mammoth-1.13.0.tgz|2cawcYFcP7ISfXbBcL9lmVgTNWo88v/52ND1gnywHtVT/pX0//puPnf0iMPV9ZgmuvNOE+zkIhDgZ9M2c2dDDg=="
"xlsx|0.20.3|https://cdn.sheetjs.com/xlsx-0.20.3/xlsx-0.20.3.tgz|sha256:8dc73fc3b00203e72d176e85b50938627c7b086e607c682e8d3c22c02bb99fe8"
# v5
"utif|4.1.0|https://registry.npmjs.org/utif2/-/utif2-4.1.0.tgz|+oknB9FHrJ7oW7A2WZYajOcv4FcDR4CfoGB0dPNfxbi4GO05RRnFmt5oa23+9w32EanrYcSJWspUiJkLMs+37w=="
"fflate|0.8.2|https://registry.npmjs.org/fflate/-/fflate-0.8.2.tgz|cPJU47OaAoCbg0pBvzsgpTPhmhqI5eJjh/JIu8tPj5q+T7iLvW/JAYUqmE7KOB4R1ZyEhzBaIQpQpardBF5z8A=="
"libarchive|2.0.2|https://registry.npmjs.org/libarchive.js/-/libarchive.js-2.0.2.tgz|JHb+P4suNSjvz/dMdRgOe7JAxluXJeialzSFkKHU5y0ZK+m175drPOaIYW6I9WXSDcPcQ13eCUgMnpgY0ggmoQ=="
)

rm -rf "$DEST"
mkdir -p "$DEST"
for row in "${LIBS[@]}"; do
  IFS='|' read -r NAME VER URL SUM <<< "$row"
  curl -sSfL "$URL" -o "$TMP/$NAME.tgz"
  if [[ "$SUM" == sha256:* ]]; then
    GOT="sha256:$(sha256sum "$TMP/$NAME.tgz" | cut -d' ' -f1)"
  else
    GOT="$(openssl dgst -sha512 -binary "$TMP/$NAME.tgz" | base64 -w0)"
  fi
  if [ "$GOT" != "$SUM" ]; then echo "ERRORE: integrity di $NAME non combacia ($GOT)" >&2; exit 1; fi
  mkdir -p "$TMP/$NAME" && tar xzf "$TMP/$NAME.tgz" -C "$TMP/$NAME"
  P="$TMP/$NAME/package"; D="$DEST/$NAME"; mkdir -p "$D"
  case "$NAME" in
    markdown-it)          cp "$P/dist/browser/markdown-it.umd.min.js" "$D/" ;;
    markdown-it-footnote) cp "$P/dist/markdown-it-footnote.min.js" "$D/" ;;
    markdown-it-mark)     cp "$P/dist/markdown-it-mark.min.js" "$D/" ;;
    highlight)
      cp "$P/highlight.min.js" "$D/"
      cp "$P/styles/github.min.css" "$P/styles/github-dark.min.css" "$D/"
      # lingue in piu' rispetto al pacchetto "common"
      for l in dockerfile powershell dos latex dart scala groovy nginx apache cmake properties \
        gradle asciidoc freedesktop haskell elixir erlang clojure fsharp ocaml julia matlab fortran delphi protobuf \
        glsl verilog vhdl x86asm armasm tcl awk elm crystal d coffeescript nix prolog lisp scheme vbscript applescript \
        autohotkey arduino llvm handlebars twig pgsql smali vim nsis puppet http nim gcode mathematica sas stata; do
        [ -f "$P/languages/$l.min.js" ] && cat "$P/languages/$l.min.js" >> "$D/extra-languages.min.js" && echo >> "$D/extra-languages.min.js"
      done ;;
    katex)
      cp "$P/dist/katex.min.js" "$P/dist/katex.min.css" "$D/"
      mkdir -p "$D/fonts" && cp "$P/dist/fonts/"*.woff2 "$D/fonts/"   # solo woff2: tutte le WebView lo leggono
      ;;
    dompurify)    cp "$P/dist/purify.min.js" "$D/" ;;
    mermaid)      cp "$P/dist/mermaid.min.js" "$D/" ;;
    jszip)        cp "$P/dist/jszip.min.js" "$D/" ;;
    docx-preview) cp "$P/dist/docx-preview.min.js" "$D/" ;;
    mammoth)      cp "$P/mammoth.browser.min.js" "$D/" ;;
    xlsx)         cp "$P/dist/xlsx.full.min.js" "$D/" ;;
    utif)         cp "$P/UTIF.js" "$D/" ;;                       # TIFF (multipagina, fax G3/G4, LZW…)
    fflate)       cp "$P/umd/index.js" "$D/fflate.umd.js" ;;      # inflate sincrono per UTIF (deflate)
    libarchive)   cp "$P/dist/libarchive.js" "$P/dist/worker-bundle.js" "$P/dist/libarchive.wasm" "$D/" ;;  # 7z, RAR4/5, tar.*, bz2, xz
  esac
  for L in LICENSE LICENSE.md LICENSE.txt LICENSE.markdown license; do [ -f "$P/$L" ] && cp "$P/$L" "$D/LICENSE" && break; done
  [ -f "$D/LICENSE" ] || { echo "ERRORE: $NAME senza LICENSE" >&2; exit 1; }
  echo "$VER" > "$D/VERSION"
  echo "ok  $NAME $VER"
done
du -sh "$DEST"/* "$DEST"
