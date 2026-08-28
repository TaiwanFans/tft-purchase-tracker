package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-detail OCR strategy: corrected page -> overlapping zones -> enlarge -> PP-OCRv6 Small ONNX
 * + ML Kit -> global coordinate mapping -> spatial consensus/conflict evidence.
 */
public final class ZoomTiledOcrBridge {
    private ZoomTiledOcrBridge() {}

    public interface Callback {
        default void onProgress(int percent, String stage) {}
        void onSuccess(String evidence);
        void onFailure(String error);
    }

    private static final class Tile {
        final String id; final float x,y,w,h,zoom;
        Tile(String id,float x,float y,float w,float h,float zoom){this.id=id;this.x=x;this.y=y;this.w=w;this.h=h;this.zoom=zoom;}
    }

    private static final class Entry {
        String engine=""; String tile=""; String text="";
        float x,y,w,h,conf,zoom;
        float cx(){return x+w/2f;} float cy(){return y+h/2f;}
    }

    private static final Tile[] PLAN = new Tile[]{
            new Tile("H1",0.00f,0.00f,0.62f,0.34f,2.70f),
            new Tile("H2",0.38f,0.00f,0.62f,0.34f,2.70f),
            // Overlap protects characters/rows that sit directly on a crop boundary.
            new Tile("R1",0.00f,0.22f,1.00f,0.27f,2.25f),
            new Tile("R2",0.00f,0.40f,1.00f,0.27f,2.25f),
            new Tile("R3",0.00f,0.58f,1.00f,0.25f,2.30f),
            // V2.0.17: cover lower item rows. Previous pipeline stopped around 82-84% of the page.
            new Tile("R4",0.00f,0.72f,1.00f,0.23f,2.35f)
    };

    private static final Pattern LINE = Pattern.compile(
            "\\[region=[^ ]+ x=([0-9.]+) y=([0-9.]+) w=([0-9.]+) h=([0-9.]+)(?: conf=([0-9.]+))?\\] (.*)");

    public static void recognize(Context context, String correctedOcrPath, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Bitmap full = null;
            try {
                full = BitmapFactory.decodeFile(correctedOcrPath);
                if (full == null || full.getWidth() < 400 || full.getHeight() < 600) {
                    throw new Exception("校正後採購單圖片尺寸無效");
                }

                List<Entry> entries = new ArrayList<>();
                StringBuilder errors = new StringBuilder();
                int[] starts = {28, 39, 50, 60, 69, 77};
                int[] ends =   {37, 48, 58, 67, 75, 82};
                for (int ti=0; ti<PLAN.length; ti++) {
                    Tile tile = PLAN[ti];
                    callback.onProgress(starts[ti], "正在切割並放大 " + tile.id + "（" + tileLabel(tile.id) + "）");
                    File crop = cropZoom(app, full, tile);
                    try {
                        callback.onProgress(Math.min(81, starts[ti]+2), "PP-OCRv6 Small ONNX + ML Kit 正在辨識 " + tile.id);
                        OcrPair pair = recognizePair(app, crop.getAbsolutePath());
                        if (!pair.pp.isEmpty()) parseAndMap(pair.pp, "PP", tile, entries);
                        else if (!pair.ppError.isEmpty()) errors.append(tile.id).append(" PP:").append(pair.ppError).append('\n');
                        if (!pair.ml.isEmpty()) parseAndMap(pair.ml, "ML", tile, entries);
                        else if (!pair.mlError.isEmpty()) errors.append(tile.id).append(" ML:").append(pair.mlError).append('\n');
                        if (pair.timedOut) errors.append(tile.id).append(" OCR_TIMEOUT: one or more engines exceeded 90s\n");
                        callback.onProgress(ends[ti], tile.id + " 雙 OCR 完成，保留文字座標");
                    } finally {
                        try { crop.delete(); } catch (Throwable ignored) {}
                    }
                }

                if (entries.isEmpty()) throw new Exception("放大分區後兩套 OCR 都沒有取得文字" + (errors.length()>0 ? "："+errors : ""));
                callback.onProgress(84, "正在把 H1/H2/R1/R2/R3/R4 座標重新組回採購單");
                List<Entry> merged = dedupe(entries);
                callback.onProgress(86, "正在比對 PP-OCR 與 ML Kit 的數字／文字衝突");
                callback.onSuccess(buildEvidence(merged, entries, errors.toString()));
            } catch (Throwable t) {
                callback.onFailure(safe(t));
            } finally {
                if (full != null && !full.isRecycled()) full.recycle();
            }
        }, "tft-zoom-tiled-ocr").start();
    }

    private static File cropZoom(Context context, Bitmap full, Tile t) throws Exception {
        int left = clamp(Math.round(full.getWidth()*t.x),0,full.getWidth()-1);
        int top = clamp(Math.round(full.getHeight()*t.y),0,full.getHeight()-1);
        int right = clamp(Math.round(full.getWidth()*(t.x+t.w)),left+1,full.getWidth());
        int bottom = clamp(Math.round(full.getHeight()*(t.y+t.h)),top+1,full.getHeight());
        Bitmap crop = Bitmap.createBitmap(full,left,top,right-left,bottom-top);
        int longSide = Math.max(crop.getWidth(),crop.getHeight());
        // Small is fast enough to keep a high-detail crop; cap memory instead of shrinking to a whole-page view.
        float scale = Math.min(t.zoom, 3600f / Math.max(1,longSide));
        if (scale < 1f) scale = 1f;
        Bitmap zoomed = crop;
        if (scale > 1.04f) {
            zoomed = Bitmap.createScaledBitmap(crop,
                    Math.max(1,Math.round(crop.getWidth()*scale)),
                    Math.max(1,Math.round(crop.getHeight()*scale)),true);
            crop.recycle();
        }
        File out = new File(context.getCacheDir(),"ocr_tile_"+t.id+"_"+System.nanoTime()+".jpg");
        try(FileOutputStream fos=new FileOutputStream(out)) {
            if(!zoomed.compress(Bitmap.CompressFormat.JPEG,100,fos)) throw new Exception("無法建立放大 OCR 區塊 "+t.id);
        } finally { if(!zoomed.isRecycled()) zoomed.recycle(); }
        return out;
    }

    private static final class OcrPair {
        String pp="",ml="",ppError="",mlError="";
        boolean timedOut=false;
    }

    private static OcrPair recognizePair(Context context,String path) throws InterruptedException {
        OcrPair out = new OcrPair();
        CountDownLatch latch = new CountDownLatch(2);
        PaddleOcrBridge.recognize(context,path,new PaddleOcrCallback(){
            @Override public void onSuccess(String s){out.pp=s==null?"":s;latch.countDown();}
            @Override public void onFailure(String e){out.ppError=e==null?"":e;latch.countDown();}
        });
        MlKitOcrBridge.recognize(context,path,new MlKitOcrBridge.Callback(){
            @Override public void onSuccess(String s){out.ml=s==null?"":s;latch.countDown();}
            @Override public void onFailure(String e){out.mlError=e==null?"":e;latch.countDown();}
        });
        out.timedOut = !latch.await(90, TimeUnit.SECONDS);
        if (out.timedOut) {
            if (out.pp.isEmpty() && out.ppError.isEmpty()) out.ppError="timeout";
            if (out.ml.isEmpty() && out.mlError.isEmpty()) out.mlError="timeout";
        }
        return out;
    }

    private static void parseAndMap(String raw,String engine,Tile tile,List<Entry> out) {
        for(String line:raw.split("\\n")) {
            Matcher m=LINE.matcher(line.trim());
            if(!m.matches()) continue;
            Entry e=new Entry(); e.engine=engine; e.tile=tile.id; e.zoom=tile.zoom;
            float lx=f(m.group(1)),ly=f(m.group(2)),lw=f(m.group(3)),lh=f(m.group(4));
            e.x=clampf(tile.x+lx*tile.w); e.y=clampf(tile.y+ly*tile.h);
            e.w=clampf(lw*tile.w); e.h=clampf(lh*tile.h);
            e.conf=m.group(5)==null?(engine.equals("ML")?0.55f:0.40f):f(m.group(5));
            e.text=m.group(6)==null?"":m.group(6).trim();
            if(!e.text.isEmpty() && e.cy()<0.95f) out.add(e);
        }
    }

    private static List<Entry> dedupe(List<Entry> source) {
        List<Entry> sorted=new ArrayList<>(source);
        Collections.sort(sorted,Comparator.comparingDouble(Entry::cy).thenComparingDouble(Entry::cx));
        List<Entry> out=new ArrayList<>();
        for(Entry e:sorted) {
            Entry same=null;
            String n=norm(e.text);
            for(int i=Math.max(0,out.size()-70);i<out.size();i++) {
                Entry x=out.get(i);
                if(Math.abs(x.cy()-e.cy())>0.038f) continue;
                if(Math.abs(x.cx()-e.cx())>0.055f) continue;
                if(n.equals(norm(x.text)) && !n.isEmpty()) {same=x;break;}
            }
            if(same==null) out.add(e);
            else {
                if(!same.engine.contains(e.engine)) same.engine += "+"+e.engine;
                if(!same.tile.contains(e.tile)) same.tile += "+"+e.tile;
                float oldConf=same.conf;
                same.conf=Math.max(same.conf,e.conf); same.zoom=Math.max(same.zoom,e.zoom);
                if(e.w*e.h < same.w*same.h && e.conf>=oldConf-0.08f){same.x=e.x;same.y=e.y;same.w=e.w;same.h=e.h;}
            }
        }
        return out;
    }

    private static String buildEvidence(List<Entry> merged,List<Entry> raw,String errors) {
        StringBuilder sb=new StringBuilder();
        sb.append("[ZOOM_TILED_OCR_V217]\n");
        sb.append("strategy=corrected_highres_page -> overlapping_tiles -> 2.25x-2.7x enlarge -> PP-OCRv6-Small-ONNX + ML-Kit -> map_to_global_coordinates -> merge\n");
        sb.append("tiles=H1(header-left),H2(header-right),R1/R2/R3/R4(item-table overlapping bands through y=0.95)\n");
        sb.append("rule=同位置雙 OCR 一致才提高可信度；衝突或低信心只提供證據，不准直接猜值。\n");
        int low=0;
        for(Entry e:merged) {
            sb.append("[engine=").append(e.engine).append(" tile=").append(e.tile)
                    .append(" region=").append(region(e.cx(),e.cy()))
                    .append(" gx=").append(ff(e.x)).append(" gy=").append(ff(e.y))
                    .append(" gw=").append(ff(e.w)).append(" gh=").append(ff(e.h))
                    .append(" conf=").append(ff(e.conf)).append(" zoom=").append(String.format(Locale.US,"%.1f",e.zoom))
                    .append("] ").append(e.text.replace('\n',' ')).append('\n');
            float threshold = e.engine.contains("PP") ? 0.50f : 0.48f;
            if(e.conf<threshold && low++<30 && (containsDigit(e.text)||region(e.cx(),e.cy()).startsWith("HEADER"))) {
                sb.append("LOW_CONFIDENCE @").append(ff(e.cx())).append(',').append(ff(e.cy()))
                        .append(" engine=").append(e.engine).append(" value=").append(e.text).append('\n');
            }
        }

        int conflicts=0,consensus=0;
        sb.append("[OCR_SPATIAL_CROSSCHECK]\n");
        for(Entry p:raw) {
            if(!p.engine.equals("PP")) continue;
            Entry best=null; double bestScore=999;
            for(Entry m:raw) {
                if(!m.engine.equals("ML")) continue;
                double dx=Math.abs(p.cx()-m.cx());
                double dy=Math.abs(p.cy()-m.cy());
                if(dy>0.022 || dx>0.060) continue;
                double score=dx + dy*2.8;
                if(score<bestScore){bestScore=score;best=m;}
            }
            if(best==null) continue;
            String a=norm(p.text),b=norm(best.text);
            if(a.isEmpty()||b.isEmpty()) continue;
            if(a.equals(b)) {
                if(consensus++<70) sb.append("CONSENSUS_HIGH @").append(ff(p.cx())).append(',').append(ff(p.cy())).append(" = ").append(p.text).append('\n');
            } else if((containsDigit(p.text)||containsDigit(best.text)) && conflicts++<55) {
                sb.append("CONFLICT @").append(ff(p.cx())).append(',').append(ff(p.cy()))
                        .append(" PP=").append(p.text).append(" | ML=").append(best.text)
                        .append(" -> LOCAL_IMAGE_CHECK_REQUIRED; unclear => NEEDS_CONFIRMATION\n");
            }
        }
        if(!errors.isEmpty()) sb.append("[OCR_PARTIAL_ERRORS]\n").append(errors);
        return sb.toString();
    }

    private static String tileLabel(String id){
        if("H1".equals(id))return"供應廠商／地址";
        if("H2".equals(id))return"採購單號／採購日期";
        return"品項表格";
    }
    private static String region(float x,float y){if(y<0.34f&&x<0.58f)return"HEADER_LEFT";if(y<0.34f)return"HEADER_RIGHT";if(y<0.95f)return"ITEM_TABLE";return"FOOTER";}
    private static boolean containsDigit(String s){return s!=null&&s.matches(".*\\d.*");}
    private static String norm(String s){return s==null?"":s.toUpperCase(Locale.TAIWAN).replaceAll("[\\s　,，.。:：()（）/\\-＿_]","");}
    private static float f(String s){try{return Float.parseFloat(s);}catch(Exception e){return 0f;}}
    private static String ff(float v){return String.format(Locale.US,"%.3f",v);}
    private static float clampf(float v){return Math.max(0f,Math.min(1f,v));}
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static String safe(Throwable t){if(t==null)return"未知錯誤";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
}
