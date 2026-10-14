package cn.renewboard

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Fictional credentials and controlled requests; never contacts a platform or changes a release package. */
@RunWith(AndroidJUnit4::class)
class DeepSeekBalanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: RenewApp
    private lateinit var originalQuery: PlatformBalanceQuery
    private val id = "deepseek-device-test"
    private val mimoId = "mimo-device-test"
    private val key = "sk-fictional-device-key-not-a-real-credential"
    private val replacementKey = "sk-fictional-replacement-key"
    private val today get() = LocalDate.now().toString()
    private fun snapshot(total: String = "123.45", granted: String = "3.45", toppedUp: String = "120",
                         currency: String = "CNY") = DeepSeekBalanceSnapshot(currency, total, granted, toppedUp, Instant.now())
    private fun plan(planId: String = id, name: String = "DeepSeek API") = Plan(id = planId, name = name,
        amount = "0", autoRenew = false, paidCycles = 0, billingAnchor = today,
        usageBalance = UsageBalance("200", today))
    private fun read() = runBlocking { app.repository.read() }
    private fun connection() = app.platformConnections.connection(id)
    private fun installQuery(body: suspend (String, String) -> DeepSeekBalanceSnapshot) {
        app.platformBalanceQuery = PlatformBalanceQuery { apiKey, currency -> body(apiKey, currency) }
    }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug"))
        originalQuery = app.platformBalanceQuery
        WorkManager.getInstance(app).cancelAllWork().result.get()
        listOf(id, mimoId).forEach { app.platformConnections.disconnect(it) }
        app.getSharedPreferences("form-drafts", Context.MODE_PRIVATE).edit().clear().commit()
        installQuery { _, _ -> error("A test must explicitly configure its fake request") }
        val p = plan()
        runBlocking { app.repository.update { Ledger(plans = listOf(p, plan(mimoId, "MiMo API")),
            payments = listOf(Payment(id = "deepseek-existing-recharge", planId = id, planName = p.name,
                amount = "50", currency = "CNY", date = today, note = "平台充值"))) } }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        app.repository.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS deepseek_test_reject")
        app.platformBalanceQuery = originalQuery
        runBlocking { app.repository.update { Ledger() } }
        listOf(id, mimoId).forEach { app.platformConnections.disconnect(it) }
        app.getSharedPreferences("form-drafts", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }
    private fun waitFor(predicate: () -> Boolean) { compose.waitUntil(10000, predicate); compose.waitForIdle() }
    private fun click(text: String) {
        val node = compose.onNode(hasText(text, substring = false) and !hasSetTextAction())
        if(text in listOf("连接并查询余额", "验证并更换", "保存余额"))
            node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        else node.performClick()
    }
    private fun field(name: String) = compose.onNode(hasText(name, substring = false) and hasSetTextAction())
    private fun openAccount(name: String = "DeepSeek API") {
        compose.onNodeWithText("账本", substring = false).performClick()
        compose.onNodeWithContentDescription("打开服务管理").performScrollTo().performClick()
        compose.onNode(hasText(name, substring = false) and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("更新余额", substring = false).assertExists()
    }
    private fun menu() = compose.onNodeWithContentDescription("余额账户更多操作").performClick()
    private fun openConnection() { menu(); click("连接平台") }
    private fun back() { compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun failure(body: suspend () -> Unit): Throwable {
        val result = runBlocking { runCatching { body() } }
        assertTrue("Operation must fail", result.isFailure)
        return result.exceptionOrNull()!!
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(app.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun connectionShowsCollapsedGiftDetailsAndRefreshesToZeroWithoutChangingPayments() {
        val queried = snapshot()
        val payments = read().payments
        val calls = AtomicInteger()
        installQuery { apiKey, currency ->
            assertEquals(key, apiKey); assertEquals("CNY", currency)
            if(calls.getAndIncrement() == 0) queried else snapshot("0", "0", "0")
        }
        openAccount(); openConnection()
        screenshot("connection-v1.13.0.png")
        field("API 密钥").performTextReplacement(key)
        click("连接并查询余额")
        waitFor { connection() != null && compose.onAllNodesWithContentDescription("刷新余额").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("¥123.45", substring = false).assertExists()
        compose.onNodeWithText("查询明细", substring = false).assertExists()
        compose.onNodeWithText("赠金 ¥3.45", substring = false).assertDoesNotExist()
        waitFor { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        screenshot("balance-v1.13.0.png")
        click("查询明细")
        compose.onNodeWithText("赠金 ¥3.45", substring = false).assertExists()
        compose.onNodeWithText("充值余额 ¥120.00", substring = false).assertExists()
        compose.onNodeWithContentDescription("刷新余额").performClick()
        waitFor { read().plans.single { it.id == id }.usageBalance!!.balance == "0" }
        compose.onNodeWithText("¥0.00", substring = false).assertExists()
        assertEquals(2, calls.get()); assertEquals(payments, read().payments)
        assertEquals("0", connection()!!.snapshot.totalBalance)
        assertTrue(Book.forecastCharges(read(), LocalDate.now(), LocalDate.now().plusDays(30)).isEmpty())
    }

    @Test fun invalidKeyNetworkAndCurrencyErrorsPreserveBalanceTimestampAndExistingCredential() {
        val initial = snapshot()
        installQuery { _, _ -> initial }
        runBlocking { app.platformBalances.connect(id, key) }
        val ledger = read(); val savedConnection = connection()
        listOf("API 密钥无效或无权查询，请更换密钥", "无法连接平台，请检查网络后重试").forEach { message ->
            installQuery { _, _ -> throw PlatformBalanceException(message) }
            assertEquals(message, failure { app.platformBalances.refresh(id) }.message)
            assertEquals(ledger, read()); assertEquals(savedConnection, connection())
        }
        installQuery { _, _ -> snapshot(currency = "USD") }
        assertEquals("平台余额币种与账户不一致", failure { app.platformBalances.refresh(id) }.message)
        assertEquals(ledger, read()); assertEquals(savedConnection, connection())
        installQuery { _, _ -> throw PlatformBalanceException("API 密钥无效或无权查询，请更换密钥") }
        openAccount(); menu(); click("更换密钥")
        field("API 密钥").performTextReplacement(replacementKey); click("验证并更换")
        waitFor { compose.onAllNodesWithText("API 密钥无效或无权查询，请更换密钥", substring = false).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(key, app.platformConnections.apiKey(id))
        assertEquals(savedConnection, connection()); assertEquals(ledger, read())
        back(); compose.onNodeWithText("更新余额", substring = false).assertExists()
    }

    @Test fun encryptedConnectionSurvivesStoreReopenRecreationAndAccountRename() {
        val queried = snapshot()
        installQuery { _, _ -> queried }
        runBlocking { app.platformBalances.connect(id, key) }
        val prefs = app.getSharedPreferences("platform-connections", Context.MODE_PRIVATE)
        assertFalse(prefs.all.toString().contains(key))
        assertFalse(Book.encode(read()).contains(key))
        val reopened = PlatformConnectionStore(app)
        assertEquals(key, reopened.apiKey(id)); assertEquals(queried, reopened.connection(id)!!.snapshot)
        runBlocking { app.repository.update { old -> old.copy(plans = old.plans.map { if(it.id == id) it.copy(name = "我的模型账户") else it }) } }
        openAccount("我的模型账户")
        compose.onNodeWithContentDescription("刷新余额").assertExists()
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        waitFor { compose.onAllNodesWithContentDescription("刷新余额").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的模型账户", substring = false).assertExists()
        assertEquals(key, PlatformConnectionStore(app).apiKey(id))
        assertEquals("123.45", read().plans.single { it.id == id }.usageBalance!!.balance)
    }

    @Test fun disconnectDeletesKeyKeepsLedgerAndMimoManualUpdateStillWorks() {
        installQuery { _, _ -> snapshot() }
        runBlocking { app.platformBalances.connect(id, key) }
        val ledger = read()
        openAccount(); menu(); click("断开连接")
        compose.onNodeWithText("断开 DeepSeek 连接？", substring = false).assertExists()
        click("断开连接")
        waitFor { connection() == null }
        assertEquals(ledger, read())
        assertFalse(app.getSharedPreferences("platform-connections", Context.MODE_PRIVATE).contains(id))
        compose.onNodeWithContentDescription("刷新余额").assertDoesNotExist()
        back(); compose.onNodeWithContentDescription("返回账本").performClick()
        openAccount("MiMo API")
        compose.onNodeWithContentDescription("刷新余额").assertDoesNotExist()
        click("更新余额"); field("查询到的余额").performTextReplacement("75")
        click("保存余额")
        waitFor { read().plans.single { it.id == mimoId }.usageBalance!!.balance == "75" }
        assertEquals(ledger.payments, read().payments)
        assertNull(app.platformConnections.connection(mimoId))
        menu(); compose.onNodeWithText("连接平台", substring = false).assertDoesNotExist()
    }

    @Test fun unverifiedKeyIsNotSavedAndLeavingSlowConnectionCancelsWithoutLateWrites() {
        openAccount(); openConnection()
        field("API 密钥").performTextReplacement(key)
        assertFalse(app.getSharedPreferences("form-drafts", Context.MODE_PRIVATE).all.toString().contains(key))
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        waitFor { compose.onAllNodes(hasText("API 密钥") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", field("API 密钥").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertNull(connection())
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        installQuery { _, _ ->
            started.complete(Unit)
            try { release.await(); snapshot() } finally { if(!release.isCompleted) cancelled.complete(Unit) }
        }
        val ledger = read()
        field("API 密钥").performTextReplacement(key); click("连接并查询余额")
        waitFor { started.isCompleted }
        compose.onNodeWithText("正在查询余额…", substring = false).assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回余额账户").performClick()
        waitFor { cancelled.isCompleted }
        release.complete(Unit); compose.waitForIdle()
        compose.onNodeWithText("更新余额", substring = false).assertExists()
        assertNull(connection()); assertEquals(ledger, read())
        assertFalse(app.getSharedPreferences("form-drafts", Context.MODE_PRIVATE).all.toString().contains(key))
    }

    @Test fun rejectedDatabaseCommitRestoresPreviousCredentialSnapshotAndBalance() {
        installQuery { _, _ -> snapshot() }
        runBlocking { app.platformBalances.connect(id, key) }
        val ledger = read(); val savedConnection = connection()
        val prefs = app.getSharedPreferences("platform-connections", Context.MODE_PRIVATE)
        val encrypted = prefs.getString(id, null)
        val database = app.repository.db.openHelper.writableDatabase
        database.execSQL("CREATE TRIGGER deepseek_test_reject BEFORE INSERT ON BookRow BEGIN SELECT RAISE(ABORT, 'fictional test save failure'); END")
        try {
            listOf(snapshot("999", "9", "990"), snapshot()).forEach { rejectedSnapshot ->
                installQuery { _, _ -> rejectedSnapshot }
                assertEquals("余额保存失败，请重试", failure { app.platformBalances.connect(id, replacementKey) }.message)
                assertEquals(ledger, read()); assertEquals(savedConnection, connection())
                assertEquals(key, app.platformConnections.apiKey(id))
                assertEquals(encrypted, prefs.getString(id, null))
            }
        } finally { database.execSQL("DROP TRIGGER IF EXISTS deepseek_test_reject") }
        installQuery { _, _ -> snapshot("999", "9", "990") }
        runBlocking { app.platformBalances.refresh(id) }
        assertEquals("999", read().plans.single { it.id == id }.usageBalance!!.balance)
        assertEquals(ledger.payments, read().payments)
    }

    @Test fun postCommitCallbackFailureKeepsNewCredentialSnapshotAndCommittedBalance() {
        installQuery { _, _ -> snapshot() }
        runBlocking { app.platformBalances.connect(id, key) }
        val ledger = read()
        val queried = snapshot("999", "9", "990")
        installQuery { apiKey, _ -> assertEquals(replacementKey, apiKey); queried }
        val originalRepository = app.repository
        val callbackCalls = AtomicInteger()
        try {
            app.repository = Repository(originalRepository.db, changed = {
                callbackCalls.incrementAndGet()
                throw IllegalStateException("fictional post-commit callback failure")
            })
            // A notification/backup callback cannot undo an already committed balance or key replacement.
            runBlocking { app.platformBalances.connect(id, replacementKey) }
            assertEquals(1, callbackCalls.get())
            assertEquals("999", read().plans.single { it.id == id }.usageBalance!!.balance)
            assertEquals(replacementKey, app.platformConnections.apiKey(id))
            assertEquals(queried, connection()!!.snapshot)
            assertEquals(ledger.payments, read().payments)
        } finally { app.repository = originalRepository }
        assertEquals("999", read().plans.single { it.id == id }.usageBalance!!.balance)
        assertEquals(replacementKey, PlatformConnectionStore(app).apiKey(id))
    }

    @Test fun inFlightRefreshDoesNotOverwriteManualBalanceAndDuplicateRequestDoesNotQuery() = runBlocking {
        installQuery { _, _ -> snapshot() }
        app.platformBalances.connect(id, key)
        val savedConnection = connection(); val payments = read().payments
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        installQuery { _, _ -> calls.incrementAndGet(); started.complete(Unit); release.await(); snapshot("999", "9", "990") }
        val pending = async(Dispatchers.IO) { runCatching { app.platformBalances.refresh(id) } }
        try {
            started.await()
            val duplicate = runCatching { app.platformBalances.refresh(id) }
            assertEquals("正在查询余额，请稍候", duplicate.exceptionOrNull()?.message)
            assertEquals(1, calls.get())
            app.repository.update { UsageBalances.calibrate(it, id, "77", LocalDate.now()) }
            release.complete(Unit)
            assertEquals("账户已修改，请重新刷新余额", pending.await().exceptionOrNull()?.message)
            assertEquals("77", read().plans.single { it.id == id }.usageBalance!!.balance)
            assertEquals(payments, read().payments); assertEquals(savedConnection, connection())
            assertEquals(key, app.platformConnections.apiKey(id))
        } finally { release.complete(Unit); pending.cancel() }
    }

    @Test fun deletingConnectedAccountClearsCredentialAndKeepsHistoricalRecharge() {
        installQuery { _, _ -> snapshot() }
        runBlocking { app.platformBalances.connect(id, key) }
        val payments = read().payments
        runBlocking { app.repository.update { Book.delete(it, id, false) } }
        assertTrue(read().plans.none { it.id == id })
        assertEquals(payments, read().payments)
        assertNull(connection())
        assertFalse(app.getSharedPreferences("platform-connections", Context.MODE_PRIVATE).contains(id))
        assertEquals("账户已删除", failure { app.platformBalances.refresh(id) }.message)
    }
}
