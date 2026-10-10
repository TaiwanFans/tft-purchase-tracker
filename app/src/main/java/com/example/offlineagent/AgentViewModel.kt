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
    val modelInstalling: Boolean = false,
    val modelInstallStatus: String = "not_installed",
    val modelInstallPercent: Int = 0,
    val modelDownloadedBytes: Long = 0,
    val modelTotalBytes: Long = 0,
    val modelInstallDetail: String = "",
    val ready: Boolean = false,
    val loading: Boolean = false,
    val generating: Boolean = false,
    val controlEnabled: Boolean = false,
    val safeMode: Boolean = false,
    val engineWarning: String? = null,
    val runtimeInfo: RuntimeInfo? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val steps: List<AgentActionStep> = emptyList(),
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            "assistant",
            "我是 LocalPilot。現在採安全啟動：App 會先正常開啟，只有你按下啟動後才載入本機 AI。"
        )
    ),
    val error: String? = null
)

class AgentViewModel(
    private val appContext: Context
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences("agent_app", Context.MODE_PRIVATE)
    private val installer = ModelInstaller(appContext)
    private val bootGuard = EngineBootGuard(appContext)

    private val savedModelPath = runCatching { installer.installedModelPath() }.getOrNull()
        ?: runCatching { prefs.getString("model_path", null) }.getOrNull()
            ?.takeIf { path -> runCatching { File(path).exists() && File(path).isFile }.getOrDefault(false) }

    private val previousCrashBackend = bootGuard.crashedBackendIfAny()

    private val _state = MutableStateFlow(
        AgentUiState(
            modelPath = savedModelPath,
            modelSizeBytes = savedModelPath?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L,
            modelInstallStatus = if (savedModelPath != null) "installed" else "not_installed",
            controlEnabled = runCatching { PhoneControlService.instance != null }.getOrDefault(false),
            safeMode = previousCrashBackend != null,
            engineWarning = previousCrashBackend?.let {
                if (it == "GPU") {
                    "上一次 GPU 啟動途中異常結束。這次已進入 CPU 安全模式，避免重複閃退。"
                } else {
                    "上一次 AI 引擎啟動途中異常結束。LocalPilot 已停止自動啟動，請手動重試。"
                }
            }
        )
    )
    val state = _state.asStateFlow()

    private var agent: LocalAgent? = null

    init {
        // v0.5 Safe Boot: 絕不在 App 開啟時自動初始化 LiteRT/GPU。
        // 只允許恢復既有的模型下載監控。
        val activeDownload = runCatching { installer.activeDownloadId() }.getOrNull()
        if (activeDownload != null) {
            viewModelScope.launch { monitorModelInstall(activeDownload) }
        }
    }

    fun refreshSystemState() {
        _state.update {
            it.copy(controlEnabled = runCatching { PhoneControlService.instance != null }.getOrDefault(false))
        }
    }

    fun installModel() {
        if (_state.value.modelInstalling) return

        val installed = runCatching { installer.installedModelPath() }.getOrNull()
        if (installed != null) {
            prefs.edit().putString("model_path", installed).apply()
            _state.update {
                it.copy(
                    modelPath = installed,
                    modelSizeBytes = File(installed).length(),
                    modelInstallStatus = "installed",
                    modelInstallPercent = 100,
                    modelInstallDetail = "AI 模型已安裝，可手動啟動 LocalPilot。",
                    error = null
                )
            }
            return
        }

        viewModelScope.launch {
            runCatching {
                val id = withContext(Dispatchers.IO) { installer.startDownload() }
                monitorModelInstall(id)
            }.onFailure {
                _state.update { current ->
                    current.copy(
                        modelInstalling = false,
                        modelInstallStatus = "failed",
                        error = "AI 模型安裝失敗：${it.message ?: it.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun cancelModelInstall() {
        runCatching { installer.cancel() }
        _state.update {
            it.copy(
                modelInstalling = false,
                modelInstallStatus = "not_installed",
                modelInstallPercent = 0,
                modelDownloadedBytes = 0,
                modelTotalBytes = 0,
                modelInstallDetail = "下載已取消",
                error = null
            )
        }
    }

    private suspend fun monitorModelInstall(downloadId: Long) {
        _state.update {
            it.copy(
                modelInstalling = true,
                modelInstallStatus = "downloading",
                error = null
            )
        }

        runCatching {
            withContext(Dispatchers.IO) {
                installer.waitUntilReady(downloadId) { progress ->
                    _state.update { current ->
                        current.copy(
                            modelInstalling = progress.status != ModelInstaller.STATUS_SUCCESS,
                            modelInstallStatus = progress.status,
                            modelInstallPercent = progress.percent,
                            modelDownloadedBytes = progress.downloadedBytes,
                            modelTotalBytes = progress.totalBytes,
                            modelInstallDetail = progress.detail
                        )
                    }
                }
            }
        }.onSuccess { file ->
            prefs.edit().putString("model_path", file.absolutePath).apply()
            _state.update {
                it.copy(
                    modelPath = file.absolutePath,
                    modelSizeBytes = file.length(),
                    modelInstalling = false,
                    modelInstallStatus = "installed",
                    modelInstallPercent = 100,
                    modelDownloadedBytes = file.length(),
                    modelTotalBytes = file.length(),
                    modelInstallDetail = "AI 模型已安裝完成",
                    messages = it.messages + ChatMessage("assistant", "AI 模型已安裝完成。為了穩定性，請按「啟動 LocalPilot」再載入 AI。")
                )
            }
        }.onFailure { t ->
            _state.update {
                it.copy(
                    modelInstalling = false,
                    modelInstallStatus = "failed",
                    error = "AI 模型安裝失敗：${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
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
                        modelInstallStatus = "installed",
                        modelInstallPercent = 100,
                        loading = false,
                        runtimeInfo = null,
                        messages = it.messages + ChatMessage("assistant", "本機模型已匯入。請按「啟動 LocalPilot」載入 AI。")
                    )
                }
            }.onFailure { fail(it, "模型匯入失敗") }
        }
    }

    fun initialize() {
        val path = _state.value.modelPath ?: return
        if (_state.value.loading || _state.value.ready) return

        val preferredBackend = if (_state.value.safeMode) "CPU" else "GPU"
        // 必須在進 native runtime 前同步落盤；若 native crash，下一次啟動才能偵測。
        bootGuard.begin(preferredBackend)

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, steps = emptyList()) }
            runCatching {
                val info = withContext(Dispatchers.IO) {
                    agent?.close()
                    LocalAgent(
                        context = appContext,
                        modelPath = path,
                        preferredBackend = preferredBackend
                    ).also { agent = it }.initialize()
                }
                bootGuard.success(info.backend)
                _state.update {
                    it.copy(
                        loading = false,
                        ready = true,
                        safeMode = info.backend == "CPU" && preferredBackend == "CPU",
                        runtimeInfo = info,
                        controlEnabled = PhoneControlService.instance != null,
                        engineWarning = if (info.backend == "CPU") "目前使用 CPU 相容模式。" else null,
                        messages = it.messages + ChatMessage(
                            "assistant",
                            "Agent 已啟動：${info.backend} · ${if (info.multimodal) "多模態" else "文字模式"}。你現在可以直接交代手機任務。"
                        )
                    )
                }
            }.onFailure {
                // Kotlin/Java exception 可以正常清掉 marker；只有 native process death 會把 marker 留到下一次。
                bootGuard.clearInProgress()
                fail(it, "Agent 啟動失敗")
            }
        }
    }

    fun retryGpuNextTime() {
        if (_state.value.ready || _state.value.loading) return
        bootGuard.reset()
        _state.update {
            it.copy(
                safeMode = false,
                engineWarning = null,
                error = null
            )
        }
    }

    fun stopAgent() {
        runCatching { agent?.close() }
        agent = null
        bootGuard.clearInProgress()
        _state.update {
            it.copy(
                ready = false,
                loading = false,
                generating = false,
                pendingConfirmation = null,
                runtimeInfo = null,
                steps = emptyList(),
                messages = it.messages + ChatMessage("assistant", "LocalPilot 已停止。你的模型與設定仍保留在手機上。")
            )
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
        runCatching { agent?.close() }
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
