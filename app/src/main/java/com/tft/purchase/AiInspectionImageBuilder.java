package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the image sent to the vision model.
 *
 * The full A4 page is intentionally NOT sent by default. OCR + global coordinates are the primary
 * evidence. Only OCR CONFLICT / LOW_CONFIDENCE locations are cropped from the corrected colour page,
 * enlarged, and assembled into a compact inspection sheet. When no visual re-check is needed, a tiny
 * neutral image is generated so the multimodal runtime can still accept the text/coordinate prompt.
 */
public final class AiInspectionImageBuilder {
    private AiInspectionImageBuilder() {}

    private static final Pattern POINT = Pattern.compile(
            "(?:CONFLICT|LOW_CONFIDENCE) @([0-9.]+),([0-9.]+)[^\\n]*");

    private static final class Focus {
        final float x,y; final String label;
        Focus(float x,float y,String label){this.x=x;this.y=y;this.label=label;}
    }

    public static String build(Context context, String correctedVisionPath, String evidence) throws Exception {
        List<Focus> focus = parse(evidence);
        File out = new File(context.getCacheDir(), "ai_inspection_" + System.nanoTime() + ".jpg");
        if (focus.isEmpty()) {
            Bitmap neutral = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(neutral);
            canvas.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.DKGRAY); p.setTextSize(28f);
            canvas.drawText("OCR coordinates are primary; no visual conflict.", 28, 165, p);
            save(neutral, out);
            neutral.recycle();
            return out.getAbsolutePath();
        }

        Bitmap source = BitmapFactory.decodeFile(correctedVisionPath);
        if (source == null) throw new Exception("無法建立 AI 局部核對圖片");
        try {
            int count = Math.min(6, focus.size());
            int sheetW = 1800;
            int blockH = 430;
            int sheetH = Math.max(520, count * blockH);
            Bitmap sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(Color.WHITE);
            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(Color.BLACK); labelPaint.setTextSize(30f);
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(3f); border.setColor(Color.DKGRAY);

            for (int i=0; i<count; i++) {
                Focus f = focus.get(i);
                int cropW = Math.max(240, Math.round(source.getWidth() * 0.34f));
                int cropH = Math.max(150, Math.round(source.getHeight() * 0.115f));
                int cx = Math.round(source.getWidth() * f.x);
                int cy = Math.round(source.getHeight() * f.y);
                int left = clamp(cx - cropW/2, 0, Math.max(0, source.getWidth()-cropW));
                int top = clamp(cy - cropH/2, 0, Math.max(0, source.getHeight()-cropH));
                cropW = Math.min(cropW, source.getWidth()-left);
                cropH = Math.min(cropH, source.getHeight()-top);
                Bitmap crop = Bitmap.createBitmap(source, left, top, cropW, cropH);
                try {
                    float maxW = sheetW - 60f;
                    float maxH = blockH - 72f;
                    float scale = Math.min(maxW/crop.getWidth(), maxH/crop.getHeight());
                    float dw = crop.getWidth()*scale;
                    float dh = crop.getHeight()*scale;
                    float x = 30f + (maxW-dw)/2f;
                    float y = i*blockH + 58f + (maxH-dh)/2f;
                    RectF dst = new RectF(x,y,x+dw,y+dh);
                    canvas.drawBitmap(crop, null, dst, null);
                    canvas.drawRect(dst,border);
                    String label = String.format(Locale.US,"C%d @ %.3f,%.3f  %s",i+1,f.x,f.y,compact(f.label));
                    canvas.drawText(label,30f,i*blockH+38f,labelPaint);
                } finally { crop.recycle(); }
            }
            save(sheet,out);
            sheet.recycle();
            return out.getAbsolutePath();
        } finally {
            source.recycle();
        }
    }

    private static List<Focus> parse(String evidence) {
        List<Focus> out = new ArrayList<>();
        Matcher m = POINT.matcher(evidence == null ? "" : evidence);
        while (m.find() && out.size() < 12) {
            float x = f(m.group(1)), y = f(m.group(2));
            if (x < 0f || x > 1f || y < 0f || y > 1f) continue;
            boolean duplicate=false;
            for (Focus old : out) {
                if (Math.abs(old.x-x)<0.025f && Math.abs(old.y-y)<0.018f) { duplicate=true; break; }
            }
            if (!duplicate) out.add(new Focus(x,y,m.group(0)));
        }
        return out;
    }

    private static void save(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 98, fos)) throw new Exception("AI 局部核對圖片建立失敗");
        }
    }

    private static String compact(String s){
        String x=s==null?"":s.replace('\n',' ').trim();
        return x.length()>88?x.substring(0,88):x;
    }
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static float f(String s){try{return Float.parseFloat(s);}catch(Exception e){return -1f;}}
}
