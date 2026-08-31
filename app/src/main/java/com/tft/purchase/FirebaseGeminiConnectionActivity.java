package com.tft.purchase;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;

/** v2.1.1 non-technical Firebase / Gemini connection helper for privately installed test APKs. */
public class FirebaseGeminiConnectionActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView projectInfo;
    private TextView appCheckStatus;
    private TextView appCheckToken;
    private TextView connectionResult;
    private Button copyToken;
    private Button testConnection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AiModelRegistry.setActive(this, AiModelRegistry.GEMINI_FIREBASE);
        FirebaseAppCheckBootstrap.ensure(this);
        FirebaseAppCheckBootstrap.requestToken(this);
        try { PlayServicesModuleManager.prefetch(this); } catch (Throwable ignored) {}
        render();
        refresh();
        handler.postDelayed(this::refresh, 700);
        handler.postDelayed(this::refresh, 1800);
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setBackgroundColor(Color.rgb(245, 248, 255));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_app_icon);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout titleText = new LinearLayout(this);
        titleText.setOrientation(LinearLayout.VERTICAL);
        titleText.setPadding(dp(12), 0, 0, 0);
        titleText.addView(label("採購單追蹤", 24, Color.rgb(15, 42, 92), true));
        titleText.addView(label("Firebase / Gemini 連線", 15, Color.rgb(37, 99, 235), true));
        titleRow.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(titleRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout infoCard = card(Color.WHITE, Color.rgb(37, 99, 235));
        content.addView(infoCard, cardParams(10, 12));
        infoCard.addView(label("V2.1.1｜Gemini + Firebase App Check 測試版", 17, Color.rgb(15, 42, 92), true));
        infoCard.addView(label("PP-OCRv6 Small、ML Kit、OpenCV 與固定 8 欄解析照常保留；Gemini 只做雲端視覺核對，最後仍以固定格線與 OCR 證據為優先。", 13, Color.rgb(71, 85, 105), false));
        projectInfo = label("正在讀取 Firebase 專桌資訊…", 14, Color.rgb(15, 23, 42), true);
        infoCard.addView(projectInfo);

        LinearLayout appCheckCard = card(Color.rgb(255, 251, 235), Color.rgb(245, 158, 11));
        content.addView(appCheckCard, cardParams(0, 12));
        appCheckCard.addView(label("Firebase App Check｜這支測試手機只需設定一次", 15, Color.rgb(146, 64, 14), true));
        appCheckStatus = label("App Check 狀態：初始化中…", 13, Color.rgb(120, 53, 15), true);
        appCheckCard.addView(appCheckStatus);
        appCheckCard.addView(label("下面是這支手機的 Debug 權杖。只需要貼到 Firebase Console，不要傳給其他人。", 13, Color.rgb(120, 53, 15), false));
        appCheckToken = label("正在產生 Debug 權杖…", 13, Color.rgb(15, 23, 42), true);
        appCheckToken.setTextIsSelectable(true);
        appCheckCard.addView(appCheckToken);

        copyToken = button("複製 App Check 權杖", Color.rgb(217, 119, 6));
        copyToken.setEnabled(false);
        copyToken.setOnClickListener(v -> copyDebugToken());
        appCheckCard.addView(copyToken, margins(5, 2));

        Button openFirebase = button("開啟 Firebase App Check 設定", Color.rgb(124, 58, 237));
        openFirebase.setOnClickListener(v -> openFirebaseConsole());
        appCheckCard.addView(openFirebase, margins(2, 2));
        appCheckCard.addView(label("Firebase Console：採購單追蹤 → ⋮ → 管理偵錯權杖 → 新增 → 貼上上面的權杖 → 儲存。", 12, Color.rgb(120, 53, 15), false));

        LinearLayout testCard = card(Color.rgb(240, 253, 244), Color.rgb(34, 197, 94));
        content.addView(testCard, cardParams(0, 12));
        testCard.addView(label("一鍵確認 Gemini 是否真的可用", 15, Color.rgb(22, 101, 52), true));
        testConnection = button("測試 Gemini 連線", Color.rgb(22, 163, 74));
        testConnection.setOnClickListener(v -> testGemini());
        testCard.addView(testConnection, margins(3, 2));
        connectionResult = label("尚未測試。第一次請先把上面的 Debug 權杖加入 Firebase Console。", 13, Color.rgb(71, 85, 105), false);
        testCard.addView(connectionResult);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, dp(4), 0, dp(8));
        root.addView(scroll, scrollParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        Button enter = button("進入採購單追蹤", Color.rgb(37, 99, 235));
        enter.setOnClickListener(v -> openMain());
        actions.addView(enter, margins(0, 4));
        Button knowledge = button("辨識知識庫／廠商資料庫", Color.rgb(8, 145, 178));
        knowledge.setOnClickListener(v -> startActivity(new Intent(this, KnowledgeBaseActivity.class)));
        actions.addView(knowledge, margins(3, 0));
        root.addView(actions);

        setContentView(root);
    }

    private void refresh() {
        boolean installed = FirebaseAppCheckBootstrap.ensure(this);
        FirebaseAppCheckBootstrap.requestToken(this);
        String token = FirebaseAppCheckBootstrap.debugSecret(this);

        String projectId = "讀取失敗";
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String value = app.getOptions().getProjectId();
            if (value != null && !value.trim().isEmpty()) projectId = value.trim();
        } catch (Throwable ignored) {}
        projectInfo.setText("Firebase Project ID：" + projectId + "\nAndroid package：" + getPackageName() + "\nAI：Gemini 3.5 Flash（Firebase AI Logic）");

        if (!installed) {
            appCheckStatus.setText("App Check 狀態：初始化失敗");
            appCheckStatus.setTextColor(Color.rgb(220, 38, 38));
        } else if (token.isEmpty()) {
            appCheckStatus.setText("App Check 狀態：Debug Provider 已載入，正在產生權杖…");
            appCheckStatus.setTextColor(Color.rgb(217, 119, 6));
        } else {
            appCheckStatus.setText("App Check 狀態：Debug Provider 已載入 ✓　Debug 權杖已產生 ✓");
            appCheckStatus.setTextColor(Color.rgb(22, 163, 74));
        }

        if (token.isEmpty()) {
            appCheckToken.setText("正在產生 Debug 權杖…");
            copyToken.setEnabled(false);
        } else {
            appCheckToken.setText(token);
            copyToken.setEnabled(true);
        }
    }

    private void testGemini() {
        testConnection.setEnabled(false);
        testConnection.setText("正在測試 Gemini 連線…");
        connectionResult.setTextColor(Color.rgb(71, 85, 105));
        connectionResult.setText("正在確認 Firebase、App Check 與 Gemini…");
        FirebaseGeminiConnectionTester.test(this, (success, message) -> runOnUiThread(() -> {
            testConnection.setEnabled(true);
            testConnection.setText("測試 Gemini 連線");
            connectionResult.setText(message);
            connectionResult.setTextColor(success ? Color.rgb(22, 163, 74) : Color.rgb(220, 38, 38));
            if (success) appCheckStatus.setText("App Check 狀態：Gemini 實際請求已通過 ✓");
        }));
    }

    private void copyDebugToken() {
        String token = FirebaseAppCheckBootstrap.debugSecret(this);
        if (token.isEmpty()) {
            Toast.makeText(this, "權杖尚未產生，請稍後再按一次", Toast.LENGTH_SHORT).show();
            FirebaseAppCheckBootstrap.requestToken(this);
            handler.postDelayed(this::refresh, 700);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Firebase App Check Debug Token", token));
        Toast.makeText(this, "App Check 權杖已複製", Toast.LENGTH_SHORT).show();
    }

    private void openFirebaseConsole() {
        String url = FirebaseAppCheckBootstrap.consoleUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "找不到 Firebase App Check 網址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "無法開啟瀏覽器", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMain() {
        Intent intent = new Intent(this, EnhancedAiMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private LinearLayout card(int fill, int stroke) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(box(fill, stroke, 1));
        return view;
    }

    private LinearLayout.LayoutParams cardParams(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        v.setLineSpacing(0, 1.08f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(0, dp(5), 0, dp(5));
        return v;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(box(color, Color.rgb(15, 23, 42), 1));
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        b.setMinHeight(dp(48));
        return b;
    }

    private GradientDrawable box(int fill, int stroke, int width) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(width), stroke);
        g.setCornerRadius(dp(10));
        return g;
    }

    private LinearLayout.LayoutParams margins(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
