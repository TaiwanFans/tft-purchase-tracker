package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.ByteArrayOutputStream;

/** MiniCPM-V 4.6 local vision provider. OCR is primary; MiniCPM resolves layout/conflicts. */
public final class MiniCpmV46Provider implements AiModelProvider {
    @Override public String id() { return AiModelRegistry.MINICPM_V46; }
    @Override public String displayName() { return "MiniCPM-V 4.6 + 分區 PP-OCRv6 Medium + ML Kit"; }
    @Override public boolean isReady(Context context) { return MiniCpmV46ModelManager.isReady(context); }

    @Override
    public void analyze(Context context, String imagePath, String ocrText,
                        AiProgressCallback progress, AiModelCallback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isReady(app)) throw new IllegalStateException("MiniCPM-V 4.6 模型尚未下載並驗證完成");
                notify(progress, 8, "整理已校正的採購單影像");
                byte[] image = prepareImage(imagePath);
                notify(progress, 20, "載入 MiniCPM-V 4.6 本機模型");
                LlamaEngine engine = LlamaEngine.getInstance(app);
                engine.ensureLoaded(MiniCpmV46ModelManager.modelFile(app).getAbsolutePath(), MiniCpmV46ModelManager.mmprojFile(app).getAbsolutePath());
                notify(progress, 35, "重組放大分區 OCR 與廠商字典");
                String prompt = buildPrompt(ocrText);
                notify(progress, 48, "MiniCPM 正在核對分區、表格與 OCR 衝突");
                String response = engine.infer(image, prompt, 1200);
                notify(progress, 90, "檢查 AI 結構化結果");
                callback.onSuccess(response == null ? "" : response);
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                callback.onFailure("MiniCPM-V 4.6 分析失敗：" + msg);
            }
        }, "tft-minicpm-v46").start();
    }

    private static String buildPrompt(String ocrText) {
        String ocr = compactEvidence(ocrText);
        return "你是台灣製造業採購單的欄位整理器，不是自由生成模型。字元辨識以 OCR 證據為主，你負責版面理解與衝突判斷。\n" +
                "本版 OCR 已先把 A4 文件切成編號區塊並放大：H1=左上供應商/地址、H2=右上單號/採購日期、R1/R2/R3=重疊的品項表格帶。\n" +
                "每段文字都有 gx/gy/gw/gh，這是重新映射回完整採購單的全域座標；相同文字在重疊區已經合併。\n" +
                "PP-OCRv6 Medium 與 ML Kit 若同位置一致，可信度高；若標記 CONFLICT，必須看圖片，仍看不清楚就留空並 needs_confirmation。\n" +
                "LOCAL_RECOGNITION_KNOWLEDGE 是使用者本機維護的字典。OWN_COMPANY_ALIASES 絕對不能成為 vendor；KNOWN_VENDORS 只能在圖片/OCR真的出現名稱或關鍵字時用來正規化。\n\n" +
                "【分區 OCR / 字典證據】\n" + ocr + "\n【證據結束】\n\n" +
                "只輸出一個 JSON object，不要 Markdown：\n" +
                "{\"vendor\":\"\",\"location\":\"\",\"order_no\":\"\",\"purchase_date\":\"\",\"items\":[{\"line_no\":\"001\",\"description\":\"\",\"specification\":\"\",\"quantity\":\"\",\"unit\":\"\",\"unit_price\":\"\",\"subtotal\":\"\",\"delivery_date\":\"\",\"note\":\"\",\"needs_confirmation\":[]}],\"needs_confirmation\":[]}\n\n" +
                "強制規則：\n" +
                "1. vendor 只從供應廠商區取值；全益畜牧器具公司、全益台灣電扇及 OWN_COMPANY_ALIASES 是我方，永遠不是供應廠商。\n" +
                "2. 如果 H1 同時看到我方名稱與另一家公司，供應廠商應選另一家公司；仍不確定就留空。\n" +
                "3. order_no/purchase_date 主要看 H2。delivery_date 只能取 R1/R2/R3 同一品項列。\n" +
                "4. 民國日期轉西元，例如 115/08/27 = 2026-08-27；輸出 YYYY-MM-DD。\n" +
                "5. items 只允許正式表格列。以下空白、本頁小計、稅額、總計、FAXED、印章、頁尾條款全部忽略。\n" +
                "6. quantity 必須逐位保留，例如 200 不得變 2 或 20。若 PP 與 ML 衝突，圖片也不清楚就留空。\n" +
                "7. description=品名，specification=同列規格；unit 只取單位欄。不要把跨列文字拼錯。\n" +
                "8. 已知廠商常購品項只做比對提示，不得因為歷史上常買就捏造這張單沒有的品項。\n" +
                "9. 每個非空值必須可追溯到 OCR/圖片，或在確定匹配已知廠商後使用其固定地址。\n" +
                "10. 不確定比猜錯好：留空並 needs_confirmation。";
    }

    private static String compactEvidence(String input) {
        if (input == null || input.trim().isEmpty()) return "[NO_OCR_EVIDENCE]";
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\\n")) {
            if (line.contains("region=FOOTER")) continue;
            out.append(line).append('\n');
            if (out.length() >= 15000) break;
        }
        if (out.length() > 15000) out.setLength(15000);
        return out.toString();
    }

    private static byte[] prepareImage(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取採購單照片");
        int sample = 1;
        while (bounds.outWidth / sample > 3200 || bounds.outHeight / sample > 4200) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(path, opts);
        if (bm == null) throw new Exception("無法解碼採購單照片");
        int degrees = 0;
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Throwable ignored) {}
        if (degrees != 0) { Matrix m=new Matrix();m.postRotate(degrees);Bitmap r=Bitmap.createBitmap(bm,0,0,bm.getWidth(),bm.getHeight(),m,true);if(r!=bm)bm.recycle();bm=r; }
        if (bm.getWidth() > bm.getHeight()*1.08f) { Matrix m=new Matrix();m.postRotate(90);Bitmap r=Bitmap.createBitmap(bm,0,0,bm.getWidth(),bm.getHeight(),m,true);if(r!=bm)bm.recycle();bm=r; }
        int maxSide=Math.max(bm.getWidth(),bm.getHeight());
        if(maxSide>2800){float scale=2800f/maxSide;Bitmap s=Bitmap.createScaledBitmap(bm,Math.max(1,Math.round(bm.getWidth()*scale)),Math.max(1,Math.round(bm.getHeight()*scale)),true);if(s!=bm)bm.recycle();bm=s;}
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!bm.compress(Bitmap.CompressFormat.JPEG,96,out)){bm.recycle();throw new Exception("採購單影像轉換失敗");}
        bm.recycle(); return out.toByteArray();
    }

    private static void notify(AiProgressCallback cb,int percent,String stage){if(cb!=null)cb.onProgress(percent,stage);}
}
