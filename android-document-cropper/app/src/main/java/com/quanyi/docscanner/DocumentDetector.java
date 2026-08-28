package com.quanyi.docscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

/** Stable boundary between scan workflow and OpenCV document detection. */
public final class DocumentDetector {
    public PointF[] detect(Bitmap bitmap) { return ImageUtils.detectDocumentCorners(bitmap); }
    public PointF[] defaultCorners(Bitmap bitmap) { return ImageUtils.defaultCorners(bitmap); }
}
