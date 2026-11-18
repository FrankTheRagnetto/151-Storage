#!/bin/bash
# Cancellare non rimescola («in Random si rimescolano tutte; deve sparire solo quella»). Cloud finto WebDAV, SOLO file
# di prova; Random e Today vengono dalla lista che il telefono fa da solo all'apertura.
# In Random, Today e in una cartella: si legge l'elenco delle celle, si cancella la seconda, e l'elenco
# dopo deve essere quello di prima MENO quella, nello stesso ordine. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/delete
source ./lib.sh
FIX=/tmp/st-thumbfix; D=/tmp/st-deldav; PORT=8093
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/st-deldav" >/dev/null 2>&1; rm -rf $D
mkdir -p "$D/Photos/Prove"
for i in $(seq -w 1 40); do cp $FIX/PHOTO_C_no_exif.jpg "$D/Photos/Prove/DEL_$i.jpg"; done # data del file = oggi: «On this day»
chmod -R a+rwX $D
bash apache-dav.sh start $D $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
bash connect.sh http://10.0.2.2:$PORT prova prova123
sleep 20 # la lista del telefono (Random, Today) si fa da sola dopo il collegamento
names() { ui_dump; python3 - <<'PY'
import re
x = open('/tmp/ui.xml').read()
print(" ".join(re.search(r'content-desc="([^"]*)"', m.group(0)).group(1) for m in re.finditer(r'<node [^>]*>', x)
               if re.search(r'content-desc="DEL_\d+\.jpg"', m.group(0))))
PY
}
cell() { ui_dump; python3 - "$1" <<'PY'
import re, sys
x = open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    if f'content-desc="{sys.argv[1]}"' in n:
        b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1)))); print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); break
PY
}
del_second() { # $1 = nome della vista. Cancella la seconda cella e confronta prima/dopo.
  local before after victim want
  before=$(names); victim=$(echo $before | awk '{print $2}')
  [ -z "$victim" ] && { ko "$1: nessuna cella"; return; }
  read X Y < <(cell "$victim"); $ADB shell input swipe $X $Y $X $Y 900; sleep 1; tap_text Delete >/dev/null; sleep 4
  tap_text "Move to trash" >/dev/null; sleep 6; shot "$1"
  after=$(names)
  want=$(echo $before | tr ' ' '\n' | grep -vx "$victim" | tr '\n' ' ' | sed 's/ $//')
  [ ! -f "$D/Photos/Prove/$victim" ] || { ko "$1: $victim non cancellato"; return; }
  # le celle visibili dopo: lo stesso ordine di prima senza la cancellata (in fondo può comparire la prossima)
  [ "${after:0:${#want}}" = "$want" ] && ok "$1: sparita solo $victim, le altre ferme al loro posto" || { ko "$1: elenco cambiato"; echo "   prima: $before"; echo "   dopo:  $after"; }
}
open_place() { $ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 5; tap_text "$1" >/dev/null; sleep 6; }
open_place Random; del_second random
del_second random-di-nuovo
open_place "On this day"; del_second oggi
$ADB shell am force-stop $PKG; $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 5
tap_text Photos >/dev/null; sleep 3; tap_text Prove >/dev/null; sleep 6; del_second cartella
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
