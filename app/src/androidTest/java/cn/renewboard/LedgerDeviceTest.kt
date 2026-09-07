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
    @Test fun overviewRangesSelectRankingAndOpenFullPagesWithFrozenCash() {
        val today=LocalDate.now()
        val names=listOf("ChatGPT","哔哩哔哩大会员","网易云音乐","百度网盘","中国移动","YouTube","Netflix")
        val rows=(0..6).map { i ->Payment(planId="app-$i",planName=names[i],amount=listOf("20","25","18","30","100","13","16")[i],currency=if(i==0) "USD" else "CNY",date=today.toString(),cnyAmount=if(i==0) "142.80" else null,note=if(i==4) "话费扣费" else "") }+
            (1..16).map {i->Payment(planId="old",planName="ChatGPT",amount=(75+i*12).toString(),currency="CNY",date=today.withDayOfMonth(1).minusMonths(i.toLong()).toString())}
        seed(Ledger(payments=rows))
        compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithText("本月实付").assertDoesNotExist()
        compose.onNodeWithText("支出概览").assertExists()
        shot("ledger-overview")
        for(range in TrendRange.entries) {
            compose.onNodeWithContentDescription("趋势范围 ${range.label}").performScrollTo().performClick()
        }
        compose.onNodeWithContentDescription("趋势范围 按年").performScrollTo().performClick()
        compose.onNodeWithContentDescription("上一年").performScrollTo().performClick()
        compose.onNodeWithText("${today.year-1} 年",substring=false).assertExists()
        compose.onNodeWithContentDescription("趋势范围 近3个月").performScrollTo().performClick()
        compose.onNode(hasContentDescription("${today.year}/${today.monthValue}，支出",substring=true)).performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("${today.year}年${today.monthValue}月 · 按应用").assertExists()
        compose.onNodeWithContentDescription("返回概览").assertDoesNotExist()
        click("展开全部 7 项")
        click("最近付款")
        shot("ledger-ranking")
        click("所选时段明细")
        shot("ledger-receipts")
        compose.onNodeWithText("话费扣费",substring=false).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("中国移动",substring=false).assertExists()
        compose.onNodeWithText("ChatGPT",substring=false).assertDoesNotExist()
        compose.onAllNodesWithText(today.toString(),substring=false).assertCountEquals(1)
        compose.onNodeWithText("中国移动",substring=false).performClick()
        compose.onNodeWithText("付款详情").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()
        compose.onNodeWithText("${today.year}年${today.monthValue}月 明细").assertExists()
        compose.onNodeWithText("ChatGPT",substring=false).assertDoesNotExist()
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()
        compose.onNodeWithText("支出概览").assertExists()
    }
    @Test fun deleteOrphanPaymentsInBatchAndSingleReceiptWithoutChangingBalance() {
        val today=LocalDate.now()
        val plan=Plan(id="phone",name="中国移动",amount="30",billingAnchor=today.plusMonths(1).toString(),balanceAccount=BalanceAccount("150",today.toString()))
        val topup=Payment(planId=plan.id,planName=plan.name,amount="50",currency="CNY",date=today.toString(),note="话费充值")
        val orphan=Payment(planId="deleted",planName="旧会员",amount="20",currency="CNY",date=today.toString())
        seed(Ledger(plans=listOf(plan),payments=listOf(topup,orphan,orphan.copy(id="other",currency="USD",cnyAmount=null))))
        compose.onNodeWithText("账本",substring=false).performClick();click("全部明细")
        compose.onNodeWithText("已删订阅").performScrollTo().performClick()
        compose.onNodeWithText("管理").performClick();compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("删除 2 笔付款？").assertExists()
        compose.onNodeWithText("取消",substring=false).performClick();assertEquals(3,read().payments.size)
        compose.onNodeWithText("删除所选").performClick();compose.onNodeWithText("确认删除").performClick()
        waitFor {it.payments.size==1};assertEquals(plan,read().plans.single())
        compose.onNodeWithText("完成").performClick();compose.onNodeWithText("全部",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("中国移动",substring=false).performClick()
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("删除这笔付款").performClick();compose.onNodeWithText("确认删除").performClick()
        waitFor {it.payments.isEmpty()};assertEquals(plan,read().plans.single())
        compose.onNodeWithContentDescription("返回概览").performClick()
        compose.onNodeWithText("范围支出 ¥0.00",substring=false).assertExists()
    }
    @Test fun selectedChartBucketFiltersExactDayMonthAndYearAndRangeResetsSelection() {
        val today=LocalDate.now()
        val rows=listOf(
            Payment(planId="one",planName="ChatGPT",amount="10",currency="CNY",date=today.toString()),
            Payment(planId="one",planName="ChatGPT",amount="20",currency="CNY",date=today.minusDays(1).toString()),
            Payment(planId="one",planName="ChatGPT",amount="40",currency="CNY",date=today.withDayOfYear(1).minusDays(1).toString())
        )
        seed(Ledger(payments=rows));compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithContentDescription("趋势范围 近30天").performScrollTo().performClick()
        compose.onNode(hasContentDescription("${today.monthValue}/${today.dayOfMonth}，支出",substring=true)).performScrollTo().performClick().assertIsSelected()
        compose.onNode(hasContentDescription("ChatGPT，支出¥10.00",substring=true)).assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        for(range in listOf(TrendRange.THREE,TrendRange.SIX,TrendRange.TWELVE,TrendRange.YEAR)) {
            compose.onNodeWithContentDescription("趋势范围 ${range.label}").performScrollTo().performClick()
            compose.onNode(hasContentDescription("${today.year}/${today.monthValue}，支出",substring=true)).performScrollTo().performClick().assertIsSelected()
            val amount=if(today.dayOfMonth==1) "10.00" else "30.00"
            compose.onNode(hasContentDescription("ChatGPT，支出¥$amount",substring=true)).assertExists()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
        }
        compose.onNodeWithContentDescription("趋势范围 近5年").performScrollTo().performClick()
        compose.onNode(hasContentDescription("${today.year-1}，支出",substring=true)).performScrollTo().performClick().assertIsSelected()
        val previousYearAmount=if(today.dayOfYear==1) "60.00" else "40.00"
        compose.onNode(hasContentDescription("ChatGPT，支出¥$previousYearAmount",substring=true)).assertExists()
        compose.onNodeWithContentDescription("趋势范围 全部").performScrollTo().performClick()
        compose.onNodeWithText("全部 · 按应用").assertExists()
        compose.onNode(hasContentDescription("ChatGPT，支出¥70.00",substring=true)).assertExists()
    }
    @Test fun missingForeignCashCanBeSupplementedOnFullPage() {
        val today=LocalDate.now()
        val p=Payment(planId="foreign",planName="ChatGPT",amount="20",currency="USD",date=today.toString())
        seed(Ledger(payments=listOf(p)));compose.onNodeWithText("账本",substring=false).performClick();click("全部明细")
        compose.onNodeWithText("补录人民币").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("人民币实付金额").performTextInput("142.80")
        compose.onNodeWithText("保存金额").performScrollTo().performClick()
        waitFor {it.payments.single().cnyAmount=="142.80"}
        compose.onNodeWithText("全部付款").assertExists()
        compose.onNodeWithText("¥142.80",substring=false).assertExists()
    }
    @Test fun phoneMonthlyFeeCountsAsExpenseAndRechargeRemainsSeparate() {
        val today=LocalDate.now()
        val fee=Payment(planId="phone",planName="中国移动",amount="30",currency="CNY",date=today.toString(),note="话费扣费")
        val topup=fee.copy(id="recharge",amount="100",note="话费充值")
        seed(Ledger(payments=listOf(fee,topup)))
        compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithText("范围支出 ¥30.00",substring=false).assertExists()
        compose.onNode(hasContentDescription("中国移动，支出¥30.00",substring=true)).assertExists()
        compose.onNodeWithText("充值 · 不计支出",substring=false).assertDoesNotExist()
        click("全部明细")
        compose.onNodeWithText("2 笔 · 支出 ¥30.00",substring=false).assertExists()
        compose.onNodeWithText("充值 ¥100.00 · 不计支出",substring=false).assertExists()
        compose.onNodeWithText("充值 · 不计支出",substring=false).assertExists()
        compose.onNodeWithText("话费扣费",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("1 笔 · 支出 ¥30.00",substring=false).assertExists()
        compose.onNodeWithText("¥100.00",substring=false).assertDoesNotExist()
        compose.onNodeWithText("中国移动",substring=false).performClick()
        compose.onNodeWithText("按设置的月费记录，非运营商账单。",substring=false).assertExists()
        compose.onNodeWithContentDescription("返回明细").performClick()
        compose.onNodeWithText("话费充值",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("1 笔 · 支出 ¥0.00",substring=false).assertExists()
        compose.onNodeWithText("¥100.00",substring=false).assertExists()
    }
    @Test fun correctingReceiptUsesFullPageAndDoesNotRenewBenefits() {
        val today=LocalDate.now()
        val plan=Plan(id="editing",name="ChatGPT",amount="20",currency="USD",billingAnchor=today.minusMonths(1).toString())
        val benefit=Benefit(id="editing-benefit",planId=plan.id,name=plan.name,anchor=today.plusMonths(1).toString())
        val payment=Payment(planId=plan.id,planName=plan.name,amount="20",currency="USD",date=today.toString(),cnyAmount="1428")
        seed(Ledger(plans=listOf(plan),benefits=listOf(benefit),payments=listOf(payment)))
        compose.onNodeWithText("账本",substring=false).performClick();click("全部明细")
        compose.onNodeWithText("ChatGPT",substring=false).performClick()
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("更正付款").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("人民币实付金额").performTextReplacement("142.80")
        compose.onNodeWithText("付款备注").performTextReplacement("更正手误")
        compose.onNodeWithText("保存更正").performScrollTo().performClick()
        waitFor {it.payments.single().cnyAmount=="142.80"}
        assertEquals(listOf(plan),read().plans);assertEquals(listOf(benefit),read().benefits)
        compose.onNodeWithText("付款详情").assertExists()
        compose.onNodeWithText("¥142.80",substring=false).assertExists()
        compose.onNodeWithText("更正手误").assertExists()
    }
    @Test fun deleteSubscriptionCanExplicitlyRemoveLinkedReceipts() {
        val today=LocalDate.now();val p=Plan(name="清理测试",amount="30",billingAnchor=today.toString())
        val b=Benefit(planId=p.id,name=p.name,anchor=today.plusMonths(1).toString())
        val payment=Payment(planId=p.id,planName=p.name,amount="30",currency="CNY",date=today.toString())
        seed(Ledger(plans=listOf(p),benefits=listOf(b),payments=listOf(payment)))
        compose.onNodeWithText("清理测试").performClick()
        compose.onNodeWithContentDescription("订阅更多操作").performClick()
        compose.onNodeWithText("删除订阅").performClick()
        compose.onNodeWithText("同时删除付款记录（1 笔）").performClick()
        compose.onNodeWithText("删除",substring=false).performClick()
        waitFor {it.plans.isEmpty()};assertTrue(read().payments.isEmpty());assertTrue(read().benefits.isEmpty())
    }
    @Test fun selectedDayDetailAndAppRankingKeepTheirPeriodWhenReturning() {
        val today=LocalDate.now()
        val rows=listOf(
            Payment(planId="one",planName="时段应用",amount="10",currency="CNY",date=today.toString()),
            Payment(planId="one",planName="时段应用",amount="20",currency="CNY",date=today.minusDays(1).toString()),
            Payment(planId="two",planName="另一应用",amount="30",currency="CNY",date=today.toString())
        )
        seed(Ledger(payments=rows));compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithContentDescription("趋势范围 近30天").performScrollTo().performClick()
        compose.onNode(hasContentDescription("${today.monthValue}/${today.dayOfMonth}，支出",substring=true)).performScrollTo().performClick()
        click("所选时段明细")
        compose.onNodeWithText("2 笔 · 支出 ¥40.00",substring=false).assertExists()
        compose.onNodeWithText("搜索应用").performTextInput("时段")
        compose.onNodeWithText("1 笔 · 支出 ¥10.00",substring=false).assertExists()
        compose.onNodeWithText("时段应用",substring=false).performClick()
        compose.onNodeWithContentDescription("返回明细").performClick()
        compose.onNodeWithText("搜索应用").assertTextContains("时段")
        compose.onNodeWithText("1 笔 · 支出 ¥10.00",substring=false).assertExists()
        compose.onNodeWithContentDescription("返回概览").performClick()
        compose.onNode(hasContentDescription("${today.monthValue}/${today.dayOfMonth}，支出",substring=true)).assertIsSelected()
        compose.onNode(hasContentDescription("时段应用，支出¥10.00",substring=true)).performScrollTo().performClick()
        compose.onNodeWithText("1 笔 · 支出 ¥10.00",substring=false).assertExists()
        compose.onNodeWithText("另一应用",substring=false).assertDoesNotExist()
        compose.onNodeWithText("¥20.00",substring=false).assertDoesNotExist()
        compose.onNodeWithText("时段应用",substring=false).performClick()
        compose.onNodeWithContentDescription("返回明细").performClick()
        compose.onNodeWithText("1 笔 · 支出 ¥10.00",substring=false).assertExists()
    }

    @Test fun refundCreatesIndependentReceiptAndNegativePeriodThenCascadesOnDeletion() {
        val today=LocalDate.now()
        val plan=Plan(id="refund-plan",name="退款测试",amount="100",billingAnchor=today.minusMonths(1).toString())
        val benefit=Benefit(id="refund-benefit",planId=plan.id,name=plan.name,anchor=today.toString(),renewals=2)
        val payment=Payment(id="refund-original",planId=plan.id,planName=plan.name,amount="100",currency="CNY",date=today.minusDays(1).toString())
        seed(Ledger(plans=listOf(plan),benefits=listOf(benefit),payments=listOf(payment)))
        compose.onNodeWithText("账本",substring=false).performClick();click("全部明细")
        compose.onNodeWithText("退款测试",substring=false).performClick()
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("记录退款",substring=false).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        shot("ledger-refund-form")
        compose.onNodeWithText("退款金额 ¥").performTextInput("40")
        compose.onNodeWithText("保存退款").performClick()
        waitFor {it.payments.size==2}
        assertEquals(payment,read().payments.first());assertEquals(listOf(plan),read().plans);assertEquals(listOf(benefit),read().benefits)
        assertEquals(payment.id,read().payments.last().refundOf)
        compose.onNodeWithContentDescription("返回明细").performClick()
        compose.onNodeWithContentDescription("返回概览").performClick()
        compose.onNodeWithContentDescription("趋势范围 近30天").performScrollTo().performClick()
        compose.onNode(hasContentDescription("${today.monthValue}/${today.dayOfMonth}，支出¥-40.00",substring=true)).performScrollTo().performClick()
        compose.onNode(hasContentDescription("净退款，零线下方",substring=true)).assertExists()
        shot("ledger-net-refund-trend")
        compose.onNode(hasContentDescription("退款测试，支出¥-40.00，净支出",substring=true)).performScrollTo()
        shot("ledger-net-refund-ranking")
        compose.onNode(hasContentDescription("退款测试，支出¥-40.00，净支出",substring=true)).performClick()
        compose.onNodeWithText("1 笔 · 支出 ¥-40.00",substring=false).assertExists()
        compose.onNodeWithText("退款测试",substring=false).performClick()
        compose.onNodeWithText("退款详情",substring=false).assertExists()
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("记录退款",substring=false).assertDoesNotExist()
        compose.onNodeWithText("更正退款",substring=false).assertExists()
        // The popup window must receive the real Back key before the activity's BackHandler.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("更正退款",substring=false).assertDoesNotExist()
        compose.onNodeWithText("退款详情",substring=false).assertExists()
        compose.onNodeWithContentDescription("返回明细").performClick()
        compose.onNodeWithContentDescription("返回概览").performClick()
        compose.onNodeWithContentDescription("趋势范围 近3个月").performScrollTo().performClick()
        click("全部明细")
        compose.onNode(hasText("¥100.00") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("付款更多操作").performClick()
        compose.onNodeWithText("删除这笔付款",substring=false).performClick()
        compose.onNodeWithText("删除 2 笔付款？",substring=false).assertExists()
        compose.onNodeWithText("包含关联退款 1 笔，将一并删除。",substring=false).assertExists()
        compose.onNodeWithText("确认删除",substring=false).performClick()
        waitFor {it.payments.isEmpty()};assertEquals(listOf(benefit),read().benefits)
    }

}
