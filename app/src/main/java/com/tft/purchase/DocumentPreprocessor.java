package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Purchase-order document preparation for OCR/VLM.
 *
 * The original photo is never modified. We create two temporary files:
 *  - visionPath: orientation/perspective corrected colour image for MiniCPM.
 *  - ocrPath: corrected grayscale + local contrast + mild sharpening for OCR engines.
 *
 * All OpenCV operations are best-effort; if contour detection fails we keep the corrected image.
 */
public final class DocumentPreprocessor {
    private DocumentPreprocessor() {}

    public static final class Prepared {
        public final String visionPath;
        public final String ocrPath;
        Prepared(String visionPath, String ocrPath) {
            this.visionPath = visionPath;
            this.ocrPath = ocrPath;
        }
        public void cleanup() {
            try { if (visionPath != null) new File(visionPath).delete(); } catch (Throwable ignored) {}
            try { if (ocrPath != null && !ocrPath.equals(visionPath)) new File(ocrPath).delete(); } catch (Throwable ignored) {}
        }
    }

    public static Prepared prepare(Context context, String sourcePath) throws Exception {
        if (context == null || sourcePath == null || sourcePath.trim().isEmpty()) {
            throw new IllegalArgumentException("採購單圖片路徑無效");
        }
        Bitmap bitmap = decodeUpright(sourcePath);
        if (bitmap == null) throw new Exception("無法解碼採購單照片");

        Bitmap corrected = bitmap;
        boolean openCvReady = ensureOpenCv();
        if (openCvReady) {
            try {
                Bitmap warped = tryPerspectiveCorrection(corrected);
                if (warped != null && warped != corrected) {
                    corrected.recycle();
                    corrected = warped;
                }
            } catch (Throwable ignored) {
                // Keep orientation-corrected source if automatic boundary detection is uncertain.
            }
        }

        corrected = resizeForDocument(corrected, 3000, 2100);
        File vision = new File(context.getCacheDir(), "doc_vision_" + UUID.randomUUID() + ".jpg");
        saveJpeg(corrected, vision, 97);

        Bitmap ocrBitmap = corrected;
        if (openCvReady) {
            try {
                Bitmap enhanced = enhanceForOcr(corrected);
                if (enhanced != null) ocrBitmap = enhanced;
            } catch (Throwable ignored) {}
        }
        File ocr = new File(context.getCacheDir(), "doc_ocr_" + UUID.randomUUID() + ".jpg");
        saveJpeg(ocrBitmap, ocr, 98);

        if (ocrBitmap != corrected && !ocrBitmap.isRecycled()) ocrBitmap.recycle();
        if (!corrected.isRecycled()) corrected.recycle();
        return new Prepared(vision.getAbsolutePath(), ocr.getAbsolutePath());
    }

    private static Bitmap decodeUpright(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("圖片尺寸無效");

        int sample = 1;
        while (bounds.outWidth / sample > 3600 || bounds.outHeight / sample > 4600) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(path, options);
        if (bm == null) throw new Exception("無法讀取採購單圖片");

        int degrees = 0;
        try {
            int orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Throwable ignored) {}
        if (degrees != 0) bm = rotateAndRecycle(bm, degrees);

        // Company purchase orders are portrait documents. Only force rotation when clearly landscape.
        if (bm.getWidth() > bm.getHeight() * 1.08f) bm = rotateAndRecycle(bm, 90);
        return bm;
    }

    private static Bitmap rotateAndRecycle(Bitmap src, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src && !src.isRecycled()) src.recycle();
        return out;
    }

    private static Bitmap resizeForDocument(Bitmap src, int maxLongSide, int minLongSide) {
        int longSide = Math.max(src.getWidth(), src.getHeight());
        float scale = 1f;
        if (longSide > maxLongSide) scale = maxLongSide / (float) longSide;
        else if (longSide < minLongSide) scale = minLongSide / (float) longSide;
        if (Math.abs(scale - 1f) < 0.02f) return src;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(src.getWidth() * scale)),
                Math.max(1, Math.round(src.getHeight() * scale)), true);
        if (out != src && !src.isRecycled()) src.recycle();
        return out;
    }

    private static boolean ensureOpenCv() {
        try {
            System.loadLibrary("opencv_java4");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bitmap tryPerspectiveCorrection(Bitmap source) {
        Mat rgba = new Mat();
        Utils.bitmapToMat(source, rgba);
        Mat gray = new Mat();
        Mat blur = new Mat();
        Mat edges = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(gray, blur, new Size(5, 5), 0);
        Imgproc.Canny(blur, edges, 55, 155);
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3)));

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        double imageArea = rgba.cols() * (double) rgba.rows();
        Point[] best = null;
        double bestArea = 0;
        for (MatOfPoint contour : contours) {
            double area = Math.abs(Imgproc.contourArea(contour));
            if (area < imageArea * 0.30 || area <= bestArea) continue;
            MatOfPoint2f c2 = new MatOfPoint2f(contour.toArray());
            double peri = Imgproc.arcLength(c2, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(c2, approx, 0.02 * peri, true);
            Point[] pts = approx.toArray();
            if (pts.length == 4 && Imgproc.isContourConvex(new MatOfPoint(pts))) {
                best = pts;
                bestArea = area;
            }
            c2.release(); approx.release(); contour.release();
        }
        gray.release(); blur.release(); edges.release(); hierarchy.release();
        if (best == null) { rgba.release(); return null; }

        Point[] p = orderCorners(best);
        double widthTop = distance(p[0], p[1]);
        double widthBottom = distance(p[3], p[2]);
        double heightLeft = distance(p[0], p[3]);
        double heightRight = distance(p[1], p[2]);
        int width = (int)Math.round(Math.max(widthTop, widthBottom));
        int height = (int)Math.round(Math.max(heightLeft, heightRight));
        if (width < 600 || height < 800) { rgba.release(); return null; }

        // Avoid wild contour mistakes. Normal A4-ish purchase documents should remain portrait-ish.
        double ratio = width / (double) height;
        if (ratio < 0.45 || ratio > 1.25) { rgba.release(); return null; }

        MatOfPoint2f srcPts = new MatOfPoint2f(p);
        MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(0, 0), new Point(width - 1, 0),
                new Point(width - 1, height - 1), new Point(0, height - 1));
        Mat transform = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Mat warped = new Mat(height, width, CvType.CV_8UC4);
        Imgproc.warpPerspective(rgba, warped, transform, new Size(width, height),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(warped, out);
        rgba.release(); srcPts.release(); dstPts.release(); transform.release(); warped.release();
        return out;
    }

    private static Point[] orderCorners(Point[] input) {
        Point[] pts = Arrays.copyOf(input, input.length);
        Point tl = Arrays.stream(pts).min(Comparator.comparingDouble(a -> a.x + a.y)).orElse(pts[0]);
        Point br = Arrays.stream(pts).max(Comparator.comparingDouble(a -> a.x + a.y)).orElse(pts[2]);
        Point tr = Arrays.stream(pts).min(Comparator.comparingDouble(a -> a.y - a.x)).orElse(pts[1]);
        Point bl = Arrays.stream(pts).max(Comparator.comparingDouble(a -> a.y - a.x)).orElse(pts[3]);
        return new Point[]{tl, tr, br, bl};
    }

    private static double distance(Point a, Point b) {
        double dx = a.x - b.x, dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static Bitmap enhanceForOcr(Bitmap source) {
        Mat rgba = new Mat();
        Utils.bitmapToMat(source, rgba);
        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

        CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        Mat contrast = new Mat();
        clahe.apply(gray, contrast);

        Mat soft = new Mat();
        Imgproc.GaussianBlur(contrast, soft, new Size(0, 0), 1.0);
        Mat sharp = new Mat();
        Core.addWeighted(contrast, 1.35, soft, -0.35, 0, sharp);

        Mat outRgba = new Mat();
        Imgproc.cvtColor(sharp, outRgba, Imgproc.COLOR_GRAY2RGBA);
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(outRgba, out);

        rgba.release(); gray.release(); contrast.release(); soft.release(); sharp.release(); outRgba.release();
        return out;
    }

    private static void saveJpeg(Bitmap bitmap, File file, int quality) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new Exception("採購單影像處理失敗");
            }
        }
    }
}
