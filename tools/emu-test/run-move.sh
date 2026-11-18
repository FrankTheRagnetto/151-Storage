#!/bin/bash
# «Sposta in…» dentro il visore: More › Move to… → la prima volta «Another folder…»
# (il solito selettore), dalla seconda la cartella usata compare in cima e basta un tocco. Il visore passa alla foto
# accanto. Cloud e telefono. Cloud finto, file di prova. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/move
source ./lib.sh
FIX=/tmp/st-thumbfix; D=st-move; PORT=8101
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
mkdir -p "/tmp/$D/Prove" "/tmp/$D/Archivio"; for n in A B C; do cp $FIX/PHOTO_C_no_exif.jpg "/tmp/$D/Prove/SPOSTA_$n.jpg"; done; chmod -R a+rwX /tmp/$D
bash apache-dav.sh start /tmp/$D $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
$ADB shell rm -rf /sdcard/DCIM/Sposta /sdcard/Pictures/Meta; $ADB shell mkdir -p /sdcard/DCIM/Sposta /sdcard/Pictures/Meta
for n in 1 2; do $ADB push $FIX/PHOTO_C_no_exif.jpg /sdcard/DCIM/Sposta/TEL_$n.jpg >/dev/null; done
$ADB shell 'for f in /sdcard/DCIM/Sposta/*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null; done'
clearlog
bash connect.sh http://10.0.2.2:$PORT prova prova123
move() { tap_text More >/dev/null; sleep 1; tap_text "Move to…" >/dev/null; sleep 1.5; }
# --- cloud: A con il selettore, B con un tocco
tap_text Prove >/dev/null; sleep 3; tap_text "SPOSTA_A.jpg" >/dev/null; sleep 3
move; tap_text "Another folder…" >/dev/null; sleep 3; tap_text "⟵ Cloud" >/dev/null; sleep 2; tap_text Archivio >/dev/null; sleep 2; tap_text "Move here" >/dev/null; sleep 4
[ -f /tmp/$D/Archivio/SPOSTA_A.jpg ] && [ ! -f /tmp/$D/Prove/SPOSTA_A.jpg ] && ok "cloud: A spostata col selettore" || ko "cloud: A non spostata"
ui_dump; grep -qE 'text="SPOSTA_(B|C).jpg"' /tmp/ui.xml && ok "cloud: il visore passa alla foto accanto" || ko "cloud: visore dopo lo spostamento"
xb=$(grep -oE 'text="SPOSTA_(B|C).jpg"' /tmp/ui.xml | head -1 | sed 's/text="//; s/"//')
move; shot 01-recenti; tap_text "Archivio" >/dev/null; sleep 4
[ -f "/tmp/$D/Archivio/$xb" ] && ok "cloud: $xb spostata con UN tocco (cartella recente in cima)" || ko "cloud: cartella recente"
$ADB shell input keyevent 4; sleep 2; T=$(ui_texts)
! echo "$T" | grep -qE 'SPOSTA_A.jpg|'"$xb" && ok "cloud: tornando, le spostate non ci sono più" || ko "cloud: la cartella mostra ancora le spostate"
# (telefono dal posto Phone: tolto il 14/10, l'utente)
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
