#!/usr/bin/env python3
"""Trim sing-box's protocol registry down to what a SOCKS5 client needs.

The PanSocks core only ever speaks socks (outbound), tun / mixed (inbound),
direct, block and the local DNS transport. sing-box registers everything by
default, so this script edits include/registry.go before the build:

    python3 core/slim-registry.py <path>/include/registry.go

Verified by the "Build Core" workflow: it builds a linux core with this patch
and runs `sing-box check` on the Android config fixtures.
Failures are loud - if upstream renames something the script exits non-zero and
the release job is skipped (the previous core release stays in place).

Size on windows/amd64 (sing-box v1.14.1, -s -w -trimpath): 36.0 MB -> 27.6 MB.
"""
import pathlib
import re
import sys

# Imports and registration calls for everything a SOCKS5 client never touches.
DROP_IMPORTS = [
    "github.com/sagernet/sing-box/dns/transport/fakeip",
    "github.com/sagernet/sing-box/dns/transport/hosts",
    "github.com/sagernet/sing-box/dns/transport/mdns",
    "github.com/sagernet/sing-box/protocol/anytls",
    "github.com/sagernet/sing-box/protocol/bridge",
    "github.com/sagernet/sing-box/protocol/group",
    "github.com/sagernet/sing-box/protocol/naive",
    "github.com/sagernet/sing-box/protocol/redirect",
    "github.com/sagernet/sing-box/protocol/shadowsocks",
    "github.com/sagernet/sing-box/protocol/shadowtls",
    "github.com/sagernet/sing-box/protocol/snell",
    "github.com/sagernet/sing-box/protocol/ssh",
    "github.com/sagernet/sing-box/protocol/tor",
    "github.com/sagernet/sing-box/protocol/trojan",
    "github.com/sagernet/sing-box/protocol/vless",
    "github.com/sagernet/sing-box/protocol/vmess",
    "github.com/sagernet/sing-box/service/api",
    "github.com/sagernet/sing-box/service/origin_ca",
    "github.com/sagernet/sing-box/service/resolved",
    "github.com/sagernet/sing-box/service/ssmapi",
]

DROP_CALLS = [
    "api.RegisterService(registry)",
    "resolved.RegisterService(registry)",
    "ssmapi.RegisterService(registry)",
    "originca.RegisterCertificateProvider(registry)",
    "registerDERPService(registry)",
    "registerUSBIPServices(registry)",
    "redirect.RegisterRedirect(registry)",
    "redirect.RegisterTProxy(registry)",
    "http.RegisterInbound(registry)",
    "shadowsocks.RegisterInbound(registry)",
    "snell.RegisterInbound(registry)",
    "vmess.RegisterInbound(registry)",
    "trojan.RegisterInbound(registry)",
    "naive.RegisterInbound(registry)",
    "shadowtls.RegisterInbound(registry)",
    "vless.RegisterInbound(registry)",
    "anytls.RegisterInbound(registry)",
    "bridge.RegisterOutbound(registry)",
    "group.RegisterSelector(registry)",
    "group.RegisterURLTest(registry)",
    "shadowsocks.RegisterOutbound(registry)",
    "snell.RegisterOutbound(registry)",
    "vmess.RegisterOutbound(registry)",
    "trojan.RegisterOutbound(registry)",
    "registerNaiveOutbound(registry)",
    "tor.RegisterOutbound(registry)",
    "ssh.RegisterOutbound(registry)",
    "shadowtls.RegisterOutbound(registry)",
    "vless.RegisterOutbound(registry)",
    "anytls.RegisterOutbound(registry)",
    "hosts.RegisterTransport(registry)",
    "mdns.RegisterTransport(registry)",
    "fakeip.RegisterTransport(registry)",
    "resolved.RegisterTransport(registry)",
]

# Must survive the trim, otherwise we would ship a broken core.
# Yang benar-benar dipakai config PanSocks:
#   inbound  : tun (jalur utama) + mixed (loopback HTTP ping di 127.0.0.1:2081)
#   outbound : socks (server SOCKS5) + direct (bootstrap & anti-loop) + block (reject)
#   dns      : tcp + udp lewat detour socks, dan "local" sebagai bootstrap
#
# mixed sudah mencakup HTTP, jadi http inbound tidak perlu didaftarkan terpisah.
# http OUTBOUND tetap dibutuhkan: preflight ping memakai Proxy.Type.HTTP.
# Catatan versi: di sing-box >= 1.12 "local" adalah transport DNS yang
# didaftarkan di include/registry.go (local.RegisterTransport). Di v1.11
# transport "local" didaftarkan dari Go lewat RegisterLocalDNSTransport()
# di experimental/libbox/dns.go, jadi tidak muncul di registry sama sekali.
# Keduanya sah - penanda di bawah menyesuaikan diri dengan versi sumbernya.
KEEP = [
    "tun.RegisterInbound(registry)",
    "socks.RegisterInbound(registry)",
    "mixed.RegisterInbound(registry)",
    "direct.RegisterInbound(registry)",
    "direct.RegisterOutbound(registry)",
    "block.RegisterOutbound(registry)",
    "socks.RegisterOutbound(registry)",
    "http.RegisterOutbound(registry)",
]

# Wajib ada, tapi lokasinya berbeda antar versi.
KEEP_ANYWHERE = {
    "dns local transport": [
        "local.RegisterTransport(registry)",      # >= 1.12
        "RegisterLocalDNSTransport",              # 1.11.x (di dns.go)
    ],
}


def main() -> int:
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "include/registry.go")
    src = path.read_text(encoding="utf-8")

    dropped, missing = [], []
    for imp in DROP_IMPORTS:
        pattern = re.compile(r'\n\t(?:[A-Za-z_][A-Za-z0-9_]* )?"' + re.escape(imp) + r'"')
        src, hits = pattern.subn("", src)
        (dropped if hits else missing).append(imp)

    for call in DROP_CALLS:
        pattern = re.compile(r'\n\t' + re.escape(call))
        src, hits = pattern.subn("", src)
        (dropped if hits else missing).append(call)

    absent = [marker for marker in KEEP if marker not in src]

    # Penanda yang boleh berada di file lain: cek di seluruh tree sumber, bukan
    # hanya registry.go, karena letaknya berubah antar versi sing-box.
    for label, alternatif in KEEP_ANYWHERE.items():
        root = path.parent.parent
        ketemu = False
        for alt in alternatif:
            if alt in src:
                ketemu = True
                break
            for gofile in root.rglob("*.go"):
                try:
                    if alt in gofile.read_text(encoding="utf-8", errors="ignore"):
                        ketemu = True
                        break
                except OSError:
                    continue
            if ketemu:
                break
        if not ketemu:
            absent.append(f"{label} ({' atau '.join(alternatif)})")

    if absent:
        print("ERROR: registry shape changed, keeping these is required:", file=sys.stderr)
        for marker in absent:
            print("  -", marker, file=sys.stderr)
        return 1

    path.write_text(src, encoding="utf-8", newline="\n")
    print(f"slim: dropped {len(dropped)} registrations/imports, kept {len(KEEP)}")
    if missing:
        print(f"note: {len(missing)} entries were already absent (upstream moved on):")
        for entry in missing[:8]:
            print("  -", entry)
    return 0


if __name__ == "__main__":
    sys.exit(main())
