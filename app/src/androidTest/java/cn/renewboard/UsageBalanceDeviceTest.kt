package cn.renewboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class UsageBalanceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        runBlocking {app.repository.update {Ledger()}};compose.waitForIdle()
    }
    @After fun cleanup() {runBlocking {app.repository.update {Ledger()}};app.getSharedPreferences("form-drafts",0).edit().clear().commit()}
    private fun field(name:String):SemanticsNodeInteraction {
        val matcher=hasText(name) and hasSetTextAction()
        try {compose.waitUntil(5000) {compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:Throwable) {throw AssertionError("Missing field $name\n"+compose.onRoot().printToString(),e)}
        return compose.onNode(matcher)
    }
    private fun read()=runBlocking {app.repository.read()}
    private fun waitFor(check:(Ledger)->Boolean) {
        try {compose.waitUntil(10000) {check(read())}} catch(e:Throwable) {throw AssertionError("Ledger did not reach expected state\n"+compose.onRoot().printToString(),e)}
        compose.waitForIdle()
    }
    private fun click(s:String)=compose.onNode(hasText(s,substring=false) and !hasSetTextAction()).performScrollTo().performClick()
    private fun tab(s:String)=compose.onNodeWithText(s,substring=false).performClick()
    private fun openServices() {
        tab("账本")
        compose.onNodeWithContentDescription("打开服务管理").performScrollTo().performClick()
    }
    private fun returnToLedger() {
        compose.onNodeWithContentDescription("返回账本").performClick()
        compose.onNodeWithText("支出概览",substring=false).assertExists()
        compose.waitUntil(10000) {ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
    }
    private fun create(name:String,balance:String) {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        field("搜索会员").performTextReplacement(name)
        click(name)
        compose.onNodeWithText("每月扣费金额",substring=false).assertDoesNotExist()
        compose.onNodeWithText("包含的权益",substring=false).assertDoesNotExist()
        field("查询到的余额").performTextReplacement(balance)
        compose.onNodeWithText("保存订阅",substring=false).performClick()
        waitFor {it.plans.size==1}
        assertNotNull(read().plans.single().usageBalance)
        assertTrue(read().payments.isEmpty());assertTrue(read().benefits.isEmpty())
        openServices();click(name)
    }
    private fun back() {compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    @Test fun walletCreationTopupAndConsumptionDoNotDuplicateExpenses() {
        create("DeepSeek API","200")
        compose.onNodeWithText("记录充值",substring=false).performClick()
        field("充值金额 ¥").performTextReplacement("50")
        compose.onNodeWithText("保存充值",substring=false).performClick()
        waitFor {it.payments.size==1}
        assertEquals("250",read().plans.single().usageBalance!!.balance)
        compose.onNodeWithText("更新余额",substring=false).performClick()
        field("查询到的余额").performTextReplacement("123.45")
        compose.onNodeWithText("保存余额",substring=false).performClick()
        waitFor {it.plans.single().usageBalance!!.balance=="123.45"}
        assertEquals(1,read().payments.size);assertEquals("50",read().payments.single().amount)
        assertTrue(Book.forecastCharges(read(),LocalDate.now(),LocalDate.now().plusDays(30)).isEmpty())
        assertTrue(ReminderPolicy.events(read()).isEmpty())
        compose.onNodeWithText("全部记录",substring=false).performClick()
        compose.onNodeWithContentDescription("选择付款类型").performClick()
        compose.onNodeWithContentDescription("付款类型 平台充值").performClick()
        compose.onNodeWithText("1 笔 · 净支出 ¥50.00",substring=false).assertExists()
        compose.onNodeWithText("DeepSeek API",substring=false).performClick()
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("更正付款",substring=false).performClick()
        compose.onNodeWithText("仅修改账目，平台余额不变。",substring=false).assertExists()
        compose.onNodeWithText("添加备注",substring=false).assertDoesNotExist()
        field("付款金额 ¥").performTextReplacement("45")
        compose.onNodeWithText("保存更正",substring=false).performClick()
        waitFor {it.payments.single().amount=="45"}
        assertEquals("平台充值",read().payments.single().note)
        assertEquals("123.45",read().plans.single().usageBalance!!.balance)
    }
    @Test fun foreignWalletTopupRequiresFrozenYuanAndDraftSurvivesExit() {
        create("OpenAI API","10")
        compose.onNodeWithText("记录充值",substring=false).performClick()
        field("充值金额 USD").performTextReplacement("5")
        compose.onNodeWithText("保存充值",substring=false).performClick()
        compose.onNodeWithText("请填写付款当天的实际人民币金额",substring=false).assertExists()
        field("人民币实付金额").performScrollTo().performTextReplacement("36.20")
        back();compose.onNodeWithText("保留草稿",substring=false).performClick()
        compose.onNodeWithText("记录充值",substring=false).performClick()
        field("人民币实付金额").assertTextContains("36.20")
        compose.onNodeWithText("保存充值",substring=false).performClick()
        waitFor {it.payments.size==1}
        assertEquals("15",read().plans.single().usageBalance!!.balance)
        assertEquals("36.20",read().payments.single().cnyAmount)
        compose.onNodeWithText("更新余额",substring=false).performClick()
        field("查询到的余额").performTextReplacement("7")
        compose.onNodeWithText("保存余额",substring=false).performClick()
        waitFor {it.plans.single().usageBalance!!.balance=="7"}
        assertEquals("36.20",read().payments.single().cnyAmount)
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithText("更新余额",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("USD 7.00",substring=false).assertExists()
    }
    @Test fun domainAndServerPresetsRemainPeriodicSubscriptions() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        field("搜索会员").performTextReplacement("域名");click("域名")
        compose.onNodeWithContentDescription("选择周期单位").assertTextContains("年")
        field("套餐价格").performTextReplacement("99")
        compose.onNodeWithText("保存订阅",substring=false).performClick()
        waitFor {it.plans.size==1}
        assertEquals(Cycle.YEAR,read().plans.single().cycle);assertNull(read().plans.single().usageBalance)
        assertEquals(1,read().benefits.size);assertEquals("99",read().payments.single().amount)
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        field("搜索会员").performTextReplacement("服务器");click("服务器")
        compose.onNodeWithContentDescription("选择周期单位").assertTextContains("月")
        field("套餐价格").performTextReplacement("30")
        compose.onNodeWithText("保存订阅",substring=false).performClick()
        waitFor {it.plans.size==2}
        assertEquals(Cycle.MONTH,read().plans.single {it.name=="服务器"}.cycle)
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        field("搜索会员").performTextReplacement("健身房");click("健身")
        field("套餐价格").performTextReplacement("199")
        compose.onNodeWithText("保存订阅",substring=false).performClick()
        waitFor {it.plans.size==3}
        val gym=read().plans.single {it.name=="健身"}
        assertEquals(Cycle.MONTH,gym.cycle);assertNull(gym.usageBalance);assertNull(gym.balanceAccount)
        assertTrue(read().benefits.any {it.planId==gym.id})
        assertEquals("199",read().payments.single {it.planId==gym.id}.amount)
        compose.onNodeWithContentDescription("健身图标",useUnmergedTree=true).assertExists()
    }
    @Test fun serviceManagementReturnsWithKeyboardAndRetainsQueryAcrossTabsAndRecreation() {
        openServices()
        field("搜索服务").performClick().performTextInput("DeepSeek")
        compose.waitUntil(10000) {ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        returnToLedger()
        tab("到期");compose.onNodeWithText("订阅簿",substring=false).assertExists()
        repeat(12) {
            openServices();compose.onNodeWithText("服务管理",substring=false).assertExists()
            field("搜索服务").assertTextContains("DeepSeek")
            returnToLedger()
            tab("到期");compose.onNodeWithText("订阅簿",substring=false).assertExists()
        }
        openServices();field("搜索服务").performClick()
        compose.waitUntil(10000) {ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        returnToLedger();tab("到期");openServices()
        compose.onNodeWithText("服务管理",substring=false).assertExists()
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithText("服务管理",substring=false).fetchSemanticsNodes().isNotEmpty()}
        field("搜索服务").assertTextContains("DeepSeek")
        returnToLedger();tab("到期");compose.onNodeWithText("订阅簿",substring=false).assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
class UsageBalanceSaveDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val app:RenewApp get()=ApplicationProvider.getApplicationContext()
    private val p=Plan(id="usage-save",name="DeepSeek API",amount="0",billingAnchor=LocalDate.now().toString(),autoRenew=false,paidCycles=0,
        usageBalance=UsageBalance("100",LocalDate.now().toString()))
    @Before fun setup() {check(app.packageName.endsWith(".debug"));clear()}
    @After fun clear() {UsageAction.entries.forEach {DraftStore(app).remove("usage:${p.id}:$it")}}
    private fun exercise(action:UsageAction) {
        var ledger=Ledger(plans=listOf(p));var attempts=0
        val commit=CompletableDeferred<Unit>()
        compose.setContent {
            var closed by remember {mutableStateOf(false)}
            MaterialTheme {
                if(closed) Text("已完成") else UsageBalanceEditor(ledger,p,action,{closed=true},save={transform->
                    attempts++;if(attempts==1) throw IllegalStateException("临时保存失败")
                    commit.await();ledger=transform(ledger)
                })
            }
        }
        val topUp=action==UsageAction.TOP_UP
        val label=if(topUp) "充值金额 ¥" else "查询到的余额"
        val button=if(topUp) "保存充值" else "保存余额"
        val key="usage:${p.id}:$action"
        compose.onNode(hasText(label) and hasSetTextAction()).performTextReplacement("20")
        compose.onNodeWithText(button,substring=false).performClick()
        compose.onNodeWithText("临时保存失败",substring=false).assertExists()
        assertNotNull(DraftStore(app).read(key));assertEquals(p,ledger.plans.single());assertTrue(ledger.payments.isEmpty())
        compose.onNodeWithText(button,substring=false).performClick()
        compose.onNodeWithText("正在保存…",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("已完成",substring=false).assertDoesNotExist()
        compose.runOnIdle {commit.complete(Unit)}
        compose.onNodeWithText("已完成",substring=false).assertExists()
        assertNull(DraftStore(app).read(key));assertEquals(2,attempts)
        assertEquals(if(topUp) "120" else "20",ledger.plans.single().usageBalance!!.balance)
        assertEquals(if(topUp) 1 else 0,ledger.payments.size)
    }
    @Test fun rechargeFailureKeepsInputAndRetryWaitsForCommit()=exercise(UsageAction.TOP_UP)
    @Test fun calibrationFailureKeepsInputAndRetryWaitsForCommit()=exercise(UsageAction.CALIBRATE)
}
