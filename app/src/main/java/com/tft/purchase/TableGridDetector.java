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
 * Wired-table detector tuned for the company's fixed purchase-order form.
 * The production form has 8 semantic columns / 9 vertical boundaries:
 * 項次 | 品名規格明細 | 數量 | 單位 | 單價 | 小計 | 交貨日期 | 備註.
 */
public final class TableGridDetector {
    private TableGridDetector() {}

    // Width proportions measured from the user's 20 real forms, normalized to the table span.
    private static final float[] FIXED_PURCHASE_WIDTHS = {
            0.034f, 0.430f, 0.075f, 0.044f, 0.065f, 0.103f, 0.123f, 0.126f
    };

    public static final class Grid {
        public final List<Float> xLines;
        public final List<Float> yLines;
        public final boolean reliable;
        public final boolean fixedPurchaseTemplate;
        public final float templateError;
        public final String reason;

        Grid(List<Float> x, List<Float> y, boolean reliable,
             boolean fixedPurchaseTemplate, float templateError, String reason) {
            this.xLines = Collections.unmodifiableList(new ArrayList<>(x));
            this.yLines = Collections.unmodifiableList(new ArrayList<>(y));
            this.reliable = reliable;
            this.fixedPurchaseTemplate = fixedPurchaseTemplate;
            this.templateError = templateError;
            this.reason = reason == null ? "" : reason;
        }

        public int columnCount() { return Math.max(0, xLines.size() - 1); }
        public int rowCount() { return Math.max(0, yLines.size() - 1); }
        public int column(float x) { return interval(xLines, x); }
        public int row(float y) { return interval(yLines, y); }

        public String summary() {
            return String.format(Locale.US,
                    "reliable=%s fixed_purchase_template=%s template_error=%.4f columns=%d rows=%d xSpan=%.3f ySpan=%.3f reason=%s",
                    reliable, fixedPurchaseTemplate, templateError,
                    columnCount(), rowCount(), span(xLines), span(yLines), reason);
        }
    }

    public static Grid detect(String correctedImagePath) {
        if (correctedImagePath == null || correctedImagePath.trim().isEmpty()) return empty("image_path_empty");
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

            List<Float> rawXs = mergeClose(cluster(xHit, cols, 0.015f, 0.995f), 0.006f);
            List<Float> ys = mergeClose(cluster(yHit, rows, 0.08f, 0.94f), 0.0045f);

            CanonicalColumns cc = canonicalizePurchaseColumns(rawXs);
            List<Float> xs = cc.matched ? cc.lines : rawXs;
            boolean reliable = xs.size() >= 5 && ys.size() >= 5
                    && span(xs) >= 0.55f && span(ys) >= 0.20f;
            // For this company's known form, 9 vertical boundaries are much stronger evidence
            // than a generic collection of lines from page borders / stamps.
            if (cc.matched) reliable = ys.size() >= 5 && span(ys) >= 0.20f;
            String reason = cc.matched ? "fixed_purchase_8col_grid"
                    : (reliable ? "wired_grid_detected"
                    : "insufficient_lines(x=" + xs.size() + ",y=" + ys.size() + ")");
            return new Grid(xs, ys, reliable, cc.matched, cc.error, reason);
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

    /** Pure-Java helper kept package-visible for regression tests. */
    static CanonicalColumns canonicalizePurchaseColumns(List<Float> input) {
        if (input == null || input.size() < 9) return new CanonicalColumns(false, Collections.emptyList(), 9f);
        List<Float> sorted = new ArrayList<>(input);
        Collections.sort(sorted);
        float best = Float.MAX_VALUE;
        List<Float> bestLines = null;
        // Real scans sometimes add one page-edge line. Test every consecutive 9-boundary window.
        for (int start = 0; start + 8 < sorted.size(); start++) {
            List<Float> w = new ArrayList<>(sorted.subList(start, start + 9));
            float s = w.get(8) - w.get(0);
            if (s < 0.72f || s > 0.99f) continue;
            float err = 0f;
            for (int i = 0; i < 8; i++) {
                float observed = (w.get(i + 1) - w.get(i)) / s;
                float d = observed - FIXED_PURCHASE_WIDTHS[i];
                // Large description and delivery-date cells carry slightly more weight.
                float weight = (i == 1 || i == 6) ? 1.35f : 1f;
                err += Math.abs(d) * weight;
            }
            if (err < best) { best = err; bestLines = w; }
        }
        // Empirically, the 20 supplied forms cluster far below this error after perspective correction.
        boolean matched = bestLines != null && best <= 0.135f;
        return new CanonicalColumns(matched,
                matched ? new ArrayList<>(bestLines) : Collections.emptyList(), best);
    }

    static final class CanonicalColumns {
        final boolean matched;
        final List<Float> lines;
        final float error;
        CanonicalColumns(boolean matched, List<Float> lines, float error) {
            this.matched = matched; this.lines = lines; this.error = error;
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
        return new Grid(Collections.emptyList(), Collections.emptyList(), false, false, 9f, reason);
    }
}
