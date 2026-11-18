#!/usr/bin/env python3
"""Banco di prova su PC delle pagine in app/src/main/assets/viewer.

Imita ESATTAMENTE le rotte che l'app serve alla WebView (DocServer.kt):
  /assets/...                          -> app/src/main/assets/...
  /file/<nome>/doc[?max=N]             -> file di prova, intero (troncato a N byte)
  /file/<nome>/range?start=A&end=B     -> byte [A, B)
dove <nome> e' un file dentro tools/web-test/out/ (generati da make-fixtures.sh).

Uso:  python3 tools/web-test/serve.py 8151
poi:  http://127.0.0.1:8151/assets/viewer/pdf.html?src=/file/big.pdf/&size=<byte>&seek=1
Serve anche a contare le richieste (/stats), per provare che i PDF grandi si leggono a pezzi.
"""
import json, mimetypes, os, sys, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
# campioni sintetici della v3 + cartelle extra (es. note vere copiate in /tmp: VIEWER_EXTRA=/tmp/vault-samples)
DIRS = [OUT, os.path.join(ROOT, "tools", "samples"), os.path.join(ROOT, "tools", "samples", "v5"), os.path.join(ROOT, "tools", "samples-real")] + [d for d in os.environ.get("VIEWER_EXTRA", "").split(":") if d]
def find(name):
    for d in DIRS:
        f = os.path.join(d, name)
        if os.path.isfile(f): return f
    return None
CSP_HTML = {
    "0": "default-src 'self' data: blob:; script-src 'none'; style-src 'self' 'unsafe-inline' data:; img-src 'self' data: blob:; connect-src 'none'; object-src 'none'; form-action 'none'",
    "1": "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: data:; style-src 'self' 'unsafe-inline' data:; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; form-action 'none'",
}
STATS = {"doc": 0, "range": 0, "range_bytes": 0, "doc_bytes": 0}
LOCK = threading.Lock()
mimetypes.add_type("text/javascript", ".mjs")
mimetypes.add_type("application/wasm", ".wasm")

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body=b"", ctype="text/plain", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        path = unquote(u.path)
        if path == "/stats":
            with LOCK:
                return self.send(200, json.dumps(STATS).encode(), "application/json")
        if path == "/stats/reset":
            with LOCK:
                for k in STATS: STATS[k] = 0
            return self.send(200, b"ok")
        if path.startswith("/assets/"):
            f = os.path.normpath(os.path.join(ASSETS, path[len("/assets/"):]))
            if not f.startswith(ASSETS) or not os.path.isfile(f):
                return self.send(404)
            ctype = mimetypes.guess_type(f)[0] or "application/octet-stream"
            return self.send(200, open(f, "rb").read(), ctype)
        # pagina HTML "vera" (nell'app: origine separata https://pagina.androidplatform.net/<token>/<nome>)
        if path.startswith("/h0/") or path.startswith("/h1/"):
            f = find(os.path.basename(path[4:]))
            if not f: return self.send(404)
            ctype = mimetypes.guess_type(f)[0] or "application/octet-stream"
            return self.send(200, open(f, "rb").read(), ctype, {"Content-Security-Policy": CSP_HTML[path[2]]} if ctype == "text/html" else None)
        if path.startswith("/file/"):
            parts = path.split("/")  # ['', 'file', name, op]
            if len(parts) != 4:
                return self.send(404)
            name, op = parts[2], parts[3]
            if op == "rel":  # risorsa accanto al file (immagini del Markdown)
                rel = q.get("p", [""])[0]
                f = find(os.path.basename(rel)) if ".." not in rel else None
                if not f: return self.send(404)
                return self.send(200, open(f, "rb").read(), mimetypes.guess_type(f)[0] or "application/octet-stream")
            f = find(os.path.basename(name))
            if not f:
                return self.send(404)
            ctype = mimetypes.guess_type(f)[0] or "application/octet-stream"
            if name.endswith(".svg"): ctype = "image/svg+xml"
            if op == "doc":
                data = open(f, "rb").read()
                if "max" in q: data = data[: int(q["max"][0])]
                with LOCK:
                    STATS["doc"] += 1; STATS["doc_bytes"] += len(data)
                return self.send(200, data, ctype)
            if op == "range":
                a, b = int(q["start"][0]), int(q["end"][0])
                with open(f, "rb") as fh:
                    fh.seek(a); data = fh.read(b - a)
                with LOCK:
                    STATS["range"] += 1; STATS["range_bytes"] += len(data)
                return self.send(200, data, "application/octet-stream")
        return self.send(404)

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8151
    print(f"http://127.0.0.1:{port}/  (assets: {ASSETS})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
