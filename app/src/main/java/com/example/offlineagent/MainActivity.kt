package com.example.offlineagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels { AgentViewModel.factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalPilotTheme {
                val state by vm.state.collectAsState()
                LocalPilotApp(
                    state = state,
                    onInstallModel = vm::installModel,
                    onCancelModelInstall = vm::cancelModelInstall,
                    onPickModel = vm::onModelImported,
                    onInit = vm::initialize,
                    onRetryGpu = vm::retryGpuNextTime,
                    onStop = vm::stopAgent,
                    onSend = vm::send,
                    onConfirm = vm::confirmPending,
                    onCancel = vm::cancelPending,
                    onClearError = vm::clearError
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshSystemState()
    }
}

private enum class Tab(val title: String, val mark: String) {
    HOME("首頁", "⌂"),
    AGENT("Agent", "✦"),
    CONTROL("控制", "◎"),
    SETTINGS("設定", "⚙")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalPilotApp(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onCancelModelInstall: () -> Unit,
    onPickModel: (Uri) -> Unit,
    onInit: () -> Unit,
    onRetryGpu: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickModel(uri)
    }
    val openModel = { picker.launch(arrayOf("*/*")) }
    val openAccessibility = {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(38)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("LocalPilot", fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    state.ready -> "本機 AI 已啟動 · ${state.runtimeInfo?.backend ?: "Local"}"
                                    state.safeMode -> "安全模式 · 等待手動啟動"
                                    state.modelInstalling -> "正在安裝 AI 模型"
                                    state.modelPath != null -> "AI 已安裝 · 尚未啟動"
                                    else -> "離線手機 AI Agent"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    StatusBadge(state.ready && state.controlEnabled)
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.mark) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBar(it, onClearError) }

            when (tab) {
                Tab.HOME -> HomePage(
                    state = state,
                    onInstallModel = onInstallModel,
                    onCancelModelInstall = onCancelModelInstall,
                    onInit = onInit,
                    onRetryGpu = onRetryGpu,
                    onOpenAccessibility = openAccessibility,
                    onOpenAgent = { tab = Tab.AGENT }
                )
                Tab.AGENT -> AgentPage(
                    state = state,
                    onSend = onSend,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                    onInit = onInit,
                    onOpenAccessibility = openAccessibility
                )
                Tab.CONTROL -> ControlPage(state, openAccessibility, onStop)
                Tab.SETTINGS -> SettingsPage(
                    state = state,
                    onInstallModel = onInstallModel,
                    onPickModel = openModel,
                    onInit = onInit,
                    onRetryGpu = onRetryGpu,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun BrandMark(size: Int) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("✦", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HomePage(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onCancelModelInstall: () -> Unit,
    onInit: () -> Unit,
    onRetryGpu: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAgent: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(54)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("你的本機手機 AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "看懂畫面、操作 App、完成任務",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "LocalPilot 把 AI 模型放在手機本機。敏感操作會先停下來請你確認。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item { StatusCard(state) }

        state.engineWarning?.let { warning ->
            item {
                RecoveryCard(
                    warning = warning,
                    safeMode = state.safeMode,
                    onInit = onInit,
                    onRetryGpu = onRetryGpu,
                    modelReady = state.modelPath != null,
                    loading = state.loading
                )
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("開始使用", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "只要完成下面 3 步。之後每次打開 App 都先進安全首頁，不會自動碰 GPU。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SetupStep(
                        number = "1",
                        title = "安裝 AI 模型",
                        detail = if (state.modelPath != null) "Gemma 4 E4B 已安裝" else "第一次需要下載約 3.66 GB",
                        done = state.modelPath != null
                    )

                    if (state.modelInstalling) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "${state.modelInstallPercent}% · ${formatBytes(state.modelDownloadedBytes)} / ${formatBytes(state.modelTotalBytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            state.modelInstallDetail.ifBlank { "正在下載與驗證 AI 模型…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onCancelModelInstall, modifier = Modifier.fillMaxWidth()) {
                            Text("取消下載")
                        }
                    } else if (state.modelPath == null) {
                        Button(onClick = onInstallModel, modifier = Modifier.fillMaxWidth()) {
                            Text("安裝 AI 模型")
                        }
                    }

                    HorizontalDivider()

                    SetupStep(
                        number = "2",
                        title = "開啟手機控制",
                        detail = if (state.controlEnabled) "Android 無障礙控制已授權" else "需要你親自在系統設定開啟",
                        done = state.controlEnabled
                    )
                    if (!state.controlEnabled) {
                        OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                            Text("前往開啟手機控制")
                        }
                    }

                    HorizontalDivider()

                    SetupStep(
                        number = "3",
                        title = "啟動 LocalPilot",
                        detail = when {
                            state.ready -> "AI 引擎已啟動"
                            state.safeMode -> "將使用 CPU 安全模式"
                            state.modelPath != null -> "準備好後手動啟動"
                            else -> "請先安裝 AI 模型"
                        },
                        done = state.ready
                    )

                    if (state.modelPath != null && !state.ready) {
                        Button(
                            onClick = onInit,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.loading
                        ) {
                            Text(if (state.loading) "正在啟動…" else if (state.safeMode) "使用 CPU 安全啟動" else "啟動 LocalPilot")
                        }
                    }

                    if (state.ready) {
                        Button(onClick = onOpenAgent, modifier = Modifier.fillMaxWidth()) {
                            Text("開始交代任務")
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("可以先試這些", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("• 打開設定，幫我找到藍牙")
                    Text("• 看看目前畫面有哪些可以點的選項")
                    Text("• 回到首頁")
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, detail: String, done: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else number, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecoveryCard(
    warning: String,
    safeMode: Boolean,
    onInit: () -> Unit,
    onRetryGpu: () -> Unit,
    modelReady: Boolean,
    loading: Boolean
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("安全模式已啟用", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(warning)
            if (modelReady && !loading) {
                Button(onClick = onInit, modifier = Modifier.fillMaxWidth()) {
                    Text(if (safeMode) "使用 CPU 安全啟動" else "啟動 AI")
                }
            }
            OutlinedButton(onClick = onRetryGpu, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                Text("清除安全模式，下次重試 GPU")
            }
        }
    }
}

@Composable
private fun AgentPage(
    state: AgentUiState,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onInit: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(state) }

            if (!state.ready) {
                item {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("AI 尚未啟動", fontWeight = FontWeight.Bold)
                            Text(
                                if (state.modelPath == null) "請先回首頁安裝 AI 模型。" else "為避免閃退，v0.5 不會自動啟動 AI。請手動啟動。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.modelPath != null) {
                                Button(onClick = onInit, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                                    Text(if (state.loading) "正在啟動…" else "啟動 LocalPilot")
                                }
                            }
                        }
                    }
                }
            }

            if (!state.controlEnabled) {
                item {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("手機控制尚未授權", fontWeight = FontWeight.Bold)
                            Text("聊天可以使用，但要操作其他 App 需要開啟 Android 無障礙權限。")
                            OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                                Text("前往開啟手機控制")
                            }
                        }
                    }
                }
            }

            if (state.steps.isNotEmpty()) item { TimelineCard(state.steps) }
            items(state.messages) { ChatBubble(it) }
            state.pendingConfirmation?.let { pending ->
                item { ConfirmationCard(pending, onConfirm, onCancel) }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.ready && !state.generating && state.pendingConfirmation == null,
                    placeholder = { Text("告訴 LocalPilot 你想在手機上完成什麼") },
                    maxLines = 4
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val task = input.trim()
                        if (task.isNotEmpty()) {
                            input = ""
                            onSend(task)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.ready && !state.generating && state.pendingConfirmation == null && input.isNotBlank()
                ) {
                    Text(if (state.generating) "Agent 執行中…" else "交給 Agent 執行")
                }
            }
        }
    }
}

@Composable
private fun ControlPage(state: AgentUiState, onAccess: () -> Unit, onStop: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("手機控制中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    MiniStatus("無障礙控制", if (state.controlEnabled) "已授權" else "未授權", state.controlEnabled)
                    if (!state.controlEnabled) {
                        Button(onClick = onAccess, modifier = Modifier.fillMaxWidth()) {
                            Text("開啟手機控制權限")
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Agent 可以做", fontWeight = FontWeight.Bold)
                    Text("• 讀取目前 App 的可互動畫面")
                    Text("• 點擊按鈕與選項")
                    Text("• 在一般輸入欄輸入文字")
                    Text("• 滑動、返回、Home、最近使用")
                    Text("• 開啟其他 App")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("安全限制", fontWeight = FontWeight.Bold)
                    Text("密碼、PIN、OTP 與驗證碼不會自動代填。")
                    Text("傳送、刪除、付款、下單等敏感操作會先要求你確認。")
                }
            }
        }
        if (state.ready) {
            item {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("停止 LocalPilot")
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onPickModel: () -> Unit,
    onInit: () -> Unit,
    onRetryGpu: () -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (state.modelPath != null) ModelInstaller.MODEL_NAME else "尚未安裝")
                    if (state.modelPath != null) {
                        Text(formatBytes(state.modelSizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            File(state.modelPath).name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.modelPath == null) {
                        Button(onClick = onInstallModel, modifier = Modifier.fillMaxWidth()) { Text("安裝 AI 模型") }
                    }
                    TextButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                        Text("進階：手動匯入 .litertlm")
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("目前狀態：${if (state.ready) "已啟動" else "未啟動"}")
                    Text("執行後端：${state.runtimeInfo?.backend ?: if (state.safeMode) "CPU 安全模式" else "GPU 優先"}")
                    state.runtimeInfo?.let {
                        Text(it.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!state.ready && state.modelPath != null) {
                        Button(onClick = onInit, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                            Text(if (state.loading) "正在啟動…" else "啟動 AI")
                        }
                    }
                    OutlinedButton(onClick = onRetryGpu, modifier = Modifier.fillMaxWidth(), enabled = !state.loading && !state.ready) {
                        Text("清除安全模式 / 重試 GPU")
                    }
                    if (state.ready) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                            Text("停止 AI")
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("LocalPilot v0.5", fontWeight = FontWeight.Bold)
                    Text("Safe Boot Edition", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("LiteRT-LM 0.14.0 · arm64-v8a", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: AgentUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniStatus(
                "AI 模型",
                if (state.modelPath != null) "已安裝" else if (state.modelInstalling) "安裝中" else "未安裝",
                state.modelPath != null,
                Modifier.weight(1f)
            )
            MiniStatus(
                "手機控制",
                if (state.controlEnabled) "已授權" else "未授權",
                state.controlEnabled,
                Modifier.weight(1f)
            )
            MiniStatus(
                "AI 引擎",
                if (state.ready) state.runtimeInfo?.backend ?: "Ready" else if (state.safeMode) "Safe" else "Off",
                state.ready,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiniStatus(title: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).background(
                    if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    CircleShape
                )
            )
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun TimelineCard(steps: List<AgentActionStep>) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agent 操作中", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("Observe → Act → Verify", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            steps.takeLast(6).forEach { step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.padding(top = 5.dp).size(8.dp).background(
                            when (step.state) {
                                "error" -> MaterialTheme.colorScheme.error
                                "confirm" -> MaterialTheme.colorScheme.tertiary
                                "working" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.secondary
                            },
                            CircleShape
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.labelLarge)
                        Text(
                            step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(if (user) 0.82f else 0.94f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(if (user) "你" else "LocalPilot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(message.text)
            }
        }
    }
}

@Composable
private fun ConfirmationCard(pending: PendingConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要你的確認", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(pending.summary)
            Text(pending.reason, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("確認執行") }
            }
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("關閉") }
        }
    }
}

@Composable
private fun StatusBadge(ready: Boolean) {
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            if (ready) "READY" else "SAFE",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "等待取得大小"
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.0f MB", bytes / 1_000_000.0)
}
