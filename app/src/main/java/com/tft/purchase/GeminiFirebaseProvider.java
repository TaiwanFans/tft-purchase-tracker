package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

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
import com.google.firebase.ai.type.Schema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Firebase AI Logic / Gemini cloud vision provider. No Gemini API key is embedded in the APK. */
public final class GeminiFirebaseProvider implements AiModelProvider {
    private static final String MODEL = "gemini-3.5-flash";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public String id() { return AiModelRegistry.GEMINI_FIREBASE; }
    @Override public String displayName() { return "Gemini 3.5 Flash（Firebase AI Logic）"; }

    @Override public boolean isReady(Context context) {
        try {
            FirebaseApp app = FirebaseApp.initializeApp(context.getApplicationContext());
            if (app == null) app = FirebaseApp.getInstance();
            return app != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void analyze(Context context, String imagePath, String ocrText,
                        AiProgressCallback progress, AiModelCallback callback) {
        try {
            notify(progress, 5, "準備 Firebase AI Logic 與 Gemini 雲端辨識");
            FirebaseApp app = FirebaseApp.initializeApp(context.getApplicationContext());
            if (app == null) app = FirebaseApp.getInstance();

            Bitmap image = decodeForGemini(imagePath);
            notify(progress, 15, "整理固定 8 欄格線、PP-OCR 與 ML Kit 證據");

            Schema itemSchema = Schema.obj(
                    Map.of(
                            "line_no", Schema.str(),
                            "description", Schema.str(),
                            "specification", Schema.str(),
                            "quantity", Schema.str(),
                            "unit", Schema.str(),
                            "unit_price", Schema.str(),
                            "subtotal", Schema.str(),
                            "delivery_date", Schema.str(),
                            "note", Schema.str(),
                            "needs_confirmation", Schema.array(Schema.str())
                    ),
                    List.of("needs_confirmation"));

            Schema rootSchema = Schema.obj(
                    Map.of(
                            "vendor", Schema.str(),
                            "location", Schema.str(),
                            "order_no", Schema.str(),
                            "purchase_date", Schema.str(),
                            "items", Schema.array(itemSchema),
                            "needs_confirmation", Schema.array(Schema.str())
                    ),
                    List.of("needs_confirmation"));

            GenerationConfig.Builder configBuilder = new GenerationConfig.Builder();
            configBuilder.responseMimeType = "application/json";
            configBuilder.responseSchema = rootSchema;
            GenerationConfig generationConfig = configBuilder.build();

            GenerativeModel ai = FirebaseAI.getInstance(app, GenerativeBackend.googleAI())
                    .generativeModel(MODEL, generationConfig);
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);

            String promptText = buildPrompt(ocrText);
            Content prompt = new Content.Builder()
                    .addImage(image)
                    .addText(promptText)
                    .build();

            notify(progress, 30, "Gemini 正在閱讀完整採購單影像");
            ListenableFuture<GenerateContentResponse> future = model.generateContent(prompt);
            Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
                @Override public void onSuccess(GenerateContentResponse result) {
                    try {
                        notify(progress, 88, "Gemini 已回傳結構化 JSON，準備固定版型驗證");
                        String text = result == null ? "" : result.getText();
                        if (text == null || text.trim().isEmpty()) {
                            callback.onFailure("Gemini 沒有回傳可解析的採購單資料");
                        } else {
                            callback.onSuccess(text.trim());
                        }
                    } finally {
                        try { image.recycle(); } catch (Throwable ignored) {}
                    }
                }

                @Override public void onFailure(Throwable t) {
                    try {
                        String msg = t == null || t.getMessage() == null ? "未知網路／Firebase 錯誤" : t.getMessage();
                        callback.onFailure("Gemini 雲端分析失敗：" + msg);
                    } finally {
                        try { image.recycle(); } catch (Throwable ignored) {}
                    }
                }
            }, executor);
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            callback.onFailure("Gemini/Firebase 初始化失敗：" + msg);
        }
    }

    private static String buildPrompt(String ocrText) {
        String evidence = compactEvidence(ocrText);
        return "你是台灣製造業『固定版型採購單』的專用資料擷取器。圖片是主要來源，OCR/格線證據用來核對。不得猜測。看不清楚就輸出空字串並把欄位加入 needs_confirmation。\n\n" +
                "【固定版型】\n" +
                "頁首左側：供應廠商、廠商地址、電話。頁首右側：採購單號、採購日期、聯絡人。\n" +
                "商品表固定 8 欄：項次｜品名規格明細｜數量｜單位｜單價｜小計｜交貨日期｜備註。\n" +
                "同一橫列才是一個品項，禁止跨列或跨欄借數字。\n\n" +
                "【強制規則】\n" +
                "1. vendor 只能取『供應廠商：』後方公司名稱。頁首『全益畜牧器具有限公司／全益畜牧器具公司／台灣電扇科技有限公司／台灣電扇／全益台灣電扇』是我方，永遠不是 vendor。\n" +
                "2. location 只能取『廠商地址：』後方地址。\n" +
                "3. order_no 只能取右上『採購單號：』後完整號碼，不得用日期、電話或項次拼接。\n" +
                "4. purchase_date 只取『採購日期：』；delivery_date 只取每個商品列第 7 欄交貨日期。\n" +
                "5. quantity 只取數量欄；品名規格中的 220V、60HZ、IP55、6英吋、90mm、16芯、尺寸不是數量。\n" +
                "6. unit_price 只取單價欄，subtotal 只取小計欄。不得跨列補值。\n" +
                "7. quantity × unit_price 與 subtotal 不一致時，不要用 subtotal÷unit_price 反推新數量；保留能直接看清楚的值並把衝突欄位加入 needs_confirmation。\n" +
                "8. 『以下空白』以及其後空白列不是商品。頁尾本頁小計、稅額、總計、條款、FAXED、審核、經辦人、廠商確認全部不是 item。\n" +
                "9. 『前單取消／以此單為準／數量修改／已備好電線／請報價』等行政列，若沒有實際採購金額，不要當商品。\n" +
                "10. line_no 保留影像原位數，例如 001 就是 001、0001 就是 0001。\n" +
                "11. description 放品名主體；specification 放尺寸、電壓、型號、材質、附帶說明等規格。\n" +
                "12. needs_confirmation 必須是欄位級，例如 item[1].quantity；禁止把整張全部標成待確認。\n" +
                "13. FIXED_PURCHASE_ORDER / FIXED_ROW / TABLE_GRID 若標記可靠，代表程式已按實體格線綁定欄位；除非圖片能明確證明該格 OCR 字元錯誤，否則不得把值移到其他欄或其他列。\n\n" +
                "【本機 OCR／固定格線證據】\n" + evidence + "\n【證據結束】\n\n" +
                "只依 response schema 回傳 JSON。不要 Markdown、不要解釋、不要新增 schema 之外欄位。";
    }

    private static String compactEvidence(String input) {
        if (input == null || input.trim().isEmpty()) return "[NO_OCR_EVIDENCE]";
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\\n")) {
            if (line.contains("region=FOOTER")) continue;
            out.append(line).append('\n');
            if (out.length() >= 36000) break;
        }
        if (out.length() > 36000) out.setLength(36000);
        return out.toString();
    }

    private static Bitmap decodeForGemini(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取採購單影像");
        int sample = 1;
        while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 4200) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(path, opts);
        if (bm == null) throw new Exception("無法解碼採購單影像");
        int maxSide = Math.max(bm.getWidth(), bm.getHeight());
        if (maxSide > 3200) {
            float scale = 3200f / maxSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bm,
                    Math.max(1, Math.round(bm.getWidth() * scale)),
                    Math.max(1, Math.round(bm.getHeight() * scale)), true);
            if (scaled != bm) bm.recycle();
            bm = scaled;
        }
        return bm;
    }

    private static void notify(AiProgressCallback cb, int percent, String stage) {
        if (cb != null) cb.onProgress(percent, stage);
    }
}
