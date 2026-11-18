#!/bin/bash
# Lo stato vero dappertutto (difetto della 0.9.10: eliminati in Duplicates, cambiata sezione, tornati → di
# nuovo lì). Cloud finto, file di prova. Si elimina e si sposta; poi si cambia sezione, si torna, si riapre l'app:
# quello che non c'è più non ricompare MAI — Duplicates, Similar, ricerca, cartella di partenza — e quello che è stato
# spostato compare nella cartella d'arrivo. Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/fresh
source ./lib.sh
D=st-fresh; PORT=8102
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
C=/tmp/$D; mkdir -p "$C/Photos/2016" "$C/Photos/2026/Raffica" "$C/Material"
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
scene(5).save(f"{c}/Photos/2016/doppione.jpg", quality=90); shutil.copy(f"{c}/Photos/2016/doppione.jpg", f"{c}/Material/doppione copia.jpg")
R = f"{c}/Photos/2026/Raffica"
scene(1).save(f"{R}/IMG_20260105_100000.jpg", quality=70); scene(1, dx=12, bright=8).save(f"{R}/IMG_20260105_100001.jpg", quality=97)
scene(7).save(f"{c}/Photos/2016/sposta_me.jpg", quality=90)
PY
chmod -R a+rwX $C
bash apache-dav.sh start $C $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
clearlog
bash connect.sh http://10.0.2.2:$PORT prova prova123
for i in $(seq 1 60); do $ADB shell run-as $PKG cat files/index-phone/similar.json 2>/dev/null | grep -q IMG_20260105 && break; sleep 2; done
sleep 3
hold() { ui_dump; python3 - "$1" > /tmp/xy.txt <<'PY'
import re, sys
x = open('/tmp/ui.xml').read(); w = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    if re.search(r' (text|content-desc)="' + re.escape(w) + '"', n):
        b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1)))); print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); break
else: print("NONE NONE")
PY
  read X Y < /tmp/xy.txt; [ "$X" != NONE ] && $ADB shell input swipe $X $Y $X $Y 900; sleep 1; }
cloud() { $ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 3; }
away_and_back() { tap_text Media >/dev/null; sleep 3; tap_text Cloud >/dev/null; sleep 3; }
has() { ui_texts | grep -q "$1"; }
# ---- 1) elimina in Duplicates, cambia sezione, torna
cloud; tap_text Duplicates >/dev/null; sleep 3
has 'doppione copia.jpg' && ok "prima: la copia è in Duplicates" || ko "prima: la copia non c'è in Duplicates"
hold "doppione copia.jpg"; tap_text Delete >/dev/null; sleep 2; tap_text "Move to trash" >/dev/null; sleep 4
has 'doppione copia.jpg' && ko "subito: la copia ancora in Duplicates" || ok "subito: la copia sparisce da Duplicates"
away_and_back; shot 01-tornato; T=$(ui_texts)
echo "$T" | grep -q 'doppione copia.jpg' && ko "tornando: la copia RICOMPARE (il difetto)" || ok "tornando da Media: la copia non ricompare"
echo "$T" | grep -q 'SAME FILE ·' && ko "tornando: il gruppo «Same file» con un file solo" || ok "il gruppo di copie è sparito (ne resta una)"
# ---- 2) quasi uguali: elimina dal visore, torna
tap_text "IMG_20260105_100000.jpg" >/dev/null; sleep 3; tap_text Delete >/dev/null; sleep 2; tap_text "Move to trash" >/dev/null; sleep 4
$ADB shell input keyevent 4; sleep 2; T=$(ui_texts)
echo "$T" | grep -q 'SIMILAR ·' && ko "dal visore: il gruppo Similar resta con una foto sola" || ok "dal visore: il gruppo Similar è sparito"
away_and_back; ui_texts | grep -q 'SIMILAR ·' && ko "tornando: Similar ricompare" || ok "tornando: Similar non ricompare"
# ---- 3) Move to…: la cartella di partenza la perde, quella d'arrivo la mostra, anche tornando
$ADB shell input keyevent 4; sleep 2; tap_text Photos >/dev/null; sleep 2; tap_text 2016 >/dev/null; sleep 3
tap_text "sposta_me.jpg" >/dev/null; sleep 3; tap_text More >/dev/null; sleep 1; tap_text "Move to…" >/dev/null; sleep 1.5
tap_text "Another folder…" >/dev/null; sleep 3
for k in 1 2 3; do ui_dump; grep -q 'text="Material"' /tmp/ui.xml && break; tap_text "⟵ Photos" >/dev/null || tap_text "⟵ Cloud" >/dev/null; sleep 2; done
tap_text Material >/dev/null; sleep 2; tap_text "Move here" >/dev/null; sleep 4
[ -f "$C/Material/sposta_me.jpg" ] && ok "spostata nel cloud" || ko "non spostata"
$ADB shell input keyevent 4; sleep 2; has 'sposta_me.jpg' && ko "la cartella 2016 la mostra ancora" || ok "la cartella di partenza non la mostra più"
away_and_back; has 'sposta_me.jpg' && ko "tornando: 2016 la mostra di nuovo" || ok "tornando da Media: 2016 non la mostra"
$ADB shell input keyevent 4; sleep 2; $ADB shell input keyevent 4; sleep 2; tap_text Material >/dev/null; sleep 4
has 'sposta_me.jpg' && ok "la cartella d'arrivo la mostra" || ko "Material non la mostra"
# ---- 4) l'app chiusa e riaperta: niente torna indietro (la lista del telefono è di prima delle modifiche)
$ADB shell am force-stop $PKG; cloud; tap_text Duplicates >/dev/null; sleep 4; T=$(ui_texts)
! echo "$T" | grep -q 'doppione copia.jpg' && ! echo "$T" | grep -q 'SIMILAR ·' && ok "app riaperta: Duplicates e Similar restano giusti" || ko "app riaperta: tornano gli eliminati"
# (senza più doppioni la voce Duplicates sparisce dalla radice: niente «indietro», si riapre il Cloud)
$ADB shell am force-stop $PKG; cloud; tap_text Search >/dev/null; sleep 1; $ADB shell input text "sposta"; sleep 4; shot 02-cerca; ui_dump
python3 - <<'PY' > /tmp/st-fr.txt
import re
x = open('/tmp/ui.xml').read(); n = 0
for m in re.finditer(r'<node [^>]*>', x):
    t = re.search(r' (?:text|content-desc)="([^"]*)"', m.group(0)).group(1)
    if t == "sposta_me.jpg": n += 1
print(n)
PY
[ "$(cat /tmp/st-fr.txt)" -ge 1 ] && ok "ricerca: la trova (al posto nuovo)" || ko "ricerca: non la trova"
$ADB shell run-as $PKG cat files/index/cloud-state.json | grep -q "sposta_me" && ok "il registro lo ricorda finché la lista non è rifatta" || ko "registro vuoto"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
