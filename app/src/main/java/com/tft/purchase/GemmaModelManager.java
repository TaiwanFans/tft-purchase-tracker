package com.tft.purchase;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * Gemma 4 E4B model download manager.
 * V2.0.10 never treats a preallocated/partial DownloadManager file as a ready model.
 */
public final class GemmaModelManager {
    public static final String MODEL_FILE = "gemma-4-E4B-it.litertlm";
    public static final long EXPECTED_SIZE = 3659530240L;
    public static final String EXPECTED_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0";

    private static final String LEGACY_MODEL_FILE = "gemma-4-E2B-it.litertlm";
    private static final String PREFS = "tft_settings";
    private static final String KEY_DOWNLOAD_ID = "gemma_download_id_v209";
    private static final String KEY_VERIFIED_SHA = "gemma_e4b_verified_sha";
    private static final String KEY_VERIFY_ERROR = "gemma_e4b_verify_error";
    private static final String URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true";

    private GemmaModelManager() {}

    public static File modelFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, MODEL_FILE);
    }

    /**
     * Ready means the model has been fully downloaded AND passed the official SHA-256.
     * File length alone is deliberately insufficient because DownloadManager may preallocate.
     */
    public static boolean isReady(Context context) {
        File f = modelFile(context);
        if (!f.exists() || f.length() != EXPECTED_SIZE) return false;
        String verified = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VERIFIED_SHA, "");
        return EXPECTED_SHA256.equalsIgnoreCase(verified == null ? "" : verified);
    }

    public static long startDownload(Context context) throws Exception {
        if (isReady(context)) return -1L;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new Exception("系統下載服務不可用");

        long oldId = prefs.getLong(KEY_DOWNLOAD_ID, -1L);
        if (oldId >= 0) {
            try { dm.remove(oldId); } catch (Throwable ignored) {}
        }

        File f = modelFile(context);
        if (f.exists() && !f.delete()) throw new Exception("無法移除不完整模型，請重新啟動 APP 後再試");
        File dir = f.getParentFile();
        if (dir != null) {
            File old = new File(dir, LEGACY_MODEL_FILE);
            if (old.exists()) old.delete();
        }
        prefs.edit().remove(KEY_VERIFIED_SHA).remove(KEY_VERIFY_ERROR).apply();

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(URL));
        req.setTitle("全益採購追蹤｜Gemma 4 E4B AI 模型");
        req.setDescription("正在下載約 3.66 GB。APP 會在下載完成後驗證完整性，驗證通過才會啟用 AI。");
        req.setMimeType("application/octet-stream");
        req.setAllowedOverRoaming(false);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, MODEL_FILE);
        long id = dm.enqueue(req);
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply();
        return id;
    }

    public static Status getStatus(Context context) {
        Status out = new Status();
        File f = modelFile(context);
        out.fileBytes = f.exists() ? f.length() : 0L;
        out.verified = isReady(context);
        if (out.verified) {
            out.state = DownloadManager.STATUS_SUCCESSFUL;
            out.downloaded = EXPECTED_SIZE;
            out.total = EXPECTED_SIZE;
            return out;
        }

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        out.validationError = p.getString(KEY_VERIFY_ERROR, "");
        long id = p.getLong(KEY_DOWNLOAD_ID, -1L);
        if (id < 0) return out;
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return out;
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        if (c == null) return out;
        try {
            if (c.moveToFirst()) {
                out.state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                out.downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                out.total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int reasonIndex = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                if (reasonIndex >= 0) out.reason = c.getInt(reasonIndex);
            }
        } finally { c.close(); }

        if (out.state == DownloadManager.STATUS_SUCCESSFUL) {
            if (out.fileBytes != EXPECTED_SIZE) {
                out.validationError = "下載完成，但模型檔案大小不正確：" + out.fileBytes + " bytes；正確應為 " + EXPECTED_SIZE + " bytes";
                p.edit().putString(KEY_VERIFY_ERROR, out.validationError).remove(KEY_VERIFIED_SHA).apply();
            } else {
                out.needsValidation = true;
            }
        }
        return out;
    }

    /** Slow one-time integrity validation. Call from a background thread only. */
    public static ValidationResult validateDownloadedModel(Context context) {
        ValidationResult result = new ValidationResult();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File f = modelFile(context);
        try {
            Status s = getStatus(context);
            if (s.state != DownloadManager.STATUS_SUCCESSFUL) {
                result.message = "系統尚未回報模型下載完成";
                return result;
            }
            if (!f.exists() || f.length() != EXPECTED_SIZE) {
                result.message = "模型檔案大小錯誤，請重新下載";
                p.edit().putString(KEY_VERIFY_ERROR, result.message).remove(KEY_VERIFIED_SHA).apply();
                return result;
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8 * 1024 * 1024];
            try (FileInputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            String actual = hex.toString();
            if (!EXPECTED_SHA256.equalsIgnoreCase(actual)) {
                result.message = "模型完整性驗證失敗（SHA-256 不符）。請重新下載。";
                p.edit().putString(KEY_VERIFY_ERROR, result.message).remove(KEY_VERIFIED_SHA).apply();
                try { f.delete(); } catch (Throwable ignored) {}
                return result;
            }

            p.edit().putString(KEY_VERIFIED_SHA, EXPECTED_SHA256).remove(KEY_VERIFY_ERROR).apply();
            result.ok = true;
            result.message = "Gemma 4 E4B 完整性驗證通過";
            return result;
        } catch (Throwable t) {
            result.message = "模型驗證失敗：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            p.edit().putString(KEY_VERIFY_ERROR, result.message).remove(KEY_VERIFIED_SHA).apply();
            return result;
        }
    }

    public static class ValidationResult {
        public boolean ok = false;
        public String message = "";
    }

    public static class Status {
        public int state = 0;
        public long downloaded = 0;
        public long total = EXPECTED_SIZE;
        public long fileBytes = 0;
        public int reason = 0;
        public boolean verified = false;
        public boolean needsValidation = false;
        public String validationError = "";
        public int percent() {
            long t = total > 0 ? total : EXPECTED_SIZE;
            long d = downloaded < 0 ? 0 : downloaded;
            return (int)Math.max(0, Math.min(100, d * 100L / Math.max(1L, t)));
        }
    }
}
