#!/bin/bash
# 151 Storage, anteprime del cloud fatte DAL TELEFONO (0.7.0; 0.8.0 il giro di tutto il cloud). Emulatore acceso con l'APK installato.
# Cloud finto WebDAV (Apache, apache-dav.sh) con foto e video ANONIMI (make-thumb-fixtures.py): i casi
# che nella 0.6.0 restavano senza anteprima. Si controlla:
#  - appena collegato, senza aprire niente: un'anteprima per ogni foto e video di TUTTO il cloud (HEIC solo da Android 9), girata giusta (sopra rosso, sotto blu);
#  - nel cloud NIENTE scritto (nessun PUT/DELETE/MOVE/COPY/MKCOL/PROPPATCH/LOCK nel registro del server);
#  - la JPEG con la miniatura della fotocamera NON scaricata intera (solo l'inizio);
#  - riaperta l'app, le anteprime vengono dal telefono: nessun file scaricato di nuovo;
#  - un file nuovo nel cloud ha la sua anteprima alla riapertura, quelle dei file spariti si buttano.
# Uscita != 0 se una cosa sola non torna. Screenshot in out/thumbs/.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/thumbs
source ./lib.sh
FIX=/tmp/st-thumbfix; DAV=/tmp/st-thumbdav; PORT=8098; PASS=prova$RANDOM
DIR="Prove anteprime"
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
rm -rf $FIX; python3 make-thumb-fixtures.py $FIX >/dev/null || { echo "FAIL file di prova"; exit 1; }
rm -rf $DAV; mkdir -p "$DAV/Photos/$DIR" "$DAV/Photos/2025/Mare" "$DAV/Archivio/Video vecchi"; cp $FIX/* "$DAV/Photos/$DIR/"
# cartelle che nessuno apre: il giro deve farle lo stesso
cp $FIX/PHOTO_A_exif_ok.jpg "$DAV/Photos/2025/Mare/MARE_1.jpg"; cp $FIX/PHOTO_C_no_exif.jpg "$DAV/Photos/2025/Mare/MARE_2.jpg"
cp $FIX/VIDEO_J_faststart.mp4 "$DAV/Archivio/Video vecchi/VECCHIO.mp4"
bash apache-dav.sh start $DAV $PORT "$PASS"; trap 'bash apache-dav.sh stop $PORT' EXIT
SDK=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
want=16; [ "$SDK" -lt 28 ] && want=15 # (sweep: 13 + 3 in cartelle mai aperte) # Android 8 non sa leggere le HEIC: lì resta l'icona, come in Telefono
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
clearlog
bash connect.sh "http://10.0.2.2:$PORT" prova "$PASS"
open_dir() {
  $ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 4
  tap_text Photos >/dev/null && sleep 2 && tap_text "$DIR" >/dev/null
}
count() { $ADB shell run-as $PKG ls files/cloud-thumbs 2>/dev/null | grep -c '\.jpg$'; }
# appena collegato, SENZA aprire nessuna cartella: il giro fa tutto il cloud (fino a 3 minuti: l'emulatore è lento)
for i in $(seq 1 90); do n=$(count); [ "$n" -ge $want ] && break; sleep 2; done
[ "$n" -eq $want ] && ok "giro di tutto il cloud, senza aprire cartelle: $n anteprime su $want (Android $SDK)" || ko "giro: $n anteprime su $want (Android $SDK)"
$ADB shell dumpsys notification --noredact | grep -q "All cloud previews" && ok "notifica: tutte le anteprime sul telefono" || ko "notifica di fine giro assente"
open_dir || ko "cartella di prova non trovata nel Cloud"
sleep 4; shot 01-griglia
# girate giuste e in piedi quelle in piedi
rm -rf /tmp/st-thumbs; mkdir -p /tmp/st-thumbs
for f in $($ADB shell run-as $PKG ls files/cloud-thumbs | tr -d '\r' | grep '\.jpg$'); do
  $ADB exec-out run-as $PKG cat "files/cloud-thumbs/$f" > /tmp/st-thumbs/$f
done
python3 - <<'PY' > /tmp/st-thumbs/verdetto.txt
import os
from PIL import Image
bad = 0; tall = 0
for f in sorted(os.listdir('/tmp/st-thumbs')):
    if not f.endswith('.jpg'): continue
    im = Image.open('/tmp/st-thumbs/' + f).convert('RGB'); w, h = im.size
    t = im.getpixel((w // 2, h // 4)); b = im.getpixel((w // 2, 3 * h // 4))
    good = t[0] > 120 and t[2] < 130 and b[2] > 120 and b[0] < 130
    if h > w: tall += 1
    if not good or not (480 <= max(w, h) <= 512): bad += 1; print("STORTA O PICCOLA", f, w, h, t, b)
print("storte", bad); print("in_piedi", tall)
PY
[ "$n" -gt 0 ] && grep -q "^storte 0" /tmp/st-thumbs/verdetto.txt && ok "tutte girate giuste (sopra rosso, sotto blu) e nitide (lato 480-512 px)" || { ko "anteprime storte"; cat /tmp/st-thumbs/verdetto.txt; }
grep -q "^in_piedi 3" /tmp/st-thumbs/verdetto.txt && ok "le 3 in piedi (2 foto, 1 video) sono in piedi" || { ko "in piedi: $(grep in_piedi /tmp/st-thumbs/verdetto.txt)"; }
LOG=$(docker logs st-apache-$PORT 2>/dev/null)
echo "$LOG" | grep -qE '^(PUT|DELETE|MOVE|COPY|MKCOL|PROPPATCH|LOCK|UNLOCK) ' && ko "il telefono ha scritto nel cloud: $(echo "$LOG" | grep -E '^(PUT|DELETE|MOVE|COPY|MKCOL|PROPPATCH|LOCK)' | head -3)" || ok "nel cloud non si è scritto niente"
full=$(stat -c %s "$FIX/PHOTO_A_exif_ok.jpg")
# (0.9.8) PHOTO_A ha un doppione (MARE_1): su un cloud senza impronta del server il controllo doppioni legge una volta
# 64 KB dall'inizio e 64 KB dalla fine (Range 0-65535 e la coda). Quelle letture si contano a parte: qui l'anteprima.
dupr=$(( full - 65536 ))
got=$(echo "$LOG" | grep "GET .*PHOTO_A_exif_ok.jpg" | grep -vE "\"bytes=(0-65535|$dupr-$((full - 1)))\"" | awk '{s+=$NF} END {print s+0}')
dgot=$(echo "$LOG" | grep "GET .*PHOTO_A_exif_ok.jpg" | grep -E "\"bytes=(0-65535|$dupr-$((full - 1)))\"" | awk '{s+=$NF} END {print s+0}')
[ "$got" -gt 0 ] && [ "$got" -le $((262144 + 2048)) ] && ok "foto con miniatura: letti $got byte su $full" || ko "foto con miniatura: letti $got byte su $full"
[ "$dgot" -le $((131072 + 2048)) ] && ok "controllo doppioni: letti $dgot byte (inizio e fine, una volta)" || ko "controllo doppioni: letti $dgot byte"
vfull=$(stat -c %s "$FIX/VIDEO_I_index_at_end.mp4")
vgot=$(echo "$LOG" | grep "GET .*VIDEO_I_index_at_end.mp4" | awk '{s+=$NF} END {print s+0}')
echo "     (video piccolo con l'indice in fondo: letti $vgot byte su $vfull)"
bfull=$(stat -c %s "$FIX/VIDEO_M_big.mp4")
bgot=$(echo "$LOG" | grep "GET .*VIDEO_M_big.mp4" | awk '{s+=$NF} END {print s+0}')
[ "$bgot" -gt 0 ] && [ "$bgot" -lt $((bfull / 4)) ] && ok "video grande: letti $bgot byte su $bfull (non scaricato intero)" || { ko "video grande: letti $bgot byte su $bfull"; echo "$LOG" | grep VIDEO_M; }
# riaperta l'app (memoria vuota): le anteprime vengono dal telefono, nessuna foto o video riscaricato
before=$(docker logs st-apache-$PORT 2>/dev/null | grep -c '^GET /Photos/')
open_dir; sleep 8; shot 02-riaperta
after=$(docker logs st-apache-$PORT 2>/dev/null | grep -c '^GET /Photos/')
[ "$after" -eq "$before" ] && ok "riaperta: anteprime dal telefono, 0 foto o video scaricati" || { ko "riaperta: $((after - before)) download di nuovo"; docker logs st-apache-$PORT 2>/dev/null | grep '^GET /Photos/' | tail -n $((after - before)); }
# «A caso» (voci dall'indice, senza dimensione): con le anteprime cancellate e senza giro (finito da poco), le deve
# fare lo schermo — e il video grande NON si scarica intero
lines0=$(docker logs st-apache-$PORT 2>/dev/null | wc -l)
$ADB shell am force-stop $PKG; $ADB shell run-as $PKG rm -rf files/cloud-thumbs
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 4; tap_text Random >/dev/null
wantr=13; [ "$SDK" -lt 28 ] && wantr=12
for i in $(seq 1 45); do n=$(count); [ "$n" -ge $wantr ] && break; sleep 2; done
sleep 3; shot 03-a-caso
[ "$n" -ge $wantr ] && ok "A caso: $n anteprime fatte dallo schermo" || ko "A caso: $n anteprime su $wantr"
python3 - "$OUT/03-a-caso.png" <<'PYX' > /tmp/st-acaso.txt
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB").resize((270, 600)); px = list(im.getdata())
r = sum(1 for p in px if p[0] > 150 and p[1] < 110 and p[2] < 110) / len(px)
b = sum(1 for p in px if p[2] > 150 and p[0] < 110) / len(px)
print(f"{r:.2f} {b:.2f}")
PYX
read R B < /tmp/st-acaso.txt
python3 -c "import sys; sys.exit(0 if $R >= 0.10 and $B >= 0.10 else 1)" && ok "A caso: le anteprime si vedono sullo schermo (rosso $R, blu $B)" || ko "A caso: sullo schermo non si vedono (rosso $R, blu $B)"
LOGR=$(docker logs st-apache-$PORT 2>/dev/null | tail -n +$((lines0 + 1)))
rgot=$(echo "$LOGR" | grep "GET .*VIDEO_M_big.mp4" | awk '{s+=$NF} END {print s+0}')
[ "$rgot" -lt $((bfull / 4)) ] && ok "A caso: video grande letto a pezzi ($rgot byte su $bfull)" || ko "A caso: video grande letti $rgot byte su $bfull"
# file nuovo nel cloud (in una cartella mai aperta) e due spariti: riaperta l'app, il giro li vede da solo
cp $FIX/PHOTO_B_exif_tiny.jpg "$DAV/Archivio/Video vecchi/NUOVA.jpg"; rm "$DAV/Photos/2025/Mare/MARE_1.jpg" "$DAV/Photos/2025/Mare/MARE_2.jpg"
$ADB shell am force-stop $PKG; $ADB shell run-as $PKG rm -f shared_prefs/cloud_thumbs.xml # come se fossero passati 10 minuti
$ADB shell am start -W -n $PKG/.MainActivity -e place photos >/dev/null
exp=$((want - 1)); for i in $(seq 1 45); do n=$(count); [ "$n" -eq $exp ] && break; sleep 2; done
[ "$n" -eq $exp ] && ok "riaperta: anteprima del file nuovo fatta, quelle dei 2 spariti buttate ($n)" || ko "file nuovo/spariti: $n anteprime, attese $exp"
alive | grep -q vivo && ok "app viva" || ko "app morta"
crashes | grep -q FATAL && { ko "crash"; crashes | head -20; }
exit $fail
