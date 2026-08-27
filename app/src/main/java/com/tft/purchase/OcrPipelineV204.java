package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * Legacy compatibility entry point. It now delegates to the same replaceable pipeline used by
 * AiAnalysisService: ML Kit OCR first, then the active local AiModelProvider.
 */
public final class OcrPipelineV204 {
    private OcrPipelineV204() {}

    public interface Callback {
        void onSuccess(PurchaseOcrParser.ParsedPurchase parsed);
        void onFailure(Exception e);
    }

    public static void recognize(Bitmap source, Callback callback) {
        if (source == null) {
            callback.onFailure(new IllegalArgumentException("圖片無法解碼"));
            return;
        }

        Context context = TftApplication.appContext();
        if (context == null) {
            callback.onFailure(new IllegalStateException("APP 尚未初始化"));
            return;
        }
        AiModelProvider provider = AiModelRegistry.active(context);
        if (!provider.isReady(context)) {
            callback.onFailure(new IllegalStateException("請先完成 MiniCPM-V 4.6 模型下載與驗證"));
            return;
        }

        File temp = null;
        try {
            temp = new File(context.getCacheDir(), "purchase_ai_" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                if (!source.compress(Bitmap.CompressFormat.JPEG, 96, fos)) {
                    throw new Exception("AI 暫存圖片失敗");
                }
            }
            final File imageFile = temp;
            MlKitOcrBridge.recognize(context, imageFile.getAbsolutePath(), new MlKitOcrBridge.Callback() {
                @Override public void onSuccess(String text) {
                    runModel(context, provider, imageFile, text, callback);
                }

                @Override public void onFailure(String error) {
                    // MiniCPM can still inspect the original image when OCR itself fails.
                    runModel(context, provider, imageFile, "", callback);
                }
            });
        } catch (Throwable t) {
            if (temp != null) try { temp.delete(); } catch (Throwable ignored) {}
            callback.onFailure(t instanceof Exception ? (Exception)t : new Exception(t));
        }
    }

    private static void runModel(Context context, AiModelProvider provider, File imageFile,
                                 String ocrText, Callback callback) {
        provider.analyze(context, imageFile.getAbsolutePath(), ocrText, null, new AiModelCallback() {
            @Override public void onSuccess(String response) {
                try {
                    PurchaseOcrParser.ParsedPurchase parsed = AiJsonParser.parse(response, ocrText);
                    callback.onSuccess(parsed);
                } catch (Throwable t) {
                    callback.onFailure(t instanceof Exception ? (Exception)t : new Exception(t));
                } finally {
                    try { imageFile.delete(); } catch (Throwable ignored) {}
                }
            }

            @Override public void onFailure(String error) {
                try { imageFile.delete(); } catch (Throwable ignored) {}
                callback.onFailure(new Exception(error == null ? "本機 AI 分析失敗" : error));
            }
        });
    }
}
