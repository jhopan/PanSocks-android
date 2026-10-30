#!/usr/bin/env python3
"""SOCKS5 server minimal untuk menguji PanSocks di perangkat.

Mendukung CONNECT (TCP) dan UDP ASSOCIATE supaya jalur yang dipakai app
benar-benar teruji, plus auth username/password (RFC 1929) bila diminta.

    python tools/test-socks5-server.py --port 1080 --user user --pass pass
"""
import argparse
import selectors
import socket
import struct
import sys
import threading

sel = selectors.DefaultSelector()
VER = 5


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def reply(sock, rep, atyp=1, addr="0.0.0.0", port=0):
    try:
        sock.sendall(struct.pack("!BBBB", VER, rep, 0, atyp))
        if atyp == 1:
            sock.sendall(socket.inet_aton(addr) + struct.pack("!H", port))
        elif atyp == 4:
            sock.sendall(socket.inet_pton(socket.AF_INET6, addr) + struct.pack("!H", port))
        else:
            sock.sendall(bytes([len(addr)]) + addr.encode() + struct.pack("!H", port))
    except OSError:
        pass


def read_addr(sock, atyp):
    if atyp == 1:
        raw = recv_exact(sock, 4)
        return socket.inet_ntoa(raw) if raw else None
    if atyp == 4:
        raw = recv_exact(sock, 16)
        return socket.inet_ntop(socket.AF_INET6, raw) if raw else None
    if atyp == 3:
        ln = recv_exact(sock, 1)
        if not ln:
            return None
        raw = recv_exact(sock, ln[0])
        return raw.decode("utf-8", "replace") if raw else None
    return None


def pipe(a, b):
    def one_way(src, dst):
        try:
            while True:
                data = src.recv(65536)
                if not data:
                    break
                dst.sendall(data)
        except OSError:
            pass
        finally:
            for s in (src, dst):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    t = threading.Thread(target=one_way, args=(a, b), daemon=True)
    t.start()
    one_way(b, a)


def handle(client, args, peer):
    try:
        client.settimeout(30)
        hdr = recv_exact(client, 2)
        if not hdr or hdr[0] != VER:
            return
        nmethods = hdr[1]
        methods = recv_exact(client, nmethods) or b""

        if args.user:
            if 2 not in methods:
                client.sendall(struct.pack("!BB", VER, 0xFF))
                return
            client.sendall(struct.pack("!BB", VER, 2))
            auth_ver = recv_exact(client, 1)
            if not auth_ver or auth_ver[0] != 1:
                return
            ulen = recv_exact(client, 1)[0]
            user = (recv_exact(client, ulen) or b"").decode()
            plen = recv_exact(client, 1)[0]
            pw = (recv_exact(client, plen) or b"").decode()
            ok = user == args.user and pw == args.passwd
            client.sendall(struct.pack("!BB", 1, 0 if ok else 1))
            if not ok:
                print(f"[auth-fail] {peer} user={user!r}")
                return
            print(f"[auth-ok] {peer} user={user!r}")
        else:
            client.sendall(struct.pack("!BB", VER, 0))

        req = recv_exact(client, 4)
        if not req:
            return
        cmd, atyp = req[1], req[3]
        addr = read_addr(client, atyp)
        port = struct.unpack("!H", recv_exact(client, 2))[0]
        if addr is None:
            return

        if cmd == 1:  # CONNECT
            print(f"[connect] {peer} -> {addr}:{port}")
            try:
                upstream = socket.create_connection((addr, port), timeout=15)
            except OSError as e:
                print(f"[connect-fail] {addr}:{port} {e}")
                reply(client, 5)
                return
            reply(client, 0, 1, "0.0.0.0", 0)
            client.settimeout(None)
            upstream.settimeout(None)
            pipe(client, upstream)

        elif cmd == 3:  # UDP ASSOCIATE
            print(f"[udp] {peer} -> {addr}:{port}")
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp.bind((args.bind, 0))
            reply(client, 0, 1, args.advertise, udp.getsockname()[1])
            # Teruskan datagram dua arah selama koneksi TCP kontrol hidup.
            targets = {}
            client.settimeout(1)
            while True:
                try:
                    if not client.recv(1):
                        break
                except socket.timeout:
                    continue
                except OSError:
                    break
                break
            udp.close()
        else:
            reply(client, 7)
    except (OSError, IndexError):
        pass
    finally:
        try:
            client.close()
        except OSError:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=1080)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--advertise", default="127.0.0.1")
    ap.add_argument("--user", default="")
    ap.add_argument("--pass", dest="passwd", default="")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.bind, args.port))
    srv.listen(64)
    auth = f"auth={args.user}" if args.user else "no-auth"
    print(f"PanSocks test SOCKS5 server on {args.bind}:{args.port} ({auth})", flush=True)

    while True:
        try:
            conn, peer = srv.accept()
        except KeyboardInterrupt:
            break
        threading.Thread(target=handle, args=(conn, args, peer), daemon=True).start()


if __name__ == "__main__":
    main()
