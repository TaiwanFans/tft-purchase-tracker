package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.ByteArrayOutputStream;

/** MiniCPM-V 4.6 local provider. OCR/table structure are primary; MiniCPM only verifies uncertain fields. */
public final class MiniCpmV46Provider implements AiModelProvider {
    @Override public String id() { return AiModelRegistry.MINICPM_V46; }
    @Override public String displayName() { return "MiniCPM-V 4.6 + PP-OCRv6 Small + OpenCV 表格綁定"; }
    @Override public boolean isReady(Context context) { return MiniCpmV46ModelManager.isReady(context); }

    @Override
    public void analyze(Context context, String imagePath, String ocrText,
                        AiProgressCallback progress, AiModelCallback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isReady(app)) throw new IllegalStateException("MiniCPM-V 4.6 模型尚未下載並驗證完成");
                notify(progress, 8, "整理 OCR 座標、交易角色與表格 Cell");
                byte[] image = prepareImage(imagePath);
                notify(progress, 20, "載入 MiniCPM-V 4.6 本機模型");
                LlamaEngine engine = LlamaEngine.getInstance(app);
                engine.ensureLoaded(MiniCpmV46ModelManager.modelFile(app).getAbsolutePath(), MiniCpmV46ModelManager.mmprojFile(app).getAbsolutePath());
                notify(progress, 35, "重組欄位級證據與人工確認知識庫");
                String prompt = buildPrompt(ocrText);
                notify(progress, 52, "MiniCPM 只核對衝突欄位與漏列");
                String response = engine.infer(image, prompt, 1700);
                notify(progress, 90, "檢查欄位級結構化結果");
                callback.onSuccess(response == null ? "" : response);
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                callback.onFailure("MiniCPM-V 4.6 分析失敗：" + msg);
            }
        }, "tft-minicpm-v46").start();
    }

    private static String buildPrompt(String ocrText) {
        String ocr = compactEvidence(ocrText);
        return "你是台灣製造業採購單／報價單的『欄位核對器』。你不是主 OCR，也不是自由生成模型。PP-OCRv6 Small、ML Kit、OpenCV 表格 Cell 與座標是主要證據。\n" +
                "核心原則：有把握的欄位照填；只有真的不確定的那一格才加入 needs_confirmation。禁止因一個衝突把整張文件全部清空。DO NOT GUESS.\n\n" +
                "【結構證據優先順序】\n" +
                "1. TABLE_GRID_V219 reliable=true 時，ROW/grid_row/cell 綁定是最高優先。不同 grid_row 的數量、單位、單價、小計絕對不能互相借用。\n" +
                "2. HEADER_ROLE_V219 用來判斷開立方、TO/收件方、單據號碼、文件日期、有效日期。\n" +
                "3. 同位置 PP-OCR 與 ML Kit 一致為高可信；CONFLICT/LOW_CONFIDENCE 才需要看高解析局部圖。\n" +
                "4. LOCAL_RECOGNITION_KNOWLEDGE 只能校正已出現在本張圖/OCR 的候選字，不能創造本張不存在的廠商或品項。\n\n" +
                "【vendor 的真正定義】\n" +
                "vendor = 本文件中『與我方交易的對方公司/counterparty』。不是『頁面最大公司名稱』。\n" +
                "若文件開立方是我方全益，而 TO/收件方是另一家公司，vendor 必須是 TO/收件方；若 TO/收件方是我方，vendor 才取另一方。\n" +
                "我方名稱包含：全益畜牧器具有限公司、全益畜牧器具公司、全益、台灣電扇科技有限公司、台灣電扇、全益台灣電扇。它們永遠不能輸出成 vendor。\n\n" +
                "【OCR / 座標 / HEADER_ROLE / TABLE_GRID / 知識庫證據】\n" + ocr + "\n【證據結束】\n\n" +
                "只輸出一個 JSON object，不要 Markdown，不要解釋：\n" +
                "{\"vendor\":\"\",\"location\":\"\",\"order_no\":\"\",\"purchase_date\":\"\",\"items\":[{\"line_no\":\"0001\",\"description\":\"\",\"specification\":\"\",\"quantity\":\"\",\"unit\":\"\",\"unit_price\":\"\",\"subtotal\":\"\",\"delivery_date\":\"\",\"note\":\"\",\"needs_confirmation\":[]}],\"needs_confirmation\":[]}\n\n" +
                "強制規則：\n" +
                "1. vendor 先做交易角色判斷；看到我方公司名稱只代表 issuer/recipient 之一，不能直接當 vendor。\n" +
                "2. location 必須跟 vendor/counterparty 同一角色；不能把我方地址配給對方公司。\n" +
                "3. order_no 只能取『單據號碼／採購單號／報價單號』錨點附近的完整號碼。禁止把日期 20260813 與品項列號 0001 拼成 202608130001。\n" +
                "4. purchase_date 代表文件日期／採購日期／報價日期。『有效日期』只能視為 valid-until，不能當 purchase_date 或 delivery_date。\n" +
                "5. 民國年 + 1911，例如 115/08/27 = 2026-08-27；日期輸出 YYYY-MM-DD。\n" +
                "6. TABLE_GRID_V219 reliable=true 時，每個 item 只能從同一 ROW grid_row 的 cell 取值。description/specification、quantity、unit、unit_price、subtotal 禁止跨 row 拼接。\n" +
                "7. 若 column_map 指出 quantity，只有該 quantity cell 的數字才能當數量。品名/規格 cell 裡的『6英吋』『220V』『IP55』都不是數量。\n" +
                "8. line_no 必須保留來源位數；原圖 0001 就輸出 0001，不准改成 001。\n" +
                "9. 正式商品列通常同列具有品名/規格以及數量/單位/價格之一。『三相與單相同價』『原物料持續調漲』『訂貨交貨及付款方式』『以上報價為』『以下空白』、條款、簽章不是商品。\n" +
                "10. 文件沒有交貨日期時 delivery_date 直接留空，這不代表整列需要確認；不要拿有效日期或文件日期代替。\n" +
                "11. quantity × unit_price 與 subtotal 不一致時，禁止用 subtotal÷price 反推一個新 quantity。只能重新看同列三個 cell；仍不確定才只標該欄位 needs_confirmation。\n" +
                "12. needs_confirmation 必須是欄位級，例如 vendor、items[3].quantity。不要輸出『全部需要確認』『整張需要確認』這種泛化結果。\n" +
                "13. 即使某個欄位衝突，只要其他欄位有清楚 OCR/Cell/影像證據，就必須保留那些正確欄位，不得一起清空。\n" +
                "14. 高解析圖只用來核對 OCR/Cell 衝突與漏列。看不清楚就留空；禁止憑歷史品項或常識補字。";
    }

    private static String compactEvidence(String input) {
        if (input == null || input.trim().isEmpty()) return "[NO_OCR_EVIDENCE]";
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\\n")) {
            if (line.contains("region=FOOTER")) continue;
            out.append(line).append('\n');
            if (out.length() >= 26000) break;
        }
        if (out.length() > 26000) out.setLength(26000);
        return out.toString();
    }

    private static byte[] prepareImage(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取 AI 高解析核對圖片");
        int sample = 1;
        while (bounds.outWidth / sample > 3200 || bounds.outHeight / sample > 4200) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(path, opts);
        if (bm == null) throw new Exception("無法解碼 AI 高解析核對圖片");
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
        if(!bm.compress(Bitmap.CompressFormat.JPEG,97,out)){bm.recycle();throw new Exception("AI 高解析核對影像轉換失敗");}
        bm.recycle(); return out.toByteArray();
    }

    private static void notify(AiProgressCallback cb,int percent,String stage){if(cb!=null)cb.onProgress(percent,stage);}
}
