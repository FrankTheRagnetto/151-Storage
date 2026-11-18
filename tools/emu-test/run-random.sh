#!/bin/bash
# 151 Storage 0.10.0: «Random» istantaneo (coi dati mobili: «lente a comparire»).
# Cloud finto WebDAV con 253 foto e video ANONIMI (240 senza miniatura della fotocamera: ognuna va scaricata
# intera), Random dalla lista che il telefono fa da solo, cloud rallentato come i dati mobili (300 KiB/s per collegamento: la rete dell'emulatore verso il PC non si lascia rallentare).
# Si tocca Random (e «More at random») e 1,5 secondi dopo si guarda lo schermo: ogni cella deve avere la sua anteprima
# (sopra rosso, sotto blu), sia MENTRE il giro di tutto il cloud lavora sia a giro finito.
# Uscita != 0 se una cosa sola non torna. Screenshot in out/random/.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/random
source ./lib.sh
FIX=/tmp/st-thumbfix; DAV=/tmp/st-randdav; PORT=8097; PASS=prova$RANDOM
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
rm -rf $DAV; mkdir -p "$DAV/Photos/Prove anteprime" "$DAV/Photos/Massa"; cp $FIX/* "$DAV/Photos/Prove anteprime/"
for i in $(seq -w 1 240); do cp $FIX/PHOTO_C_no_exif.jpg "$DAV/Photos/Massa/MASSA_$i.jpg"; done
bash apache-dav.sh start $DAV $PORT "$PASS" 300 # 300 KiB/s per collegamento: i dati mobili in palestra
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
count() { $ADB shell run-as $PKG ls files/cloud-thumbs 2>/dev/null | grep -c '\.jpg$'; }
running() { $ADB shell dumpsys notification --noredact | grep -q "Making cloud previews"; }
clearlog
bash connect.sh "http://10.0.2.2:$PORT" prova "$PASS"

# guarda lo schermo: quante celle di foto ci sono e quante hanno già l'anteprima
cells() {
  $ADB exec-out screencap -p > "$OUT/$1.png"
  ui_dump
  python3 - "$OUT/$1.png" <<'PY'
import re, sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
x = open('/tmp/ui.xml').read()
tot = good = 0
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    if not re.search(r'\.(jpg|jpeg|png|heic|mp4|mov)$', d, re.I): continue
    b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1))))
    if b[3] - b[1] < 100: continue
    tot += 1
    cx = (b[0] + b[2]) // 2; t = im.getpixel((cx, b[1] + (b[3] - b[1]) // 4)); u = im.getpixel((cx, b[3] - (b[3] - b[1]) // 4))
    if t[0] > 120 and t[2] < 130 and u[2] > 120 and u[0] < 130: good += 1
print(good, tot)
PY
}
try_random() { # $1 nome, $2 = "first" (dalla radice) o "again"
  if [ "$2" = first ]; then
    $ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 4
    tap_text Random >/dev/null
  else tap_text "More at random" >/dev/null; fi
  sleep 1.5
  read G T < <(cells "$1")
  [ "$T" -ge 12 ] && [ "$G" -eq "$T" ] && ok "$1: $G anteprime su $T celle dopo 1,5 s" || ko "$1: $G anteprime su $T celle dopo 1,5 s"
}

# A — MENTRE il giro lavora (240 foto da scaricare intere coi dati: minuti)
for i in $(seq 1 90); do n=$(count); [ "$n" -ge 20 ] && break; sleep 1; done
running && ok "il giro è in corso ($n anteprime fatte)" || ko "il giro non è in corso ($n anteprime)"
try_random "giro-in-corso-1" first
try_random "giro-in-corso-2" again
try_random "giro-in-corso-3" again
running && ok "il giro è ancora in corso dopo le prove ($(count) anteprime)" || echo "     (il giro è finito durante le prove: la parte A vale lo stesso)"
# B — a giro finito (fino a 12 minuti)
for i in $(seq 1 360); do $ADB shell dumpsys notification --noredact | grep -q "All cloud previews" && break; sleep 2; done
n=$(count); [ "$n" -ge 252 ] && ok "giro finito: $n anteprime" || ko "giro non finito: $n anteprime"
try_random "giro-finito-1" first
try_random "giro-finito-2" again
try_random "giro-finito-3" again
alive | grep -q vivo && ok "app viva" || ko "app morta"
crashes | grep -q FATAL && { ko "crash"; crashes | head -20; }
exit $fail
