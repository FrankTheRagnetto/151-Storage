#!/bin/bash
# 151 Storage, prova vera sull'emulatore (AVD acceso, APK installato): cloud WebDAV finto sul PC con dati
# ANONIMI, collega dall'app, seleziona un mese di foto, carica, e controlla sul server: ogni file arrivato
# identico al byte, nella cartella del mese di SCATTO, nessun provvisorio rimasto, il doppione saltato.
# Uscita != 0 se una cosa sola non torna. Screenshot in out/storage/.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/storage
source ./lib.sh
FIX=/tmp/st-fix; DAV=/tmp/st-dav; PORT=8099; PASS=prova$RANDOM
hidekb() { $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent 4; sleep 0.6; }
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -d $FIX ] || python3 make-storage-fixtures.py $FIX >/dev/null
P="Photos and videos" # la cartella predefinita delle foto e dei video: «Photos and videos/AAAA/MM»
rm -rf $DAV; mkdir -p "$DAV/$P/2026/09" "$DAV/Documents"
cp $FIX/IMG_20260901_*.jpg "$DAV/$P/2026/09/"
pkill -f "rclone serve webdav $DAV" 2>/dev/null
rclone serve webdav $DAV --addr 127.0.0.1:$PORT --user prova --pass "$PASS" >/tmp/st-dav.log 2>&1 &
DAVPID=$!; trap 'kill $DAVPID 2>/dev/null' EXIT; sleep 2
# telefono finto: foto e video in DCIM/Camera e una «WhatsApp»
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
for p in POST_NOTIFICATIONS READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do $ADB shell pm grant $PKG android.permission.$p 2>/dev/null; done
$ADB shell mkdir -p /sdcard/DCIM/Camera /sdcard/Pictures/WhatsApp
for f in $FIX/IMG_2026*.jpg $FIX/VID_*.mp4; do $ADB push "$f" /sdcard/DCIM/Camera/ >/dev/null; done
$ADB push $FIX/IMG-20261005-WA0003.jpg /sdcard/Pictures/WhatsApp/ >/dev/null
$ADB shell 'for f in /sdcard/DCIM/Camera/* /sdcard/Pictures/WhatsApp/*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null; done'
clearlog
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 6
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 2  # il primo avvio dopo pm clear su Android 8 a volte non porta in primo piano
tap_text "WebDAV" >/dev/null; sleep 1
ui_dump; python3 - <<'PY' > /tmp/f.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if 'EditText' in n:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2)
PY
vals=("http://10.0.2.2:$PORT" prova "$PASS"); i=0
while read -u 3 X Y; do hidekb; $ADB shell input tap $X $Y; sleep 0.8
  $ADB shell input keycombination 113 29; $ADB shell input keyevent KEYCODE_DEL; $ADB shell input text "${vals[$i]}"; sleep 0.5; i=$((i+1)); done 3< /tmp/f.txt
hidekb; tap_text Connect >/dev/null; sleep 4; shot 01-collegato
ui_texts | grep -q "text=\"$P\"" && ok "collega e sfoglia il cloud" || ko "collega e sfoglia il cloud"
# password sbagliata non resta scritta in chiaro nelle preferenze
$ADB shell run-as $PKG cat shared_prefs/cloud-secret.xml 2>/dev/null | grep -q "$PASS" && ko "password cifrata" || ok "password cifrata (non in chiaro)"
$ADB shell am start -W -n $PKG/.MainActivity -e place photos >/dev/null; sleep 3
$ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place photos >/dev/null; sleep 4
ui_dump; python3 - <<'PY' > /tmp/c.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if 'content-desc="IMG_' in n or 'content-desc="VID_' in n:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/c.txt; $ADB shell input swipe $X $Y $X $Y 900; sleep 1
# tutti i mesi delle prove (su Android 8 MediaStore raggruppa per EXIF, sul 15 per data di arrivo)
for m in "OCTOBER 2026" "SEPTEMBER 2026" "AUGUST 2026"; do
  for k in 1 2 3; do tap_text "$m" >/dev/null && break; $ADB shell input swipe 540 1700 540 900 300; sleep 0.8; done; sleep 0.6
done
tap_text "Upload to cloud" >/dev/null; sleep 5; shot 02-foglietto
T=$(ui_texts); echo "$T" | grep -q "already there" && ok "il foglietto vede il doppione" || ko "il foglietto vede il doppione"
echo "$T" | grep -q "2026/08" && echo "$T" | grep -qE "08 · 09 · 10" && ok "cartelle per mese di scatto" || ko "cartelle per mese di scatto"
GO=$(echo "$T" | grep -oE 'text="Upload [0-9]+ files?"' | head -1 | sed 's/text="//; s/"$//')
touch /tmp/st-mark; sleep 1; tap_text "$GO" >/dev/null; sleep 15; shot 03-dopo
n=0; bad=0
while IFS= read -r f; do [ -f "$FIX/$(basename "$f")" ] || continue; n=$((n+1)); cmp -s "$f" "$FIX/$(basename "$f")" || bad=$((bad+1)); done < <(find "$DAV/$P" -type f -newer /tmp/st-mark)
[ $n -ge 10 ] && [ $bad -eq 0 ] && ok "$n file arrivati identici" || ko "arrivati $n, diversi $bad"
[ -f "$DAV/$P/2026/09/IMG-20261005-WA0003.jpg" ] && ok "WhatsApp nel mese dell'EXIF, non dell'arrivo" || ko "WhatsApp nel mese giusto"
[ -z "$(find $DAV -name '.*')" ] && ok "nessun provvisorio rimasto" || ko "provvisori rimasti"
[ "$(find $DAV -name 'IMG_20260901_*' | wc -l)" = 1 ] && ok "doppione non ricaricato" || ko "doppione ricaricato"
# «Sul telefono»: tieni premuta la cartella delle foto nel Cloud → la copia privata dell'app deve avere gli stessi file del cloud
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 3
ui_dump; python3 - "$P" <<'PY' > /tmp/c.txt
import re,sys
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if ' text="'+sys.argv[1]+'"' in n:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/c.txt; $ADB shell input swipe $X $Y $X $Y 900; sleep 1; tap_text "Keep on phone" >/dev/null; sleep 12; shot 04-sul-telefono
NR=$(find "$DAV/$P" -type f | wc -l)
NL=$($ADB shell run-as $PKG find "'files/offline/$P'" -type f 2>/dev/null | grep -vc '\.part$')
[ "$NR" -gt 0 ] && [ "$NR" = "$NL" ] && ok "sul telefono: $NL file come sul cloud" || ko "sul telefono: cloud $NR, copia $NL"
$ADB shell content query --uri content://media/external/file --projection _data 2>/dev/null | grep -q "files/offline" && ko "la copia compare in MediaStore" || ok "la copia non la vede nessun'altra app"
# senza rete (server spento) la cartella tenuta si sfoglia lo stesso
kill $DAVPID; sleep 1
tap_text "$P" >/dev/null; sleep 4; shot 05-senza-rete
ui_texts | grep -q 'text="2026"' && ok "senza rete: si sfoglia la copia" || ko "senza rete: si sfoglia la copia"
# un video qualunque in Documents compare in Media: un video vero, fatto conoscere ad Android, e una cartella qualunque accanto
$ADB shell mkdir -p /sdcard/Documents/Film /sdcard/Documents/Altro; $ADB push /tmp/st-thumbfix/VIDEO_J_faststart.mp4 /sdcard/Documents/Film/FILM_DOC.mp4 >/dev/null
$ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Documents/Film/FILM_DOC.mp4 >/dev/null; sleep 2
$ADB shell am start -W -n $PKG/.MainActivity -e place photos >/dev/null; sleep 4; tap_text ALL >/dev/null; sleep 2
ui_texts | grep -q 'FILM_DOC' && ok "il video di Documents compare in Media" || ko "il video di Documents non compare in Media"
crashes | grep -qE "FATAL|AndroidRuntime: FATAL" && ko "crash" || ok "nessun crash"
exit $fail
