package com.tft.purchase;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

public final class GemmaModelManager {
    public static final String MODEL_FILE = "gemma-4-E4B-it.litertlm";
    public static final long EXPECTED_SIZE = 3654467584L;
    private static final String LEGACY_MODEL_FILE = "gemma-4-E2B-it.litertlm";
    private static final String PREFS = "tft_settings";
    private static final String KEY_DOWNLOAD_ID = "gemma_download_id_v209";
    private static final String URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true";

    private GemmaModelManager() {}

    public static File modelFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, MODEL_FILE);
    }

    public static boolean isReady(Context context) {
        File f = modelFile(context);
        return f.exists() && f.length() > 3_400_000_000L;
    }

    public static long startDownload(Context context) throws Exception {
        if (isReady(context)) return -1L;
        File f = modelFile(context);
        if (f.exists()) f.delete();
        File dir = f.getParentFile();
        if (dir != null) {
            File old = new File(dir, LEGACY_MODEL_FILE);
            if (old.exists()) old.delete();
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new Exception("系統下載服務不可用");
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(URL));
        req.setTitle("全益採購追蹤｜Gemma 4 E4B AI 模型");
        req.setDescription("正在下載約 3.7 GB 的離線視覺模型，完成後可完全在手機本機辨識採購單。");
        req.setMimeType("application/octet-stream");
        req.setAllowedOverRoaming(false);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, MODEL_FILE);
        long id = dm.enqueue(req);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DOWNLOAD_ID, id).apply();
        return id;
    }

    public static Status getStatus(Context context) {
        Status out = new Status();
        if (isReady(context)) {
            out.state = DownloadManager.STATUS_SUCCESSFUL;
            out.downloaded = modelFile(context).length();
            out.total = out.downloaded;
            return out;
        }
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        return out;
    }

    public static class Status {
        public int state = 0;
        public long downloaded = 0;
        public long total = EXPECTED_SIZE;
        public int reason = 0;
        public int percent() {
            long t = total > 0 ? total : EXPECTED_SIZE;
            return (int)Math.max(0, Math.min(100, downloaded * 100L / Math.max(1L, t)));
        }
    }
}
