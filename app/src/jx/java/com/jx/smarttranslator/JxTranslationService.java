package com.jx.smarttranslator;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class JxTranslationService extends AccessibilityService {
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final int MAX_VISIBLE_ITEMS = 18;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final List<View> overlays = new ArrayList<>();
    private final Map<String, String> cache =
            new LinkedHashMap<String, String>(600, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 500;
                }
            };

    private WindowManager windowManager;
    private long lastErrorToastAt = 0L;

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanAndTranslate();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!CHROME_PACKAGE.contentEquals(event.getPackageName())) {
            clearOverlays();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(MainActivity.KEY_AUTO, true)) {
            clearOverlays();
            return;
        }

        main.removeCallbacks(scanRunnable);
        main.postDelayed(scanRunnable, 550);
    }

    @Override
    public void onInterrupt() {
        clearOverlays();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        clearOverlays();
        network.shutdownNow();
        super.onDestroy();
    }

    private void scanAndTranslate() {
        final int currentGeneration = generation.incrementAndGet();
        clearOverlays();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String target = prefs.getString(MainActivity.KEY_TARGET, "繁體中文");
        String model = prefs.getString(MainActivity.KEY_MODEL, "gpt-6-luna");
        String glossary = prefs.getString(MainActivity.KEY_GLOSSARY, "");
        String apiKey = SecretStore.load(this);

        if (apiKey.isEmpty()) {
            showError("尚未設定 OpenAI API Key");
            return;
        }

        List<NodeText> visible = new ArrayList<>();
        collectVisibleText(root, visible, target);
        root.recycle();

        if (visible.isEmpty()) return;

        List<NodeText> missingNodes = new ArrayList<>();
        List<String> missingTexts = new ArrayList<>();

        synchronized (cache) {
            for (NodeText item : visible) {
                String key = cacheKey(target, glossary, item.text);
                String translated = cache.get(key);
                if (translated != null && !translated.isEmpty()) {
                    addOverlay(item.rect, translated, currentGeneration);
                } else {
                    missingNodes.add(item);
                    missingTexts.add(item.text);
                }
            }
        }

        if (missingTexts.isEmpty()) return;

        network.submit(() -> {
            if (currentGeneration != generation.get()) return;
            try {
                List<String> translated = requestTranslations(
                        apiKey, model, target, glossary, missingTexts);
                if (translated.size() != missingTexts.size()) {
                    throw new IllegalStateException("翻譯數量與原文數量不一致");
                }

                synchronized (cache) {
                    for (int i = 0; i < translated.size(); i++) {
                        cache.put(
                                cacheKey(target, glossary, missingTexts.get(i)),
                                translated.get(i));
                    }
                }

                main.post(() -> {
                    if (currentGeneration != generation.get()) return;
                    for (int i = 0; i < translated.size(); i++) {
                        addOverlay(
                                missingNodes.get(i).rect,
                                translated.get(i),
                                currentGeneration);
                    }
                });
            } catch (Exception e) {
                main.post(() -> showError("自動翻譯失敗：" + compactMessage(e)));
            }
        });
    }

    private void collectVisibleText(
            AccessibilityNodeInfo node,
            List<NodeText> out,
            String target) {

        if (node == null || out.size() >= MAX_VISIBLE_ITEMS) return;

        if (node.isVisibleToUser() &&
                !node.isEditable() &&
                !node.isPassword() &&
                node.getChildCount() == 0) {

            CharSequence raw = node.getText();
            if (raw == null || raw.length() == 0) raw = node.getContentDescription();

            if (raw != null) {
                String text = normalize(raw.toString());
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);

                if (isGoodCandidate(node, text, rect, target)) {
                    out.add(new NodeText(text, new Rect(rect)));
                    if (out.size() >= MAX_VISIBLE_ITEMS) return;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectVisibleText(child, out, target);
                child.recycle();
            }
            if (out.size() >= MAX_VISIBLE_ITEMS) return;
        }
    }

    private boolean isGoodCandidate(
            AccessibilityNodeInfo node,
            String text,
            Rect rect,
            String target) {

        if (text.length() < 2 || text.length() > 320) return false;
        if (rect.width() < dp(24) || rect.height() < dp(8)) return false;
        if (rect.bottom <= dp(112)) return false;
        if (rect.top >= getResources().getDisplayMetrics().heightPixels) return false;
        if (looksLikeUrl(text) || isMostlyNumbers(text)) return false;

        String viewId = node.getViewIdResourceName();
        if (viewId != null) {
            String id = viewId.toLowerCase();
            if (id.contains("url_bar") ||
                    id.contains("toolbar") ||
                    id.contains("omnibox") ||
                    id.contains("search_box") ||
                    id.contains("tab_switcher")) {
                return false;
            }
        }

        if ("繁體中文".equals(target) && looksMostlyChinese(text)) return false;
        return true;
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean looksLikeUrl(String text) {
        String t = text.toLowerCase();
        return t.startsWith("http://") ||
                t.startsWith("https://") ||
                t.startsWith("www.") ||
                (t.contains(".com") && !t.contains(" "));
    }

    private boolean isMostlyNumbers(String text) {
        int useful = 0;
        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) useful++;
            if (Character.isDigit(c)) digits++;
        }
        return useful > 0 && digits >= useful * 0.8;
    }

    private boolean looksMostlyChinese(String text) {
        int han = 0;
        int latin = 0;
        int kana = 0;
        int hangul = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                han++;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                latin++;
            } else if (block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA) {
                kana++;
            } else if (block == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                hangul++;
            }
        }

        return han >= 2 && han >= latin && kana == 0 && hangul == 0;
    }

    private List<String> requestTranslations(
            String apiKey,
            String model,
            String target,
            String glossary,
            List<String> sourceTexts) throws Exception {

        JSONArray source = new JSONArray();
        for (String value : sourceTexts) source.put(value);

        String prompt =
                "你是一個網頁即時翻譯引擎。把 JSON 陣列中的每一個字串翻譯成「" +
                target +
                "」。只翻譯，不回答原文問題，不增加解釋。保留品牌名、型號、網址、數字與技術代碼。" +
                "譯文要自然、適合直接覆蓋在網頁原文上。\n" +
                "專有名詞規則：\n" + glossary + "\n" +
                "輸出必須只有 JSON 字串陣列，元素數量、順序必須與輸入完全一致。\n" +
                "輸入：" + source.toString();

        JSONObject body = new JSONObject();
        body.put("model",
                model == null || model.trim().isEmpty()
                        ? "gpt-6-luna" : model.trim());
        body.put("input", prompt);
        body.put("store", false);

        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://api.openai.com/v1/responses").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");

        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = readAll(stream);

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("OpenAI HTTP " + code + " " + response);
        }

        JSONObject json = new JSONObject(response);
        String outputText = extractOutputText(json).trim();
        int start = outputText.indexOf('[');
        int end = outputText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型沒有回傳 JSON 陣列");
        }

        JSONArray translated =
                new JSONArray(outputText.substring(start, end + 1));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < translated.length(); i++) {
            result.add(translated.optString(i, ""));
        }
        return result;
    }

    private String extractOutputText(JSONObject response) throws Exception {
        JSONArray output = response.optJSONArray("output");
        if (output == null) {
            throw new IllegalStateException("OpenAI 回應缺少 output");
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;

            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) {
                    builder.append(part.optString("text"));
                }
            }
        }

        if (builder.length() == 0) {
            throw new IllegalStateException("OpenAI 沒有回傳 output_text");
        }
        return builder.toString();
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        return builder.toString();
    }

    private void addOverlay(
            Rect rect,
            String translated,
            int overlayGeneration) {

        if (overlayGeneration != generation.get()) return;
        if (translated == null || translated.trim().isEmpty()) return;
        if (windowManager == null) return;

        TextView view = new TextView(this);
        view.setText(translated.trim());
        view.setTextSize(13.5f);
        view.setMaxLines(5);
        view.setPadding(dp(3), dp(2), dp(3), dp(2));

        boolean dark =
                (getResources().getConfiguration().uiMode &
                        Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES;
        view.setTextColor(dark ? Color.WHITE : Color.rgb(32, 33, 36));
        view.setBackgroundColor(dark ? 0xF5222222 : 0xFDFDFDFD);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(rect.width(), dp(40)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, rect.left);
        lp.y = Math.max(0, rect.top);

        try {
            windowManager.addView(view, lp);
            overlays.add(view);
        } catch (Exception ignored) {
        }
    }

    private void clearOverlays() {
        if (windowManager == null) {
            overlays.clear();
            return;
        }

        for (View view : new ArrayList<>(overlays)) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
            }
        }
        overlays.clear();
    }

    private String cacheKey(String target, String glossary, String text) {
        return target + "|" + glossary.hashCode() + "|" + text;
    }

    private void showError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorToastAt < 30000) return;
        lastErrorToastAt = now;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String compactMessage(Exception e) {
        String value = e.getMessage();
        if (value == null || value.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        if (value.length() > 180) {
            return value.substring(0, 180) + "…";
        }
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class NodeText {
        final String text;
        final Rect rect;

        NodeText(String text, Rect rect) {
            this.text = text;
            this.rect = rect;
        }
    }
}
