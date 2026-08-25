package com.quanyi.docscanner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;

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
        float mx = b.getWidth() * 0.06f, my = b.getHeight() * 0.06f;
        return new PointF[]{
                new PointF(mx, my), new PointF(b.getWidth()-mx, my),
                new PointF(b.getWidth()-mx, b.getHeight()-my), new PointF(mx, b.getHeight()-my)
        };
    }

    static PointF[] detectDocumentCorners(Bitmap src) {
        final int max = 800;
        float scale = Math.min(1f, max / (float)Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(32, Math.round(src.getWidth()*scale));
        int h = Math.max(32, Math.round(src.getHeight()*scale));
        Bitmap b = scale < 1f ? Bitmap.createScaledBitmap(src, w, h, true) : src;
        int[] px = new int[w*h]; b.getPixels(px, 0, w, 0, 0, w, h);
        int[] gray = new int[w*h];
        for (int i=0;i<px.length;i++) {
            int c=px[i]; gray[i]=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;
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
            int m = Math.abs(gx)+Math.abs(gy); mag[i]=m;
            if ((x%4==0)&&(y%4==0)) mags.add(m);
        }
        if (mags.isEmpty()) { if (b!=src) b.recycle(); return defaultCorners(src); }
        Collections.sort(mags);
        int threshold = Math.max(80, mags.get((int)(mags.size()*0.88f)));

        float minSum=Float.MAX_VALUE, maxSum=-Float.MAX_VALUE, minDiff=Float.MAX_VALUE, maxDiff=-Float.MAX_VALUE;
        PointF tl=null,tr=null,br=null,bl=null;
        int marginX=Math.max(2,(int)(w*.015f)), marginY=Math.max(2,(int)(h*.015f));
        for (int y=marginY;y<h-marginY;y+=2) for (int x=marginX;x<w-marginX;x+=2) {
            if (mag[y*w+x] < threshold) continue;
            float sum=x+y, diff=x-y;
            if (sum<minSum){minSum=sum;tl=new PointF(x,y);} if(sum>maxSum){maxSum=sum;br=new PointF(x,y);}
            if (diff>maxDiff){maxDiff=diff;tr=new PointF(x,y);} if(diff<minDiff){minDiff=diff;bl=new PointF(x,y);}
        }
        if (b!=src) b.recycle();
        if (tl==null||tr==null||br==null||bl==null) return defaultCorners(src);
        float inv=1f/scale;
        PointF[] p={new PointF(tl.x*inv,tl.y*inv),new PointF(tr.x*inv,tr.y*inv),new PointF(br.x*inv,br.y*inv),new PointF(bl.x*inv,bl.y*inv)};
        if (!validQuad(p, src.getWidth(), src.getHeight())) return defaultCorners(src);
        return p;
    }

    private static boolean validQuad(PointF[] p, int w, int h) {
        double area=0;
        for(int i=0;i<4;i++){PointF a=p[i],b=p[(i+1)%4];area+=a.x*b.y-b.x*a.y;}
        area=Math.abs(area)/2.0;
        if(area < w*h*0.18) return false;
        for(int i=0;i<4;i++) if(dist(p[i],p[(i+1)%4])<Math.min(w,h)*.12) return false;
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
        Bitmap work=input.copy(Bitmap.Config.ARGB_8888,true);
        if(brighter){
            Bitmap temp=Bitmap.createBitmap(work.getWidth(),work.getHeight(),Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(temp); Paint p=new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix cm=new ColorMatrix(new float[]{1.06f,0,0,0,12, 0,1.06f,0,0,12, 0,0,1.06f,0,12, 0,0,0,1,0});
            p.setColorFilter(new ColorMatrixColorFilter(cm)); c.drawBitmap(work,0,0,p);
            work.recycle(); work=temp;
        }
        if(sharper) {
            int w=work.getWidth(), h=work.getHeight();
            int[] a=new int[w*h]; work.getPixels(a,0,w,0,0,w,h); int[] o=a.clone();
            for(int y=1;y<h-1;y++) for(int x=1;x<w-1;x++){
                int i=y*w+x, c=a[i], l=a[i-1], r=a[i+1], u=a[i-w], dn=a[i+w];
                int rr=clamp((Color.red(c)*6-Color.red(l)-Color.red(r)-Color.red(u)-Color.red(dn))/2);
                int gg=clamp((Color.green(c)*6-Color.green(l)-Color.green(r)-Color.green(u)-Color.green(dn))/2);
                int bb=clamp((Color.blue(c)*6-Color.blue(l)-Color.blue(r)-Color.blue(u)-Color.blue(dn))/2);
                o[i]=Color.argb(Color.alpha(c),rr,gg,bb);
            }
            work.setPixels(o,0,w,0,0,w,h);
        }
        return work;
    }

    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
    private static float dist(PointF a,PointF b){float dx=a.x-b.x,dy=a.y-b.y;return (float)Math.sqrt(dx*dx+dy*dy);}
}
