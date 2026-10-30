<div align="center">

<img src="docs/logo.png" width="220" alt="PanSocks">

# PanSocks

**SOCKS5 Tunnel Client — Android**

Rutekan semua trafik (TCP, UDP, DNS) lewat server SOCKS5 kamu.
Anti DNS leak, TUN gvisor, hemat baterai.

[![Android](https://img.shields.io/badge/Android-APK-3ddc84?style=for-the-badge&logo=android&logoColor=white)](../../releases/latest)
[![Version](https://img.shields.io/badge/version-1.0.0-00d0f0?style=for-the-badge)](../../releases)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

</div>

---

## Fitur

| Fitur | Detail |
|---|---|
| **Anti DNS Leak** | Semua query DNS ditangkap di tunnel (`hijack-dns`) dan dijawab lewat SOCKS. Tidak ada resolver publik di layer OS |
| **TUN gvisor** | Stack network userspace — tidak bergantung driver/kernel perangkat |
| **MTU 1400** | Dioptimalkan untuk hotspot mobile (mencegah PMTU blackhole) |
| **Hemat baterai** | Foreground service `systemExempted` — tunnel tidak dimatikan sistem saat layar mati |
| **IPv6 diblokir** | IPv6 masuk tunnel lalu di-reject, DNS `ipv4_only` — tidak ada jalur v6 yang bocor |
| **Network watchdog** | Ganti WiFi ↔ seluler memuat ulang sing-box otomatis, tidak perlu disconnect manual |
| **HTTP ping** | Cek koneksi tiap 30 detik via `generate_204` (opsional, checkbox) — sekaligus keep-alive jalur SOCKS |
| **Traffic counter** | Baca TX/RX dari interface TUN, tampil di kartu status dan notifikasi |
| **Password terenkripsi** | Android Keystore (AES-256-GCM), bukan plaintext di SharedPreferences |
| **Notifikasi interaktif** | Status + traffic + tombol Disconnect langsung dari shade |

## Download

| File | Arsitektur | Untuk |
|---|---|---|
| `PanSocks-*-arm64-v8a.apk` | arm64-v8a (64-bit) | hampir semua HP dari ~2016 ke atas |
| `PanSocks-*-armeabi-v7a.apk` | armeabi-v7a (32-bit) | HP lama / low-end |
| `PanSocks-*-universal.apk` | semua ABI | kalau ragu; ukuran lebih besar |

→ [**Latest Release**](../../releases/latest)

## Quick Start

1. Download APK sesuai arsitektur HP → install
2. Buka app → isi **Host** (IP server) dan **Port** (default `1080`)
3. Username & Password opsional — hanya bila server SOCKS5 kamu memakai auth
4. Tap **Connect** → izinkan permintaan VPN
5. Notifikasi persisten muncul; tap **Disconnect** di app atau di notifikasi untuk berhenti

> ⚠️ **Host wajib IP literal** (contoh `10.12.132.225`), bukan hostname.
> Hostname akan di-resolve di dalam proses app, dan proses app dikecualikan dari
> VPN — query itu keluar lewat jaringan bawah (DNS leak) atau gagal total.

## Arsitektur

```
App → TUN (gvisor, MTU 1400) → sing-box (libbox) → SOCKS5 → Server → Internet
          │
          ├── DNS hijack: semua query dijawab lewat tunnel
          ├── Anti-loop: trafik ke server SOCKS sendiri keluar via direct
          └── strict_route: tidak ada app yang bisa bypass
```

### Alur satu koneksi

```
MainActivity          PanSocksVpnService              libbox (sing-box)
     │                        │                              │
     │ VpnService.prepare()   │                              │
     │──── izin VPN ─────────►│                              │
     │ ACTION_CONNECT         │                              │
     │───────────────────────►│ startForeground()            │
     │                        │ Libbox.setup()               │
     │                        │ newCommandServer() ─────────►│
     │                        │ buildSingBoxConfig()         │
     │                        │ startOrReloadService() ─────►│
     │                        │                              │ openTun()
     │                        │◄───── PlatformInterface ─────│
     │                        │ Builder.establish()          │
     │                        │                              │
     │◄── heartbeat 10s ──────│ ping / traffic / watchdog    │
```

### Struktur repo

```
PanSocks-android/
├── app/src/main/java/id/jhopanstore/pansocks/
│   ├── MainActivity.java          # UI: form server, status, traffic, ping
│   ├── SplashActivity.java        # splash logo
│   ├── PanSocksVpnService.java    # VPN service + config builder sing-box
│   └── SecurePrefs.java           # enkripsi password (Android Keystore)
├── app/src/main/res/              # ikon, logo, tema, network security config
├── core/slim-registry.py          # trim registry sing-box (build core)
├── scripts/fetch-core.sh          # unduh libbox.aar dari rilis "core"
├── config.ip.json                 # fixture config: server berupa IP
├── config.hostname.json           # fixture config: server berupa hostname
└── .github/workflows/
    ├── build-core.yml             # build libbox.aar → rilis "core"
    ├── build-apk-release.yml      # build + tanda tangan + rilis APK
    └── ci-android.yml             # compile + lint tiap push
```

## Build dari Source

### Prasyarat
- JDK 17
- Android SDK (compileSdk 35, minSdk 24), NDK hanya bila membangun core
- Gradle 8.9 (wrapper sudah disertakan)

### Langkah

```bash
# Cara termudah: satu skrip, otomatis ambil core + pilih JDK
bash scripts/build-local.sh debug     # → app/build/outputs/apk/debug/
bash scripts/build-local.sh release   # → app/build/outputs/apk/release/
bash scripts/build-local.sh lint
```

Atau manual:

```bash
# 1. Ambil core (libbox.aar) dari rilis "core"
bash scripts/fetch-core.sh

# 2. Build debug
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 3. Build release (butuh keystore)
export KEYSTORE_FILE=/path/to/pansocks-release.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=pansocks
export KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

> **Windows (Cygwin / Git-Bash):** jangan panggil `./gradlew` langsung. Skrip itu
> menerjemahkan `JAVA_HOME` dan `CLASSPATH` dengan asumsi path POSIX, sehingga
> gagal dengan `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`.
> Pakai `bash scripts/build-local.sh`, yang memanggil
> `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain`
> dan melewati terjemahan path tersebut. Di Linux/macOS/CI, `./gradlew` normal.

`libbox.aar` **tidak** disimpan di git. Kalau rilis `core` belum ada, jalankan
workflow **Build Core** dulu (atau bangun sendiri dari
[SagerNet/sing-box](https://github.com/SagerNet/sing-box)).

### Signing

| Secret | Isi |
|---|---|
| `KEYSTORE_BASE64` | isi file `.jks` yang di-base64 |
| `KEYSTORE_PASSWORD` | password keystore |
| `KEY_ALIAS` | alias kunci |
| `KEY_PASSWORD` | password kunci |

```bash
base64 -w0 pansocks-release.jks | gh secret set KEYSTORE_BASE64 -R jhopan/PanSocks-android
```

## Konfigurasi

### DNS
| Server | Tipe | Detour |
|---|---|---|
| `1.1.1.1` | `tcp` | `socks-out` (primary) |
| `1.1.1.1` | `udp` | `socks-out` |
| `8.8.8.8` | `tcp` | `socks-out` (backup, promosikan dengan mengubah `final`) |
| — | `local` | bootstrap resolve hostname server |

Strategi `ipv4_only`: AAAA tidak pernah di-resolve, jadi tidak ada app yang
mencoba jalur IPv6.

### Route rules
1. `action: sniff` (protokol `dns`) — supaya DNS over TCP dan query ke resolver
   hardcoded juga dikenali
2. `protocol: dns` → `action: hijack-dns` — semua DNS dijawab lewat tunnel
3. `ip_cidr: ::/0` → `action: reject` — IPv6 gagal cepat, bukan menggantung
4. IP server SOCKS → `direct` — anti routing loop
5. Sisanya → `socks-out`

## QA

```bash
# Compile + lint
./gradlew :app:assembleDebug :app:lint

# Cek config fixture diterima core (butuh binary sing-box)
sing-box check -c config.ip.json
sing-box check -c config.hostname.json
```

Checklist manual di perangkat:

| Uji | Harapan |
|---|---|
| Connect dengan server mati | Status gagal, tombol kembali ke Connect, tidak ada crash |
| Putus WiFi saat tunnel naik | Callback jaringan memuat ulang, tunnel pulih tanpa interaksi |
| Layar mati 10 menit | Tunnel masih hidup (heartbeat tidak berhenti) |
| `https://dnsleaktest.com` (extended) | Semua resolver dari server SOCKS, bukan ISP |
| `https://ipv6-test.com` | IPv6 tidak terpakai |
| Rotasi layar | Form dan status tidak hilang |
| Install build baru di atas build lama | Berhasil tanpa uninstall (kunci rilis sama) |

## Troubleshooting

| Gejala | Penyebab umum | Solusi |
|---|---|---|
| "Host harus IP" | Mengisi hostname | Pakai IP literal server |
| Connect lalu langsung Disconnected | Port salah / server tidak menerima SOCKS5 | Cek port, cek `nc -z IP 1080` dari jaringan yang sama |
| Connected tapi tidak ada internet | Server tidak mengizinkan UDP associate, atau DNS server diblokir | Matikan HTTP ping dulu, coba `curl -x socks5h://IP:1080 http://example.com` dari PC |
| Tunnel mati saat layar mati | Battery optimization agresif di OEM tertentu | Kecualikan PanSocks dari battery optimization |
| Notifikasi tidak muncul | Izin `POST_NOTIFICATIONS` ditolak | Izinkan lewat Settings → Apps → PanSocks → Notifications |

## Spesifikasi

| | Android |
|---|---|
| GUI | Java (tanpa XML layout, tanpa dependency eksternal) |
| TUN | `VpnService` + gvisor |
| Core | sing-box 1.14.1 minimal (`libbox.aar`) |
| RAM idle | ~50 MB |
| Binary | 16–32 MB |
| DNS | hijack + VPN DNS |
| Min Android | 7.0 (API 24) |

## Kredit

Kode awal diturunkan dari [SocksClient](https://github.com/jhopan/SocksClient)
(MIT, JhopanStore). Bagian desktop, CLI, dan tooling non-Android dihapus —
repo ini fokus Android saja.

Core: [SagerNet/sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0),
dipakai sebagai library lewat `libbox.aar`.

## Developer

**JhopanStore**

[![Telegram](https://img.shields.io/badge/Telegram-@jhopan__05-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/jhopan_05)
[![Website](https://img.shields.io/badge/Website-jhopanstore.my.id-4FC3F7?style=flat-square&logo=googlechrome&logoColor=white)](https://jhopanstore.my.id)

## License

MIT — lihat [LICENSE](LICENSE)
