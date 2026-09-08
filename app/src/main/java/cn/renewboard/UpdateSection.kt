package cn.renewboard

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun UpdateSection(checkForUpdate: () -> AppUpdate = { Updates.check(BuildConfig.VERSION_NAME) }) {
    val context = LocalContext.current
    val downloads = remember { UpdateDownload(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(downloads.current()) }
    var installWhenReady by rememberSaveable { mutableStateOf(false) }
    fun openInstaller() {
        runCatching { context.startActivity(downloads.installerIntent()) }
            .onFailure { error = it.message ?: "无法打开系统安装界面" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
        else error = "尚未允许安装更新，可点击“安装更新”重试。"
    }
    fun install() {
        error = null
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
        else runCatching {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }.onFailure { error = "请在系统设置中允许订阅簿安装应用后重试。" }
    }
    LaunchedEffect(state?.version, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (state != null) {
                state = withContext(Dispatchers.IO) { downloads.current() }
                if (state?.ready == true) {
                    if (installWhenReady) { installWhenReady = false; install() }
                    break
                }
                if (state?.failed == true) { installWhenReady = false; break }
                delay(1000)
            }
        }
    }
    fun checkUpdate() {
        if(checking) return
        checking = true; error = null
        scope.launch {
            try { update = withContext(Dispatchers.IO) { checkForUpdate() } }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "无法检查更新，请检查网络后重试。"
            } finally { checking = false }
        }
    }
    Text("当前版本 ${BuildConfig.VERSION_NAME}")
    OutlinedButton(onClick = ::checkUpdate, enabled = !checking && state == null) {
        if (checking) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (checking) "正在检查…" else "检查更新")
    }
    state?.let { download ->
        when {
            download.ready -> {
                Text("${download.version} 已下载")
                Button(onClick = { install() }) { Text("安装更新") }
            }
            download.failed -> Text("下载失败或安装包已移除，请重新下载。", color = MaterialTheme.colorScheme.error)
            else -> {
                val progress = if (download.total > 0) (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f) else null
                Text(if (download.status == DownloadManager.STATUS_PAUSED) "等待网络，稍后自动继续…"
                    else if (progress != null) "正在下载 ${(progress * 100).toInt()}%" else "正在准备下载…")
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        TextButton(onClick = {
            downloads.cancel(); state = null; installWhenReady = false; error = null
            if(download.failed) checkUpdate()
        }) {
            Text(if (download.failed) "重新检查更新" else if (download.ready) "删除安装包" else "取消下载")
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    update?.let { result ->
        AlertDialog(onDismissRequest = { update = null },
            title = { Text(if (result.newer) "发现新版本 ${result.version}" else "当前已是最新版本") },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (result.newer) {
                    Text(result.notes.ifBlank { "新版本已发布。" })
                    Spacer(Modifier.height(12.dp))
                    Text(if (result.apkUrl == null) "该版本暂无可下载安装包，请稍后重试。" else "应用内下载，完成后按系统提示安装。")
                } else Text("当前 ${BuildConfig.VERSION_NAME} · 最新发布 ${result.version}")
            } },
            confirmButton = { TextButton(onClick = {
                if (result.newer) {
                    runCatching { downloads.start(result); state = downloads.current(); installWhenReady = true }
                        .onFailure { error = it.message ?: "无法开始下载，请稍后重试。" }
                }
                update = null
            }, enabled = !result.newer || result.apkUrl != null) { Text(if (result.newer) "下载并安装" else "知道了") } },
            dismissButton = { if (result.newer) TextButton(onClick = { update = null }) { Text("稍后") } })
    }
}
