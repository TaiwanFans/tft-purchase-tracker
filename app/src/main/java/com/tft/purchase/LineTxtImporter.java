package com.tft.purchase;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LineTxtImporter {
    private static final Pattern MESSAGE = Pattern.compile("^(\\d{1,2}:\\d{2})\\t([^\\t]+)\\t(.*)$");
    private static final Pattern DATE_SLASH = Pattern.compile("^(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2}).*$");

    public static Result importUri(Context context, Uri uri) throws Exception {
        LineDbHelper db = new LineDbHelper(context);
        String fileName = displayName(context, uri);
        String conversation = normalizeConversationName(fileName);
        int inserted = 0;
        int skipped = 0;
        String currentDate = null;
        long fallbackTs = System.currentTimeMillis();

        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("無法開啟檔案");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String clean = line.replace("\uFEFF", "").trim();
                if (clean.isEmpty()) continue;

                Matcher date = DATE_SLASH.matcher(clean);
                if (date.matches()) {
                    currentDate = date.group(1) + "-" + two(date.group(2)) + "-" + two(date.group(3));
                    continue;
                }

                Matcher msg = MESSAGE.matcher(line.replace("\uFEFF", ""));
                if (!msg.matches()) continue;

                String time = msg.group(1);
                String sender = msg.group(2).trim();
                String text = msg.group(3).trim();
                long ts = parseTimestamp(currentDate, time, fallbackTs++);

                LineDbHelper.Message m = new LineDbHelper.Message();
                m.eventId = "txt-" + UUID.randomUUID();
                m.notificationKey = "";
                m.conversation = conversation;
                m.sender = sender.isEmpty() ? "未知發言者" : sender;
                m.text = text;
                m.timestamp = ts;
                m.source = "LINE_TXT_IMPORT";
                m.completeness = "LIKELY_COMPLETE";
                m.rawJson = "{\"file\":\"" + escape(fileName) + "\"}";
                if (db.insertMessage(m)) inserted++; else skipped++;
            }
        }
        return new Result(conversation, inserted, skipped);
    }

    private static long parseTimestamp(String date, String time, long fallback) {
        if (date == null || time == null) return fallback;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).parse(date + " " + time);
            return d == null ? fallback : d.getTime();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String displayName(Context context, Uri uri) {
        Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) return c.getString(0);
            } finally { c.close(); }
        }
        return "LINE聊天記錄.txt";
    }

    private static String normalizeConversationName(String fileName) {
        String name = fileName == null ? "LINE聊天記錄" : fileName;
        name = name.replaceAll("(?i)\\.txt$", "");
        name = name.replace("[LINE]", "").trim();
        name = name.replace("的聊天", "").replace("聊天記錄", "").trim();
        if (name.startsWith("與")) name = name.substring(1).trim();
        return name.isEmpty() ? "LINE TXT 匯入" : name;
    }

    private static String two(String s) {
        try { return String.format(Locale.US, "%02d", Integer.parseInt(s)); }
        catch (Exception e) { return s; }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class Result {
        public final String conversation;
        public final int inserted;
        public final int skipped;
        Result(String conversation, int inserted, int skipped) {
            this.conversation = conversation;
            this.inserted = inserted;
            this.skipped = skipped;
        }
    }
}
