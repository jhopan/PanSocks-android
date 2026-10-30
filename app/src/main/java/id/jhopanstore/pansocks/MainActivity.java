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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private static final int REQ_NOTIF = 40;

    private static final String STATUS_PREFS = "pansocks_status";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_STATUS = "status";
    private static final String KEY_LAST_SEEN = "last_seen";
    private static final String KEY_UP = "upload_bytes";
    private static final String KEY_DOWN = "download_bytes";

    /** Jendela toleransi heartbeat service (3x HEARTBEAT_MS di service). */
    private static final long LAST_SEEN_WINDOW_MS = 45000;

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
                saveStatus(false, "Izin VPN ditolak");
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
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        // Header: logo + versi
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_pansocks);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(-1, dp(88));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, logoParams);

        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView ver = text("v" + vn, 11, false, Color.rgb(92, 99, 112));
            ver.setGravity(Gravity.CENTER);
            root.addView(ver, marginTop(matchWrap(), 8));
        } catch (Exception ignored) {
        }

        // Kartu status
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
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

        statusCard.addView(statusCol, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(statusCard, marginTop(matchWrap(), 22));

        // Field
        hostInput = textInput("Contoh: 10.12.132.225", prefs.getString("host", ""));
        portInput = numberInput("1080", prefs.getInt("port", 1080));
        userInput = textInput("Kosongkan bila server tanpa auth", prefs.getString("user", ""));
        String passPlain = SecurePrefs.decrypt(this, prefs.getString("pass_enc", null));
        passInput = textInput("Kosongkan bila server tanpa auth",
                passPlain != null ? passPlain : prefs.getString("pass", ""));
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        root.addView(fieldBox("Host / IP server", hostInput), marginTop(matchWrap(), 16));
        root.addView(fieldBox("Port", portInput), marginTop(matchWrap(), 10));
        root.addView(fieldBox("Username (opsional)", userInput), marginTop(matchWrap(), 10));
        root.addView(fieldBox("Password (opsional)", passInput), marginTop(matchWrap(), 10));

        // Tombol utama
        connectButton = button("Connect");
        connectButton.setBackgroundColor(GREEN);
        connectButton.setOnClickListener(v -> onToggleConnection());
        root.addView(connectButton, marginTop(matchWrap(), 20));

        // HTTP ping
        CheckBox pingCheck = new CheckBox(this);
        pingCheck.setText("HTTP ping (keep-alive tiap 30 detik)");
        pingCheck.setTextColor(TEXT_PRIMARY);
        pingCheck.setTextSize(13);
        boolean pingEnabled = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
                .getBoolean("ping_enabled", false);
        pingCheck.setChecked(pingEnabled);
        pingCheck.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit()
                        .putBoolean("ping_enabled", checked).apply());
        root.addView(pingCheck, marginTop(matchWrap(), 14));

        pingText = text("", 12, false, TEXT_SECONDARY);
        root.addView(pingText, marginTop(matchWrap(), 2));

        // Spacer: dorong footer ke bawah saat layar tinggi, tapi tetap bisa
        // di-scroll saat konten lebih panjang dari layar.
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Info Developer
        Button infoDev = button("Info Developer");
        infoDev.setBackgroundColor(BLUE);
        infoDev.setOnClickListener(v -> showDeveloperInfo());
        root.addView(infoDev, marginTop(matchWrap(), 22));

        TextView foot = text("Semua trafik TCP, UDP dan DNS keluar lewat server SOCKS5 kamu.",
                11, false, Color.rgb(92, 99, 112));
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, marginTop(matchWrap(), 18));

        return scroll;
    }

    // ----------------------------------------------
    // Aksi
    // ----------------------------------------------

    private void onToggleConnection() {
        SharedPreferences statusPrefs = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE);
        boolean connected = statusPrefs.getBoolean(KEY_CONNECTED, false);
        if (connected || isVpnServiceAlive()) {
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

        connectButton.setEnabled(false);
        statusText.setText("Menyiapkan VPN...");
        statusText.setTextColor(ACCENT);

        prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("user", userInput.getText().toString().trim())
                .putString("pass_enc", SecurePrefs.encrypt(this, passInput.getText().toString()))
                .remove("pass") // jangan tinggalkan bekas plaintext
                .apply();

        new Thread(() -> {
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
        if (host == null) return false;
        String h = host.trim();
        if (h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return true;
        return h.contains(":") && h.matches("^[0-9a-fA-F:]+$");
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
            saveStatus(false, "Gagal start VPN: " + safeMessage(e));
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
            saveStatus(false, "Stop error: " + safeMessage(e));
            refreshUi();
        }
    }

    // ----------------------------------------------
    // Refresh UI
    // ----------------------------------------------

    private void refreshUi() {
        SharedPreferences sp = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE);
        boolean connected = sp.getBoolean(KEY_CONNECTED, false);

        // Service menulis heartbeat tiap 10 detik; kalau berhenti menulis,
        // anggap tunnel mati walau flag connected masih true.
        if (connected && !isVpnServiceAlive()) {
            connected = false;
            saveStatus(false, "Disconnected");
        }

        String status = sp.getString(KEY_STATUS, "");
        boolean connecting = status != null
                && status.toLowerCase(Locale.US).contains("connect")
                && !status.equalsIgnoreCase("Connected")
                && !status.equalsIgnoreCase("Disconnected");
        boolean alive = connected || isVpnServiceAlive();

        // Status
        if (alive) {
            statusText.setText("Connected");
            statusText.setTextColor(GREEN);
            statusDot.setBackgroundColor(GREEN);
        } else if (connecting) {
            statusText.setText("Connecting...");
            statusText.setTextColor(ACCENT);
            statusDot.setBackgroundColor(ACCENT);
        } else {
            statusText.setText("Disconnected");
            statusText.setTextColor(TEXT_SECONDARY);
            statusDot.setBackgroundColor(DOT_OFF);
        }

        // Traffic
        if (trafficText != null) {
            long up = sp.getLong(KEY_UP, 0);
            long down = sp.getLong(KEY_DOWN, 0);
            trafficText.setText("\u2191 " + formatBytes(up) + "   \u2193 " + formatBytes(down));
        }

        // Ping
        if (pingText != null) {
            boolean pingEnabled = sp.getBoolean("ping_enabled", false);
            String pingResult = sp.getString("ping_result", null);
            if (pingResult != null) {
                pingText.setText("Ping: " + pingResult);
            } else {
                pingText.setText(pingEnabled
                        ? (alive ? "Ping: menunggu hasil..." : "")
                        : "");
            }
        }

        // Tombol
        if (alive || connecting) {
            connectButton.setText("Disconnect");
            connectButton.setBackgroundColor(RED);
            connectButton.setEnabled(true);
        } else {
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(GREEN);
            connectButton.setEnabled(true);
        }

        boolean lock = alive || connecting;
        hostInput.setEnabled(!lock);
        portInput.setEnabled(!lock);
        userInput.setEnabled(!lock);
        passInput.setEnabled(!lock);

        // Heartbeat service memicu listener tiap 10 detik, jadi selama tunnel
        // hidup UI ikut segar tanpa polling. Kalau service dibunuh paksa tidak
        // ada tulisan lagi - satu cek ulang setelah jendela heartbeat lewat
        // cukup untuk menyadarinya, tanpa membangunkan CPU terus-menerus.
        handler.removeCallbacks(aliveRecheck);
        if (alive) handler.postDelayed(aliveRecheck, LAST_SEEN_WINDOW_MS + 5000);
    }

    private final Runnable aliveRecheck = this::refreshUi;

    private boolean isVpnServiceAlive() {
        SharedPreferences sp = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE);
        long lastSeen = sp.getLong(KEY_LAST_SEEN, 0);
        // Service menulis heartbeat tiap 10 detik, jadi jendela ini harus >= 3x
        // interval supaya Doze/layar mati sesaat tidak dibaca sebagai service mati.
        if (lastSeen > 0 && (System.currentTimeMillis() - lastSeen) < LAST_SEEN_WINDOW_MS) {
            return true;
        }
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

    private void saveStatus(boolean connected, String status) {
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CONNECTED, connected)
                .putString(KEY_STATUS, status)
                .apply();
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
