package com.quanyi.docscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

/** Non-destructive crop state. Only normalized quad coordinates are persisted. */
public final class CropController {
    private CropController() {}

    public static float[] normalize(PointF[] points, Bitmap bitmap) {
        if (bitmap == null || points == null || points.length != 4) return null;
        float w = Math.max(1f, bitmap.getWidth());
        float h = Math.max(1f, bitmap.getHeight());
        float[] out = new float[8];
        for (int i=0;i<4;i++) {
            out[i*2] = clamp01(points[i].x / w);
            out[i*2+1] = clamp01(points[i].y / h);
        }
        return out;
    }

    public static PointF[] restore(float[] normalized, Bitmap bitmap) {
        if (bitmap == null || normalized == null || normalized.length != 8) return null;
        PointF[] out = new PointF[4];
        for (int i=0;i<4;i++)
            out[i] = new PointF(normalized[i*2] * bitmap.getWidth(), normalized[i*2+1] * bitmap.getHeight());
        return out;
    }

    public static PointF[] scale(PointF[] points, float sx, float sy) {
        if (points == null || points.length != 4) return null;
        PointF[] out = new PointF[4];
        for (int i=0;i<4;i++) out[i] = new PointF(points[i].x*sx, points[i].y*sy);
        return out;
    }

    public static float[] fullFrame() { return new float[]{0f,0f, 1f,0f, 1f,1f, 0f,1f}; }
    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
}
