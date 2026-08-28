package com.quanyi.docscanner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;

/** HQ current-page preview rebuilt from original URI, not lossy cache JPEG. */
public final class PreviewRenderer {
    private final ContentResolver resolver;
    private final DocumentDetector detector;
    public PreviewRenderer(ContentResolver resolver, DocumentDetector detector) {
        this.resolver = resolver;
        this.detector = detector;
    }

    public Bitmap render(Uri uri, float[] normalizedCrop, boolean aiAlreadyCropped, int maxDimension) throws Exception {
        Bitmap original = null;
        Bitmap cropped = null;
        try {
            original = ImageUtils.loadBitmapExact(resolver, uri, maxDimension);
            PointF[] points = CropController.restore(normalizedCrop, original);
            if (points == null) {
                points = aiAlreadyCropped ? detector.defaultCorners(original) : detector.detect(original);
                if (points == null) points = detector.defaultCorners(original);
            }
            cropped = ImageUtils.perspectiveCrop(original, points, maxDimension);
            Bitmap result = cropped;
            cropped = null;
            return result;
        } finally {
            if (cropped != null && !cropped.isRecycled()) cropped.recycle();
            if (original != null && !original.isRecycled()) original.recycle();
        }
    }
}
