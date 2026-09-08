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
        menu();compose.onNodeWithText("金额",substring=false).performClick()
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
        menu();compose.onNodeWithText("✓ 自动续费",substring=false).assertExists();compose.onNodeWithText("✓ 金额",substring=false).assertExists()
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
}
