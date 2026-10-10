package com.tft.purchase;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.util.Locale;

/**
 * Secondary OCR. ML Kit is intentionally used as cross-check evidence instead of the sole source
 * of truth. Line coordinates are preserved so the local VLM can reason about table layout.
 */
public final class MlKitOcrBridge {
    private MlKitOcrBridge() {}

    public interface Callback {
        void onSuccess(String text);
        void onFailure(String error);
    }

    public static void recognize(Context context, String imagePath, Callback callback) {
        final TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        try {
            File file = new File(imagePath);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, bounds);
            final int imageW = Math.max(1, bounds.outWidth);
            final int imageH = Math.max(1, bounds.outHeight);

            InputImage image = InputImage.fromFilePath(context, Uri.fromFile(file));
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        try { callback.onSuccess(toStructured(result, imageW, imageH)); }
                        finally { recognizer.close(); }
                    })
                    .addOnFailureListener(e -> {
                        try {
                            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                            callback.onFailure(msg);
                        } finally { recognizer.close(); }
                    });
        } catch (Throwable t) {
            recognizer.close();
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            callback.onFailure(msg);
        }
    }

    private static String toStructured(Text result, int imageW, int imageH) {
        if (result == null) return "[MLKIT]\n";
        StringBuilder sb = new StringBuilder("[MLKIT_CHINESE]\n");
        int count = 0;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String text = line.getText() == null ? "" : line.getText().trim();
                if (text.isEmpty()) continue;
                Rect r = line.getBoundingBox();
                if (r == null) {
                    sb.append(text.replace('\n', ' ')).append('\n');
                } else {
                    float x = clamp(r.left / (float) imageW);
                    float y = clamp(r.top / (float) imageH);
                    float w = clamp(r.width() / (float) imageW);
                    float h = clamp(r.height() / (float) imageH);
                    float cx = clamp(x + w / 2f);
                    float cy = clamp(y + h / 2f);
                    sb.append("[region=").append(region(cx, cy))
                            .append(" x=").append(f(x))
                            .append(" y=").append(f(y))
                            .append(" w=").append(f(w))
                            .append(" h=").append(f(h))
                            .append("] ").append(text.replace('\n', ' ')).append('\n');
                }
                if (++count >= 260) return sb.toString();
            }
        }
        if (count == 0 && result.getText() != null) sb.append(result.getText());
        return sb.toString();
    }

    private static String region(float x, float y) {
        if (y < 0.30f && x < 0.55f) return "HEADER_LEFT";
        if (y < 0.30f) return "HEADER_RIGHT";
        if (y < 0.76f) return "ITEM_TABLE";
        return "FOOTER";
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static String f(float v) { return String.format(Locale.US, "%.3f", v); }
}
