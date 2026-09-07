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
    fun snooze(c:Context,planId:String) {
        WorkManager.getInstance(c).enqueueUniqueWork("snooze-$planId",ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SnoozeReminderWorker>().setInitialDelay(1,TimeUnit.DAYS).setInputData(workDataOf("planId" to planId)).build())
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
class ReminderWorker(c: Context, p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val nm = NotificationManagerCompat.from(c)
        if((Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) || !nm.areNotificationsEnabled()) return Result.success()
        nm.createNotificationChannel(NotificationChannel("expiry","到期提醒",NotificationManager.IMPORTANCE_DEFAULT))
        val repo = c.repository(); val l = repo.read(); val today = LocalDate.now()
        repo.db.book().prune(today.minusDays(370).toString())
        Book.due(l,today).forEach { b ->
            val p = l.plans.single { it.id == b.planId }; val key = Book.reminderKey(b,p,today)
            if(repo.db.book().claim(ReminderRow(key,today.toString())) != -1L) {
                try {
                    val intent = Jobs.detailIntent(c,p.id)
                    nm.notify(key,0,NotificationCompat.Builder(c,"expiry").setSmallIcon(R.drawable.ic_launcher).setContentTitle("${b.name} 即将到期").setContentText("${Book.expiry(b,p)} · ${p.name}").setContentIntent(intent).addAction(0,"确认已扣款",Jobs.detailIntent(c,p.id,"pay")).addAction(0,"明天提醒",Jobs.detailIntent(c,p.id,"snooze")).setAutoCancel(true).build())
                } catch(e: SecurityException) { repo.db.book().release(key) }
            }
        }
        Prepaid.due(l,today).forEach { p ->
            val date=Prepaid.rechargeDate(p)!!
            val key="balance:${p.id}:$date:$today"
            if(repo.db.book().claim(ReminderRow(key,today.toString())) != -1L) {
                try {
                    val intent=Jobs.detailIntent(c,p.id)
                    nm.notify(key,0,NotificationCompat.Builder(c,"expiry").setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("${p.name} 余额提醒").setContentText("预计 $date 余额不足，请及时充值")
                        .setContentIntent(intent).addAction(0,"明天提醒",Jobs.detailIntent(c,p.id,"snooze")).setAutoCancel(true).build())
                } catch(e: SecurityException) { repo.db.book().release(key) }
            }
        }
        return Result.success()
    }
}

class SnoozeReminderWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p) {
    override suspend fun doWork():Result {
        val c=applicationContext
        val id=inputData.getString("planId") ?: return Result.success()
        val l=c.repository().read();val plan=l.plans.find{it.id==id && !it.archived} ?: return Result.success()
        val nm=NotificationManagerCompat.from(c)
        if(!nm.areNotificationsEnabled() || (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)) return Result.success()
        nm.createNotificationChannel(NotificationChannel("expiry","到期提醒",NotificationManager.IMPORTANCE_DEFAULT))
        val text=if(plan.balanceAccount!=null) "查看话费余额与充值记录" else l.benefits.filter{it.planId==id}.minOfOrNull{Book.expiry(it,plan)}?.let {"到期日 $it"} ?: return Result.success()
        try {nm.notify("snooze-$id",0,NotificationCompat.Builder(c,"expiry").setSmallIcon(R.drawable.ic_launcher).setContentTitle("${plan.name} · 稍后提醒").setContentText(text).setContentIntent(Jobs.detailIntent(c,id)).setAutoCancel(true).build())} catch(_:SecurityException) {}
        return Result.success()
    }
}
