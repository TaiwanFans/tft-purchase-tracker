package com.quanyi.docscanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public class ZoomableImageView extends ImageView {
    private final Matrix imageMatrixInternal = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float relativeScale = 1f;
    private final float minRelativeScale = 1f;
    private final float maxRelativeScale = 5f;
    private float lastX, lastY;
    private boolean dragging = false;

    public ZoomableImageView(Context context) { super(context); init(context); }
    public ZoomableImageView(Context context, AttributeSet attrs) { super(context, attrs); init(context); }
    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(context); }

    private void init(Context context) {
        super.setScaleType(ScaleType.MATRIX);
        setClickable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float target = relativeScale * factor;
                if (target < minRelativeScale) factor = minRelativeScale / relativeScale;
                if (target > maxRelativeScale) factor = maxRelativeScale / relativeScale;
                relativeScale *= factor;
                imageMatrixInternal.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
                setImageMatrix(imageMatrixInternal);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(relativeScale > 1.01f);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }
        });
        setOnTouchListener((v, event) -> handleTouch(event));
    }

    private boolean handleTouch(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX(); lastY = event.getY(); dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 && relativeScale > 1.01f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) > 0.2f || Math.abs(dy) > 0.2f) dragging = true;
                    imageMatrixInternal.postTranslate(dx, dy);
                    fixTranslation();
                    setImageMatrix(imageMatrixInternal);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastX = event.getX(); lastY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                dragging = false;
                break;
        }
        return true;
    }

    @Override public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::resetZoom);
    }

    public void setImageBitmapKeepTransform(Bitmap bm) {
        // Compare mode swaps equal-sized current-page bitmaps. Keep the user's
        // pinch zoom / pan matrix so the exact same text region stays visible.
        super.setImageBitmap(bm);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::resetZoom);
    }

    public void resetZoom() {
        Drawable d = getDrawable();
        if (d == null || getWidth() <= 0 || getHeight() <= 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        imageMatrixInternal.reset();
        float scale = Math.min(getWidth() / (float)dw, getHeight() / (float)dh);
        float dx = (getWidth() - dw * scale) * 0.5f;
        float dy = (getHeight() - dh * scale) * 0.5f;
        imageMatrixInternal.postScale(scale, scale);
        imageMatrixInternal.postTranslate(dx, dy);
        relativeScale = 1f;
        setImageMatrix(imageMatrixInternal);
        invalidate();
    }

    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        imageMatrixInternal.mapRect(rect);
        float dx = 0f, dy = 0f;
        if (rect.width() <= getWidth()) dx = getWidth() * 0.5f - rect.centerX();
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < getWidth()) dx = getWidth() - rect.right;
        if (rect.height() <= getHeight()) dy = getHeight() * 0.5f - rect.centerY();
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
        imageMatrixInternal.postTranslate(dx, dy);
    }
}
