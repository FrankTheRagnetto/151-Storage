#!/usr/bin/env python3
"""Davanti al cloud finto, il limite di molti server WebDAV/SFTP: al massimo 10 collegamenti insieme per account
(docs.hetzner.com/robot/storage-box/general, letto il 08/10/2026). L'undicesimo viene chiuso subito, come un rifiuto.
Uso: limit-proxy.py <porta-ascolto> <porta-cloud> [massimo=10]   — conta i rifiuti su stderr ("RIFIUTATO n")."""
import socket, sys, threading

listen, target = int(sys.argv[1]), int(sys.argv[2])
cap = int(sys.argv[3]) if len(sys.argv) > 3 else 10
live = 0; refused = 0; lock = threading.Lock()

def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except OSError: pass
    finally:
        for s in (a, b):
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass

def serve(c):
    global live, refused
    with lock:
        if live >= cap:
            refused += 1; print(f"RIFIUTATO {refused} (aperti {live})", file=sys.stderr, flush=True)
            c.close(); return
        live += 1
    try:
        u = socket.create_connection(("127.0.0.1", target))
        t = threading.Thread(target=pipe, args=(u, c), daemon=True); t.start()
        pipe(c, u); t.join()
        u.close()
    except OSError: pass
    finally:
        c.close()
        with lock: live -= 1

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", listen)); srv.listen(64)
while True:
    c, _ = srv.accept()
    threading.Thread(target=serve, args=(c,), daemon=True).start()
