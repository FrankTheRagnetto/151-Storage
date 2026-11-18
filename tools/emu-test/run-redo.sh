#!/bin/bash
# «Le avevo già scaricate, perché vengono rifatte?». Un telefono che non lascia scrivere la data sulle
# anteprime le ha tutte «più nuove» del file del cloud: la regola vecchia (data diversa di 2 s = da rifare) le rifaceva
# a ogni giro, scaricando di nuovo tutto. Qui: giro completo, poi le anteprime vengono toccate (data = adesso, come su
# quel telefono), poi un giro nuovo: NESSUNA foto o video deve essere riscaricato.
# Uscita != 0 se una cosa sola non torna. Pochi minuti.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/redo
source ./lib.sh
FIX=/tmp/st-thumbfix; DAV=/tmp/st-redodav; PORT=8094; PASS=prova$RANDOM
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
rm -rf $DAV; mkdir -p "$DAV/Photos/Prove"; cp $FIX/* "$DAV/Photos/Prove/"
touch -d "2020-06-01 12:00" "$DAV/Photos/Prove/"* # come le foto vere: scattate anni fa
bash apache-dav.sh start $DAV $PORT "$PASS"; trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
count() { $ADB shell run-as $PKG ls files/cloud-thumbs 2>/dev/null | grep -c '\.jpg$'; }
bash connect.sh "http://10.0.2.2:$PORT" prova "$PASS"
want=$(ls "$DAV/Photos/Prove" | wc -l); for i in $(seq 1 90); do [ "$(count)" -ge "$want" ] && break; sleep 2; done
for i in $(seq 1 30); do $ADB shell dumpsys notification --noredact | grep -q "Making cloud previews" || break; sleep 2; done
n=$(count); [ "$n" -ge "$want" ] && ok "primo giro: $n anteprime" || ko "primo giro: $n anteprime su $want"
# come sul telefono che non lascia scrivere la data: ogni anteprima «fatta adesso»
$ADB shell am force-stop $PKG; sleep 2
$ADB shell "run-as $PKG sh -c 'touch files/cloud-thumbs/*; rm -f shared_prefs/cloud_thumbs.xml'" # adb unisce gli argomenti: tutto fra virgolette doppie
before=$(docker logs st-apache-$PORT 2>/dev/null | grep -c '^GET /Photos/')
clearlog; $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null
for i in $(seq 1 45); do $ADB logcat -d | grep -q "giro anteprime" && break; sleep 2; done
$ADB logcat -d | grep -q "giro anteprime" && ok "secondo giro fatto: $($ADB logcat -d | grep -o 'giro anteprime.*' | tail -1)" || ko "secondo giro non partito"
after=$(docker logs st-apache-$PORT 2>/dev/null | grep -c '^GET /Photos/')
[ "$after" -eq "$before" ] && ok "secondo giro: 0 foto o video riscaricati" || ko "secondo giro: $((after - before)) download, anteprime rifatte"
[ "$(count)" -ge "$want" ] && ok "anteprime ancora tutte sul telefono ($(count))" || ko "anteprime sparite: $(count)"
exit $fail
