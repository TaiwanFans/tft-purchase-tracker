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
import java.util.List;

final class ImageUtils {
    private ImageUtils() {}

    static Bitmap loadBitmap(ContentResolver resolver, Uri uri, int maxDim) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = resolver.openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, opts); }
        if (bmp == null) throw new Exception("無法讀取圖片");

        int rotation = 0;
        try (InputStream in = resolver.openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {}
        if (rotation != 0) {
            Matrix m = new Matrix(); m.postRotate(rotation);
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
        Mat original = new Mat();
        Mat small = new Mat();
        Mat gray = new Mat();
        Utils.bitmapToMat(src, original);
        double scale = Math.min(1.0, 1200.0 / Math.max(src.getWidth(), src.getHeight()));
        if (scale < 1.0) Imgproc.resize(original, small, new Size(src.getWidth()*scale, src.getHeight()*scale), 0, 0, Imgproc.INTER_AREA);
        else original.copyTo(small);
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(gray, gray, new Size(5,5), 0);

        Candidate best = null;
        ArrayList<Mat> maps = new ArrayList<>();
        int[][] canny = {{35,105},{55,165},{80,220}};
        for (int[] t : canny) {
            Mat edge = new Mat();
            Imgproc.Canny(gray, edge, t[0], t[1]);
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));
            Imgproc.morphologyEx(edge, edge, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();
            maps.add(edge);
        }
        Mat adaptive = new Mat();
        Imgproc.adaptiveThreshold(gray, adaptive, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 9);
        Mat adaptiveKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7,7));
        Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, adaptiveKernel);
        adaptiveKernel.release();
        maps.add(adaptive);

        for (Mat edge : maps) {
            Candidate c = findBestQuad(edge, small.cols(), small.rows());
            if (c != null && (best == null || c.score > best.score)) best = c;
        }

        for (Mat m : maps) m.release();
        gray.release(); small.release(); original.release();
        if (best == null) return defaultCorners(src);

        float inv = (float)(1.0 / scale);
        PointF[] result = new PointF[4];
        for (int i=0;i<4;i++) result[i] = new PointF((float)best.points[i].x*inv, (float)best.points[i].y*inv);
        if (!validQuad(result, src.getWidth(), src.getHeight())) return defaultCorners(src);
        return result;
    }

    private static Candidate findBestQuad(Mat binary, int w, int h) {
        ArrayList<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();
        Candidate best = null;
        double imageArea = w * (double)h;

        for (MatOfPoint contour : contours) {
            double area = Math.abs(Imgproc.contourArea(contour));
            if (area < imageArea * 0.12 || area > imageArea * 0.995) { contour.release(); continue; }
            MatOfPoint2f c2 = new MatOfPoint2f(contour.toArray());
            double peri = Imgproc.arcLength(c2, true);
            for (double eps : new double[]{0.015,0.02,0.028,0.035}) {
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(c2, approx, peri*eps, true);
                Point[] pts = approx.toArray();
                if (pts.length == 4) {
                    MatOfPoint poly = new MatOfPoint(pts);
                    boolean convex = Imgproc.isContourConvex(poly);
                    poly.release();
                    if (convex) {
                        Point[] ordered = orderPoints(pts);
                        double score = quadScore(ordered, area, imageArea, w, h);
                        if (score > 0 && (best == null || score > best.score)) best = new Candidate(ordered, score);
                    }
                }
                approx.release();
            }
            c2.release(); contour.release();
        }
        return best;
    }

    private static double quadScore(Point[] p, double area, double imageArea, int w, int h) {
        double minSide = Math.min(Math.min(distance(p[0],p[1]), distance(p[1],p[2])), Math.min(distance(p[2],p[3]), distance(p[3],p[0])));
        if (minSide < Math.min(w,h)*0.12) return -1;
        double anglePenalty = 0;
        for (int i=0;i<4;i++) {
            Point prev = p[(i+3)%4], cur = p[i], next = p[(i+1)%4];
            double ax=prev.x-cur.x, ay=prev.y-cur.y, bx=next.x-cur.x, by=next.y-cur.y;
            double cos = Math.abs((ax*bx+ay*by)/(Math.max(1e-6,Math.hypot(ax,ay)*Math.hypot(bx,by))));
            anglePenalty += cos;
        }
        anglePenalty /= 4.0;
        if (anglePenalty > 0.72) return -1;
        Rect r = Imgproc.boundingRect(new MatOfPoint(p));
        double fill = area / Math.max(1.0, r.area());
        double areaRatio = area / imageArea;
        double score = areaRatio * 100.0 + fill * 8.0 + (1.0-anglePenalty)*10.0;
        if (areaRatio > 0.90) score += 4.0;
        return score;
    }

    private static Point[] orderPoints(Point[] pts) {
        Point tl=null,tr=null,br=null,bl=null;
        double minSum=Double.MAX_VALUE,maxSum=-Double.MAX_VALUE,minDiff=Double.MAX_VALUE,maxDiff=-Double.MAX_VALUE;
        for(Point p:pts){
            double sum=p.x+p.y,diff=p.x-p.y;
            if(sum<minSum){minSum=sum;tl=p;} if(sum>maxSum){maxSum=sum;br=p;}
            if(diff>maxDiff){maxDiff=diff;tr=p;} if(diff<minDiff){minDiff=diff;bl=p;}
        }
        return new Point[]{tl,tr,br,bl};
    }

    private static boolean validQuad(PointF[] p, int w, int h) {
        double area=0;
        for(int i=0;i<4;i++){PointF a=p[i],b=p[(i+1)%4];area+=a.x*b.y-b.x*a.y;}
        area=Math.abs(area)/2.0;
        if(area < w*h*0.12) return false;
        for(int i=0;i<4;i++) if(dist(p[i],p[(i+1)%4])<Math.min(w,h)*.10) return false;
        return true;
    }

    static Bitmap perspectiveCrop(Bitmap src, PointF[] p) {
        float top=dist(p[0],p[1]), bottom=dist(p[3],p[2]);
        float left=dist(p[0],p[3]), right=dist(p[1],p[2]);
        int ow=Math.max(64, Math.round((top+bottom)/2f));
        int oh=Math.max(64, Math.round((left+right)/2f));
        float limit=4096f/Math.max(ow,oh);
        if(limit<1f){ow=Math.round(ow*limit);oh=Math.round(oh*limit);}
        Bitmap out=Bitmap.createBitmap(ow,oh,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out); c.drawColor(Color.WHITE);
        float[] s={p[0].x,p[0].y,p[1].x,p[1].y,p[2].x,p[2].y,p[3].x,p[3].y};
        float[] d={0,0,ow,0,ow,oh,0,oh};
        Matrix m=new Matrix();
        if(!m.setPolyToPoly(s,0,d,0,4)) return src.copy(Bitmap.Config.ARGB_8888,false);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src,m,paint);
        return out;
    }

    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper) {
        Mat src = new Mat();
        Utils.bitmapToMat(input, src);
        Mat work = new Mat();
        src.copyTo(work);

        if (brighter) {
            Mat rgb = new Mat();
            Mat lab = new Mat();
            Imgproc.cvtColor(work, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
            List<Mat> channels = new ArrayList<>();
            Core.split(lab, channels);
            CLAHE clahe = Imgproc.createCLAHE(2.2, new Size(8,8));
            Mat enhancedL = new Mat();
            clahe.apply(channels.get(0), enhancedL);
            channels.get(0).release();
            channels.set(0, enhancedL);
            Core.merge(channels, lab);
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);
            Imgproc.cvtColor(rgb, work, Imgproc.COLOR_RGB2RGBA);
            work.convertTo(work, -1, 1.08, 18);
            for (Mat c : channels) c.release();
            lab.release(); rgb.release();
        }

        if (sharper) {
            Mat blur = new Mat();
            Imgproc.GaussianBlur(work, blur, new Size(0,0), 1.25);
            Core.addWeighted(work, 1.75, blur, -0.75, 0, work);
            blur.release();
        }

        Bitmap out = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(work, out);
        work.release(); src.release();
        return out;
    }

    private static double distance(Point a, Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static float dist(PointF a,PointF b){float dx=a.x-b.x,dy=a.y-b.y;return (float)Math.sqrt(dx*dx+dy*dy);}
    private static final class Candidate { final Point[] points; final double score; Candidate(Point[] p,double s){points=p;score=s;} }
}
