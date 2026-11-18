#!/usr/bin/env python3
"""Campioni SINTETICI della v5 (nessun dato personale) -> tools/samples/v5/.
Sono file veri, fatti con gli strumenti veri quando esistono: ImageMagick (TIFF fax G4, PSD, EPS con
anteprima TIFF), Ghostscript (EPS senza anteprima), LibreOffice (PDF), bsdtar (7z, tar.gz), openssl
(firma CAdES .p7m), gzip. Il CBR serve `rar` (RARLAB, prova gratuita): RAR=/percorso/rar, altrimenti si salta.
I campioni "reali" presi da progetti open source (Outlook .msg, XPS, Pages/Numbers/Keynote, MOBI/AZW3, RAR,
DOC/PPT con immagini…) li scarica tools/web-test/fetch-real-samples.sh in tools/samples-real/ (fuori da git).
Uso: python3 tools/web-test/make-samples-v5.py
"""
import base64, hashlib, json, os, shutil, struct, subprocess, sys, tempfile, zlib, urllib.parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "tools", "samples", "v5")
os.makedirs(OUT, exist_ok=True)
TMP = tempfile.mkdtemp()
def p(n): return os.path.join(OUT, n)
def w(n, text, mode="w"):
    with open(p(n), mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f: f.write(text)
def run(*a, **k): subprocess.run(a, check=True, **k)

# ------------------------------------------------------------------ immagini-documento
pages = []
for i, (title, color) in enumerate([("Pagina 1 · Contratto di prova", "#1a73e8"), ("Pagina 2 · Allegato tecnico", "#188038"), ("Pagina 3 · Firme", "#d93025")]):
    f = os.path.join(TMP, f"pag{i+1}.png")
    run("magick", "-size", "850x1100", "xc:white", "-fill", color, "-draw", "rectangle 60,60 790,160",
        "-fill", "black", "-pointsize", "40", "-annotate", "+80+250", title,
        "-pointsize", "26", "-annotate", "+80+330", "Documento sintetico per 151 Viewer.\nNessun dato reale.",
        "-stroke", "black", "-strokewidth", "3", "-draw", "line 80,900 400,900", f)
    pages.append(f)
run("magick", *pages, "-monochrome", "-compress", "Group4", "-density", "100", p("fax-multipagina.tif"))
run("magick", pages[0], "-resize", "50%", "-compress", "LZW", p("scansione-lzw.tif"))
run("magick", pages[0], "-resize", "40%", p("grafica.psd"))
run("magick", pages[1], "-resize", "10%", "-colors", "16", "ept:" + p("logo-anteprima.eps"))
run("gs", "-q", "-dNOPAUSE", "-dBATCH", "-sDEVICE=eps2write", "-sOutputFile=" + p("vettoriale-senza-anteprima.eps"), "-c",
    "newpath 100 100 moveto 300 300 lineto 500 100 lineto closepath 0.2 0.5 0.9 setrgbcolor fill "
    "/Helvetica findfont 36 scalefont setfont 0 0 0 setrgbcolor 120 400 moveto (EPS di prova) show showpage")
w("disegno.svg", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 260"><rect width="400" height="260" fill="#f4f1ea"/>
<circle cx="120" cy="130" r="80" fill="#1a73e8"/><rect x="220" y="60" width="140" height="140" rx="20" fill="#e8710a"/>
<text x="200" y="245" font-family="sans-serif" font-size="20" text-anchor="middle">SVG di prova</text></svg>""")

# PDF (per .ai e .p7m) con LibreOffice
fodt = os.path.join(TMP, "doc.fodt")
open(fodt, "w").write("""<?xml version="1.0" encoding="UTF-8"?><office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.text">
<office:body><office:text><text:h text:outline-level="1">Contratto firmato di prova</text:h><text:p>Documento sintetico per provare la firma digitale .p7m in 151 Viewer.</text:p></office:text></office:body></office:document>""")
run("soffice", "--headless", "--convert-to", "pdf", "--outdir", TMP, fodt, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
shutil.copy(os.path.join(TMP, "doc.pdf"), p("illustrazione.ai"))  # gli .ai moderni sono PDF

# ------------------------------------------------------------------ fumetti e archivi
comic = os.path.join(TMP, "fumetto"); os.makedirs(comic)
for i in range(1, 12):
    run("magick", "-size", "600x900", f"xc:hsl({i*30},60%,85%)", "-fill", "black", "-pointsize", "90", "-gravity", "center",
        "-annotate", "+0+0", f"Tavola {i}", os.path.join(comic, f"pag{i}.jpg"))
names = sorted(os.listdir(comic))
run("bsdtar", "--format", "7zip", "-cf", p("fumetto.cb7"), "-C", comic, *names)
rar = os.environ.get("RAR")
if rar and os.path.exists(rar):
    if os.path.exists(p("fumetto.cbr")): os.remove(p("fumetto.cbr"))
    run(rar, "a", "-ep", "-idq", "-ma5", p("fumetto.cbr"), *[os.path.join(comic, n) for n in names])
else:
    print("RAR non impostato: fumetto.cbr saltato")
arch = os.path.join(TMP, "archivio"); os.makedirs(os.path.join(arch, "cartella"))
open(os.path.join(arch, "leggimi.md"), "w").write("# Archivio di prova\n\nDentro un **7z**.\n")
open(os.path.join(arch, "cartella", "dati.json"), "w").write(json.dumps({"ok": True, "n": [1, 2, 3]}, indent=1))
open(os.path.join(arch, "cartella", "script.py"), "w").write("def ciao():\n    print('ciao da un archivio')\n")
run("bsdtar", "--format", "7zip", "-cf", p("archivio.7z"), "-C", arch, "leggimi.md", "cartella")
run("bsdtar", "-czf", p("archivio.tar.gz"), "-C", arch, "leggimi.md", "cartella")
run("bsdtar", "-cjf", p("archivio.tar.bz2"), "-C", arch, "leggimi.md", "cartella")
run("bsdtar", "-cJf", p("archivio.tar.xz"), "-C", arch, "leggimi.md", "cartella")
log = "\n".join(f"2026-09-30 10:{i:02d}:00 {'ERROR' if i % 7 == 0 else 'INFO'} evento numero {i}" for i in range(60))
with open(os.path.join(TMP, "server.log"), "w") as f: f.write(log + "\n")
run("gzip", "-kf", os.path.join(TMP, "server.log")); shutil.copy(os.path.join(TMP, "server.log.gz"), p("server.log.gz"))

# ------------------------------------------------------------------ dati e codice
png_b64 = base64.b64encode(open(pages[2], "rb").read()).decode()
subprocess.run(["magick", pages[2], "-resize", "25%", os.path.join(TMP, "small.png")], check=True)
png_b64 = base64.b64encode(open(os.path.join(TMP, "small.png"), "rb").read()).decode()
nb = {"nbformat": 4, "nbformat_minor": 5, "metadata": {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}, "language_info": {"name": "python"}},
  "cells": [
    {"cell_type": "markdown", "metadata": {}, "source": ["# Analisi di prova\n", "Formula: $e^{i\\pi} + 1 = 0$ e una **lista**:\n", "- uno\n", "- due\n"]},
    {"cell_type": "code", "execution_count": 1, "metadata": {}, "source": ["import math\n", "print('radice di 2 =', math.sqrt(2))"], "outputs": [{"output_type": "stream", "name": "stdout", "text": ["radice di 2 = 1.4142135623730951\n"]}]},
    {"cell_type": "code", "execution_count": 2, "metadata": {}, "source": ["{'a': 1, 'b': 2}"], "outputs": [{"output_type": "execute_result", "execution_count": 2, "metadata": {}, "data": {"text/plain": ["{'a': 1, 'b': 2}"]}}]},
    {"cell_type": "code", "execution_count": 3, "metadata": {}, "source": ["df.head()"], "outputs": [{"output_type": "execute_result", "execution_count": 3, "metadata": {}, "data": {
      "text/html": ["<table><tr><th></th><th>città</th><th>abitanti</th></tr><tr><th>0</th><td>Roma</td><td>2.749.000</td></tr><tr><th>1</th><td>Milano</td><td>1.371.000</td></tr></table>"], "text/plain": ["tabella"]}}]},
    {"cell_type": "code", "execution_count": 4, "metadata": {}, "source": ["plt.plot(x, y)"], "outputs": [{"output_type": "display_data", "metadata": {}, "data": {"image/png": png_b64, "text/plain": ["<Figure>"]}}]},
    {"cell_type": "code", "execution_count": 5, "metadata": {}, "source": ["1/0"], "outputs": [{"output_type": "error", "ename": "ZeroDivisionError", "evalue": "division by zero",
      "traceback": ["\u001b[0;31m---------------------------------------------------------------------------\u001b[0m", "\u001b[0;31mZeroDivisionError\u001b[0m: division by zero"]}]},
  ]}
w("analisi.ipynb", json.dumps(nb, indent=1, ensure_ascii=False))
# GPX: anello di 60 punti con quota e tempo
import math
pts = []
for i in range(61):
    a = i / 60 * 2 * math.pi
    lat, lon = 45.07 + 0.01 * math.sin(a), 7.68 + 0.015 * math.cos(a)
    pts.append(f'<trkpt lat="{lat:.6f}" lon="{lon:.6f}"><ele>{240 + 60 * math.sin(a * 2):.1f}</ele><time>2026-09-20T08:{i // 2:02d}:{(i % 2) * 30:02d}Z</time></trkpt>')
w("giro-in-bici.gpx", f"""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="151 Viewer test" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>Giro di prova</name></metadata>
<wpt lat="45.08" lon="7.68"><name>Fontana</name></wpt><wpt lat="45.06" lon="7.695"><name>Bar</name><desc>Sosta caffè</desc></wpt>
<trk><name>Anello</name><trkseg>{''.join(pts)}</trkseg></trk></gpx>""")
w("luoghi.geojson", json.dumps({"type": "FeatureCollection", "features": [
    {"type": "Feature", "properties": {"name": "Piazza"}, "geometry": {"type": "Point", "coordinates": [9.19, 45.464]}},
    {"type": "Feature", "properties": {"name": "Percorso"}, "geometry": {"type": "LineString", "coordinates": [[9.18, 45.46], [9.19, 45.465], [9.2, 45.47]]}},
    {"type": "Feature", "properties": {"name": "Parco"}, "geometry": {"type": "Polygon", "coordinates": [[[9.17, 45.47], [9.18, 45.475], [9.175, 45.48], [9.17, 45.47]]]}}]}, indent=1))
w("eventi.jsonl", "\n".join(json.dumps({"id": i, "tipo": "clic" if i % 2 else "vista", "ms": 120 + i}) for i in range(20)) + "\n")
w("query.sql", "-- report mensile\nSELECT cliente, SUM(importo) AS totale\nFROM fatture\nWHERE data >= '2026-09-01'\nGROUP BY cliente\nORDER BY totale DESC;\n")
w("modifica.diff", """diff --git a/app.py b/app.py
--- a/app.py
+++ b/app.py
@@ -1,4 +1,4 @@
 def saluta(nome):
-    print("Ciao " + nome)
+    print(f"Ciao {nome}!")

 saluta("mondo")
""")
w("articolo.tex", r"""\documentclass{article}
\usepackage[italian]{babel}
\begin{document}
\section{Introduzione}
La formula $E = mc^2$ \cite{einstein}.
\end{document}
""")
w("note.org", "* Progetto\n** TODO Scrivere il README\n   SCHEDULED: <2026-10-01>\n** DONE Provare 151 Viewer\n")
w("guida.rst", "Guida\n=====\n\nUn paragrafo con **grassetto**.\n\n.. code-block:: python\n\n   print('ciao')\n")
w("manuale.adoc", "= Manuale\n:toc:\n\n== Installazione\n\nScarica l'APK e *installalo*.\n")
w("app.properties", "# configurazione\napp.nome=151 Viewer\napp.versione=1.3.0\nlog.livello=INFO\n")
w("config.env", "DATABASE_URL=postgres://utente@localhost/prova\nDEBUG=false\n")
w("Dockerfile", "FROM python:3.12-slim\nWORKDIR /app\nCOPY . .\nRUN pip install -r requirements.txt\nCMD [\"python\", \"app.py\"]\n")
w("Makefile", "all: build\n\nbuild:\n\tgcc -O2 -o prova main.c\n\nclean:\n\trm -f prova\n")
w("build.gradle", "plugins {\n    id 'java'\n}\n\ndependencies {\n    implementation 'com.google.code.gson:gson:2.11.0'\n}\n")
w("esempio.rs", "fn main() {\n    let v: Vec<i32> = (1..=5).map(|x| x * x).collect();\n    println!(\"{:?}\", v);\n}\n")
w("esempio.go", "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"ciao\")\n}\n")
w("esempio.kt", "fun main() {\n    val nomi = listOf(\"Anna\", \"Luca\")\n    nomi.forEach { println(\"Ciao $it\") }\n}\n")

# ------------------------------------------------------------------ diagrammi
model = """<mxGraphModel dx="800" dy="600" grid="1"><root><mxCell id="0"/><mxCell id="1" parent="0"/>
<mxCell id="a" value="Inizio" style="ellipse;fillColor=#dae8fc;strokeColor=#6c8ebf;" vertex="1" parent="1"><mxGeometry x="40" y="40" width="120" height="60" as="geometry"/></mxCell>
<mxCell id="b" value="Controllo&lt;br&gt;&lt;b&gt;dati&lt;/b&gt;" style="rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;" vertex="1" parent="1"><mxGeometry x="220" y="20" width="140" height="100" as="geometry"/></mxCell>
<mxCell id="c" value="Archivio" style="shape=cylinder3;fillColor=#d5e8d4;strokeColor=#82b366;" vertex="1" parent="1"><mxGeometry x="420" y="30" width="80" height="80" as="geometry"/></mxCell>
<mxCell id="d" value="Errore" style="rounded=1;fillColor=#f8cecc;strokeColor=#b85450;" vertex="1" parent="1"><mxGeometry x="230" y="180" width="120" height="50" as="geometry"/></mxCell>
<mxCell id="e1" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="e2" value="ok" edge="1" parent="1" source="b" target="c"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="e3" value="no" style="dashed=1;" edge="1" parent="1" source="b" target="d"><mxGeometry relative="1" as="geometry"/></mxCell>
</root></mxGraphModel>"""
comp = zlib.compressobj(9, zlib.DEFLATED, -15)
packed = base64.b64encode(comp.compress(urllib.parse.quote(model, safe="").encode()) + comp.flush()).decode()
w("flusso.drawio", f'<mxfile host="drawio" version="24.0.0"><diagram id="p1" name="Flusso">{packed}</diagram>'
  f'<diagram id="p2" name="Non compresso">{model}</diagram></mxfile>')
scene = {"type": "excalidraw", "version": 2, "source": "https://excalidraw.com", "elements": [
    {"id": "r", "type": "rectangle", "x": 0, "y": 0, "width": 220, "height": 110, "angle": 0, "strokeColor": "#1e1e1e", "backgroundColor": "#a5d8ff", "fillStyle": "hachure", "strokeWidth": 2, "strokeStyle": "solid", "opacity": 100, "roundness": {"type": 3}, "isDeleted": False},
    {"id": "t", "type": "text", "x": 30, "y": 35, "width": 160, "height": 25, "angle": 0, "strokeColor": "#1e1e1e", "backgroundColor": "transparent", "text": "Idea di prova", "fontSize": 24, "fontFamily": 1, "textAlign": "left", "opacity": 100, "isDeleted": False},
    {"id": "e", "type": "ellipse", "x": 320, "y": 10, "width": 140, "height": 90, "angle": 0, "strokeColor": "#e03131", "backgroundColor": "#ffc9c9", "fillStyle": "solid", "strokeWidth": 2, "opacity": 100, "isDeleted": False},
    {"id": "a", "type": "arrow", "x": 225, "y": 55, "width": 90, "height": 0, "angle": 0, "strokeColor": "#1e1e1e", "backgroundColor": "transparent", "strokeWidth": 2, "points": [[0, 0], [90, 0]], "endArrowhead": "arrow", "opacity": 100, "isDeleted": False},
    {"id": "f", "type": "freedraw", "x": 40, "y": 150, "width": 200, "height": 40, "angle": 0, "strokeColor": "#2f9e44", "backgroundColor": "transparent", "strokeWidth": 2, "points": [[0, 0], [40, 30], [80, 5], [120, 35], [160, 0], [200, 25]], "opacity": 100, "isDeleted": False},
    {"id": "x", "type": "rectangle", "x": 900, "y": 900, "width": 10, "height": 10, "isDeleted": True}],
  "appState": {"viewBackgroundColor": "#ffffff"}, "files": {}}
w("lavagna.excalidraw", json.dumps(scene, indent=1))
lz = subprocess.run(["node", "-e", "const L=require('/tmp/npmchk/lz/package/libs/lz-string.js');process.stdout.write(L.compressToBase64(require('fs').readFileSync(0,'utf8')))"],
                    input=json.dumps(scene), capture_output=True, text=True, check=True).stdout
w("lavagna.excalidraw.md", "---\n\nexcalidraw-plugin: parsed\ntags: [excalidraw]\n\n---\n==⚠  Switch to EXCALIDRAW VIEW in the MORE OPTIONS menu of this document. ⚠==\n\n\n# Text Elements\nIdea di prova ^t\n\n%%\n# Drawing\n```compressed-json\n"
  + "\n".join(lz[i:i + 256] for i in range(0, len(lz), 256)) + "\n```\n%%\n")

# ------------------------------------------------------------------ collegamenti, torrent
w("sito.url", "[InternetShortcut]\r\nURL=https://example.org/pagina?da=151viewer\r\nIconIndex=0\r\n")
w("pagina.webloc", '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict><key>URL</key><string>https://example.com/articolo</string></dict></plist>\n')
w("editor.desktop", "[Desktop Entry]\nType=Application\nName=Editor di prova\nComment=Un editor inventato\nExec=editor %F\nIcon=editor\nCategories=Utility;TextEditor;\n")
def benc(x):
    if isinstance(x, int): return b"i%de" % x
    if isinstance(x, str): x = x.encode()
    if isinstance(x, bytes): return b"%d:%s" % (len(x), x)
    if isinstance(x, list): return b"l" + b"".join(benc(i) for i in x) + b"e"
    if isinstance(x, dict): return b"d" + b"".join(benc(k) + benc(v) for k, v in sorted(x.items())) + b"e"
info = {"name": "raccolta-libri-pubblico-dominio", "piece length": 262144, "pieces": hashlib.sha1(b"x").digest() * 12,
        "files": [{"length": 1048576, "path": ["libri", "divina-commedia.epub"]}, {"length": 2097152, "path": ["libri", "promessi-sposi.epub"]}, {"length": 4096, "path": ["LEGGIMI.txt"]}]}
open(p("raccolta.torrent"), "wb").write(benc({"announce": "udp://tracker.example.org:1337/announce", "announce-list": [["udp://tracker.example.org:1337/announce"], ["https://tracker.example.net/announce"]],
    "comment": "Torrent di prova (non esiste)", "created by": "151 Viewer make-samples", "creation date": 1790000000, "info": info}))

# ------------------------------------------------------------------ certificati, firme, fattura elettronica
shutil.copy("/etc/ssl/certs/ISRG_Root_X1.pem", p("isrg-root-x1.pem"))
key, crt = os.path.join(TMP, "k.pem"), os.path.join(TMP, "c.pem")
run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", crt, "-days", "3650",
    "-subj", "/C=IT/O=Prova 151/CN=Mario Rossi/serialNumber=TINIT-RSSMRA80A01H501U", "-addext", "keyUsage=critical,digitalSignature,nonRepudiation", stderr=subprocess.DEVNULL)
shutil.copy(crt, p("firmatario.crt"))
fattura = """<?xml version="1.0" encoding="UTF-8"?>
<p:FatturaElettronica versione="FPR12" xmlns:p="http://ivaservizi.agenziaentrate.gov.it/docs/xsd/fatture/v1.2">
<FatturaElettronicaHeader>
<DatiTrasmissione><IdTrasmittente><IdPaese>IT</IdPaese><IdCodice>01234567890</IdCodice></IdTrasmittente><ProgressivoInvio>00001</ProgressivoInvio><FormatoTrasmissione>FPR12</FormatoTrasmissione><CodiceDestinatario>0000000</CodiceDestinatario></DatiTrasmissione>
<CedentePrestatore><DatiAnagrafici><IdFiscaleIVA><IdPaese>IT</IdPaese><IdCodice>01234567890</IdCodice></IdFiscaleIVA><Anagrafica><Denominazione>Officina di Prova S.r.l.</Denominazione></Anagrafica><RegimeFiscale>RF01</RegimeFiscale></DatiAnagrafici>
<Sede><Indirizzo>Via Roma</Indirizzo><NumeroCivico>1</NumeroCivico><CAP>10100</CAP><Comune>Torino</Comune><Provincia>TO</Provincia><Nazione>IT</Nazione></Sede></CedentePrestatore>
<CessionarioCommittente><DatiAnagrafici><CodiceFiscale>RSSMRA80A01H501U</CodiceFiscale><Anagrafica><Nome>Mario</Nome><Cognome>Rossi</Cognome></Anagrafica></DatiAnagrafici>
<Sede><Indirizzo>Corso Italia 10</Indirizzo><CAP>20100</CAP><Comune>Milano</Comune><Provincia>MI</Provincia><Nazione>IT</Nazione></Sede></CessionarioCommittente>
</FatturaElettronicaHeader>
<FatturaElettronicaBody>
<DatiGenerali><DatiGeneraliDocumento><TipoDocumento>TD01</TipoDocumento><Divisa>EUR</Divisa><Data>2026-09-15</Data><Numero>42/2026</Numero><ImportoTotaleDocumento>183.00</ImportoTotaleDocumento><Causale>Riparazione bicicletta</Causale></DatiGeneraliDocumento></DatiGenerali>
<DatiBeniServizi>
<DettaglioLinee><NumeroLinea>1</NumeroLinea><Descrizione>Manodopera</Descrizione><Quantita>2.00</Quantita><UnitaMisura>ore</UnitaMisura><PrezzoUnitario>40.00</PrezzoUnitario><PrezzoTotale>80.00</PrezzoTotale><AliquotaIVA>22.00</AliquotaIVA></DettaglioLinee>
<DettaglioLinee><NumeroLinea>2</NumeroLinea><Descrizione>Camera d'aria e copertone</Descrizione><Quantita>1.00</Quantita><PrezzoUnitario>70.00</PrezzoUnitario><PrezzoTotale>70.00</PrezzoTotale><AliquotaIVA>22.00</AliquotaIVA></DettaglioLinee>
<DatiRiepilogo><AliquotaIVA>22.00</AliquotaIVA><ImponibileImporto>150.00</ImponibileImporto><Imposta>33.00</Imposta><EsigibilitaIVA>I</EsigibilitaIVA></DatiRiepilogo>
</DatiBeniServizi>
<DatiPagamento><CondizioniPagamento>TP02</CondizioniPagamento><DettaglioPagamento><ModalitaPagamento>MP05</ModalitaPagamento><DataScadenzaPagamento>2026-10-15</DataScadenzaPagamento><ImportoPagamento>183.00</ImportoPagamento><IBAN>IT60X0542811101000000123456</IBAN></DettaglioPagamento></DatiPagamento>
<Allegati><NomeAttachment>nota.txt</NomeAttachment><Attachment>""" + base64.b64encode("Grazie per aver scelto la nostra officina.\n".encode()).decode() + """</Attachment></Allegati>
</FatturaElettronicaBody>
</p:FatturaElettronica>
"""
w("IT01234567890_00001.xml", fattura)
for src, dst in [(p("IT01234567890_00001.xml"), p("IT01234567890_00001.xml.p7m")), (os.path.join(TMP, "doc.pdf"), p("contratto.pdf.p7m"))]:
    run("openssl", "cms", "-sign", "-binary", "-nodetach", "-outform", "DER", "-md", "sha256", "-in", src, "-signer", crt, "-inkey", key, "-out", dst)
print("campioni v5 in", OUT)
for n in sorted(os.listdir(OUT)): print(f"  {os.path.getsize(p(n)):>9}  {n}")
