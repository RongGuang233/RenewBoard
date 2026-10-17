package cn.renewboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class AssetsDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    @Before fun reset() {
        app=ApplicationProvider.getApplicationContext();check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("asset-display",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking {app.repository.restore(Backup(data=Ledger()))}
        compose.waitForIdle();compose.onNodeWithText("资产",substring=false).performClick()
    }
    @After fun clean() {if(::app.isInitialized) {
        runBlocking {app.repository.restore(Backup(data=Ledger()))}
        app.getSharedPreferences("asset-display",android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }}
    private fun fill(label:String,value:String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
    private fun click(label:String) {compose.onNodeWithText(label,substring=false).performScrollTo().performClick();compose.waitForIdle()}
    private fun await(predicate:(Ledger)->Boolean):Ledger {
        compose.waitUntil(10000) {predicate(runBlocking {app.repository.read()})};compose.waitForIdle();return runBlocking {app.repository.read()}
    }
    private fun manage() {
        compose.onNodeWithContentDescription("资产管理菜单").performScrollTo().performClick()
        compose.onNodeWithText("账户管理",substring=false).performClick();compose.waitForIdle()
    }
    private fun seed(accounts:List<AssetAccount>) {
        runBlocking {app.repository.restore(Backup(data=Ledger(assets=AssetBook(accounts=accounts))))}
        compose.waitUntil(10000) {compose.onAllNodesWithText(accounts.first().name,substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    private fun back() {compose.onNodeWithContentDescription("返回").performClick();compose.waitForIdle()}
    private fun screenshot(name:String) {
        compose.activityRule.scenario.onActivity {activity->
            activity.currentFocus?.clearFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        compose.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun manualBalanceCorrectionArchiveAndTrendPreserveActualTotalsWithoutExpenses() {
        compose.onNodeWithContentDescription("添加账户").performClick()
        click("银行卡")
        fill("账户名称","测试银行");fill("当前余额","5000")
        screenshot("assets-add-account")
        click("保存账户")
        await {it.assets.accounts.singleOrNull()?.balance=="5000"}
        click("更新余额");fill("新的当前余额","5300")
        screenshot("assets-balance-sheet");click("保存余额")
        await {it.assets.accounts.singleOrNull()?.balance=="5300"}
        click("更新余额");fill("新的当前余额","5200")
        click("纠正上次录入");click("保存余额")
        val corrected=await {it.assets.accounts.singleOrNull()?.balance=="5200"}
        assertEquals(2,corrected.assets.accounts.single().history.size);assertTrue(corrected.payments.isEmpty())
        assertEquals("余额校正",corrected.assets.history.last().reason)
        screenshot("assets-account-detail")
        click("账户设置");compose.onNodeWithContentDescription("归档账户开关").performScrollTo().performClick();click("保存账户")
        val archived=await {it.assets.accounts.singleOrNull()?.archived==true}
        assertEquals(0,BigDecimal("5200").compareTo(Assets.totals(archived).net))
        back();compose.onNodeWithText("测试银行").assertDoesNotExist()
        manage();compose.onNodeWithText("归档账户",substring=false).assertExists()
        compose.onNodeWithText("测试银行").performScrollTo().assertIsDisplayed()
        back();click("查看趋势");compose.onNodeWithText("净资产变化").assertExists();screenshot("assets-trend")
        back();compose.onNodeWithContentDescription("隐藏资产金额").performClick()
        compose.onAllNodesWithText("¥5,200.00").assertCountEquals(0)
    }
    @Test fun invalidAccountStaysEditableAndPrepaidSwitchControlsNetWorth() {
        compose.onNodeWithContentDescription("添加账户").performClick()
        click("饭卡")
        compose.onNodeWithContentDescription("计入净资产开关").assertIsOn()
        back();click("其他预付")
        compose.onNodeWithContentDescription("计入净资产开关").assertIsOff()
        fill("账户名称","测试饭卡");fill("当前余额","-bad")
        click("保存账户")
        compose.onNode(hasText("当前余额") and hasSetTextAction()).assertExists()
        assertTrue(runBlocking {app.repository.read()}.assets.accounts.isEmpty())
        fill("当前余额","200")
        compose.onNode(isToggleable() and !hasContentDescription("计入净资产开关")).performScrollTo().performClick()
        compose.onNodeWithContentDescription("计入净资产开关").assertIsOn()
        click("保存账户")
        val saved=await {it.assets.accounts.singleOrNull()?.balance=="200"}
        assertEquals(0,BigDecimal("200").compareTo(Assets.totals(saved).net))
        click("账户设置");compose.onNodeWithContentDescription("计入净资产开关").performScrollTo().performClick();click("保存账户")
        val excluded=await {it.assets.accounts.singleOrNull()?.included==false}
        assertEquals(0,Assets.totals(excluded).net.signum());assertTrue(excluded.payments.isEmpty())
    }

    @Test fun groupBalancesSeparateExcludedMoneyAndHiddenStateCoversAllAmounts() {
        seed(listOf(
            AssetAccount(name="示例饭卡",category=AssetCategory.PREPAID,subtype="饭卡",balance="240.74"),
            AssetAccount(name="示例会员卡",category=AssetCategory.PREPAID,subtype="会员卡",balance="100",included=false)
        ))
        compose.onNodeWithContentDescription("收起预付余额").assertTextContains("¥340.74")
        compose.onNodeWithText("其中计入净资产 ¥240.74").assertExists()
        compose.onNodeWithText("不计入",substring=false).assertExists()
        compose.onNodeWithContentDescription("隐藏资产金额").performScrollTo().performClick()
        listOf("¥340.74","¥240.74","¥100.00","其中计入净资产 ¥240.74").forEach {
            compose.onAllNodesWithText(it,substring=true).assertCountEquals(0)
        }
        compose.onNodeWithText("其中计入 ••••").assertExists()
        compose.onNodeWithContentDescription("显示资产金额").performClick()
        compose.onNodeWithContentDescription("收起预付余额").assertTextContains("¥340.74")
    }

    @Test fun groupFoldingAndOrderSurviveActivityRecreation() {
        seed(listOf(
            AssetAccount(name="示例现金",category=AssetCategory.CASH,subtype="现金",balance="1000"),
            AssetAccount(name="示例饭卡",category=AssetCategory.PREPAID,subtype="饭卡",balance="200")
        ))
        compose.onNodeWithContentDescription("收起资金账户").performScrollTo().performClick()
        compose.onNodeWithContentDescription("收起预付余额").performScrollTo().performClick()
        manage()
        repeat(4) {compose.onNodeWithContentDescription("上移预付余额").performScrollTo().performClick();compose.waitForIdle()}
        back()
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("展开预付余额").fetchSemanticsNodes().isNotEmpty()}
        val prepaid=compose.onNodeWithContentDescription("展开预付余额").fetchSemanticsNode().positionInRoot.y
        val cash=compose.onNodeWithContentDescription("展开资金账户").fetchSemanticsNode().positionInRoot.y
        assertTrue("预付余额 should retain the chosen first position",prepaid<cash)
        compose.onNodeWithText("示例现金").assertDoesNotExist();compose.onNodeWithText("示例饭卡").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开预付余额").performScrollTo().performClick()
        compose.onNodeWithText("示例饭卡").assertExists()
    }

    @Test fun referenceStyleOverviewFixtureShowsInstitutionsAndSeparateTuitionTotals() {
        // Fictional balances for visual review; no real accounts or connections are read.
        seed(listOf(
            AssetAccount(name="微信",category=AssetCategory.CASH,subtype="微信",balance="1200.50"),
            AssetAccount(name="支付宝",category=AssetCategory.CASH,subtype="支付宝",balance="2800.00"),
            AssetAccount(name="现金",category=AssetCategory.CASH,subtype="现金",balance="300"),
            AssetAccount(name="招商银行",category=AssetCategory.CASH,subtype="银行卡",balance="18000"),
            AssetAccount(name="交通银行",category=AssetCategory.CASH,subtype="银行卡",balance="8600"),
            AssetAccount(name="农业银行",category=AssetCategory.CASH,subtype="银行卡",balance="4200.00"),
            AssetAccount(name="饭卡",category=AssetCategory.PREPAID,subtype="饭卡",balance="240.00"),
            AssetAccount(name="健身卡",category=AssetCategory.PREPAID,subtype="会员卡",balance="300",included=false),
            AssetAccount(name="示例学生甲",category=AssetCategory.TUITION,studentId="fixture-a",balance="1200"),
            AssetAccount(name="示例学生乙",category=AssetCategory.TUITION,studentId="fixture-b",balance="-500")
        ))
        compose.onNodeWithContentDescription("收起资金账户").assertTextContains("¥35,100.50")
        compose.onNodeWithText("¥34,640.50",substring=false).assertExists()
        screenshot("assets-reference-overview")
        compose.onNodeWithText("查看学生",substring=false).performScrollTo()
        compose.onNodeWithText("待上课预收款",substring=false).assertIsDisplayed()
        compose.onNodeWithText("已上课待收款",substring=false).assertIsDisplayed()
        compose.onNodeWithText("示例学生甲").assertDoesNotExist()
        screenshot("assets-reference-prepaid-tuition")
    }
}
