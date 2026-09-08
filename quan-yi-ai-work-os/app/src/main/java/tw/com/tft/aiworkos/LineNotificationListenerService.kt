package tw.com.tft.aiworkos

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class LineNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName != "jp.naver.line.android") return
        val extras = n.notification.extras ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (text.isBlank()) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty().trim()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()

        val group = conversation.ifBlank { sub }
        val sender = when {
            title.isBlank() -> ""
            group.isNotBlank() && title == group -> ""
            else -> title
        }
        val raw = JSONObject()
            .put("package", n.packageName)
            .put("key", n.key)
            .put("post_time", n.postTime)
            .put("title", title)
            .put("conversation_title", conversation)
            .put("sub_text", sub)
            .put("text", text)
            .put("note", "僅保存 Android 通知實際提供的欄位；不代表 LINE 完整聊天內容")
            .toString()

        try {
            WorkOSDatabase(applicationContext).use { db ->
                db.addInbox(
                    source = "LINE通知",
                    text = text,
                    groupName = group,
                    sender = sender,
                    rawPayload = raw
                )
            }
        } catch (_: Exception) {
            // 通知監聽不可因資料庫錯誤造成服務崩潰。
        }
    }
}
