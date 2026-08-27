package com.tft.purchase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.ByteArrayOutputStream;

/**
 * MiniCPM-V 4.6 local vision provider.
 * V2.0.14: PP-OCRv6 Medium is the main character reader; two Google ML Kit passes provide
 * independent OCR evidence. MiniCPM focuses on table/field understanding and conflict resolution.
 */
public final class MiniCpmV46Provider implements AiModelProvider {
    @Override public String id() { return AiModelRegistry.MINICPM_V46; }
    @Override public String displayName() { return "MiniCPM-V 4.6 + PP-OCRv6 Medium + ML Kit"; }
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
                engine.ensureLoaded(
                        MiniCpmV46ModelManager.modelFile(app).getAbsolutePath(),
                        MiniCpmV46ModelManager.mmprojFile(app).getAbsolutePath());

                notify(progress, 35, "整理 PP-OCRv6 Medium 與 ML Kit 交叉證據");
                String prompt = buildPrompt(ocrText);
                notify(progress, 48, "MiniCPM 正在核對表格欄位與 OCR 衝突");
                String response = engine.infer(image, prompt, 950);
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
        return "你是台灣製造業採購單的欄位整理器，不是自由生成聊天模型。\n" +
                "你收到：① 已校正的採購單圖片；② PP-OCRv6 Medium 文字框、座標、信心值；③ Google ML Kit 彩色影像 OCR；④ Google ML Kit 強化影像 OCR。\n" +
                "PP-OCRv6 Medium 是主要字元證據；兩次 ML Kit 是交叉核對；圖片只用來確認版面位置及處理 OCR 衝突。\n" +
                "任何非空值都必須能追溯到圖片或 OCR 證據。看不清楚就留空並 needs_confirmation，禁止猜測。\n\n" +
                "【OCR 與版面證據】\n" + ocr + "\n【證據結束】\n\n" +
                "region 說明：HEADER_LEFT=左上供應商區；HEADER_RIGHT=右上採購單號/採購日期；ITEM_TABLE=採購品項表格；FOOTER=頁尾條款。\n" +
                "只輸出一個 JSON object，不要 Markdown，不要說明：\n" +
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
                "1. vendor 只從 HEADER_LEFT 的供應廠商欄取值；不要把全益台灣電扇或我方公司資料當供應商。\n" +
                "2. order_no 與 purchase_date 優先從 HEADER_RIGHT 取值；delivery_date 只能取 ITEM_TABLE 同一品項列的交貨日期。\n" +
                "3. 民國日期轉西元，例如 115/08/27 = 2026-08-27；輸出日期一律 YYYY-MM-DD。\n" +
                "4. items 只允許 ITEM_TABLE 的正式資料列。『以下空白』、本頁小計、稅額、總計、FAXED、印章、頁尾條款全部忽略。\n" +
                "5. description=品名；specification=同列規格；quantity 必須逐位保留完整數字，例如 200 不得縮成 2 或 20；unit 只取單位欄。\n" +
                "6. 若 PP-OCR 與至少一個 ML Kit 對同一位置/數字一致，可提高採用信心；若三路衝突，重新看圖片。仍不能確定就留空並加入 needs_confirmation。\n" +
                "7. OCR 的 x/y/region 是重要證據。不可把不同列或不同欄的數字拼在一起。\n" +
                "8. 不准依常識修正成常見公司名、商品名或數量。不准自行補齊圖片/OCR 沒有的字。\n" +
                "9. 單價與小計不是必要欄位，可留空；不要為了讓算式成立而修改 quantity。\n" +
                "10. 如果確定存在項次但某格模糊，可以保留 line_no，其餘不確定欄位留空並標記。\n" +
                "11. 輸出前逐欄確認每個非空值都能在 OCR 或圖片中找到直接證據。";
    }

    private static String compactEvidence(String input) {
        if (input == null || input.trim().isEmpty()) return "[NO_OCR_EVIDENCE]";
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\\n")) {
            if (line.contains("region=FOOTER")) continue;
            out.append(line).append('\n');
            if (out.length() >= 9000) break;
        }
        if (out.length() > 9000) out.setLength(9000);
        return out.toString();
    }

    private static byte[] prepareImage(String path) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("無法讀取採購單照片");

        int sample = 1;
        while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 3800) sample *= 2;
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
        if (bm.getWidth() > bm.getHeight() * 1.08f) {
            Matrix m = new Matrix(); m.postRotate(90);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (rotated != bm) bm.recycle();
            bm = rotated;
        }

        // MiniCPM does not need the same large image as OCR because OCR already provides exact glyph evidence.
        int maxSide = Math.max(bm.getWidth(), bm.getHeight());
        if (maxSide > 2500) {
            float scale = 2500f / maxSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bm,
                    Math.max(1, Math.round(bm.getWidth() * scale)),
                    Math.max(1, Math.round(bm.getHeight() * scale)), true);
            if (scaled != bm) bm.recycle();
            bm = scaled;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bm.compress(Bitmap.CompressFormat.JPEG, 96, out)) {
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
