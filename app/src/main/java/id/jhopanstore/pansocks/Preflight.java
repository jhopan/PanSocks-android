package id.jhopanstore.pansocks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Cek pra-sambung (preflight) sebelum tunnel dinaikkan.
 *
 * Tanpa ini, server yang mati atau password yang salah baru ketahuan setelah
 * sing-box jalan - dan pesan errornya generik ("Gagal connect"). Dengan
 * preflight, kegagalan ketahuan dalam hitungan detik dengan sebab yang jelas,
 * dan VPN tidak pernah sempat naik.
 *
 * Tiga tahap, sama seperti klien desktop:
 *   1. host harus IP literal dan port masuk akal,
 *   2. TCP connect ke host:port,
 *   3. handshake SOCKS5 (pilih metode + login bila server meminta).
 *
 * Catatan: aplikasi ini dikecualikan dari VPN (addDisallowedApplication), jadi
 * koneksi cek ini keluar lewat jaringan bawah - persis seperti yang dilakukan
 * sing-box nanti saat menghubungi server.
 */
final class Preflight {

    /** Batas waktu tiap tahap, dalam milidetik. */
    static final int TIMEOUT_MS = 8000;

    /** Callback progres supaya UI bisa menampilkan tahap yang sedang jalan. */
    interface StepListener {
        void onStep(String message);
    }

    private Preflight() {
    }

    /**
     * Menjalankan semua tahap cek.
     *
     * @return null bila server siap dipakai, atau kalimat error siap tampil.
     */
    static String check(String host, int port, String user, String pass, StepListener step) {
        if (step == null) step = message -> { };

        // ── 1. Host dan port ──
        step.onStep("Cek host dan port...");
        if (host == null || !isIpLiteral(host.trim())) {
            return "Host harus IP, contoh 10.12.132.225";
        }
        if (port < 1 || port > 65535) {
            return "Port tidak valid";
        }

        String alamat = host.trim() + ":" + port;
        step.onStep("Cek koneksi ke " + alamat + "...");

        Socket socket = new Socket();
        try {
            // ── 2. TCP ──
            try {
                socket.connect(new InetSocketAddress(host.trim(), port), TIMEOUT_MS);
            } catch (IOException e) {
                return "Server tidak bisa dihubungi (" + shortError(e) + ")";
            }

            socket.setSoTimeout(TIMEOUT_MS);

            // ── 3. Handshake SOCKS5 ──
            step.onStep("Cek autentikasi SOCKS5...");

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            boolean pakaiAuth = user != null && !user.trim().isEmpty();

            // Greeting: versi 5, jumlah metode, lalu daftar metode.
            // 0x00 = tanpa auth, 0x02 = username/password (RFC 1929).
            byte[] greet = pakaiAuth
                    ? new byte[]{0x05, 0x02, 0x00, 0x02}
                    : new byte[]{0x05, 0x01, 0x00};
            out.write(greet);
            out.flush();

            byte[] balas = new byte[2];
            if (!readFully(in, balas)) {
                return "Tidak ada balasan SOCKS5";
            }
            if ((balas[0] & 0xFF) != 0x05) {
                return "Bukan server SOCKS5";
            }

            int metode = balas[1] & 0xFF;
            if (metode == 0x00) {
                // Server menerima tanpa auth. Kalau user mengisi kredensial,
                // tidak masalah - server memang tidak memintanya.
                return null;
            }
            if (metode == 0x02) {
                if (!pakaiAuth) {
                    return "Server butuh login, isi Username dan Password";
                }
                return login(out, in, user.trim(), pass == null ? "" : pass);
            }
            if (metode == 0xFF) {
                return "Server menolak semua metode autentikasi";
            }
            return "Metode autentikasi server tidak didukung";

        } catch (IOException e) {
            return "Gagal cek server (" + shortError(e) + ")";
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Sub-negotiation username/password (RFC 1929). @return null bila diterima. */
    private static String login(OutputStream out, InputStream in, String user, String pass)
            throws IOException {
        byte[] u = user.getBytes("UTF-8");
        byte[] p = pass.getBytes("UTF-8");
        if (u.length > 255 || p.length > 255) {
            return "Username/password terlalu panjang";
        }

        byte[] auth = new byte[3 + u.length + p.length];
        auth[0] = 0x01;                       // versi sub-negotiation
        auth[1] = (byte) u.length;
        System.arraycopy(u, 0, auth, 2, u.length);
        auth[2 + u.length] = (byte) p.length;
        System.arraycopy(p, 0, auth, 3 + u.length, p.length);

        out.write(auth);
        out.flush();

        byte[] hasil = new byte[2];
        if (!readFully(in, hasil)) {
            return "Tidak ada balasan login";
        }
        if ((hasil[1] & 0xFF) != 0x00) {
            return "Login ditolak (username/password salah)";
        }
        return null;
    }

    /** Baca tepat buf.length byte. @return false bila koneksi ditutup lebih awal. */
    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) return false;
            total += n;
        }
        return true;
    }

    /**
     * Rule anti-loop dan bootstrap hanya aman kalau host berupa IP literal.
     * Disamakan dengan {@code isIpLiteral} di MainActivity dan service.
     */
    static boolean isIpLiteral(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return true;
        return h.contains(":") && h.matches("^[0-9a-fA-F:]+$");
    }

    /** Ambil inti pesan error (tanpa prefix panjang) supaya muat di status bar. */
    private static String shortError(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        int i = s.lastIndexOf(": ");
        if (i >= 0) s = s.substring(i + 2);
        return s.length() > 48 ? s.substring(0, 48) : s;
    }
}
