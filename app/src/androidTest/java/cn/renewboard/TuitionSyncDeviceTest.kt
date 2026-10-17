package cn.renewboard

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Isolated debug data and an injected query; no real server or credentials. */
@RunWith(AndroidJUnit4::class)
class TuitionSyncDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: RenewApp
    private lateinit var original: TuitionQuery
    private val connection = TuitionConnection("https://example.invalid/tuition", "fictional-read-token",
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + 30).toByte() }))
    private fun snapshot(amount: Long = 200000L, published: String = "2026-10-15T08:00:00Z") = TuitionSnapshot(publishedAt=published,
        students=listOf(TuitionStudent("student-a", "示例学生", "active", amount, true)))
    private fun read() = runBlocking { app.repository.read() }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); check(app.packageName.endsWith(".debug"))
        original = app.tuitionQuery
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("asset-display",Context.MODE_PRIVATE).edit().clear().commit()
        app.tuitionConnection.disconnect()
        app.tuitionQuery = TuitionQuery { error("Test must inject its query") }
        runBlocking { app.repository.restore(Backup(data=Ledger(assets=AssetBook(accounts=listOf(
            AssetAccount(id="bank",name="示例银行卡",category=AssetCategory.CASH,balance="5000")))))) }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        app.tuitionQuery = original; app.tuitionConnection.disconnect()
        app.getSharedPreferences("asset-display",Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { app.repository.restore(Backup(data=Ledger())) }
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }
    private fun click(text: String) {
        compose.onNode(hasText(text,substring=false) and hasClickAction()).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }
    private fun open() {
        compose.onNodeWithText("资产",substring=false).performClick()
        compose.onNodeWithContentDescription("资产管理菜单").performScrollTo().performClick()
        compose.onNode(hasText("学费同步") and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
    }
    private fun waitFor(predicate: () -> Boolean) { compose.waitUntil(10000,predicate); compose.waitForIdle() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }

    @Test fun encryptedConnectionIsNotInBusinessBackupAndDisconnectPreservesAccounts() {
        app.tuitionConnection.save(connection)
        assertEquals(connection,app.tuitionConnection.read())
        val raw=app.getSharedPreferences("tuition-connection",Context.MODE_PRIVATE).getString("connection",null)!!
        assertFalse(raw.contains(connection.token)); assertFalse(raw.contains(connection.encryptionKey))
        val backup=Book.encode(read())
        assertFalse(backup.contains(connection.url)); assertFalse(backup.contains(connection.token)); assertFalse(backup.contains(connection.encryptionKey))
        app.tuitionConnection.disconnect()
        assertNull(app.tuitionConnection.read()); assertEquals("5000",read().assets.accounts.single().balance)
    }

    @Test fun manualPreviewThenRepeatedRefreshDoesNotDuplicateOrChangeBank() {
        app.tuitionConnection.save(connection)
        app.tuitionQuery = TuitionQuery { assertEquals(connection,it); snapshot() }
        open(); click("刷新学费余额")
        compose.onNodeWithText("选择要导入的学生").assertExists()
        screenshot("tuition-import-preview")
        assertEquals(1,read().assets.accounts.size)
        click("确认导入并更新")
        waitFor { read().assets.accounts.size==2 }
        assertEquals("5000",read().assets.accounts.first { it.id=="bank" }.balance)
        assertEquals(0,Assets.totals(read()).net.compareTo(java.math.BigDecimal("3000")))
        val histories=read().assets.history
        val fetched=read().assets.tuitionFetchedAt
        click("刷新学费余额")
        waitFor { read().assets.tuitionFetchedAt!=fetched }
        assertEquals(2,read().assets.accounts.size); assertEquals(histories,read().assets.history)
        app.tuitionQuery = TuitionQuery { snapshot(170000L,"2026-10-16T08:00:00Z") }
        click("刷新学费余额")
        waitFor { read().assets.accounts.any { it.studentId=="student-a" && it.balance=="1700.00" } }
        assertEquals("5000",read().assets.accounts.first { it.id=="bank" }.balance)
        assertEquals(0,Assets.totals(read()).net.compareTo(java.math.BigDecimal("3300")))
        val before=read()
        app.tuitionQuery = TuitionQuery { throw TuitionSyncException("无法读取学费余额，请检查网络后重试") }
        click("刷新学费余额")
        compose.onNodeWithText("无法读取学费余额，请检查网络后重试").assertExists()
        assertEquals(before,read())
    }

    @Test fun viewingStudentsNeverDownloadsAndHomepageRefreshRunsOnce() {
        app.tuitionConnection.save(connection)
        runBlocking {app.repository.update {Assets.importTuition(it,snapshot(),setOf("student-a"),"2026-10-15T09:00:00Z")}}
        val downloads=AtomicInteger()
        app.tuitionQuery=TuitionQuery {
            assertEquals(connection,it);downloads.incrementAndGet()
            snapshot(170000L,"2026-10-16T08:00:00Z")
        }
        compose.onNodeWithText("资产",substring=false).performClick()
        click("查看学生")
        compose.onNodeWithText("示例学生",substring=false).assertExists()
        assertEquals(0,downloads.get())
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        waitFor {compose.onAllNodesWithText("示例学生",substring=false).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(0,downloads.get())
        compose.onNodeWithContentDescription("返回").performClick();compose.waitForIdle()
        click("刷新")
        waitFor {read().assets.accounts.any {it.studentId=="student-a" && it.balance=="1700.00"}}
        assertEquals(1,downloads.get())
        assertEquals("5000",read().assets.accounts.first {it.id=="bank"}.balance)
        // Saving another account and recreating must not replay the original refresh action.
        runBlocking {app.repository.update {Assets.updateBalance(it,"bank","5100",java.time.LocalDate.now().toString(),false)}}
        compose.waitForIdle();compose.activityRule.scenario.recreate();compose.waitForIdle()
        waitFor {compose.onAllNodesWithText("示例学生",substring=false).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(1,downloads.get())
        screenshot("tuition-student-accounts")
        compose.onNodeWithContentDescription("返回").performClick();compose.waitForIdle()
        click("查看学生");assertEquals(1,downloads.get())
    }

    @Test fun legacyStoredPayloadAndRestoreDoNotInventAssetHistory() = runBlocking {
        val old = Ledger(settings=Settings(reminderDays=listOf(3,0)))
        val payload=Book.json.encodeToString(old)
        assertFalse(payload.contains("\"assets\""))
        app.repository.db.book().put(BookRow(payload=payload))
        assertEquals(AssetBook(),app.repository.read().assets)
        val account=AssetAccount(name="本地账户",category=AssetCategory.CASH,balance="100")
        app.repository.update { Assets.put(it,account) }
        val saved=app.repository.read()
        assertEquals(1,saved.assets.history.size)
        app.repository.restore(Book.decode(Book.encode(saved)))
        assertEquals(saved,app.repository.read())
        app.repository.restore(Book.decode(Book.encode(old)))
        assertEquals(old,app.repository.read())
    }
}
