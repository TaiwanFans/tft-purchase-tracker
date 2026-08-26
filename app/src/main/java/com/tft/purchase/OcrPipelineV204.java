package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * Hybrid document recognizer:
 * 1) ML Kit OCR on original image
 * 2) ML Kit OCR on enhanced document image
 * 3) If Gemma is installed, Gemma directly sees the image + OCR text and returns structured JSON
 * 4) Validate/merge Gemma fields with OCR so uncertain AI output cannot erase usable OCR data.
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
        TextRecognizer originalRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        InputImage originalImage = InputImage.fromBitmap(source, 0);
        originalRecognizer.process(originalImage)
                .addOnSuccessListener(originalText -> {
                    PurchaseOcrParser.ParsedPurchase original = PurchaseOcrParserV204.parse(originalText, source.getWidth(), source.getHeight());
                    Bitmap enhanced;
                    try { enhanced = enhanceDocument(source); }
                    catch (Throwable t) {
                        originalRecognizer.close();
                        maybeRunGemma(source, original, callback);
                        return;
                    }
                    TextRecognizer enhancedRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                    enhancedRecognizer.process(InputImage.fromBitmap(enhanced, 0))
                            .addOnSuccessListener(enhancedText -> {
                                PurchaseOcrParser.ParsedPurchase second = PurchaseOcrParserV204.parse(enhancedText, enhanced.getWidth(), enhanced.getHeight());
                                PurchaseOcrParser.ParsedPurchase merged = PurchaseOcrParserV204.merge(original, second);
                                originalRecognizer.close();
                                enhancedRecognizer.close();
                                enhanced.recycle();
                                maybeRunGemma(source, merged, callback);
                            })
                            .addOnFailureListener(e -> {
                                originalRecognizer.close();
                                enhancedRecognizer.close();
                                enhanced.recycle();
                                maybeRunGemma(source, original, callback);
                            });
                })
                .addOnFailureListener(e -> {
                    originalRecognizer.close();
                    callback.onFailure(e instanceof Exception ? (Exception)e : new Exception(e));
                });
    }

    private static void maybeRunGemma(Bitmap source, PurchaseOcrParser.ParsedPurchase ocr, Callback callback) {
        Context context = TftApplication.appContext();
        if (context == null || !GemmaBridge.isReady(context)) {
            callback.onSuccess(ocr);
            return;
        }
        File temp = null;
        try {
            temp = new File(context.getCacheDir(), "gemma_purchase_" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                if (!source.compress(Bitmap.CompressFormat.JPEG, 96, fos)) throw new Exception("AI 暫存圖片失敗");
            }
            final File imageFile = temp;
            GemmaBridge.analyze(context, imageFile.getAbsolutePath(), ocr.rawText == null ? "" : ocr.rawText, new GemmaAiCallback() {
                @Override public void onSuccess(String response) {
                    try {
                        PurchaseOcrParser.ParsedPurchase merged = GemmaJsonParser.merge(ocr, response);
                        callback.onSuccess(merged);
                    } catch (Throwable t) {
                        callback.onSuccess(ocr);
                    } finally {
                        try { imageFile.delete(); } catch (Throwable ignored) {}
                    }
                }

                @Override public void onFailure(String error) {
                    try { imageFile.delete(); } catch (Throwable ignored) {}
                    // Gemma failure must never block the user's workflow: keep OCR result.
                    if (ocr.rawText == null) ocr.rawText = "";
                    ocr.rawText += "\n\n[Gemma AI 未完成]\n" + error;
                    callback.onSuccess(ocr);
                }
            });
        } catch (Throwable t) {
            if (temp != null) try { temp.delete(); } catch (Throwable ignored) {}
            callback.onSuccess(ocr);
        }
    }

    /** Geometry stays unchanged so OCR bounding boxes still match known form columns. */
    private static Bitmap enhanceDocument(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                int v = (int)((gray - 128) * 1.55f + 128);
                if (v > 220) v = 255;
                else if (v < 60) v = 0;
                else v = Math.max(0, Math.min(255, v));
                row[x] = Color.rgb(v, v, v);
            }
            out.setPixels(row, 0, w, 0, y, w, 1);
        }
        return out;
    }
}
