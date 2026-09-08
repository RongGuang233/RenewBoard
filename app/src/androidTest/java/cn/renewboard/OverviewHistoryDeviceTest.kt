package cn.renewboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class OverviewHistoryDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    @Before fun reset() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {app.repository.update {Ledger()}};compose.waitForIdle()
    }
    @After fun clean() {runBlocking {app.repository.update {Ledger()}}}
    private fun seed(l:Ledger) {runBlocking {app.repository.update {l}};compose.waitForIdle()}
    private fun back() {compose.runOnUiThread {compose.activity.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    @Test fun forecastCountsChargesAndReturnsThroughPlanToOverview() {
        val today=LocalDate.now()
        val weekly=Plan(name="每周会员",amount="10",cycle=Cycle.WEEK,billingAnchor=today.toString(),paidCycles=0)
        val phone=Plan(name="中国移动",amount="39",billingAnchor=today.plusDays(2).toString(),balanceAccount=BalanceAccount("100",today.toString()))
        seed(Ledger(plans=listOf(weekly,phone),benefits=listOf(Benefit(planId=weekly.id,name=weekly.name,anchor=today.toString()))))
        val expected=Book.forecastCharges(runBlocking {app.repository.read()},today,today.plusDays(30))
        compose.onNodeWithText("预计扣款 ${expected.size} 笔").assertExists()
        compose.onNodeWithText("未来 30 天预计扣款").performClick()
        compose.onAllNodesWithText("每周会员").onFirst().performScrollTo().performClick()
        compose.onNodeWithContentDescription("订阅更多操作").assertExists()
        back()
        compose.onNodeWithContentDescription("订阅更多操作").assertDoesNotExist()
        compose.onAllNodesWithText("每周会员").onFirst().assertExists()
        back()
        compose.onNodeWithContentDescription("记一笔订阅").assertExists()
    }
    @Test fun phonePreviewIsThreeRowsAndFullHistoryKeepsAccountsSeparate() {
        val today=LocalDate.now()
        val phone=Plan(name="中国移动",amount="39",billingAnchor=today.toString(),balanceAccount=BalanceAccount("100",today.toString()))
        val other=phone.copy(id="other")
        val payments=(1..5).map {i->Payment(id="history-$i",planId=phone.id,planName=phone.name,amount="${10+i}",currency="CNY",date=today.minusDays(i.toLong()).toString(),note="话费充值")}
        seed(Ledger(plans=listOf(phone,other),payments=payments+Payment(planId=other.id,planName=other.name,amount="999",currency="CNY",date=today.toString(),note="话费充值")))
        compose.onAllNodesWithText("中国移动").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("¥11.00").performScrollTo().assertExists()
        compose.onNodeWithText("¥13.00").performScrollTo().assertExists()
        compose.onNodeWithText("¥14.00").assertDoesNotExist()
        compose.onNodeWithText("¥11.00").performScrollTo().performClick()
        compose.onNodeWithContentDescription("付款更多操作").assertExists()
        back()
        compose.onNodeWithText("全部记录").performScrollTo().performClick()
        compose.onNodeWithText("¥14.00").performScrollTo().assertExists()
        compose.onNodeWithText("¥999.00").assertDoesNotExist()
        back()
        compose.onNodeWithText("校准余额").performScrollTo().assertExists()
        back()
        compose.onNodeWithContentDescription("记一笔订阅").assertExists()
    }
    @Test fun deviceListAmountsFollowSelectedStatus() {
        val date=LocalDate.now().minusDays(10).toString()
        seed(Ledger(devices=listOf(
            Device(name="在用手机",purchaseAmount="3999",startDate=date),
            Device(name="旧手机",status=DeviceStatus.SOLD,purchaseAmount="3999",startDate=date,endDate=date,saleDate=date,saleAmount="800"),
            Device(name="新耳机",status=DeviceStatus.WISHLIST,purchaseAmount="1500")
        )))
        compose.onNodeWithText("设备",substring=false).performClick()
        compose.onNodeWithText("购入金额").assertExists();compose.onNodeWithText("¥3999.00").assertExists()
        compose.onNodeWithContentDescription("筛选设备状态").performClick()
        compose.onNode(hasText("已卖出") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("净花费").assertExists();compose.onNodeWithText("¥3199.00").assertExists()
        compose.onNodeWithContentDescription("筛选设备状态").performClick()
        compose.onNode(hasText("待购买") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("预算").assertExists();compose.onNodeWithText("¥1500.00").assertExists()
    }
}
