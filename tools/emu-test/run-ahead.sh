#!/bin/bash
# Il visore prepara le vicine: guardando una foto del cloud, anche quella DOPO LA DOPO è già
# scaricata (prima solo ±1), così scorrendo compare subito. Un video parte da solo quando ti fermi su di lui.
# Cloud finto a 300 KiB/s come i dati. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/ahead
source ./lib.sh
FIX=/tmp/st-thumbfix; D=st-ahead; PORT=8100
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
P="/tmp/$D/Prove"; mkdir -p "$P"
for n in 1 2 3 4; do cp $FIX/PHOTO_C_no_exif.jpg "$P/FOTO_$n.jpg"; done; cp $FIX/VIDEO_J_faststart.mp4 "$P/FOTO_5_video.mp4"
chmod -R a+rwX /tmp/$D
bash apache-dav.sh start /tmp/$D $PORT prova123 300
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
$ADB shell "run-as $PKG sh -c 'mkdir -p files && touch files/no-sweep'" # niente giro delle anteprime: si misura il visore
bash connect.sh http://10.0.2.2:$PORT prova prova123
tap_text Prove >/dev/null; sleep 3
clearlog; tap_text "FOTO_1.jpg" >/dev/null
full=$(stat -c %s $FIX/PHOTO_C_no_exif.jpg)
got3() { docker logs st-apache-$PORT 2>/dev/null | grep "GET /Prove/FOTO_3.jpg " | awk '{s+=$NF} END {print s+0}'; }
# pronta = il file intero nella cache del visore (l'anteprima della cartella lo legge ma non lo tiene: non conta)
in3() { $ADB shell run-as $PKG find cache -name 'FOTO_3.jpg' 2>/dev/null | grep -c FOTO_3; }
for i in $(seq 1 40); do [ "$(in3)" -ge 1 ] && break; sleep 1; done
[ "$(in3)" -ge 1 ] && ok "guardando la 1, la 3 è già scaricata nel visore" || ko "la 3 non è pronta nel visore"
# avanti fino a un titolo: un colpo di dito, si aspetta che la pagina si fermi, si guarda il titolo
goto() { for k in 1 2 3 4 5; do ui_dump; grep -q "text=\"$1\"" /tmp/ui.xml && return 0; $ADB shell input swipe 900 1200 150 1200 120; sleep ${2:-1.2}; done; ui_dump; grep -q "text=\"$1\"" /tmp/ui.xml; }
# avanti fino alla 3: compare subito (niente rotella)
goto FOTO_3.jpg; shot 01-terza
grep -q 'text="FOTO_3.jpg"' /tmp/ui.xml && ok "arrivati sulla 3" || ko "non siamo sulla 3: $(grep -o 'text="FOTO_[^"]*"' /tmp/ui.xml | head -2)"
grep -q 'ProgressBar' /tmp/ui.xml && ko "la 3 sta ancora caricando" || ok "la 3 compare subito"
# il video: ci si ferma sopra → parte da solo
clearlog; goto FOTO_5_video.mp4; sleep 3; shot 02-video
UID_APP=$($ADB shell dumpsys package $PKG | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
$ADB logcat -d | grep -qE "requestAudioFocus.*(callingPack=$PKG|from uid/pid $UID_APP/)" && ok "il video parte da solo quando ci si ferma" || ko "il video non è partito"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
