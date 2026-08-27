package com.example.offlineagent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject


data class RuntimeInfo(
    val backend: String,
    val multimodal: Boolean,
    val detail: String
)

data class PendingConfirmation(
    val tool: String,
    val argumentsJson: String,
    val reason: String,
    val summary: String
)

data class AgentRunResult(
    val text: String,
    val pendingConfirmation: PendingConfirmation? = null
)

data class AgentActionStep(
    val title: String,
    val detail: String,
    val state: String = "done"
)

class LocalAgent(
    private val context: Context,
    private val modelPath: String
) : AutoCloseable {

    private lateinit var engine: Engine
    private lateinit var conversation: Conversation
    private val tools = AgentTools(context)
    private var pendingTool: ParsedToolCall? = null
    private var pendingReason: String? = null

    var runtimeInfo: RuntimeInfo = RuntimeInfo("尚未啟動", false, "")
        private set

    fun initialize(): RuntimeInfo {
        val cacheDir = context.cacheDir.path
        val attempts = listOf(
            Triple(
                "GPU",
                true,
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    audioBackend = Backend.CPU(),
                    cacheDir = cacheDir
                )
            ),
            Triple(
                "CPU",
                true,
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    audioBackend = Backend.CPU(),
                    cacheDir = cacheDir
                )
            ),
            Triple(
                "CPU",
                false,
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir
                )
            )
        )

        var lastError: Throwable? = null
        for ((backendName, multimodal, config) in attempts) {
            val candidate = Engine(config)
            try {
                candidate.initialize()
                engine = candidate
                runtimeInfo = RuntimeInfo(
                    backend = backendName,
                    multimodal = multimodal,
                    detail = if (multimodal) "文字 + 視覺/音訊執行器已啟動" else "GPU/多模態不相容，已切到 CPU 純文字模式"
                )
                conversation = engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(SYSTEM_PROMPT),
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.9,
                            temperature = 0.25
                        ),
                        automaticToolCalling = false,
                        maxOutputToken = 1024
                    )
                )
                return runtimeInfo
            } catch (t: Throwable) {
                lastError = t
                runCatching { if (candidate.isInitialized()) candidate.close() }
            }
        }

        throw IllegalStateException(
            "LiteRT-LM 初始化失敗：${lastError?.message ?: lastError?.javaClass?.simpleName ?: "未知錯誤"}",
            lastError
        )
    }

    fun run(
        userRequest: String,
        onStep: (AgentActionStep) -> Unit = {}
    ): AgentRunResult {
        require(::conversation.isInitialized) { "Agent 尚未初始化" }
        if (pendingTool != null) {
            return AgentRunResult(
                text = "目前有一個待確認操作，請先選擇確認或取消。",
                pendingConfirmation = currentPendingConfirmation()
            )
        }

        onStep(AgentActionStep("理解任務", userRequest, "working"))
        val first = conversation.sendMessage(userRequest).toString()
        onStep(AgentActionStep("完成理解", "已產生下一步決策"))
        return continueLoop(first, onStep)
    }

    fun confirmPending(
        onStep: (AgentActionStep) -> Unit = {}
    ): AgentRunResult {
        val call = pendingTool
            ?: return AgentRunResult("目前沒有待確認操作。")
        val reason = pendingReason.orEmpty()
        pendingTool = null
        pendingReason = null

        onStep(AgentActionStep("你已確認", summarizeTool(call), "working"))
        val result = tools.execute(call.name, call.arguments, approved = true)
        onStep(
            AgentActionStep(
                if (result.optBoolean("ok")) "敏感操作已執行" else "操作失敗",
                result.optString("error", summarizeTool(call)),
                if (result.optBoolean("ok")) "done" else "error"
            )
        )

        val followUp = toolResultPrompt(result, approved = true, reason = reason)
        val response = conversation.sendMessage(followUp).toString()
        return continueLoop(response, onStep)
    }

    fun cancelPending(): AgentRunResult {
        val call = pendingTool ?: return AgentRunResult("目前沒有待確認操作。")
        pendingTool = null
        pendingReason = null
        val message = "使用者取消了操作：${summarizeTool(call)}。不要再執行這個操作，除非使用者重新要求。"
        runCatching { conversation.sendMessage(message) }
        return AgentRunResult("已取消，沒有執行這個操作。")
    }

    private fun continueLoop(
        initialResponse: String,
        onStep: (AgentActionStep) -> Unit
    ): AgentRunResult {
        var response = initialResponse

        repeat(MAX_TOOL_STEPS) { index ->
            val call = ToolProtocol.parse(response)
                ?: return AgentRunResult(response.cleanToolMarkup().ifBlank { "任務已完成。" })

            onStep(
                AgentActionStep(
                    title = "步驟 ${index + 1} · ${toolTitle(call.name)}",
                    detail = summarizeTool(call),
                    state = "working"
                )
            )

            val result = tools.execute(call.name, call.arguments)
            if (result.optBoolean("requires_confirmation")) {
                pendingTool = call
                pendingReason = result.optString("reason", "這個操作需要確認。")
                val pending = currentPendingConfirmation()
                onStep(AgentActionStep("等待你的確認", pending?.summary.orEmpty(), "confirm"))
                return AgentRunResult(
                    text = "我已準備好下一個動作，但它可能造成外部或不可逆變更，需要你先確認。",
                    pendingConfirmation = pending
                )
            }

            val ok = result.optBoolean("ok", false)
            onStep(
                AgentActionStep(
                    title = if (ok) "已完成" else "工具回報失敗",
                    detail = if (ok) summarizeToolResult(call, result) else result.optString("error", "未知錯誤"),
                    state = if (ok) "done" else "error"
                )
            )

            val followUp = toolResultPrompt(result, approved = false, reason = "")
            response = conversation.sendMessage(followUp).toString()
        }

        return AgentRunResult("為避免無限操作，我已在 $MAX_TOOL_STEPS 個步驟後停止。請檢查目前畫面再繼續。")
    }

    private fun currentPendingConfirmation(): PendingConfirmation? {
        val call = pendingTool ?: return null
        return PendingConfirmation(
            tool = call.name,
            argumentsJson = call.arguments.toString(),
            reason = pendingReason.orEmpty(),
            summary = summarizeTool(call)
        )
    }

    private fun toolResultPrompt(result: JSONObject, approved: Boolean, reason: String): String = """
        工具執行結果：
        ${result}

        ${if (approved) "使用者已明確確認上一個敏感操作。" else ""}
        ${if (reason.isNotBlank()) "原本需要確認的原因：$reason" else ""}

        依照 Observe → Decide → Act → Verify 繼續。
        若剛執行 UI 操作，下一步原則上必須 inspect_screen 驗證結果。
        若任務已完成，直接用繁體中文簡潔回覆，不要再輸出 tool_call。
        若還需要工具，一次只輸出一個 <tool_call>。
    """.trimIndent()

    private fun toolTitle(name: String): String = when (name) {
        "inspect_screen" -> "觀察畫面"
        "open_app" -> "開啟 App"
        "tap" -> "點擊元件"
        "set_text" -> "輸入文字"
        "scroll" -> "滑動畫面"
        "global_action" -> "系統操作"
        "tap_coordinates" -> "座標點擊"
        "save_note" -> "寫入記憶"
        "list_notes" -> "讀取記憶"
        "search_notes" -> "搜尋記憶"
        "get_current_time" -> "取得時間"
        else -> name
    }

    private fun summarizeTool(call: ParsedToolCall): String = when (call.name) {
        "open_app" -> "開啟 ${call.arguments.optString("package")}" 
        "tap" -> "點擊「${call.arguments.optString("selector") }」"
        "set_text" -> "在「${call.arguments.optString("selector") }」輸入文字"
        "scroll" -> "向 ${call.arguments.optString("direction", "down")} 滑動"
        "global_action" -> "執行 ${call.arguments.optString("action") }"
        "tap_coordinates" -> "點擊座標 (${call.arguments.optDouble("x")}, ${call.arguments.optDouble("y")})"
        "inspect_screen" -> "讀取目前 App 的可互動 UI"
        "save_note" -> "儲存「${call.arguments.optString("title") }」"
        "search_notes" -> "搜尋「${call.arguments.optString("query") }」"
        "list_notes" -> "列出本機記憶"
        "get_current_time" -> "取得手機時間"
        else -> "${call.name} ${call.arguments}"
    }

    private fun summarizeToolResult(call: ParsedToolCall, result: JSONObject): String = when (call.name) {
        "inspect_screen" -> "已讀取 ${result.optString("package", "目前 App")} 的畫面結構"
        "tap" -> "已點擊「${call.arguments.optString("selector") }」"
        "set_text" -> "已完成文字輸入"
        "scroll" -> "已完成滑動"
        "open_app" -> "App 已開啟"
        else -> summarizeTool(call)
    }

    override fun close() {
        if (::conversation.isInitialized) runCatching { conversation.close() }
        if (::engine.isInitialized && engine.isInitialized()) runCatching { engine.close() }
    }

    companion object {
        private const val MAX_TOOL_STEPS = 14

        private val SYSTEM_PROMPT = """
            你是 LocalPilot，一個完全離線、在 Android 手機本機執行的 AI Agent。
            你可以在使用者授權後操作手機 App。你的首要目標是「完成任務」，但每一步都必須可觀察、可驗證、可停止。

            固定循環：Observe → Decide → Act → Verify。
            - 操作其他 App 前，先 inspect_screen。
            - 一次只做一個最小操作。
            - 點擊、輸入、滑動、開 App 後，原則上再次 inspect_screen 驗證。
            - 不可以因為你預期畫面會改變，就假裝已經成功。

            可用工具：
            save_note {"title":"標題","content":"內容"}
            list_notes {}
            search_notes {"query":"關鍵字"}
            get_current_time {}
            open_app {"package":"Android package name"}
            inspect_screen {}
            tap {"selector":"畫面上的文字、hint、contentDescription 或 viewId"}
            set_text {"selector":"輸入欄 selector","text":"內容"}
            scroll {"direction":"down"}
            global_action {"action":"back"}
            tap_coordinates {"x":500,"y":900}

            tool 格式只能是：
            <tool_call>{"name":"工具名稱","arguments":{...}}</tool_call>

            重要規則：
            - 不需要工具時，直接用繁體中文回答。
            - 不可以捏造工具結果。
            - 同一操作連續失敗兩次就停止，說明卡在哪裡。
            - 優先使用語意 selector；只有 UI Tree 找不到可靠元件時才考慮 tap_coordinates。
            - 刪除、傳送訊息、寄信、公開發文、付款、購買、下單、轉帳、提交不可逆表單等操作必須等待使用者確認。
            - 不擷取、不保存、不代填密碼、PIN、OTP、驗證碼或金融認證資料。
            - 不繞過 Android 權限、安全警告、CAPTCHA、App 防護或裝置安全機制。
            - 若工具沒有某種能力，就清楚說明不能完成，不要假裝。
        """.trimIndent()
    }
}

data class ParsedToolCall(
    val name: String,
    val arguments: JSONObject
)

object ToolProtocol {
    private val regex = Regex(
        """<tool_call>\s*(\{.*?\})\s*</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(text: String): ParsedToolCall? {
        val match = regex.find(text) ?: return null
        return runCatching {
            val obj = JSONObject(match.groupValues[1])
            ParsedToolCall(
                name = obj.getString("name"),
                arguments = obj.optJSONObject("arguments") ?: JSONObject()
            )
        }.getOrNull()
    }
}

private fun String.cleanToolMarkup(): String =
    replace(Regex("""<tool_call>.*?</tool_call>""", RegexOption.DOT_MATCHES_ALL), "")
        .trim()
