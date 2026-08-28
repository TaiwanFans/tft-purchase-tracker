package com.quanyi.docscanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

public class DocumentCropView extends View {
    private static final float PRECISION_SCALE = 0.36f;

    private Bitmap bitmap;
    private Bitmap previewBitmap;
    private PointF[] corners;
    private final Matrix imageMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Matrix previewMatrix = new Matrix();
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensCross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensLabel = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 0..3 = corners, 4=top, 5=right, 6=bottom, 7=left
    private int active = -1;
    private float handleSize;
    private float edgeLong;
    private float edgeShort;
    private boolean showMagnifier = false;

    private float touchImageX;
    private float touchImageY;
    private float dragStartTouchX;
    private float dragStartTouchY;
    private float focusImageX;
    private float focusImageY;
    private PointF[] dragStartCorners;

    // Small precomputed gradient map for live edge snapping.
    // It is built once per image and reused during drag, so ACTION_MOVE stays cheap.
    private int[] snapGradX;
    private int[] snapGradY;
    private int snapW;
    private int snapH;
    private boolean snapActive = false;

    public DocumentCropView(Context c) {
        super(c);
        // CamScanner-style compact visual handles. Invisible touch targets remain large.
        handleSize = dp(10);
        edgeLong = dp(18);
        edgeShort = dp(7);

        linePaint.setColor(Color.rgb(76,255,169));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));

        handlePaint.setColor(Color.rgb(245,250,250));
        handlePaint.setStyle(Paint.Style.FILL);

        handleBorder.setColor(Color.rgb(76,255,169));
        handleBorder.setStyle(Paint.Style.STROKE);
        handleBorder.setStrokeWidth(dp(3));

        lensBorder.setColor(Color.rgb(76,255,169));
        lensBorder.setStyle(Paint.Style.STROKE);
        lensBorder.setStrokeWidth(dp(4));

        lensCross.setColor(Color.rgb(16,24,32));
        lensCross.setStyle(Paint.Style.STROKE);
        lensCross.setStrokeWidth(dp(2));

        lensLabel.setColor(Color.rgb(76,255,169));
        lensLabel.setTextSize(dp(11));
        lensLabel.setTypeface(Typeface.DEFAULT_BOLD);
        lensLabel.setTextAlign(Paint.Align.CENTER);

        imagePaint.setDither(false);
        setBackgroundColor(Color.rgb(16,24,32));
    }

    public void setDocument(Bitmap b, PointF[] p) {
        recyclePreview();
        bitmap = b;
        previewBitmap = buildPreviewBitmap(b);
        buildSnapEdgeMap(b);
        corners = p;
        active = -1;
        dragStartCorners = null;
        showMagnifier = false;
        invalidate();
    }

    public PointF[] getCorners() {
        PointF[] o = new PointF[4];
        for (int i=0;i<4;i++) o[i] = new PointF(corners[i].x, corners[i].y);
        return o;
    }

    public void setCorners(PointF[] p) {
        corners = p;
        active = -1;
        dragStartCorners = null;
        showMagnifier = false;
        invalidate();
    }

    private Bitmap buildPreviewBitmap(Bitmap source) {
        if (source == null || source.isRecycled()) return source;
        int longest = Math.max(source.getWidth(), source.getHeight());
        long heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        int target;
        if (heapMb <= 256) target = 900;
        else if (heapMb <= 384) target = 1100;
        else if (heapMb <= 512) target = 1350;
        else target = 1700;
        if (longest <= target) return source;
        float s = target / (float)longest;
        try {
            return Bitmap.createScaledBitmap(
                    source,
                    Math.max(1, Math.round(source.getWidth()*s)),
                    Math.max(1, Math.round(source.getHeight()*s)),
                    true
            );
        } catch (Throwable ignored) {
            return source;
        }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || corners == null) return;
        updateMatrix();

        Bitmap drawBitmap = previewBitmap != null && !previewBitmap.isRecycled() ? previewBitmap : bitmap;
        updatePreviewMatrix(drawBitmap);

        // 拖曳時關閉底圖濾鏡，可降低舊手機每幀重繪負擔；放大鏡仍保持清楚。
        imagePaint.setFilterBitmap(!showMagnifier);
        c.drawBitmap(drawBitmap, previewMatrix, imagePaint);
        imagePaint.setFilterBitmap(true);

        float[] v = mappedCorners();
        Path path = new Path();
        path.moveTo(v[0], v[1]);
        for (int i=1;i<4;i++) path.lineTo(v[i*2], v[i*2+1]);
        path.close();
        c.drawPath(path, linePaint);

        for (int i=0;i<4;i++) {
            float x=v[i*2], y=v[i*2+1];
            c.drawCircle(x, y, handleSize, handlePaint);
            c.drawCircle(x, y, handleSize, handleBorder);
        }

        drawEdgeHandle(c, midpoint(v,0,1), true);
        drawEdgeHandle(c, midpoint(v,1,2), false);
        drawEdgeHandle(c, midpoint(v,2,3), true);
        drawEdgeHandle(c, midpoint(v,3,0), false);

        if (showMagnifier && active >= 0) drawMagnifier(c, drawBitmap);
    }

    private void drawEdgeHandle(Canvas c, PointF p, boolean horizontal) {
        float hw = horizontal ? edgeLong : edgeShort;
        float hh = horizontal ? edgeShort : edgeLong;
        RectF r = new RectF(p.x-hw,p.y-hh,p.x+hw,p.y+hh);
        float radius = dp(7);
        c.drawRoundRect(r, radius, radius, handlePaint);
        c.drawRoundRect(r, radius, radius, handleBorder);
    }

    private void drawMagnifier(Canvas c, Bitmap drawBitmap) {
        if (drawBitmap == null || drawBitmap.isRecycled()) return;

        final float radius = dp(60);
        final float margin = dp(14);
        float lensX;
        float lensY = radius + margin;

        float[] focusView = {focusImageX, focusImageY};
        imageMatrix.mapPoints(focusView);
        if (focusView[0] < getWidth()/2f) lensX = getWidth()-radius-margin;
        else lensX = radius+margin;
        if (focusView[1] < radius*2.5f) lensY = getHeight()-radius-margin-dp(18);

        float px = focusImageX * drawBitmap.getWidth() / (float)bitmap.getWidth();
        float py = focusImageY * drawBitmap.getHeight() / (float)bitmap.getHeight();

        float[] matrixVals = new float[9];
        previewMatrix.getValues(matrixVals);
        float displayScale = Math.max(0.01f, matrixVals[Matrix.MSCALE_X]);
        float zoom = 3.2f;
        float sampleRadius = radius / (displayScale * zoom);

        float left = clamp(px - sampleRadius, 0, drawBitmap.getWidth()-1);
        float top = clamp(py - sampleRadius, 0, drawBitmap.getHeight()-1);
        float right = clamp(px + sampleRadius, 1, drawBitmap.getWidth());
        float bottom = clamp(py + sampleRadius, 1, drawBitmap.getHeight());
        if (right <= left + 1 || bottom <= top + 1) return;

        int save = c.save();
        Path clip = new Path();
        clip.addCircle(lensX,lensY,radius,Path.Direction.CW);
        c.clipPath(clip);
        c.drawColor(Color.WHITE);
        Rect src = new Rect((int)left,(int)top,(int)right,(int)bottom);
        RectF dst = new RectF(lensX-radius,lensY-radius,lensX+radius,lensY+radius);
        c.drawBitmap(drawBitmap,src,dst,imagePaint);
        c.restoreToCount(save);

        c.drawCircle(lensX,lensY,radius,lensBorder);
        c.drawLine(lensX-dp(13),lensY,lensX+dp(13),lensY,lensCross);
        c.drawLine(lensX,lensY-dp(13),lensX,lensY+dp(13),lensCross);
        c.drawText(active >= 4 ? (snapActive ? "自動貼邊 ✓" : "自動貼邊") : "精細 0.36×", lensX, lensY + radius + dp(16), lensLabel);
    }

    private float[] mappedCorners() {
        float[] v = new float[8];
        for (int i=0;i<4;i++) {
            v[i*2]=corners[i].x;
            v[i*2+1]=corners[i].y;
        }
        imageMatrix.mapPoints(v);
        return v;
    }

    private PointF midpoint(float[] v, int a, int b) {
        return new PointF((v[a*2]+v[b*2])/2f,(v[a*2+1]+v[b*2+1])/2f);
    }

    private PointF midpointImage(int a, int b) {
        return new PointF((corners[a].x+corners[b].x)/2f,(corners[a].y+corners[b].y)/2f);
    }

    private float cropSideInsetPx() {
        // True finger-safe canvas. The document itself is rendered inward,
        // while all crop coordinates remain in original-image coordinates.
        return clamp(getWidth() * 0.090f, dp(30), dp(46));
    }

    private float cropVerticalInsetPx() {
        return clamp(getHeight() * 0.030f, dp(14), dp(28));
    }

    private void updateMatrix() {
        if (getWidth()==0 || getHeight()==0 || bitmap==null) return;
        float side=cropSideInsetPx();
        float vertical=cropVerticalInsetPx();
        float usableW=Math.max(1f,getWidth()-side*2f);
        float usableH=Math.max(1f,getHeight()-vertical*2f);
        float sx=usableW/(float)bitmap.getWidth();
        float sy=usableH/(float)bitmap.getHeight();
        float s=Math.min(sx,sy);
        float dx=side+(usableW-bitmap.getWidth()*s)/2f;
        float dy=vertical+(usableH-bitmap.getHeight()*s)/2f;
        imageMatrix.reset();
        imageMatrix.postScale(s,s);
        imageMatrix.postTranslate(dx,dy);
        imageMatrix.invert(inverse);
    }

    private void updatePreviewMatrix(Bitmap drawBitmap) {
        if (getWidth()==0 || getHeight()==0 || drawBitmap==null) return;
        float side=cropSideInsetPx();
        float vertical=cropVerticalInsetPx();
        float usableW=Math.max(1f,getWidth()-side*2f);
        float usableH=Math.max(1f,getHeight()-vertical*2f);
        float sx=usableW/(float)drawBitmap.getWidth();
        float sy=usableH/(float)drawBitmap.getHeight();
        float s=Math.min(sx,sy);
        float dx=side+(usableW-drawBitmap.getWidth()*s)/2f;
        float dy=vertical+(usableH-drawBitmap.getHeight()*s)/2f;
        previewMatrix.reset();
        previewMatrix.postScale(s,s);
        previewMatrix.postTranslate(dx,dy);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (bitmap==null || corners==null) return false;
        updateMatrix();

        float[] p={e.getX(),e.getY()};
        inverse.mapPoints(p);
        touchImageX=clamp(p[0],0,bitmap.getWidth());
        touchImageY=clamp(p[1],0,bitmap.getHeight());

        if (e.getAction()==MotionEvent.ACTION_DOWN) {
            active=nearestHandle(e.getX(),e.getY());
            snapActive=false;
            if (active >= 0) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragStartTouchX=touchImageX;
                dragStartTouchY=touchImageY;
                dragStartCorners=copyCorners(corners);
                updateFocusFromActive();
                showMagnifier=true;
            } else {
                showMagnifier=false;
            }
            postInvalidateOnAnimation();
            return true;
        }

        if (e.getAction()==MotionEvent.ACTION_MOVE && active>=0 && dragStartCorners!=null) {
            float dx=(touchImageX-dragStartTouchX)*PRECISION_SCALE;
            float dy=(touchImageY-dragStartTouchY)*PRECISION_SCALE;

            if (active < 4) {
                snapActive=false;
                moveCornerPrecise(active,dx,dy);
            } else {
                moveEdgePrecise(active,dx,dy);
                snapActive=snapEdgeToDocument(active);
            }

            updateFocusFromActive();
            postInvalidateOnAnimation();
            return true;
        }

        if (e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (e.getAction()==MotionEvent.ACTION_UP) performClick();
            active=-1;
            dragStartCorners=null;
            showMagnifier=false;
            snapActive=false;
            postInvalidateOnAnimation();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void moveCornerPrecise(int idx, float dx, float dy) {
        PointF s=dragStartCorners[idx];
        corners[idx].x=clamp(s.x+dx,0,bitmap.getWidth());
        corners[idx].y=clamp(s.y+dy,0,bitmap.getHeight());
    }

    private void moveEdgePrecise(int edge, float dx, float dy) {
        float gap=Math.max(12f,Math.min(bitmap.getWidth(),bitmap.getHeight())*0.025f);
        PointF[] s=dragStartCorners;

        if (edge==4) {
            float delta=dy;
            float minDelta=-Math.min(s[0].y,s[1].y);
            float maxDelta=Math.min(s[3].y-gap-s[0].y,s[2].y-gap-s[1].y);
            delta=clamp(delta,minDelta,maxDelta);
            corners[0].y=s[0].y+delta;
            corners[1].y=s[1].y+delta;
        } else if (edge==6) {
            float delta=dy;
            float minDelta=Math.max(s[1].y+gap-s[2].y,s[0].y+gap-s[3].y);
            float maxDelta=bitmap.getHeight()-Math.max(s[2].y,s[3].y);
            delta=clamp(delta,minDelta,maxDelta);
            corners[2].y=s[2].y+delta;
            corners[3].y=s[3].y+delta;
        } else if (edge==5) {
            float delta=dx;
            float minDelta=Math.max(s[0].x+gap-s[1].x,s[3].x+gap-s[2].x);
            float maxDelta=bitmap.getWidth()-Math.max(s[1].x,s[2].x);
            delta=clamp(delta,minDelta,maxDelta);
            corners[1].x=s[1].x+delta;
            corners[2].x=s[2].x+delta;
        } else if (edge==7) {
            float delta=dx;
            float minDelta=-Math.min(s[0].x,s[3].x);
            float maxDelta=Math.min(s[1].x-gap-s[0].x,s[2].x-gap-s[3].x);
            delta=clamp(delta,minDelta,maxDelta);
            corners[0].x=s[0].x+delta;
            corners[3].x=s[3].x+delta;
        }
    }

    private void buildSnapEdgeMap(Bitmap source) {
        snapGradX = null;
        snapGradY = null;
        snapW = snapH = 0;
        if (source == null || source.isRecycled()) return;
        Bitmap small = null;
        try {
            int longest = Math.max(source.getWidth(), source.getHeight());
            int target = 720;
            float scale = longest > target ? target / (float)longest : 1f;
            snapW = Math.max(32, Math.round(source.getWidth() * scale));
            snapH = Math.max(32, Math.round(source.getHeight() * scale));
            small = scale < 1f ? Bitmap.createScaledBitmap(source, snapW, snapH, true) : source;
            int[] px = new int[snapW * snapH];
            int[] gray = new int[snapW * snapH];
            small.getPixels(px, 0, snapW, 0, 0, snapW, snapH);
            for (int i=0;i<px.length;i++) {
                int color=px[i];
                gray[i]=(Color.red(color)*30 + Color.green(color)*59 + Color.blue(color)*11)/100;
            }
            snapGradX = new int[snapW * snapH];
            snapGradY = new int[snapW * snapH];
            for (int y=1;y<snapH-1;y++) {
                int row=y*snapW;
                for (int x=1;x<snapW-1;x++) {
                    int i=row+x;
                    snapGradX[i]=Math.abs(gray[i+1]-gray[i-1]);
                    snapGradY[i]=Math.abs(gray[i+snapW]-gray[i-snapW]);
                }
            }
        } catch (Throwable ignored) {
            snapGradX = null; snapGradY = null; snapW = snapH = 0;
        } finally {
            if (small != null && small != source && !small.isRecycled()) small.recycle();
        }
    }

    private boolean snapEdgeToDocument(int edge) {
        if (bitmap == null || snapGradX == null || snapGradY == null || snapW < 8 || snapH < 8) return false;
        if (edge < 4 || edge > 7) return false;
        float radius=Math.max(8f, Math.min(bitmap.getWidth(),bitmap.getHeight())*0.022f);
        float step=Math.max(1f, radius/12f);
        float base=edgeSnapScore(edge,0f);
        float bestRaw=base;
        float bestAdjusted=base;
        float bestDelta=0f;
        for (float d=-radius; d<=radius; d+=step) {
            if (Math.abs(d) < step*0.45f) continue;
            float raw=edgeSnapScore(edge,d);
            float adjusted=raw-(Math.abs(d)/radius)*18f;
            if (adjusted > bestAdjusted) {
                bestAdjusted=adjusted;
                bestRaw=raw;
                bestDelta=d;
            }
        }
        if (bestRaw < 22f) return false;
        if (Math.abs(bestDelta) >= 0.5f && bestAdjusted > base + 3f) {
            shiftCurrentEdge(edge,bestDelta);
            return true;
        }
        // Already sitting on a strong edge: show the user that auto-align is active.
        return base >= 30f;
    }

    private float edgeSnapScore(int edge, float delta) {
        PointF a,b;
        if (edge==4) { a=corners[0]; b=corners[1]; }
        else if (edge==5) { a=corners[1]; b=corners[2]; }
        else if (edge==6) { a=corners[3]; b=corners[2]; }
        else { a=corners[0]; b=corners[3]; }
        boolean horizontal=(edge==4 || edge==6);
        int[] grad=horizontal ? snapGradY : snapGradX;
        float sx=snapW/(float)Math.max(1,bitmap.getWidth());
        float sy=snapH/(float)Math.max(1,bitmap.getHeight());
        float sum=0f;
        int count=0;
        final int samples=30;
        for (int i=0;i<samples;i++) {
            float t=0.08f + (0.84f*i)/(samples-1f);
            float ox=a.x+(b.x-a.x)*t + (horizontal ? 0f : delta);
            float oy=a.y+(b.y-a.y)*t + (horizontal ? delta : 0f);
            int x=Math.round(ox*sx);
            int y=Math.round(oy*sy);
            if (x<2 || x>=snapW-2 || y<2 || y>=snapH-2) continue;
            int idx=y*snapW+x;
            int v=grad[idx];
            if (horizontal) v=Math.max(v,Math.max(grad[idx-snapW],grad[idx+snapW]));
            else v=Math.max(v,Math.max(grad[idx-1],grad[idx+1]));
            sum+=v;
            count++;
        }
        return count>0 ? sum/count : 0f;
    }

    private void shiftCurrentEdge(int edge, float delta) {
        float gap=Math.max(12f,Math.min(bitmap.getWidth(),bitmap.getHeight())*0.025f);
        if (edge==4) {
            float min=-Math.min(corners[0].y,corners[1].y);
            float max=Math.min(corners[3].y-gap-corners[0].y,corners[2].y-gap-corners[1].y);
            float d=clamp(delta,min,max);
            corners[0].y+=d; corners[1].y+=d;
        } else if (edge==6) {
            float min=Math.max(corners[1].y+gap-corners[2].y,corners[0].y+gap-corners[3].y);
            float max=bitmap.getHeight()-Math.max(corners[2].y,corners[3].y);
            float d=clamp(delta,min,max);
            corners[2].y+=d; corners[3].y+=d;
        } else if (edge==5) {
            float min=Math.max(corners[0].x+gap-corners[1].x,corners[3].x+gap-corners[2].x);
            float max=bitmap.getWidth()-Math.max(corners[1].x,corners[2].x);
            float d=clamp(delta,min,max);
            corners[1].x+=d; corners[2].x+=d;
        } else if (edge==7) {
            float min=-Math.min(corners[0].x,corners[3].x);
            float max=Math.min(corners[1].x-gap-corners[0].x,corners[2].x-gap-corners[3].x);
            float d=clamp(delta,min,max);
            corners[0].x+=d; corners[3].x+=d;
        }
    }

    private void updateFocusFromActive() {
        if (active < 0) return;
        if (active < 4) {
            focusImageX=corners[active].x;
            focusImageY=corners[active].y;
        } else {
            PointF m;
            if (active==4) m=midpointImage(0,1);
            else if (active==5) m=midpointImage(1,2);
            else if (active==6) m=midpointImage(2,3);
            else m=midpointImage(3,0);
            focusImageX=m.x;
            focusImageY=m.y;
        }
    }

    private PointF[] copyCorners(PointF[] src) {
        PointF[] out=new PointF[4];
        for (int i=0;i<4;i++) out[i]=new PointF(src[i].x,src[i].y);
        return out;
    }

    private int nearestHandle(float x,float y) {
        float[] v=mappedCorners();
        int idx=-1;
        float best=dp(110);

        for (int i=0;i<4;i++) {
            float d=distance(x,y,v[i*2],v[i*2+1]);
            if (d<best) { best=d; idx=i; }
        }

        PointF[] mids={
                midpoint(v,0,1),
                midpoint(v,1,2),
                midpoint(v,2,3),
                midpoint(v,3,0)
        };
        for (int i=0;i<4;i++) {
            float d=distance(x,y,mids[i].x,mids[i].y);
            if (d<best && d<dp(92)) { best=d; idx=4+i; }
        }
        return idx;
    }

    private void recyclePreview() {
        if (previewBitmap != null && previewBitmap != bitmap && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
        snapGradX = null;
        snapGradY = null;
        snapW = snapH = 0;
    }

    @Override protected void onDetachedFromWindow() {
        recyclePreview();
        super.onDetachedFromWindow();
    }

    private float distance(float x1,float y1,float x2,float y2) {
        float dx=x1-x2,dy=y1-y2;
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    private float clamp(float v,float min,float max) {
        if (max < min) return min;
        return Math.max(min,Math.min(max,v));
    }

    private float dp(float v) {
        return v*getResources().getDisplayMetrics().density;
    }
}
