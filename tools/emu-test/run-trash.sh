#!/bin/bash
# 151 Storage: il cestino del cloud lo fa l'APP da sola.
#  A) cloud qualunque → cestino dell'app `.151-storage-trash/<id>/<nome>` + meta.json; Restore; Delete forever.
# Solo file di prova, server WebDAV Apache. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/trash
source ./lib.sh
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
xy() { ui_dump; python3 - "$1" > /tmp/xy.txt <<'PY'
import re,sys
x=open('/tmp/ui.xml').read(); w=sys.argv[1]; h=[]
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if re.search(r' (text|content-desc)="'+re.escape(w)+'"',n):
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); h.append(((b[0]+b[2])//2,(b[1]+b[3])//2))
print(*(h[-1] if h else ("NONE","NONE")))
PY
read X Y < /tmp/xy.txt; }
hold() { xy "$1"; [ "$X" != NONE ] && $ADB shell input swipe $X $Y $X $Y 900; sleep 1; }
menu() { tap_text "More options" >/dev/null; sleep 1; tap_text "$1" >/dev/null; sleep 3; }
clean() { docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$1" >/dev/null 2>&1; rm -rf "/tmp/$1"; }
fresh() { $ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow; $ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
  $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 5; }
trap 'bash apache-dav.sh stop 8095' EXIT
# ---------- A: cestino dell'app (cloud qualunque)
D=st-tr-a; clean $D; mkdir -p /tmp/$D/Material; echo uno > /tmp/$D/Material/a.txt; echo due > /tmp/$D/Material/b.txt
bash apache-dav.sh start /tmp/$D 8095 prova123 2>/dev/null; fresh; bash connect.sh http://10.0.2.2:8095 prova prova123
tap_text Material >/dev/null; sleep 3; hold a.txt; tap_text Delete >/dev/null; sleep 2
tap_text "Move to trash" >/dev/null; sleep 4
[ ! -f /tmp/$D/Material/a.txt ] && ls /tmp/$D/.151-storage-trash/*/a.txt >/dev/null 2>&1 && ls /tmp/$D/.151-storage-trash/*/meta.json >/dev/null 2>&1 && ok "A: nel cestino dell'app, con la scheda" || ko "A: cestino dell'app"
menu Trash; ui_texts | grep -q 'text="a.txt"' && ok "A: Trash lo mostra" || ko "A: Trash lo mostra"
tap_text a.txt >/dev/null; sleep 1; tap_text Restore >/dev/null; sleep 4
[ -f /tmp/$D/Material/a.txt ] && [ -z "$(ls /tmp/$D/.151-storage-trash 2>/dev/null)" ] && ok "A: Restore lo rimette al suo posto" || ko "A: Restore"
bash apache-dav.sh stop 8095
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
clean st-tr-a
exit $fail
