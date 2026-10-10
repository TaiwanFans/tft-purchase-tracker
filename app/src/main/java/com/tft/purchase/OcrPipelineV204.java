package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * Legacy compatibility entry point. It delegates to V2.0.13's hybrid OCR pipeline:
 * document correction -> PP-OCRv6 Small + ML Kit -> active local AiModelProvider.
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
                if (!source.compress(Bitmap.CompressFormat.JPEG, 97, fos)) {
                    throw new Exception("AI 暫存圖片失敗");
                }
            }
            final File imageFile = temp;
            HybridOcrBridge.recognize(context, imageFile.getAbsolutePath(), new HybridOcrBridge.Callback() {
                @Override public void onSuccess(HybridOcrBridge.Result result) {
                    String modelPath = result.preparedImagePath == null || result.preparedImagePath.isEmpty()
                            ? imageFile.getAbsolutePath() : result.preparedImagePath;
                    runModel(context, provider, imageFile, modelPath, result.evidence, result, callback);
                }

                @Override public void onFailure(String error) {
                    runModel(context, provider, imageFile, imageFile.getAbsolutePath(),
                            "[OCR_ERROR] " + (error == null ? "雙 OCR 未完成" : error), null, callback);
                }
            });
        } catch (Throwable t) {
            if (temp != null) try { temp.delete(); } catch (Throwable ignored) {}
            callback.onFailure(t instanceof Exception ? (Exception)t : new Exception(t));
        }
    }

    private static void runModel(Context context, AiModelProvider provider, File originalTemp,
                                 String modelImagePath, String ocrEvidence,
                                 HybridOcrBridge.Result hybridResult, Callback callback) {
        provider.analyze(context, modelImagePath, ocrEvidence, null, new AiModelCallback() {
            @Override public void onSuccess(String response) {
                try {
                    PurchaseOcrParser.ParsedPurchase parsed = AiJsonParser.parse(response, ocrEvidence);
                    callback.onSuccess(parsed);
                } catch (Throwable t) {
                    callback.onFailure(t instanceof Exception ? (Exception)t : new Exception(t));
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                    try { originalTemp.delete(); } catch (Throwable ignored) {}
                }
            }

            @Override public void onFailure(String error) {
                if (hybridResult != null) hybridResult.cleanup();
                try { originalTemp.delete(); } catch (Throwable ignored) {}
                callback.onFailure(new Exception(error == null ? "本機 AI 分析失敗" : error));
            }
        });
    }
}
