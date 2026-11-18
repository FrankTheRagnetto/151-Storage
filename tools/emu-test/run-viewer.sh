#!/bin/bash
# Un solo visore: una foto si apre con lo STESSO aspetto e la STESSA barra da Media, da Phone e dal Cloud
# (Share · Upload/Download · Delete · Info · More, fascia dello stesso colore), e dentro il visore si elimina:
# dal Cloud la foto va nel cestino del cloud, il visore passa alla prossima e tornando indietro non c'è più.
# Solo file di prova, cloud finto. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/viewer
source ./lib.sh
FIX=/tmp/st-thumbfix; D=st-view; PORT=8098
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
mkdir -p "/tmp/$D/Photos/Prove"; for n in UNO DUE TRE; do cp $FIX/PHOTO_C_no_exif.jpg "/tmp/$D/Photos/Prove/$n.jpg"; done; chmod -R a+rwX /tmp/$D
bash apache-dav.sh start /tmp/$D $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
$ADB shell rm -rf /sdcard/DCIM/Vista; $ADB shell mkdir -p /sdcard/DCIM/Vista
$ADB push $FIX/PHOTO_C_no_exif.jpg /sdcard/DCIM/Vista/VISTA_1.jpg >/dev/null; $ADB push $FIX/PHOTO_A_exif_ok.jpg /sdcard/DCIM/Vista/VISTA_2.jpg >/dev/null
$ADB shell 'for f in /sdcard/DCIM/Vista/*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null; done'
clearlog
# la barra del visore: le scritte in fondo, da sinistra a destra, e il colore della fascia sotto la barra in alto
bar() { shot "$1"; ui_dump; python3 - "$OUT/$1.png" <<'PY'
import re, sys
from PIL import Image
x = open('/tmp/ui.xml').read(); im = Image.open(sys.argv[1]).convert("RGB"); H = im.height; row = []; band = None
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0); t = re.search(r' text="([^"]*)"', n).group(1)
    b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1))))
    if t in ("Share", "Upload", "In cloud", "Download", "Delete", "Info", "More") and b[1] > H * 0.8: row.append((b[0], t))
    if 'mBand"' in n: band = im.getpixel(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
print("bar", " ".join(t for _, t in sorted(row))); print("band", band)
PY
}
# 1) Media
$ADB shell am start -W -n $PKG/.MainActivity -e place photos >/dev/null; sleep 4
tap_text "VISTA_1.jpg" >/dev/null; sleep 3; bar 01-media > /tmp/st-v1.txt; $ADB shell input keyevent 4; sleep 2
# (2) Phone: posto tolto il 14/10, niente da provare
# 3) Cloud
bash connect.sh http://10.0.2.2:$PORT prova prova123; sleep 3
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 3
tap_text Photos >/dev/null; sleep 2; tap_text Prove >/dev/null; sleep 3
tap_text "DUE.jpg" >/dev/null; sleep 4; bar 03-cloud > /tmp/st-v3.txt
cat /tmp/st-v1.txt /tmp/st-v3.txt
grep -q "^bar Share Upload Delete Info More" /tmp/st-v1.txt && ok "Media: Share · Upload · Delete · Info · More" || ko "Media: $(head -1 /tmp/st-v1.txt)"
grep -q "^bar Share Download Delete Info More" /tmp/st-v3.txt && ok "Cloud: la stessa barra (Download al posto di Upload)" || ko "Cloud: $(grep bar /tmp/st-v3.txt)"
b1=$(grep band /tmp/st-v1.txt); [ "$b1" = "$(grep band /tmp/st-v3.txt)" ] && [ "$b1" != "band None" ] && ok "la fascia è dello stesso colore ovunque" || ko "fascia: $(grep -h band /tmp/st-v*.txt | tr '\n' ' ')"
# 4) elimina dentro il visore, dal Cloud
tap_text Delete >/dev/null; sleep 2; tap_text "Move to trash" >/dev/null; sleep 5; shot 04-dopo
[ ! -f "/tmp/$D/Photos/Prove/DUE.jpg" ] && ok "DUE.jpg non è più nella cartella" || ko "DUE.jpg ancora lì"
ls /tmp/$D/.151-storage-trash/*/DUE.jpg >/dev/null 2>&1 && ok "DUE.jpg nel cestino del cloud (si ripristina)" || ko "DUE.jpg non nel cestino"
T=$(ui_texts); echo "$T" | grep -qE 'text="(UNO|TRE).jpg"' && ok "il visore passa alla foto accanto" || ko "il visore dopo l'eliminazione: $(echo "$T" | head -c 300)"
$ADB shell input keyevent 4; sleep 2; T=$(ui_texts)
! echo "$T" | grep -q 'DUE.jpg' && echo "$T" | grep -q 'UNO.jpg' && ok "tornando al Cloud DUE.jpg non c'è più" || ko "il Cloud mostra ancora DUE.jpg"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
