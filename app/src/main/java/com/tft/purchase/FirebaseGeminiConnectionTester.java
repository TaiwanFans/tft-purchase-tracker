package com.tft.purchase;

import android.content.Context;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerationConfig;
import com.google.firebase.ai.type.GenerativeBackend;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-tap human-readable Firebase AI Logic / Gemini connectivity test for debug builds. */
public final class FirebaseGeminiConnectionTester {
    private static final String MODEL = "gemini-3.5-flash";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private FirebaseGeminiConnectionTester() {}

    public static void test(Context context, Callback callback) {
        try {
            Context appContext = context.getApplicationContext();
            FirebaseApp app = FirebaseApp.initializeApp(appContext);
            if (app == null) app = FirebaseApp.getInstance();

            if (!FirebaseAppCheckBootstrap.ensure(appContext)) {
                finish(callback, false, "Firebase App Check 初始化失敗。請先確認 Firebase 設定後再試。");
                return;
            }
            FirebaseAppCheckBootstrap.requestToken(appContext);

            GenerationConfig config = new GenerationConfig.Builder().build();
            GenerativeModel ai = FirebaseAI.getInstance(app, GenerativeBackend.googleAI())
                    .generativeModel(MODEL, config);
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);
            Content prompt = new Content.Builder()
                    .addText("這是採購單追蹤 APP 的連線測試。請只回覆 OK。").build();

            ListenableFuture<GenerateContentResponse> future = model.generateContent(prompt);
            Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
                @Override public void onSuccess(GenerateContentResponse result) {
                    String text = result == null ? "" : result.getText();
                    if (text == null || text.trim().isEmpty()) {
                        finish(callback, false, "Firebase 已連上，但 Gemini 沒有回傳內容。請再測試一次。");
                    } else {
                        finish(callback, true, "Gemini 連線成功 ✓\nFirebase AI Logic 可用 ✓\nApp Check 請求已通過 ✓");
                    }
                }

                @Override public void onFailure(Throwable t) {
                    finish(callback, false, humanMessage(appContext, t));
                }
            }, EXECUTOR);
        } catch (Throwable t) {
            finish(callback, false, humanMessage(context.getApplicationContext(), t));
        }
    }

    private static void finish(Callback callback, boolean success, String message) {
        if (callback != null) callback.onResult(success, message);
    }

    private static String humanMessage(Context context, Throwable t) {
        String raw = t == null || t.getMessage() == null ? "" : t.getMessage().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        String token = FirebaseAppCheckBootstrap.debugSecret(context);

        if (lower.contains("app check") || lower.contains("403") || lower.contains("permission") || lower.contains("unauth")) {
            if (!token.isEmpty()) {
                return "Firebase 還沒有接受這支手機的 App Check Debug 權杖。\n"
                        + "請按『複製 App Check 權杖』→『開啟 Firebase App Check 設定』→ 管理偵錯權杖 → 新增 → 貼上 → 儲存，然後回 APP 再按一次測試。";
            }
            return "App Check 尚未準備完成。請先等 Debug 權杖出現，再按一次測試。";
        }
        if (lower.contains("network") || lower.contains("timeout") || lower.contains("unable to resolve") || lower.contains("internet")) {
            return "目前無法連上 Firebase / Gemini。請確認手機有網路後再試。";
        }
        if (lower.contains("404") || lower.contains("not found") || lower.contains("model")) {
            return "Firebase 已收到請求，但 Gemini 模型目前不可用。請保留此畫面訊息交給開發端處理。"
                    + (raw.isEmpty() ? "" : "\n錯誤：" + raw);
        }
        return "Gemini 連線測試失敗。" + (raw.isEmpty() ? "請稍後再試。" : "\n錯誤：" + raw);
    }
}
