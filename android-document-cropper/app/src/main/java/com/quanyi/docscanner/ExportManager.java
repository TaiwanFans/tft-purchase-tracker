package com.quanyi.docscanner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;

/** Full-resolution pipeline: original URI -> quad -> perspective -> filter -> one JPEG encode. */
public final class ExportManager {
    private final ContentResolver resolver;
    private final DocumentDetector detector;
    private final DocumentFilterEngine filters;
    public ExportManager(ContentResolver resolver, DocumentDetector detector, DocumentFilterEngine filters) {
        this.resolver = resolver;
        this.detector = detector;
        this.filters = filters;
    }

    public Bitmap renderFinal(Uri uri, float[] normalizedCrop, boolean aiAlreadyCropped,
                              FilterPreset preset, int maxDimension) throws Exception {
        Bitmap original = null;
        Bitmap cropped = null;
        Bitmap enhanced = null;
        try {
            original = ImageUtils.loadBitmapExact(resolver, uri, maxDimension);
            PointF[] points = CropController.restore(normalizedCrop, original);
            if (points == null) {
                points = aiAlreadyCropped ? detector.defaultCorners(original) : detector.detect(original);
                if (points == null) points = detector.defaultCorners(original);
            }
            cropped = ImageUtils.perspectiveCrop(original, points, maxDimension);
            enhanced = filters.apply(cropped, preset);
            if (enhanced == null) enhanced = cropped.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap result = enhanced;
            enhanced = null;
            return result;
        } finally {
            if (enhanced != null && !enhanced.isRecycled()) enhanced.recycle();
            if (cropped != null && !cropped.isRecycled()) cropped.recycle();
            if (original != null && !original.isRecycled()) original.recycle();
        }
    }

    public void writeJpeg(Bitmap bitmap, File file, int quality) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, Math.max(90, Math.min(100, quality)), stream))
                throw new Exception("encode");
        }
    }
}
