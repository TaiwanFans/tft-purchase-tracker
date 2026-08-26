package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Persistent state for long-running on-device Gemma analysis. */
public final class AiJobStore {
    private AiJobStore() {}
    private static final String PREF = "tft_ai_jobs";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_ERROR = "error";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void begin(Context c, List<String> paths, long replaceId) {
        JSONArray a = new JSONArray();
        if (paths != null) for (String s : paths) a.put(s);
        p(c).edit()
                .putString("state", STATE_RUNNING)
                .putInt("progress", 1)
                .putString("stage", "準備 AI 分析")
                .putInt("total", paths == null ? 0 : paths.size())
                .putInt("current", 0)
                .putInt("success", 0)
                .putInt("failed", 0)
                .putInt("warning_count", 0)
                .putString("paths", a.toString())
                .putLong("replace_id", replaceId)
                .putString("last_ids", "")
                .putString("error", "")
                .apply();
    }

    public static void progress(Context c, int percent, String stage, int current, int total, int success, int failed) {
        p(c).edit()
                .putString("state", STATE_RUNNING)
                .putInt("progress", Math.max(1, Math.min(99, percent)))
                .putString("stage", stage == null ? "AI 分析中" : stage)
                .putInt("current", current)
                .putInt("total", total)
                .putInt("success", success)
                .putInt("failed", failed)
                .apply();
    }

    public static void appendId(Context c, long id) {
        SharedPreferences s = p(c);
        String old = s.getString("last_ids", "");
        String next = old == null || old.isEmpty() ? String.valueOf(id) : old + "," + id;
        s.edit().putString("last_ids", next).apply();
    }

    public static void done(Context c, int created, int warnings) {
        String stage;
        if (created <= 0) stage = "AI 分析結束，但沒有建立採購單";
        else if (warnings > 0) stage = "已建立 " + created + " 張採購單，其中 " + warnings + " 張建議重新 AI 分析";
        else stage = "已建立 " + created + " 張採購單，後續功能已可使用";
        p(c).edit()
                .putString("state", created > 0 ? STATE_DONE : STATE_ERROR)
                .putInt("progress", 100)
                .putString("stage", stage)
                .putInt("success", Math.max(0, created))
                .putInt("failed", 0)
                .putInt("warning_count", Math.max(0, warnings))
                .putString("error", created > 0 ? "" : "AI 沒有建立任何採購單")
                .apply();
    }

    public static void error(Context c, String message) {
        p(c).edit()
                .putString("state", STATE_ERROR)
                .putInt("progress", 100)
                .putString("stage", "AI 分析停止")
                .putString("error", message == null ? "未知錯誤" : message)
                .apply();
    }

    public static Snapshot get(Context c) {
        SharedPreferences s = p(c);
        Snapshot x = new Snapshot();
        x.state = s.getString("state", STATE_IDLE);
        x.progress = s.getInt("progress", 0);
        x.stage = s.getString("stage", "");
        x.total = s.getInt("total", 0);
        x.current = s.getInt("current", 0);
        x.success = s.getInt("success", 0);
        x.failed = s.getInt("failed", 0);
        x.warningCount = s.getInt("warning_count", 0);
        x.error = s.getString("error", "");
        x.replaceId = s.getLong("replace_id", -1);
        x.paths = readPaths(s.getString("paths", "[]"));
        x.lastIds = readIds(s.getString("last_ids", ""));
        return x;
    }

    private static List<String> readPaths(String raw) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static List<Long> readIds(String raw) {
        List<Long> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String s : raw.split(",")) {
            try { out.add(Long.parseLong(s.trim())); } catch (Exception ignored) {}
        }
        return out;
    }

    public static class Snapshot {
        public String state = STATE_IDLE;
        public int progress;
        public String stage = "";
        public int total;
        public int current;
        public int success;
        public int failed;
        public int warningCount;
        public String error = "";
        public long replaceId = -1;
        public List<String> paths = new ArrayList<>();
        public List<Long> lastIds = new ArrayList<>();
        public boolean running() { return STATE_RUNNING.equals(state); }
    }
}
