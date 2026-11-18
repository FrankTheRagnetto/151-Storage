#!/usr/bin/env bash
# v3 sull'emulatore: apre ogni campione via content:// di MediaStore (Download/v3/) con il MIME vero
# e anche con application/octet-stream (riconoscimento dai byte). Screenshot in tools/emu-test/out/v3/.
# Prima: adb push tools/samples/. /sdcard/Download/v3/ + MEDIA_SCANNER_SCAN_FILE per ogni file.
cd "$(dirname "$0")"
export OUT=$PWD/out/v3
source ./lib.sh
id_of() { $ADB shell content query --uri content://media/external/file --projection _id --where "\"_display_name='$1' AND relative_path='Download/v3/'\"" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1; }
while read -r f mime <&3; do
  [ -z "$f" ] && continue
  [ -n "$1" ] && [[ "$f" != *"$1"* ]] && continue
  id=$(id_of "$f")
  echo "=== $f ($mime) id=$id"
  WAIT=${WAIT:-5} view "v3-${f//./-}" "content://media/external/file/$id" "$mime"
done 3<<'EOF'
nota-obsidian.md text/markdown
obsidian-syntax.md text/markdown
pagina.html text/html
documento.docx application/vnd.openxmlformats-officedocument.wordprocessingml.document
documento.doc application/msword
foglio.xlsx application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
foglio.xls application/vnd.ms-excel
foglio.ods application/vnd.oasis.opendocument.spreadsheet
tabella.csv text/comma-separated-values
presentazione.pptx application/vnd.openxmlformats-officedocument.presentationml.presentation
presentazione.odp application/vnd.oasis.opendocument.presentation
presentazione.ppt application/vnd.ms-powerpoint
documento.odt application/vnd.oasis.opendocument.text
documento.rtf text/rtf
libro.epub application/epub+zip
archivio.zip application/zip
email.eml message/rfc822
pagina.mhtml application/octet-stream
contatto.vcf text/x-vcard
evento.ics text/calendar
sottotitoli.srt application/x-subrip
app.log application/octet-stream
config.yaml application/yaml
config.ini application/octet-stream
config.toml application/octet-stream
carattere.ttf font/ttf
suono.mp3 audio/mpeg
video.mp4 video/mp4
documento.docx application/octet-stream
EOF
