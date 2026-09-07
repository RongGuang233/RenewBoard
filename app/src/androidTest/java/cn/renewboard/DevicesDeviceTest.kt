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

@RunWith(AndroidJUnit4::class)
class DevicesDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app: RenewApp
    @Before fun reset() {
        app=ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
        compose.onNodeWithText("设备",substring=false).performClick()
    }
    @After fun clean() {if(::app.isInitialized) runBlocking {app.repository.update {Ledger()}}}
    private fun fill(label: String,value: String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
    private fun click(text: String) {compose.onNodeWithText(text,substring=false).performScrollTo().performClick()}
    private fun back() {compose.onNodeWithContentDescription("返回").performClick()}
    private fun awaitDevice(predicate: (Ledger)->Boolean): Ledger {
        compose.waitUntil(10000) {predicate(runBlocking {app.repository.read()})}
        compose.waitForIdle()
        return runBlocking {app.repository.read()}
    }
    private fun shot(name:String) {
        compose.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun createPersistEditRetireSellAndDeleteDeviceWithBackNavigation() {
        compose.onNodeWithContentDescription("添加设备").performClick()
        fill("设备名称","测试手机")
        fill("购入金额（元）","3000")
        fill("服役日期","2026-09-01")
        click("保存设备")
        val created=awaitDevice {it.devices.singleOrNull()?.name=="测试手机"}
        assertTrue(created.payments.isEmpty())
        shot("device-detail")
        back();shot("devices-list");click("测试手机")
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        click("编辑设备")
        click("已退役")
        fill("结束日期","2026-09-03")
        click("保存设备")
        awaitDevice {it.devices.singleOrNull()?.status==DeviceStatus.RETIRED}
        compose.onNodeWithText("已服役 3 天").assertExists()
        back()
        click("已退役")
        click("测试手机")
        click("编辑设备")
        click("已卖出")
        fill("卖出金额（元）","2000")
        click("保存设备")
        val sold=awaitDevice {it.devices.singleOrNull()?.status==DeviceStatus.SOLD}
        assertEquals(created.devices.single().id,sold.devices.single().id)
        assertEquals("2000",sold.devices.single().saleAmount)
        click("删除设备")
        compose.onNodeWithText("取消",substring=false).performClick()
        compose.onNodeWithText("测试手机").assertExists()
        click("删除设备")
        compose.onNodeWithText("删除",substring=false).performClick()
        awaitDevice {it.devices.isEmpty()}
        compose.onNodeWithText("我的设备").assertExists()
    }
    @Test fun invalidInputCanBeCorrectedAndWishlistHasNoServiceStats() {
        compose.onNodeWithContentDescription("添加设备").performClick()
        fill("设备名称","未来电脑")
        click("待购买")
        fill("购买预算（元）","-1")
        fill("计划日期（选填）","")
        click("保存设备")
        compose.onNodeWithText("金额格式错误（最多4位小数）").assertExists()
        assertTrue(runBlocking {app.repository.read()}.devices.isEmpty())
        fill("购买预算（元）","8000")
        click("保存设备")
        val saved=awaitDevice {it.devices.size==1}
        assertEquals(DeviceStatus.WISHLIST,saved.devices.single().status)
        assertNull(saved.devices.single().startDate)
        compose.onAllNodes(hasText("已服役",substring=true)).assertCountEquals(0)
        click("编辑设备")
        fill("设备名称","不会保存的名字")
        back()
        compose.onNodeWithText("未来电脑").assertExists()
        back()
        click("待购买")
        compose.onNodeWithText("未来电脑").assertExists()
    }
}
