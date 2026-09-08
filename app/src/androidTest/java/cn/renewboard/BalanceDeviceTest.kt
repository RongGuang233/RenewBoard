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
class BalanceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app: RenewApp
    @Before fun reset() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        app.getSharedPreferences("form-drafts",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking { app.repository.update { Ledger() } };compose.waitForIdle()
    }
    @After fun cleanup() {
        app.getSharedPreferences("form-drafts",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { app.repository.update { Ledger() } }
    }
    private fun fill(label:String,value:String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    private fun click(text:String) {
        if(text=="选择常见会员" && compose.onAllNodes(hasText("搜索会员") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) return
        if(text=="保存订阅") {
            // The save action is fixed outside the form's scroll container.
            compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            return
        }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun ledger()=runBlocking { app.repository.read() }
    private fun await(check:(Ledger)->Boolean):Ledger { compose.waitUntil(10000) { check(ledger()) };compose.waitForIdle();return ledger() }
    @Test fun phonePresetTracksBalanceRechargeAndCalibrationWithoutDoubleCounting() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        click("选择常见会员")
        compose.onNode(hasText("搜索会员") and hasSetTextAction()).performTextReplacement("中国移动")
        compose.onNode(hasText("中国移动",substring=false) and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText("周期",substring=false).assertDoesNotExist()
        compose.onNodeWithText("包含的权益").assertDoesNotExist()
        compose.onNodeWithText("余额日期").assertDoesNotExist()
        compose.onNodeWithText("每月扣费日期").assertDoesNotExist()
        fill("每月扣费日","1")
        fill("每月扣费金额","30")
        fill("查询到的余额","100")
        click("保存订阅")
        val created=await { it.plans.size==1 }
        assertTrue(created.payments.isEmpty());assertTrue(created.benefits.isEmpty())
        compose.onNodeWithText("¥30.00",substring=false).assertExists()
        compose.onNodeWithText("中国移动").performClick()
        compose.onNodeWithText("¥100.00").assertExists()
        click("记录充值")
        fill("充值金额","50")
        // Fixed footer moves during IME animation; trigger its action after input.
        compose.onNodeWithText("确认充值").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val recharged=await { it.payments.size==1 }
        assertEquals("50",recharged.payments.single().amount)
        assertEquals(java.math.BigDecimal.ZERO,Book.paidCny(recharged))
        assertEquals(0,Prepaid.balance(recharged.plans.single(),LocalDate.now()).compareTo(java.math.BigDecimal("150")))
        compose.onNodeWithText("¥150.00").assertExists()
        click("校准余额")
        fill("实际余额","125")
        compose.onNodeWithText("确认校准").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val corrected=await { it.plans.single().balanceAccount?.balance=="125" }
        assertEquals(recharged.payments,corrected.payments)
        click("修改月费")
        fill("新月费","40")
        compose.onNodeWithText("立即",substring=false).performClick()
        compose.onNodeWithText("保存月费",substring=false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val calibrated=await { it.plans.single().amount=="40" }
        assertEquals(recharged.payments,calibrated.payments)
        assertEquals(calibrated,Book.decode(Book.encode(calibrated)).data)
        compose.onNodeWithText("¥125.00").assertExists()
        compose.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"phone-balance.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun existingPhoneConvertsWithoutRemovingHistoricalPaymentsOrBenefits() {
        val today=LocalDate.now()
        val p=Plan(name="中国电信",amount="39",billingAnchor=today.toString())
        val b=Benefit(planId=p.id,name=p.name,anchor=today.plusMonths(1).toString())
        val receipt=Payment(planId=p.id,planName=p.name,amount="100",currency="CNY",date=today.toString())
        runBlocking { app.repository.update { Ledger(plans=listOf(p),benefits=listOf(b),payments=listOf(receipt)) } }
        compose.waitForIdle();compose.onNodeWithText("中国电信").performClick()
        compose.onNodeWithContentDescription("订阅更多操作").performScrollTo().performClick()
        compose.onNodeWithText("编辑订阅与到期日").performClick()
        click("话费余额")
        fill("查询到的余额","61")
        click("保存订阅")
        val converted=await { it.plans.single().balanceAccount!=null }
        assertEquals(listOf(receipt),converted.payments)
        assertEquals(listOf(b),converted.benefits)
        assertTrue(Book.due(converted,today.plusMonths(1)).isEmpty())
        compose.onNodeWithText("中国电信").performClick()
        compose.onNodeWithText("¥61.00").assertExists()
    }
    @Test fun recordedBalanceDateCreatesMonthlyFeesAndReopenDoesNotDuplicateThem() {
        val today=LocalDate.now()
        val anchor=today.minusMonths(1)
        val p=Plan(name="中国移动",amount="30",billingAnchor=anchor.toString(),paidCycles=0,
            balanceAccount=BalanceAccount("100",anchor.minusDays(1).toString()))
        runBlocking {app.repository.update {Ledger(plans=listOf(p))}}
        val saved=await {it.payments.size==2}
        assertTrue(saved.payments.all {it.note=="话费扣费"})
        assertEquals(java.math.BigDecimal("60"),Book.paidCny(saved))
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        assertEquals(saved.payments,ledger().payments)
        compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNodeWithText("中国移动").assertExists()
        compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithText("净支出 ¥60.00").assertExists()
    }

    @Test fun historicalRechargeRequiresExplicitBalanceChoiceAndCalibrationCanRecordExpense() {
        val today=LocalDate.now()
        val p=Plan(name="中国移动",amount="30",billingAnchor=today.toString(),paidCycles=0,
            balanceAccount=BalanceAccount("100",today.toString()))
        runBlocking {app.repository.update {Ledger(plans=listOf(p))}}
        compose.waitForIdle();compose.onNodeWithText("中国移动").performClick()
        click("记录充值")
        fill("充值金额","50")
        fill("充值日期",today.minusDays(5).toString())
        compose.onNodeWithText("确认充值").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("请选择这笔充值是否已包含在当前余额中").assertExists()
        assertTrue(ledger().payments.isEmpty())
        click("已包含，仅补记充值记录")
        compose.onNodeWithText("确认充值").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val history=await {it.payments.size==1}
        assertEquals("100",history.plans.single().balanceAccount!!.balance)
        click("校准余额")
        fill("实际余额","85")
        click("差额 ¥15.00 记为额外支出")
        compose.onNodeWithText("确认校准").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val calibrated=await {it.payments.size==2}
        assertEquals(java.math.BigDecimal("15"),Book.paidCny(calibrated))
        assertEquals("85",calibrated.plans.single().balanceAccount!!.balance)
        compose.onNodeWithText("删除订阅").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多操作").performScrollTo().performClick()
        compose.onNodeWithText("删除订阅").assertExists()
    }

    @Test fun monthlyFeeDialogSchedulesAndCancelsNextMonthPriceWithoutChangingReceipts() {
        val today=LocalDate.now()
        val p=Plan(name="中国移动",amount="30",billingAnchor=today.toString(),paidCycles=0,
            balanceAccount=BalanceAccount("100",today.toString()))
        runBlocking {app.repository.update {Ledger(plans=listOf(p))}}
        compose.waitForIdle();compose.onNodeWithText("中国移动").performClick()
        click("修改月费")
        fill("新月费","40")
        compose.onNodeWithText("下月",substring=false).assertIsSelected()
        compose.onNodeWithText("保存月费",substring=false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val scheduled=await {it.plans.single().balanceAccount?.pendingFee!=null}
        assertEquals("30",scheduled.plans.single().amount)
        assertTrue(scheduled.payments.isEmpty())
        assertEquals(java.time.YearMonth.from(today).plusMonths(1).atDay(1).toString(),scheduled.plans.single().balanceAccount!!.pendingFee!!.effectiveDate)
        click("修改月费")
        compose.onNodeWithText("取消待生效调整").performScrollTo().performClick()
        val cancelled=await {it.plans.single().balanceAccount?.pendingFee==null}
        assertEquals("30",cancelled.plans.single().amount)
        assertTrue(cancelled.payments.isEmpty())
    }

    @Test fun customFeeMonthSelectsYearAndMonthAndCancelDoesNotApply() {
        val today=LocalDate.now()
        val p=Plan(id="month-choice",name="月份选择话费",amount="39",billingAnchor=today.toString(),paidCycles=0,balanceAccount=BalanceAccount("200",today.toString()))
        runBlocking {app.repository.update {Ledger(plans=listOf(p))}};compose.waitForIdle()
        click(p.name);click("修改月费");click("指定月份")
        compose.onNodeWithContentDescription("选择生效月份").performClick()
        val nextYear=java.time.YearMonth.from(today).plusMonths(1).year+1
        compose.onNodeWithContentDescription("下一年").performClick()
        compose.onNodeWithContentDescription("生效月份 ${nextYear}年2月").performClick()
        val picker=isDialog() and hasAnyDescendant(hasContentDescription("选择生效年份"))
        compose.onNode(hasText("取消") and hasAnyAncestor(picker)).performClick()
        val originalMonth=java.time.YearMonth.from(today).plusMonths(1)
        compose.onNodeWithText("${originalMonth.year}年${originalMonth.monthValue}月起生效").assertExists()
        assertNull(ledger().plans.single().balanceAccount!!.pendingFee)
        compose.onNodeWithContentDescription("选择生效月份").performClick()
        compose.onNodeWithContentDescription("下一年").performClick()
        compose.onNodeWithContentDescription("生效月份 ${nextYear}年2月").performClick()
        compose.onNode(hasText("确定") and hasAnyAncestor(picker)).performClick()
        fill("新月费","29")
        compose.onNodeWithText("保存月费",substring=false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val saved=await {it.plans.single().balanceAccount?.pendingFee!=null}
        assertEquals(MonthlyFeeChange("29","$nextYear-02-01"),saved.plans.single().balanceAccount!!.pendingFee)
        assertEquals("39",saved.plans.single().amount)
        assertTrue(saved.payments.isEmpty())
    }

}
