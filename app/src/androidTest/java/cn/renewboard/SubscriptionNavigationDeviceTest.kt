package cn.renewboard

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SubscriptionNavigationDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    @Before fun seed() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        val today=LocalDate.now().toString()
        val plans=(1..20).map {i->Plan(id="nav-$i",name="导航测试 ${i.toString().padStart(2,'0')}",amount=i.toString(),billingAnchor=today)}+
            Plan(id="manual",name="导航测试 手动",amount="999",billingAnchor=today,autoRenew=false)
        runBlocking {app.repository.update {Ledger(plans=plans,benefits=plans.map {Benefit(planId=it.id,name=it.name,anchor=today)})}}
        compose.waitForIdle()
    }
    @After fun clean() {runBlocking {app.repository.update {Ledger()}};compose.waitForIdle()}
    private fun menu() {compose.onNodeWithContentDescription("订阅筛选与排序").performScrollTo().performClick()}
    @Test fun returningFromDetailsKeepsQueryFilterSortAndScrollAcrossRecreation() {
        compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).performTextInput("导航测试")
        menu();compose.onNodeWithText("自动续费",substring=false).performClick()
        menu();compose.onNodeWithText("单次金额",substring=false).performClick()
        compose.onNodeWithText("导航测试 手动",substring=false).assertDoesNotExist()
        val target=compose.onNodeWithText("导航测试 10",substring=false)
        target.performScrollTo()
        val before=target.fetchSemanticsNode().boundsInRoot.top
        target.performClick()
        compose.onNodeWithText("记录付款 / 提前续费").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
        target.assertIsDisplayed()
        assertEquals(before,target.fetchSemanticsNode().boundsInRoot.top,2f)
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).assertTextContains("导航测试")
        menu();compose.onNodeWithText("✓ 自动续费",substring=false).assertExists();compose.onNodeWithText("✓ 单次金额",substring=false).assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle();target.performScrollTo()
        fun viewport()=compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("导航测试 10",substring=false))).fetchSemanticsNode()
        val savedOffset=viewport().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        // Activity recreation can move the Compose root relative to system bars; compare within the same viewport.
        val savedTop=target.fetchSemanticsNode().boundsInRoot.top-viewport().boundsInRoot.top
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithText("导航测试 10",substring=false).fetchSemanticsNodes().isNotEmpty()}
        target.assertIsDisplayed()
        assertEquals(savedOffset,viewport().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(),0f)
        assertEquals(savedTop,target.fetchSemanticsNode().boundsInRoot.top-viewport().boundsInRoot.top,2f)
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).assertTextContains("导航测试")
        compose.onNodeWithText("导航测试 手动",substring=false).assertDoesNotExist()
    }
    @Test fun activeFilterCanBeClearedWithoutLosingQueryOrSort() {
        compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNodeWithContentDescription("清除续费筛选").assertDoesNotExist()
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).performTextInput("导航测试")
        menu();compose.onNodeWithText("单次金额",substring=false).performClick()
        menu();compose.onNodeWithText("自动续费",substring=false).performClick()
        compose.onNodeWithContentDescription("清除续费筛选").assertExists().performScrollTo().performClick()
        compose.onNodeWithContentDescription("清除续费筛选").assertDoesNotExist()
        compose.onNodeWithText("导航测试 手动",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).assertTextContains("导航测试")
        compose.onNodeWithText("单次金额",substring=false).assertExists()
    }

    @Test fun searchFindsIncludedBenefitsAndKeepsQueryAfterDetails() {
        val today=LocalDate.now().toString()
        val bundle=Plan(id="bundle",name="联合会员套餐",amount="59",billingAnchor=today)
        val single=bundle.copy(id="single",name="百度网盘",amount="30")
        val other=bundle.copy(id="other",name="无关套餐")
        val benefits=listOf(Benefit(planId=bundle.id,name="百度网盘",anchor=today),Benefit(planId=bundle.id,name="网易云音乐",anchor=today),
            Benefit(planId=single.id,name=single.name,anchor=today),Benefit(planId=other.id,name=other.name,anchor=today))
        runBlocking {app.repository.update {Ledger(plans=listOf(bundle,single,other),benefits=benefits)}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).performTextReplacement("百度")
        compose.onNodeWithText("联合会员套餐").assertExists()
        compose.onNodeWithText("百度网盘",substring=false).assertExists()
        compose.onNodeWithText("包含：百度网盘").assertExists()
        compose.onNodeWithText("网易云音乐",substring=false).assertDoesNotExist()
        compose.onNodeWithText("无关套餐").assertDoesNotExist()
        compose.onNodeWithText("联合会员套餐").performClick()
        compose.onNodeWithText("网易云音乐").assertExists()
        compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
        compose.onNode(hasText("搜索订阅") and hasSetTextAction()).assertTextContains("百度")
        compose.onNodeWithText("包含：百度网盘").assertExists()
    }
    @Test fun comparisonShowsMonthlyCostsAndPhoneFeeWithoutChangingPayments() {
        val today=LocalDate.now().toString()
        val annual=Plan(id="annual",name="年费会员",amount="148",cycle=Cycle.YEAR,autoRenew=false,billingAnchor=today)
        val monthly=annual.copy(id="monthly",name="月费会员",amount="45",cycle=Cycle.MONTH)
        val phone=annual.copy(id="phone",name="话费比较",amount="39",cycle=Cycle.MONTH,balanceAccount=BalanceAccount("-10",today))
        val ai=monthly.copy(id="ai",name="外币会员",currency="USD",amount="20")
        val missing=ai.copy(id="missing",name="待补会员")
        val plans=listOf(annual,monthly,phone,ai,missing)
        val receipt=Payment(planId=ai.id,planName=ai.name,amount="20",currency="USD",date=today,cnyAmount="142.80")
        val ledger=Ledger(plans=plans,benefits=plans.filter{it.balanceAccount==null}.map{Benefit(planId=it.id,name=it.name,anchor=today)},payments=listOf(receipt))
        runBlocking {app.repository.update {ledger}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick()
        menu();compose.onNodeWithText("折合月费",substring=false).performClick()
        compose.onNodeWithText("≈¥142.80").assertExists()
        compose.onNodeWithText("≈¥12.33").assertExists()
        compose.onNodeWithText("≈¥39.00").assertExists()
        compose.onNodeWithText("¥-10.00").assertDoesNotExist()
        compose.onNodeWithText("¥148.00 / 年").assertExists()
        compose.onNodeWithText("待补人民币").assertExists()
        fun top(name:String)=compose.onNodeWithText(name,substring=false).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("外币会员")<top("月费会员"));assertTrue(top("月费会员")<top("话费比较"))
        assertTrue(top("话费比较")<top("年费会员"));assertTrue(top("年费会员")<top("待补会员"))
        assertEquals(ledger,runBlocking {app.repository.read()})
        menu();compose.onNodeWithText("单次金额",substring=false).performClick()
        assertTrue(top("年费会员")<top("外币会员"))
        compose.onNodeWithText("每年 · 手动").assertExists()
        assertEquals(ledger,runBlocking {app.repository.read()})
    }

}
