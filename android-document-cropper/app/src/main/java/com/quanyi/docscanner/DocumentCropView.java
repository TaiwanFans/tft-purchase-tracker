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
import android.view.MotionEvent;
import android.view.View;

public class DocumentCropView extends View {
    private Bitmap bitmap;
    private PointF[] corners;
    private final Matrix imageMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensCross = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 0..3 = corners, 4=top, 5=right, 6=bottom, 7=left
    private int active = -1;
    private float handleSize;
    private float edgeLong;
    private float edgeShort;
    private float touchImageX;
    private float touchImageY;
    private boolean showMagnifier = false;

    public DocumentCropView(Context c) {
        super(c);
        handleSize = dp(17);
        edgeLong = dp(25);
        edgeShort = dp(11);

        linePaint.setColor(Color.rgb(76,255,169));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(3));

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

        setBackgroundColor(Color.rgb(16,24,32));
    }

    public void setDocument(Bitmap b, PointF[] p) {
        bitmap = b;
        corners = p;
        invalidate();
    }

    public PointF[] getCorners() {
        PointF[] o = new PointF[4];
        for (int i=0;i<4;i++) o[i] = new PointF(corners[i].x, corners[i].y);
        return o;
    }

    public void setCorners(PointF[] p) {
        corners = p;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || corners == null) return;
        updateMatrix();
        c.drawBitmap(bitmap, imageMatrix, imagePaint);

        float[] v = mappedCorners();
        Path path = new Path();
        path.moveTo(v[0], v[1]);
        for (int i=1;i<4;i++) path.lineTo(v[i*2], v[i*2+1]);
        path.close();
        c.drawPath(path, linePaint);

        for (int i=0;i<4;i++) {
            float x=v[i*2], y=v[i*2+1];
            c.drawRect(x-handleSize,y-handleSize,x+handleSize,y+handleSize,handlePaint);
            c.drawRect(x-handleSize,y-handleSize,x+handleSize,y+handleSize,handleBorder);
            c.drawLine(x-dp(6),y,x+dp(6),y,handleBorder);
            c.drawLine(x,y-dp(6),x,y+dp(6),handleBorder);
        }

        drawEdgeHandle(c, midpoint(v,0,1), true);
        drawEdgeHandle(c, midpoint(v,1,2), false);
        drawEdgeHandle(c, midpoint(v,2,3), true);
        drawEdgeHandle(c, midpoint(v,3,0), false);

        if (showMagnifier && active >= 0) drawMagnifier(c);
    }

    private void drawEdgeHandle(Canvas c, PointF p, boolean horizontal) {
        float hw = horizontal ? edgeLong : edgeShort;
        float hh = horizontal ? edgeShort : edgeLong;
        c.drawRect(p.x-hw,p.y-hh,p.x+hw,p.y+hh,handlePaint);
        c.drawRect(p.x-hw,p.y-hh,p.x+hw,p.y+hh,handleBorder);
        if (horizontal) c.drawLine(p.x-dp(10),p.y,p.x+dp(10),p.y,handleBorder);
        else c.drawLine(p.x,p.y-dp(10),p.x,p.y+dp(10),handleBorder);
    }

    private void drawMagnifier(Canvas c) {
        if (bitmap == null) return;
        final float radius = dp(58);
        final float margin = dp(14);
        float lensX;
        float lensY = radius + margin;

        float[] touchView = {touchImageX,touchImageY};
        imageMatrix.mapPoints(touchView);
        if (touchView[0] < getWidth()/2f) lensX = getWidth()-radius-margin;
        else lensX = radius+margin;
        if (touchView[1] < radius*2.4f) lensY = getHeight()-radius-margin;

        float[] matrixVals = new float[9];
        imageMatrix.getValues(matrixVals);
        float displayScale = Math.max(0.01f, matrixVals[Matrix.MSCALE_X]);
        float zoom = 3.0f;
        float sampleRadius = radius / (displayScale * zoom);

        float left = clamp(touchImageX - sampleRadius, 0, bitmap.getWidth()-1);
        float top = clamp(touchImageY - sampleRadius, 0, bitmap.getHeight()-1);
        float right = clamp(touchImageX + sampleRadius, 1, bitmap.getWidth());
        float bottom = clamp(touchImageY + sampleRadius, 1, bitmap.getHeight());
        if (right <= left + 1 || bottom <= top + 1) return;

        int save = c.save();
        Path clip = new Path();
        clip.addCircle(lensX,lensY,radius,Path.Direction.CW);
        c.clipPath(clip);
        c.drawColor(Color.WHITE);
        Rect src = new Rect((int)left,(int)top,(int)right,(int)bottom);
        RectF dst = new RectF(lensX-radius,lensY-radius,lensX+radius,lensY+radius);
        c.drawBitmap(bitmap,src,dst,imagePaint);
        c.restoreToCount(save);

        c.drawCircle(lensX,lensY,radius,lensBorder);
        c.drawLine(lensX-dp(12),lensY,lensX+dp(12),lensY,lensCross);
        c.drawLine(lensX,lensY-dp(12),lensX,lensY+dp(12),lensCross);
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

    private void updateMatrix() {
        if (getWidth()==0 || getHeight()==0 || bitmap==null) return;
        float sx=getWidth()/(float)bitmap.getWidth();
        float sy=getHeight()/(float)bitmap.getHeight();
        float s=Math.min(sx,sy);
        float dx=(getWidth()-bitmap.getWidth()*s)/2f;
        float dy=(getHeight()-bitmap.getHeight()*s)/2f;
        imageMatrix.reset();
        imageMatrix.postScale(s,s);
        imageMatrix.postTranslate(dx,dy);
        imageMatrix.invert(inverse);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (bitmap==null || corners==null) return false;
        updateMatrix();
        float x=e.getX(), y=e.getY();
        float[] p={x,y};
        inverse.mapPoints(p);
        touchImageX=clamp(p[0],0,bitmap.getWidth());
        touchImageY=clamp(p[1],0,bitmap.getHeight());

        if (e.getAction()==MotionEvent.ACTION_DOWN) {
            active=nearestHandle(x,y);
            showMagnifier=active>=0;
            invalidate();
            return true;
        }

        if (e.getAction()==MotionEvent.ACTION_MOVE && active>=0) {
            if (active < 4) moveCorner(active,touchImageX,touchImageY);
            else moveEdge(active,touchImageX,touchImageY);
            invalidate();
            return true;
        }

        if (e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL) {
            active=-1;
            showMagnifier=false;
            invalidate();
            return true;
        }
        return true;
    }

    private void moveCorner(int idx, float x, float y) {
        corners[idx].x=clamp(x,0,bitmap.getWidth());
        corners[idx].y=clamp(y,0,bitmap.getHeight());
    }

    private void moveEdge(int edge, float x, float y) {
        float gap = Math.max(12f, Math.min(bitmap.getWidth(),bitmap.getHeight())*0.025f);
        if (edge==4) {
            float current=(corners[0].y+corners[1].y)/2f;
            float delta=y-current;
            float minDelta=-Math.min(corners[0].y,corners[1].y);
            float maxDelta=Math.min(corners[3].y-gap-corners[0].y,corners[2].y-gap-corners[1].y);
            delta=clamp(delta,minDelta,maxDelta);
            corners[0].y+=delta; corners[1].y+=delta;
        } else if (edge==6) {
            float current=(corners[2].y+corners[3].y)/2f;
            float delta=y-current;
            float minDelta=Math.max(corners[1].y+gap-corners[2].y,corners[0].y+gap-corners[3].y);
            float maxDelta=bitmap.getHeight()-Math.max(corners[2].y,corners[3].y);
            delta=clamp(delta,minDelta,maxDelta);
            corners[2].y+=delta; corners[3].y+=delta;
        } else if (edge==5) {
            float current=(corners[1].x+corners[2].x)/2f;
            float delta=x-current;
            float minDelta=Math.max(corners[0].x+gap-corners[1].x,corners[3].x+gap-corners[2].x);
            float maxDelta=bitmap.getWidth()-Math.max(corners[1].x,corners[2].x);
            delta=clamp(delta,minDelta,maxDelta);
            corners[1].x+=delta; corners[2].x+=delta;
        } else if (edge==7) {
            float current=(corners[0].x+corners[3].x)/2f;
            float delta=x-current;
            float minDelta=-Math.min(corners[0].x,corners[3].x);
            float maxDelta=Math.min(corners[1].x-gap-corners[0].x,corners[2].x-gap-corners[3].x);
            delta=clamp(delta,minDelta,maxDelta);
            corners[0].x+=delta; corners[3].x+=delta;
        }
    }

    private int nearestHandle(float x,float y) {
        float[] v=mappedCorners();
        int idx=-1;
        float best=dp(82);
        for (int i=0;i<4;i++) {
            float d=distance(x,y,v[i*2],v[i*2+1]);
            if (d<best) { best=d; idx=i; }
        }
        PointF[] mids={midpoint(v,0,1),midpoint(v,1,2),midpoint(v,2,3),midpoint(v,3,0)};
        for (int i=0;i<4;i++) {
            float d=distance(x,y,mids[i].x,mids[i].y);
            if (d<best && d<dp(68)) { best=d; idx=4+i; }
        }
        return idx;
    }

    private float distance(float x1,float y1,float x2,float y2) {
        float dx=x1-x2,dy=y1-y2;
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    private float clamp(float v,float min,float max) {
        if (max < min) return min;
        return Math.max(min,Math.min(max,v));
    }

    private float dp(float v) { return v*getResources().getDisplayMetrics().density; }
}
