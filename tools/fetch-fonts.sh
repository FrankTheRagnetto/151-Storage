#!/usr/bin/env bash
# Caratteri di 151 Viewer (tutti OFL, Google Fonts): Archivo (titoli, etichette, nomi),
# EB Garamond (le frasi dell'app), JetBrains Mono (misure: dimensioni, date, percorsi, estensioni).
# Scarica i variabili da google/fonts, ne taglia le istanze statiche e le riduce all'alfabeto latino.
# Uscita: app/src/main/res/font/*.ttf (app nativa) + app/src/main/assets/viewer/fonts/*.woff2 (pagine web).
# Non serve alla build: i file prodotti sono nel repo. Serve solo per rifarli.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=/tmp/151viewer-fonts-src; VENV=/tmp/151viewer-fontvenv
RES=app/src/main/res/font; WEB=app/src/main/assets/viewer/fonts; LIC=app/src/main/assets/viewer/fonts
mkdir -p "$SRC" "$RES" "$WEB"
[ -x "$VENV/bin/python" ] || { python3 -m venv "$VENV"; "$VENV/bin/pip" install -q fonttools brotli; }
get() { # get <cartella google/fonts> <file>
  local out="$SRC/$1-$(echo "$2" | tr -d '[]')"
  [ -s "$out" ] && return 0
  local url="https://raw.githubusercontent.com/google/fonts/main/ofl/$1/$(echo "$2" | sed 's/\[/%5B/;s/\]/%5D/')"
  curl -sSfL -o "$out.part" "$url"
  [ -s "$out.part" ] || { echo "download vuoto: $url" >&2; exit 1; }
  mv "$out.part" "$out"
}
get archivo 'Archivo[wdth,wght].ttf'; get archivo OFL.txt
get ebgaramond 'EBGaramond[wght].ttf'; get ebgaramond OFL.txt
get jetbrainsmono 'JetBrainsMono[wght].ttf'; get jetbrainsmono OFL.txt
UNI="U+0020-007E,U+00A0-017F,U+2010-2027,U+2030-203A,U+20AC,U+2190-2193,U+2212,U+00D7,U+2715"
inst() { # inst <sorgente> <nome> <assi...>
  local src="$1" name="$2"; shift 2
  "$VENV/bin/fonttools" varLib.instancer -q -o "$SRC/$name.full.ttf" "$src" "$@"
  "$VENV/bin/pyftsubset" "$SRC/$name.full.ttf" --unicodes="$UNI" --layout-features='kern,liga,calt,tnum,lnum,onum' \
    --output-file="$RES/$name.ttf.part" && mv "$RES/$name.ttf.part" "$RES/$name.ttf"
  "$VENV/bin/pyftsubset" "$SRC/$name.full.ttf" --unicodes="$UNI" --layout-features='kern,liga,calt,tnum,lnum,onum' \
    --flavor=woff2 --output-file="$WEB/$name.woff2.part" && mv "$WEB/$name.woff2.part" "$WEB/$name.woff2"
}
A="$SRC/archivo-Archivowdth,wght.ttf"; G="$SRC/ebgaramond-EBGaramondwght.ttf"; M="$SRC/jetbrainsmono-JetBrainsMonowght.ttf"
inst "$A" archivo_regular wdth=100 wght=400
inst "$A" archivo_medium wdth=100 wght=500
inst "$A" archivo_semibold wdth=100 wght=600
inst "$A" archivo_bold wdth=100 wght=700
inst "$A" archivo_condensed_bold wdth=75 wght=700
inst "$G" garamond_regular wght=400
inst "$G" garamond_medium wght=500
inst "$M" mono_regular wght=400
inst "$M" mono_medium wght=500
cp "$SRC/archivo-OFL.txt" "$LIC/OFL-Archivo.txt"
cp "$SRC/ebgaramond-OFL.txt" "$LIC/OFL-EBGaramond.txt"
cp "$SRC/jetbrainsmono-OFL.txt" "$LIC/OFL-JetBrainsMono.txt"
ls -la "$RES" "$WEB"
