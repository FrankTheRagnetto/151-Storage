#!/usr/bin/env bash
# v5 sull'emulatore: apre ogni campione NUOVO via content:// di MediaStore (Download/v5/) con un MIME
# realistico, e alcuni come application/octet-stream (riconoscimento dai byte). Screenshot in out/v5/.
# Prima: adb push dei campioni (tools/samples/v5 + tools/samples-real) in /sdcard/Download/v5/ + MEDIA_SCANNER_SCAN_FILE.
cd "$(dirname "$0")"
export OUT=$PWD/out/v5
source ./lib.sh
id_of() { $ADB shell content query --uri content://media/external/file --projection _id --where "\"_display_name='$1' AND relative_path='Download/v5/'\"" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1; }
n=0
while read -r f mime <&3; do
  [ -z "$f" ] && continue
  [ -n "$1" ] && [[ "$f" != *"$1"* ]] && continue
  id=$(id_of "$f")
  n=$((n+1))
  tag=$(printf "%02d" $n)
  echo "=== $f ($mime) id=$id"
  WAIT=${WAIT:-5} view "v5-$tag-${f//./-}${mime:+-$(echo $mime | tr '/+' '__' | cut -c1-24)}" "content://media/external/file/$id" "$mime"
done 3<<'LIST'
testMSG.msg application/vnd.ms-outlook
test-outlook2003.msg application/octet-stream
testWORD_3imgs.doc application/msword
testPPT_2imgs.ppt application/vnd.ms-powerpoint
testPages2013.pages application/vnd.apple.pages
testNumbers2013.numbers application/octet-stream
testKeynote2013.key application/vnd.apple.keynote
testXPS_various.xps application/vnd.ms-xpsdocument
alice.mobi application/x-mobipocket-ebook
alice.azw3 application/octet-stream
test.fb2 application/x-fictionbook+xml
codedread.cbz application/vnd.comicbook+zip
fumetto.cbr application/vnd.comicbook-rar
fumetto.cb7 application/octet-stream
archivio.7z application/x-7z-compressed
test-documents.rar application/vnd.rar
archivio.tar.gz application/gzip
archivio.tar.xz application/x-xz
server.log.gz application/gzip
analisi.ipynb application/x-ipynb+json
giro-in-bici.gpx application/gpx+xml
testKMZ.kmz application/vnd.google-earth.kmz
luoghi.geojson application/geo+json
eventi.jsonl application/octet-stream
query.sql application/sql
modifica.diff text/x-diff
articolo.tex application/x-tex
Dockerfile application/octet-stream
Makefile application/octet-stream
build.gradle application/octet-stream
esempio.rs text/x-rust
flusso.drawio application/vnd.jgraph.mxfile
lavagna.excalidraw application/octet-stream
lavagna.excalidraw.md text/markdown
sito.url application/x-mswinurl
pagina.webloc application/octet-stream
raccolta.torrent application/x-bittorrent
fintochat.apk application/vnd.android.package-archive
isrg-root-x1.pem application/x-pem-file
testCERT.der application/x-x509-ca-cert
IT01234567890_00001.xml.p7m application/pkcs7-mime
contratto.pdf.p7m application/octet-stream
IT01234567890_00001.xml text/xml
disegno.svg image/svg+xml
fax-multipagina.tif image/tiff
testTIFF_multipage.tif image/tiff
testPSD2.psd image/vnd.adobe.photoshop
logo-anteprima.eps application/postscript
illustrazione.ai application/illustrator
testDJVU.djvu image/vnd.djvu
foto.jpg image/jpeg
video.mp4 video/mp4
immagine.png application/octet-stream
LIST
echo "aperti: $n"
