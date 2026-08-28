package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent state for long-running on-device purchase analysis and its reorderable queue. */
public final class AiJobStore {
    private AiJobStore() {}
    private static final String PREF = "tft_ai_jobs";
    private static final String KEY_QUEUE = "queue_v2";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_ERROR = "error";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Starts a fresh queue, replacing the previous finished/error job state. */
    public static synchronized void begin(Context c, List<String> paths, long replaceId) {
        List<QueueItem> queue = new ArrayList<>();
        if (paths != null) {
            for (String path : paths) {
                if (path == null || path.trim().isEmpty()) continue;
                queue.add(new QueueItem(UUID.randomUUID().toString(), path, replaceId));
            }
        }
        writeFresh(c, queue);
    }

    /**
     * Adds work to the current running queue. If there is no running job, a fresh queue is created.
     * replaceId belongs to each queue item so a re-analysis can safely coexist with newly queued photos.
     */
    public static synchronized void enqueue(Context c, List<String> paths, long replaceId) {
        if (paths == null || paths.isEmpty()) return;
        Snapshot s = readSnapshot(c, false);
        if (!s.running() || s.queue.isEmpty()) {
            begin(c, paths, replaceId);
            return;
        }
        List<QueueItem> queue = new ArrayList<>(s.queue);
        int added = 0;
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            queue.add(new QueueItem(UUID.randomUUID().toString(), path, replaceId));
            added++;
        }
        if (added <= 0) return;
        p(c).edit()
                .putString(KEY_QUEUE, queueToJson(queue).toString())
                .putString("paths", pathsToJson(queue).toString())
                .putInt("total", queue.size())
                .apply();
    }

    /** Only waiting items may move. Completed items and the currently-processing item are locked. */
    public static synchronized boolean movePending(Context c, int fromIndex, int toIndex) {
        Snapshot s = readSnapshot(c, false);
        if (!s.running()) return false;
        int size = s.queue.size();
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= size || toIndex >= size || fromIndex == toIndex) return false;
        int lockedThrough = Math.max(0, Math.min(size - 1, s.nextIndex));
        if (fromIndex <= lockedThrough || toIndex <= lockedThrough) return false;
        List<QueueItem> queue = new ArrayList<>(s.queue);
        QueueItem item = queue.remove(fromIndex);
        queue.add(toIndex, item);
        p(c).edit()
                .putString(KEY_QUEUE, queueToJson(queue).toString())
                .putString("paths", pathsToJson(queue).toString())
                .apply();
        return true;
    }

    private static void writeFresh(Context c, List<QueueItem> queue) {
        p(c).edit()
                .putString("state", queue.isEmpty() ? STATE_IDLE : STATE_RUNNING)
                .putInt("progress", queue.isEmpty() ? 0 : 1)
                .putInt("item_progress", 0)
                .putString("stage", queue.isEmpty() ? "" : "準備 AI 分析佇列")
                .putInt("total", queue.size())
                .putInt("current", 0)
                .putInt("next_index", 0)
                .putInt("success", 0)
                .putInt("failed", 0)
                .putInt("warning_count", 0)
                .putString(KEY_QUEUE, queueToJson(queue).toString())
                .putString("paths", pathsToJson(queue).toString())
                .putLong("replace_id", queue.size() == 1 ? queue.get(0).replaceId : -1)
                .putString("last_ids", "")
                .putString("error", "")
                .apply();
    }

    public static void progress(Context c, int percent, String stage, int current, int total, int success, int failed) {
        progress(c, percent, stage, current, total, success, failed, 0);
    }

    public static synchronized void progress(Context c, int percent, String stage, int current, int total,
                                             int success, int failed, int itemProgress) {
        Snapshot s = readSnapshot(c, false);
        int liveTotal = Math.max(total, s.queue.size());
        p(c).edit()
                .putString("state", STATE_RUNNING)
                .putInt("progress", Math.max(1, Math.min(99, percent)))
                .putInt("item_progress", Math.max(0, Math.min(100, itemProgress)))
                .putString("stage", stage == null ? "AI 分析中" : stage)
                .putInt("current", current)
                .putInt("total", liveTotal)
                .putInt("success", success)
                .putInt("failed", failed)
                .apply();
    }

    /** Called only after the current image has been persisted (success or retry placeholder). */
    public static synchronized void checkpointNextIndex(Context c, int nextIndex, int success, int warnings) {
        p(c).edit()
                .putInt("next_index", Math.max(0, nextIndex))
                .putInt("success", Math.max(0, success))
                .putInt("failed", Math.max(0, warnings))
                .putInt("warning_count", Math.max(0, warnings))
                .putInt("item_progress", 100)
                .apply();
    }

    public static synchronized void appendId(Context c, long id) {
        SharedPreferences s = p(c);
        String old = s.getString("last_ids", "");
        String next = old == null || old.isEmpty() ? String.valueOf(id) : old + "," + id;
        s.edit().putString("last_ids", next).apply();
    }

    public static synchronized void done(Context c, int created, int warnings) {
        String stage;
        if (created <= 0) stage = "AI 分析結束，但沒有建立採購單";
        else if (warnings > 0) stage = "已建立 " + created + " 張採購單，其中 " + warnings + " 張需要人工確認";
        else stage = "已建立 " + created + " 張採購單，排隊工作全部完成";
        p(c).edit()
                .putString("state", created > 0 ? STATE_DONE : STATE_ERROR)
                .putInt("progress", 100)
                .putInt("item_progress", 100)
                .putString("stage", stage)
                .putInt("success", Math.max(0, created))
                .putInt("failed", Math.max(0, warnings))
                .putInt("warning_count", Math.max(0, warnings))
                .putString("error", created > 0 ? "" : "AI 沒有建立任何採購單")
                .apply();
    }

    public static synchronized void error(Context c, String message) {
        p(c).edit()
                .putString("state", STATE_ERROR)
                .putInt("progress", 100)
                .putString("stage", "AI 分析停止")
                .putString("error", message == null ? "未知錯誤" : message)
                .apply();
    }

    public static synchronized Snapshot get(Context c) {
        return readSnapshot(c, true);
    }

    private static Snapshot readSnapshot(Context c, boolean migrateLegacy) {
        SharedPreferences s = p(c);
        Snapshot x = new Snapshot();
        x.state = s.getString("state", STATE_IDLE);
        x.progress = s.getInt("progress", 0);
        x.itemProgress = s.getInt("item_progress", 0);
        x.stage = s.getString("stage", "");
        x.current = s.getInt("current", 0);
        x.nextIndex = s.getInt("next_index", 0);
        x.success = s.getInt("success", 0);
        x.failed = s.getInt("failed", 0);
        x.warningCount = s.getInt("warning_count", 0);
        x.error = s.getString("error", "");
        x.replaceId = s.getLong("replace_id", -1);
        x.queue = readQueue(s.getString(KEY_QUEUE, ""));

        if (x.queue.isEmpty()) {
            List<String> legacyPaths = readPaths(s.getString("paths", "[]"));
            if (!legacyPaths.isEmpty()) {
                long legacyReplace = legacyPaths.size() == 1 ? x.replaceId : -1;
                for (String path : legacyPaths) {
                    x.queue.add(new QueueItem(UUID.randomUUID().toString(), path, legacyReplace));
                }
                if (migrateLegacy) {
                    s.edit().putString(KEY_QUEUE, queueToJson(x.queue).toString()).apply();
                }
            }
        }
        x.paths = new ArrayList<>();
        for (QueueItem item : x.queue) x.paths.add(item.path);
        x.total = x.queue.size();
        x.lastIds = readIds(s.getString("last_ids", ""));
        return x;
    }

    private static JSONArray queueToJson(List<QueueItem> queue) {
        JSONArray a = new JSONArray();
        if (queue == null) return a;
        for (QueueItem item : queue) {
            if (item == null || item.path == null || item.path.trim().isEmpty()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", item.id == null ? UUID.randomUUID().toString() : item.id);
                o.put("path", item.path);
                o.put("replace_id", item.replaceId);
                a.put(o);
            } catch (Exception ignored) {}
        }
        return a;
    }

    private static JSONArray pathsToJson(List<QueueItem> queue) {
        JSONArray a = new JSONArray();
        if (queue != null) for (QueueItem item : queue) if (item != null && item.path != null) a.put(item.path);
        return a;
    }

    private static List<QueueItem> readQueue(String raw) {
        List<QueueItem> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String path = o.optString("path", "");
                if (path.isEmpty()) continue;
                out.add(new QueueItem(o.optString("id", UUID.randomUUID().toString()), path,
                        o.optLong("replace_id", -1)));
            }
        } catch (Exception ignored) {}
        return out;
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

    public static final class QueueItem {
        public final String id;
        public final String path;
        public final long replaceId;
        QueueItem(String id, String path, long replaceId) {
            this.id = id == null ? "" : id;
            this.path = path == null ? "" : path;
            this.replaceId = replaceId;
        }
    }

    public static class Snapshot {
        public String state = STATE_IDLE;
        public int progress;
        public int itemProgress;
        public String stage = "";
        public int total;
        public int current;
        public int nextIndex;
        public int success;
        public int failed;
        public int warningCount;
        public String error = "";
        public long replaceId = -1;
        public List<QueueItem> queue = new ArrayList<>();
        public List<String> paths = new ArrayList<>();
        public List<Long> lastIds = new ArrayList<>();
        public boolean running() { return STATE_RUNNING.equals(state); }
        public int pendingCount() { return Math.max(0, queue.size() - Math.max(0, nextIndex + (running() ? 1 : 0))); }
    }
}
