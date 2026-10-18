package cn.renewboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable private fun TuitionSyncDates(ledger: Ledger) {
    fun display(value: String?) = value?.let {
        runCatching { java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrDefault("未知")
    } ?: "尚未同步"
    Text("来源发布：${display(ledger.assets.tuitionPublishedAt)}", style = MaterialTheme.typography.bodySmall)
    Text("手机读取：${display(ledger.assets.tuitionFetchedAt)}", style = MaterialTheme.typography.bodySmall)
}

// A homepage refresh is an explicit user action; merely opening students never downloads.
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TuitionSyncScreen(
    ledger: Ledger,
    refreshOnOpen: Boolean = false,
    onRefreshConsumed: () -> Unit = {},
    studentContent: @Composable () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as RenewApp
    val store = remember { app.tuitionConnection }
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(store.configured) }
    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var existing by remember { mutableStateOf<TuitionConnection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<TuitionSnapshot?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var disconnect by remember { mutableStateOf(false) }
    var refreshConsumed by rememberSaveable { mutableStateOf(false) }
    fun operation(block: suspend () -> Unit) {
        if(busy) return
        busy = true; message = null
        scope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { message = e.message ?: "操作未完成，请重试" }
            finally { busy = false }
        }
    }
    suspend fun apply(snapshot: TuitionSnapshot, ids: Set<String>) {
        app.repository.update { Assets.importTuition(it, snapshot, ids, Instant.now().toString()) }
        pending = null
        message = "已更新学费余额；银行卡余额请按实际金额单独更新"
    }
    fun refresh() {
        if(!configured) {
            editing = true
            return
        }
        operation {
            val connection = withContext(Dispatchers.IO) { store.read() }
                ?: throw TuitionSyncException("请先填写连接设置")
            val snapshot = app.tuitionQuery.download(connection)
            val newStudents = Assets.newTuitionStudents(ledger, snapshot)
            if(newStudents.isEmpty()) apply(snapshot, emptySet()) else {
                selected = newStudents.filter { it.status == "active" }.map { it.id }.toSet()
                pending = snapshot
            }
        }
    }
    LaunchedEffect(Unit) {
        val shouldRefresh = refreshOnOpen && !refreshConsumed
        if(refreshOnOpen) {
            refreshConsumed = true
            onRefreshConsumed()
        }
        try {
            existing = withContext(Dispatchers.IO) { store.read() }
            url = existing?.url.orEmpty()
            configured = existing != null
            if(shouldRefresh) refresh()
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { message = e.message; editing = true }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AssetCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("学费同步", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editing = true }, enabled = !busy) { Text("连接设置") }
                }
                Text("在 LessonManager 记课并发布后，手动刷新余额。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TuitionSyncDates(ledger)
                Button(onClick = { refresh() }, enabled = !busy && pending == null,
                    modifier = Modifier.fillMaxWidth()) { Text(if(configured) "刷新学费余额" else "连接学费同步") }
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        pending?.let { snapshot ->
            val candidates = Assets.newTuitionStudents(ledger, snapshot)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择要导入的学生", style = MaterialTheme.typography.titleMedium)
                    Text("已关联账户会更新。新学生默认选择在读学生；归档且未结清的余额仍列出。")
                    candidates.forEach { student ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(student.id in selected, onCheckedChange = { checked ->
                                selected = if(checked) selected + student.id else selected - student.id
                            }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(student.name)
                                Text(when(student.status) { "archived" -> "已归档"; "paused" -> "已暂停"; else -> "在读" } +
                                    if(student.verified) "" else " · 待核对", style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(displayMoney("CNY", java.math.BigDecimal.valueOf(student.balanceCents, 2).abs().toPlainString()))
                                Text(when {
                                    student.balanceCents > 0 -> "待上课"
                                    student.balanceCents < 0 -> "待收款"
                                    else -> "已结清"
                                }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("待上课计入负债；待收款计入资产。")
                    Button(onClick = { operation { apply(snapshot, selected) } }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()) { Text("确认导入并更新") }
                    TextButton(onClick = { pending = null }, enabled = !busy) { Text("取消") }
                }
            }
        }
        studentContent()
    }
    if(editing) {
        ModalBottomSheet(onDismissRequest = { if(!busy) { editing = false; token = ""; secret = "" } },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("学费连接设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Field("HTTPS 同步地址", url, { url = it })
                Field("读取令牌", token, { token = it }, secret = true)
                Field("加密密钥", secret, { secret = it }, secret = true)
                Hint(if(existing != null) "令牌和密钥留空可保留已保存值。只保存到本机，不加入 JSON 或 WebDAV 备份。" else
                    "填写电脑发布端的加密密钥和服务器读取令牌。连接信息不加入备份；更换手机后重新连接。")
                Button(onClick = { operation {
                    val connection = TuitionConnection(url.trim(), token.trim().ifEmpty { existing?.token.orEmpty() },
                        secret.trim().ifEmpty { existing?.encryptionKey.orEmpty() })
                    withContext(Dispatchers.IO) { store.save(connection) }
                    existing = connection; configured = true; editing = false; token = ""; secret = ""; pending = null
                    message = "连接已保存，点击刷新读取最新学费余额"
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存连接") }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if(configured) TextButton(onClick = { disconnect = true }, enabled = !busy) { Text("断开学费连接") }
            }
        }
    }
    if(disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("断开学费连接？") },
        text = { Text("清除本机连接信息，已导入的账户和余额仍保留。服务器密文不变。") },
        confirmButton = { TextButton(onClick = { disconnect = false; operation {
            withContext(Dispatchers.IO) { store.disconnect() }
            existing = null; configured = false; editing = true; url = ""; token = ""; secret = ""; pending = null
            message = "已断开连接"
        } }) { Text("断开") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("取消") } })
}
