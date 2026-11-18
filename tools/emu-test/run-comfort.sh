#!/bin/bash
# 151 Storage, le comodità: On this day / Random / Duplicates dalla lista che il telefono fa da solo, ricerca nel
# cloud, rinomina nel cloud, rinomina e copia sul telefono, preferiti, video dentro il visore. Dati anonimi, cloud finto.
# Uscita != 0 se una cosa sola non torna. Screenshot in out/comfort/.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/comfort
source ./lib.sh
FIX=/tmp/st-fix; DAV=/tmp/st-dav2; PORT=8098; PASS=prova$RANDOM
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
hidekb() { $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent 4; sleep 0.6; }
xy() { ui_dump; python3 - "$1" > /tmp/xy.txt <<'PY'
import re,sys
x=open('/tmp/ui.xml').read(); w=sys.argv[1]
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if re.search(r' (text|content-desc)="'+re.escape(w)+'"',n):
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/xy.txt; }
# tocca una scritta, scorrendo in giù se non è sullo schermo
tapS() { for k in 1 2 3 4; do tap_text "$1" >/dev/null && return 0; $ADB shell input swipe 540 1700 540 900 300; sleep 0.8; done; return 1; }
clearfield() { $ADB shell input keyevent KEYCODE_MOVE_END; for k in $(seq 1 30); do $ADB shell input keyevent KEYCODE_DEL; done; }
hold() { for k in 1 2 3; do xy "$1"; [ "${X:-NONE}" != NONE ] && [ -n "${X:-}" ] && break; $ADB shell input swipe 540 1700 540 900 300; sleep 0.8; done; [ -n "${X:-}" ] && $ADB shell input swipe $X $Y $X $Y 900; sleep 1; }
[ -d $FIX ] || python3 make-storage-fixtures.py $FIX >/dev/null
rm -rf $DAV; M="$DAV/Photos/2026/Phone Photos/10 - October"; mkdir -p "$M" "$DAV/Photos/2025"
cp $FIX/IMG_202610*.jpg "$M/"; cp $FIX/IMG_20261002_123000.jpg "$DAV/Photos/2025/copia.jpg"
MMDD=$(date +%m-%d)
# «Oggi negli anni» vero (09/10: la lista la fa il telefono dai file, non si può più dichiarare una data finta):
# una foto senza EXIF col giorno di oggi del 2025 nel nome
python3 -c "from PIL import Image; Image.new('RGB',(640,480),(30,90,200)).save('$DAV/Photos/2025/IMG_2025$(date +%m%d)_120000.jpg', quality=85)"
pkill -f "rclone serve webdav $DAV" 2>/dev/null
rclone serve webdav $DAV --addr 127.0.0.1:$PORT --user prova --pass "$PASS" >/tmp/st-dav2.log 2>&1 &
DAVPID=$!; trap 'kill $DAVPID 2>/dev/null' EXIT; sleep 2
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
for p in POST_NOTIFICATIONS READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do $ADB shell pm grant $PKG android.permission.$p 2>/dev/null; done
$ADB shell rm -rf /sdcard/Prova; $ADB shell mkdir -p /sdcard/Prova /sdcard/DCIM/Camera
$ADB shell "echo uno > /sdcard/Prova/a.txt; echo due > /sdcard/Prova/b.txt"
$ADB push $FIX/VID_20261003_1.mp4 /sdcard/DCIM/Camera/ >/dev/null; $ADB push $FIX/IMG_20261006_143000.jpg /sdcard/DCIM/Camera/ >/dev/null
$ADB shell 'for f in /sdcard/DCIM/Camera/*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null; done'
clearlog
# collega
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 6
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 2
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
hidekb; tap_text Connect >/dev/null; sleep 2
# la lista del telefono (On this day, Random, Duplicates) si fa da sola dopo il collegamento
for i in $(seq 1 60); do $ADB shell run-as $PKG cat files/index-phone/meta.json 2>/dev/null | grep -q dupsAt && break; sleep 2; done
$ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 4; shot 01-radice
T=$(ui_texts)
for w in "On this day" "Random" "Random day" "Duplicates"; do echo "$T" | grep -q "text=\"$w\"" && ok "radice: $w" || ko "radice: $w"; done
tap_text "On this day" >/dev/null; sleep 4; shot 02-oggi
T2=$(ui_texts); echo "$T2" | grep -q 'text="2025"' && echo "$T2" | grep -q 'text="1 item"' && ok "Oggi negli anni: anno per anno" || ko "Oggi negli anni"
$ADB shell input keyevent 4; sleep 2; tap_text "Duplicates" >/dev/null; sleep 4; shot 03-doppioni
ui_texts | grep -qi '2 copies' && ok "Doppioni" || ko "Doppioni"
$ADB shell input keyevent 4; sleep 2
# ricerca in tutto il cloud (dall'indice)
tap_text "Search" >/dev/null; sleep 1; $ADB shell input text "copia"; sleep 4; shot 04-cerca
ui_texts | grep -q 'text="copia.jpg"' && ok "ricerca in tutto il cloud" || ko "ricerca in tutto il cloud"
hidekb; tap_text "Collapse" >/dev/null 2>&1 || $ADB shell input keyevent 4; sleep 2
# rinomina nel cloud: Photos › 2025 › copia.jpg → doppia.jpg
tap_text Photos >/dev/null; sleep 2; tap_text 2025 >/dev/null; sleep 2; hold "copia.jpg"; tap_text More >/dev/null; sleep 1; tap_text Rename >/dev/null; sleep 1
clearfield; $ADB shell input text "doppia.jpg"; sleep 0.5; tap_text OK >/dev/null || tap_text Ok >/dev/null; sleep 4
[ -f "$DAV/Photos/2025/doppia.jpg" ] && [ ! -f "$DAV/Photos/2025/copia.jpg" ] && ok "rinomina nel cloud" || ko "rinomina nel cloud"
# (telefono: rinomina, copia, preferiti dal posto Phone — tolto il 14/10, l'utente)
# video dentro il visore, coi comandi
$ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place photos >/dev/null; sleep 4
xy "VID_20261003_1.mp4"; $ADB shell input tap $X $Y; sleep 3; tap_text Play >/dev/null; sleep 4; shot 05-video
# mentre un video scorre uiautomator e dumpsys non sono affidabili: si guarda che il lettore di Android l'abbia aperto
# per la nostra app (richiesta dell'audio + file del video aperto) — lo schermo si guarda nello screenshot 05-video
# Android 8 non scrive il pacchetto ma lo uid: «requestAudioFocus() from uid/pid <uid>/…»
UID_APP=$($ADB shell dumpsys package $PKG | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
$ADB logcat -d | grep -qE "requestAudioFocus.*(callingPack=$PKG|from uid/pid $UID_APP/)" && $ADB logcat -d | grep -q "VID_20261003_1.mp4" && ok "video coi comandi nel visore" || ko "video coi comandi nel visore"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
