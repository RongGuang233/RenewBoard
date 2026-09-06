package cn.renewboard

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File

/** One system-managed APK download, retained across screen/process restarts. */
class UpdateDownload(private val context: Context, private val currentVersion: String = BuildConfig.VERSION_NAME) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app-update", Context.MODE_PRIVATE)
    data class State(val version: String, val status: Int, val downloaded: Long = 0, val total: Long = -1) {
        val ready get() = status == DownloadManager.STATUS_SUCCESSFUL
        val failed get() = status == DownloadManager.STATUS_FAILED
    }
    fun current(): State? {
        val id = prefs.getLong("id", -1)
        if (id == -1L) return null
        val version = prefs.getString("version", "").orEmpty()
        // The installed update no longer needs its downloaded APK.
        if (!Updates.isNewer(version, currentVersion)) { cancel(); return null }
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) return State(version,
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
        }
        return State(version, DownloadManager.STATUS_FAILED)
    }
    fun start(update: AppUpdate) {
        val url = requireNotNull(update.apkUrl) { "该版本暂无可下载安装包" }
        require(Updates.isNewer(update.version, currentVersion)) { "当前已是最新版本" }
        cancel()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("订阅簿 ${update.version}")
            .setDescription("正在下载更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "RenewBoard-update.apk")
        val id = manager.enqueue(request)
        prefs.edit().putLong("id", id).putString("version", update.version).apply()
    }
    fun cancel() {
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "RenewBoard-update.apk").delete() }
        prefs.edit().clear().apply()
    }
    fun installerIntent(): Intent {
        check(current()?.ready == true) { "安装包尚未下载完成，请重新下载" }
        val uri = manager.getUriForDownloadedFile(prefs.getLong("id", -1))
            ?: error("安装包已被移除，请重新下载")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
