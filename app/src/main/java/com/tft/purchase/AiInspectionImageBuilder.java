package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the compact high-resolution inspection sheet sent to the local vision model.
 *
 * V2.0.17 always includes representative header/table crops so MiniCPM can rescue OCR omissions
 * and confidently-wrong OCR, not only explicit PP-vs-ML conflicts. Conflict/low-confidence points
 * are then appended as additional close-up crops. The full A4 page is still avoided to preserve
 * readable character scale and memory on Android.
 */
public final class AiInspectionImageBuilder {
    private AiInspectionImageBuilder() {}

    private static final Pattern POINT = Pattern.compile(
            "(?:CONFLICT|LOW_CONFIDENCE) @([0-9.]+),([0-9.]+)[^\\n]*");

    private static final class Focus {
        final float x,y,w,h; final String label;
        Focus(float x,float y,float w,float h,String label){
            this.x=x;this.y=y;this.w=w;this.h=h;this.label=label;
        }
    }

    public static String build(Context context, String correctedVisionPath, String evidence) throws Exception {
        Bitmap source = BitmapFactory.decodeFile(correctedVisionPath);
        if (source == null) throw new Exception("無法建立 AI 高解析核對圖片");
        File out = new File(context.getCacheDir(), "ai_inspection_" + System.nanoTime() + ".jpg");
        try {
            List<Focus> focus = buildFocus(evidence);
            int count = Math.min(8, focus.size());
            int sheetW = 1800;
            int blockH = 350;
            int sheetH = Math.max(700, count * blockH);
            Bitmap sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(Color.WHITE);

            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextSize(28f);
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(3f);
            border.setColor(Color.DKGRAY);

            for (int i=0; i<count; i++) {
                Focus f = focus.get(i);
                int cropW = Math.max(220, Math.round(source.getWidth() * f.w));
                int cropH = Math.max(130, Math.round(source.getHeight() * f.h));
                cropW = Math.min(cropW, source.getWidth());
                cropH = Math.min(cropH, source.getHeight());
                int cx = Math.round(source.getWidth() * f.x);
                int cy = Math.round(source.getHeight() * f.y);
                int left = clamp(cx - cropW/2, 0, Math.max(0, source.getWidth()-cropW));
                int top = clamp(cy - cropH/2, 0, Math.max(0, source.getHeight()-cropH));
                cropW = Math.min(cropW, source.getWidth()-left);
                cropH = Math.min(cropH, source.getHeight()-top);

                Bitmap crop = Bitmap.createBitmap(source, left, top, cropW, cropH);
                try {
                    float maxW = sheetW - 50f;
                    float maxH = blockH - 60f;
                    float scale = Math.min(maxW/crop.getWidth(), maxH/crop.getHeight());
                    float dw = crop.getWidth()*scale;
                    float dh = crop.getHeight()*scale;
                    float x = 25f + (maxW-dw)/2f;
                    float y = i*blockH + 48f + (maxH-dh)/2f;
                    RectF dst = new RectF(x,y,x+dw,y+dh);
                    canvas.drawBitmap(crop, null, dst, null);
                    canvas.drawRect(dst,border);
                    String label = String.format(Locale.US,"C%d  %s  @ %.3f,%.3f",i+1,compact(f.label),f.x,f.y);
                    canvas.drawText(label,25f,i*blockH+32f,labelPaint);
                } finally {
                    crop.recycle();
                }
            }

            save(sheet,out);
            sheet.recycle();
            return out.getAbsolutePath();
        } finally {
            source.recycle();
        }
    }

    private static List<Focus> buildFocus(String evidence) {
        List<Focus> out = new ArrayList<>();
        // Always-visible anchors: OCR can be wrong with high confidence or omit a row entirely.
        out.add(new Focus(0.27f,0.15f,0.54f,0.19f,"H1 供應廠商／地址（固定核對）"));
        out.add(new Focus(0.73f,0.15f,0.54f,0.19f,"H2 採購單號／採購日期（固定核對）"));
        out.add(new Focus(0.50f,0.37f,0.94f,0.145f,"R1 品項表格上段（固定核對）"));
        out.add(new Focus(0.50f,0.54f,0.94f,0.145f,"R2 品項表格中上段（固定核對）"));
        out.add(new Focus(0.50f,0.70f,0.94f,0.145f,"R3 品項表格中下段（固定核對）"));
        out.add(new Focus(0.50f,0.86f,0.94f,0.14f,"R4 品項表格下段（固定核對）"));

        Matcher m = POINT.matcher(evidence == null ? "" : evidence);
        while (m.find() && out.size() < 8) {
            float x = f(m.group(1)), y = f(m.group(2));
            if (x < 0f || x > 1f || y < 0f || y > 1f) continue;
            boolean duplicate=false;
            for (Focus old : out) {
                if (Math.abs(old.x-x)<0.035f && Math.abs(old.y-y)<0.025f) { duplicate=true; break; }
            }
            if (!duplicate) out.add(new Focus(x,y,0.38f,0.12f,"OCR 衝突／低信心："+m.group(0)));
        }
        return out;
    }

    private static void save(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 98, fos)) throw new Exception("AI 高解析核對圖片建立失敗");
        }
    }

    private static String compact(String s){
        String x=s==null?"":s.replace('\n',' ').trim();
        return x.length()>76?x.substring(0,76):x;
    }
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static float f(String s){try{return Float.parseFloat(s);}catch(Exception e){return -1f;}}
}
