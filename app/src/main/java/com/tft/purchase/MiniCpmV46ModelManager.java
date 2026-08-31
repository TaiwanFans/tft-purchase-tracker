package com.tft.purchase;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import com.example.minicpm_v_demo.LlamaEngine;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Downloads, hashes and load-tests the two official MiniCPM-V 4.6 GGUF files. */
public final class MiniCpmV46ModelManager {
    public static final String MODEL_FILE = "MiniCPM-V-4_6-Q4_K_M.gguf";
    public static final String MMPROJ_FILE = "mmproj-model-f16.gguf";
    public static final long EXPECTED_TOTAL = 1_640_000_000L;

    private static final String MODEL_URL =
            "https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/MiniCPM-V-4_6-Q4_K_M.gguf?download=true";
    private static final String MMPROJ_URL =
            "https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/mmproj-model-f16.gguf?download=true";

    private static final String MODEL_MD5 = "fd778481dd56b6036dd8f9cf7c1519cf";
    private static final String MMPROJ_MD5 = "54aea6e04d752f47309a48f12795a1a3";

    private static final String PREFS = "tft_minicpm_v46";
    private static final String KEY_MODEL_DL = "model_download_id";
    private static final String KEY_MMPROJ_DL = "mmproj_download_id";
    private static final String KEY_VERIFIED = "verified";
    private static final String KEY_ERROR = "validation_error";

    private MiniCpmV46ModelManager() {}

    public static File modelDir(Context context) {
        File root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) root = context.getFilesDir();
        File dir = new File(root, "minicpm-v-4.6");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File modelFile(Context context) { return new File(modelDir(context), MODEL_FILE); }
    public static File mmprojFile(Context context) { return new File(modelDir(context), MMPROJ_FILE); }

    public static boolean isReady(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(KEY_VERIFIED, false)
                && modelFile(context).isFile() && modelFile(context).length() > 450_000_000L
                && mmprojFile(context).isFile() && mmprojFile(context).length() > 900_000_000L;
    }

    public static void startDownload(Context context) throws Exception {
        if (isReady(context)) return;
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new Exception("Android 系統下載服務不可用");

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean(KEY_VERIFIED, false).remove(KEY_ERROR).apply();

        deleteIfExists(modelFile(context));
        deleteIfExists(mmprojFile(context));
        cleanupOldGemma(context);

        DownloadManager.Request modelReq = new DownloadManager.Request(Uri.parse(MODEL_URL));
        modelReq.setTitle("全益採購追蹤｜MiniCPM-V 4.6 主模型");
        modelReq.setDescription("下載本機 AI 主模型（約 0.53 GB）");
        modelReq.setMimeType("application/octet-stream");
        modelReq.setAllowedOverRoaming(false);
        modelReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        modelReq.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                "minicpm-v-4.6/" + MODEL_FILE);

        DownloadManager.Request mmReq = new DownloadManager.Request(Uri.parse(MMPROJ_URL));
        mmReq.setTitle("全益採購追蹤｜MiniCPM-V 4.6 視覺模型");
        mmReq.setDescription("下載圖片理解模型（約 1.11 GB）");
        mmReq.setMimeType("application/octet-stream");
        mmReq.setAllowedOverRoaming(false);
        mmReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        mmReq.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                "minicpm-v-4.6/" + MMPROJ_FILE);

        long modelId = dm.enqueue(modelReq);
        long mmId = dm.enqueue(mmReq);
        p.edit().putLong(KEY_MODEL_DL, modelId).putLong(KEY_MMPROJ_DL, mmId).apply();
    }

    public static Status getStatus(Context context) {
        Status out = new Status();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        out.validationError = p.getString(KEY_ERROR, "");
        if (isReady(context)) {
            out.state = DownloadManager.STATUS_SUCCESSFUL;
            out.downloaded = modelFile(context).length() + mmprojFile(context).length();
            out.total = out.downloaded;
            return out;
        }

        long a = p.getLong(KEY_MODEL_DL, -1L);
        long b = p.getLong(KEY_MMPROJ_DL, -1L);
        DownloadPart s1 = query(context, a);
        DownloadPart s2 = query(context, b);

        if (s1.state == DownloadManager.STATUS_FAILED || s2.state == DownloadManager.STATUS_FAILED) {
            out.state = DownloadManager.STATUS_FAILED;
            out.reason = s1.state == DownloadManager.STATUS_FAILED ? s1.reason : s2.reason;
        } else if (s1.state == DownloadManager.STATUS_SUCCESSFUL && s2.state == DownloadManager.STATUS_SUCCESSFUL) {
            out.state = DownloadManager.STATUS_SUCCESSFUL;
            out.needsValidation = true;
        } else if (s1.state == DownloadManager.STATUS_RUNNING || s2.state == DownloadManager.STATUS_RUNNING) {
            out.state = DownloadManager.STATUS_RUNNING;
        } else if (s1.state == DownloadManager.STATUS_PAUSED || s2.state == DownloadManager.STATUS_PAUSED) {
            out.state = DownloadManager.STATUS_PAUSED;
        } else if (s1.state == DownloadManager.STATUS_PENDING || s2.state == DownloadManager.STATUS_PENDING) {
            out.state = DownloadManager.STATUS_PENDING;
        } else if (modelFile(context).isFile() && mmprojFile(context).isFile()) {
            out.state = DownloadManager.STATUS_SUCCESSFUL;
            out.needsValidation = true;
        }

        out.downloaded = positive(s1.downloaded) + positive(s2.downloaded);
        out.total = positive(s1.total) + positive(s2.total);
        if (out.total <= 0) out.total = EXPECTED_TOTAL;
        if (out.downloaded <= 0) {
            out.downloaded = (modelFile(context).exists() ? modelFile(context).length() : 0)
                    + (mmprojFile(context).exists() ? mmprojFile(context).length() : 0);
        }
        return out;
    }

    /**
     * Readiness requires all three checks: realistic size, official MD5, and an actual JNI/native load +
     * mmproj prepare. This prevents the UI from claiming AI is ready merely because a download reached 100%.
     */
    public static ValidationResult validateDownloadedModel(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            File model = modelFile(context);
            File mm = mmprojFile(context);
            if (!model.isFile() || !mm.isFile()) throw new Exception("模型檔案尚未完整下載");
            if (model.length() < 450_000_000L) throw new Exception("MiniCPM 主模型檔案過小，可能下載不完整");
            if (mm.length() < 900_000_000L) throw new Exception("MiniCPM 視覺模型檔案過小，可能下載不完整");

            String modelMd5 = md5(model);
            if (!MODEL_MD5.equalsIgnoreCase(modelMd5)) {
                throw new Exception("主模型 MD5 不符；請重新下載");
            }
            String mmMd5 = md5(mm);
            if (!MMPROJ_MD5.equalsIgnoreCase(mmMd5)) {
                throw new Exception("視覺模型 MD5 不符；請重新下載");
            }

            // Real native-library/model-load test. A successful APK build alone is not enough.
            LlamaEngine.getInstance(context.getApplicationContext())
                    .ensureLoaded(model.getAbsolutePath(), mm.getAbsolutePath());

            p.edit().putBoolean(KEY_VERIFIED, true).remove(KEY_ERROR).apply();
            return new ValidationResult(true, "MiniCPM-V 4.6 MD5 與 native 模型載入測試通過");
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            p.edit().putBoolean(KEY_VERIFIED, false).putString(KEY_ERROR, msg).apply();
            return new ValidationResult(false, msg);
        }
    }

    private static DownloadPart query(Context context, long id) {
        DownloadPart out = new DownloadPart();
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
                int r = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                if (r >= 0) out.reason = c.getInt(r);
            }
        } finally { c.close(); }
        return out;
    }

    private static long positive(long v) { return v > 0 ? v : 0; }
    private static void deleteIfExists(File f) { try { if (f.exists()) f.delete(); } catch (Throwable ignored) {} }

    private static void cleanupOldGemma(Context context) {
        try {
            File root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (root == null) return;
            deleteIfExists(new File(root, "gemma-4-E4B-it.litertlm"));
            deleteIfExists(new File(root, "gemma-4-E2B-it.litertlm"));
        } catch (Throwable ignored) {}
    }

    private static String md5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static class DownloadPart {
        int state = 0;
        int reason = 0;
        long downloaded = 0;
        long total = 0;
    }

    public static class Status {
        public int state = 0;
        public int reason = 0;
        public long downloaded = 0;
        public long total = EXPECTED_TOTAL;
        public boolean needsValidation = false;
        public String validationError = "";
        public int percent() {
            long t = total > 0 ? total : EXPECTED_TOTAL;
            return (int)Math.max(0, Math.min(100, downloaded * 100L / Math.max(1L, t)));
        }
    }

    public static class ValidationResult {
        public final boolean ok;
        public final String message;
        public ValidationResult(boolean ok, String message) { this.ok = ok; this.message = message; }
    }
}
