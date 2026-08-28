package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.ByteArrayOutputStream;

/** MiniCPM-V 4.6 local provider. OCR is primary; MiniCPM resolves structure/conflicts only. */
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
                notify(progress, 8, "整理 OCR 座標與局部衝突圖片");
                byte[] image = prepareImage(imagePath);
                notify(progress, 20, "載入 MiniCPM-V 4.6 本機模型");
                LlamaEngine engine = LlamaEngine.getInstance(app);
                engine.ensureLoaded(MiniCpmV46ModelManager.modelFile(app).getAbsolutePath(), MiniCpmV46ModelManager.mmprojFile(app).getAbsolutePath());
                notify(progress, 35, "重組分區 OCR、全域座標與廠商知識庫");
                String prompt = buildPrompt(ocrText);
                notify(progress, 52, "MiniCPM 只核對衝突／低信心欄位與表格歸屬");
                String response = engine.infer(image, prompt, 1500);
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
        return "你是台灣製造業採購單的『證據式欄位整理器』，不是自由生成模型。OCR 是主要字元來源；你只負責版面歸屬、欄位結構與衝突核對。\n" +
                "DO NOT GUESS. DO NOT INFER MISSING VALUES FROM EXPERIENCE. 不確定就輸出空字串並加入 needs_confirmation。\n" +
                "你收到的圖片通常不是整張 A4，而是 CONFLICT / LOW_CONFIDENCE 座標附近的高解析局部核對圖 C1~C6；沒有衝突時圖片可能只是中性佔位圖，這時完全依 OCR 座標證據整理。\n" +
                "OCR 已把文件切成 H1=左上供應商/地址、H2=右上單號/採購日期、R1/R2/R3=重疊品項表格帶，每段都有 gx/gy/gw/gh 全域座標。\n" +
                "PP-OCRv6 Medium 與 ML Kit 同位置一致 => 高可信；CONFLICT / LOW_CONFIDENCE => 只能看局部圖片再決定，看不清楚必須留空。\n" +
                "LOCAL_RECOGNITION_KNOWLEDGE 是人工確認過的本機字典。OWN_COMPANY_ALIASES 絕對不能成為 vendor；KNOWN_VENDORS/歷史品項只能做文字正規化與候選提示，不能創造本次採購內容。\n\n" +
                "【OCR / 座標 / 衝突 / 知識庫證據】\n" + ocr + "\n【證據結束】\n\n" +
                "只輸出一個 JSON object，不要 Markdown，不要解釋：\n" +
                "{\"vendor\":\"\",\"location\":\"\",\"order_no\":\"\",\"purchase_date\":\"\",\"items\":[{\"line_no\":\"001\",\"description\":\"\",\"specification\":\"\",\"quantity\":\"\",\"unit\":\"\",\"unit_price\":\"\",\"subtotal\":\"\",\"delivery_date\":\"\",\"note\":\"\",\"needs_confirmation\":[]}],\"needs_confirmation\":[]}\n\n" +
                "強制規則：\n" +
                "1. vendor 只從供應廠商區取值；全益畜牧器具公司、全益、台灣電扇科技有限公司、台灣電扇、全益台灣電扇以及 OWN_COMPANY_ALIASES 永遠不是供應廠商。\n" +
                "2. H1 同時看到我方名稱與另一家公司，只能把有 OCR/圖片證據的另一家公司列為 vendor；仍不確定就 vendor=\"\" 並 needs_confirmation。\n" +
                "3. order_no/purchase_date 主要看 H2。delivery_date 只能取 R1/R2/R3 同一品項列，禁止拿表頭日期當交貨日。\n" +
                "4. 民國年 + 1911，例如 115/08/27 = 2026-08-27；輸出 YYYY-MM-DD。\n" +
                "5. items 必須是正式表格列。以下空白、本頁小計、注意事項、出貨說明、回傳說明、FAXED、公司機密、簽章、頁尾條款全部忽略。\n" +
                "6. quantity 必須逐位保留。200 不得縮成 20 或 2。PP/ML 衝突且局部圖仍不清楚 => quantity=\"\" + needs_confirmation，禁止用常識補。\n" +
                "7. description=品名；specification=同列規格，兩欄分開輸出；unit 只取單位欄，禁止跨列拼接。\n" +
                "8. 如果 quantity × unit_price 與 subtotal 明顯不一致，不准自行修成你覺得合理的數字；保留有證據欄位，將可疑欄位留空並 needs_confirmation。\n" +
                "9. 採購單號前 8 碼若看起來是日期且與 purchase_date 不一致，要 needs_confirmation，不得硬選。\n" +
                "10. 已知廠商地址只有在本張 OCR/圖片確實匹配該廠商時才能補；常購品項永遠只能校正文字符合度，不能新增本張未出現品項。\n" +
                "11. 每個非空值都必須可追溯到 OCR/座標/局部圖片或唯一匹配的人工確認廠商資料。\n" +
                "12. DO NOT GUESS：留空比猜錯好。";
    }

    private static String compactEvidence(String input) {
        if (input == null || input.trim().isEmpty()) return "[NO_OCR_EVIDENCE]";
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\\n")) {
            if (line.contains("region=FOOTER")) continue;
            out.append(line).append('\n');
            if (out.length() >= 18000) break;
        }
        if (out.length() > 18000) out.setLength(18000);
        return out.toString();
    }

    private static byte[] prepareImage(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取 AI 局部核對圖片");
        int sample = 1;
        while (bounds.outWidth / sample > 3200 || bounds.outHeight / sample > 4200) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(path, opts);
        if (bm == null) throw new Exception("無法解碼 AI 局部核對圖片");
        int degrees = 0;
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Throwable ignored) {}
        if (degrees != 0) { Matrix m=new Matrix();m.postRotate(degrees);Bitmap r=Bitmap.createBitmap(bm,0,0,bm.getWidth(),bm.getHeight(),m,true);if(r!=bm)bm.recycle();bm=r; }
        int maxSide=Math.max(bm.getWidth(),bm.getHeight());
        if(maxSide>3000){float scale=3000f/maxSide;Bitmap s=Bitmap.createScaledBitmap(bm,Math.max(1,Math.round(bm.getWidth()*scale)),Math.max(1,Math.round(bm.getHeight()*scale)),true);if(s!=bm)bm.recycle();bm=s;}
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!bm.compress(Bitmap.CompressFormat.JPEG,97,out)){bm.recycle();throw new Exception("AI 局部核對影像轉換失敗");}
        bm.recycle(); return out.toByteArray();
    }

    private static void notify(AiProgressCallback cb,int percent,String stage){if(cb!=null)cb.onProgress(percent,stage);}
}
