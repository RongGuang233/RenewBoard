package cn.renewboard

import android.graphics.Bitmap
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HomeBalanceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    private lateinit var originalQuery:PlatformBalanceQuery
    private val today get()=LocalDate.now().toString()
    private fun wallet(id:String,name:String,amount:String,currency:String="CNY",archived:Boolean=false)=
        Plan(id=id,name=name,amount="0",currency=currency,billingAnchor=today,autoRenew=false,paidCycles=0,
            archived=archived,usageBalance=UsageBalance(amount,today))
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        originalQuery=app.platformBalanceQuery
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
    }
    @After fun cleanup() {app.platformBalanceQuery=originalQuery;runBlocking {app.repository.update {Ledger()}}}
    private fun seed(l:Ledger) {
        runBlocking {app.repository.update {l}}
        compose.waitUntil(10000) {compose.onAllNodesWithText("DeepSeek API",substring=false).fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun platformAndPhoneBalancesSitBelowExpiryAndOpenTheirOriginalDetail() {
        val member=Plan(id="home-member",name="待续费会员",amount="20",billingAnchor=today)
        val phone=Plan(id="home-phone",name="中国移动",amount="30",billingAnchor=today,paidCycles=0,balanceAccount=BalanceAccount("200",today))
        val l=Ledger(plans=listOf(member,phone,wallet("home-ai","DeepSeek API","123.45")),
            benefits=listOf(Benefit(planId=member.id,name=member.name,anchor=LocalDate.now().plusDays(1).toString())))
        seed(l)
        var queries=0
        app.platformBalanceQuery=PlatformBalanceQuery {_,_->queries++;DeepSeekBalanceSnapshot("CNY","123.45","3.45","120",Instant.now())}
        runBlocking {app.platformBalances.connect("home-ai","test-home-key-not-a-real-credential")}
        val due=compose.onNodeWithText("待续费会员",substring=false).fetchSemanticsNode().positionInRoot.y
        val heading=compose.onNodeWithText("余额",substring=false).fetchSemanticsNode().positionInRoot.y
        val phoneTop=compose.onNodeWithText("中国移动",substring=false).fetchSemanticsNode().positionInRoot.y
        val apiTop=compose.onNodeWithText("DeepSeek API",substring=false).fetchSemanticsNode().positionInRoot.y
        assertTrue(due<heading && heading<phoneTop && heading<apiTop)
        compose.onNodeWithText("¥123.45",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("DeepSeek API",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("更新余额",substring=false).assertExists()
        compose.onNodeWithContentDescription("刷新余额").assertExists()
        compose.onNodeWithText("记录充值",substring=false).assertExists()
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.onNode(hasText("到期",substring=false) and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("余额",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("中国移动",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("修改月费",substring=false).assertExists()
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        assertEquals(l,runBlocking {app.repository.read()})
        assertEquals(1,queries)
        compose.onNodeWithText("余额",substring=false).performScrollTo()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("截图失败")
        java.io.File(app.cacheDir,"home-balances-v1.13.1.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun apiOnlyHomeShowsZeroAndForeignBalancesExcludesArchivedAndDoesNotForecastThem() {
        val l=Ledger(plans=listOf(wallet("home-ai","DeepSeek API","0"),wallet("home-mimo","MiMo API","8.50","USD"),
            wallet("home-archived","已归档平台","999",archived=true)))
        seed(l)
        compose.onNodeWithText("余额",substring=false).assertExists()
        compose.onNodeWithText("USD 8.50",substring=false).assertExists()
        compose.onAllNodesWithText("¥0.00",substring=false).assertCountEquals(2)
        compose.onNodeWithText("已归档平台",substring=false).assertDoesNotExist()
        compose.onNodeWithText("还没有需要记挂的到期日",substring=false).assertDoesNotExist()
        compose.onNodeWithText("预计扣款 0 笔",substring=false).assertExists()
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithText("MiMo API",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("余额",substring=false).assertExists()
        assertEquals(l,runBlocking {app.repository.read()})
    }
}
