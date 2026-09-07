package cn.renewboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private enum class SettingsPage(val title: String) {
    HOME("设置"), REMINDERS("到期提醒"), BACKUP("本地备份"), WEBDAV("坚果云 · WebDAV"),
    REMOTE("云端备份"), ABOUT("更新与关于"), LICENSES("开源许可证")
}

@Composable internal fun SettingsScreen(
    l: Ledger,
    change: ((Ledger) -> Ledger) -> Unit,
    message: (String) -> Unit,
    onSubpageChange: (Boolean) -> Unit = {}
) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { CredentialsStore(c) }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.HOME.name) }
    val page = SettingsPage.valueOf(pageName)
    fun navigate(next: SettingsPage) { pageName = next.name }
    fun back() { navigate(when (page) {
        SettingsPage.REMOTE -> SettingsPage.WEBDAV
        SettingsPage.LICENSES -> SettingsPage.ABOUT
        else -> SettingsPage.HOME
    }) }
    val currentOnSubpageChange by rememberUpdatedState(onSubpageChange)
    LaunchedEffect(page) { currentOnSubpageChange(page != SettingsPage.HOME) }
    DisposableEffect(Unit) { onDispose { currentOnSubpageChange(false) } }
    BackHandler(page != SettingsPage.HOME) { back() }

    var url by remember { mutableStateOf(config.url) }
    var user by remember { mutableStateOf(config.user) }
    var password by remember { mutableStateOf("") }
    var reminder by remember(l.settings.reminderDays) { mutableStateOf(l.settings.reminderDays.joinToString(",")) }
    var busy by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(config.configured) }
    var status by remember { mutableStateOf(config.status) }
    var success by remember { mutableStateOf(config.lastSuccess) }
    var preview by remember { mutableStateOf<Backup?>(null) }
    var remote by remember { mutableStateOf<List<String>?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        message(if (allowed) "已允许通知" else "通知未授权，订阅和备份仍可使用")
        Jobs.schedule(c)
    }
    fun operation(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message(if (e is DavException) e.message ?: "WebDAV 失败" else "操作失败，请检查文件、网络或应用密码")
            } finally {
                busy = false; status = config.status; success = config.lastSuccess; configured = config.configured
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) operation {
            withContext(Dispatchers.IO) {
                val text = Book.encode(c.repository().read())
                c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入")
            }
            message("已导出，不包含 WebDAV 密码")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            preview = withContext(Dispatchers.IO) {
                val input = c.contentResolver.openInputStream(uri) ?: error("无法读取")
                input.use { Book.decode(String(it.readBackupBytesLimited(), Charsets.UTF_8)) }
            }
        }
    }
    LaunchedEffect(page) {
        if (page == SettingsPage.LICENSES && licenseText == null) operation {
            licenseText = withContext(Dispatchers.IO) {
                c.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                    name + "\n" + c.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != SettingsPage.HOME) {
                FilledTonalButton(onClick = { back() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp)); Text("返回")
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(page.title, fontSize = if (page == SettingsPage.HOME) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        key(page) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    SettingsPage.HOME -> {
                        SettingsGroup("偏好") {
                            SettingsLink("到期提醒", if (l.settings.reminderDays.isEmpty()) "已关闭" else "${l.settings.reminderDays.size} 个提醒时间", Icons.Outlined.Notifications) { navigate(SettingsPage.REMINDERS) }
                        }
                        SettingsGroup("数据") {
                            SettingsLink("本地备份", "导出与恢复", Icons.Outlined.SaveAlt) { navigate(SettingsPage.BACKUP) }
                            SettingsLink("坚果云 · WebDAV", if (configured) "已连接" else "未连接", Icons.Outlined.CloudUpload) { navigate(SettingsPage.WEBDAV) }
                        }
                        SettingsGroup("应用") {
                            SettingsLink("更新与关于", "${BuildConfig.VERSION_NAME}", Icons.Outlined.Info) { navigate(SettingsPage.ABOUT) }
                        }
                    }
                    SettingsPage.REMINDERS -> {
                        SettingsPanel {
                            Field("提前天数，用逗号分隔；0 表示当天", reminder, { reminder = it })
                            SettingsNote("清空可关闭提醒；系统省电可能延迟通知。")
                            Button(onClick = {
                                try {
                                    val days = if (reminder.isBlank()) emptyList() else reminder.replace('，', ',').split(',').map { it.trim().toInt() }
                                    Book.validate(l.copy(settings = l.settings.copy(reminderDays = days)))
                                    change { it.copy(settings = it.settings.copy(reminderDays = days)) }
                                    message("提醒已保存")
                                } catch (e: Exception) { message("请检查提醒天数") }
                            }, modifier = Modifier.fillMaxWidth()) { Text("保存提醒") }
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else message("请在系统应用设置中管理通知授权")
                            }, modifier = Modifier.fillMaxWidth()) { Text("允许到期通知") }
                        }
                    }
                    SettingsPage.BACKUP -> {
                        SettingsPanel {
                            Text("保存一份完整备份", style = MaterialTheme.typography.titleMedium)
                            SettingsNote("包含订阅、付款、设备与设置，不包含 WebDAV 密码。")
                            Button({ export.launch("订阅簿-${LocalDate.now()}.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出 JSON") }
                        }
                        SettingsPanel {
                            Text("从备份恢复", style = MaterialTheme.typography.titleMedium)
                            SettingsNote("选择文件后先预览，确认后替换本机数据。")
                            OutlinedButton({ import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("从文件恢复") }
                        }
                    }
                    SettingsPage.WEBDAV -> {
                        SettingsPanel {
                            Field("WebDAV 地址", url, { url = it })
                            Field("坚果云账号", user, { user = it })
                            Field(if (configured) "应用密码（留空保留）" else "专用应用密码", password, { password = it }, secret = true)
                            SettingsNote("使用专用应用密码，自动保留最近 10 份备份。")
                            Button({
                                try {
                                    config.save(url.trim(), user.trim(), password); password = ""; configured = config.configured
                                    Jobs.backup(c); message("已保存，将在联网后自动备份")
                                } catch (e: Exception) { message(e.message ?: "配置无效") }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存并启用自动备份") }
                        }
                        if (configured) {
                            SettingsPanel {
                                Text("备份状态", style = MaterialTheme.typography.titleMedium)
                                SettingsNote("最后成功：${settingsDisplayTime(success)}")
                                if (status.isNotBlank()) SettingsNote(status)
                                OutlinedButton({ operation {
                                    withContext(Dispatchers.IO) {
                                        try { config.client().upload(c.repository().read(), false); config.result() }
                                        catch (e: Exception) { config.result("手动备份失败，请检查网络与应用密码"); throw e }
                                    }
                                    message("手动备份成功，已下载核验")
                                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("立即备份") }
                                SettingsNote("手动备份长期保留。")
                                OutlinedButton({
                                    navigate(SettingsPage.REMOTE)
                                    operation { remote = withContext(Dispatchers.IO) { config.client().list() } }
                                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("选择云端备份") }
                            }
                            TextButton({ disconnect = true }, enabled = !busy) { Text("断开 WebDAV", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    SettingsPage.REMOTE -> {
                        SettingsNote("选择一份备份，预览后确认恢复。")
                        if (remote.isNullOrEmpty() && !busy) {
                            SettingsPanel {
                                Text(if (remote == null) "尚未读取云端备份" else "专用目录中没有备份")
                                OutlinedButton({ operation { remote = withContext(Dispatchers.IO) { config.client().list() } } }, enabled = !busy) { Text("重新读取") }
                            }
                        }
                        remote?.forEach { name ->
                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                                TextButton({ operation { preview = withContext(Dispatchers.IO) { config.client().download(name) } } }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(name) }
                            }
                        }
                    }
                    SettingsPage.ABOUT -> {
                        SettingsPanel { UpdateSection() }
                        SettingsGroup("关于订阅簿") {
                            SettingsLink("开源许可证", "MIT", Icons.Outlined.Description) { navigate(SettingsPage.LICENSES) }
                        }
                    }
                    SettingsPage.LICENSES -> {
                        SettingsPanel { Text(licenseText ?: "正在读取…", fontSize = 13.sp, lineHeight = 20.sp) }
                    }
                }
            }
        }
    }
    preview?.let { backup ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("确认替换本机数据？") }, text = {
            Text("备份日期：${settingsDisplayTime(backup.createdAt)}\n${backup.data.plans.size} 个订阅 · ${backup.data.benefits.size} 项权益\n${backup.data.payments.size} 笔付款 · ${backup.data.devices.size} 台设备\n\n将整体替换本机账本、设备、提醒与汇率。WebDAV 凭据不变。可取消后先导出本机备份。")
        }, confirmButton = {
            TextButton({ preview = null; operation { c.repository().restore(backup); message("恢复完成") } }, enabled = !busy) { Text("确认替换") }
        }, dismissButton = { TextButton({ preview = null }) { Text("取消") } })
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("断开 WebDAV？") }, text = {
        Text("删除本机保存的账号和应用密码，停止后续自动备份。本机账本和远端备份不删除。")
    }, confirmButton = { TextButton({
        config.disconnect(); password = ""; user = ""; remote = null; disconnect = false
        status = config.status; success = config.lastSuccess; configured = config.configured
        message("已断开")
    }) { Text("断开") } }, dismissButton = { TextButton({ disconnect = false }) { Text("取消") } })
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(content = content)
        }
    }
}

@Composable private fun SettingsLink(title: String, detail: String, icon: ImageVector, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun SettingsNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
}

private fun settingsDisplayTime(value: String) = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)

private fun java.io.InputStream.readBackupBytesLimited(): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; result.write(buffer, 0, n); require(result.size() <= 16 * 1024 * 1024) }
    return result.toByteArray()
}
