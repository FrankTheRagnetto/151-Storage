#!/bin/bash
# Doppioni con le foto QUASI uguali: dentro Cloud › Duplicates, dopo i gruppi di copie
# identiche, i gruppi «Similar» — stessa scena scattata due volte lo stesso giorno — con la migliore (la più grande)
# per prima. Una scena diversa dello stesso giorno, e la stessa scena di un altro giorno, NON entrano.
# Le impronte vengono dalle anteprime sul telefono: nessuna lettura in più dal cloud. Cloud finto, file di prova.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/similar
source ./lib.sh
D=st-sim; PORT=8099
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
C=/tmp/$D; mkdir -p "$C/Photos/2026/Raffica" "$C/Photos/2025"
python3 - "$C" <<'PY'
import sys, random, shutil
from PIL import Image, ImageDraw
c = sys.argv[1]
def scene(seed, dx=0, bright=0):
    random.seed(seed); im = Image.new("RGB", (1600, 1200), (40, 60, 90)); d = ImageDraw.Draw(im)
    for i in range(14):
        x, y = random.randrange(1500), random.randrange(1100); r = random.randrange(60, 260); col = tuple(random.randrange(256) for _ in range(3))
        d.ellipse([x + dx - r, y - r, x + dx + r, y + r], fill=col)
    return im.point(lambda v: min(255, v + bright)) if bright else im
R = f"{c}/Photos/2026/Raffica"
scene(1).save(f"{R}/IMG_20260105_100000.jpg", quality=70)                 # raffica, la più piccola
scene(1, dx=12, bright=8).save(f"{R}/IMG_20260105_100001.jpg", quality=97) # raffica, la più grande → prima
scene(2).save(f"{R}/IMG_20260105_120000.jpg", quality=90)                 # stesso giorno, scena diversa
scene(1, dx=6).save(f"{c}/Photos/2025/IMG_20250105_100000.jpg", quality=90)  # stessa scena, altro giorno
scene(3).save(f"{R}/IMG_20260105_130000.jpg", quality=90); shutil.copy(f"{R}/IMG_20260105_130000.jpg", f"{c}/Photos/2025/copia.jpg")  # copia identica
PY
chmod -R a+rwX $C
bash apache-dav.sh start $C $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
clearlog
bash connect.sh http://10.0.2.2:$PORT prova prova123
for i in $(seq 1 60); do $ADB shell run-as $PKG cat files/index-phone/similar.json 2>/dev/null | grep -q 'IMG_20260105_100001' && break; sleep 2; done
$ADB shell run-as $PKG cat files/index-phone/similar.json > /tmp/st-sim.json 2>/dev/null
python3 - <<'PY' > /tmp/st-sim.txt
import json
g = json.load(open("/tmp/st-sim.json"))["groups"]
print("gruppi", [[f["path"].split("/")[-1] for f in x["files"]] for x in g])
PY
cat /tmp/st-sim.txt
grep -q "^gruppi \[\['IMG_20260105_100001.jpg', 'IMG_20260105_100000.jpg'\]\]" /tmp/st-sim.txt && ok "una raffica sola, la più grande per prima; scena diversa e altro giorno fuori; copie identiche non ripetute" || ko "gruppi delle quasi uguali"
before=$(docker logs st-apache-$PORT 2>/dev/null | grep -c "^GET .*Raffica")
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 3
tap_text "Duplicates" >/dev/null; sleep 4; shot 01-doppioni; ui_dump
python3 - <<'PY' > /tmp/st-sim2.txt
import re
x = open('/tmp/ui.xml').read(); seq = []
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0); t = re.search(r' text="([^"]*)"', n).group(1); d = re.search(r'content-desc="([^"]*)"', n).group(1)
    for w in (t, d):
        if w.upper().startswith("SAME FILE") or w.upper().startswith("SIMILAR") or w.startswith("IMG_2026") or w == "copia.jpg": seq.append(w if w.startswith(("IMG", "copia")) else w.upper())
out = []; [out.append(w) for w in seq if not out or out[-1] != w]
print(" | ".join(out))
PY
cat /tmp/st-sim2.txt
grep -q "SAME FILE · 2 COPIES" /tmp/st-sim2.txt && ok "le copie identiche, come prima" || ko "copie identiche nella vista"
grep -qE "SIMILAR · 2 PHOTOS \| IMG_20260105_100001.jpg \| IMG_20260105_100000.jpg" /tmp/st-sim2.txt && ok "«Similar · 2 photos», la migliore per prima" || ko "quasi uguali nella vista"
after=$(docker logs st-apache-$PORT 2>/dev/null | grep -c "^GET .*Raffica")
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
