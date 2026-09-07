package cn.renewboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PaymentEditorDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app: RenewApp
    private val first=Plan(id="payment-a",name="付款测试甲",amount="20",billingAnchor="2026-09-01")
    private val second=first.copy(id="payment-b",name="付款测试乙")
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        DraftStore(app).remove("payment:${first.id}");DraftStore(app).remove("payment:${second.id}")
        runBlocking {app.repository.update {Ledger(plans=listOf(first,second),benefits=listOf(
            Benefit(id="a1",planId=first.id,name="主权益",anchor="2026-10-01"),
            Benefit(id="a2",planId=first.id,name="附加权益",anchor="2026-10-01"),
            Benefit(id="b1",planId=second.id,name="乙权益",anchor="2026-10-01")
        ))}}
        compose.waitForIdle()
    }
    @After fun cleanup() {
        if(::app.isInitialized) {
            runBlocking {app.repository.update {Ledger()}}
            DraftStore(app).remove("payment:${first.id}");DraftStore(app).remove("payment:${second.id}")
        }
    }
    private fun click(text: String) {compose.onNodeWithText(text,substring=false).performScrollTo().performClick()}
    private fun fill(label: String,text: String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(text)}
    private fun open(name: String) {
        compose.onNodeWithText("订阅",substring=false).performClick()
        click(name);click("记录付款 / 提前续费")
        compose.onNodeWithText("记录付款",substring=false).assertExists()
    }
    private fun back() {compose.onNodeWithContentDescription("返回").performClick()}
    private fun awaitPayments(count: Int) {
        compose.waitUntil(10000) {runBlocking {app.repository.read()}.payments.size==count};compose.waitForIdle()
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun draftSurvivesRecreateAndExitStaysScopedToPlanAndCanBeDiscarded() {
        open(first.name)
        fill("付款金额 ¥","35.80")
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.onNode(hasText("付款金额 ¥") and hasSetTextAction()).assertTextContains("35.80")
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        back()
        open(second.name)
        compose.onNode(hasText("付款金额 ¥") and hasSetTextAction()).assertTextContains("20")
        back();back()
        open(first.name)
        compose.onNodeWithText("已恢复付款草稿").assertExists()
        compose.onNode(hasText("付款金额 ¥") and hasSetTextAction()).assertTextContains("35.80")
        back();compose.onNodeWithText("放弃修改",substring=false).performClick()
        assertNull(DraftStore(app).read("payment:${first.id}"))
        assertTrue(runBlocking {app.repository.read()}.payments.isEmpty())
        back()
        open(first.name)
        fill("付款金额 ¥","99")
        fill("付款金额 ¥","20")
        back()
        compose.onNodeWithText("保留付款草稿？",substring=false).assertDoesNotExist()
        assertNull(DraftStore(app).read("payment:${first.id}"))
    }
    @Test fun multiPeriodRenewalRecordsOnePaymentAndClearsDraft() {
        open(first.name)
        compose.onNodeWithText("主权益",substring=false).assertDoesNotExist()
        shot("payment-editor-default")
        click("本次续费 1 期")
        fill("续费期数","2")
        click("续费权益 2/2 项")
        compose.onNodeWithText("主权益",substring=false).assertExists()
        fill("付款金额 ¥","40")
        shot("payment-editor")
        compose.onNodeWithText("确认付款",substring=false).performClick()
        awaitPayments(1)
        val saved=runBlocking {app.repository.read()}
        assertEquals("40",saved.payments.single().amount)
        assertEquals(2,saved.benefits.first {it.id=="a1"}.renewals)
        assertEquals(2,saved.benefits.first {it.id=="a2"}.renewals)
        assertNull(DraftStore(app).read("payment:${first.id}"))
    }
    @Test fun duplicateWarningAllowsReviewAndExplicitSecondRecord() {
        runBlocking {app.repository.update {Book.recordPayment(it,first.id,"20",LocalDate.now(),"已有记录")}}
        open(first.name)
        click("仅记账")
        compose.onNodeWithText("确认付款",substring=false).performClick()
        compose.onNodeWithText("发现相似付款").assertExists()
        compose.onNodeWithText("已有记录").assertExists()
        compose.onNodeWithText("返回检查").performClick()
        assertEquals(1,runBlocking {app.repository.read()}.payments.size)
        compose.onNodeWithText("确认付款",substring=false).performClick()
        compose.onNodeWithText("仍然记录").performClick()
        awaitPayments(2)
        assertEquals(0,runBlocking {app.repository.read()}.benefits.first {it.id=="a1"}.renewals)
    }
}
