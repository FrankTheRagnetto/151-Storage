#!/usr/bin/env bash
# Genera i file di prova in tools/web-test/out/ (ignorata da git).
# Richiede: ghostscript (gs), python3 + Pillow.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out && cd out

# 1) PDF di testo, 40 pagine; la parola "Zanzibar" sta alle pagine 3, 17 e 33.
python3 - <<'PY'
lines = ["%!PS", "/Helvetica findfont 22 scalefont setfont"]
for p in range(1, 41):
    lines.append(f"72 720 moveto (151 Viewer - pagina {p} di 40) show")
    lines.append("/Helvetica findfont 12 scalefont setfont")
    for i in range(30):
        word = " Zanzibar" if p in (3, 17, 33) and i == 10 else ""
        lines.append(f"72 {680 - i*20} moveto (Riga {i+1} della pagina {p}: testo di prova selezionabile.{word}) show")
    lines.append("/Helvetica findfont 22 scalefont setfont")
    lines.append("showpage")
open("text.ps", "w").write("\n".join(lines))
PY
gs -q -dNOPAUSE -dBATCH -sDEVICE=pdfwrite -sOutputFile=text.pdf text.ps

# 2) PDF grande (~55 MB): pagine di testo + pagine con foto "rumore" JPEG non comprimibili.
python3 - <<'PY'
import os
from PIL import Image
imgs = []
for i in range(24):
    im = Image.frombytes("RGB", (2400, 1800), os.urandom(2400 * 1800 * 3))
    imgs.append(im)
imgs[0].save("noise.pdf", save_all=True, append_images=imgs[1:], quality=92, resolution=150)
PY
gs -q -dNOPAUSE -dBATCH -sDEVICE=pdfwrite -dPassThroughJPEGImages=true -sOutputFile=big.pdf text.pdf noise.pdf
rm -f noise.pdf text.ps

# 3) testo, JSON, SVG, "docx" finto
printf 'Prima riga\nSeconda riga con àèìòù e €\n\tRiga con tab\n' > note.txt
for i in $(seq 1 5000); do echo "riga $i: il gatto Zanzibar dorme sul divano $i volte"; done >> note.txt
echo '{"nome":"151 Viewer","versione":1,"formati":["pdf","immagini","testo"],"annidato":{"a":[1,2,3],"b":null}}' > dati.json
cat > disegno.svg <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><circle cx="100" cy="100" r="80" fill="#ff5a36"/><text x="100" y="112" font-size="36" text-anchor="middle" fill="#fff" font-family="sans-serif">151</text><script>document.title='SCRIPT ESEGUITO'</script></svg>
SVG
ls -la
