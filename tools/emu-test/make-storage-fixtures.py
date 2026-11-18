#!/usr/bin/env python3
"""Foto, video e documenti ANONIMI per le prove di 151 Storage (mai file veri).
Le foto hanno EXIF vero (DateTimeOriginal + miniatura EXIF in IFD1, come quelle di una fotocamera),
così si prova sia la data di scatto sia la miniatura letta dai primi 64 KB dal cloud.
Uso: make-storage-fixtures.py <cartella>"""
import io, os, struct, subprocess, sys
from PIL import Image, ImageDraw

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

def exif_app1(dt: str, thumb: bytes) -> bytes:
    # TIFF little endian: IFD0 (ExifIFD pointer) -> ExifIFD (DateTimeOriginal) ; IFD1 (thumbnail JPEG)
    dtb = dt.encode() + b"\0"                     # 20 byte
    le = "<"
    hdr = b"II*\0" + struct.pack(le + "I", 8)
    # IFD0 at 8: 1 entry (0x8769 ExifIFD), next IFD -> IFD1
    ifd0_off = 8; ifd0_size = 2 + 12 + 4
    exif_off = ifd0_off + ifd0_size; exif_size = 2 + 12 + 4
    dt_off = exif_off + exif_size
    ifd1_off = dt_off + len(dtb)
    ifd1_size = 2 + 12 * 3 + 4
    thumb_off = ifd1_off + ifd1_size
    ifd0 = struct.pack(le + "H", 1) + struct.pack(le + "HHII", 0x8769, 4, 1, exif_off) + struct.pack(le + "I", ifd1_off)
    exif = struct.pack(le + "H", 1) + struct.pack(le + "HHII", 0x9003, 2, len(dtb), dt_off) + struct.pack(le + "I", 0)
    ifd1 = struct.pack(le + "H", 3) + struct.pack(le + "HHII", 0x0103, 3, 1, 6) \
        + struct.pack(le + "HHII", 0x0201, 4, 1, thumb_off) + struct.pack(le + "HHII", 0x0202, 4, 1, len(thumb)) + struct.pack(le + "I", 0)
    tiff = hdr + ifd0 + exif + dtb + ifd1 + thumb
    body = b"Exif\0\0" + tiff
    return b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body

def photo(name, dt, color, label, size=(2400, 1800)):
    im = Image.new("RGB", size, color)
    d = ImageDraw.Draw(im)
    for i in range(0, size[0], 120):
        d.line([(i, 0), (i + 600, size[1])], fill=tuple(min(255, c + 40) for c in color), width=18)
    d.rectangle([80, 80, 900, 420], fill=(250, 248, 240))
    d.text((120, 120), label, fill=(20, 20, 20), font_size=140)
    t = im.copy(); t.thumbnail((160, 120))
    tb = io.BytesIO(); t.save(tb, "JPEG", quality=70)
    main = io.BytesIO(); im.save(main, "JPEG", quality=88)
    raw = main.getvalue()
    data = raw[:2] + exif_app1(dt, tb.getvalue()) + raw[2:]
    open(os.path.join(out, name), "wb").write(data)

colors = [(17, 120, 112), (176, 133, 80), (62, 143, 209), (219, 90, 31), (43, 74, 174), (59, 138, 55), (191, 62, 103), (106, 63, 189)]
n = 0
for month, days in ((8, (3, 9, 15, 22)), (9, (1, 5, 12, 18, 25, 29)), (10, (1, 2, 4, 6))):
    for d in days:
        n += 1
        photo(f"IMG_2026{month:02d}{d:02d}_1{n % 10}3000.jpg", f"2026:{month:02d}:{d:02d} 1{n % 10}:30:00", colors[n % len(colors)], f"Prova {n}")
# una foto «WhatsApp»: il nome dice il giorno d'arrivo (più tardi), l'EXIF il giorno vero
photo("IMG-20261005-WA0003.jpg", "2026:09:30 21:15:00", (60, 60, 70), "WA")
# due video di 3 s, con la data di ripresa nei metadati (UTC)
for i, (ts, col) in enumerate((("2026-09-20T10:00:00Z", "teal"), ("2026-10-03T17:45:00Z", "orange"))):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size=640x360:rate=25:duration=3",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-metadata", f"creation_time={ts}", os.path.join(out, f"VID_2026{ts[5:7]}{ts[8:10]}_{i}.mp4")], check=True)
print(f"{n + 1} foto e 2 video in {out}")
