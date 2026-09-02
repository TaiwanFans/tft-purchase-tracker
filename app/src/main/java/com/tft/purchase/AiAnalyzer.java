package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AiAnalyzer {
    private final Context context;
    private final LineDbHelper db;

    public AiAnalyzer(Context context) {
        this.context = context.getApplicationContext();
        this.db = new LineDbHelper(this.context);
    }

    public boolean isAiConfigured() {
        SharedPreferences sp = context.getSharedPreferences("line_ai_settings", Context.MODE_PRIVATE);
        return !TextUtils.isEmpty(sp.getString("api_url", ""))
                && !TextUtils.isEmpty(sp.getString("api_key", ""))
                && !TextUtils.isEmpty(sp.getString("model", ""));
    }

    public LineDbHelper.Summary analyzeConversation(String conversation) throws Exception {
        List<LineDbHelper.Message> messages = db.recentMessages(conversation, 60);
        if (messages.isEmpty()) return null;
        if (!isAiConfigured()) return localFallback(conversation, messages);

        SharedPreferences sp = context.getSharedPreferences("line_ai_settings", Context.MODE_PRIVATE);
        String apiUrl = sp.getString("api_url", "").trim();
        String apiKey = sp.getString("api_key", "").trim();
        String model = sp.getString("model", "").trim();

        String prompt = buildPrompt(conversation, messages);
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("temperature", 0.1);
        JSONArray chat = new JSONArray();
        chat.put(new JSONObject().put("role", "system").put("content",
                "你是全益台灣電扇公司的業務案件整理助手。只根據提供的訊息判斷，不得捏造價格、型號、規格、身份、已報價、已成交或已完成。資料不足必須使用 NEED_INFORMATION。只輸出單一 JSON 物件，不要 Markdown。"));
        chat.put(new JSONObject().put("role", "user").put("content", prompt));
        request.put("messages", chat);

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(body); }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(stream);
        if (code < 200 || code >= 300) throw new Exception("AI API HTTP " + code + ": " + response);

        JSONObject root = new JSONObject(response);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("AI 回應沒有 choices");
        String content = choices.getJSONObject(0).getJSONObject("message").optString("content", "");
        JSONObject result = new JSONObject(stripCodeFence(content));

        LineDbHelper.Summary s = new LineDbHelper.Summary();
        s.conversation = conversation;
        s.conversationType = result.optString("conversation_type", "UNKNOWN");
        s.roles = jsonValueAsText(result.opt("roles"));
        s.summary = result.optString("summary", "");
        s.status = normalizeStatus(result.optString("status", "NEED_INFORMATION"));
        s.nextAction = result.optString("next_action", "");
        s.priority = normalizePriority(result.optString("priority", "NORMAL"));
        s.replyDraft = result.optString("reply_draft", "");
        s.evidence = jsonValueAsText(result.opt("evidence_event_ids"));
        s.needs = jsonValueAsText(result.opt("need_information"));
        s.updatedAt = System.currentTimeMillis();
        db.upsertSummary(s);
        return s;
    }

    private String buildPrompt(String conversation, List<LineDbHelper.Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("對話/群組名稱：").append(conversation).append("\n\n");
        sb.append("請判斷：是否群組、各發言者可能角色（客戶/廠商/公司內部/未知）、需求、案件狀態、下一步、回覆草稿。\n");
        sb.append("案件狀態只能使用：NEW, NEED_INFORMATION, NEED_QUOTE, QUOTED, WAITING_CUSTOMER, NEED_FOLLOWUP, ORDER_CONFIRMED, PROCESSING, COMPLETED, NEED_REPLY。\n");
        sb.append("輸出 JSON 欄位：conversation_type, roles, summary, status, next_action, priority, reply_draft, evidence_event_ids, need_information。\n");
        sb.append("priority 只能 HIGH/NORMAL/LOW。roles 請列出每個姓名與角色；不確定就 UNKNOWN。\n\n");
        sb.append("訊息（由舊到新）：\n");
        for (int i = messages.size() - 1; i >= 0; i--) {
            LineDbHelper.Message m = messages.get(i);
            sb.append("[").append(m.eventId).append("] ")
                    .append(m.sender).append("：")
                    .append(m.text == null ? "" : m.text.replace("\n", " "))
                    .append(" | source=").append(m.source)
                    .append(" | completeness=").append(m.completeness)
                    .append("\n");
        }
        return sb.toString();
    }

    private LineDbHelper.Summary localFallback(String conversation, List<LineDbHelper.Message> messages) {
        StringBuilder combined = new StringBuilder();
        StringBuilder evidence = new StringBuilder();
        for (LineDbHelper.Message m : messages) {
            if (m.text != null) combined.append(m.text).append(' ');
            if (evidence.length() > 0) evidence.append(',');
            evidence.append(m.eventId);
        }
        String text = combined.toString();
        String status = "NEED_REPLY";
        String next = "人工確認最新訊息並回覆";
        String needs = "";
        if (containsAny(text, "報價", "多少", "價格", "價錢", "單價")) {
            status = "NEED_QUOTE";
            next = "確認產品、數量與規格後製作報價";
        }
        if (containsAny(text, "型號不清楚", "規格不清楚", "哪一款", "尺寸不確定")) {
            status = "NEED_INFORMATION";
            next = "向對方補問缺少的型號或規格";
            needs = "型號/規格需確認";
        }
        if (containsAny(text, "已報價", "報價單已", "報價給")) {
            status = "WAITING_CUSTOMER";
            next = "確認客戶是否已回覆；未回覆則安排追蹤";
        }
        if (containsAny(text, "確定訂", "我要訂", "下單", "請出貨")) {
            status = "ORDER_CONFIRMED";
            next = "人工確認訂單內容後進入後續處理";
        }

        LineDbHelper.Message latest = messages.get(0);
        LineDbHelper.Summary s = new LineDbHelper.Summary();
        s.conversation = conversation;
        s.conversationType = "UNKNOWN";
        s.roles = latest.sender + "=UNKNOWN（尚未使用 AI 確認角色）";
        s.summary = latest.sender + "：" + (latest.text == null ? "" : latest.text);
        s.status = status;
        s.nextAction = next;
        s.priority = status.equals("NEED_QUOTE") || status.equals("NEED_INFORMATION") ? "HIGH" : "NORMAL";
        s.replyDraft = "";
        s.evidence = evidence.toString();
        s.needs = needs;
        s.updatedAt = System.currentTimeMillis();
        db.upsertSummary(s);
        return s;
    }

    private boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    private String normalizeStatus(String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        switch (s) {
            case "NEW": case "NEED_INFORMATION": case "NEED_QUOTE": case "QUOTED":
            case "WAITING_CUSTOMER": case "NEED_FOLLOWUP": case "ORDER_CONFIRMED":
            case "PROCESSING": case "COMPLETED": case "NEED_REPLY": return s;
            default: return "NEED_INFORMATION";
        }
    }

    private String normalizePriority(String priority) {
        String p = priority == null ? "" : priority.trim().toUpperCase();
        if (p.equals("HIGH") || p.equals("LOW")) return p;
        return "NORMAL";
    }

    private String jsonValueAsText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        return value.toString();
    }

    private String stripCodeFence(String s) {
        if (s == null) return "{}";
        String x = s.trim();
        if (x.startsWith("```")) {
            int firstNewline = x.indexOf('\n');
            if (firstNewline >= 0) x = x.substring(firstNewline + 1);
            int end = x.lastIndexOf("```");
            if (end >= 0) x = x.substring(0, end);
        }
        return x.trim();
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
