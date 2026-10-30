package id.jhopanstore.pansocks;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Splash singkat: logo PanSocks + versi, lalu pindah ke MainActivity.
 *
 * Ditulis tanpa layout XML supaya proyek tetap satu bahasa (Java) dan tidak ada
 * resource yang perlu di-maintain terpisah.
 */
public class SplashActivity extends Activity {

    private static final long SPLASH_DELAY_MS = 1200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0A0B0E"));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_pansocks);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        logoParams.bottomMargin = dp(14);
        root.addView(logo, logoParams);

        TextView tagline = new TextView(this);
        tagline.setText("SOCKS5 Tunnel Client");
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tagline.setTextColor(Color.parseColor("#8A919E"));
        tagline.setGravity(Gravity.CENTER);
        root.addView(tagline);

        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                finish();
            }
        }, SPLASH_DELAY_MS);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }
}
