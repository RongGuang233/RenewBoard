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
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
        compose.onNodeWithText("设备",substring=false).performClick()
    }
    @After fun clean() {if(::app.isInitialized && app.packageName.endsWith(".debug")) {
        runBlocking {app.repository.update {Ledger()}}
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
    }}
    private fun fill(label: String,value: String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
    private fun click(text: String) {
        if(text=="保存设备") compose.onNodeWithText(text,substring=false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        else compose.onNodeWithText(text,substring=false).performScrollTo().performClick()
    }
    private fun select(description: String,value: String) {
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun back() {
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        if(compose.onAllNodesWithText("保留设备草稿？").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("放弃修改").performClick()
        }
    }
    private fun awaitDevice(predicate: (Ledger)->Boolean): Ledger {
        compose.waitUntil(10000) {predicate(runBlocking {app.repository.read()})}
        compose.waitForIdle()
        return runBlocking {app.repository.read()}
    }
    private fun shot(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun createPersistEditRetireSellAndDeleteDeviceWithBackNavigation() {
        compose.onNodeWithContentDescription("筛选设备状态").assertExists()
        compose.onNodeWithText("已退役",substring=false).assertDoesNotExist()
        compose.onNodeWithContentDescription("添加设备").performClick()
        fill("设备名称","测试手机")
        fill("购入金额（元）","3000")
        fill("服役日期","2026-09-01")
        compose.onNodeWithContentDescription("选择日期").performClick()
        shot("date-picker")
        compose.onNodeWithContentDescription("选择月份").performClick()
        shot("date-months")
        compose.onNodeWithText("取消",substring=false).performClick()
        click("保存设备")
        val created=awaitDevice {it.devices.singleOrNull()?.name=="测试手机"}
        assertTrue(created.payments.isEmpty())
        shot("device-detail")
        back();shot("devices-list");click("测试手机")
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        click("编辑设备")
        select("选择设备状态","已退役")
        compose.onNode(hasText("卖出日期") and hasSetTextAction()).assertDoesNotExist()
        fill("退役日期","2026-09-03")
        click("保存设备")
        awaitDevice {it.devices.singleOrNull()?.status==DeviceStatus.RETIRED}
        compose.onNodeWithText("已服役 3 天").assertExists()
        back()
        select("筛选设备状态","已退役")
        click("测试手机")
        click("编辑设备")
        select("选择设备状态","已卖出")
        compose.onNode(hasText("退役日期") and hasSetTextAction()).assertDoesNotExist()
        fill("卖出日期","2026-09-04")
        fill("卖出金额（元）","2000")
        compose.onNodeWithContentDescription("选择设备分类").performScrollTo().performClick()
        shot("device-category-picker")
        compose.onNode(hasText("搜索分类") and hasSetTextAction()).performTextReplacement("手机")
        compose.onNode(hasText("手机",substring=false) and !hasSetTextAction() and hasAnyAncestor(isDialog())).performClick()
        shot("device-editor")
        click("保存设备")
        val sold=awaitDevice {it.devices.singleOrNull()?.status==DeviceStatus.SOLD}
        assertEquals(created.devices.single().id,sold.devices.single().id)
        assertEquals(DeviceCategory.PHONE,sold.devices.single().category)
        assertEquals("2000",sold.devices.single().saleAmount)
        assertEquals("2026-09-03",sold.devices.single().endDate)
        assertEquals("2026-09-04",sold.devices.single().saleDate)
        compose.onNodeWithText("卖出日期",substring=false).assertExists()
        compose.onNodeWithText("停止服役日期",substring=false).assertExists()
        compose.onNodeWithText("已服役 3 天").assertExists()
        compose.onAllNodes(hasText("日均 =",substring=true)).assertCountEquals(0)
        compose.onNodeWithText("净花费",substring=false).assertExists()
        compose.onNodeWithText("¥1000.00",substring=false).assertExists()
        shot("device-sold-detail")
        compose.onNodeWithContentDescription("设备更多操作").performClick()
        compose.onNodeWithText("删除设备",substring=false).performClick()
        compose.onNodeWithText("取消",substring=false).performClick()
        compose.onNodeWithText("测试手机").assertExists()
        compose.onNodeWithContentDescription("设备更多操作").performClick()
        compose.onNodeWithText("删除设备",substring=false).performClick()
        compose.onNodeWithText("删除",substring=false).performClick()
        awaitDevice {it.devices.isEmpty()}
        compose.onNodeWithText("我的设备").assertExists()
    }
    @Test fun invalidInputCanBeCorrectedAndWishlistHasNoServiceStats() {
        compose.onNodeWithContentDescription("添加设备").performClick()
        fill("设备名称","未来电脑")
        compose.onNodeWithContentDescription("选择设备分类").performScrollTo().performClick()
        compose.onNode(hasText("搜索分类") and hasSetTextAction()).performTextReplacement("不存在的分类")
        compose.onNodeWithText("没有匹配的分类").assertExists()
        compose.onNode(hasText("搜索分类") and hasSetTextAction()).performTextReplacement("电纸")
        compose.onNode(hasText("电纸书",substring=false) and hasAnyAncestor(isDialog())).performClick()
        select("选择设备状态","待购买")
        fill("购买预算（元）","-1")
        fill("计划日期（选填）","")
        click("保存设备")
        compose.onNodeWithText("金额格式错误（最多4位小数）").assertExists()
        assertTrue(runBlocking {app.repository.read()}.devices.isEmpty())
        fill("购买预算（元）","8000")
        click("保存设备")
        val saved=awaitDevice {it.devices.size==1}
        assertEquals(DeviceStatus.WISHLIST,saved.devices.single().status)
        assertEquals(DeviceCategory.EREADER,saved.devices.single().category)
        assertNull(saved.devices.single().startDate)
        compose.onAllNodes(hasText("已服役",substring=true)).assertCountEquals(0)
        click("编辑设备")
        fill("设备名称","不会保存的名字")
        back()
        compose.onNodeWithText("未来电脑").assertExists()
        back()
        select("筛选设备状态","待购买")
        compose.onNodeWithText("未来电脑").assertExists()
        compose.onNodeWithContentDescription("添加设备").performClick()
        compose.onNode(hasText("购入金额（元）") and hasSetTextAction()).assertExists()
        compose.onNode(hasText("购买预算（元）") and hasSetTextAction()).assertDoesNotExist()
        back()
        compose.onNodeWithText("未来电脑").assertExists()
        assertEquals(1,runBlocking {app.repository.read()}.devices.size)
    }
    @Test fun listSearchAndSortMenuPreserveDefaultActiveStatus() {
        runBlocking { app.repository.update { it.copy(devices=listOf(
            Device(id="old",name="旧手机",category=DeviceCategory.PHONE,purchaseAmount="900",startDate="2026-08-01"),
            Device(id="new",name="新手机",category=DeviceCategory.PHONE,purchaseAmount="10000",startDate="2026-09-01"),
            Device(id="wish",name="待购手表",status=DeviceStatus.WISHLIST,purchaseAmount="2000")
        )) } }
        awaitDevice {it.devices.size==3}
        compose.onNodeWithText("待购手表").assertDoesNotExist()
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("购入金额 · 最高").performClick()
        val newer=compose.onNodeWithText("新手机").fetchSemanticsNode().boundsInRoot.top
        val older=compose.onNodeWithText("旧手机").fetchSemanticsNode().boundsInRoot.top
        assertTrue(newer<older)
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("服役时长 · 最长").performClick()
        assertTrue(compose.onNodeWithText("旧手机").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("新手机").fetchSemanticsNode().boundsInRoot.top)
        fill("搜索设备","不存在")
        compose.onNodeWithText("没有匹配的设备").assertExists()
        fill("搜索设备","新手机")
        compose.onNodeWithText("旧手机").assertDoesNotExist()
        compose.onNode(hasText("新手机",substring=false) and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        back()
        compose.onNode(hasText("搜索设备") and hasSetTextAction()).assertTextContains("新手机")
        shot("devices-search-sort")
    }

    @Test fun soldSortingMatchesNetAmountsAndWishlistOnlyOffersApplicableSorts() {
        runBlocking { app.repository.update { it.copy(devices=listOf(
            Device(id="computer",name="卖出电脑",status=DeviceStatus.SOLD,purchaseAmount="10000",saleAmount="9900",startDate="2026-01-01",endDate="2026-06-01",saleDate="2026-09-01"),
            Device(id="phone",name="卖出手机",status=DeviceStatus.SOLD,purchaseAmount="3999",saleAmount="800",startDate="2026-02-01",endDate="2026-08-01"),
            Device(id="wish",name="待购手表",status=DeviceStatus.WISHLIST,purchaseAmount="2000")
        )) } }
        awaitDevice {it.devices.size==3}
        select("筛选设备状态","已卖出")
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("净花费 · 最高").performClick()
        compose.onNodeWithText("¥100.00").assertExists()
        compose.onNodeWithText("¥3199.00").assertExists()
        assertTrue(compose.onNodeWithText("卖出手机").fetchSemanticsNode().positionInRoot.y < compose.onNodeWithText("卖出电脑").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("卖出日期 · 最近").performClick()
        assertTrue(compose.onNodeWithText("卖出电脑").fetchSemanticsNode().positionInRoot.y < compose.onNodeWithText("卖出手机").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("服役时长 · 最长").performClick()
        select("筛选设备状态","待购买")
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("服役时长 · 最长").assertDoesNotExist()
        compose.onNodeWithText("预算 · 最高").assertExists()
        compose.onNodeWithText("计划日期 · 最近").performClick()
        compose.onNodeWithText("待购手表").assertExists()
        select("筛选设备状态","已退役")
        compose.onNodeWithContentDescription("设备排序").performScrollTo().performClick()
        compose.onNodeWithText("退役日期 · 最近").assertExists()
        compose.onNodeWithText("购入金额 · 最高").performClick()
    }

}
