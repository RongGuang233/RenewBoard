package cn.renewboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RefundDraftDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    private val today=LocalDate.now()
    private val plan=Plan(id="refund-draft-plan",name="退款草稿测试",amount="100",billingAnchor=today.minusMonths(1).toString())
    private val original=Payment(id="refund-draft-original",planId=plan.id,planName=plan.name,amount="100",currency="CNY",date=today.minusDays(1).toString())
    private val key="refund:${original.id}"
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        DraftStore(app).remove(key)
        runBlocking {app.repository.update {Ledger(plans=listOf(plan),benefits=listOf(Benefit(planId=plan.id,name=plan.name,anchor=today.toString())),payments=listOf(original))}}
        compose.waitForIdle()
    }
    @After fun cleanup() {
        if(::app.isInitialized) {
            runBlocking {app.repository.update {Ledger()}}
            DraftStore(app).remove(key)
        }
    }
    private fun open() {
        compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNodeWithText(plan.name,substring=false).performScrollTo().performClick()
        compose.onNodeWithText(original.date,substring=false).performScrollTo().performClick()
        refund()
    }
    private fun refund() {
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("记录退款",substring=false).performClick()
    }
    private fun fill(amount:String) {
        compose.onNode(hasText("退款金额 ¥") and hasSetTextAction()).performScrollTo().performTextReplacement(amount)
    }
    private fun back() {compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    @Test fun blankOrRevertedFormLeavesWithoutPrompt() {
        open();back()
        compose.onNodeWithText("付款详情",substring=false).assertExists()
        compose.onNodeWithText("保留退款草稿？",substring=false).assertDoesNotExist()
        assertNull(DraftStore(app).read(key))
        refund();fill("25");fill("");back()
        compose.onNodeWithText("付款详情",substring=false).assertExists()
        compose.onNodeWithText("保留退款草稿？",substring=false).assertDoesNotExist()
        assertNull(DraftStore(app).read(key))
    }
    @Test fun draftSurvivesRecreationAndRetainedExitThenCanBeDiscarded() {
        open();fill("35.80")
        compose.onNodeWithText("添加备注",substring=false).performScrollTo().performClick()
        compose.onNode(hasText("退款备注") and hasSetTextAction()).performScrollTo().performTextReplacement("客服退回差价")
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("退款金额 ¥") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("退款金额 ¥") and hasSetTextAction()).assertTextContains("35.80")
        compose.onNode(hasText("退款备注") and hasSetTextAction()).assertTextContains("客服退回差价")
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        compose.onNodeWithText("付款详情",substring=false).assertExists()
        refund()
        compose.onNodeWithText("已恢复退款草稿",substring=false).assertExists()
        compose.onNode(hasText("退款金额 ¥") and hasSetTextAction()).assertTextContains("35.80")
        back();compose.onNodeWithText("放弃修改",substring=false).performClick()
        compose.onNodeWithText("付款详情",substring=false).assertExists()
        assertNull(DraftStore(app).read(key))
        assertEquals(listOf(original),runBlocking {app.repository.read()}.payments)
        refund();back()
        compose.onNodeWithText("保留退款草稿？",substring=false).assertDoesNotExist()
    }
}

@RunWith(AndroidJUnit4::class)
class RefundDraftSaveDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val original=Payment(id="refund-draft-save",planId="source",planName="保存测试",amount="100",currency="CNY",date=LocalDate.now().toString())
    private val app:RenewApp get()=ApplicationProvider.getApplicationContext()
    private val key="refund:${original.id}"
    @Before fun setup() {check(app.packageName.endsWith(".debug"));DraftStore(app).remove(key)}
    @After fun cleanup() {DraftStore(app).remove(key)}
    @Test fun failedSaveRetainsDraftAndSuccessfulRetryWaitsForCommit() {
        var ledger=Ledger(payments=listOf(original))
        var attempts=0
        val commit=CompletableDeferred<Unit>()
        compose.setContent {
            var closed by remember {mutableStateOf(false)}
            MaterialTheme {
                if(closed) Text("退款已保存") else RefundEditor(ledger,original,{closed=true},save={transform ->
                    attempts++
                    if(attempts==1) throw IllegalStateException("临时保存失败")
                    commit.await()
                    ledger=transform(ledger)
                })
            }
        }
        compose.onNode(hasText("退款金额 ¥") and hasSetTextAction()).performTextReplacement("20")
        compose.onNodeWithText("保存退款",substring=false).performClick()
        compose.onNodeWithText("临时保存失败",substring=false).assertExists()
        assertNotNull(DraftStore(app).read(key))
        assertEquals(1,ledger.payments.size)
        compose.onNodeWithText("保存退款",substring=false).performClick()
        compose.onNodeWithText("正在保存…",substring=false).assertIsNotEnabled()
        assertNotNull(DraftStore(app).read(key))
        assertEquals(1,ledger.payments.size)
        compose.runOnIdle {commit.complete(Unit)}
        compose.onNodeWithText("退款已保存",substring=false).assertExists()
        assertNull(DraftStore(app).read(key))
        assertEquals(2,attempts)
        assertEquals("20",ledger.payments.last().amount)
        assertEquals(original.id,ledger.payments.last().refundOf)
    }
}
