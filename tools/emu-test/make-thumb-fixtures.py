#!/usr/bin/env python3
"""Foto e video ANONIMI per la prova delle anteprime del cloud (run-thumbs.sh). Mai file veri.
Ogni file, guardato DRITTO, ha la metà di sopra ROSSA e quella di sotto BLU: così la prova capisce da sola
se l'anteprima fatta dal telefono è girata giusta. I casi sono quelli che nella 0.6.0 restavano senza anteprima.
Uso: make-thumb-fixtures.py <cartella>"""
import io, os, struct, subprocess, sys, tempfile
from PIL import Image, ImageDraw

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
RED, BLUE = (220, 30, 30), (30, 60, 220)

def upright(w, h):
    """Dritta: sopra rosso, sotto blu, con un po' di disegno (così la JPEG non è minuscola)."""
    im = Image.new("RGB", (w, h), RED)
    d = ImageDraw.Draw(im)
    d.rectangle([0, h // 2, w, h], fill=BLUE)
    for i in range(0, w, 97):
        d.line([(i, 0), (i, h)], fill=(240, 240, 240) if i % 2 else (10, 10, 10), width=3)
    # grana leggera: le foto pesano come quelle vere (qualche MB), i colori restano riconoscibili
    noise = Image.effect_noise((w, h), 90).convert("RGB")
    return Image.blend(im, noise, 0.18)

def stored_for_orientation6(w, h):
    """Orientation 6 = va girata di 90° in senso orario per vederla dritta: si salva girata al contrario."""
    return upright(w, h).rotate(90, expand=True)

def exif_app1(thumb: bytes, orientation: int) -> bytes:
    le = "<"
    hdr = b"II*\0" + struct.pack(le + "I", 8)
    ifd0_off = 8; ifd0_size = 2 + 12 + 4
    ifd1_off = ifd0_off + ifd0_size; ifd1_size = 2 + 12 * 3 + 4
    thumb_off = ifd1_off + ifd1_size
    ifd0 = struct.pack(le + "H", 1) + struct.pack(le + "HHIHH", 0x0112, 3, 1, orientation, 0) + struct.pack(le + "I", ifd1_off)
    ifd1 = struct.pack(le + "H", 3) + struct.pack(le + "HHII", 0x0103, 3, 1, 6) \
        + struct.pack(le + "HHII", 0x0201, 4, 1, thumb_off) + struct.pack(le + "HHII", 0x0202, 4, 1, len(thumb)) + struct.pack(le + "I", 0)
    body = b"Exif\0\0" + hdr + ifd0 + ifd1 + thumb
    return b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body

def jpeg(im, q=88):
    b = io.BytesIO(); im.save(b, "JPEG", quality=q); return b.getvalue()

def with_exif(name, stored, thumb_side, orientation=1, pad_before=0):
    raw = jpeg(stored)
    segs = b""
    if thumb_side:
        t = stored.copy(); t.thumbnail((thumb_side, thumb_side))
        segs = exif_app1(jpeg(t, 75), orientation)
    elif orientation != 1:
        le = "<"
        tiff = b"II*\0" + struct.pack(le + "I", 8) + struct.pack(le + "H", 1) + struct.pack(le + "HHIHH", 0x0112, 3, 1, orientation, 0) + struct.pack(le + "I", 0)
        body = b"Exif\0\0" + tiff
        segs = b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body
    pad = b""
    left = pad_before
    while left > 0:  # segmenti APP2 di riempimento PRIMA dell'EXIF: la miniatura finisce oltre i primi 256 KB
        n = min(left, 65000)
        pad += b"\xff\xe2" + struct.pack(">H", n + 2) + os.urandom(n)
        left -= n
    open(os.path.join(out, name), "wb").write(raw[:2] + pad + segs + raw[2:])

# 1. JPEG con miniatura della fotocamera buona (512 px, come Samsung): basta leggere l'inizio
with_exif("PHOTO_A_exif_ok.jpg", upright(2400, 1800), 512)
# 2. JPEG con miniatura minuscola (160 px, come iPhone): si fa dalla foto vera
with_exif("PHOTO_B_exif_tiny.jpg", upright(2400, 1800), 160)
# 3. JPEG senza nessun EXIF (foto modificate, esportate, scaricate)
with_exif("PHOTO_C_no_exif.jpg", upright(2400, 1800), 0)
# 4. JPEG con la miniatura oltre i primi 256 KB (molti dati del produttore prima)
with_exif("PHOTO_D_exif_far.jpg", upright(2400, 1800), 320, pad_before=320 * 1024)
# 5. foto in piedi (orientation 6) con miniatura: la miniatura va girata
with_exif("PHOTO_E_portrait_exif.jpg", stored_for_orientation6(1800, 2400), 320, orientation=6)
# 6. foto in piedi (orientation 6) senza miniatura: si gira la foto vera
with_exif("PHOTO_F_portrait_noexif.jpg", stored_for_orientation6(1800, 2400), 0, orientation=6)
# 7. PNG grande
upright(3000, 2000).save(os.path.join(out, "PHOTO_G_big.png"))
# 8. HEIC (foto di iPhone e di molti Android recenti)
with tempfile.TemporaryDirectory() as td:
    p = os.path.join(td, "h.png"); upright(2000, 1500).save(p)
    subprocess.run(["heif-enc", "-q", "60", "-o", os.path.join(out, "PHOTO_H_iphone.heic"), p], check=True, stdout=subprocess.DEVNULL)

# video: l'indice IN FONDO (come escono dal telefono senza ottimizzare), all'inizio, e uno girato (in piedi)
def video(name, im, extra_in=(), extra_out=()):
    with tempfile.TemporaryDirectory() as td:
        p = os.path.join(td, "f.png"); im.save(p)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *extra_in, "-loop", "1", "-i", p, "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
                        "-t", "4", "-r", "25", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", *extra_out,
                        os.path.join(out, name)], check=True)

video("VIDEO_I_index_at_end.mp4", upright(1280, 720))
video("VIDEO_J_faststart.mp4", upright(1280, 720), extra_out=("-movflags", "+faststart"))
# in piedi: i fotogrammi salvati girati, e la rotazione scritta nel file (come fa il telefono girato)
video("VIDEO_K_portrait.mp4", upright(720, 1280).rotate(90, expand=True), extra_in=("-display_rotation", "-90"))
video("VIDEO_L_camera.mov", upright(1280, 720))
# video GRANDE (~80 MB, indice in fondo): l'anteprima deve leggerne solo un pezzo, non scaricarlo intero
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=0xdc1e1e:s=1920x540:d=20,format=yuv420p[a];color=c=0x1e3cdc:s=1920x540:d=20[b];[a][b]vstack,noise=alls=40:allf=t",
                "-r", "30", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "32M", "-maxrate", "32M", "-bufsize", "8M", os.path.join(out, "VIDEO_M_big.mp4")], check=True)
print("8 foto e 5 video in", out)
