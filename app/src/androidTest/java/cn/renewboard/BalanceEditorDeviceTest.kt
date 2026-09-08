package cn.renewboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
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
class BalanceEditorDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val today=LocalDate.now()
    private val first=Plan(id="balance-editor-a",name="话费草稿甲",amount="30",billingAnchor=today.toString(),balanceAccount=BalanceAccount("100",today.toString()))
    private val second=first.copy(id="balance-editor-b",name="话费草稿乙")
    private val app:RenewApp get()=ApplicationProvider.getApplicationContext()
    private fun clearDrafts() {
        listOf(first,second).forEach {p -> BalanceAction.entries.forEach {DraftStore(app).remove(balanceDraftKey(p.id,it))}}
    }
    @Before fun setup() {
        check(app.packageName.endsWith(".debug"));clearDrafts()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {app.repository.update {Ledger(plans=listOf(first,second))}}
        compose.waitForIdle()
    }
    @After fun cleanup() {clearDrafts();runBlocking {app.repository.update {Ledger()}}}
    private fun click(text:String) {compose.onNodeWithText(text,substring=false).performScrollTo().performClick()}
    private fun fill(label:String,value:String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
    private fun field(label:String)=compose.onNode(hasText(label) and hasSetTextAction())
    private fun back() {compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    private fun open(action:BalanceAction) {click(if(action==BalanceAction.TOP_UP) "记录充值" else action.title)}
    private fun checkDraft(action:BalanceAction,label:String,value:String) {
        click(first.name);open(action);fill(label,value)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodes(hasText(label) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        field(label).assertTextContains(value)
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        assertNotNull(DraftStore(app).read(balanceDraftKey(first.id,action)))
        open(action);field(label).assertTextContains(value)
        back();compose.onNodeWithText("放弃修改",substring=false).performClick()
        assertNull(DraftStore(app).read(balanceDraftKey(first.id,action)))
        open(action);back()
        compose.onNodeWithText("保留${action.title}草稿？",substring=false).assertDoesNotExist()
        assertTrue(runBlocking {app.repository.read()}.payments.isEmpty())
    }
    @Test fun rechargeDraftSurvivesRecreationRetainAndDiscard() {checkDraft(BalanceAction.TOP_UP,"充值金额","35.80")}
    @Test fun calibrationDraftSurvivesRecreationRetainAndDiscard() {checkDraft(BalanceAction.CALIBRATE,"实际余额","85")}
    @Test fun monthlyFeeDraftSurvivesRecreationRetainAndDiscard() {checkDraft(BalanceAction.MONTHLY_FEE,"新月费","40")}
    @Test fun draftsAreIsolatedByAccountAndAction() {
        click(first.name);open(BalanceAction.TOP_UP);fill("充值金额","57")
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        open(BalanceAction.CALIBRATE);assertEquals("",field("实际余额").fetchSemanticsNode().config[SemanticsProperties.EditableText].text);back()
        open(BalanceAction.MONTHLY_FEE);field("新月费").assertTextContains("30");back();back()
        click(second.name);open(BalanceAction.TOP_UP);assertEquals("",field("充值金额").fetchSemanticsNode().config[SemanticsProperties.EditableText].text);back();back()
        click(first.name);open(BalanceAction.TOP_UP);field("充值金额").assertTextContains("57")
    }
    @Test fun historicalChoiceAndPreviewSurviveRecreationAndOnlyUnincludedRechargeAddsBalance() {
        click(first.name);open(BalanceAction.TOP_UP)
        compose.onNodeWithText("余额与月费推算同步更新",substring=true).assertDoesNotExist()
        fill("充值金额","50")
        compose.onNodeWithText("充值后估算余额 ¥150.00").assertExists()
        fill("充值日期",today.minusDays(5).toString())
        compose.onNodeWithText("充值后估算余额",substring=true).assertDoesNotExist()
        compose.onNodeWithText("确认充值").performClick()
        compose.onNodeWithText("请选择这笔充值是否已包含在当前余额中").assertExists()
        click("已包含，仅补记充值记录")
        compose.onNodeWithText("充值后估算余额 ¥100.00").assertExists()
        click("未包含，同时增加余额")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodesWithText("充值后估算余额 ¥150.00").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("未包含，同时增加余额").assertIsSelected()
        compose.onNodeWithText("确认充值").performClick()
        compose.waitUntil(10000) {runBlocking {app.repository.read()}.payments.size==1}
        val saved=runBlocking {app.repository.read()}
        assertEquals("150",saved.plans.single {it.id==first.id}.balanceAccount!!.balance)
        assertEquals(today.minusDays(5).toString(),saved.payments.single().date)
        assertEquals(java.math.BigDecimal.ZERO,Book.paidCny(saved))
        assertNull(DraftStore(app).read(balanceDraftKey(first.id,BalanceAction.TOP_UP)))
    }
}

@RunWith(AndroidJUnit4::class)
class BalanceEditorSaveDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val today=LocalDate.now()
    private val plan=Plan(id="balance-save",name="话费保存测试",amount="30",billingAnchor=today.toString(),balanceAccount=BalanceAccount("100",today.toString()))
    private val app:RenewApp get()=ApplicationProvider.getApplicationContext()
    @Before fun setup() {check(app.packageName.endsWith(".debug"));clear()}
    @After fun cleanup() {clear()}
    private fun clear() {BalanceAction.entries.forEach {DraftStore(app).remove(balanceDraftKey(plan.id,it))}}
    private fun verifySave(action:BalanceAction,label:String,value:String) {
        var ledger=Ledger(plans=listOf(plan))
        var attempts=0
        val commit=CompletableDeferred<Unit>()
        compose.setContent {
            var closed by remember {mutableStateOf(false)}
            MaterialTheme {
                if(closed) Text("操作已保存") else BalanceEditor(ledger,plan,action,{closed=true},save={transform ->
                    attempts++
                    if(attempts==1) throw IllegalStateException("临时保存失败")
                    commit.await();ledger=transform(ledger)
                })
            }
        }
        compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
        compose.onNodeWithText(action.saveLabel).performClick()
        compose.onNodeWithText("临时保存失败").assertExists()
        compose.onNode(hasText(label) and hasSetTextAction()).assertTextContains(value)
        assertEquals(plan,ledger.plans.single())
        assertNotNull(DraftStore(app).read(balanceDraftKey(plan.id,action)))
        compose.onNodeWithText(action.saveLabel).performClick()
        compose.onNodeWithText("正在保存…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回话费详情").performClick()
        compose.onNodeWithText("操作已保存").assertDoesNotExist()
        assertEquals(plan,ledger.plans.single())
        assertNotNull(DraftStore(app).read(balanceDraftKey(plan.id,action)))
        compose.runOnIdle {commit.complete(Unit)}
        compose.onNodeWithText("操作已保存").assertExists()
        assertNull(DraftStore(app).read(balanceDraftKey(plan.id,action)))
        assertEquals(2,attempts)
        when(action) {
            BalanceAction.TOP_UP -> {assertEquals("120",ledger.plans.single().balanceAccount!!.balance);assertEquals(1,ledger.payments.size)}
            BalanceAction.CALIBRATE -> {assertEquals("80",ledger.plans.single().balanceAccount!!.balance);assertTrue(ledger.payments.isEmpty())}
            BalanceAction.MONTHLY_FEE -> {assertEquals("40",ledger.plans.single().balanceAccount!!.pendingFee!!.amount);assertTrue(ledger.payments.isEmpty())}
        }
    }
    @Test fun rechargeFailureRetainsInputAndRetryAwaitsSave() {verifySave(BalanceAction.TOP_UP,"充值金额","20")}
    @Test fun calibrationFailureRetainsInputAndRetryAwaitsSave() {verifySave(BalanceAction.CALIBRATE,"实际余额","80")}
    @Test fun feeFailureRetainsInputAndRetryAwaitsSave() {verifySave(BalanceAction.MONTHLY_FEE,"新月费","40")}
}
