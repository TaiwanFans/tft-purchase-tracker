package com.tft.purchase;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight wired-table detector for purchase/quotation documents.
 * It uses morphology to isolate long horizontal/vertical rules, then exposes
 * normalized grid boundaries so OCR words can be permanently bound to a row/cell.
 */
public final class TableGridDetector {
    private TableGridDetector() {}

    public static final class Grid {
        public final List<Float> xLines;
        public final List<Float> yLines;
        public final boolean reliable;
        public final String reason;

        Grid(List<Float> x, List<Float> y, boolean reliable, String reason) {
            this.xLines = Collections.unmodifiableList(new ArrayList<>(x));
            this.yLines = Collections.unmodifiableList(new ArrayList<>(y));
            this.reliable = reliable;
            this.reason = reason == null ? "" : reason;
        }

        public int columnCount() { return Math.max(0, xLines.size() - 1); }
        public int rowCount() { return Math.max(0, yLines.size() - 1); }

        public int column(float x) { return interval(xLines, x); }
        public int row(float y) { return interval(yLines, y); }

        public String summary() {
            return String.format(Locale.US,
                    "reliable=%s columns=%d rows=%d xSpan=%.3f ySpan=%.3f reason=%s",
                    reliable, columnCount(), rowCount(), span(xLines), span(yLines), reason);
        }
    }

    public static Grid detect(String correctedImagePath) {
        if (correctedImagePath == null || correctedImagePath.trim().isEmpty()) {
            return empty("image_path_empty");
        }
        if (!ensureOpenCv()) return empty("opencv_unavailable");

        Bitmap bm = null;
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat bw = new Mat();
        Mat horizontal = new Mat();
        Mat vertical = new Mat();
        Mat hKernel = new Mat();
        Mat vKernel = new Mat();
        try {
            bm = decodeBounded(correctedImagePath, 1800);
            if (bm == null) return empty("decode_failed");
            Utils.bitmapToMat(bm, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.adaptiveThreshold(gray, bw, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 15);

            int cols = bw.cols(), rows = bw.rows();
            if (cols < 400 || rows < 600) return empty("image_too_small");

            int horizontalLen = Math.max(28, cols / 18);
            int verticalLen = Math.max(28, rows / 24);
            hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(horizontalLen, 1));
            vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, verticalLen));
            Imgproc.morphologyEx(bw, horizontal, Imgproc.MORPH_OPEN, hKernel);
            Imgproc.morphologyEx(bw, vertical, Imgproc.MORPH_OPEN, vKernel);

            // Connect slightly broken rules after JPEG/scan noise.
            Imgproc.dilate(horizontal, horizontal,
                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 1)));
            Imgproc.dilate(vertical, vertical,
                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, 7)));

            boolean[] xHit = new boolean[cols];
            boolean[] yHit = new boolean[rows];
            int minVerticalPixels = Math.max(30, Math.round(rows * 0.13f));
            int minHorizontalPixels = Math.max(80, Math.round(cols * 0.34f));
            for (int x = 0; x < cols; x++) xHit[x] = Core.countNonZero(vertical.col(x)) >= minVerticalPixels;
            for (int y = 0; y < rows; y++) yHit[y] = Core.countNonZero(horizontal.row(y)) >= minHorizontalPixels;

            List<Float> xs = cluster(xHit, cols, 0.015f, 0.985f);
            List<Float> ys = cluster(yHit, rows, 0.08f, 0.94f);
            xs = mergeClose(xs, 0.006f);
            ys = mergeClose(ys, 0.0045f);

            boolean reliable = xs.size() >= 5 && ys.size() >= 5
                    && span(xs) >= 0.55f && span(ys) >= 0.20f;
            String reason = reliable ? "wired_grid_detected"
                    : "insufficient_lines(x=" + xs.size() + ",y=" + ys.size() + ")";
            return new Grid(xs, ys, reliable, reason);
        } catch (Throwable t) {
            String m = t.getMessage();
            return empty("exception:" + (m == null ? t.getClass().getSimpleName() : m));
        } finally {
            try { rgba.release(); } catch (Throwable ignored) {}
            try { gray.release(); } catch (Throwable ignored) {}
            try { bw.release(); } catch (Throwable ignored) {}
            try { horizontal.release(); } catch (Throwable ignored) {}
            try { vertical.release(); } catch (Throwable ignored) {}
            try { hKernel.release(); } catch (Throwable ignored) {}
            try { vKernel.release(); } catch (Throwable ignored) {}
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
    }

    private static Bitmap decodeBounded(String path, int maxLongSide) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, b);
        if (b.outWidth <= 0 || b.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(b.outWidth, b.outHeight) / sample > maxLongSide) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o);
    }

    private static List<Float> cluster(boolean[] hits, int length, float min, float max) {
        List<Float> out = new ArrayList<>();
        int i = 0;
        while (i < hits.length) {
            if (!hits[i]) { i++; continue; }
            int a = i;
            while (i + 1 < hits.length && hits[i + 1]) i++;
            int b = i;
            float p = ((a + b) * 0.5f) / Math.max(1f, length - 1f);
            if (p >= min && p <= max) out.add(p);
            i++;
        }
        return out;
    }

    private static List<Float> mergeClose(List<Float> in, float gap) {
        if (in.isEmpty()) return in;
        List<Float> out = new ArrayList<>();
        float sum = in.get(0); int n = 1; float last = in.get(0);
        for (int i = 1; i < in.size(); i++) {
            float v = in.get(i);
            if (v - last <= gap) { sum += v; n++; }
            else { out.add(sum / n); sum = v; n = 1; }
            last = v;
        }
        out.add(sum / n);
        return out;
    }

    private static int interval(List<Float> lines, float value) {
        if (lines == null || lines.size() < 2) return -1;
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (value >= lines.get(i) && value < lines.get(i + 1)) return i;
        }
        return -1;
    }

    private static float span(List<Float> xs) {
        if (xs == null || xs.size() < 2) return 0f;
        return xs.get(xs.size() - 1) - xs.get(0);
    }

    private static boolean ensureOpenCv() {
        try { System.loadLibrary("opencv_java4"); return true; }
        catch (Throwable t) { return false; }
    }

    private static Grid empty(String reason) {
        return new Grid(Collections.emptyList(), Collections.emptyList(), false, reason);
    }
}
