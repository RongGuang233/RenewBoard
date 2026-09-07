package cn.renewboard

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GrantedReminderWorkerDeviceTest {
    // 1. adb shell am instrument -w -e class cn.renewboard.GrantedReminderWorkerDeviceTest cn.renewboard.debug.test/androidx.test.runner.AndroidJUnitRunner
    // 2. adb shell pm revoke cn.renewboard.debug android.permission.POST_NOTIFICATIONS
    @Test @SdkSuppress(minSdkVersion = 33)
    fun grantedNotificationPermissionPostsOnlyOnceForSameEvent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RenewApp>()
        check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS))
        val originalRepository = app.repository
        val name = "notification-instrumentation-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(app, BookDatabase::class.java, name).build()
        val manager = app.getSystemService(NotificationManager::class.java)
        val today = LocalDate.now()
        val ledger = deviceLedger().let { it.copy(benefits = it.benefits.map { b ->
            b.copy(id = "notification-${UUID.randomUUID()}", anchor = today.toString())
        }, payments = emptyList()) }
        val phone = Plan(id="balance-notification",name="中国移动",amount="30",billingAnchor=today.toString(),
            balanceAccount=BalanceAccount("0",today.minusDays(1).toString()))
        val withPhone=ledger.copy(plans=ledger.plans+phone)
        val balanceKey="balance:${phone.id}:$today:$today"
        val key = Book.reminderKey(ledger.benefits.single(), ledger.plans.single(), today)
        try {
            val repository = Repository(database)
            app.repository = repository
            repository.update { withPhone }
            assertTrue(manager.areNotificationsEnabled())
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork())
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while ((manager.activeNotifications.none { it.tag == key } || manager.activeNotifications.none { it.tag == balanceKey }) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            val first = manager.activeNotifications.single { it.tag == key }
            assertEquals(0, first.id)
            assertEquals("设备测试权益 即将到期", first.notification.extras.getString("android.title"))
            assertEquals(-1L, database.book().claim(ReminderRow(key, today.toString())))
            val firstBalance=manager.activeNotifications.single { it.tag==balanceKey }
            assertEquals("中国移动 余额提醒",firstBalance.notification.extras.getString("android.title"))
            assertEquals(-1L,database.book().claim(ReminderRow(balanceKey,today.toString())))

            // Ensure a second post would receive a distinct wall-clock postTime.
            SystemClock.sleep(100)
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork())
            SystemClock.sleep(250)
            val second = manager.activeNotifications.single { it.tag == key }
            assertEquals(first.key, second.key)
            assertEquals(first.postTime, second.postTime)
            assertEquals(firstBalance.postTime,manager.activeNotifications.single { it.tag==balanceKey }.postTime)
            assertEquals(withPhone, repository.read())
        } finally {
            manager.cancel(key, 0)
            manager.cancel(balanceKey,0)
            app.repository = originalRepository
            database.close()
            app.deleteDatabase(name)
            // Revoking a runtime permission here can kill the instrumentation process.
            // The host must run pm revoke after this isolated test invocation returns.
        }
    }

}

@RunWith(AndroidJUnit4::class)
class ReminderWorkerDeviceTest {
    /** Run on a fresh API 33+ debug install with POST_NOTIFICATIONS ungranted. */
    @Test @SdkSuppress(minSdkVersion = 33)
    fun deniedNotificationPermissionSucceedsWithoutConsumingReminderClaim() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RenewApp>()
        check(app.packageName.endsWith(".debug"))
        assertEquals("Run with debug notification permission denied", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        val originalRepository = app.repository
        val name = "worker-instrumentation-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(app, BookDatabase::class.java, name).build()
        try {
            val repository = Repository(database)
            app.repository = repository
            val today = LocalDate.now()
            val ledger = deviceLedger().let { it.copy(benefits = it.benefits.map { b -> b.copy(anchor = today.toString()) }) }
            repository.update { ledger }
            assertEquals(1, Book.due(ledger, today).size)
            val worker = TestListenableWorkerBuilder<ReminderWorker>(app).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(ledger, repository.read())
            val key = Book.reminderKey(ledger.benefits.single(), ledger.plans.single(), today)
            assertTrue("Denied notifications must not mark a reminder as delivered",
                database.book().claim(ReminderRow(key, today.toString())) != -1L)
        } finally {
            app.repository = originalRepository
            database.close()
            app.deleteDatabase(name)
        }
    }
}
