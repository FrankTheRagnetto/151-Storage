#!/bin/bash
# v6 (aspetto «il manuale»): libreria, linguette, dialoghi, visualizzatore, foglietti ed errore, chiaro e scuro.
# Uso: tools/emu-test/run-v6.sh light|dark  → screenshot in out/v6/ (da guardare). Campioni già in /sdcard/Download.
cd "$(dirname "$0")"
export OUT=$PWD/out/v6
source ./lib.sh
id_of() { $ADB shell content query --uri content://media/external/file --projection _id --where "\"_display_name='$1'\"" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1; }
mode=${1:-light}
[ "$mode" = dark ] && $ADB shell cmd uimode night yes || $ADB shell cmd uimode night no
sleep 1
$ADB shell am force-stop $PKG
$ADB shell am start -W -n $PKG/.MainActivity | grep -E Status
sleep 3; shot "$mode-01-libreria"
tap_text "Documenti" >/dev/null
sleep 1
ui_dump; python3 - <<'PY' > /tmp/chip.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    d=re.search(r'content-desc="([^"]*)"',n).group(1)
    if d=='PDF':
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/chip.txt; [ -n "$X" ] && $ADB shell input tap $X $Y
sleep 1.5; shot "$mode-02-libreria-pdf"
tap_text "Recenti" >/dev/null; sleep 1.5; shot "$mode-03-recenti"
tap_text "Documenti" >/dev/null; sleep 1
# selezione (v6.1): tieni premuto = seleziona; tocco = aggiungi; casella tenuta premuta = tutti / nessuno
$ADB shell input swipe 540 960 540 960 900; sleep 1.2; shot "$mode-04-selezione-uno"
$ADB shell input tap 540 1130; sleep 1; shot "$mode-05-selezione-due"
ui_dump; python3 - <<'PY' > /tmp/chk.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if 'checkbox' in n.lower() or 'CheckBox' in n:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/chk.txt; $ADB shell input swipe $X $Y $X $Y 900; sleep 1.2; shot "$mode-05b-selezione-tutti"
$ADB shell input swipe $X $Y $X $Y 900; sleep 1.2; shot "$mode-05c-selezione-nessuno"
tap_text "More options" >/dev/null || tap_text "Altre opzioni" >/dev/null; sleep 1; shot "$mode-06-menu"
tap_text "Cestino" >/dev/null; sleep 2; shot "$mode-07-cestino"
$ADB shell input keyevent 4; sleep 0.8
tap_text "Ordina" >/dev/null; sleep 1; shot "$mode-08-ordina"
$ADB shell input keyevent 4; sleep 0.8
tap_text "Documenti" >/dev/null; sleep 1
for f in documento.pdf:application/pdf lettera.docx:application/vnd.openxmlformats-officedocument.wordprocessingml.document lavagna.excalidraw.md:text/markdown archivio.7z:application/x-7z-compressed testDJVU.djvu:image/vnd.djvu foto.jpg:image/jpeg fintochat.apk:application/vnd.android.package-archive query.sql:application/sql testWORD_3imgs.doc:application/msword; do
  n=${f%%:*}; t=${f#*:}; id=$(id_of "$n")
  WAIT=4 view "$mode-1x-${n//./-}" "content://media/external/file/$id" "$t" >/dev/null
done
WAIT=3 view "$mode-20-errore-lettura" "file:///sdcard/Download/non-esiste.pdf" "application/pdf" >/dev/null
if [ "$mode" = light ]; then
  $ADB shell appops set $PKG MANAGE_EXTERNAL_STORAGE deny; $ADB shell am force-stop $PKG
  $ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 2.5; shot "$mode-09-dialogo-accesso"
  $ADB shell input keyevent 4; sleep 1; shot "$mode-10-invito"
  $ADB shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
fi
$ADB shell am force-stop $PKG
