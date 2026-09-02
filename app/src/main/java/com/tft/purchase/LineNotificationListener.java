package com.tft.purchase;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.UUID;

public class LineNotificationListener extends NotificationListenerService {
    private static final String LINE_PACKAGE = "jp.naver.line.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !LINE_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;
        String title = asString(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = asString(extras.getCharSequence(Notification.EXTRA_TEXT));
        String conversationTitle = asString(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String subText = asString(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));

        String fallbackConversation = firstNonBlank(conversationTitle, subText, title, "LINE 未辨識對話");
        boolean insertedAny = false;

        Parcelable[] rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (rawMessages != null) {
            for (Parcelable p : rawMessages) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                String msgText = asString(b.getCharSequence("text"));
                if (TextUtils.isEmpty(msgText)) continue;

                String sender = asString(b.getCharSequence("sender"));
                if (TextUtils.isEmpty(sender) && android.os.Build.VERSION.SDK_INT >= 28) {
                    Object personObj = b.get("sender_person");
                    if (personObj instanceof Person) {
                        sender = asString(((Person) personObj).getName());
                    }
                }

                long time = b.getLong("time", sbn.getPostTime());
                String conversation = fallbackConversation;
                if (TextUtils.isEmpty(conversationTitle) && !TextUtils.isEmpty(title) && !TextUtils.equals(title, sender)) {
                    conversation = title;
                }
                if (TextUtils.isEmpty(sender)) sender = inferSenderFromText(msgText, title, conversationTitle);

                insertEvent(sbn, conversation, sender, stripSenderPrefix(msgText, sender), time,
                        "LIKELY_COMPLETE", title, text, conversationTitle, subText);
                insertedAny = true;
            }
        }

        if (!insertedAny && !TextUtils.isEmpty(text)) {
            String sender = inferSenderFromText(text, title, conversationTitle);
            String conversation = fallbackConversation;
            String cleanedText = stripSenderPrefix(text, sender);
            insertEvent(sbn, conversation, sender, cleanedText, sbn.getPostTime(),
                    TextUtils.isEmpty(conversationTitle) ? "PARTIAL" : "LIKELY_COMPLETE",
                    title, text, conversationTitle, subText);
        }
    }

    private void insertEvent(StatusBarNotification sbn, String conversation, String sender, String text, long timestamp,
                             String completeness, String title, String rawText, String conversationTitle, String subText) {
        try {
            JSONObject raw = new JSONObject();
            raw.put("package", sbn.getPackageName());
            raw.put("notification_key", sbn.getKey());
            raw.put("title", title);
            raw.put("text", rawText);
            raw.put("conversation_title", conversationTitle);
            raw.put("sub_text", subText);

            String normalizedConversation = firstNonBlank(conversation, title, "LINE 未辨識對話");
            String normalizedSender = firstNonBlank(sender, title, "未知發言者");
            String eventId = "notif-" + Math.abs((sbn.getKey() + "|" + timestamp + "|" + normalizedSender + "|" + text).hashCode()) + "-" + timestamp;

            LineDbHelper.Message m = new LineDbHelper.Message();
            m.eventId = eventId;
            m.notificationKey = sbn.getKey();
            m.conversation = normalizedConversation;
            m.sender = normalizedSender;
            m.text = text == null ? "" : text;
            m.timestamp = timestamp;
            m.source = "ANDROID_NOTIFICATION";
            m.completeness = completeness;
            m.rawJson = raw.toString();
            new LineDbHelper(getApplicationContext()).insertMessage(m);
        } catch (Exception ignored) {
        }
    }

    private String inferSenderFromText(String text, String title, String conversationTitle) {
        if (!TextUtils.isEmpty(conversationTitle)) {
            String[] separators = {": ", "：", ":"};
            for (String sep : separators) {
                int idx = text == null ? -1 : text.indexOf(sep);
                if (idx > 0 && idx < 40) return text.substring(0, idx).trim();
            }
        }
        return firstNonBlank(title, "未知發言者");
    }

    private String stripSenderPrefix(String text, String sender) {
        if (text == null) return "";
        if (TextUtils.isEmpty(sender)) return text;
        String[] prefixes = {sender + ": ", sender + "：", sender + ":"};
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return text.substring(prefix.length()).trim();
        }
        return text;
    }

    private String asString(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (!TextUtils.isEmpty(v)) return v.trim();
        return "";
    }
}
