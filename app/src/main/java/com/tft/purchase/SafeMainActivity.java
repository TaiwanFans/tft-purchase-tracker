package com.tft.purchase;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Launcher wrapper for production-like field testing.
 * If MainActivity throws during startup, keep the process alive and show a
 * diagnostic screen instead of flashing back to the launcher.
 */
public class SafeMainActivity extends MainActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /*
         * Pixel 10 Pro XL / Android 17 (API 37) can reach MainActivity's
         * startup window configuration before PhoneWindow has installed its
         * DecorView. PhoneWindow.getInsetsController() then dereferences a
         * null DecorView and crashes.
         *
         * Force the DecorView to be installed before MainActivity.onCreate()
         * runs. Activity.attach() has already created the Window at this point,
         * so this is safe and removes the API 37 startup timing race.
         */
        try {
            getWindow().getDecorView();
        } catch (Throwable ignored) {
            // Never let cosmetic/window preparation prevent startup.
        }

        try {
            super.onCreate(savedInstanceState);
        } catch (Throwable t) {
            showStartupFailure(t);
        }
    }

    private void showStartupFailure(Throwable error) {
        String details = buildDetails(error);
        try {
            File out = new File(getFilesDir(), "startup_crash.txt");
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(details.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}

        try {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(18), dp(28), dp(18), dp(28));
            page.setBackgroundColor(Color.rgb(248, 250, 252));

            TextView title = label("APP 啟動發生錯誤", 24, Color.rgb(185, 28, 28), true);
            page.addView(title);
            page.addView(label("這個版本已阻止 APP 直接閃退。請把下方錯誤資訊截圖或複製給我，我可以繼續直接定位修正。", 15, Color.rgb(51, 65, 85), false));

            TextView info = label(details, 12, Color.rgb(15, 23, 42), false);
            info.setTextIsSelectable(true);
            info.setPadding(dp(12), dp(12), dp(12), dp(12));
            info.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(0, dp(16), 0, dp(12));
            page.addView(info, ip);

            Button copy = button("複製錯誤資訊");
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("全益採購追蹤啟動錯誤", details));
                Toast.makeText(this, "已複製錯誤資訊", Toast.LENGTH_SHORT).show();
            });
            page.addView(copy);

            Button reset = button("重設啟動設定後重試（不刪採購資料）");
            reset.setOnClickListener(v -> {
                getSharedPreferences("tft_settings", MODE_PRIVATE).edit().clear().apply();
                restartSelf();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.setMargins(0, dp(10), 0, 0);
            page.addView(reset, rp);

            ScrollView scroll = new ScrollView(this);
            scroll.addView(page);
            setContentView(scroll);
        } catch (Throwable fatalUiError) {
            TextView v = new TextView(this);
            v.setText("全益採購追蹤啟動失敗\n" + error.getClass().getName() + "\n" + String.valueOf(error.getMessage()));
            v.setTextSize(16);
            v.setPadding(24, 48, 24, 24);
            setContentView(v);
        }
    }

    private void restartSelf() {
        Intent i = new Intent(this, SafeMainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private String buildDetails(Throwable t) {
        StringBuilder s = new StringBuilder();
        s.append("版本：2.0.2\n");
        s.append("Android：").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\n");
        s.append("裝置：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            s.append(depth == 0 ? "錯誤：" : "Caused by：")
                    .append(cur.getClass().getName()).append("\n")
                    .append(String.valueOf(cur.getMessage())).append("\n");
            StackTraceElement[] stack = cur.getStackTrace();
            int max = Math.min(stack == null ? 0 : stack.length, 12);
            for (int i = 0; i < max; i++) s.append("  at ").append(stack[i]).append("\n");
            cur = cur.getCause();
            depth++;
        }
        return s.toString();
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
