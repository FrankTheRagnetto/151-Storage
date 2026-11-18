#!/bin/bash
# La lista del cloud fatta DAL TELEFONO, su un cloud finto qualunque (WebDAV, niente ls in un colpo, niente md5 del
# server):
#  - date: dentro il file (EXIF, letta facendo l'anteprima) > nome > cartella AAAA-MM-GG > data del file;
#  - tutto il cloud per «On this day»; nascosti fuori; doppioni veri sì, stessa dimensione ma diversi no; ricerca ovunque;
#  - nel Cloud compaiono On this day / Random / Duplicates dalla lista del telefono; «About the cloud list» lo dice.
# Uscita != 0 se una cosa sola non torna.
set -u
cd "$(dirname "$0")"
export OUT=$PWD/out/phone-list
source ./lib.sh
FIX=/tmp/st-thumbfix; D=st-plist; PORT=8097
fail=0; ok() { echo "PASS $1"; }; ko() { echo "FAIL $1"; fail=1; }
[ -f $FIX/PHOTO_C_no_exif.jpg ] || python3 make-thumb-fixtures.py $FIX >/dev/null
docker run --rm -v /tmp:/t alpine sh -c "rm -rf /t/$D" >/dev/null 2>&1; rm -rf /tmp/$D
C=/tmp/$D; mkdir -p "$C/Photos/2019/a" "$C/Photos/2018" "$C/Photos/2017/2017-07-04" "$C/Photos/2016" "$C/Material" "$C/Photos/.nascosta"
python3 - "$C" "$FIX" <<'PY'
import sys, os, shutil, json, random
from PIL import Image
c, fix = sys.argv[1], sys.argv[2]
def jpg(path, seed, exif_date=None):
    random.seed(seed); im = Image.new("RGB", (900, 600), (200, 30, 30))
    for i in range(400): im.putpixel((random.randrange(900), random.randrange(600)), (random.randrange(256),) * 3)
    ex = Image.Exif()
    if exif_date: ex.get_ifd(0x8769)[36867] = exif_date
    im.save(path, quality=90, exif=ex)
jpg(f"{c}/Photos/2019/a/SENZA_NOME.jpg", 1, "2019:05:27 15:33:27")       # data solo dentro il file
jpg(f"{c}/Photos/2018/IMG_20180315_101010.jpg", 2)                        # data dal nome
jpg(f"{c}/Photos/2017/2017-07-04/plain.jpg", 3)                          # data dalla cartella
jpg(f"{c}/Photos/2016/solo_data_file.jpg", 5)                             # solo la data del file
jpg(f"{c}/Photos/2016/doppione.jpg", 6); shutil.copy(f"{c}/Photos/2016/doppione.jpg", f"{c}/Material/doppione copia.jpg")
jpg(f"{c}/Photos/.nascosta/IMG_20150101_000000.jpg", 7)
jpg(f"{c}/Material/fuori_photos_20140101.jpg", 9)
# stessa dimensione, contenuto diverso (non sono doppioni)
a = f"{c}/Photos/2016/uguale_a.jpg"; jpg(a, 10); b = f"{c}/Photos/2016/uguale_b.jpg"
d = bytearray(open(a, "rb").read()); d[200] ^= 0xFF; open(b, "wb").write(d)
open(f"{c}/Material/note di prova.txt", "w").write("ciao")
PY
touch -d "2016-02-29 12:00" "$C/Photos/2016/solo_data_file.jpg"; touch -d "2020-01-01 12:00" "$C/Photos/2019/a/SENZA_NOME.jpg"
chmod -R a+rwX $C
bash apache-dav.sh start $C $PORT prova123
trap 'bash apache-dav.sh stop $PORT' EXIT
$ADB shell pm clear $PKG >/dev/null; $ADB shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
clearlog
bash connect.sh http://10.0.2.2:$PORT prova prova123
for i in $(seq 1 60); do $ADB shell run-as $PKG cat files/index-phone/meta.json 2>/dev/null | grep -q dupsAt && break; sleep 2; done
sleep 8 # le anteprime finiscono e la lista si riscrive con le date lette dentro i file
for f in media-index duplicates meta; do $ADB shell run-as $PKG cat files/index-phone/$f.json > /tmp/st-pl-$f.json 2>/dev/null; done
$ADB shell run-as $PKG cat files/index-phone/list-cache.tsv > /tmp/st-pl-list-cache.tsv 2>/dev/null
python3 - <<'PY' > /tmp/st-pl.txt
import json
m = json.load(open("/tmp/st-pl-media-index.json")); it = {i["path"]: i for v in m["buckets"].values() for i in v}
def d(p): x = it.get(p); return f'{x["capturedDate"]}/{x["source"]}' if x else "ASSENTE"
print("exif", d("Photos/2019/a/SENZA_NOME.jpg"))
print("nome", d("Photos/2018/IMG_20180315_101010.jpg"))
print("cartella", d("Photos/2017/2017-07-04/plain.jpg"))
print("filedate", d("Photos/2016/solo_data_file.jpg"))
print("nascosti", any(".nascosta" in p for p in it))
print("fuori_photos", any(p.startswith("Material/") for p in it))
l = [r.split("\t") for r in open("/tmp/st-pl-list-cache.tsv").read().splitlines()]
print("ricerca", any(r[:2] == ["Material", "note di prova.txt"] for r in l))
g = json.load(open("/tmp/st-pl-duplicates.json"))["groups"]
print("doppioni", sorted(sorted(f["path"] for f in x["files"]) for x in g))
PY
cat /tmp/st-pl.txt
grep -q "^exif 2019-05-27/exif" /tmp/st-pl.txt && ok "data dentro il file (EXIF letta facendo l'anteprima)" || ko "data EXIF"
grep -q "^nome 2018-03-15/name" /tmp/st-pl.txt && ok "data dal nome" || ko "data dal nome"
grep -q "^cartella 2017-07-04/path" /tmp/st-pl.txt && ok "data dalla cartella AAAA-MM-GG" || ko "data dalla cartella"
grep -q "^filedate 2016-02-29/mtime" /tmp/st-pl.txt && ok "data del file per ultima" || ko "data del file"
grep -q "^nascosti False" /tmp/st-pl.txt && ok "cartelle nascoste fuori" || ko "nascosti dentro"
grep -q "^fuori_photos True" /tmp/st-pl.txt && ok "Oggi negli anni da tutto il cloud" || ko "fuori da Photos manca nella lista delle date"
grep -q "^ricerca True" /tmp/st-pl.txt && ok "ricerca anche fuori da Photos" || ko "ricerca"
grep -q "^doppioni \[\['Photos/2016/doppione.jpg', 'Material/doppione copia.jpg'\]\]" /tmp/st-pl.txt && ok "doppione vero sì, stessa dimensione diversa no" || ko "doppioni"
# nel Cloud: le funzioni dalla lista del telefono
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 3; T=$(ui_texts)
echo "$T" | grep -q 'text="On this day"' && echo "$T" | grep -q 'text="Random"' && echo "$T" | grep -q 'text="Duplicates"' && ok "On this day, Random, Duplicates nel Cloud" || ko "funzioni del Cloud"
tap_text "More options" >/dev/null; sleep 1; tap_text "About the cloud list" >/dev/null; sleep 2; shot 01-about; T=$(ui_texts)
echo "$T" | grep -q "this phone's list" && ok "in uso la lista del telefono" || ko "lista in uso: $(echo "$T" | grep -o 'In use now[^.]*')"
$ADB shell input keyevent 4; sleep 1; tap_text "Duplicates" >/dev/null; sleep 3; T=$(ui_texts)
echo "$T" | grep -q "doppione copia.jpg" && ok "Duplicates mostra la coppia" || ko "Duplicates"
crashes | grep -qE "FATAL EXCEPTION" && ko "crash" || ok "nessun crash"
exit $fail
