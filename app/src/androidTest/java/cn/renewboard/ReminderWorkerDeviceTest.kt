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
            repository.recordMonthlyFees(today)
            val settled=repository.read()
            assertEquals("话费扣费",settled.payments.single().note)
            assertEquals("30",settled.payments.single().amount)
            assertTrue(manager.areNotificationsEnabled())
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork())
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while ((manager.activeNotifications.none { it.tag == key } || manager.activeNotifications.none { it.tag == balanceKey }) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            val first = manager.activeNotifications.single { it.tag == key }
            assertEquals(0, first.id)
            assertEquals(listOf("确认已扣款","明天提醒"),first.notification.actions.map {it.title.toString()})
            assertNotEquals(Jobs.detailIntent(app,phone.id),Jobs.detailIntent(app,ledger.plans.single().id))
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
            assertEquals(settled, repository.read())
            assertEquals(ListenableWorker.Result.success(),TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(Jobs.snoozeData(settled, phone.id, today)).build().doWork())
            val snoozeDeadline=SystemClock.elapsedRealtime()+5000
            while(manager.activeNotifications.none {it.tag=="snooze-${phone.id}"} && SystemClock.elapsedRealtime()<snoozeDeadline) SystemClock.sleep(25)
            assertTrue(manager.activeNotifications.any {it.tag=="snooze-${phone.id}"})
            manager.cancel("snooze-${phone.id}",0)
            repository.update {it.copy(plans=it.plans.map {p->if(p.id==phone.id) p.copy(archived=true) else p})}
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(Jobs.snoozeData(settled, phone.id, today)).build().doWork()
            assertTrue(manager.activeNotifications.none {it.tag=="snooze-${phone.id}"})
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

    @Test @SdkSuppress(minSdkVersion = 33)
    fun catchUpAndSnoozeFollowSuccessfulRenewalAndBalanceResolution() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RenewApp>()
        check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val original = app.repository
        val databaseName = "reminder-followthrough-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(app, BookDatabase::class.java, databaseName).build()
        val manager = app.getSystemService(NotificationManager::class.java)
        fun awaitNotification(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertTrue(predicate())
        }
        val today = LocalDate.now()
        val plan = Plan(id = "followthrough", name = "联合提醒测试", amount = "30", billingAnchor = today.toString())
        val first = Benefit(id = "followthrough-a", planId = plan.id, name = "权益甲", anchor = today.minusDays(1).toString())
        val second = first.copy(id = "followthrough-b", name = "权益乙", anchor = today.minusDays(2).toString())
        try {
            val repo = Repository(db, ledgerChanged = { before, after -> Jobs.clearResolvedReminders(app, before, after) })
            app.repository = repo
            manager.cancel("reminder-catchup", 0)
            repo.update { Ledger(plans = listOf(plan), benefits = listOf(first, second)) }
            val initial = repo.read()
            val snooze = Jobs.snoozeData(initial, plan.id, today)
            Jobs.snooze(app, plan.id, initial, today).result.get()
            val scheduled = WorkManager.getInstance(app).getWorkInfosForUniqueWork("snooze-${plan.id}").get().single()
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, scheduled.state)
            TestListenableWorkerBuilder<ReminderWorker>(app).build().deliver(today)
            awaitNotification { manager.activeNotifications.any { it.tag == "reminder-catchup" } }
            val catchUp = manager.activeNotifications.single { it.tag == "reminder-catchup" }
            assertEquals(2, catchUp.notification.extras.getStringArray("eventKeys")!!.size)
            assertEquals(1, manager.activeNotifications.count { it.tag == "reminder-catchup" })
            TestListenableWorkerBuilder<ReminderWorker>(app).build().deliver(today.plusDays(1))
            SystemClock.sleep(100)
            assertEquals(catchUp.postTime, manager.activeNotifications.single { it.tag == "reminder-catchup" }.postTime)

            repo.update { Book.renew(it, plan.id, "30", today, setOf(first.id), "部分续费") }
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED,
                WorkManager.getInstance(app).getWorkInfosForUniqueWork("snooze-${plan.id}").get().single().state)
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(snooze).build().doWork()
            awaitNotification { manager.activeNotifications.any { it.tag == "snooze-${plan.id}" } }
            val partial = manager.activeNotifications.single { it.tag == "snooze-${plan.id}" }
            assertEquals(listOf("${second.id}:${second.anchor}"), partial.notification.extras.getStringArray("eventKeys")!!.toList())
            assertFalse(partial.notification.extras.getString("android.text")!!.contains("权益甲"))
            repo.update { Book.renew(it, plan.id, "30", today, setOf(second.id), "其余续费") }
            awaitNotification { manager.activeNotifications.none { it.tag == "snooze-${plan.id}" || it.tag == "reminder-catchup" } }
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(snooze).build().doWork()
            SystemClock.sleep(100)
            assertTrue(manager.activeNotifications.none { it.tag == "snooze-${plan.id}" })
            awaitNotification {
                WorkManager.getInstance(app).getWorkInfosForUniqueWork("snooze-${plan.id}").get().single().state == androidx.work.WorkInfo.State.CANCELLED
            }

            val phone = Plan(id = "followthrough-phone", name = "话费测试", amount = "30", billingAnchor = today.toString(),
                balanceAccount = BalanceAccount("-10", today.toString()))
            repo.update { Ledger(plans = listOf(phone)) }
            val phoneSnooze = Jobs.snoozeData(repo.read(), phone.id, today)
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(phoneSnooze).build().doWork()
            awaitNotification { manager.activeNotifications.any { it.tag == "snooze-${phone.id}" } }
            repo.update { Prepaid.topUp(it, phone.id, "100", today) }
            awaitNotification { manager.activeNotifications.none { it.tag == "snooze-${phone.id}" } }
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(phoneSnooze).build().doWork()
            SystemClock.sleep(100)
            assertTrue(manager.activeNotifications.none { it.tag == "snooze-${phone.id}" })

            repo.update { Ledger(plans = listOf(phone)) }
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(phoneSnooze).build().doWork()
            awaitNotification { manager.activeNotifications.any { it.tag == "snooze-${phone.id}" } }
            repo.update { Prepaid.calibrate(it, phone.id, "100", today) }
            awaitNotification { manager.activeNotifications.none { it.tag == "snooze-${phone.id}" } }
            TestListenableWorkerBuilder<SnoozeReminderWorker>(app).setInputData(phoneSnooze).build().doWork()
            SystemClock.sleep(100)
            assertTrue(manager.activeNotifications.none { it.tag == "snooze-${phone.id}" })
        } finally {
            WorkManager.getInstance(app).cancelUniqueWork("snooze-${plan.id}").result.get()
            manager.cancel("reminder-catchup", 0)
            manager.cancel("snooze-${plan.id}", 0)
            manager.cancel("snooze-followthrough-phone", 0)
            app.repository = original
            db.close()
            app.deleteDatabase(databaseName)
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
            database.book().release(key)
            assertTrue(database.book().reminderEvents().isEmpty())
            // The user enables notifications the next day: the missed reminder is delivered once.
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.cancel("reminder-catchup", 0)
            TestListenableWorkerBuilder<ReminderWorker>(app).build().deliver(today.plusDays(1))
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (manager.activeNotifications.none { it.tag == "reminder-catchup" } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            val posted = manager.activeNotifications.single { it.tag == "reminder-catchup" }
            TestListenableWorkerBuilder<ReminderWorker>(app).build().deliver(today.plusDays(2))
            SystemClock.sleep(100)
            assertEquals(posted.postTime, manager.activeNotifications.single { it.tag == "reminder-catchup" }.postTime)
            manager.cancel("reminder-catchup", 0)
        } finally {
            app.repository = originalRepository
            database.close()
            app.deleteDatabase(name)
        }
    }
}
