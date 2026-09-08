package tw.com.tft.aiworkos

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AIClient(private val settings: SecureSettings) {
    data class Result(val ok: Boolean, val content: String, val error: String = "")

    fun analyzeInbox(item: InboxItem, candidates: List<CaseItem>): Result {
        val candidateText = if (candidates.isEmpty()) "無" else candidates.joinToString("\n") {
            "ID=${it.id}｜${it.title}｜${it.company}｜${it.status}｜${it.nextAction}"
        }
        val prompt = """
你是「全益畜牧器具 / 台灣電扇科技有限公司」的工作案件分析器。
任務：把新資訊轉成可執行案件資料。沒有證據就不可宣稱已完成；不確定請降低 confidence。
公司業務流程：確認需求→報價→傳對應型錄→傳產品/安裝照片→電話聯絡解說→持續追蹤→訂單/轉單/出貨。
採購流程：採購單→傳廠商→確認收到→廠商簽名回傳→追生產→確認交期→到貨→清點/驗收/品管→完成。

可能相關的既有案件：
$candidateText

新資料：
來源=${item.source}
群組=${item.groupName}
發訊者=${item.sender}
文字=${item.text}

只輸出 JSON，不要 Markdown：
{
  "source":"LINE/手動/TXT/圖片/通知/其他",
  "group_name":"",
  "sender":"",
  "company":"",
  "role":"客戶/廠商/老闆/員工/未知",
  "case_title":"",
  "case_type":"詢價/報價/採購/廠商追蹤/客戶追蹤/老闆交辦/行政/其他",
  "summary":"",
  "completed_items":[],
  "missing_items":[],
  "next_action":"",
  "assigned_to":"",
  "priority":"urgent/high/medium/low",
  "status":"待處理/處理中/等客戶/等廠商/等老闆/等內部/待追蹤/已完成/暫停/封存",
  "due_date":null,
  "waiting_for":"",
  "confidence":0.0,
  "match_existing_case_id":null,
  "match_confidence":0.0
}
規則：match_existing_case_id 只能從上方既有案件 ID 選；不確定就 null。confidence 範圍 0~1。
        """.trimIndent()
        return chat(prompt, item.attachmentPath, item.mimeType)
    }

    fun generateLine(caseItem: CaseItem, recentMessages: List<String>, mode: String, salutation: String?, date: String): Result {
        val name = salutation?.takeIf { it.isNotBlank() } ?: caseItem.person.ifBlank { caseItem.company }
        val prompt = """
你是全益台灣電扇的 LINE 工作文字助理。請依案件資料產生可直接複製的繁體中文 LINE 訊息。
模式：$mode
案件：${caseItem.title}
對象：${caseItem.company} ${caseItem.person}
稱呼優先：$name
狀態：${caseItem.status}
摘要：${caseItem.summary}
下一步：${caseItem.nextAction}
等待：${caseItem.waitingFor}
最近紀錄：${recentMessages.joinToString("｜")}

若是「向老闆報告」，格式：
向老闆報告：
1.……
2.……
以上報告。
$date.建玄

其他對外訊息：開頭使用「○○老闆 您好！」或資料中指定稱呼；多項內容用 1. 2. 3.；最後「麻煩您了，謝謝您。」下一行「$date.建玄」。
不要自行編造價格、日期、已完成狀態、規格或承諾。
只輸出訊息本體。
        """.trimIndent()
        return chat(prompt, null, null)
    }

    private fun chat(prompt: String, attachmentPath: String?, mimeType: String?): Result {
        val key = settings.getApiKey()
        if (key.isBlank()) return Result(false, "", "尚未設定 API Key")
        val endpoint = endpoint(settings.baseUrl)
        return try {
            val body = JSONObject().put("model", settings.modelName).put("temperature", 0.1)
            val userMessage = JSONObject().put("role", "user")
            if (attachmentPath != null && mimeType?.startsWith("image/") == true) {
                val f = File(attachmentPath)
                if (f.exists() && f.length() <= 5L * 1024L * 1024L) {
                    val data = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    val content = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mimeType;base64,$data")))
                    userMessage.put("content", content)
                } else userMessage.put("content", prompt + "\n（圖片過大或檔案不存在，未附圖）")
            } else userMessage.put("content", prompt)
            body.put("messages", JSONArray().put(userMessage))

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return Result(false, "", "AI API $code：${raw.take(300)}")
            val root = JSONObject(raw)
            val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) Result(false, "", "AI 回傳內容為空") else Result(true, content)
        } catch (e: Exception) {
            Result(false, "", "AI 分析失敗：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun parseJsonObject(text: String): JSONObject? {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(cleaned) } catch (_: Exception) {
            val a = cleaned.indexOf('{'); val b = cleaned.lastIndexOf('}')
            if (a >= 0 && b > a) try { JSONObject(cleaned.substring(a, b + 1)) } catch (_: Exception) { null } else null
        }
    }

    private fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }
}
