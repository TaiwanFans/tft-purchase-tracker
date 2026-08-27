package com.tft.purchase;

import android.content.Context;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;

/** Google ML Kit runs first and supplies raw OCR evidence to the local vision model. */
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
            InputImage image = InputImage.fromFilePath(context, Uri.fromFile(new File(imagePath)));
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        try { callback.onSuccess(result == null ? "" : result.getText()); }
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
}
