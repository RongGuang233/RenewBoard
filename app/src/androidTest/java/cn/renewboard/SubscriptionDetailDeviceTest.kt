package cn.renewboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SubscriptionDetailDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    private val today=LocalDate.now()
    @Before fun reset() {
        app=ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
    }
    @After fun cleanup() {
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
    }
    private fun ledger()=runBlocking {app.repository.read()}
    private fun seed(l:Ledger) {runBlocking {app.repository.update {l}};compose.waitForIdle()}
    private fun waitFor(check:(Ledger)->Boolean) {compose.waitUntil(10000) {check(ledger())};compose.waitForIdle()}
    private fun click(text:String)=compose.onNodeWithText(text,substring=false).performScrollTo().performClick()
    private fun openPlan(name:String,archived:Boolean=false) {
        compose.onNodeWithText("订阅",substring=false).performClick()
        if(archived) click("已归档")
        click(name)
    }

    @Test fun bundleUsesCompactBenefitsFixedPaymentAndThreeRecentReceipts() {
        val p=Plan(id="bundle",name="联合会员",amount="40",billingAnchor=today.toString())
        val benefits=(1..4).map {Benefit(id="right-$it",planId=p.id,name="权益 $it",anchor=today.plusMonths(1).toString())}
        val receipts=(1..4).map {Payment(id="receipt-$it",planId=p.id,planName=p.name,amount="$it",currency="CNY",date=today.minusDays(it.toLong()).toString())}
        // Same application name must not leak another subscription's records into this history.
        val other=Payment(id="unrelated",planId="other-plan",planName=p.name,amount="99",currency="CNY",date=today.toString())
        seed(Ledger(plans=listOf(p),benefits=benefits,payments=receipts+other))
        openPlan(p.name)
        compose.onNodeWithText("增加赠送时长").assertDoesNotExist()
        compose.onNodeWithText("记录付款 / 提前续费").assertIsDisplayed()
        val buttonBefore=compose.onNodeWithText("记录付款 / 提前续费").fetchSemanticsNode().boundsInRoot
        click("全部记录")
        compose.onNodeWithText("话费充值").assertDoesNotExist()
        compose.onNodeWithText("付款",substring=false).assertExists()
        compose.onNodeWithText("4 笔 · 净支出 ¥10.00").assertExists()
        compose.onNodeWithText(today.minusDays(4).toString()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回订阅详情").performClick()
        compose.onNodeWithText(today.minusDays(4).toString()).assertDoesNotExist()
        compose.onNodeWithText(today.minusDays(3).toString()).performScrollTo()
        compose.onNodeWithText("记录付款 / 提前续费").assertIsDisplayed()
        assertEquals(buttonBefore,compose.onNodeWithText("记录付款 / 提前续费").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("权益 2权益更多操作").performScrollTo().performClick()
        compose.onNodeWithText("增加赠送时长").performClick()
        compose.onNode(hasText("增加天数") and hasSetTextAction()).performTextReplacement("5")
        compose.onNodeWithText("增加",substring=false).performClick()
        waitFor {it.benefits.find {b->b.id=="right-2"}?.giftDays==5}
        assertTrue(ledger().benefits.filter {it.id!="right-2"}.all {it.giftDays==0})
        assertEquals(receipts+other,ledger().payments)
        compose.onNodeWithText("记录付款 / 提前续费").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.onNodeWithText("续费权益 4/4 项").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("续费权益 4/4 项").assertDoesNotExist()
        compose.onNodeWithText("记录付款 / 提前续费").assertIsDisplayed()
    }

    @Test fun archiveHidesFutureActionsAndRestoringKeepsRenewalPreference() {
        val p=Plan(id="archive",name="手动会员",amount="20",billingAnchor=today.toString(),autoRenew=false)
        val receipt=Payment(planId=p.id,planName=p.name,amount="20",currency="CNY",date=today.toString())
        seed(Ledger(plans=listOf(p),benefits=listOf(Benefit(planId=p.id,name=p.name,anchor=today.toString())),payments=listOf(receipt)))
        openPlan(p.name)
        compose.onNodeWithContentDescription("订阅更多操作").performClick()
        compose.onNodeWithText("归档订阅").performClick()
        waitFor {it.plans.single().archived}
        compose.onNodeWithText("已归档",substring=false).assertExists()
        compose.onNodeWithText("下次预计扣款",substring=true).assertDoesNotExist()
        compose.onNodeWithText("有效期提醒仍保留",substring=true).assertDoesNotExist()
        compose.onNodeWithText("记录付款 / 提前续费").assertDoesNotExist()
        compose.onNodeWithText("恢复使用").assertIsDisplayed()
        compose.onNodeWithContentDescription("订阅更多操作").performClick()
        compose.onNodeWithText("明天提醒").assertDoesNotExist()
        compose.onNodeWithText("仅补记付款").performClick()
        compose.onNodeWithText("本次实付总额",substring=true).assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("恢复使用").performClick()
        waitFor {!it.plans.single().archived}
        assertFalse(ledger().plans.single().autoRenew)
        assertEquals(listOf(receipt),ledger().payments)
        compose.onNodeWithText("记录付款 / 提前续费").assertIsDisplayed()
    }

    @Test fun archivedPhoneShowsStoppedBalanceAndRestoresWithoutBackCharging() {
        val p=Plan(id="old-phone",name="归档话费",amount="30",billingAnchor=today.minusMonths(2).toString(),paidCycles=0,archived=true,
            balanceAccount=BalanceAccount("100",today.minusMonths(2).toString()))
        seed(Ledger(plans=listOf(p)))
        openPlan(p.name,true)
        compose.onNodeWithText("已归档 · 归档时余额").assertExists()
        compose.onNodeWithText("下次扣费").assertDoesNotExist()
        compose.onNodeWithText("需充值",substring=true).assertDoesNotExist()
        compose.onNodeWithText("记录充值").assertDoesNotExist()
        click("恢复使用")
        waitFor {!it.plans.single().archived}
        compose.onNodeWithText("下次扣费").assertExists()
        compose.onNodeWithText("记录充值").assertExists()
        assertEquals(0,BigDecimal("100").compareTo(Prepaid.balance(ledger().plans.single(),today)))
        assertTrue(ledger().payments.isEmpty())
    }
}
