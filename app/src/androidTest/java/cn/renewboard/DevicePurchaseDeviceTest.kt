package cn.renewboard

import androidx.compose.ui.semantics.SemanticsActions
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
class DevicePurchaseDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp
    private val original=Device(id="purchase-computer",name="待购电脑",category=DeviceCategory.COMPUTER,
        status=DeviceStatus.WISHLIST,purchaseAmount="8000",startDate=LocalDate.now().plusMonths(1).toString(),note="保留配件与保修信息")
    private val other=Device(id="purchase-watch",name="待购手表",category=DeviceCategory.WATCH,
        status=DeviceStatus.WISHLIST,purchaseAmount="2000")
    private val purchaseKey="device:purchase:${original.id}"

    @Before fun prepare() {
        app=ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug"))
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        runBlocking {app.repository.update {Ledger(devices=listOf(original,other))}}
        compose.waitForIdle()
        click("设备")
        compose.onNodeWithContentDescription("筛选设备状态").performClick()
        compose.onNode(hasText("待购买") and hasAnyAncestor(isPopup())).performClick()
        click(original.name)
    }

    @After fun clean() {
        if(::app.isInitialized && app.packageName.endsWith(".debug")) {
            runBlocking {app.repository.update {Ledger()}}
            app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        }
    }

    private fun click(text:String) {compose.onNodeWithText(text,substring=false).performClick()}
    private fun field(label:String)=compose.onNode(hasText(label) and hasSetTextAction())
    private fun fill(label:String,value:String) {field(label).performScrollTo().performTextReplacement(value)}
    private fun back() {compose.onNodeWithContentDescription("返回").performClick()}
    private fun saved()=runBlocking {app.repository.read()}.devices.single {it.id==original.id}
    private fun purchase() {click("记为已购买");field("实际购入金额（元）").assertExists()}
    private fun confirm() {compose.onNodeWithText("确认已购买").performSemanticsAction(SemanticsActions.OnClick) {it()}}

    @Test fun cancelThenConfirmUsesActualValuesAndPreservesDeviceAndLedger() {
        compose.onNodeWithText("编辑设备").assertDoesNotExist()
        purchase()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        field("实际购入金额（元）").assertTextContains("8000")
        field("开始服役日期").assertTextContains(LocalDate.now().toString())
        fill("实际购入金额（元）","7000")
        back();click("放弃修改")
        assertEquals(original,saved())
        assertNull(DraftStore(app).read(purchaseKey))
        purchase()
        fill("实际购入金额（元）","-1")
        confirm()
        compose.onNodeWithText("金额格式错误（最多4位小数）").assertExists()
        field("实际购入金额（元）").assertTextContains("-1")
        assertEquals(original,saved())
        val started=LocalDate.now().minusDays(2).toString()
        fill("实际购入金额（元）","7299.50")
        fill("开始服役日期",started)
        confirm()
        compose.waitUntil(10000) {saved().status==DeviceStatus.ACTIVE}
        compose.waitForIdle()
        assertEquals(original.copy(status=DeviceStatus.ACTIVE,purchaseAmount="7299.50",startDate=started),saved())
        val ledger=runBlocking {app.repository.read()}
        assertEquals(other,ledger.devices.single {it.id==other.id})
        assertTrue(ledger.payments.isEmpty())
        assertTrue(ledger.plans.isEmpty())
        assertNull(DraftStore(app).read(purchaseKey))
        compose.onNodeWithText("编辑设备").assertExists()
        compose.onNodeWithText("记为已购买").assertDoesNotExist()
        compose.onNodeWithText("已服役 3 天").assertExists()
    }

    @Test fun purchaseDraftSurvivesRecreationAndStaysSeparateFromEditingAndOtherDevices() {
        compose.onNodeWithContentDescription("设备更多操作").performClick();click("编辑设备")
        fill("设备名称","普通编辑草稿")
        back();click("保留草稿")
        val editDraft=DraftStore(app).read("device:${original.id}")
        assertNotNull(editDraft)
        purchase()
        compose.onNodeWithText(original.name).assertExists()
        field("实际购入金额（元）").assertTextContains("8000")
        val started=LocalDate.now().minusDays(4).toString()
        fill("实际购入金额（元）","6999.90")
        fill("开始服役日期",started)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("实际购入金额（元）") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        field("实际购入金额（元）").assertTextContains("6999.90")
        field("开始服役日期").assertTextContains(started)
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        click("保留草稿")
        field("实际购入金额（元）").assertDoesNotExist()
        assertEquals(original,saved())
        back();click(other.name);purchase()
        field("实际购入金额（元）").assertTextContains("2000")
        field("开始服役日期").assertTextContains(LocalDate.now().toString())
        back();compose.onNodeWithText("保留购买草稿？").assertDoesNotExist()
        back();click(original.name);purchase()
        compose.onNodeWithText("已恢复上次草稿").assertExists()
        field("实际购入金额（元）").assertTextContains("6999.90")
        field("开始服役日期").assertTextContains(started)
        confirm()
        compose.waitUntil(10000) {saved().status==DeviceStatus.ACTIVE}
        compose.waitForIdle()
        assertEquals(original.copy(status=DeviceStatus.ACTIVE,purchaseAmount="6999.90",startDate=started),saved())
        assertNull(DraftStore(app).read(purchaseKey))
        assertEquals(editDraft,DraftStore(app).read("device:${original.id}"))
    }

    @Test fun unchangedDefaultsExitWithoutDraftPromptIncludingAfterRecreation() {
        purchase();back()
        compose.onNodeWithText("保留购买草稿？").assertDoesNotExist()
        assertNull(DraftStore(app).read(purchaseKey))
        purchase()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("实际购入金额（元）") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithText("保留购买草稿？").assertDoesNotExist()
        compose.onNodeWithText("记为已购买").assertExists()
        field("实际购入金额（元）").assertDoesNotExist()
        assertEquals(original,saved())
        assertNull(DraftStore(app).read(purchaseKey))
    }
}
