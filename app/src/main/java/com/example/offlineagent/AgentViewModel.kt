package com.example.offlineagent

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


data class ChatMessage(
    val role: String,
    val text: String
)

data class AgentUiState(
    val modelPath: String? = null,
    val modelSizeBytes: Long = 0,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val generating: Boolean = false,
    val controlEnabled: Boolean = false,
    val runtimeInfo: RuntimeInfo? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val steps: List<AgentActionStep> = emptyList(),
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            "assistant",
            "我是 LocalPilot。模型載入後，我可以離線理解任務、讀取手機畫面、操作 App，並在敏感操作前請你確認。"
        )
    ),
    val error: String? = null
)

class AgentViewModel(
    private val appContext: Context
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences("agent_app", Context.MODE_PRIVATE)
    private val savedModelPath = prefs.getString("model_path", null)
    private val _state = MutableStateFlow(
        AgentUiState(
            modelPath = savedModelPath,
            modelSizeBytes = savedModelPath?.let { File(it).takeIf(File::exists)?.length() } ?: 0L,
            controlEnabled = PhoneControlService.instance != null
        )
    )
    val state = _state.asStateFlow()

    private var agent: LocalAgent? = null

    fun refreshSystemState() {
        _state.update { it.copy(controlEnabled = PhoneControlService.instance != null) }
    }

    fun onModelImported(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, ready = false) }
            runCatching {
                val target = withContext(Dispatchers.IO) {
                    val modelDir = File(appContext.filesDir, "models").apply { mkdirs() }
                    val target = File(modelDir, "gemma-localpilot.litertlm")
                    appContext.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "無法讀取模型檔" }
                        target.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
                    }
                    target
                }
                prefs.edit().putString("model_path", target.absolutePath).apply()
                _state.update {
                    it.copy(
                        modelPath = target.absolutePath,
                        modelSizeBytes = target.length(),
                        loading = false,
                        runtimeInfo = null,
                        messages = it.messages + ChatMessage("assistant", "模型已匯入。下一步按「啟動 Agent」載入 LiteRT-LM。")
                    )
                }
            }.onFailure { fail(it, "模型匯入失敗") }
        }
    }

    fun initialize() {
        val path = _state.value.modelPath ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, steps = emptyList()) }
            runCatching {
                val info = withContext(Dispatchers.IO) {
                    agent?.close()
                    LocalAgent(appContext, path).also { agent = it }.initialize()
                }
                _state.update {
                    it.copy(
                        loading = false,
                        ready = true,
                        runtimeInfo = info,
                        controlEnabled = PhoneControlService.instance != null,
                        messages = it.messages + ChatMessage(
                            "assistant",
                            "Agent 已啟動：${info.backend} · ${if (info.multimodal) "多模態" else "文字模式"}。你現在可以直接交代手機任務。"
                        )
                    )
                }
            }.onFailure { fail(it, "Agent 啟動失敗") }
        }
    }

    fun send(text: String) {
        val currentAgent = agent ?: return
        if (_state.value.pendingConfirmation != null) return

        _state.update {
            it.copy(
                generating = true,
                error = null,
                steps = emptyList(),
                messages = it.messages + ChatMessage("user", text)
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentAgent.run(text, ::recordStep)
                }
            }.onSuccess { applyResult(it) }
                .onFailure { fail(it, "Agent 執行失敗", stopGenerating = true) }
        }
    }

    fun confirmPending() {
        val currentAgent = agent ?: return
        if (_state.value.pendingConfirmation == null) return
        _state.update { it.copy(generating = true, error = null) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentAgent.confirmPending(::recordStep)
                }
            }.onSuccess { applyResult(it) }
                .onFailure { fail(it, "確認操作失敗", stopGenerating = true) }
        }
    }

    fun cancelPending() {
        val currentAgent = agent ?: return
        val result = currentAgent.cancelPending()
        _state.update {
            it.copy(
                generating = false,
                pendingConfirmation = null,
                messages = it.messages + ChatMessage("assistant", result.text),
                steps = it.steps + AgentActionStep("已取消", "沒有執行待確認操作", "done")
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun recordStep(step: AgentActionStep) {
        _state.update { current ->
            val old = current.steps
            val normalized = if (step.state != "working" && old.lastOrNull()?.state == "working") {
                old.dropLast(1)
            } else old
            current.copy(steps = (normalized + step).takeLast(14))
        }
    }

    private fun applyResult(result: AgentRunResult) {
        _state.update {
            it.copy(
                generating = false,
                pendingConfirmation = result.pendingConfirmation,
                controlEnabled = PhoneControlService.instance != null,
                messages = if (result.text.isBlank()) it.messages else it.messages + ChatMessage("assistant", result.text)
            )
        }
    }

    private fun fail(t: Throwable, prefix: String, stopGenerating: Boolean = false) {
        _state.update {
            it.copy(
                loading = false,
                generating = if (stopGenerating) false else it.generating,
                ready = if (prefix.contains("啟動")) false else it.ready,
                error = "$prefix：${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    override fun onCleared() {
        agent?.close()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AgentViewModel(context.applicationContext) as T
                }
            }
    }
}
