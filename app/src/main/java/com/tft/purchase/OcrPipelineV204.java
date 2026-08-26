package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * V2.0.6 purchase-order recognizer.
 *
 * Gemma vision is now the ONLY field/item decision maker. OCR is intentionally not run here,
 * because OCR row parsing previously contaminated good AI output (for example 200 -> 2 and
 * footer instructions becoming fake items 003/004/005).
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
        if (!GemmaBridge.isReady(context)) {
            callback.onFailure(new IllegalStateException("請先完成 Gemma AI 模型安裝，再進行採購單辨識。此版本不再用 OCR 自動填欄位，以避免錯誤資料。"));
            return;
        }

        Bitmap prepared = null;
        File temp = null;
        try {
            prepared = prepareForGemma(source);
            temp = new File(context.getCacheDir(), "gemma_purchase_only_" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                if (!prepared.compress(Bitmap.CompressFormat.JPEG, 100, fos)) {
                    throw new Exception("AI 暫存圖片失敗");
                }
            }

            final File imageFile = temp;
            final Bitmap preparedFinal = prepared;
            GemmaBridge.analyze(context, imageFile.getAbsolutePath(), "", new GemmaAiCallback() {
                @Override public void onSuccess(String response) {
                    try {
                        PurchaseOcrParser.ParsedPurchase parsed = GemmaJsonParser.parseGemmaOnly(response);
                        if (!GemmaJsonParser.hasUsefulData(parsed)) {
                            callback.onFailure(new Exception("Gemma 沒有可靠讀到採購單欄位。請確認圖片清楚、方向正確後重新辨識。"));
                            return;
                        }
                        callback.onSuccess(parsed);
                    } catch (Throwable t) {
                        callback.onFailure(t instanceof Exception ? (Exception)t : new Exception(t));
                    } finally {
                        try { imageFile.delete(); } catch (Throwable ignored) {}
                        if (preparedFinal != source && !preparedFinal.isRecycled()) {
                            try { preparedFinal.recycle(); } catch (Throwable ignored) {}
                        }
                    }
                }

                @Override public void onFailure(String error) {
                    try { imageFile.delete(); } catch (Throwable ignored) {}
                    if (preparedFinal != source && !preparedFinal.isRecycled()) {
                        try { preparedFinal.recycle(); } catch (Throwable ignored) {}
                    }
                    callback.onFailure(new Exception(error));
                }
            });
        } catch (Throwable t) {
            if (temp != null) try { temp.delete(); } catch (Throwable ignored) {}
            if (prepared != null && prepared != source && !prepared.isRecycled()) {
                try { prepared.recycle(); } catch (Throwable ignored) {}
            }
            callback.onFailure(t instanceof Exception ? (Exception)t : new Exception(t));
        }
    }

    /**
     * The user's purchase orders use a stable A4 form. All useful header + item-table data is in
     * the upper part of the page, while the lower area contains terms, stamps and signatures that
     * must never become items. Cropping that noise also makes printed characters larger to Gemma.
     */
    private static Bitmap prepareForGemma(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= 0 || h <= 0) return source;

        // Keep enough space to include the entire item table and 本頁小計 boundary, while removing
        // most footer clauses/signatures that caused false 003/004/005 rows.
        int cropH = Math.max(1, Math.min(h, Math.round(h * 0.75f)));
        Bitmap crop = Bitmap.createBitmap(source, 0, 0, w, cropH);

        // Give small gallery images more effective text resolution; cap large camera photos to
        // control memory and inference time on-device.
        int targetW;
        if (w < 1600) targetW = 1600;
        else if (w > 2200) targetW = 2200;
        else targetW = w;

        if (targetW == w) return crop;
        int targetH = Math.max(1, Math.round(cropH * (targetW / (float)w)));
        Bitmap scaled = Bitmap.createScaledBitmap(crop, targetW, targetH, true);
        if (scaled != crop && !crop.isRecycled()) crop.recycle();
        return scaled;
    }
}
