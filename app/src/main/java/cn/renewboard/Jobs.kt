package cn.renewboard

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialsStore(private val c: Context) {
    private val prefs = c.getSharedPreferences("webdav", Context.MODE_PRIVATE)
    val url get() = prefs.getString("url", "https://dav.jianguoyun.com/dav/")!!
    val user get() = prefs.getString("user", "")!!
    val configured get() = user.isNotBlank() && prefs.contains("secret")
    val status get() = prefs.getString("status", "尚未备份")!!
    val lastSuccess get() = prefs.getString("success", "尚无成功备份")!!
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("renewboard.webdav",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("renewboard.webdav",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(url: String, user: String, password: String) {
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null) { "请输入不含账号信息的 HTTPS WebDAV 地址" }
        require(user.isNotBlank()) { "请填写账号" }
        if(password.isBlank()) require(configured && this.url == url && this.user == user) { "请填写专用应用密码" }
        val edit = prefs.edit().putString("url",url).putString("user",user)
        if(this.url != url || this.user != user) edit.remove("backed")
        if(password.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
            edit.putString("secret",Base64.encodeToString(cipher.doFinal(password.toByteArray()),Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
        }
        edit.apply()
    }
    fun disconnect() { prefs.edit().clear().apply(); WorkManager.getInstance(c).cancelUniqueWork("backup-changes") }
    fun isBackedUp(signature: String) = prefs.getString("backed", null) == signature
    fun markBackedUp(signature: String) { prefs.edit().putString("backed", signature).apply() }
    fun client(): WebDav {
        check(configured) { "请先配置 WebDAV" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv", ""),Base64.NO_WRAP))) }
        val password = String(cipher.doFinal(Base64.decode(prefs.getString("secret", ""),Base64.NO_WRAP)))
        return WebDav(url,user,password)
    }
    fun result(error: String? = null) { prefs.edit().putString("status",error ?: "备份成功").apply(); if(error == null) prefs.edit().putString("success",Instant.now().toString()).apply() }
}
object Jobs {
    val backupMutex = Mutex()
    private const val SNOOZE_EVENT_TAG = "snooze-event:"
    fun schedule(c: Context) {
        val work = WorkManager.getInstance(c)
        work.enqueueUniquePeriodicWork("reminders",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ReminderWorker>(6,TimeUnit.HOURS).build())
        work.enqueueUniquePeriodicWork("backup-periodic",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<BackupWorker>(12,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        work.enqueueUniqueWork("reminder-now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }
    fun detailIntent(c:Context,planId:String,action:String="open"):PendingIntent {
        val intent=Intent(c,MainActivity::class.java).setData(android.net.Uri.parse("renewboard://subscription/${android.net.Uri.encode(planId)}/$action"))
            .putExtra("planId",planId).putExtra("subscriptionAction",action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun snoozeData(ledger: Ledger, planId: String, today: LocalDate = LocalDate.now()) = workDataOf(
        "planId" to planId, "eventKeys" to ReminderPolicy.snoozeTargets(ledger, planId, today).map { it.key }.toTypedArray())
    fun snooze(c:Context,planId:String,ledger:Ledger,today:LocalDate=LocalDate.now()): Operation {
        val data = snoozeData(ledger, planId, today)
        val request = OneTimeWorkRequestBuilder<SnoozeReminderWorker>().setInitialDelay(1,TimeUnit.DAYS)
            .setInputData(data)
        data.getStringArray("eventKeys").orEmpty().forEach { request.addTag("$SNOOZE_EVENT_TAG$it") }
        return WorkManager.getInstance(c).enqueueUniqueWork("snooze-$planId",ExistingWorkPolicy.REPLACE,
            request.build())
    }
    /** Call after a successful ledger write; delivery workers independently recheck their bound events. */
    fun clearResolvedReminders(c: Context, before: Ledger, after: Ledger) {
        val oldEvents = ReminderPolicy.events(before)
        val valid = ReminderPolicy.unresolved(after, oldEvents.map { it.key }).map { it.key }.toSet()
        val resolved = oldEvents.filter { it.key !in valid }
        if (resolved.isEmpty()) return
        val work = WorkManager.getInstance(c)
        resolved.map { it.planId }.distinct().forEach { id ->
            val pending = work.getWorkInfosForUniqueWork("snooze-$id")
            pending.addListener({
                // The future is complete here. Inspect the original bound events, not a
                // different benefit expiry created by an earlier partial renewal.
                pending.get().filterNot { it.state.isFinished }.forEach { info ->
                    val bound = info.tags.filter { it.startsWith(SNOOZE_EVENT_TAG) }.map { it.removePrefix(SNOOZE_EVENT_TAG) }
                    if (bound.isNotEmpty() && ReminderPolicy.unresolved(after, bound).isEmpty())
                        work.cancelWorkById(info.id)
                }
            }, ContextCompat.getMainExecutor(c))
        }
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.forEach { active ->
            val keys = active.notification.extras.getStringArray("eventKeys")?.toList()
            if (keys != null) {
                val remaining = ReminderPolicy.unresolved(after, keys)
                if (remaining.size != keys.size) {
                    if (remaining.isEmpty()) manager.cancel(active.tag, active.id)
                    else {
                        val extras = android.os.Bundle(active.notification.extras).apply {
                            putStringArray("eventKeys", remaining.map { it.key }.toTypedArray())
                        }
                        val builder = Notification.Builder.recoverBuilder(c, active.notification)
                            .setContentText(ReminderPolicy.summary(remaining)).setExtras(extras)
                            .setOnlyAlertOnce(true).setContentIntent(detailIntent(c, remaining.first().planId))
                            .setStyle(Notification.BigTextStyle().bigText(ReminderPolicy.summary(remaining)))
                        if (active.tag == "reminder-catchup") builder.setContentTitle("有 ${remaining.size} 项近期提醒待处理")
                        try { manager.notify(active.tag, active.id, builder.build()) } catch (_: SecurityException) {}
                    }
                }
            } else if (resolved.any { active.tag?.startsWith("${it.key}:") == true } ||
                resolved.any { active.tag == "snooze-${it.planId}" && oldEvents.none { e -> e.planId == it.planId && e.key in valid } }) {
                manager.cancel(active.tag, active.id)
            }
        }
    }
    fun backup(c: Context) {
        if(!CredentialsStore(c).configured) return
        WorkManager.getInstance(c).enqueueUniqueWork("backup-changes",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(30,TimeUnit.SECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
class BackupWorker(c: Context, p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = CredentialsStore(applicationContext)
        if(!config.configured) return@withContext Result.success()
        try {
            Jobs.backupMutex.withLock {
                val ledger = applicationContext.repository().read()
                val signature = java.security.MessageDigest.getInstance("SHA-256").digest(Book.json.encodeToString(ledger).toByteArray()).joinToString("") { "%02x".format(it) }
                if(!config.isBackedUp(signature)) { config.client().upload(ledger,true); config.markBackedUp(signature); config.result() }
            }; Result.success()
        }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; config.result(if(e is DavException) e.message else "备份失败，请检查网络与应用密码后重试"); Result.retry() }
    }
}
private fun notificationsAllowed(c: Context, manager: NotificationManagerCompat): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!manager.areNotificationsEnabled()) return false
    manager.createNotificationChannel(NotificationChannel("expiry", "到期提醒", NotificationManager.IMPORTANCE_DEFAULT))
    return c.getSystemService(NotificationManager::class.java).getNotificationChannel("expiry")?.importance != NotificationManager.IMPORTANCE_NONE
}

private fun eventExtras(events: List<ReminderEvent>) = android.os.Bundle().apply {
    putStringArray("eventKeys", events.map { it.key }.toTypedArray())
}

class ReminderWorker(c: Context, p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = deliver(LocalDate.now())

    // Explicit date seam lets device acceptance cross midnight without waiting or changing the clock.
    internal suspend fun deliver(today: LocalDate): Result {
        val c = applicationContext
        val nm = NotificationManagerCompat.from(c)
        if (!notificationsAllowed(c, nm)) return Result.success()
        val repo = c.repository()
        val l = repo.read()
        val dao = repo.db.book()
        dao.prune(today.minusDays(370).toString())
        ReminderPolicy.scheduled(l, today).forEach { event ->
            val key = event.deliveryKey(today)
            if (dao.claim(ReminderRow(key, today.toString())) != -1L) {
                try {
                    val builder = NotificationCompat.Builder(c, "expiry").setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle(if (event.balance) "${event.name} 余额提醒" else "${event.name} 即将到期")
                        .setContentText(if (event.balance) "预计 ${event.date} 余额不足，请及时充值" else "${event.date} · ${event.planName}")
                        .setContentIntent(Jobs.detailIntent(c, event.planId)).setExtras(eventExtras(listOf(event)))
                        .setAutoCancel(true)
                    if (!event.balance) builder.addAction(0, "确认已扣款", Jobs.detailIntent(c, event.planId, "pay"))
                    builder.addAction(0, "明天提醒", Jobs.detailIntent(c, event.planId, "snooze"))
                    nm.notify(key, 0, builder.build())
                } catch (_: SecurityException) { dao.release(key) }
            }
        }
        val missed = ReminderPolicy.missed(l, today, dao.reminderEvents().toSet())
        val claimed = missed.filter { dao.claim(ReminderRow(it.catchUpKey, today.toString())) != -1L }
        if (claimed.isNotEmpty()) {
            try {
                // Retain other unresolved items in the existing aggregate when a later item is added.
                val activeKeys = c.getSystemService(NotificationManager::class.java).activeNotifications
                    .find { it.tag == "reminder-catchup" }?.notification?.extras?.getStringArray("eventKeys").orEmpty()
                val combined = (ReminderPolicy.unresolved(l, activeKeys.toList(), today) + claimed).distinctBy { it.key }
                val text = ReminderPolicy.summary(combined)
                nm.notify("reminder-catchup", 0, NotificationCompat.Builder(c, "expiry").setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("有 ${combined.size} 项近期提醒待处理").setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setExtras(eventExtras(combined))
                    .setContentIntent(Jobs.detailIntent(c, combined.first().planId)).setAutoCancel(true).build())
            } catch (_: SecurityException) { claimed.forEach { dao.release(it.catchUpKey) } }
        }
        return Result.success()
    }
}

class SnoozeReminderWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p) {
    override suspend fun doWork():Result {
        val c = applicationContext
        val id = inputData.getString("planId") ?: return Result.success()
        val keys = inputData.getStringArray("eventKeys")?.toList() ?: return Result.success()
        val l = c.repository().read()
        val plan = l.plans.find { it.id == id && !it.archived } ?: return Result.success()
        val pending = ReminderPolicy.unresolved(l, keys).filter { it.planId == id }
        if (pending.isEmpty()) return Result.success()
        val nm = NotificationManagerCompat.from(c)
        if (!notificationsAllowed(c, nm)) return Result.success()
        val text = ReminderPolicy.summary(pending)
        try {
            nm.notify("snooze-$id", 0, NotificationCompat.Builder(c, "expiry").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("${plan.name} · 稍后提醒").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setExtras(eventExtras(pending))
                .setContentIntent(Jobs.detailIntent(c, id)).setAutoCancel(true).build())
        } catch (_: SecurityException) {}
        return Result.success()
    }
}
