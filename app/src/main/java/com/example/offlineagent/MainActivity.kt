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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private enum class Tab(val title: String) { AGENT("Agent"), CONTROL("控制"), SETTINGS("設定") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalPilotApp(
    state: AgentUiState,
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
                            if (state.ready) "離線手機 Agent · ${state.runtimeInfo?.backend ?: "本機"}" else "On-device AI Agent",
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
                Tab.AGENT -> AgentPage(state, openModel, onInit, openAccessibility, onSend, onConfirm, onCancel)
                Tab.CONTROL -> ControlPage(state, openAccessibility)
                Tab.SETTINGS -> SettingsPage(state, openModel, onInit)
            }
        }
    }
}

@Composable
private fun AgentPage(
    state: AgentUiState,
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
            if (!state.ready) item { SetupCard(state, onPickModel, onInit, onOpenAccessibility) }
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
                ) { Text(if (state.generating) "Agent 執行中…" else "交給 Agent 執行") }
            }
        }
    }
}

@Composable
private fun StatusCard(state: AgentUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStatus("模型", if (state.ready) "Ready" else "未啟動", state.ready, Modifier.weight(1f))
            MiniStatus("控制", if (state.controlEnabled) "已授權" else "未授權", state.controlEnabled, Modifier.weight(1f))
            MiniStatus("網路", "離線", true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStatus(title: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun SetupCard(state: AgentUiState, onPick: () -> Unit, onInit: () -> Unit, onAccess: () -> Unit) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("完成 3 個步驟開始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("1 · 匯入 Gemma .litertlm", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text(if (state.modelPath == null) "選擇模型" else "重新選擇模型") }
            Text("2 · 啟動 LiteRT-LM", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onInit, enabled = state.modelPath != null && !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.loading) "載入中…" else "啟動 Agent")
            }
            Text("3 · 開啟手機控制", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAccess, modifier = Modifier.fillMaxWidth()) { Text(if (state.controlEnabled) "管理無障礙權限" else "開啟無障礙權限") }
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
                    Box(Modifier.padding(top = 5.dp).size(8.dp).background(
                        when (step.state) {
                            "error" -> MaterialTheme.colorScheme.error
                            "confirm" -> MaterialTheme.colorScheme.tertiary
                            "working" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        }, CircleShape
                    ))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.labelLarge)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("手機控制中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (state.controlEnabled) "AccessibilityService 已連接，可以讀取 UI 並執行操作。" else "尚未授權，Agent 目前不能操作其他 App。")
                    Button(onClick = onAccess, modifier = Modifier.fillMaxWidth()) { Text(if (state.controlEnabled) "管理權限" else "開啟手機控制") }
                }
            }
        }
        item { FeatureCard("可操作", listOf("讀取文字、按鈕與輸入欄", "點擊、輸入、上下滑動", "返回、Home、最近使用、通知欄", "開啟 App 後逐步驗證")) }
        item { FeatureCard("安全閘門", listOf("傳送、發布、刪除、付款、下單先確認", "不代填密碼、PIN、OTP、驗證碼", "座標盲點擊需確認", "不繞過 CAPTCHA 與安全機制")) }
    }
}

@Composable
private fun SettingsPage(state: AgentUiState, onPick: () -> Unit, onInit: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("模型與 Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(state.modelPath?.let { File(it).name } ?: "尚未匯入")
                    if (state.modelSizeBytes > 0) Text("%.2f GB".format(state.modelSizeBytes / 1024.0 / 1024.0 / 1024.0))
                    state.runtimeInfo?.let {
                        HorizontalDivider()
                        Text("Backend：${it.backend}")
                        Text("模式：${if (it.multimodal) "多模態" else "文字"}")
                        Text(it.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("更換模型") }
                    if (state.modelPath != null) Button(onClick = onInit, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("重新啟動 Agent") }
                }
            }
        }
        item { FeatureCard("本機隱私", listOf("Manifest 不宣告 INTERNET 權限", "模型推論完全在手機本機", "Agent 記憶保存在 App 空間", "目前不把畫面內容上傳雲端")) }
    }
}

@Composable
private fun FeatureCard(title: String, lines: List<String>) {
    Card {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            lines.forEach { Text("• $it", modifier = Modifier.padding(vertical = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ErrorBar(error: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClear) { Text("關閉") }
    }
}

@Composable
private fun StatusBadge(active: Boolean) {
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (active) "READY" else "SETUP", style = MaterialTheme.typography.labelSmall)
    }
}
