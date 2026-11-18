#!/bin/bash
# Media mostra le cartelle del cloud tenute «sempre sul telefono» («è sul mio telefono, devo poterlo
# sfogliare in Media»). Solo file di prova, cloud finto. Una cartella «Manga Pages» con due volumi: dopo «Keep on phone»
# in Media c'è l'album con la nuvola, le sottocartelle fanno da intestazioni, «Page 2» viene prima di «Page 10»,
# in «All» le pagine NON si mischiano col rullino, una pagina si apre nel visore anche SENZA rete, e la linguetta
# Preferiti ha la stella (nessun foro). Uscita != 0 se una cosa non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/media-cloud
source ./lib.sh
FIX=/tmp/st-thumbfix; D=st-mcloud; PORT=8093
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
M="/tmp/$D/Manga Pages"; mkdir -p "$M/Vol 1" "$M/Vol 2"
for p in 1 2 10; do cp $FIX/PHOTO_C_no_exif.jpg "$M/Vol 1/Page $p.jpg"; done
cp $FIX/PHOTO_C_no_exif.jpg "$M/Vol 2/Page 1.jpg"
chmod -R a+rwX /tmp/$D
bash apache-dav.sh start /tmp/$D $PORT prova123
trap 'bash apache-dav.sh stop $PORT; $ADB shell cmd connectivity airplane-mode disable >/dev/null 2>&1' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
clearlog
bash connect.sh http://10.0.2.2:$PORT prova prova123
ui_dump; python3 - <<'PY' > /tmp/c.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if re.search(r' text="Manga Pages"',n):
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
read X Y < /tmp/c.txt; $ADB shell input swipe $X $Y $X $Y 900; sleep 1; tap_text "Keep on phone" >/dev/null; sleep 12
n=$($ADB shell run-as $PKG find files/offline -name '*.jpg' | wc -l); [ "$n" -eq 4 ] && ok "4 pagine copiate sul telefono" || ko "copiate $n pagine, attese 4"
tap_text "Media" >/dev/null; sleep 4; shot 01-media
T=$(ui_texts)
echo "$T" | grep -q 'MANGA PAGES' && ok "in Media c'è l'album MANGA PAGES" || ko "album MANGA PAGES assente"
tap_text "ALL" >/dev/null; sleep 2; T=$(ui_texts)
echo "$T" | grep -q 'Page 10.jpg' && ko "le pagine finiscono in All" || ok "in All le pagine non si mischiano col rullino"
tap_text "MANGA PAGES" >/dev/null; sleep 4; shot 02-album
ui_dump; python3 - <<'PY' > /tmp/st-mc.txt
import re
x=open('/tmp/ui.xml').read(); pos={}
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0); t=re.search(r' text="([^"]*)"',n).group(1); d=re.search(r'content-desc="([^"]*)"',n).group(1)
    b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1))))
    k=t or d
    if k in ("VOL 1","VOL 2") or k.startswith("Page "): pos.setdefault(k,[]).append((b[1],b[0]))
order=[k for k,_ in sorted(((k,v[0]) for k,v in pos.items() if k!="Page 1.jpg"), key=lambda kv: kv[1])]
print("headers", "VOL 1" in pos and "VOL 2" in pos and pos["VOL 1"][0] < pos["VOL 2"][0])
print("natural", "Page 2.jpg" in pos and "Page 10.jpg" in pos and pos["Page 2.jpg"][0] < pos["Page 10.jpg"][0])
print("cells", sum(len(v) for k,v in pos.items() if k.endswith(".jpg")))
PY
cat /tmp/st-mc.txt
grep -q "headers True" /tmp/st-mc.txt && ok "intestazioni VOL 1 / VOL 2 in ordine" || ko "intestazioni delle sottocartelle"
grep -q "natural True" /tmp/st-mc.txt && ok "Page 2 prima di Page 10" || ko "ordine delle pagine"
grep -q "cells 4" /tmp/st-mc.txt && ok "4 celle nell'album" || ko "celle nell'album: $(grep cells /tmp/st-mc.txt)"
# senza rete: la pagina si apre dalla copia
$ADB shell cmd connectivity airplane-mode enable >/dev/null 2>&1; sleep 2
tap_text "Page 10.jpg" >/dev/null; sleep 5; shot 03-visore-senza-rete
T=$(ui_texts)
echo "$T" | grep -q "Page 10.jpg" && ! echo "$T" | grep -q "Can't show this photo" && ok "Page 10 si apre senza rete" || ko "visore senza rete"
# (0.9.9, un solo visore) anche qui c'è Elimina: sposta nel cestino del CLOUD l'originale (la copia segue)
echo "$T" | grep -qE 'text="Delete"|content-desc="Delete"' && ok "Elimina nel visore anche su una copia del cloud" || ko "Elimina assente nel visore"
$ADB shell cmd connectivity airplane-mode disable >/dev/null 2>&1
# stella: la pagina diventa preferita, la linguetta Preferiti compare con la stella e senza foro
tap_text "Favourite" >/dev/null || tap_text "Add to favourites" >/dev/null || tap_text "Favorite" >/dev/null; sleep 1
$ADB shell input keyevent 4; sleep 3
T=$(ui_texts); echo "$T" | grep -qE 'FAVOU?RITES' && ok "linguetta Preferiti presente" || ko "linguetta Preferiti assente"
tap_text "$(echo "$T" | grep -oE 'FAVOU?RITES' | head -1)" >/dev/null; sleep 3; shot 04-preferiti
T=$(ui_texts); echo "$T" | grep -q "Page 10.jpg" && ok "la pagina sta nei Preferiti" || ko "pagina non nei Preferiti"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
