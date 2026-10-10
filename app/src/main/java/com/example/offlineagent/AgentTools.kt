package com.example.offlineagent

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class AgentTools(private val context: Context) {
    private val prefs = context.getSharedPreferences("agent_notes", Context.MODE_PRIVATE)

    fun execute(name: String, args: JSONObject, approved: Boolean = false): JSONObject {
        if (!approved) {
            val policy = checkPolicy(name, args)
            if (policy != null) return policy
        }

        return when (name) {
            "save_note" -> saveNote(args.optString("title", "未命名"), args.optString("content", ""))
            "list_notes" -> JSONObject().put("ok", true).put("notes", loadNotes())
            "search_notes" -> searchNotes(args.optString("query", ""))
            "get_current_time" -> getCurrentTime()
            "open_app" -> openApp(args.optString("package", ""))
            "inspect_screen" -> phone()?.snapshot() ?: disabled()
            "tap" -> phone()?.tap(args.optString("selector", "")) ?: disabled()
            "set_text" -> phone()?.setText(args.optString("selector", ""), args.optString("text", "")) ?: disabled()
            "scroll" -> phone()?.scroll(args.optString("direction", "down")) ?: disabled()
            "global_action" -> phone()?.global(args.optString("action", "back")) ?: disabled()
            "tap_coordinates" -> phone()?.tapCoordinates(
                args.optDouble("x", 0.0).toFloat(),
                args.optDouble("y", 0.0).toFloat()
            ) ?: disabled()
            else -> JSONObject().put("ok", false).put("error", "未知工具：$name")
        }
    }

    private fun checkPolicy(name: String, args: JSONObject): JSONObject? {
        val selector = args.optString("selector", "").lowercase()
        val sensitive = listOf(
            "傳送", "送出", "發布", "刪除", "付款", "購買", "下單", "轉帳", "匯款",
            "提交", "確認付款", "確認購買", "send", "submit", "delete", "pay", "purchase",
            "buy", "post", "publish", "transfer"
        )
        val forbidden = listOf("密碼", "password", "otp", "驗證碼", "verification code", "pin")

        if (name == "set_text" && forbidden.any { selector.contains(it) }) {
            return JSONObject()
                .put("ok", false)
                .put("blocked", true)
                .put("error", "基於安全規則，Agent 不會自動填寫密碼、PIN、OTP 或驗證碼。")
        }

        if (name == "tap" && sensitive.any { selector.contains(it) }) {
            return confirmation(name, args, "這個點擊可能會送出、發布、刪除、付款或產生其他不可逆結果。")
        }

        if (name == "tap_coordinates") {
            return confirmation(name, args, "這是座標點擊，無法可靠判斷目標內容，因此需要你確認。")
        }

        return null
    }

    private fun confirmation(name: String, args: JSONObject, reason: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("requires_confirmation", true)
            .put("tool", name)
            .put("arguments", args)
            .put("reason", reason)

    private fun phone() = PhoneControlService.instance

    private fun disabled() = JSONObject()
        .put("ok", false)
        .put("error", "手機控制服務尚未開啟。請到 Android 設定 → 無障礙 → LocalPilot 手機控制 → 開啟。")

    private fun openApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return JSONObject().put("ok", false).put("error", "package 不可為空")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().put("ok", false).put("error", "找不到可啟動的 App：$pkg")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            JSONObject().put("ok", true).put("package", pkg)
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "無法開啟 $pkg：${it.message}")
        }
    }

    private fun saveNote(title: String, content: String): JSONObject {
        if (content.isBlank()) return JSONObject().put("ok", false).put("error", "content 不可為空")
        val notes = loadNotes()
        val note = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("title", title)
            .put("content", content)
            .put("createdAt", ZonedDateTime.now().toString())
        notes.put(note)
        prefs.edit().putString("notes", notes.toString()).apply()
        return JSONObject().put("ok", true).put("saved", note)
    }

    private fun searchNotes(query: String): JSONObject {
        val src = loadNotes()
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val note = src.getJSONObject(i)
            val haystack = "${note.optString("title")} ${note.optString("content")}".lowercase()
            if (haystack.contains(query.lowercase())) out.put(note)
        }
        return JSONObject().put("ok", true).put("query", query).put("matches", out)
    }

    private fun getCurrentTime(): JSONObject {
        val now = ZonedDateTime.now()
        return JSONObject()
            .put("ok", true)
            .put("iso", now.toString())
            .put("display", now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss z")))
    }

    private fun loadNotes(): JSONArray {
        val raw = prefs.getString("notes", null)
        return if (raw.isNullOrBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }
}
