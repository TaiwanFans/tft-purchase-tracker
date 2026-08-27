package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.ByteArrayOutputStream;

/** MiniCPM-V 4.6 local vision provider. OCR text is supplied as evidence, never as auto-filled fields. */
public final class MiniCpmV46Provider implements AiModelProvider {
    @Override public String id() { return AiModelRegistry.MINICPM_V46; }
    @Override public String displayName() { return "MiniCPM-V 4.6 + Google ML Kit OCR"; }
    @Override public boolean isReady(Context context) { return MiniCpmV46ModelManager.isReady(context); }

    @Override
    public void analyze(Context context, String imagePath, String ocrText,
                        AiProgressCallback progress, AiModelCallback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isReady(app)) throw new IllegalStateException("MiniCPM-V 4.6 模型尚未下載並驗證完成");
                notify(progress, 8, "整理照片方向與解析度");
                byte[] image = prepareImage(imagePath);

                notify(progress, 20, "載入 MiniCPM-V 4.6 本機模型");
                LlamaEngine engine = LlamaEngine.getInstance(app);
                engine.ensureLoaded(
                        MiniCpmV46ModelManager.modelFile(app).getAbsolutePath(),
                        MiniCpmV46ModelManager.mmprojFile(app).getAbsolutePath());

                notify(progress, 35, "把 ML Kit OCR 文字交給 MiniCPM 交叉理解");
                String prompt = buildPrompt(ocrText);
                notify(progress, 48, "MiniCPM 正在看採購單圖片並整理欄位");
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
        String ocr = ocrText == null ? "" : ocrText.trim();
        if (ocr.length() > 4500) ocr = ocr.substring(0, 4500);
        return "你是台灣公司採購單的資料擷取器。你同時收到原始採購單圖片，以及 Google ML Kit OCR 文字。\n" +
                "圖片是主要證據；OCR 只是輔助，OCR 可能錯字、漏字或欄位順序錯。禁止根據常識補不存在的資料。\n\n" +
                "【ML KIT OCR 文字】\n" + ocr + "\n【OCR 結束】\n\n" +
                "只輸出一個 JSON object，不要 Markdown，不要解釋：\n" +
                "{\n" +
                "  \"vendor\":\"\",\n" +
                "  \"location\":\"\",\n" +
                "  \"order_no\":\"\",\n" +
                "  \"purchase_date\":\"\",\n" +
                "  \"items\":[\n" +
                "    {\"line_no\":\"001\",\"description\":\"\",\"specification\":\"\",\"quantity\":\"\",\"unit\":\"\",\"unit_price\":\"\",\"subtotal\":\"\",\"delivery_date\":\"\",\"note\":\"\",\"needs_confirmation\":[]}\n" +
                "  ],\n" +
                "  \"needs_confirmation\":[]\n" +
                "}\n\n" +
                "強制規則：\n" +
                "1. vendor 只取供應廠商；location 只取廠商地址/地區；不要把全益台灣電扇當成供應商。\n" +
                "2. purchase_date 只取採購日期；delivery_date 只取該品項同一列交貨日期。\n" +
                "3. 民國日期必須轉西元，例如 115/08/27 = 2026-08-27。所有日期輸出 YYYY-MM-DD。\n" +
                "4. items 只允許真正的採購表格資料列。『以下空白』、本頁小計、稅額、總計、FAXED、印章、頁尾條款都不是品項。\n" +
                "5. description 是品名；specification 是同列規格。quantity 必須逐位確認完整數字；unit 只取單位欄。\n" +
                "6. 圖片與 OCR 衝突時，重新看圖片；仍無法確認就把該欄輸出空字串，並把欄位名稱加入 needs_confirmation。\n" +
                "7. 不得把模糊字改成常見公司名/商品名；不得自行計算或補數量、日期、品名、規格。\n" +
                "8. 如果整列看不清楚但確定有該項次，可以保留 line_no，其餘不確定欄位留空並加入 needs_confirmation。\n" +
                "9. 單價與小計不是本 APP 的必要辨識欄位，看不清楚可直接留空，絕對不要猜。\n" +
                "10. 請先在內部核對圖片與 OCR，再只輸出 JSON。";
    }

    private static byte[] prepareImage(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取採購單照片");

        int sample = 1;
        while (bounds.outWidth / sample > 2600 || bounds.outHeight / sample > 3400) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(path, opts);
        if (bm == null) throw new Exception("無法解碼採購單照片");

        int degrees = 0;
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Throwable ignored) {}
        if (degrees != 0) {
            Matrix m = new Matrix(); m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (rotated != bm) bm.recycle();
            bm = rotated;
        }
        if (bm.getWidth() > bm.getHeight()) {
            Matrix m = new Matrix(); m.postRotate(90);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (rotated != bm) bm.recycle();
            bm = rotated;
        }

        int maxSide = Math.max(bm.getWidth(), bm.getHeight());
        if (maxSide > 2200) {
            float scale = 2200f / maxSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bm,
                    Math.max(1, Math.round(bm.getWidth() * scale)),
                    Math.max(1, Math.round(bm.getHeight() * scale)), true);
            if (scaled != bm) bm.recycle();
            bm = scaled;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bm.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
            bm.recycle();
            throw new Exception("採購單影像轉換失敗");
        }
        bm.recycle();
        return out.toByteArray();
    }

    private static void notify(AiProgressCallback cb, int percent, String stage) {
        if (cb != null) cb.onProgress(percent, stage);
    }
}
