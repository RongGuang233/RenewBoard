package cn.renewboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LedgerDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {app.repository.update {Ledger()}};compose.waitForIdle()
    }
    @After fun cleanup() {runBlocking {app.repository.update {Ledger()}}}
    private fun seed(l:Ledger) {runBlocking {app.repository.update {l}};compose.waitForIdle()}
    private fun click(text:String) {compose.onNodeWithText(text).performScrollTo().performClick()}
    private fun read()=runBlocking {app.repository.read()}
    private fun waitFor(check:(Ledger)->Boolean) {compose.waitUntil(10000) {check(read())};compose.waitForIdle()}
    private fun shot(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(300)
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun overviewRangesDrilldownAndRankingWorkWithFrozenCash() {
        val today=LocalDate.now()
        val names=listOf("ChatGPT","哔哩哔哩大会员","网易云音乐","百度网盘","中国移动","YouTube","Netflix")
        val rows=(0..6).map { i ->Payment(planId="app-$i",planName=names[i],amount=listOf("20","25","18","30","100","13","16")[i],currency=if(i==0) "USD" else "CNY",date=today.toString(),cnyAmount=if(i==0) "142.80" else null,note=if(i==4) "话费充值" else "") }+
            (1..16).map {i->Payment(planId="old",planName="ChatGPT",amount=(75+i*12).toString(),currency="CNY",date=today.withDayOfMonth(1).minusMonths(i.toLong()).toString())}
        seed(Ledger(payments=rows))
        compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithText("¥344.80",substring=false).assertExists()
        shot("ledger-overview")
        for(range in TrendRange.entries) {
            compose.onNodeWithContentDescription("趋势范围 ${range.label}").performScrollTo().performClick()
        }
        compose.onNodeWithContentDescription("趋势范围 1年").performScrollTo().performClick()
        compose.onNodeWithContentDescription("上一年").performScrollTo().performClick()
        compose.onNodeWithText("${today.year-1} 年",substring=false).assertExists()
        compose.onNodeWithContentDescription("趋势范围 3月").performScrollTo().performClick()
        compose.onNode(hasContentDescription("${today.year}/${today.monthValue}，实付",substring=true)).performScrollTo().performClick()
        compose.onNodeWithText("${today.year}/${today.monthValue}付款").assertExists()
        compose.onNodeWithContentDescription("返回概览").performClick()
        click("展开全部 7 项")
        click("最近付款")
        shot("ledger-ranking")
        click("全部明细")
        compose.onNodeWithText("话费充值",substring=false).performClick()
        compose.onNode(hasText("中国移动",substring=false) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("ChatGPT",substring=false) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }
    @Test fun deleteOrphanPaymentsInBatchAndSingleReceiptWithoutChangingBalance() {
        val today=LocalDate.now()
        val plan=Plan(id="phone",name="中国移动",amount="30",billingAnchor=today.plusMonths(1).toString(),balanceAccount=BalanceAccount("150",today.toString()))
        val topup=Payment(planId=plan.id,planName=plan.name,amount="50",currency="CNY",date=today.toString(),note="话费充值")
        val orphan=Payment(planId="deleted",planName="旧会员",amount="20",currency="CNY",date=today.toString())
        seed(Ledger(plans=listOf(plan),payments=listOf(topup,orphan,orphan.copy(id="other",currency="USD",cnyAmount=null))))
        compose.onNodeWithText("账本",substring=false).performClick();click("全部明细")
        compose.onNodeWithText("已删订阅").performClick()
        compose.onNodeWithText("管理").performClick();compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("删除 2 笔付款？").assertExists()
        compose.onNodeWithText("取消",substring=false).performClick();assertEquals(3,read().payments.size)
        compose.onNodeWithText("删除所选").performClick();compose.onNodeWithText("确认删除").performClick()
        waitFor {it.payments.size==1};assertEquals(plan,read().plans.single())
        compose.onNodeWithText("完成").performClick();compose.onNode(hasText("全部",substring=false) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("中国移动",substring=false) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("删除这笔付款").performClick();compose.onNodeWithText("确认删除").performClick()
        waitFor {it.payments.isEmpty()};assertEquals(plan,read().plans.single())
        compose.onNodeWithContentDescription("返回概览").performClick()
        compose.onNodeWithText("¥0.00",substring=false).assertExists()
    }
    @Test fun deleteSubscriptionCanExplicitlyRemoveLinkedReceipts() {
        val today=LocalDate.now();val p=Plan(name="清理测试",amount="30",billingAnchor=today.toString())
        val b=Benefit(planId=p.id,name=p.name,anchor=today.plusMonths(1).toString())
        val payment=Payment(planId=p.id,planName=p.name,amount="30",currency="CNY",date=today.toString())
        seed(Ledger(plans=listOf(p),benefits=listOf(b),payments=listOf(payment)))
        compose.onNodeWithText("清理测试").performClick();click("删除订阅")
        compose.onNodeWithText("同时删除付款记录（1 笔）").performClick()
        compose.onNodeWithText("删除",substring=false).performClick()
        waitFor {it.plans.isEmpty()};assertTrue(read().payments.isEmpty());assertTrue(read().benefits.isEmpty())
    }
}
