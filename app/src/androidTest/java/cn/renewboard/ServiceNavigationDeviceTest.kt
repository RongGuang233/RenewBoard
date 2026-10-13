package cn.renewboard

import android.content.Intent
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
class ServiceNavigationDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    private lateinit var baseline:Ledger
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        val day=LocalDate.now().toString()
        val plans=(1..12).map {i->Plan(id="service-navigation-$i",name="服务测试 ${i.toString().padStart(2,'0')}",amount=i.toString(),billingAnchor=day)}
        baseline=Ledger(plans=plans,benefits=plans.map {Benefit(planId=it.id,name=it.name,anchor=day)},
            payments=listOf(Payment(planId=plans.first().id,planName=plans.first().name,amount="1",currency="CNY",date=day)))
        runBlocking {app.repository.update {baseline}};compose.waitForIdle()
    }
    @After fun cleanup() {runBlocking {app.repository.update {Ledger()}}}
    private fun tab(name:String)=compose.onNode(hasText(name,substring=false) and hasClickAction())
    private fun search()=compose.onNode(hasText("搜索服务") and hasSetTextAction())
    private fun openServices() {
        tab("账本").performClick()
        compose.onNodeWithContentDescription("打开服务管理").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回账本").assertIsDisplayed()
    }
    private fun systemBack() {
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.waitForIdle()
    }
    @Test fun coldStartShowsDueAndFourTabsAlwaysMatchTheirContents() {
        tab("到期").assertIsSelected()
        compose.onNodeWithText("订阅簿",substring=false).assertExists()
        compose.onNodeWithText("订阅",substring=false).assertDoesNotExist()
        listOf("到期","账本","设备","设置").forEach {tab(it).assertIsDisplayed()}
        repeat(12) {
            tab("账本").performClick().assertIsSelected()
            compose.onNodeWithText("支出概览",substring=false).assertExists()
            compose.onNodeWithText("订阅簿",substring=false).assertDoesNotExist()
            tab("到期").performClick().assertIsSelected()
            compose.onNodeWithText("订阅簿",substring=false).assertExists()
            compose.onNodeWithText("支出概览",substring=false).assertDoesNotExist()
        }
        tab("设备").performClick().assertIsSelected()
        compose.onNodeWithText("我的设备",substring=false).assertExists()
        tab("设置").performClick().assertIsSelected()
        compose.onNodeWithText("更新与关于",substring=false).assertExists()
        tab("到期").performClick().assertIsSelected()
        compose.onNodeWithText("订阅簿",substring=false).assertExists()
        assertEquals(baseline,runBlocking {app.repository.read()})
    }
    @Test fun serviceDetailsAndSystemBackReturnThroughListThenLedger() {
        openServices()
        compose.onNodeWithText("服务管理",substring=false).assertExists()
        tab("到期").assertDoesNotExist()
        compose.onNodeWithText("服务测试 01",substring=false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("订阅更多操作").assertExists()
        systemBack()
        compose.onNodeWithContentDescription("返回账本").assertExists()
        compose.onNodeWithText("服务管理",substring=false).assertExists()
        systemBack()
        tab("账本").assertIsSelected()
        compose.onNodeWithContentDescription("打开服务管理").assertExists()
        compose.onNodeWithText("搜索服务",substring=false).assertDoesNotExist()
        assertEquals(baseline,runBlocking {app.repository.read()})
    }
    @Test fun ledgerPeriodAndServiceQueryStayIndependentAcrossReturnAndRecreation() {
        tab("账本").performClick()
        compose.onNodeWithContentDescription("选择趋势范围").performClick()
        compose.onNodeWithContentDescription("趋势范围 近30天").performClick()
        compose.onNodeWithContentDescription("打开服务管理").performScrollTo().performClick()
        search().performTextReplacement("服务测试 02")
        compose.onNode(hasText("服务测试 02",substring=false) and !hasSetTextAction()).assertExists()
        compose.onNodeWithText("服务测试 01",substring=false).assertDoesNotExist()
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("搜索服务") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        search().assertTextContains("服务测试 02")
        compose.onNodeWithContentDescription("返回账本").performClick()
        compose.onNodeWithText("近30天",substring=false).assertExists()
        tab("到期").performClick()
        compose.onNodeWithText("订阅簿",substring=false).assertExists()
        openServices()
        search().assertTextContains("服务测试 02")
        assertEquals(baseline,runBlocking {app.repository.read()})
    }
    @Test fun notificationOpensServiceDetailAndBackReturnsToDue() {
        openServices()
        compose.activityRule.scenario.onActivity {activity->
            activity.startActivity(Intent(activity.intent)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("planId",baseline.plans.first().id))
        }
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("订阅更多操作").fetchSemanticsNodes().isNotEmpty()}
        systemBack()
        tab("到期").assertIsSelected()
        compose.onNodeWithText("订阅簿",substring=false).assertExists()
        compose.onNodeWithText("搜索服务",substring=false).assertDoesNotExist()
        assertEquals(baseline,runBlocking {app.repository.read()})
    }
}
