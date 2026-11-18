#!/usr/bin/env python3
"""Campioni SINTETICI (nessun dato personale) per ogni formato della v3 -> tools/samples/.
Office e OpenDocument sono file veri: si scrivono in ODF "flat" e li converte LibreOffice headless.
Uso: python3 tools/web-test/make-samples.py      (serve soffice e ffmpeg nel PATH)
"""
import base64, os, shutil, struct, subprocess, sys, tempfile, zipfile, zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "tools", "samples")
os.makedirs(OUT, exist_ok=True)

def png(w, h, fn):
    raw = b"".join(b"\x00" + b"".join(bytes(fn(x, y)) for x in range(w)) for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

def pix(x, y):
    inside = (x - 100) ** 2 + (y - 60) ** 2 < 40 ** 2
    return (240, 180, 40) if inside else (30 + x // 2, 90 + y, 200 - x // 3)
IMG = png(200, 120, pix)
IMG_B64 = base64.b64encode(IMG).decode()
open(os.path.join(OUT, "immagine.png"), "wb").write(IMG)

def w(name, text, mode="w"):
    with open(os.path.join(OUT, name), mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f:
        f.write(text)

# ------------------------------------------------------------------ Markdown (tutte le estensioni Obsidian)
w("nota-obsidian.md", r"""---
title: Nota di prova
tags:
  - prova
  - viewer
creato: 2026-09-30
completata: false
alias: [Prova, Test]
---

# Nota di prova per 151 Viewer

Testo con **grassetto**, *corsivo*, ***entrambi***, ~~barrato~~, `codice in linea` e ==evidenziato==.
Un link esterno [Obsidian](https://obsidian.md), un [[Wikilink]], un [[Altra nota|alias mostrato]] e un tag #progetto/viewer.
Nota a piè di pagina[^1] e un'altra[^lunga].

## Liste

- primo punto
- secondo punto
  - annidato
1. uno
2. due

- [ ] da fare
- [x] fatto
- [/] a metà
- [-] annullato

## Tabella

| Formato | Libreria | Licenza |
|:--|:-:|--:|
| Markdown | markdown-it | MIT |
| Excel | SheetJS | Apache-2.0 |
| Word | docx-preview | Apache-2.0 |

## Callout

> [!note] Nota
> Un callout semplice con **markdown** dentro.

> [!warning]- Attenzione (pieghevole, chiuso)
> Contenuto nascosto finché non lo apri.

> [!tip]+ Suggerimento (pieghevole, aperto)
> Aperto di default.

> [!danger]
> Senza titolo: prende il nome del tipo.

> Citazione normale
> su due righe.

## Codice

```python
def saluta(nome: str) -> str:
    # commento
    return f"Ciao {nome}!"
```

```js
const x = [1, 2, 3].map(n => n * 2);
console.log(x);
```

## Matematica

In linea: $e^{i\pi} + 1 = 0$ e $\sqrt{a^2+b^2}$.

$$
\int_0^1 x^2\,dx = \frac{1}{3}
$$

## Diagramma

```mermaid
graph LR
  A[Apri file] --> B{Formato?}
  B -->|Markdown| C[markdown-it]
  B -->|Word| D[docx-preview]
```

## Immagini

![Immagine accanto al file](immagine.png)

![[immagine.png|120]]

Immagine remota (bloccata, niente rete): ![remota](https://example.com/x.png)

%%commento Obsidian: non si vede%%

---

Fine. <script>alert('xss')</script> <img src=x onerror="alert(1)">

[^1]: La prima nota.
[^lunga]: Una nota più lunga, con *corsivo*.
""")

# ------------------------------------------------------------------ HTML con CSS e script accanto
w("pagina.html", """<!doctype html>
<html lang="it"><head><meta charset="utf-8"><title>Pagina di prova</title>
<link rel="stylesheet" href="pagina.css">
<style>.inline { color: #0a7; }</style>
<link rel="stylesheet" href="https://example.com/remoto.css">
</head><body>
<h1>Pagina HTML di prova</h1>
<p class="inline">Stile in linea (verde).</p>
<p class="esterno">Stile dal file pagina.css accanto (se raggiungibile: sfondo giallo).</p>
<p>Immagine accanto: <img src="immagine.png" alt="immagine locale" width="100"></p>
<p>Immagine remota: <img src="https://example.com/remota.png" alt="remota bloccata" width="50"></p>
<p id="js">JavaScript SPENTO (se leggi questo, lo script non è girato).</p>
<script>document.getElementById('js').textContent = 'JavaScript ACCESO: lo script della pagina è girato.';
fetch('https://example.com/spia').then(()=>document.title='RETE!').catch(()=>{});</script>
<table border="1"><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>
<p><a href="https://example.com">link esterno</a></p>
</body></html>
""")
w("pagina.css", ".esterno { background: #ffe066; padding: 4px; } body { font-family: serif; max-width: 40em; margin: 1em auto; } h1 { color: #b03; }")

# ------------------------------------------------------------------ testo "strutturato"
w("config.ini", "; commento\n[server]\nhost = 127.0.0.1\nport = 8080\n\n[utente]\nnome = \"Mario\"\nattivo = true\n")
w("config.toml", "# TOML\ntitle = \"Esempio\"\n\n[database]\nserver = \"192.168.1.1\"\nports = [ 8000, 8001 ]\nenabled = true\n")
w("config.yaml", "# YAML\nnome: Esempio\nversione: 1.2\nelenco:\n  - uno\n  - due\nannidato:\n  chiave: valore\n  numero: 42\n")
w("app.log", "\n".join([
    "2026-09-30 10:00:01 INFO  avvio servizio",
    "2026-09-30 10:00:02 DEBUG config letta da /etc/app.conf",
    "2026-09-30 10:00:05 WARN  disco quasi pieno (91%)",
    "2026-09-30 10:00:09 ERROR connessione rifiutata: timeout",
    "2026-09-30 10:00:10 INFO  nuovo tentativo",
]) + "\n")
w("sottotitoli.srt", "1\n00:00:01,000 --> 00:00:03,500\nCiao, questa è una prova.\n\n2\n00:00:04,000 --> 00:00:06,000\nSeconda riga\nsu due righe.\n\n3\n00:01:10,250 --> 00:01:12,000\n<i>Corsivo</i> finale.\n")
w("tabella.csv", "Nome;Città;Importo\nAnna;Bologna;12,50\nLuca;Milano;7,00\n\"Rossi, Maria\";Roma;100,00\n")
w("contatto.vcf", "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Rossi;Maria;;;\r\nFN:Maria Rossi\r\nORG:Esempio Srl\r\nTITLE:Responsabile\r\nTEL;TYPE=CELL:+39 333 000 0000\r\nTEL;TYPE=WORK:+39 051 000000\r\nEMAIL:maria@example.com\r\nADR;TYPE=WORK:;;Via Roma 1;Bologna;;40100;Italia\r\nBDAY:1990-05-12\r\nNOTE:Contatto finto di prova\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:Luca Bianchi\r\nTEL:+39 347 111 1111\r\nEND:VCARD\r\n")
w("evento.ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//151//Viewer//IT\r\nBEGIN:VEVENT\r\nUID:1@example\r\nDTSTART:20261015T090000Z\r\nDTEND:20261015T103000Z\r\nSUMMARY:Riunione di prova\r\nLOCATION:Sala A\\, primo piano\r\nDESCRIPTION:Ordine del giorno:\\n1. uno\\n2. due\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:2@example\r\nDTSTART;VALUE=DATE:20261101\r\nSUMMARY:Giornata intera\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")

# ------------------------------------------------------------------ EML (multipart: testo + html + immagine cid + allegato)
eml = f"""From: =?UTF-8?Q?Mario_Rossi?= <mario@example.com>
To: Anna <anna@example.com>
Cc: luca@example.com
Subject: =?UTF-8?B?{base64.b64encode('Prova email con allegato — è ok'.encode()).decode()}?=
Date: Tue, 29 Sep 2026 18:30:00 +0200
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="MIX"

--MIX
Content-Type: multipart/alternative; boundary="ALT"

--ALT
Content-Type: text/plain; charset=utf-8
Content-Transfer-Encoding: quoted-printable

Ciao Anna,=0Aquesta =C3=A8 la versione in testo.

--ALT
Content-Type: multipart/related; boundary="REL"

--REL
Content-Type: text/html; charset=utf-8
Content-Transfer-Encoding: quoted-printable

<html><body><p>Ciao <b>Anna</b>,</p><p>questa =C3=A8 la versione <i>HTML</i> con immagine:</p><p><img src=3D"cid:img1" width=3D"120"></p><p><img src=3D"https://tracker.example.com/pixel.gif"></p><script>alert(1)</script></body></html>

--REL
Content-Type: image/png
Content-Transfer-Encoding: base64
Content-ID: <img1>

{base64.encodebytes(IMG).decode()}
--REL--

--ALT--

--MIX
Content-Type: text/plain; charset=utf-8; name="allegato.txt"
Content-Disposition: attachment; filename="allegato.txt"
Content-Transfer-Encoding: base64

{base64.encodebytes('Questo è l\'allegato di testo.\n'.encode()).decode()}
--MIX--
"""
w("email.eml", eml.replace("\n", "\r\n"))

# MHTML (come lo salva Chrome)
mht = f"""From: <Saved by Blink>
Subject: Pagina salvata
MIME-Version: 1.0
Content-Type: multipart/related; type="text/html"; boundary="----MultipartBoundary--X"

------MultipartBoundary--X
Content-Type: text/html
Content-ID: <frame-1@mhtml.blink>
Content-Transfer-Encoding: quoted-printable
Content-Location: https://example.com/pagina

<html><head><link rel=3D"stylesheet" href=3D"https://example.com/s.css"></head><body><h1>Pagina MHTML</h1><p class=3D"x">Stile dal CSS salvato dentro l'archivio.</p><img src=3D"https://example.com/logo.png" width=3D"100"></body></html>
------MultipartBoundary--X
Content-Type: text/css
Content-Transfer-Encoding: quoted-printable
Content-Location: https://example.com/s.css

.x {{ background: #cfe; padding: 6px; }} h1 {{ color: #06c; }}
------MultipartBoundary--X
Content-Type: image/png
Content-Transfer-Encoding: base64
Content-Location: https://example.com/logo.png

{base64.encodebytes(IMG).decode()}
------MultipartBoundary--X--
"""
w("pagina.mhtml", mht.replace("\n", "\r\n"))

# ------------------------------------------------------------------ EPUB 3 (2 capitoli + immagine + indice)
def epub(path):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>', zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/content.opf", '''<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:151-prova</dc:identifier><dc:title>Libro di prova</dc:title><dc:creator>151 Viewer</dc:creator><dc:language>it</dc:language></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="testo/cap1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="testo/cap2.xhtml" media-type="application/xhtml+xml"/><item id="css" href="stile.css" media-type="text/css"/><item id="img" href="img/figura.png" media-type="image/png"/></manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>''', zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/nav.xhtml", '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="testo/cap1.xhtml">Capitolo 1 — L\'inizio</a></li><li><a href="testo/cap2.xhtml">Capitolo 2 — La fine</a></li></ol></nav></body></html>', zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/stile.css", "h1 { color: #8a2be2; } p.primo::first-letter { font-size: 2em; }", zipfile.ZIP_DEFLATED)
        para = " ".join(["Questo è un paragrafo di prova del libro, abbastanza lungo da andare a capo più volte."] * 3)
        z.writestr("OEBPS/testo/cap1.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cap 1</title><link rel="stylesheet" href="../stile.css"/></head><body><h1>Capitolo 1 — L\'inizio</h1><p class="primo">{para}</p><p><img src="../img/figura.png" alt="figura"/></p><p><a href="cap2.xhtml">Vai al capitolo 2</a></p><script>document.body.innerHTML="SCRIPT"</script></body></html>', zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/testo/cap2.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cap 2</title><link rel="stylesheet" href="../stile.css"/></head><body><h1>Capitolo 2 — La fine</h1><p><em>Corsivo</em> e <strong>grassetto</strong>.</p><p>{para}</p></body></html>', zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/img/figura.png", IMG)
epub(os.path.join(OUT, "libro.epub"))

# ------------------------------------------------------------------ ODF flat -> LibreOffice
FODT = f'''<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.text">
<office:styles>
 <style:style style:name="Standard" style:family="paragraph"><style:text-properties fo:font-size="11pt" style:font-name="Liberation Serif"/></style:style>
 <style:style style:name="Heading_20_1" style:display-name="Heading 1" style:family="paragraph" style:default-outline-level="1"><style:text-properties fo:font-size="20pt" fo:font-weight="bold" fo:color="#1f4e79"/></style:style>
 <style:style style:name="Heading_20_2" style:display-name="Heading 2" style:family="paragraph" style:default-outline-level="2"><style:text-properties fo:font-size="15pt" fo:font-weight="bold" fo:color="#2e75b6"/></style:style>
</office:styles>
<office:automatic-styles>
 <style:style style:name="B" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
 <style:style style:name="I" style:family="text"><style:text-properties fo:font-style="italic"/></style:style>
 <style:style style:name="U" style:family="text"><style:text-properties style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"/></style:style>
 <style:style style:name="R" style:family="text"><style:text-properties fo:color="#c00000" fo:background-color="#ffff00"/></style:style>
 <style:style style:name="C" style:family="paragraph" style:parent-style-name="Standard"><style:paragraph-properties fo:text-align="center"/></style:style>
 <style:style style:name="Tab" style:family="table"><style:table-properties style:width="16cm" table:align="margins"/></style:style>
 <style:style style:name="TH" style:family="table-cell"><style:table-cell-properties fo:background-color="#d9e2f3" fo:border="0.5pt solid #000000" fo:padding="0.1cm"/></style:style>
 <style:style style:name="TD" style:family="table-cell"><style:table-cell-properties fo:border="0.5pt solid #000000" fo:padding="0.1cm"/></style:style>
 <text:list-style style:name="L1"><text:list-level-style-bullet text:level="1" text:bullet-char="•"><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" fo:text-indent="-0.635cm" fo:margin-left="1.27cm"/></style:list-level-properties></text:list-level-style-bullet></text:list-style>
 <text:list-style style:name="L2"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" fo:text-indent="-0.635cm" fo:margin-left="1.27cm"/></style:list-level-properties></text:list-level-style-number></text:list-style>
 <style:style style:name="fr1" style:family="graphic"><style:graphic-properties style:wrap="none" style:vertical-pos="top" style:horizontal-pos="center" style:horizontal-rel="paragraph"/></style:style>
</office:automatic-styles>
<office:body><office:text>
 <text:h text:style-name="Heading_20_1" text:outline-level="1">Relazione di prova</text:h>
 <text:p text:style-name="Standard">Paragrafo con <text:span text:style-name="B">grassetto</text:span>, <text:span text:style-name="I">corsivo</text:span>, <text:span text:style-name="U">sottolineato</text:span> e <text:span text:style-name="R">rosso evidenziato</text:span>. Nota a piè di pagina<text:note text:id="n1" text:note-class="footnote"><text:note-citation>1</text:note-citation><text:note-body><text:p>Questa è la nota.</text:p></text:note-body></text:note>.</text:p>
 <text:h text:style-name="Heading_20_2" text:outline-level="2">Elenchi</text:h>
 <text:list text:style-name="L1"><text:list-item><text:p>Primo punto</text:p></text:list-item><text:list-item><text:p>Secondo punto</text:p></text:list-item></text:list>
 <text:list text:style-name="L2"><text:list-item><text:p>Passo uno</text:p></text:list-item><text:list-item><text:p>Passo due</text:p></text:list-item></text:list>
 <text:h text:style-name="Heading_20_2" text:outline-level="2">Tabella</text:h>
 <table:table table:name="T1" table:style-name="Tab"><table:table-column table:number-columns-repeated="3"/>
  <table:table-row><table:table-cell table:style-name="TH"><text:p><text:span text:style-name="B">Prodotto</text:span></text:p></table:table-cell><table:table-cell table:style-name="TH"><text:p><text:span text:style-name="B">Quantità</text:span></text:p></table:table-cell><table:table-cell table:style-name="TH"><text:p><text:span text:style-name="B">Prezzo</text:span></text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell table:style-name="TD"><text:p>Mele</text:p></table:table-cell><table:table-cell table:style-name="TD"><text:p>10</text:p></table:table-cell><table:table-cell table:style-name="TD"><text:p>2,50 €</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell table:style-name="TD"><text:p>Pere</text:p></table:table-cell><table:table-cell table:style-name="TD"><text:p>4</text:p></table:table-cell><table:table-cell table:style-name="TD"><text:p>3,10 €</text:p></table:table-cell></table:table-row>
 </table:table>
 <text:h text:style-name="Heading_20_2" text:outline-level="2">Immagine</text:h>
 <text:p text:style-name="C"><draw:frame draw:style-name="fr1" draw:name="img1" text:anchor-type="as-char" svg:width="6cm" svg:height="3.6cm"><draw:image><office:binary-data>{IMG_B64}</office:binary-data></draw:image></draw:frame></text:p>
 <text:p text:style-name="C">Didascalia centrata.</text:p>
</office:text></office:body></office:document>
'''

FODS = '''<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" xmlns:of="urn:oasis:names:tc:opendocument:xmlns:of:1.2" office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.spreadsheet">
<office:automatic-styles>
 <number:currency-style style:name="EUR"><number:number number:decimal-places="2" number:min-integer-digits="1" number:grouping="true"/><number:text> </number:text><number:currency-symbol>€</number:currency-symbol></number:currency-style>
 <number:date-style style:name="D"><number:day number:style="long"/><number:text>/</number:text><number:month number:style="long"/><number:text>/</number:text><number:year number:style="long"/></number:date-style>
 <style:style style:name="H" style:family="table-cell"><style:table-cell-properties fo:background-color="#ffd966"/><style:text-properties fo:font-weight="bold"/></style:style>
 <style:style style:name="E" style:family="table-cell" style:data-style-name="EUR"/>
 <style:style style:name="DT" style:family="table-cell" style:data-style-name="D"/>
</office:automatic-styles>
<office:body><office:spreadsheet>
 <table:table table:name="Vendite">
  <table:table-row><table:table-cell table:style-name="H" office:value-type="string"><text:p>Mese</text:p></table:table-cell><table:table-cell table:style-name="H" office:value-type="string"><text:p>Pezzi</text:p></table:table-cell><table:table-cell table:style-name="H" office:value-type="string"><text:p>Incasso</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell office:value-type="string"><text:p>Gennaio</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="120"><text:p>120</text:p></table:table-cell><table:table-cell table:style-name="E" office:value-type="currency" office:currency="EUR" office:value="1500.5"><text:p>1.500,50 €</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell office:value-type="string"><text:p>Febbraio</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="95"><text:p>95</text:p></table:table-cell><table:table-cell table:style-name="E" office:value-type="currency" office:currency="EUR" office:value="1210"><text:p>1.210,00 €</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell table:style-name="H" office:value-type="string"><text:p>Totale</text:p></table:table-cell><table:table-cell table:formula="of:=SUM([.B2:.B3])" office:value-type="float" office:value="215"><text:p>215</text:p></table:table-cell><table:table-cell table:style-name="E" table:formula="of:=SUM([.C2:.C3])" office:value-type="currency" office:currency="EUR" office:value="2710.5"><text:p>2.710,50 €</text:p></table:table-cell></table:table-row>
 </table:table>
 <table:table table:name="Clienti">
  <table:table-row><table:table-cell table:style-name="H" office:value-type="string"><text:p>Nome</text:p></table:table-cell><table:table-cell table:style-name="H" office:value-type="string"><text:p>Città</text:p></table:table-cell><table:table-cell table:style-name="H" office:value-type="string"><text:p>Dal</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell office:value-type="string"><text:p>Anna</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p>Bologna</text:p></table:table-cell><table:table-cell table:style-name="DT" office:value-type="date" office:date-value="2024-03-15"><text:p>15/03/2024</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell office:value-type="string"><text:p>Luca</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p>Milano</text:p></table:table-cell><table:table-cell table:style-name="DT" office:value-type="date" office:date-value="2025-11-02"><text:p>02/11/2025</text:p></table:table-cell></table:table-row>
 </table:table>
 <table:table table:name="Grande">
''' + "".join(
    '  <table:table-row>' + "".join(f'<table:table-cell office:value-type="float" office:value="{r*c}"><text:p>{r*c}</text:p></table:table-cell>' for c in range(1, 16)) + '</table:table-row>\n'
    for r in range(1, 61)) + '''
 </table:table>
</office:spreadsheet></office:body></office:document>
'''

FODP = f'''<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.presentation">
<office:styles>
 <style:style style:name="standard" style:family="graphic"><style:graphic-properties draw:fill="none" draw:stroke="none"/></style:style>
</office:styles>
<office:automatic-styles>
 <style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="25.4cm" fo:page-height="14.29cm" style:print-orientation="landscape"/></style:page-layout>
 <style:style style:name="dp1" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#f4f7fb"/></style:style>
 <style:style style:name="dp2" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#1f2a44"/></style:style>
 <style:style style:name="gr1" style:family="graphic" style:parent-style-name="standard"><style:graphic-properties draw:fill="solid" draw:fill-color="#2e75b6" draw:stroke="none"/></style:style>
 <style:style style:name="Pt" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/><style:text-properties fo:font-size="40pt" fo:font-weight="bold" fo:color="#1f2a44"/></style:style>
 <style:style style:name="Ps" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/><style:text-properties fo:font-size="22pt" fo:color="#555555"/></style:style>
 <style:style style:name="Pb" style:family="paragraph"><style:text-properties fo:font-size="22pt" fo:color="#222222"/></style:style>
 <style:style style:name="Pw" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/><style:text-properties fo:font-size="26pt" fo:color="#ffffff" fo:font-weight="bold"/></style:style>
 <style:style style:name="Ph" style:family="paragraph"><style:text-properties fo:font-size="32pt" fo:font-weight="bold" fo:color="#2e75b6"/></style:style>
</office:automatic-styles>
<office:master-styles><style:master-page style:name="Default" style:page-layout-name="PM1"/></office:master-styles>
<office:body><office:presentation>
 <draw:page draw:name="Titolo" draw:style-name="dp1" draw:master-page-name="Default">
  <draw:frame svg:x="2cm" svg:y="4cm" svg:width="21.4cm" svg:height="3cm"><draw:text-box><text:p text:style-name="Pt">Presentazione di prova</text:p></draw:text-box></draw:frame>
  <draw:frame svg:x="2cm" svg:y="7.5cm" svg:width="21.4cm" svg:height="2cm"><draw:text-box><text:p text:style-name="Ps">151 Viewer · settembre 2026</text:p></draw:text-box></draw:frame>
 </draw:page>
 <draw:page draw:name="Punti" draw:style-name="dp1" draw:master-page-name="Default">
  <draw:frame svg:x="1.5cm" svg:y="0.8cm" svg:width="22cm" svg:height="2cm"><draw:text-box><text:p text:style-name="Ph">Elenco e immagine</text:p></draw:text-box></draw:frame>
  <draw:frame svg:x="1.5cm" svg:y="3.2cm" svg:width="12cm" svg:height="8cm"><draw:text-box><text:list><text:list-item><text:p text:style-name="Pb">Primo punto</text:p></text:list-item><text:list-item><text:p text:style-name="Pb">Secondo punto</text:p></text:list-item><text:list-item><text:p text:style-name="Pb">Terzo punto</text:p></text:list-item></text:list></draw:text-box></draw:frame>
  <draw:frame svg:x="14.5cm" svg:y="3.5cm" svg:width="9cm" svg:height="5.4cm"><draw:image><office:binary-data>{IMG_B64}</office:binary-data></draw:image></draw:frame>
 </draw:page>
 <draw:page draw:name="Forma" draw:style-name="dp2" draw:master-page-name="Default">
  <draw:custom-shape draw:style-name="gr1" svg:x="5cm" svg:y="4cm" svg:width="15.4cm" svg:height="5cm"><text:p text:style-name="Pw">Rettangolo blu con testo</text:p><draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="rectangle" draw:enhanced-path="M 0 0 L 21600 0 21600 21600 0 21600 0 0 Z N"/></draw:custom-shape>
 </draw:page>
</office:presentation></office:body></office:document>
'''

tmp = tempfile.mkdtemp()
for n, s in (("documento.fodt", FODT), ("foglio.fods", FODS), ("presentazione.fodp", FODP)):
    open(os.path.join(tmp, n), "w", encoding="utf-8").write(s)
prof = "file://" + os.path.join(tmp, "lo-profile")
def soffice(src, fmt):
    subprocess.run(["soffice", f"-env:UserInstallation={prof}", "--headless", "--convert-to", fmt, "--outdir", OUT, src if os.path.isabs(src) else os.path.join(tmp, src)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
soffice("documento.fodt", 'docx:MS Word 2007 XML')
soffice("documento.fodt", "odt:writer8")
soffice("documento.fodt", 'rtf:Rich Text Format')
soffice("documento.fodt", 'doc:MS Word 97')
soffice("foglio.fods", 'xlsx:Calc MS Excel 2007 XML')
soffice(os.path.join(OUT, "foglio.xlsx"), "ods:calc8")  # fods -> ods fa fallire soffice 25.x; da xlsx va
soffice("foglio.fods", 'xls:MS Excel 97')
soffice("presentazione.fodp", 'pptx:Impress MS PowerPoint 2007 XML')
soffice("presentazione.fodp", "odp:impress8")
soffice("presentazione.fodp", 'ppt:MS PowerPoint 97')

# ZIP con dentro cartelle, md, immagine, docx
with zipfile.ZipFile(os.path.join(OUT, "archivio.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(OUT, "nota-obsidian.md"), "note/nota-obsidian.md")
    z.write(os.path.join(OUT, "immagine.png"), "note/immagine.png")
    z.write(os.path.join(OUT, "documento.docx"), "documenti/documento.docx")
    z.write(os.path.join(OUT, "tabella.csv"), "tabella.csv")
    z.writestr("leggimi.txt", "File di testo dentro lo zip.\n")

# font (licenza OFL, dal sistema) + audio/video brevi generati
for cand in ("/usr/share/fonts/liberation/LiberationSans-Regular.ttf", "/usr/share/fonts/TTF/DejaVuSans.ttf"):
    if os.path.exists(cand):
        shutil.copy(cand, os.path.join(OUT, "carattere.ttf")); break
ff = ["ffmpeg", "-y", "-loglevel", "error"]
subprocess.run(ff + ["-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "libmp3lame", "-b:a", "64k", os.path.join(OUT, "suono.mp3")], check=True)
subprocess.run(ff + ["-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=3", "-f", "lavfi", "-i", "sine=frequency=660:duration=3",
                     "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline", "-c:a", "aac", "-shortest", os.path.join(OUT, "video.mp4")], check=True)
shutil.rmtree(tmp, ignore_errors=True)
for f in os.listdir(OUT):
    if f.startswith(".~lock") or f.endswith(".tmp"): os.remove(os.path.join(OUT, f))
for f in sorted(os.listdir(OUT)):
    print(f"{os.path.getsize(os.path.join(OUT, f)):>9}  {f}")
