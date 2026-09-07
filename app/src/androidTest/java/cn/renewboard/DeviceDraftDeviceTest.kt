package cn.renewboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DeviceDraftDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp

    @Before fun prepare() {
        app=ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug")) {"Draft tests must only target the debug app"}
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
        compose.onNodeWithText("设备",substring=false).performClick()
    }

    @After fun clean() {
        if(::app.isInitialized && app.packageName.endsWith(".debug")) {
            runBlocking {app.repository.update {Ledger()}}
            app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        }
    }

    private fun field(label:String)=compose.onNode(hasText(label) and hasSetTextAction())
    private fun fill(label:String,value:String) {field(label).performScrollTo().performTextReplacement(value)}
    private fun click(text:String) {compose.onNodeWithText(text,substring=false).performClick()}
    private fun selectStatus(label:String) {
        compose.onNodeWithContentDescription("选择设备状态").performScrollTo().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun notes() {
        compose.onNode(hasText("设备备注") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
    }
    private fun awaitEditor() {
        compose.waitUntil(10000) {compose.onAllNodes(hasText("设备名称") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    private fun save() {
        compose.onNodeWithText("保存设备").performSemanticsAction(SemanticsActions.OnClick) {it()}
    }
    private fun awaitDevice(predicate:(Device?)->Boolean):Device {
        compose.waitUntil(10000) {predicate(runBlocking {app.repository.read()}.devices.singleOrNull())}
        compose.waitForIdle()
        return runBlocking {app.repository.read()}.devices.single()
    }

    @Test fun hardwareBackRetainsWholeNewDraftAndDiscardRestoresCleanForm() {
        compose.onNodeWithContentDescription("添加设备").performClick()
        fill("设备名称","想买的电脑")
        compose.onNodeWithContentDescription("选择设备分类").performScrollTo().performClick()
        compose.onNode(hasText("电脑",substring=false) and hasAnyAncestor(isDialog())).performClick()
        selectStatus("待购买")
        fill("购买预算（元）","6800.50")
        val planned=LocalDate.now().plusMonths(1).toString()
        fill("计划日期（选填）",planned)
        notes();fill("设备备注","等促销时购买")
        // Dispatch the activity's system-back path, not the form's own button.
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        click("保留草稿")
        compose.onNodeWithText("我的设备").assertExists()
        assertTrue(runBlocking {app.repository.read()}.devices.isEmpty())
        assertNotNull(DraftStore(app).read("device:new"))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("添加设备").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("添加设备").performClick();awaitEditor()
        compose.onNodeWithText("已恢复上次草稿").assertExists()
        field("设备名称").assertTextContains("想买的电脑")
        field("购买预算（元）").performScrollTo().assertTextContains("6800.50")
        field("计划日期（选填）").performScrollTo().assertTextContains(planned)
        compose.onNodeWithContentDescription("选择设备分类").assertTextContains("电脑")
        notes();field("设备备注").performScrollTo().assertTextContains("等促销时购买")
        compose.onNodeWithText("放弃草稿").performScrollTo().performClick()
        assertEquals("",field("设备名称").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field("购入金额（元）").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的设备").assertExists()
        compose.onNodeWithText("保留设备草稿？").assertDoesNotExist()
        assertNull(DraftStore(app).read("device:new"))
    }

    @Test fun editedSoldStateAndHiddenAmountsRestoreThenSaveOnceAndClearDraft() {
        val today=LocalDate.now()
        val original=Device(id="draft-device",name="旧手机",category=DeviceCategory.PHONE,purchaseAmount="3000",
            startDate=today.minusMonths(2).toString())
        runBlocking {app.repository.update {it.copy(devices=listOf(original))}}
        compose.waitUntil(10000) {compose.onAllNodesWithText("旧手机").fetchSemanticsNodes().isNotEmpty()}
        click("旧手机");click("编辑设备");awaitEditor()
        selectStatus("已卖出")
        fill("卖出日期",today.minusDays(1).toString())
        fill("卖出金额（元）","1250.50")
        notes();fill("设备备注","已转让，保留配件")
        selectStatus("服役中") // Hidden sale fields must still belong to the complete draft.
        compose.onNodeWithContentDescription("返回").performClick();click("保留草稿")
        assertEquals(original,runBlocking {app.repository.read()}.devices.single())
        click("编辑设备");awaitEditor()
        compose.onNodeWithText("已恢复上次草稿").assertExists()
        selectStatus("已卖出")
        field("卖出金额（元）").performScrollTo().assertTextContains("1250.50")
        field("卖出日期").performScrollTo().assertTextContains(today.minusDays(1).toString())
        notes();field("设备备注").performScrollTo().assertTextContains("已转让，保留配件")
        save()
        val saved=awaitDevice {it?.status==DeviceStatus.SOLD}
        assertEquals(original.id,saved.id)
        assertEquals("3000",saved.purchaseAmount)
        assertEquals("1250.50",saved.saleAmount)
        assertEquals(today.minusDays(1).toString(),saved.endDate)
        assertEquals("已转让，保留配件",saved.note)
        assertTrue(runBlocking {app.repository.read()}.payments.isEmpty())
        assertNull(DraftStore(app).read("device:${original.id}"))
        compose.onNodeWithText("净花费").assertExists()
        compose.onNodeWithText("¥1749.50").assertExists()
    }
}
