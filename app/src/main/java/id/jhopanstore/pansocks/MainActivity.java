package id.jhopanstore.pansocks;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.MenuItem;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Layar utama PanSocks.
 *
 * Satu layar, satu tombol: isi Host/Port (+ user/password bila server meminta),
 * tekan Connect, izinkan VPN, selesai. Semua state dibaca dari SharedPreferences
 * yang ditulis {@link PanSocksVpnService}, jadi UI tidak perlu menebak apakah
 * tunnel masih hidup: service menulis heartbeat tiap 10 detik.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PanSocksMain";

    // Palet
    private static final int BG = Color.rgb(10, 11, 14);
    private static final int CARD = Color.rgb(22, 24, 30);
    private static final int TEXT_PRIMARY = Color.rgb(245, 245, 247);
    private static final int TEXT_SECONDARY = Color.rgb(138, 145, 158);
    private static final int ACCENT = Color.rgb(0, 208, 240);
    private static final int GREEN = Color.rgb(28, 184, 98);
    private static final int RED = Color.rgb(220, 60, 60);
    private static final int BLUE = Color.rgb(70, 130, 180);
    private static final int DOT_OFF = Color.rgb(70, 76, 88);

    private static final int REQ_VPN = 300;
    private static final int MENU_PING = 1;
    private static final int MENU_COUNTER = 2;
    private static final int MENU_ABOUT = 3;
    private static final int REQ_NOTIF = 40;

    // Semua state tunnel dibaca/ditulis lewat TunnelState - lihat komentar di
    // kelas itu: dua skema berbeda pernah membuat UI macet di "Menyiapkan VPN...".
    private static final String STATUS_PREFS = TunnelState.PREFS;
    private static final String KEY_UP = TunnelState.KEY_UP;
    private static final String KEY_DOWN = TunnelState.KEY_DOWN;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private EditText hostInput;
    private EditText portInput;
    private EditText userInput;
    private EditText passInput;
    private TextView statusText;
    private TextView pingText;
    private TextView trafficText;
    private Button connectButton;
    private View statusDot;

    /**
     * Service menulis status/traffic/heartbeat ke SharedPreferences, jadi UI
     * cukup mendengarkan perubahan itu. Polling tiap detik membuat layar tidak
     * pernah idle (aksesibilitas & uiautomator gagal) dan membangunkan CPU
     * tanpa alasan - listener hanya jalan saat ada yang benar-benar berubah.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (sharedPreferences, key) -> refreshUi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pansocks", MODE_PRIVATE);
        requestNotificationPermission();
        setContentView(buildContent());
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(statusListener);
        refreshUi();
    }

    @Override
    protected void onStop() {
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(statusListener);
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn();
            } else {
                connectButton.setEnabled(true);
                saveState(TunnelState.PHASE_IDLE, "Izin VPN ditolak");
                refreshUi();
            }
        }
    }

    // ----------------------------------------------
    // Layout
    // ----------------------------------------------

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(12), dp(22), dp(22));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        // ── Top bar minimal: hanya menu titik tiga di kanan ──
        // Judul/logo dihapus supaya layar bersih; identitas app sudah terlihat
        // dari ikon launcher dan dari menu About.
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        TextView overflow = text("\u22EE", 24, true, TEXT_SECONDARY);
        overflow.setGravity(Gravity.CENTER);
        overflow.setPadding(dp(10), dp(2), dp(10), dp(6));
        overflow.setOnClickListener(this::showOverflowMenu);
        topBar.addView(overflow, new LinearLayout.LayoutParams(-2, -2));

        root.addView(topBar, matchWrap());

        // ── Field ──
        hostInput = textInput("Contoh: 10.12.132.225", prefs.getString("host", ""));
        portInput = numberInput("1080", prefs.getInt("port", 1080));
        userInput = textInput("Kosongkan bila server tanpa auth", prefs.getString("user", ""));
        String passPlain = SecurePrefs.decrypt(this, prefs.getString("pass_enc", null));
        passInput = textInput("Kosongkan bila server tanpa auth",
                passPlain != null ? passPlain : prefs.getString("pass", ""));
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        root.addView(fieldBox("Host / IP server", hostInput), marginTop(matchWrap(), 8));
        root.addView(fieldBox("Port", portInput), marginTop(matchWrap(), 10));
        root.addView(fieldBox("Username (opsional)", userInput), marginTop(matchWrap(), 10));
        root.addView(fieldBox("Password (opsional)", passInput), marginTop(matchWrap(), 10));

        // ── Tombol utama ──
        connectButton = button("Connect");
        connectButton.setBackgroundColor(GREEN);
        connectButton.setOnClickListener(v -> onToggleConnection());
        root.addView(connectButton, marginTop(matchWrap(), 20));

        // ── Kartu status: tepat di bawah tombol ──
        // Status dan counter menempel ke tombol Connect supaya mata langsung
        // melihat hasil tekanannya, bukan terpisah di dasar layar.
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusCard.setBackgroundColor(CARD);

        statusDot = new View(this);
        statusDot.setBackgroundColor(DOT_OFF);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.rightMargin = dp(12);
        statusCard.addView(statusDot, dotParams);

        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);

        statusText = text("Disconnected", 16, true, TEXT_SECONDARY);
        statusCol.addView(statusText, matchWrap());

        trafficText = text("\u2191 0 B   \u2193 0 B", 12, false, TEXT_SECONDARY);
        statusCol.addView(trafficText, marginTop(matchWrap(), 3));

        pingText = text("", 11, false, TEXT_SECONDARY);
        statusCol.addView(pingText, marginTop(matchWrap(), 2));

        statusCard.addView(statusCol, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(statusCard, marginTop(matchWrap(), 12));

        return scroll;
    }

    // ----------------------------------------------
    // Menu titik tiga
    // ----------------------------------------------

    /**
     * Menu titik tiga: dua toggle langsung + About.
     *
     * Toggle ditampilkan sebagai item menu yang bisa dicentang (centang = hidup,
     * kosong = mati). Tidak ada dialog perantara dan tidak ada item "Pengaturan" -
     * begitu menu dibuka, status kedua opsi langsung terlihat dan bisa diubah
     * dengan satu ketukan.
     *
     * PopupMenu tidak membalik nilai sendiri, jadi tiap klik membaca nilai
     * sekarang lalu menulis kebalikannya.
     */
    private void showOverflowMenu(View anchor) {
        SharedPreferences sp = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE);
        boolean pingOn = sp.getBoolean(TunnelState.KEY_PING_ENABLED, false);
        boolean counterOn = sp.getBoolean(TunnelState.KEY_TRAFFIC_ENABLED, true);

        PopupMenu menu = new PopupMenu(this, anchor);

        MenuItem itemPing = menu.getMenu().add(0, MENU_PING, 0, "HTTP ping");
        itemPing.setCheckable(true);
        itemPing.setChecked(pingOn);

        MenuItem itemCounter = menu.getMenu().add(0, MENU_COUNTER, 1, "Counter");
        itemCounter.setCheckable(true);
        itemCounter.setChecked(counterOn);

        menu.getMenu().add(0, MENU_ABOUT, 2, "About");

        menu.setOnMenuItemClickListener(item -> {
            SharedPreferences.Editor ed = sp.edit();
            switch (item.getItemId()) {
                case MENU_PING:
                    ed.putBoolean(TunnelState.KEY_PING_ENABLED, !pingOn).apply();
                    return true;
                case MENU_COUNTER:
                    ed.putBoolean(TunnelState.KEY_TRAFFIC_ENABLED, !counterOn).apply();
                    return true;
                case MENU_ABOUT:
                    showDeveloperInfo();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    // ----------------------------------------------
    // Aksi
    // ----------------------------------------------

    private void onToggleConnection() {
        // Fase eksplisit, bukan flag boolean yang bisa tertinggal basi.
        if (TunnelState.isConnected(this) || TunnelState.isConnecting(this)
                || isVpnServiceAlive()) {
            stopVpn();
        } else {
            prepareAndConnect();
        }
    }

    private void prepareAndConnect() {
        String host = hostInput.getText().toString().trim();
        int port = parsePort(portInput, 1080);

        if (host.isEmpty()) {
            statusText.setText("Host / IP wajib diisi");
            statusText.setTextColor(RED);
            return;
        }

        // Hostname akan di-resolve oleh proses app, dan proses app dikecualikan
        // dari VPN: query itu keluar lewat jaringan bawah (DNS leak) atau gagal.
        // Karena itu hanya IP literal yang diterima.
        if (!isIpLiteral(host)) {
            statusText.setText("Host harus IP, contoh 10.12.132.225");
            statusText.setTextColor(RED);
            return;
        }

        final String user = userInput.getText().toString().trim();
        final String pass = passInput.getText().toString();

        connectButton.setEnabled(false);
        statusText.setTextColor(ACCENT);
        statusText.setText("Cek host dan port...");
        saveState(TunnelState.PHASE_CONNECTING, "Cek host dan port...");

        prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("user", user)
                .putString("pass_enc", SecurePrefs.encrypt(this, pass))
                .remove("pass") // jangan tinggalkan bekas plaintext
                .apply();

        // Preflight di thread latar: cek TCP + handshake SOCKS5 dulu, baru minta
        // izin VPN. Kalau server mati atau password salah, tunnel tidak pernah
        // sempat naik dan pengguna langsung tahu sebabnya.
        new Thread(() -> {
            String err = Preflight.check(host, port, user, pass, msg ->
                    handler.post(() -> {
                        statusText.setTextColor(ACCENT);
                        statusText.setText(msg);
                    }));

            if (err != null) {
                handler.post(() -> {
                    statusText.setText(err);
                    statusText.setTextColor(RED);
                    connectButton.setEnabled(true);
                    saveState(TunnelState.PHASE_IDLE, err);
                });
                return;
            }

            // Server lolos cek - sekarang minta izin VPN lalu naikkan tunnel.
            handler.post(() -> {
                statusText.setTextColor(ACCENT);
                statusText.setText("Server siap, menyiapkan VPN...");
                saveState(TunnelState.PHASE_CONNECTING, "Menyiapkan VPN...");
            });

            try {
                Intent vpnIntent = VpnService.prepare(MainActivity.this);
                handler.post(() -> {
                    if (vpnIntent != null) {
                        try {
                            startActivityForResult(vpnIntent, REQ_VPN);
                        } catch (Exception e) {
                            statusText.setText("Izin VPN error: " + safeMessage(e));
                            statusText.setTextColor(RED);
                            connectButton.setEnabled(true);
                        }
                    } else {
                        startVpn();
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    statusText.setText("Gagal menyiapkan VPN: " + safeMessage(e));
                    statusText.setTextColor(RED);
                    connectButton.setEnabled(true);
                });
            }
        }).start();
    }

    /** Bootstrap dan rule anti-loop hanya aman kalau host berupa IP literal. */
    private static boolean isIpLiteral(String host) {
        return Preflight.isIpLiteral(host);
    }

    private void startVpn() {
        try {
            Intent intent = new Intent(this, PanSocksVpnService.class)
                    .setAction(PanSocksVpnService.ACTION_CONNECT)
                    .putExtra(PanSocksVpnService.EXTRA_HOST, hostInput.getText().toString().trim())
                    .putExtra(PanSocksVpnService.EXTRA_PORT, parsePort(portInput, 1080))
                    .putExtra(PanSocksVpnService.EXTRA_USER, userInput.getText().toString().trim())
                    .putExtra(PanSocksVpnService.EXTRA_PASS, passInput.getText().toString());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable e) {
            saveState(TunnelState.PHASE_IDLE, "Gagal start VPN: " + safeMessage(e));
        }
        refreshUi();
    }

    private void stopVpn() {
        try {
            connectButton.setEnabled(false);
            statusText.setText("Menghentikan VPN...");
            statusText.setTextColor(ACCENT);

            Intent stop = new Intent(this, PanSocksVpnService.class)
                    .setAction(PanSocksVpnService.ACTION_DISCONNECT);
            startService(stop);

            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                handler.post(this::refreshUi);
            }).start();
        } catch (Throwable e) {
            saveState(TunnelState.PHASE_IDLE, "Stop error: " + safeMessage(e));
            refreshUi();
        }
    }

    // ----------------------------------------------
    // Refresh UI
    // ----------------------------------------------

    private void refreshUi() {
        SharedPreferences sp = TunnelState.prefs(this);

        // Fase dibaca eksplisit. "Connected" hanya sah kalau service masih
        // menulis heartbeat; kalau service dibunuh paksa, UI ikut turun.
        String phase = TunnelState.phase(this);
        boolean alive = TunnelState.PHASE_CONNECTED.equals(phase);
        boolean connecting = TunnelState.PHASE_CONNECTING.equals(phase);

        if (alive && !TunnelState.isAlive(this) && !isVpnServiceAlive()) {
            alive = false;
            TunnelState.idle(this, "Disconnected");
            phase = TunnelState.PHASE_IDLE;
        }

        String message = TunnelState.message(this);
        if (message == null || message.trim().isEmpty()) {
            message = alive ? "Connected" : connecting ? "Connecting..." : "Disconnected";
        }

        // Status: pesan ditampilkan apa adanya, warna mengikuti fase.
        statusText.setText(message);
        if (alive) {
            statusText.setTextColor(GREEN);
            statusDot.setBackgroundColor(GREEN);
        } else if (connecting) {
            statusText.setTextColor(ACCENT);
            statusDot.setBackgroundColor(ACCENT);
        } else {
            // Bukan "Disconnected" berarti ini pesan kegagalan preflight.
            boolean gagal = !"Disconnected".equalsIgnoreCase(message);
            statusText.setTextColor(gagal ? RED : TEXT_SECONDARY);
            statusDot.setBackgroundColor(gagal ? RED : DOT_OFF);
        }

        // Counter: disembunyikan sepenuhnya saat dimatikan lewat menu,
        // supaya tidak ada angka basi yang menempel di kartu status.
        if (trafficText != null) {
            boolean counterOn = sp.getBoolean(TunnelState.KEY_TRAFFIC_ENABLED, true);
            if (counterOn) {
                // Saat tidak tersambung, tampilkan nol - bukan angka sisa sesi
                // sebelumnya. Service mereset counter waktu disconnect, tapi UI
                // bisa sempat menggambar nilai lama sebelum tulisan itu masuk.
                long up = alive ? sp.getLong(KEY_UP, 0) : 0;
                long down = alive ? sp.getLong(KEY_DOWN, 0) : 0;
                trafficText.setVisibility(View.VISIBLE);
                trafficText.setText("\u2191 " + formatBytes(up) + "   \u2193 " + formatBytes(down));
            } else {
                trafficText.setVisibility(View.GONE);
            }
        }

        // Ping
        if (pingText != null) {
            boolean pingEnabled = sp.getBoolean(TunnelState.KEY_PING_ENABLED, false);
            String pingResult = sp.getString(TunnelState.KEY_PING_RESULT, null);
            if (!pingEnabled || !alive) {
                pingText.setVisibility(View.GONE);
            } else {
                pingText.setVisibility(View.VISIBLE);
                pingText.setText(pingResult != null
                        ? "Ping " + pingResult
                        : "Ping: menunggu hasil...");
            }
        }

        // Tombol
        boolean active = alive || connecting;
        if (active) {
            connectButton.setText("Disconnect");
            connectButton.setBackgroundColor(RED);
            connectButton.setEnabled(true);
        } else {
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(GREEN);
            connectButton.setEnabled(true);
        }

        boolean lock = active;
        hostInput.setEnabled(!lock);
        portInput.setEnabled(!lock);
        userInput.setEnabled(!lock);
        passInput.setEnabled(!lock);

        // Heartbeat service memicu listener tiap 10 detik, jadi selama tunnel
        // hidup UI ikut segar tanpa polling. Kalau service dibunuh paksa tidak
        // ada tulisan lagi - satu cek ulang setelah jendela heartbeat lewat
        // cukup untuk menyadarinya, tanpa membangunkan CPU terus-menerus.
        handler.removeCallbacks(aliveRecheck);
        if (alive) handler.postDelayed(aliveRecheck, TunnelState.LAST_SEEN_WINDOW_MS + 5000);
    }

    private final Runnable aliveRecheck = this::refreshUi;

    private boolean isVpnServiceAlive() {
        if (TunnelState.isAlive(this)) return true;
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
            if (info.service != null
                    && PanSocksVpnService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void saveState(String phase, String message) {
        if (TunnelState.PHASE_CONNECTED.equals(phase)) {
            TunnelState.connected(this, message);
        } else if (TunnelState.PHASE_CONNECTING.equals(phase)) {
            TunnelState.connecting(this, message);
        } else {
            TunnelState.idle(this, message);
        }
    }

    private String safeMessage(Throwable e) {
        if (e == null) return "unknown";
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ----------------------------------------------
    // Info Developer
    // ----------------------------------------------

    private void showDeveloperInfo() {
        String versionName = "1.0.0";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        String info = "PanSocks v" + versionName + "\n\n"
                + "Developer: JhopanStore\n"
                + "Platform: Android (SOCKS5 VPN Client)\n\n"
                + "Semua trafik TCP, UDP dan DNS keluar lewat server SOCKS5,\n"
                + "tanpa DNS leak dan tanpa jalur IPv6.\n\n"
                + "Hubungi developer:";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Info Developer")
                .setMessage(info)
                .setPositiveButton("Telegram", (d, w) -> openUrl("https://t.me/jhopan_05"))
                .setNegativeButton("Website", (d, w) -> openUrl("https://jhopanstore.my.id"))
                .create();

        dialog.setOnShowListener(d -> {
            AlertDialog ad = (AlertDialog) d;
            ad.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(0, 136, 204));
            ad.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(76, 175, 80));
        });

        dialog.show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    // ----------------------------------------------
    // Widget helper
    // ----------------------------------------------

    private int parsePort(EditText input, int fallback) {
        try {
            int port = Integer.parseInt(input.getText().toString().trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText textInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_SECONDARY);
        input.setBackgroundColor(Color.TRANSPARENT);
        return input;
    }

    private EditText numberInput(String hint, int value) {
        EditText input = textInput(hint, String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFontFeatureSettings("tnum");
        return input;
    }

    private LinearLayout fieldBox(String label, EditText input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(7));
        box.setBackgroundColor(CARD);
        TextView tv = text(label, 12, true, TEXT_SECONDARY);
        box.addView(tv, matchWrap());
        box.addView(input, marginTop(matchWrap(), 4));
        return box;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackgroundColor(Color.rgb(70, 76, 88));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout.LayoutParams params, int topDp) {
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
