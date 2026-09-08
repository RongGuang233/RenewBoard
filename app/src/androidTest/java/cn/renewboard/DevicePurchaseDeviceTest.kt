package cn.renewboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
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
    private fun edit() {compose.onNodeWithContentDescription("设备更多操作").performClick();click("编辑设备")}
    private fun selectStatus(label:String) {
        compose.onNodeWithContentDescription("选择设备状态").performScrollTo().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun saveEdit() {compose.onNodeWithText("保存设备").performSemanticsAction(SemanticsActions.OnClick) {it()}}

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

    @Test fun purchasePreservesUnfinishedEditsWithoutRestoringOldBudgetOrStatus() {
        edit()
        fill("设备名称","普通编辑草稿")
        fill("购买预算（元）","2000")
        compose.onNodeWithContentDescription("选择设备分类").performScrollTo().performClick()
        compose.onNode(hasText("平板",substring=false) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("设备备注") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        fill("设备备注","尚未保存的配件备注")
        back();click("保留草稿")
        val editDraft=DraftStore(app).read("device:${original.id}")
        assertNotNull(editDraft)
        purchase()
        compose.onNodeWithText(original.name).assertExists()
        field("实际购入金额（元）").assertTextContains("8000")
        val started=LocalDate.now().minusDays(4).toString()
        fill("实际购入金额（元）","1500")
        fill("开始服役日期",started)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodes(hasText("实际购入金额（元）") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        field("实际购入金额（元）").assertTextContains("1500")
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
        field("实际购入金额（元）").assertTextContains("1500")
        field("开始服役日期").assertTextContains(started)
        confirm()
        compose.waitUntil(10000) {saved().status==DeviceStatus.ACTIVE}
        compose.waitForIdle()
        val purchased=original.copy(status=DeviceStatus.ACTIVE,purchaseAmount="1500",startDate=started)
        assertEquals(purchased,saved())
        assertNull(DraftStore(app).read(purchaseKey))
        assertNotNull(DraftStore(app).read("device:${original.id}"))
        click("编辑设备")
        compose.onNodeWithText("已恢复上次草稿").assertExists()
        field("设备名称").assertTextContains("普通编辑草稿")
        compose.onNodeWithContentDescription("选择设备状态").assertTextContains("服役中")
        compose.onNodeWithContentDescription("选择设备分类").assertTextContains("平板")
        field("购入金额（元）").performScrollTo().assertTextContains("1500")
        field("服役日期").performScrollTo().assertTextContains(started)
        saveEdit()
        compose.waitUntil(10000) {saved().name=="普通编辑草稿"}
        compose.waitForIdle()
        assertEquals(purchased.copy(name="普通编辑草稿",category=DeviceCategory.TABLET,note="尚未保存的配件备注"),saved())
        assertEquals(other,runBlocking {app.repository.read()}.devices.single {it.id==other.id})
        assertNull(DraftStore(app).read("device:${original.id}"))
    }

    @Test fun failedPurchaseLeavesSavedDeviceAndEditingDraftUntouched() {
        edit();fill("设备名称","失败后保留的编辑");fill("购买预算（元）","2000")
        back();click("保留草稿")
        val editDraft=DraftStore(app).read("device:${original.id}")
        assertNotNull(editDraft)
        purchase();fill("实际购入金额（元）","1500")
        fill("开始服役日期",LocalDate.now().minusDays(1).toString())
        compose.waitForIdle()
        val purchaseDraft=DraftStore(app).read(purchaseKey)
        assertNotNull(purchaseDraft)
        runBlocking(Dispatchers.IO) {
            app.repository.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_device_purchase BEFORE INSERT ON BookRow BEGIN SELECT RAISE(ABORT, 'test purchase write failure'); END")
        }
        try {
            confirm()
            compose.waitUntil(10000) {compose.onAllNodesWithText("test purchase write failure",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle()
            assertEquals(listOf(original,other),runBlocking {app.repository.read()}.devices)
            assertEquals(editDraft,DraftStore(app).read("device:${original.id}"))
            assertEquals(purchaseDraft,DraftStore(app).read(purchaseKey))
            compose.onNodeWithText("确认已购买").assertIsEnabled()
        } finally {
            runBlocking(Dispatchers.IO) {app.repository.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_device_purchase")}
        }
    }

    @Test fun changingWishlistStateClearsPurchaseDraftBeforeAnotherPurchase() {
        purchase();fill("实际购入金额（元）","1500")
        back();click("保留草稿")
        assertNotNull(DraftStore(app).read(purchaseKey))
        edit();selectStatus("服役中");fill("购入金额（元）","3000")
        fill("服役日期",LocalDate.now().minusDays(2).toString())
        saveEdit()
        compose.waitUntil(10000) {saved().status==DeviceStatus.ACTIVE}
        compose.waitForIdle()
        assertNull(DraftStore(app).read(purchaseKey))
        click("编辑设备");selectStatus("待购买");saveEdit()
        compose.waitUntil(10000) {saved().status==DeviceStatus.WISHLIST}
        compose.waitForIdle()
        purchase()
        field("实际购入金额（元）").assertTextContains("3000")
        compose.onNodeWithText("已恢复上次草稿").assertDoesNotExist()
    }

    @Test fun openPurchaseCannotOverwriteDeviceThatIsAlreadyActive() {
        purchase();fill("实际购入金额（元）","1500")
        val active=original.copy(status=DeviceStatus.ACTIVE,purchaseAmount="3000",
            startDate=LocalDate.now().minusDays(2).toString())
        // A saved-state change while the purchase form is open must win over its old input.
        runBlocking {app.repository.update {it.copy(devices=listOf(active,other))}}
        compose.waitForIdle()
        confirm()
        compose.waitUntil(10000) {compose.onAllNodesWithText("设备已不在待购买状态，请返回查看").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(active,saved())
        field("实际购入金额（元）").assertTextContains("1500")
        back();click("保留草稿")
        compose.onNodeWithText("记为已购买").assertDoesNotExist()
        compose.onNodeWithText("编辑设备").assertExists()
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
