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

private enum class Tab(val title: String) {
    AGENT("Agent"), CONTROL("控制"), SETTINGS("設定")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalPilotApp(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onCancelModelInstall: () -> Unit,
    onPickModel: (Uri) -> Unit,
    onInit: () -> Unit,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.AGENT) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickModel(uri)
    }
    val openModel = { picker.launch(arrayOf("*/*")) }
    val openAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LocalPilot", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                state.ready -> "離線手機 Agent · ${state.runtimeInfo?.backend ?: "本機"}"
                                state.modelInstalling -> "正在安裝 AI 模型"
                                state.loading -> "正在啟動 AI"
                                else -> "Personal On-device Agent"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = { StatusBadge(state.ready && state.controlEnabled) }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(if (tab == item) "●" else "○") },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBar(it, onClearError) }
            when (tab) {
                Tab.AGENT -> AgentPage(
                    state = state,
                    onInstallModel = onInstallModel,
                    onCancelModelInstall = onCancelModelInstall,
                    onPickModel = openModel,
                    onInit = onInit,
                    onOpenAccessibility = openAccessibility,
                    onSend = onSend,
                    onConfirm = onConfirm,
                    onCancel = onCancel
                )
                Tab.CONTROL -> ControlPage(state, openAccessibility)
                Tab.SETTINGS -> SettingsPage(state, onInstallModel, openModel, onInit)
            }
        }
    }
}

@Composable
private fun AgentPage(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onCancelModelInstall: () -> Unit,
    onPickModel: () -> Unit,
    onInit: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(state) }

            if (!state.ready || !state.controlEnabled || state.modelInstalling) {
                item {
                    FirstRunCard(
                        state = state,
                        onInstallModel = onInstallModel,
                        onCancelModelInstall = onCancelModelInstall,
                        onPickModel = onPickModel,
                        onInit = onInit,
                        onOpenAccessibility = onOpenAccessibility
                    )
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
                    placeholder = { Text("例如：打開設定，幫我找到藍牙") },
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
private fun StatusCard(state: AgentUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniStatus("AI", when {
                state.ready -> "Ready"
                state.modelInstalling -> "安裝中"
                state.modelPath != null -> "啟動中"
                else -> "未安裝"
            }, state.ready, Modifier.weight(1f))
            MiniStatus("控制", if (state.controlEnabled) "已授權" else "未授權", state.controlEnabled, Modifier.weight(1f))
            MiniStatus("推論", "本機", state.modelPath != null, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStatus(title: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun FirstRunCard(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onCancelModelInstall: () -> Unit,
    onPickModel: () -> Unit,
    onInit: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("第一次設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "不用找模型檔。LocalPilot 會自己安裝 AI，完成後就能離線使用。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                state.modelInstalling -> {
                    Text("1 · 正在安裝 ${ModelInstaller.MODEL_NAME}", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.modelInstallPercent}% · ${formatBytes(state.modelDownloadedBytes)} / ${formatBytes(state.modelTotalBytes)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        state.modelInstallDetail.ifBlank { "約 3 GB，下載完成後會自動驗證並啟動。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onCancelModelInstall, modifier = Modifier.fillMaxWidth()) {
                        Text("取消下載")
                    }
                }

                state.modelPath == null -> {
                    Text("1 · 安裝 AI 模型", fontWeight = FontWeight.SemiBold)
                    Button(onClick = onInstallModel, modifier = Modifier.fillMaxWidth()) {
                        Text("安裝 AI 模型 · 約 3 GB")
                    }
                    Text(
                        "第一次需要網路，建議使用 Wi‑Fi。下載一次之後，AI 推論不需要網路。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onPickModel, modifier = Modifier.align(Alignment.End)) {
                        Text("進階：我已經有 .litertlm")
                    }
                }

                state.loading -> {
                    Text("1 · AI 模型已安裝", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在自動啟動 LocalPilot…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                !state.ready -> {
                    Text("1 · AI 模型已安裝", fontWeight = FontWeight.SemiBold)
                    Button(onClick = onInit, modifier = Modifier.fillMaxWidth()) { Text("重新啟動 Agent") }
                }

                else -> {
                    Text("✓ AI 已準備完成", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider()

            Text("2 · 開啟手機控制", fontWeight = FontWeight.SemiBold)
            if (state.controlEnabled) {
                Text("✓ 手機控制已授權", color = MaterialTheme.colorScheme.secondary)
            } else {
                OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text("開啟手機控制權限")
                }
                Text(
                    "Android 規定這個權限一定要由你親自開啟，App 不能代替你按。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(steps: List<AgentActionStep>) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agent 操作時間軸", fontWeight = FontWeight.Bold)
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
private fun ControlPage(state: AgentUiState, onAccess: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("手機控制中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (state.controlEnabled) "AccessibilityService 已連接，可以讀取 UI 並執行操作。"
                        else "尚未授權，Agent 目前不能操作其他 App。"
                    )
                    Button(onClick = onAccess, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.controlEnabled) "管理手機控制權限" else "開啟手機控制")
                    }
                }
            }
        }
        item { FeatureCard("可以做", listOf("讀取畫面文字與按鈕", "點擊、輸入、滑動", "返回、Home、最近使用", "逐步確認操作結果")) }
        item { FeatureCard("安全閘門", listOf("傳送、發布、刪除、付款、下單前先確認", "不代填密碼、PIN、OTP、驗證碼", "不繞過 CAPTCHA 或系統安全機制")) }
    }
}

@Composable
private fun SettingsPage(
    state: AgentUiState,
    onInstallModel: () -> Unit,
    onPick: () -> Unit,
    onInit: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("AI 模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(state.modelPath?.let { File(it).name } ?: "尚未安裝")
                    if (state.modelSizeBytes > 0) Text(formatBytes(state.modelSizeBytes))
                    state.runtimeInfo?.let {
                        HorizontalDivider()
                        Text("Backend：${it.backend}")
                        Text("模式：${if (it.multimodal) "多模態" else "文字"}")
                        Text(it.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.modelPath == null && !state.modelInstalling) {
                        Button(onClick = onInstallModel, modifier = Modifier.fillMaxWidth()) { Text("安裝官方 AI 模型") }
                    }
                    TextButton(onClick = onPick, modifier = Modifier.align(Alignment.End)) { Text("進階：手動匯入模型") }
                    if (state.modelPath != null && !state.ready && !state.loading) {
                        Button(onClick = onInit, modifier = Modifier.fillMaxWidth()) { Text("重新啟動 Agent") }
                    }
                }
            }
        }
        item {
            FeatureCard(
                "離線與隱私",
                listOf(
                    "第一次安裝模型需要網路",
                    "模型安裝完成後，AI 推論可完全在手機本機執行",
                    "Agent 記憶保存在 App 自己的空間",
                    "模型檔不會丟到公開下載資料夾"
                )
            )
        }
    }
}

@Composable
private fun FeatureCard(title: String, lines: List<String>) {
    Card {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            lines.forEach {
                Text("• $it", modifier = Modifier.padding(vertical = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorBar(error: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClear) { Text("關閉") }
    }
}

@Composable
private fun StatusBadge(active: Boolean) {
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).background(
                if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                CircleShape
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(if (active) "READY" else "SETUP", style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(bytes / 1024.0 / 1024.0)
}
