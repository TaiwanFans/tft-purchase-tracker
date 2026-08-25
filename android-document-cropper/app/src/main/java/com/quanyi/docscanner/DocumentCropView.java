package com.quanyi.docscanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

public class DocumentCropView extends View {
    private Bitmap bitmap;
    private PointF[] corners;
    private final Matrix imageMatrix=new Matrix();
    private final Matrix inverse=new Matrix();
    private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleBorder=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int active=-1;
    private float handleSize;

    public DocumentCropView(Context c){
        super(c);
        handleSize=dp(18);
        linePaint.setColor(Color.rgb(76,255,169));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(3));
        handlePaint.setColor(Color.rgb(245,250,250));
        handlePaint.setStyle(Paint.Style.FILL);
        handleBorder.setColor(Color.rgb(76,255,169));
        handleBorder.setStyle(Paint.Style.STROKE);
        handleBorder.setStrokeWidth(dp(3));
        setBackgroundColor(Color.rgb(16,24,32));
    }

    public void setDocument(Bitmap b,PointF[] p){bitmap=b;corners=p;invalidate();}
    public PointF[] getCorners(){PointF[] o=new PointF[4];for(int i=0;i<4;i++)o[i]=new PointF(corners[i].x,corners[i].y);return o;}
    public void setCorners(PointF[] p){corners=p;invalidate();}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        if(bitmap==null||corners==null)return;
        updateMatrix();
        c.drawBitmap(bitmap,imageMatrix,imagePaint);
        float[] v=new float[8];
        for(int i=0;i<4;i++){v[i*2]=corners[i].x;v[i*2+1]=corners[i].y;}
        imageMatrix.mapPoints(v);
        Path path=new Path();
        path.moveTo(v[0],v[1]);
        for(int i=1;i<4;i++)path.lineTo(v[i*2],v[i*2+1]);
        path.close();
        c.drawPath(path,linePaint);
        for(int i=0;i<4;i++){
            float x=v[i*2],y=v[i*2+1];
            c.drawRect(x-handleSize,y-handleSize,x+handleSize,y+handleSize,handlePaint);
            c.drawRect(x-handleSize,y-handleSize,x+handleSize,y+handleSize,handleBorder);
            c.drawLine(x-dp(6),y,x+dp(6),y,handleBorder);
            c.drawLine(x,y-dp(6),x,y+dp(6),handleBorder);
        }
    }

    private void updateMatrix(){
        if(getWidth()==0||getHeight()==0||bitmap==null)return;
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

    @Override public boolean onTouchEvent(MotionEvent e){
        if(bitmap==null||corners==null)return false;
        updateMatrix();
        float x=e.getX(),y=e.getY();
        if(e.getAction()==MotionEvent.ACTION_DOWN){active=nearest(x,y);return true;}
        if(e.getAction()==MotionEvent.ACTION_MOVE&&active>=0){
            float[] p={x,y};
            inverse.mapPoints(p);
            corners[active].x=Math.max(0,Math.min(bitmap.getWidth(),p[0]));
            corners[active].y=Math.max(0,Math.min(bitmap.getHeight(),p[1]));
            invalidate();
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){active=-1;return true;}
        return true;
    }

    private int nearest(float x,float y){
        float[] v=new float[8];
        for(int i=0;i<4;i++){v[i*2]=corners[i].x;v[i*2+1]=corners[i].y;}
        imageMatrix.mapPoints(v);
        int idx=-1;float best=dp(82);
        for(int i=0;i<4;i++){
            float dx=x-v[i*2],dy=y-v[i*2+1],d=(float)Math.sqrt(dx*dx+dy*dy);
            if(d<best){best=d;idx=i;}
        }
        return idx;
    }

    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
