#!/bin/bash
# Anteprime nel Cestino del cloud. Solo file di prova (sopra rosso, sotto blu), cestino dell'app
# (`.151-storage-trash`). Due foto: UNO aveva già l'anteprima prima di essere cancellata (dall'app) →
# nel Cestino si usa quella, senza scaricare niente; DUE già nel cestino e mai vista (messa lì a mano, da un altro
# telefono) → anteprima fatta dal file dentro il cestino. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/trash-thumbs
source ./lib.sh
FIX=/tmp/st-thumbfix; D=st-trth; PORT=8091
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
T=lsabc12-a1b2c3 # una voce del cestino nella forma dell'app: millisecondi in base 36 + «-» + 6 caratteri
mkdir -p /tmp/$D/Photos/Prove /tmp/$D/.151-storage-trash/$T
cp $FIX/PHOTO_C_no_exif.jpg /tmp/$D/Photos/Prove/UNO.jpg; cp $FIX/PHOTO_C_no_exif.jpg /tmp/$D/.151-storage-trash/$T/DUE.jpg
python3 -c "import json,datetime,sys; json.dump({'id':sys.argv[2],'origRel':'Photos/Prove/DUE.jpg','name':'DUE.jpg','type':'file','size':__import__('os').path.getsize(sys.argv[1].replace('meta.json','DUE.jpg')),'deletedAt':datetime.datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%S.000Z')},open(sys.argv[1],'w'))" /tmp/$D/.151-storage-trash/$T/meta.json $T
bash apache-dav.sh start /tmp/$D $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
chmod -R a+rwX /tmp/$D
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
bash connect.sh http://10.0.2.2:$PORT prova prova123
# UNO: vista nella cartella (anteprima fatta), poi cancellata dall'app
tap_text Photos >/dev/null; sleep 2; tap_text Prove >/dev/null; sleep 8
n=$($ADB shell run-as $PKG ls files/cloud-thumbs | grep -c '\.jpg$'); [ "$n" -ge 1 ] && ok "UNO ha l'anteprima prima di essere cancellata" || ko "UNO senza anteprima prima"
ui_dump; python3 - <<'PY' > /tmp/xy.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if 'content-desc="UNO.jpg"' in n:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/xy.txt; $ADB shell input swipe $X $Y $X $Y 900; sleep 1; tap_text Delete >/dev/null; sleep 3; tap_text "Move to trash" >/dev/null; sleep 6
chmod -R a+rwX /tmp/$D 2>/dev/null
before=$(docker logs st-apache-$PORT 2>/dev/null | grep -c "^GET .*-trash/.*UNO.jpg")
tap_text "More options" >/dev/null; sleep 1; tap_text Trash >/dev/null; sleep 12; $ADB exec-out screencap -p > $OUT/cestino.png
ui_dump; python3 - $OUT/cestino.png <<'PY' > /tmp/st-trth.txt
import re, sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); x = open('/tmp/ui.xml').read()
# riga del cestino: il testo col nome e, a sinistra, la miniatura (resource-id …/thumb) sulla stessa altezza
names = {}; thumbs = []
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0); b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1))))
    t = re.search(r' text="([^"]*)"', n).group(1)
    if t in ("UNO.jpg", "DUE.jpg"): names[t] = (b[1] + b[3]) // 2
    if n.find('/thumb"') > 0 and b[2] - b[0] > 20: thumbs.append(b)
for nm, cy in sorted(names.items()):
    good = False
    for b in thumbs:
        if b[1] <= cy <= b[3]:
            cx = (b[0] + b[2]) // 2; t = im.getpixel((cx, b[1] + (b[3] - b[1]) // 4)); u = im.getpixel((cx, b[3] - (b[3] - b[1]) // 4))
            good = t[0] > 120 and t[2] < 130 and u[2] > 120 and u[0] < 130
    print(nm, "si" if good else "no")
PY
grep -q "UNO.jpg si" /tmp/st-trth.txt && ok "Cestino: UNO ha l'anteprima" || ko "Cestino: UNO senza anteprima ($(cat /tmp/st-trth.txt | tr '\n' ' '))"
grep -q "DUE.jpg si" /tmp/st-trth.txt && ok "Cestino: DUE (già nel cestino, mai vista) ha l'anteprima" || ko "Cestino: DUE senza anteprima"
after=$(docker logs st-apache-$PORT 2>/dev/null | grep -c "^GET .*-trash/.*UNO.jpg")
[ "$after" -eq "$before" ] && ok "UNO: usata l'anteprima di prima, nessun download dal cestino" || ko "UNO: riscaricata ($((after - before)))"
docker logs st-apache-$PORT 2>/dev/null | grep -q "^GET .*-trash/.*DUE.jpg" && ok "DUE: anteprima fatta dal file nel cestino" || ko "DUE: nessuna lettura dal cestino"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
