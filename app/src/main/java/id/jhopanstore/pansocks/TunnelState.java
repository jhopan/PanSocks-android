package id.jhopanstore.pansocks;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Satu-satunya sumber kebenaran untuk state tunnel.
 *
 * Sebelumnya dua komponen menulis skema berbeda ke SharedPreferences yang sama:
 * {@code PanSocksVpnService} menulis {@code connected}/{@code status}, sementara
 * {@code MainActivity} membaca {@code phase}/{@code message}. Akibatnya tunnel
 * yang sudah tersambung (ping 204) tetap tampil "Menyiapkan VPN..." dengan tombol
 * yang tidak bisa ditekan, karena fase lama tidak pernah ditimpa.
 *
 * Semua penulisan state sekarang lewat kelas ini, dan pembacaan memakai fase
 * eksplisit - bukan menebak dari isi pesan (pesan error "Connection refused"
 * pernah ikut terbaca sebagai "connecting").
 */
final class TunnelState {

    static final String PREFS = "pansocks_status";

    static final String PHASE_IDLE = "idle";
    static final String PHASE_CONNECTING = "connecting";
    static final String PHASE_CONNECTED = "connected";

    static final String KEY_PHASE = "phase";
    static final String KEY_MESSAGE = "message";
    static final String KEY_LAST_SEEN = "last_seen";
    static final String KEY_UP = "upload_bytes";
    static final String KEY_DOWN = "download_bytes";
    static final String KEY_PING_ENABLED = "ping_enabled";
    static final String KEY_PING_RESULT = "ping_result";
    static final String KEY_TRAFFIC_ENABLED = "traffic_counter_enabled";

    /**
     * Batas umur heartbeat. Service menulis {@code last_seen} tiap 10 detik,
     * jadi jendela ini harus >= 3x interval supaya Doze/layar mati sesaat
     * tidak dibaca sebagai service mati.
     */
    static final long LAST_SEEN_WINDOW_MS = 45000;

    private TunnelState() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void connecting(Context context, String message) {
        write(context, PHASE_CONNECTING, message);
    }

    static void connected(Context context, String message) {
        write(context, PHASE_CONNECTED, message);
    }

    static void idle(Context context, String message) {
        write(context, PHASE_IDLE, message);
    }

    private static void write(Context context, String phase, String message) {
        prefs(context).edit()
                .putString(KEY_PHASE, phase)
                .putString(KEY_MESSAGE, message)
                .putLong(KEY_LAST_SEEN, System.currentTimeMillis())
                .apply();
    }

    /** Hanya memperbarui heartbeat, tanpa mengubah fase. */
    static void touch(Context context) {
        prefs(context).edit()
                .putLong(KEY_LAST_SEEN, System.currentTimeMillis())
                .apply();
    }

    static String phase(Context context) {
        String p = prefs(context).getString(KEY_PHASE, PHASE_IDLE);
        return p == null ? PHASE_IDLE : p;
    }

    static String message(Context context) {
        return prefs(context).getString(KEY_MESSAGE, null);
    }

    static boolean isConnected(Context context) {
        return PHASE_CONNECTED.equals(phase(context));
    }

    static boolean isConnecting(Context context) {
        return PHASE_CONNECTING.equals(phase(context));
    }

    /**
     * True bila service masih menulis heartbeat. Dipakai UI untuk menyadari
     * service yang dibunuh paksa tanpa menunggu tulisan berikutnya.
     */
    static boolean isAlive(Context context) {
        long lastSeen = prefs(context).getLong(KEY_LAST_SEEN, 0);
        return lastSeen > 0 && (System.currentTimeMillis() - lastSeen) < LAST_SEEN_WINDOW_MS;
    }
}
