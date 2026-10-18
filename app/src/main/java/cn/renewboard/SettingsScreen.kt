package cn.renewboard

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal fun reminderNotificationSettingsIntent(context: Context): Intent =
    if (appNotificationsAllowed(context)) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, EXPIRY_CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

private enum class SettingsPage(val title: String) {
    HOME("设置"), REMINDERS("到期提醒"), BACKUP("本地备份"), WEBDAV("云端备份"),
    CONNECTION("连接设置"), REMOTE("备份记录"), TUITION("学费同步"), UPDATES("版本更新"), ABOUT("关于订阅簿"), LICENSES("开源许可证")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SettingsScreen(
    l: Ledger,
    message: (String) -> Unit,
    onSubpageChange: (Boolean) -> Unit = {}
) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { CredentialsStore(c) }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.HOME.name) }
    val page = SettingsPage.valueOf(pageName)
    var savingReminder by remember { mutableStateOf(false) }
    fun navigate(next: SettingsPage) { pageName = next.name }
    fun back() {
        if (savingReminder) return
        navigate(when (page) {
        SettingsPage.REMOTE, SettingsPage.CONNECTION -> SettingsPage.WEBDAV
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
    var reminderDays by remember { mutableStateOf(l.settings.reminderDays.toSet()) }
    LaunchedEffect(l.settings.reminderDays, savingReminder) {
        if (!savingReminder) reminderDays = l.settings.reminderDays.toSet()
    }
    var customDay by rememberSaveable { mutableStateOf("") }
    var showCustomDay by rememberSaveable { mutableStateOf(false) }
    var reminderHelp by rememberSaveable { mutableStateOf(false) }
    var reminderError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(config.configured) }
    var status by remember { mutableStateOf(config.status) }
    var success by remember { mutableStateOf(config.lastSuccess) }
    var preview by remember { mutableStateOf<Backup?>(null) }
    var remote by remember { mutableStateOf<List<CloudBackup>?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    var notificationsEnabled by remember { mutableStateOf(notificationsAllowed(c)) }
    fun refreshStatus() {
        notificationsEnabled=notificationsAllowed(c)
        status=config.status;success=config.lastSuccess;configured=config.configured
    }
    DisposableEffect(c, config) {
        val prefs = c.getSharedPreferences("webdav", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf("status", "success", "user", "secret")) {
                status = config.status; success = config.lastSuccess; configured = config.configured
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        status = config.status; success = config.lastSuccess; configured = config.configured
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) refreshStatus() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(page) { refreshStatus() }
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
    fun saveReminderDays(days: Set<Int>, clearCustom: Boolean = false) {
        if (savingReminder) return
        savingReminder = true
        reminderError = null
        scope.launch {
            try {
                c.repository().update { it.copy(settings = it.settings.copy(reminderDays = days.sorted())) }
                reminderDays = days
                if (clearCustom) { customDay = ""; showCustomDay = false }
                message(if (days.isEmpty()) "已关闭到期提醒" else "提醒已保存")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                reminderError = "提醒保存失败，请重试"
            } finally { savingReminder = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) operation {
            withContext(Dispatchers.IO) {
                val text = Book.encode(c.repository().read())
                c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入")
            }
            message("已导出，不包含连接密码、令牌或密钥")
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

    Column(Modifier.fillMaxSize().pageTransition(page).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != SettingsPage.HOME) {
                PageBack(back={back()})
                Spacer(Modifier.width(12.dp))
            }
            Text(page.title, fontSize = if (page == SettingsPage.HOME) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        key(page) {
            if(page == SettingsPage.TUITION) Box(Modifier.weight(1f)) { TuitionSyncScreen(l, title="LessonManager") }
            else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    SettingsPage.HOME -> {
                        SettingsGroup("通用") {
                            SettingsLink("到期提醒", when {
                                !notificationsEnabled -> "通知未允许"
                                l.settings.reminderDays.isEmpty() -> "已关闭"
                                else -> l.settings.reminderDays.sorted().joinToString("、") {if(it==0) "当天" else "提前 $it 天"}
                            }, Icons.Outlined.Notifications, warning=!notificationsEnabled) { navigate(SettingsPage.REMINDERS) }
                        }
                        SettingsGroup("数据") {
                            SettingsLink("本地备份", "", Icons.Outlined.SaveAlt) { navigate(SettingsPage.BACKUP) }
                            SettingsDivider()
                            SettingsLink("云端备份", when {
                                !configured -> "未配置"
                                status=="备份成功" && success.isNotBlank() -> settingsSummaryTime(success)
                                status.isBlank() || status=="尚未备份" -> "尚未备份"
                                else -> "备份失败"
                            }, Icons.Outlined.CloudUpload, warning=configured && status.isNotBlank() && status !in setOf("备份成功","尚未备份")) { navigate(SettingsPage.WEBDAV) }
                            SettingsDivider()
                            SettingsLink("学费同步", if(!(c.applicationContext as RenewApp).tuitionConnection.configured) "未连接"
                                else l.assets.tuitionFetchedAt?.let {settingsSummaryTime(it)} ?: "尚未同步", Icons.Outlined.School) { navigate(SettingsPage.TUITION) }
                        }
                        SettingsGroup("应用") {
                            SettingsLink("检查更新", BuildConfig.VERSION_NAME, Icons.Outlined.SystemUpdate) { navigate(SettingsPage.UPDATES) }
                            SettingsDivider()
                            SettingsLink("关于订阅簿", "", Icons.Outlined.Info) { navigate(SettingsPage.ABOUT) }
                        }
                    }
                    SettingsPage.REMINDERS -> {
                        SettingsPanel {
                            Text("提醒时间",style=MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf(0,1,3,7).forEach { day -> FilterChip(selected=day in reminderDays,
                                    onClick={saveReminderDays(if(day in reminderDays) reminderDays-day else reminderDays+day)}, enabled=!savingReminder,
                                    label={Text(if(day==0) "当天" else "$day 天前",fontSize=13.sp)}) }
                                reminderDays.filter { it !in setOf(0,1,3,7) }.sorted().forEach { day ->
                                    InputChip(selected=true,onClick={saveReminderDays(reminderDays-day)},enabled=!savingReminder,label={Text("提前 $day 天",fontSize=13.sp)},
                                        trailingIcon={Icon(Icons.Outlined.Close,contentDescription="移除提前 $day 天",modifier=Modifier.size(16.dp))})
                                }
                            }
                            TextButton(onClick={showCustomDay=!showCustomDay},enabled=!savingReminder,contentPadding=PaddingValues(0.dp)) {
                                Icon(if(showCustomDay) Icons.Outlined.ExpandLess else Icons.Outlined.Add,null,Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text(if(showCustomDay) "收起自定义" else "自定义")
                            }
                            if(showCustomDay) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Field("自定义提前天数",customDay,{customDay=it;reminderError=null},modifier=Modifier.weight(1f),keyboardType=KeyboardType.Number)
                                OutlinedButton({
                                    val day=customDay.toIntOrNull()
                                    if(day==null || day !in 0..365) reminderError="请输入 0 至 365 天"
                                    else saveReminderDays(reminderDays+day, clearCustom=true)
                                }, enabled=!savingReminder) { Text("添加") }
                            }
                            reminderError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                            if (savingReminder) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(reminderDays.isEmpty()) SettingsNote("到期提醒已关闭。")
                        }
                        Surface(shape=RoundedCornerShape(14.dp),color=Color.White) {
                            SettingsLink("系统通知",if(notificationsEnabled) "已允许" else "去开启",Icons.Outlined.NotificationsActive,warning=!notificationsEnabled) {
                                c.startActivity(reminderNotificationSettingsIntent(c))
                            }
                        }
                        TextButton(onClick={reminderHelp=!reminderHelp}) {Text("收不到提醒？")}
                        if(reminderHelp) SettingsPanel {
                            SettingsNote("请确认已选择提醒时间，并在系统设置中允许订阅簿发送到期通知。系统省电可能延迟通知，可检查应用的后台运行与电池限制。")
                        }
                    }
                    SettingsPage.BACKUP -> {
                        SettingsPanel {
                            Text("保存一份完整备份", style = MaterialTheme.typography.titleMedium)
                            SettingsNote("包含订阅、付款、资产余额与历史、设备和设置。连接密码、令牌与加密密钥不包含在备份中。")
                            Button({ export.launch("订阅簿-${LocalDate.now()}.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出备份") }
                        }
                        SettingsPanel {
                            Text("从备份恢复", style = MaterialTheme.typography.titleMedium)
                            SettingsNote("选择文件后先预览，确认后替换本机数据。")
                            OutlinedButton({ import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复备份") }
                        }
                    }
                    SettingsPage.WEBDAV, SettingsPage.CONNECTION -> {
                        if (page == SettingsPage.CONNECTION || !configured) {
                            SettingsPanel {
                                Text("坚果云 · WebDAV",style=MaterialTheme.typography.titleMedium)
                                Field("WebDAV 地址", url, { url = it })
                                Field("坚果云账号", user, { user = it })
                                Field(if (configured) "应用密码（留空保留）" else "专用应用密码", password, { password = it }, secret = true)
                                SettingsNote("使用专用应用密码，自动保留最近 10 份备份。")
                                Button({
                                    try {
                                        config.save(url.trim(), user.trim(), password); password = ""; configured = config.configured
                                        Jobs.backup(c); message("已保存，将在联网后自动备份")
                                        navigate(SettingsPage.WEBDAV)
                                    } catch (e: Exception) { message(e.message ?: "配置无效") }
                                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存并启用自动备份") }
                            }
                            if (configured) TextButton({ disconnect = true }, enabled = !busy) {
                                Text("断开 WebDAV", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            SettingsPanel {
                                Text("坚果云 · WebDAV", style = MaterialTheme.typography.titleMedium)
                                Text(if(success.isBlank()) "尚未备份" else "最后成功：${settingsDisplayTime(success)}",fontWeight=FontWeight.Medium)
                                if (status.isNotBlank() && status!="尚未备份") Text(status,color=if(status=="备份成功") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,fontSize=13.sp)
                                Button({ operation {
                                    withContext(Dispatchers.IO) {
                                        try { config.client().upload(c.repository().read(), false); config.result() }
                                        catch (e: Exception) { config.result("手动备份失败，请检查网络与应用密码"); throw e }
                                    }
                                    message("手动备份成功，已下载核验")
                                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("立即备份") }
                                SettingsNote("手动备份长期保留。")
                            }
                            Surface(shape=RoundedCornerShape(14.dp),color=Color.White) {
                                Column {
                                SettingsLink("备份记录", "", Icons.Outlined.History,enabled=!busy) {
                                    navigate(SettingsPage.REMOTE)
                                    operation { remote = withContext(Dispatchers.IO) { config.client().list() } }
                                }
                                SettingsDivider()
                                SettingsLink("连接设置", "", Icons.Outlined.Settings,enabled=!busy) {
                                    url = config.url; user = config.user; password = ""
                                    navigate(SettingsPage.CONNECTION)
                                }
                                }
                            }
                        }
                    }
                    SettingsPage.REMOTE -> {
                        SettingsNote("选择备份后预览，确认后恢复。")
                        if (remote.isNullOrEmpty() && !busy) {
                            SettingsPanel {
                                Text(if (remote == null) "尚未读取云端备份" else "专用目录中没有备份")
                                OutlinedButton({ operation { remote = withContext(Dispatchers.IO) { config.client().list() } } }, enabled = !busy) { Text("重新读取") }
                            }
                        }
                        remote?.forEach { backup ->
                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                                TextButton({ operation { preview = withContext(Dispatchers.IO) { config.client().download(backup.name) } } }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(backup.time?.let { settingsDisplayTime(it.toString()) } ?: "时间未知", style = MaterialTheme.typography.titleMedium)
                                        Text(if (backup.automatic) "自动备份" else "手动备份", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    SettingsPage.TUITION -> Unit
                    SettingsPage.UPDATES -> SettingsPanel { UpdateSection(checkOnOpen=true) }
                    SettingsPage.ABOUT -> {
                        SettingsPanel {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("订阅簿", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                SettingsNote("作者 RongGuang233")
                            }
                            SettingsNote("版本 ${BuildConfig.VERSION_NAME}")
                        }
                        SettingsGroup("开源") {
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
            Text("备份日期：${settingsDisplayTime(backup.createdAt)}\n${backup.data.plans.size} 个订阅 · ${backup.data.benefits.size} 项权益\n${backup.data.payments.size} 笔付款 · ${backup.data.devices.size} 台设备\n${Assets.accounts(backup.data).size} 个资产账户\n\n将整体替换本机账本、资产、设备、提醒与汇率。连接凭据不变。可取消后先导出本机备份。")
        }, confirmButton = {
            TextButton({ preview = null; operation { c.repository().restore(backup); message("恢复完成") } }, enabled = !busy) { Text("确认替换") }
        }, dismissButton = { TextButton({ preview = null }) { Text("取消") } })
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("断开 WebDAV？") }, text = {
        Text("删除本机保存的账号和应用密码，停止后续自动备份。本机账本和远端备份不删除。")
    }, confirmButton = { TextButton({
        config.disconnect(); password = ""; user = ""; remote = null; disconnect = false
        status = config.status; success = config.lastSuccess; configured = config.configured
        url = config.url; navigate(SettingsPage.WEBDAV)
        message("已断开")
    }) { Text("断开") } }, dismissButton = { TextButton({ disconnect = false }) { Text("取消") } })
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White) { Column(content = content) }
    }
}

@Composable private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start=58.dp,end=14.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.25f))
}

@Composable private fun SettingsLink(title: String, detail: String, icon: ImageVector, warning:Boolean=false, enabled:Boolean=true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=click).heightIn(min=60.dp).padding(horizontal=14.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Surface(shape=RoundedCornerShape(9.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.06f)) {
            Box(Modifier.size(32.dp),contentAlignment=Alignment.Center) {Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(20.dp))}
        }
        Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
        if(detail.isNotBlank()) Text(detail,fontSize=12.sp,color=if(warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=150.dp))
        Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.65f),modifier=Modifier.size(16.dp))
    }
}

@Composable private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White) {
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

private fun settingsSummaryTime(value:String):String = runCatching {
    val date=java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
    val pattern=when(date.toLocalDate()) {
        LocalDate.now() -> "今天 HH:mm"
        LocalDate.now().minusDays(1) -> "昨天 HH:mm"
        else -> if(date.year==LocalDate.now().year) "M月d日 HH:mm" else "yyyy年M月d日"
    }
    date.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}.getOrDefault("时间未知")
