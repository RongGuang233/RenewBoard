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
class ReceiptEditorDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    private val original=Payment(id="receipt-ui-test",planId="receipt-plan",planName="更正体验",amount="20",currency="USD",date=LocalDate.now().toString(),cnyAmount="142.80")
    private val key="receipt:correction:${original.id}"
    private val settlementKey="receipt:settlement:${original.id}"
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        listOf(key,settlementKey,"refund:${original.id}").forEach {DraftStore(app).remove(it)}
        seed(original)
    }
    @After fun cleanup() {
        runBlocking {app.repository.update {Ledger()}}
        listOf(key,settlementKey,"refund:${original.id}").forEach {DraftStore(app).remove(it)}
    }
    private fun seed(p:Payment) {runBlocking {app.repository.update {Ledger(plans=listOf(Plan(id=p.planId,name=p.planName,amount="20",currency=p.currency,billingAnchor=p.date)),benefits=listOf(Benefit(planId=p.planId,name=p.planName,anchor=p.date)),payments=listOf(p))}};compose.waitForIdle()}
    private fun open() {
        compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNodeWithText(original.planName,substring=false).performScrollTo().performClick()
        compose.onNodeWithText(original.date,substring=false).performScrollTo().performClick()
    }
    private fun action(name:String) {compose.onNodeWithContentDescription("付款更多操作").performClick();compose.onNodeWithText(name,substring=false).performClick()}
    private fun field(name:String)=compose.onNode(hasText(name) and hasSetTextAction())
    private fun back() {compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    private fun read()=runBlocking {app.repository.read()}
    @Test fun correctionDraftSurvivesRecreationAndExitAndCanBeDiscarded() {
        open();action("更正付款")
        field("付款备注").assertDoesNotExist()
        compose.onNodeWithText("仅修改账目，权益到期日不变。",substring=false).assertExists()
        compose.onNodeWithText("添加备注",substring=false).performScrollTo().performClick()
        field("付款备注").performScrollTo().performTextReplacement("等核对账单")
        field("人民币实付金额").performScrollTo().performTextReplacement("141")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("人民币实付金额") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        field("人民币实付金额").assertTextContains("141")
        field("付款备注").assertTextContains("等核对账单")
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        action("更正付款")
        compose.onNodeWithText("已恢复更正草稿",substring=false).assertExists()
        field("付款备注").assertTextContains("等核对账单")
        back();compose.onNodeWithText("放弃修改",substring=false).performClick()
        assertNull(DraftStore(app).read(key));assertEquals(original,read().payments.single())
        action("更正付款");back()
        compose.onNodeWithText("保留更正草稿？",substring=false).assertDoesNotExist()
    }
    @Test fun missingOriginalIsSettledBeforeRefundAndBackReturnsToPayment() {
        seed(original.copy(cnyAmount=null));open();action("记录退款")
        compose.onNodeWithText("补录原付款",substring=false).assertExists()
        field("退款金额 USD").assertDoesNotExist()
        compose.onNodeWithText("保存并继续退款",substring=false).performClick()
        compose.onNodeWithText("请填写付款当天的实际人民币金额",substring=false).assertExists()
        field("人民币实付金额").performTextReplacement("140")
        compose.onNodeWithText("保存并继续退款",substring=false).performClick()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("退款金额 USD") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        assertEquals("140",read().payments.single().cnyAmount)
        compose.onNodeWithText("请先返回详情补录原付款的人民币实付金额。",substring=false).assertDoesNotExist()
        back();compose.onNodeWithText("付款详情",substring=false).assertExists()
        action("记录退款")
        field("退款金额 USD").performTextReplacement("5")
        field("人民币实退金额").performScrollTo().performTextReplacement("35")
        compose.onNodeWithText("保存退款",substring=false).performClick()
        compose.waitUntil(10000) {read().payments.size==2}
        assertEquals(original.id,read().payments.last().refundOf)
        assertEquals("35",read().payments.last().cnyAmount)
        assertNull(DraftStore(app).read(settlementKey))
    }
    @Test fun settlementDraftReturnsToContinuationAfterRecreation() {
        seed(original.copy(cnyAmount=null));open();action("记录退款")
        field("人民币实付金额").performTextReplacement("139")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodesWithText("保存并继续退款",substring=false).fetchSemanticsNodes().isNotEmpty()}
        field("人民币实付金额").assertTextContains("139")
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        compose.onNodeWithText(original.date,substring=false).performScrollTo().performClick()
        field("人民币实付金额").assertTextContains("139")
        action("记录退款");compose.onNodeWithText("保存并继续退款",substring=false).performClick()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("退款金额 USD") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        assertEquals("139",read().payments.single().cnyAmount)
    }
    @Test fun phoneCorrectionKeepsRecordTypeAndDoesNotMentionBenefits() {
        seed(original.copy(currency="CNY",cnyAmount=null,note="话费扣费"));open();action("更正付款")
        compose.onNodeWithText("仅修改账目，话费余额不变。",substring=false).assertExists()
        compose.onNodeWithText("添加备注",substring=false).assertDoesNotExist()
        field("付款金额 ¥").performTextReplacement("30")
        compose.onNodeWithText("保存更正",substring=false).performClick()
        compose.waitUntil(10000) {read().payments.single().amount=="30"}
        assertEquals("话费扣费",read().payments.single().note)
    }
}

@RunWith(AndroidJUnit4::class)
class ReceiptEditorSaveDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val original=Payment(id="receipt-save-test",planId="source",planName="保存测试",amount="20",currency="USD",date=LocalDate.now().toString(),cnyAmount="140")
    private val app:RenewApp get()=ApplicationProvider.getApplicationContext()
    @Before fun setup() {check(app.packageName.endsWith(".debug"));clear()}
    @After fun clear() {listOf("correction","settlement").forEach {DraftStore(app).remove("receipt:$it:${original.id}")}}
    private fun exercise(settlementOnly:Boolean) {
        val p=if(settlementOnly) original.copy(cnyAmount=null) else original
        var ledger=Ledger(payments=listOf(p))
        var attempts=0
        val commit=CompletableDeferred<Unit>()
        compose.setContent {
            var closed by remember {mutableStateOf(false)}
            MaterialTheme {
                if(closed) Text("已保存") else ReceiptEditor(ledger,p,{},saved={closed=true},save={transform ->
                    attempts++;if(attempts==1) throw IllegalStateException("临时保存失败")
                    commit.await();ledger=transform(ledger)
                },settlementOnly=settlementOnly)
            }
        }
        val button=if(settlementOnly) "保存金额" else "保存更正"
        val key="receipt:${if(settlementOnly) "settlement" else "correction"}:${p.id}"
        compose.onNode(hasText("人民币实付金额") and hasSetTextAction()).performTextReplacement("145")
        compose.onNodeWithText(button,substring=false).performClick()
        compose.onNodeWithText("临时保存失败",substring=false).assertExists()
        assertNotNull(DraftStore(app).read(key));assertEquals(p,ledger.payments.single())
        compose.onNodeWithText(button,substring=false).performClick()
        compose.onNodeWithText("正在保存…",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("已保存",substring=false).assertDoesNotExist()
        compose.runOnIdle {commit.complete(Unit)}
        compose.onNodeWithText("已保存",substring=false).assertExists()
        assertNull(DraftStore(app).read(key));assertEquals(2,attempts)
        assertEquals(p.copy(cnyAmount="145"),ledger.payments.single())
    }
    @Test fun correctionFailureKeepsInputAndRetryWaitsForSave()=exercise(false)
    @Test fun settlementFailureKeepsInputAndRetryWaitsForSave()=exercise(true)
}
