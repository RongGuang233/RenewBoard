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
        runBlocking {app.repository.restore(Backup(data=Ledger()))}
        compose.waitForIdle();compose.onNodeWithText("资产",substring=false).performClick()
    }
    @After fun clean() {if(::app.isInitialized) runBlocking {app.repository.restore(Backup(data=Ledger()))}}
    private fun fill(label:String,value:String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
    private fun click(label:String) {compose.onNodeWithText(label,substring=false).performScrollTo().performClick();compose.waitForIdle()}
    private fun await(predicate:(Ledger)->Boolean):Ledger {
        compose.waitUntil(10000) {predicate(runBlocking {app.repository.read()})};compose.waitForIdle();return runBlocking {app.repository.read()}
    }
    private fun back() {compose.onNodeWithContentDescription("返回").performClick();compose.waitForIdle()}
    private fun screenshot(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun manualBalanceCorrectionArchiveAndTrendPreserveActualTotalsWithoutExpenses() {
        compose.onNodeWithContentDescription("添加账户").performClick()
        fill("账户名称","测试银行");fill("当前余额","5000")
        click("保存账户")
        await {it.assets.accounts.singleOrNull()?.balance=="5000"}
        click("更新余额");fill("新的当前余额","5300");click("保存余额")
        await {it.assets.accounts.singleOrNull()?.balance=="5300"}
        click("更新余额");fill("新的当前余额","5200")
        compose.onNode(isToggleable()).performClick();click("保存余额")
        val corrected=await {it.assets.accounts.singleOrNull()?.balance=="5200"}
        assertEquals(2,corrected.assets.accounts.single().history.size);assertTrue(corrected.payments.isEmpty())
        assertEquals("余额校正",corrected.assets.history.last().reason)
        screenshot("assets-account-detail")
        click("账户设置");compose.onNodeWithContentDescription("归档账户开关").performScrollTo().performClick();click("保存账户")
        val archived=await {it.assets.accounts.singleOrNull()?.archived==true}
        assertEquals(0,BigDecimal("5200").compareTo(Assets.totals(archived).net))
        back();compose.onNodeWithText("显示归档账户（仍按设置计入）").assertExists()
        click("显示归档账户（仍按设置计入）");compose.onNodeWithText("测试银行").assertExists()
        screenshot("assets-overview")
        click("查看变化");compose.onNodeWithText("净资产变化").assertExists();screenshot("assets-trend")
        back();compose.onNodeWithContentDescription("隐藏资产金额").performClick();compose.onNodeWithText("¥5200.00").assertDoesNotExist()
    }
    @Test fun invalidAccountStaysEditableAndPrepaidSwitchControlsNetWorth() {
        compose.onNodeWithContentDescription("添加账户").performClick()
        compose.onNodeWithContentDescription("选择账户分类").performClick()
        compose.onNode(hasText("预付余额") and hasAnyAncestor(isPopup())).performClick()
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
}
