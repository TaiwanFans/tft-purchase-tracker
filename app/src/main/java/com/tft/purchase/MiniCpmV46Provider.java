package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.ByteArrayOutputStream;

/** MiniCPM-V 4.6 local provider. OCR/fixed-template structure are primary; MiniCPM only verifies uncertain fields. */
public final class MiniCpmV46Provider implements AiModelProvider {
    @Override public String id() { return AiModelRegistry.MINICPM_V46; }
    @Override public String displayName() { return "MiniCPM-V 4.6 + PP-OCRv6 Small + 固定採購單 8 欄解析"; }
    @Override public boolean isReady(Context context) { return MiniCpmV46ModelManager.isReady(context); }

    @Override
    public void analyze(Context context, String imagePath, String ocrText,
                        AiProgressCallback progress, AiModelCallback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isReady(app)) throw new IllegalStateException("MiniCPM-V 4.6 模型尚未下載並驗證完成");
                notify(progress, 8, "整理固定採購單 8 欄、OCR 座標與交易角色");
                byte[] image = prepareImage(imagePath);
                notify(progress, 20, "載入 MiniCPM-V 4.6 本機模型");
                LlamaEngine engine = LlamaEngine.getInstance(app);
                engine.ensureLoaded(MiniCpmV46ModelManager.modelFile(app).getAbsolutePath(), MiniCpmV46ModelManager.mmprojFile(app).getAbsolutePath());
                notify(progress, 35, "重組格子級證據與人工確認知識庫");
                String prompt = buildPrompt(ocrText);
                notify(progress, 52, "MiniCPM 只核對固定格子中的衝突字元");
                String response = engine.infer(image, prompt, 1700);
                notify(progress, 90, "檢查固定版型結構化結果");
                callback.onSuccess(response == null ? "" : response);
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                callback.onFailure("MiniCPM-V 4.6 分析失敗：" + msg);
            }
        }, "tft-minicpm-v46").start();
    }

    private static String buildPrompt(String ocrText) {
        String ocr = compactEvidence(ocrText);
        return "你是台灣製造業固定版型採購單的『格子核對器』。這個 APP 只會收到同一種採購單版型。你不是主 OCR，也不是自由生成模型。\n" +
                "最重要：FIXED_PURCHASE_ORDER_V220 / TABLE_GRID_V220 是主要事實來源。MiniCPM 不能推翻格線位置，只能核對少數 OCR 看不清楚的字。DO NOT GUESS.\n\n" +
                "【固定版型公式】\n" +
                "整張表固定 8 欄：0項次 | 1品名規格明細 | 2數量 | 3單位 | 4單價 | 5小計 | 6交貨日期 | 7備註。\n" +
                "同一橫列才是一個品項。任何數字都不得跨 grid_row，也不得跨 column。\n" +
                "FIXED_ROW 若存在，line_no/quantity/unit/unit_price/subtotal/delivery_date/note 必須優先照 FIXED_ROW；除非該欄明確為空或標記衝突，否則不要重寫。\n" +
                "供應廠商只取左上『供應廠商：』後方公司；我方頁首公司名稱永遠不是 vendor。\n" +
                "採購單號只取右上『採購單號：』後方 12 位數；採購日期只取右上『採購日期：』。\n\n" +
                "【證據優先順序】\n" +
                "1. FIXED_PURCHASE_ORDER_V220 matched=true 的 FIXED_HEADER / FIXED_ROW。\n" +
                "2. TABLE_GRID_V220 reliable=true 的 8 欄 cell 綁定。\n" +
                "3. 同位置 PP-OCR 與 ML Kit 一致。\n" +
                "4. 高解析局部圖只用於第 1~3 項真的衝突時。\n" +
                "5. LOCAL_RECOGNITION_KNOWLEDGE 只能校正已出現在本張的候選字，不能創造資料。\n\n" +
                "【vendor 定義】\n" +
                "vendor = 左上『供應廠商：』後方公司。頁首『全益畜牧器具有限公司／全益畜牧器具公司／台灣電扇科技有限公司／台灣電扇／全益台灣電扇』全部是我方，永遠不能輸出成 vendor。\n\n" +
                "【OCR / 固定版型 / 表格格子 / 知識庫證據】\n" + ocr + "\n【證據結束】\n\n" +
                "只輸出一個 JSON object，不要 Markdown，不要解釋：\n" +
                "{\"vendor\":\"\",\"location\":\"\",\"order_no\":\"\",\"purchase_date\":\"\",\"items\":[{\"line_no\":\"001\",\"description\":\"\",\"specification\":\"\",\"quantity\":\"\",\"unit\":\"\",\"unit_price\":\"\",\"subtotal\":\"\",\"delivery_date\":\"\",\"note\":\"\",\"needs_confirmation\":[]}],\"needs_confirmation\":[]}\n\n" +
                "強制規則：\n" +
                "1. FIXED_HEADER 有 vendor/order_no/purchase_date 時直接採用，不要從頁面其他位置另猜。\n" +
                "2. location 必須跟 vendor 同一列/下一列的廠商地址；不能拿右上我方地址。\n" +
                "3. order_no 必須是採購單號後方完整 12 位數，例如 202604280003；禁止拿日期、電話、項次拼接。\n" +
                "4. purchase_date 是採購日期；交貨日期只能來自第 6 欄。\n" +
                "5. TABLE_GRID_V220 / FIXED_ROW 中每個 item 只能使用同一 row。\n" +
                "6. quantity 只能來自第 2 欄；品名規格中的 220V、60HZ、IP55、6英吋、90mm、16芯全部不是數量。\n" +
                "7. unit 只能來自第 3 欄；unit_price 只能第 4 欄；subtotal 只能第 5 欄；delivery_date 只能第 6 欄；note 只能第 7 欄。\n" +
                "8. 『以下空白』及其後方不是商品；『前單取消／以此單為準／數量修改／已備好電線／請報價』若單價與小計都是 0/空，也不是採購商品。\n" +
                "9. line_no 保留來源位數：001 就是 001，0001 就是 0001。\n" +
                "10. quantity × unit_price 與 subtotal 不一致時，不得用 subtotal÷price 反推新數量；只標該欄 needs_confirmation。\n" +
                "11. 文件某格沒有值就留空，不得用其他列或頁尾總計補值。\n" +
                "12. needs_confirmation 必須欄位級，不能輸出整張都要確認。\n" +
                "13. 頁尾『本頁小計、稅額、總計、審核、經辦人、廠商確認、FAXED、條款』全部不是 item。\n" +
                "14. 若 FIXED_ROW 與影像看起來不同，但你無法清楚判讀，保留 FIXED_ROW 並只標那一格 needs_confirmation，不得自行創造第三個值。";
    }

    private static String compactEvidence(String input) {
        if (input == null || input.trim().isEmpty()) return "[NO_OCR_EVIDENCE]";
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\\n")) {
            if (line.contains("region=FOOTER")) continue;
            out.append(line).append('\n');
            if (out.length() >= 30000) break;
        }
        if (out.length() > 30000) out.setLength(30000);
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
