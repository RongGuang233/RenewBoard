package cn.renewboard

import androidx.compose.ui.semantics.SemanticsProperties
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
class MainTabNavigationDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    @Before fun seed() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        val sold=(1..24).map {i->Device(id="sold-$i",name="卖出设备 ${i.toString().padStart(2,'0')}",status=DeviceStatus.SOLD,
            purchaseAmount=(i*100).toString(),saleAmount="50",startDate="2026-01-01",endDate="2026-08-01",saleDate="2026-08-02")}
        val active=Device(id="active",name="服役设备",purchaseAmount="100",startDate="2026-01-01")
        val today=LocalDate.now()
        val payments=(0..29).map {i->Payment(id="payment-$i",planId="plan",planName="月度测试",amount="20",currency="CNY",date=today.minusDays(i.toLong()).toString())}
        runBlocking {app.repository.update {Ledger(devices=sold+active,payments=payments)}}
        compose.waitForIdle()
    }
    @After fun clean() {runBlocking {app.repository.update {Ledger()}};compose.waitForIdle()}
    private fun tab(name:String) {compose.onNodeWithText(name,substring=false).performClick();compose.waitForIdle()}

    @Test fun devicesRetainStatusQuerySortAndScrollAcrossDetailTabsAndRecreation() {
        tab("设备")
        compose.onNodeWithText("服役中",substring=false).assertExists()
        compose.onNodeWithText("服役设备").assertExists()
        compose.onNodeWithContentDescription("筛选设备状态").performClick()
        compose.onNode(hasText("已卖出") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithContentDescription("设备排序").performClick()
        compose.onNodeWithText("净花费 · 最高").performClick()
        compose.onNode(hasText("搜索设备") and hasSetTextAction()).performTextReplacement("卖出设备")
        compose.activityRule.scenario.onActivity {activity->
            activity.currentFocus?.clearFocus()
            val input=activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        val target=compose.onNodeWithText("卖出设备 12",substring=false)
        target.performScrollTo()
        fun offset()=compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("卖出设备 12"))).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before=offset();assertTrue(before>0)
        target.performClick()
        compose.onNodeWithText("设备详情").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        target.assertIsDisplayed();assertEquals(before,offset(),0f)
        tab("账本");tab("设备")
        target.assertIsDisplayed();assertEquals(before,offset(),0f)
        compose.onNode(hasText("搜索设备") and hasSetTextAction()).assertTextContains("卖出设备")
        compose.onNodeWithText("服役设备").assertDoesNotExist()
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithText("卖出设备 12").fetchSemanticsNodes().isNotEmpty()}
        target.assertIsDisplayed();assertEquals(before,offset(),0f)
        // Inspect the restored ordering without selecting the sort again.
        compose.onNodeWithText("卖出设备 24").performScrollTo()
        assertTrue(compose.onNodeWithText("卖出设备 24").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("卖出设备 23").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun ledgerRetainsRangeSelectedDayAndScrollAcrossTabs() {
        tab("账本")
        compose.onNodeWithContentDescription("选择趋势范围").performClick()
        compose.onNodeWithContentDescription("趋势范围 近30天").performClick()
        val day=LocalDate.now().minusDays(2)
        val description="${day.monthValue}/${day.dayOfMonth}，支出¥20.00，1笔，待补录0笔"
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.onNodeWithText("所选时段明细").assertExists()
        compose.onNodeWithText(LocalDate.now().minusDays(2).toString(),substring=false).performScrollTo()
        fun offset()=compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("支出概览"))).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before=offset();assertTrue(before>0)
        tab("订阅");tab("账本")
        assertEquals(before,offset(),0f)
        compose.onNodeWithText("近30天",substring=false).assertExists()
        compose.onNodeWithContentDescription(description).assertIsSelected()
        compose.onNodeWithText("所选时段明细").assertExists()
    }
}
