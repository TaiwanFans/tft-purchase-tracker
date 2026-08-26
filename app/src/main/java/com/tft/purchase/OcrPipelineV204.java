package com.tft.purchase;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** Runs OCR twice: original image + high-contrast document image, then merges. */
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
                        callback.onSuccess(original);
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
                                callback.onSuccess(merged);
                            })
                            .addOnFailureListener(e -> {
                                originalRecognizer.close();
                                enhancedRecognizer.close();
                                enhanced.recycle();
                                callback.onSuccess(original);
                            });
                })
                .addOnFailureListener(e -> {
                    originalRecognizer.close();
                    callback.onFailure(e instanceof Exception ? (Exception)e : new Exception(e));
                });
    }

    /**
     * Keeps the geometry exactly the same so OCR bounding boxes still map to the
     * known purchase-order columns. Designed for black/gray text on white paper.
     */
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
