package com.tft.purchase;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class LineDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "line_ai_mvp.db";
    private static final int DB_VERSION = 1;

    public LineDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "event_id TEXT UNIQUE NOT NULL," +
                "notification_key TEXT," +
                "conversation TEXT NOT NULL," +
                "sender TEXT," +
                "text TEXT," +
                "timestamp INTEGER NOT NULL," +
                "source TEXT NOT NULL," +
                "completeness TEXT NOT NULL," +
                "raw_json TEXT)");
        db.execSQL("CREATE INDEX idx_messages_conversation_ts ON messages(conversation, timestamp DESC)");
        db.execSQL("CREATE TABLE summaries (" +
                "conversation TEXT PRIMARY KEY," +
                "conversation_type TEXT," +
                "roles TEXT," +
                "summary TEXT," +
                "status TEXT," +
                "next_action TEXT," +
                "priority TEXT," +
                "reply_draft TEXT," +
                "evidence TEXT," +
                "needs TEXT," +
                "updated_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS summaries");
        db.execSQL("DROP TABLE IF EXISTS messages");
        onCreate(db);
    }

    public synchronized boolean insertMessage(Message m) {
        ContentValues v = new ContentValues();
        v.put("event_id", m.eventId);
        v.put("notification_key", m.notificationKey);
        v.put("conversation", safe(m.conversation, "未辨識對話"));
        v.put("sender", safe(m.sender, "未知發言者"));
        v.put("text", safe(m.text, ""));
        v.put("timestamp", m.timestamp);
        v.put("source", safe(m.source, "UNKNOWN"));
        v.put("completeness", safe(m.completeness, "UNKNOWN"));
        v.put("raw_json", safe(m.rawJson, "{}"));
        long result = getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }

    public synchronized void upsertSummary(Summary s) {
        ContentValues v = new ContentValues();
        v.put("conversation", s.conversation);
        v.put("conversation_type", safe(s.conversationType, "UNKNOWN"));
        v.put("roles", safe(s.roles, ""));
        v.put("summary", safe(s.summary, ""));
        v.put("status", safe(s.status, "NEED_REPLY"));
        v.put("next_action", safe(s.nextAction, ""));
        v.put("priority", safe(s.priority, "NORMAL"));
        v.put("reply_draft", safe(s.replyDraft, ""));
        v.put("evidence", safe(s.evidence, ""));
        v.put("needs", safe(s.needs, ""));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("summaries", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<String> listConversations(int limit) {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT conversation, MAX(timestamp) t FROM messages GROUP BY conversation ORDER BY t DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally { c.close(); }
        return out;
    }

    public synchronized List<Message> recentMessages(String conversation, int limit) {
        ArrayList<Message> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT event_id, notification_key, conversation, sender, text, timestamp, source, completeness, raw_json " +
                        "FROM messages WHERE conversation=? ORDER BY timestamp DESC LIMIT ?",
                new String[]{conversation, String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                Message m = new Message();
                m.eventId = c.getString(0);
                m.notificationKey = c.getString(1);
                m.conversation = c.getString(2);
                m.sender = c.getString(3);
                m.text = c.getString(4);
                m.timestamp = c.getLong(5);
                m.source = c.getString(6);
                m.completeness = c.getString(7);
                m.rawJson = c.getString(8);
                out.add(m);
            }
        } finally { c.close(); }
        return out;
    }

    public synchronized List<Summary> listSummaries() {
        ArrayList<Summary> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT conversation, conversation_type, roles, summary, status, next_action, priority, reply_draft, evidence, needs, updated_at " +
                        "FROM summaries ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, updated_at DESC",
                null);
        try {
            while (c.moveToNext()) {
                Summary s = new Summary();
                s.conversation = c.getString(0);
                s.conversationType = c.getString(1);
                s.roles = c.getString(2);
                s.summary = c.getString(3);
                s.status = c.getString(4);
                s.nextAction = c.getString(5);
                s.priority = c.getString(6);
                s.replyDraft = c.getString(7);
                s.evidence = c.getString(8);
                s.needs = c.getString(9);
                s.updatedAt = c.getLong(10);
                out.add(s);
            }
        } finally { c.close(); }
        return out;
    }

    public synchronized long messageCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages", null);
        try { return c.moveToFirst() ? c.getLong(0) : 0; }
        finally { c.close(); }
    }

    public synchronized int countStatus(String status) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM summaries WHERE status=?", new String[]{status});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public static class Message {
        public String eventId;
        public String notificationKey;
        public String conversation;
        public String sender;
        public String text;
        public long timestamp;
        public String source;
        public String completeness;
        public String rawJson;
    }

    public static class Summary {
        public String conversation;
        public String conversationType;
        public String roles;
        public String summary;
        public String status;
        public String nextAction;
        public String priority;
        public String replyDraft;
        public String evidence;
        public String needs;
        public long updatedAt;
    }
}
