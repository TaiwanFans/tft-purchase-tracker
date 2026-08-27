package com.quanyi.docscanner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.net.Uri;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ImageUtils {
    private ImageUtils() {}

    private static volatile Boolean opencvReady = null;

    private static boolean ensureOpenCv() {
        if (opencvReady != null) return opencvReady;
        synchronized (ImageUtils.class) {
            if (opencvReady != null) return opencvReady;
            boolean ok;
            try {
                System.loadLibrary("opencv_java4");
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            }
            opencvReady = ok;
            return ok;
        }
    }

    static Bitmap loadBitmap(ContentResolver resolver, Uri uri, int maxDim) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取圖片尺寸");

        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = resolver.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new Exception("無法讀取圖片");

        int rotation = 0;
        try (InputStream in = resolver.openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Throwable ignored) {}

        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    static PointF[] defaultCorners(Bitmap b) {
        float mx = b.getWidth() * 0.05f, my = b.getHeight() * 0.05f;
        return new PointF[]{
                new PointF(mx, my), new PointF(b.getWidth()-mx, my),
                new PointF(b.getWidth()-mx, b.getHeight()-my), new PointF(mx, b.getHeight()-my)
        };
    }

    static PointF[] detectDocumentCorners(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        if (!ensureOpenCv()) return fallbackDetectDocumentCorners(src);

        Bitmap detectBitmap = null;
        Mat rgba = new Mat();
        Mat rgb = new Mat();
        Mat gray = new Mat();
        Mat hsv = new Mat();
        Mat edgeReference = new Mat();
        ArrayList<Mat> maps = new ArrayList<>();
        try {
            final int maxDetectDim = 1280;
            double scale = Math.min(1.0, maxDetectDim / (double)Math.max(src.getWidth(), src.getHeight()));
            int dw = Math.max(32, (int)Math.round(src.getWidth() * scale));
            int dh = Math.max(32, (int)Math.round(src.getHeight() * scale));
            detectBitmap = scale < 1.0 ? Bitmap.createScaledBitmap(src, dw, dh, true) : src;

            Utils.bitmapToMat(detectBitmap, rgba);
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.GaussianBlur(gray, gray, new Size(5,5), 0);

            Imgproc.Canny(gray, edgeReference, 38, 118);
            Mat edgeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3,3));
            Imgproc.dilate(edgeReference, edgeReference, edgeKernel);
            edgeKernel.release();

            ArrayList<Candidate> candidates = new ArrayList<>();

            int[][] canny = {{24,72},{36,108},{52,156},{72,216}};
            for (int[] t : canny) {
                Mat edge = new Mat();
                Imgproc.Canny(gray, edge, t[0], t[1]);
                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));
                Imgproc.morphologyEx(edge, edge, Imgproc.MORPH_CLOSE, kernel);
                kernel.release();
                maps.add(edge);
                findQuadCandidates(edge, rgba.cols(), rgba.rows(), 0, candidates);
            }

            Mat adaptive = new Mat();
            Imgproc.adaptiveThreshold(gray, adaptive, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 35, 10);
            Mat adaptiveKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9,9));
            Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, adaptiveKernel);
            adaptiveKernel.release();
            maps.add(adaptive);
            findQuadCandidates(adaptive, rgba.cols(), rgba.rows(), 1, candidates);

            Mat otsu = new Mat();
            Imgproc.threshold(gray, otsu, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Mat otsuKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(17,17));
            Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, otsuKernel);
            otsuKernel.release();
            maps.add(otsu);
            findQuadCandidates(otsu, rgba.cols(), rgba.rows(), 2, candidates);

            Mat paperMask = new Mat();
            Core.inRange(hsv, new Scalar(0, 0, 108), new Scalar(180, 125, 255), paperMask);
            Mat paperClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(23,23));
            Mat paperOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));
            Imgproc.morphologyEx(paperMask, paperMask, Imgproc.MORPH_CLOSE, paperClose);
            Imgproc.morphologyEx(paperMask, paperMask, Imgproc.MORPH_OPEN, paperOpen);
            paperClose.release();
            paperOpen.release();
            maps.add(paperMask);
            findQuadCandidates(paperMask, rgba.cols(), rgba.rows(), 3, candidates);

            Candidate best = chooseBestCandidate(candidates, gray, hsv, edgeReference, rgba.cols(), rgba.rows());

            if (best == null || best.score < 54.0) {
                Candidate lineCandidate = detectByDominantLines(edgeReference, gray, hsv, rgba.cols(), rgba.rows());
                if (lineCandidate != null && (best == null || lineCandidate.score > best.score)) {
                    best = lineCandidate;
                }
            }

            if (best == null || best.score < 39.0) return fallbackDetectDocumentCorners(src);

            Point[] expanded = expandQuad(best.points, rgba.cols(), rgba.rows(), 0.0045);
            float inv = (float)(1.0 / scale);
            PointF[] result = new PointF[4];
            for (int i=0;i<4;i++) {
                result[i] = new PointF((float)expanded[i].x * inv, (float)expanded[i].y * inv);
            }
            if (!validQuad(result, src.getWidth(), src.getHeight())) return fallbackDetectDocumentCorners(src);
            return result;
        } catch (Throwable ignored) {
            return fallbackDetectDocumentCorners(src);
        } finally {
            for (Mat m : maps) try { m.release(); } catch (Throwable ignored) {}
            try { edgeReference.release(); } catch (Throwable ignored) {}
            try { hsv.release(); } catch (Throwable ignored) {}
            try { gray.release(); } catch (Throwable ignored) {}
            try { rgb.release(); } catch (Throwable ignored) {}
            try { rgba.release(); } catch (Throwable ignored) {}
            if (detectBitmap != null && detectBitmap != src && !detectBitmap.isRecycled()) detectBitmap.recycle();
        }
    }

    private static void findQuadCandidates(Mat binary, int w, int h, int sourceKind, List<Candidate> out) {
        ArrayList<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Mat copy = binary.clone();
        ArrayList<Candidate> local = new ArrayList<>();
        try {
            int retrieval = sourceKind >= 2 ? Imgproc.RETR_EXTERNAL : Imgproc.RETR_LIST;
            Imgproc.findContours(copy, contours, hierarchy, retrieval, Imgproc.CHAIN_APPROX_SIMPLE);
            double imageArea = w * (double)h;

            for (MatOfPoint contour : contours) {
                double contourArea = Math.abs(Imgproc.contourArea(contour));
                if (contourArea < imageArea * 0.07 || contourArea > imageArea * 0.992) continue;

                MatOfPoint2f c2 = new MatOfPoint2f(contour.toArray());
                try {
                    double peri = Imgproc.arcLength(c2, true);
                    if (peri < Math.min(w,h) * 0.70) continue;
                    for (double eps : new double[]{0.010,0.014,0.018,0.023,0.030,0.040}) {
                        MatOfPoint2f approx = new MatOfPoint2f();
                        try {
                            Imgproc.approxPolyDP(c2, approx, peri * eps, true);
                            Point[] pts = approx.toArray();
                            if (pts.length != 4) continue;
                            MatOfPoint poly = new MatOfPoint(pts);
                            boolean convex;
                            try { convex = Imgproc.isContourConvex(poly); }
                            finally { poly.release(); }
                            if (!convex) continue;

                            Point[] ordered = orderPoints(pts);
                            double geometry = geometryScore(ordered, w, h);
                            if (geometry < 0) continue;
                            Candidate c = new Candidate(ordered, geometry, sourceKind);
                            addUniqueCandidate(local, c, w, h);
                        } finally {
                            approx.release();
                        }
                    }
                } finally {
                    c2.release();
                }
            }

            Collections.sort(local, new Comparator<Candidate>() {
                @Override public int compare(Candidate a, Candidate b) {
                    return Double.compare(b.score, a.score);
                }
            });
            int limit = Math.min(14, local.size());
            for (int i=0;i<limit;i++) addUniqueCandidate(out, local.get(i), w, h);
        } finally {
            copy.release();
            hierarchy.release();
            for (MatOfPoint c : contours) try { c.release(); } catch (Throwable ignored) {}
        }
    }

    private static Candidate chooseBestCandidate(List<Candidate> candidates, Mat gray, Mat hsv, Mat edgeRef, int w, int h) {
        Candidate best = null;
        for (Candidate c : candidates) {
            double score = fullDocumentScore(c.points, c.sourceKind, gray, hsv, edgeRef, w, h);
            if (score < 0) continue;
            c.score = score;
            if (best == null || c.score > best.score) best = c;
        }
        return best;
    }

    private static double geometryScore(Point[] p, int w, int h) {
        if (p == null || p.length != 4) return -1;
        double area = polygonArea(p);
        double imageArea = w * (double)h;
        double areaRatio = area / imageArea;
        if (areaRatio < 0.07 || areaRatio > 0.992) return -1;

        double minSide = Math.min(Math.min(distance(p[0],p[1]), distance(p[1],p[2])),
                Math.min(distance(p[2],p[3]), distance(p[3],p[0])));
        if (minSide < Math.min(w,h) * 0.10) return -1;

        double anglePenalty = quadAnglePenalty(p);
        if (anglePenalty > 0.78) return -1;

        MatOfPoint temp = new MatOfPoint(p);
        Rect r;
        try { r = Imgproc.boundingRect(temp); }
        finally { temp.release(); }
        double rectangularity = area / Math.max(1.0, r.area());

        return areaRatio * 76.0 + (1.0-anglePenalty) * 14.0 + clamp01((rectangularity-0.45)/0.50) * 10.0;
    }

    private static double fullDocumentScore(Point[] p, int sourceKind, Mat gray, Mat hsv, Mat edgeRef, int w, int h) {
        double area = polygonArea(p);
        double imageArea = w * (double)h;
        double areaRatio = area / imageArea;
        if (areaRatio < 0.07 || areaRatio > 0.992) return -1;

        double anglePenalty = quadAnglePenalty(p);
        if (anglePenalty > 0.78) return -1;

        double top = distance(p[0],p[1]);
        double right = distance(p[1],p[2]);
        double bottom = distance(p[2],p[3]);
        double left = distance(p[3],p[0]);
        double avgW = (top + bottom) * 0.5;
        double avgH = (left + right) * 0.5;
        double aspect = Math.min(avgW, avgH) / Math.max(1.0, Math.max(avgW, avgH));
        if (aspect < 0.28) return -1;

        MatOfPoint temp = new MatOfPoint(p);
        Rect r;
        try { r = Imgproc.boundingRect(temp); }
        finally { temp.release(); }
        double rectangularity = area / Math.max(1.0, r.area());

        double cx=0, cy=0;
        for (Point point : p) { cx += point.x; cy += point.y; }
        cx /= 4.0; cy /= 4.0;
        double centerDist = Math.hypot((cx-w*0.5)/(w*0.5), (cy-h*0.5)/(h*0.5));
        double centerScore = 1.0 - clamp01(centerDist / 1.15);

        Mat mask = Mat.zeros(h, w, CvType.CV_8U);
        Mat borderMask = Mat.zeros(h, w, CvType.CV_8U);
        Mat ring = new Mat();
        Mat dilated = new Mat();
        Mat intersection = new Mat();
        MatOfPoint polygon = new MatOfPoint(p);
        try {
            Imgproc.fillConvexPoly(mask, polygon, Scalar.all(255));
            for (int i=0;i<4;i++) {
                Imgproc.line(borderMask, p[i], p[(i+1)%4], Scalar.all(255), 7);
            }

            Scalar insideGray = Core.mean(gray, mask);
            Scalar insideHsv = Core.mean(hsv, mask);

            Mat ringKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(25,25));
            try { Imgproc.dilate(mask, dilated, ringKernel); }
            finally { ringKernel.release(); }
            Core.subtract(dilated, mask, ring);
            Scalar outsideGray = Core.countNonZero(ring) > 20 ? Core.mean(gray, ring) : insideGray;

            Core.bitwise_and(edgeRef, borderMask, intersection);
            double borderPixels = Math.max(1.0, Core.countNonZero(borderMask));
            double edgeSupport = clamp01(Core.countNonZero(intersection) / borderPixels);

            double paperBrightness = clamp01((insideGray.val[0] - 95.0) / 135.0);
            double paperLowSaturation = clamp01((145.0 - insideHsv.val[1]) / 145.0);
            double brightnessDifference = insideGray.val[0] - outsideGray.val[0];
            double contrastScore = clamp01((brightnessDifference + 18.0) / 75.0);

            double areaScore = clamp01((areaRatio - 0.06) / 0.58);
            double angleScore = 1.0 - anglePenalty;
            double rectScore = clamp01((rectangularity - 0.48) / 0.47);
            double aspectScore = 1.0 - clamp01(Math.abs(aspect - 0.67) / 0.39);

            double sourceBonus = 0;
            if (sourceKind == 3) sourceBonus = 5.0;
            else if (sourceKind == 2) sourceBonus = 2.5;
            else if (sourceKind == 1) sourceBonus = 1.2;
            else if (sourceKind == 4) sourceBonus = 2.0;

            return areaScore * 38.0
                    + angleScore * 15.0
                    + rectScore * 7.0
                    + aspectScore * 6.0
                    + centerScore * 4.0
                    + edgeSupport * 17.0
                    + paperBrightness * 4.0
                    + paperLowSaturation * 4.0
                    + contrastScore * 5.0
                    + sourceBonus;
        } finally {
            polygon.release();
            mask.release();
            borderMask.release();
            ring.release();
            dilated.release();
            intersection.release();
        }
    }

    private static Candidate detectByDominantLines(Mat edgeRef, Mat gray, Mat hsv, int w, int h) {
        Mat lines = new Mat();
        try {
            Imgproc.HoughLinesP(edgeRef, lines, 1, Math.PI/180.0, 60,
                    Math.min(w,h) * 0.28, Math.min(w,h) * 0.055);
            if (lines.rows() < 4) return null;

            ArrayList<LineSegment> horizontals = new ArrayList<>();
            ArrayList<LineSegment> verticals = new ArrayList<>();
            for (int i=0;i<lines.rows();i++) {
                double[] v = lines.get(i,0);
                if (v == null || v.length < 4) continue;
                Point a = new Point(v[0],v[1]);
                Point b = new Point(v[2],v[3]);
                double dx = b.x-a.x, dy = b.y-a.y;
                double length = Math.hypot(dx,dy);
                double angle = Math.toDegrees(Math.atan2(dy,dx));
                if (angle < 0) angle += 180.0;

                if ((angle <= 36 || angle >= 144) && length >= w * 0.30) {
                    horizontals.add(new LineSegment(a,b,length));
                } else if (angle >= 54 && angle <= 126 && length >= h * 0.30) {
                    verticals.add(new LineSegment(a,b,length));
                }
            }
            if (horizontals.size() < 2 || verticals.size() < 2) return null;

            LineSegment top = null, bottom = null, left = null, right = null;
            for (LineSegment l : horizontals) {
                double y = l.midY();
                if (top == null || y < top.midY()) top = l;
                if (bottom == null || y > bottom.midY()) bottom = l;
            }
            for (LineSegment l : verticals) {
                double x = l.midX();
                if (left == null || x < left.midX()) left = l;
                if (right == null || x > right.midX()) right = l;
            }
            if (top == null || bottom == null || left == null || right == null) return null;
            if (Math.abs(bottom.midY()-top.midY()) < h*0.25 || Math.abs(right.midX()-left.midX()) < w*0.25) return null;

            Point tl = lineIntersection(top,left);
            Point tr = lineIntersection(top,right);
            Point br = lineIntersection(bottom,right);
            Point bl = lineIntersection(bottom,left);
            if (tl==null || tr==null || br==null || bl==null) return null;
            Point[] p = new Point[]{tl,tr,br,bl};
            for (Point q : p) {
                if (q.x < -w*0.12 || q.x > w*1.12 || q.y < -h*0.12 || q.y > h*1.12) return null;
                q.x = Math.max(0, Math.min(w-1, q.x));
                q.y = Math.max(0, Math.min(h-1, q.y));
            }
            double score = fullDocumentScore(p, 4, gray, hsv, edgeRef, w, h);
            return score > 0 ? new Candidate(p, score, 4) : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            lines.release();
        }
    }

    private static Point lineIntersection(LineSegment a, LineSegment b) {
        double x1=a.a.x, y1=a.a.y, x2=a.b.x, y2=a.b.y;
        double x3=b.a.x, y3=b.a.y, x4=b.b.x, y4=b.b.y;
        double den=(x1-x2)*(y3-y4)-(y1-y2)*(x3-x4);
        if (Math.abs(den) < 1e-6) return null;
        double px=((x1*y2-y1*x2)*(x3-x4)-(x1-x2)*(x3*y4-y3*x4))/den;
        double py=((x1*y2-y1*x2)*(y3-y4)-(y1-y2)*(x3*y4-y3*x4))/den;
        return new Point(px,py);
    }

    private static void addUniqueCandidate(List<Candidate> list, Candidate candidate, int w, int h) {
        double norm = Math.max(w,h);
        for (int i=0;i<list.size();i++) {
            Candidate existing = list.get(i);
            double d=0;
            for (int k=0;k<4;k++) d += distance(existing.points[k], candidate.points[k]);
            d = d / 4.0 / norm;
            if (d < 0.025) {
                if (candidate.score > existing.score) list.set(i, candidate);
                return;
            }
        }
        list.add(candidate);
    }

    private static Point[] expandQuad(Point[] p, int w, int h, double ratio) {
        double cx=0,cy=0;
        for (Point q : p) { cx+=q.x; cy+=q.y; }
        cx/=4.0; cy/=4.0;
        Point[] out = new Point[4];
        for (int i=0;i<4;i++) {
            double nx = cx + (p[i].x-cx)*(1.0+ratio);
            double ny = cy + (p[i].y-cy)*(1.0+ratio);
            out[i]=new Point(Math.max(0,Math.min(w-1,nx)), Math.max(0,Math.min(h-1,ny)));
        }
        return out;
    }

    private static double quadAnglePenalty(Point[] p) {
        double penalty=0;
        for (int i=0;i<4;i++) {
            Point prev=p[(i+3)%4], cur=p[i], next=p[(i+1)%4];
            double ax=prev.x-cur.x, ay=prev.y-cur.y;
            double bx=next.x-cur.x, by=next.y-cur.y;
            double denom=Math.max(1e-6,Math.hypot(ax,ay)*Math.hypot(bx,by));
            penalty += Math.abs((ax*bx+ay*by)/denom);
        }
        return penalty/4.0;
    }

    private static double polygonArea(Point[] p) {
        double a=0;
        for (int i=0;i<4;i++) {
            Point q=p[i], r=p[(i+1)%4];
            a += q.x*r.y-r.x*q.y;
        }
        return Math.abs(a)*0.5;
    }

    private static Point[] orderPoints(Point[] pts) {
        Point tl=null,tr=null,br=null,bl=null;
        double minSum=Double.MAX_VALUE,maxSum=-Double.MAX_VALUE,minDiff=Double.MAX_VALUE,maxDiff=-Double.MAX_VALUE;
        for(Point p:pts){
            double sum=p.x+p.y,diff=p.x-p.y;
            if(sum<minSum){minSum=sum;tl=p;}
            if(sum>maxSum){maxSum=sum;br=p;}
            if(diff>maxDiff){maxDiff=diff;tr=p;}
            if(diff<minDiff){minDiff=diff;bl=p;}
        }
        return new Point[]{tl,tr,br,bl};
    }

    private static boolean validQuad(PointF[] p, int w, int h) {
        if (p == null || p.length != 4) return false;
        double area=0;
        for(int i=0;i<4;i++){
            PointF a=p[i],b=p[(i+1)%4];
            if (a == null || b == null) return false;
            area+=a.x*b.y-b.x*a.y;
        }
        area=Math.abs(area)/2.0;
        if(area < w*h*0.08) return false;
        for(int i=0;i<4;i++) if(dist(p[i],p[(i+1)%4])<Math.min(w,h)*.08) return false;
        return true;
    }

    private static PointF[] fallbackDetectDocumentCorners(Bitmap src) {
        try {
            final int max = 900;
            float scale = Math.min(1f, max / (float)Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(32, Math.round(src.getWidth()*scale));
            int h = Math.max(32, Math.round(src.getHeight()*scale));
            Bitmap b = scale < 1f ? Bitmap.createScaledBitmap(src, w, h, true) : src;
            int[] px = new int[w*h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            int[] gray = new int[w*h];
            for (int i=0;i<px.length;i++) {
                int c=px[i];
                gray[i]=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;
            }
            int[] blur = gray.clone();
            for (int y=1;y<h-1;y++) for (int x=1;x<w-1;x++) {
                int i=y*w+x;
                blur[i]=(gray[i]+gray[i-1]+gray[i+1]+gray[i-w]+gray[i+w])/5;
            }
            ArrayList<Integer> mags = new ArrayList<>((w*h)/16);
            int[] mag = new int[w*h];
            for (int y=2;y<h-2;y++) for (int x=2;x<w-2;x++) {
                int i=y*w+x;
                int gx = -blur[i-w-1]-2*blur[i-1]-blur[i+w-1] + blur[i-w+1]+2*blur[i+1]+blur[i+w+1];
                int gy = -blur[i-w-1]-2*blur[i-w]-blur[i-w+1] + blur[i+w-1]+2*blur[i+w]+blur[i+w+1];
                int m = Math.abs(gx)+Math.abs(gy);
                mag[i]=m;
                if ((x%4==0)&&(y%4==0)) mags.add(m);
            }
            if (mags.isEmpty()) {
                if (b!=src) b.recycle();
                return defaultCorners(src);
            }
            Collections.sort(mags);
            int threshold = Math.max(80, mags.get((int)(mags.size()*0.90f)));
            float minSum=Float.MAX_VALUE, maxSum=-Float.MAX_VALUE, minDiff=Float.MAX_VALUE, maxDiff=-Float.MAX_VALUE;
            PointF tl=null,tr=null,br=null,bl=null;
            int marginX=Math.max(2,(int)(w*.02f)), marginY=Math.max(2,(int)(h*.02f));
            for (int y=marginY;y<h-marginY;y+=2) for (int x=marginX;x<w-marginX;x+=2) {
                if (mag[y*w+x] < threshold) continue;
                float sum=x+y, diff=x-y;
                if (sum<minSum){minSum=sum;tl=new PointF(x,y);}
                if(sum>maxSum){maxSum=sum;br=new PointF(x,y);}
                if (diff>maxDiff){maxDiff=diff;tr=new PointF(x,y);}
                if(diff<minDiff){minDiff=diff;bl=new PointF(x,y);}
            }
            if (b!=src) b.recycle();
            if (tl==null||tr==null||br==null||bl==null) return defaultCorners(src);
            float inv=1f/scale;
            PointF[] p={new PointF(tl.x*inv,tl.y*inv),new PointF(tr.x*inv,tr.y*inv),new PointF(br.x*inv,br.y*inv),new PointF(bl.x*inv,bl.y*inv)};
            return validQuad(p,src.getWidth(),src.getHeight()) ? p : defaultCorners(src);
        } catch (Throwable ignored) {
            return defaultCorners(src);
        }
    }

    static Bitmap perspectiveCrop(Bitmap src, PointF[] p) {
        float top=dist(p[0],p[1]), bottom=dist(p[3],p[2]);
        float left=dist(p[0],p[3]), right=dist(p[1],p[2]);
        int ow=Math.max(64, Math.round((top+bottom)/2f));
        int oh=Math.max(64, Math.round((left+right)/2f));
        float limit=4096f/Math.max(ow,oh);
        if(limit<1f){ow=Math.round(ow*limit);oh=Math.round(oh*limit);}
        Bitmap out=Bitmap.createBitmap(ow,oh,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);
        c.drawColor(Color.WHITE);
        float[] s={p[0].x,p[0].y,p[1].x,p[1].y,p[2].x,p[2].y,p[3].x,p[3].y};
        float[] d={0,0,ow,0,ow,oh,0,oh};
        Matrix m=new Matrix();
        if(!m.setPolyToPoly(s,0,d,0,4)) return src.copy(Bitmap.Config.ARGB_8888,false);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src,m,paint);
        return out;
    }

    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper) {
        if (input == null || input.isRecycled()) return input;
        if (ensureOpenCv()) {
            try {
                return enhanceOpenCv(input, brighter, sharper);
            } catch (Throwable ignored) {}
        }
        return fallbackEnhance(input, brighter, sharper);
    }

    private static Bitmap enhanceOpenCv(Bitmap input, boolean brighter, boolean sharper) {
        Mat src = new Mat();
        Mat rgb = new Mat();
        try {
            Utils.bitmapToMat(input, src);
            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB);

            if (brighter) {
                Mat background = new Mat();
                Mat normalized = new Mat();
                Mat lab = new Mat();
                List<Mat> channels = new ArrayList<>();
                try {
                    double sigma = Math.max(10.0, Math.max(input.getWidth(), input.getHeight()) / 28.0);
                    Imgproc.GaussianBlur(rgb, background, new Size(0,0), sigma);
                    Core.add(background, Scalar.all(1.0), background);
                    Core.divide(rgb, background, normalized, 245.0);
                    normalized.convertTo(rgb, -1, 1.28, -50.0);

                    Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
                    Core.split(lab, channels);
                    CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8,8));
                    Mat enhancedL = new Mat();
                    clahe.apply(channels.get(0), enhancedL);
                    channels.get(0).release();
                    channels.set(0, enhancedL);
                    Core.merge(channels, lab);
                    Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);
                } finally {
                    for (Mat c : channels) try { c.release(); } catch (Throwable ignored) {}
                    try { lab.release(); } catch (Throwable ignored) {}
                    try { normalized.release(); } catch (Throwable ignored) {}
                    try { background.release(); } catch (Throwable ignored) {}
                }
            }

            if (sharper) {
                Mat blur = new Mat();
                try {
                    Imgproc.GaussianBlur(rgb, blur, new Size(0,0), 0.9);
                    Core.addWeighted(rgb, 1.55, blur, -0.55, 0, rgb);
                } finally {
                    blur.release();
                }
            }

            Mat outRgba = new Mat();
            try {
                Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA);
                Bitmap out = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(outRgba, out);
                return out;
            } finally {
                outRgba.release();
            }
        } finally {
            rgb.release();
            src.release();
        }
    }

    private static Bitmap fallbackEnhance(Bitmap input, boolean brighter, boolean sharper) {
        Bitmap work = input.copy(Bitmap.Config.ARGB_8888, true);
        int w = work.getWidth(), h = work.getHeight();
        int[] a = new int[w*h];
        work.getPixels(a,0,w,0,0,w,h);

        if (brighter) {
            for (int i=0;i<a.length;i++) {
                int c=a[i];
                int r=clamp((int)(Color.red(c)*1.28f-45));
                int g=clamp((int)(Color.green(c)*1.28f-45));
                int b=clamp((int)(Color.blue(c)*1.28f-45));
                a[i]=Color.argb(Color.alpha(c),r,g,b);
            }
        }

        if (sharper && w>2 && h>2) {
            int[] base=a.clone();
            for(int y=1;y<h-1;y++) for(int x=1;x<w-1;x++){
                int i=y*w+x,c=base[i],l=base[i-1],r=base[i+1],u=base[i-w],d=base[i+w];
                int rr=clamp((Color.red(c)*6-Color.red(l)-Color.red(r)-Color.red(u)-Color.red(d))/2);
                int gg=clamp((Color.green(c)*6-Color.green(l)-Color.green(r)-Color.green(u)-Color.green(d))/2);
                int bb=clamp((Color.blue(c)*6-Color.blue(l)-Color.blue(r)-Color.blue(u)-Color.blue(d))/2);
                a[i]=Color.argb(Color.alpha(c),rr,gg,bb);
            }
        }

        work.setPixels(a,0,w,0,0,w,h);
        return work;
    }

    private static double clamp01(double v){return Math.max(0.0,Math.min(1.0,v));}
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
    private static double distance(Point a, Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static float dist(PointF a,PointF b){float dx=a.x-b.x,dy=a.y-b.y;return (float)Math.sqrt(dx*dx+dy*dy);}

    private static final class Candidate {
        final Point[] points;
        double score;
        final int sourceKind;
        Candidate(Point[] p,double s,int source){points=p;score=s;sourceKind=source;}
    }

    private static final class LineSegment {
        final Point a,b;
        final double length;
        LineSegment(Point a, Point b, double length){this.a=a;this.b=b;this.length=length;}
        double midX(){return (a.x+b.x)*0.5;}
        double midY(){return (a.y+b.y)*0.5;}
    }
}
