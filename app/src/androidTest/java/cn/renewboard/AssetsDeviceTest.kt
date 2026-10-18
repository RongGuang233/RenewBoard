package cn.renewboard

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
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
    private fun addAccount() {
        compose.onNodeWithContentDescription("资产管理菜单").performScrollTo().performClick()
        compose.onNodeWithText("新增账户",substring=false).performClick();compose.waitForIdle()
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
    @Test fun manualBalanceCorrectionAndConfirmedDeletionPreserveHistoryWithoutExpenses() {
        addAccount()
        screenshot("assets-type-picker")
        click("银行卡")
        screenshot("assets-bank-picker")
        click("招商银行")
        compose.onNode(hasText("账户名称") and hasSetTextAction()).assertDoesNotExist()
        fill("当前余额","5000")
        screenshot("assets-add-account")
        click("保存账户")
        await {it.assets.accounts.singleOrNull()?.balance=="5000"}
        click("编辑");fill("当前余额","5300")
        screenshot("assets-edit-balance");click("保存账户")
        await {it.assets.accounts.singleOrNull()?.balance=="5300"}
        click("编辑");fill("当前余额","5200")
        click("纠正上次录入");click("保存账户")
        val corrected=await {it.assets.accounts.singleOrNull()?.balance=="5200"}
        assertEquals(2,corrected.assets.accounts.single().history.size);assertTrue(corrected.payments.isEmpty())
        assertEquals("余额校正",corrected.assets.history.last().reason)
        screenshot("assets-account-detail")
        back();addAccount()
        click("银行卡");click("招商银行")
        compose.onNodeWithText("账户详情",substring=false).assertExists()
        assertEquals(corrected,runBlocking {app.repository.read()})
        click("编辑");compose.onNodeWithContentDescription("归档账户开关").assertDoesNotExist()
        back();click("删除账户")
        compose.onNodeWithText("删除账户？",substring=false).assertExists()
        compose.onNodeWithText("取消").performClick();compose.waitForIdle();assertEquals(corrected,runBlocking {app.repository.read()})
        click("删除账户");compose.onNodeWithText("删除",substring=false).performClick()
        val deleted=await {it.assets.accounts.isEmpty()}
        assertEquals(corrected.assets.history,deleted.assets.history.take(corrected.assets.history.size))
        assertTrue(deleted.payments.isEmpty());assertEquals(0,Assets.totals(deleted).net.signum())
        compose.onNodeWithText("查看趋势").assertDoesNotExist()
        compose.onNodeWithText("排序").assertDoesNotExist()

    }
    @Test fun invalidAccountStaysEditableAndPrepaidSwitchControlsNetWorth() {
        addAccount()
        click("饭卡")
        compose.onNodeWithContentDescription("计入净资产开关").assertIsOn()
        back();click("其他充值")
        compose.onNodeWithContentDescription("计入净资产开关").assertIsOff()
        compose.onNode(hasText("账户名称") and hasSetTextAction()).assertDoesNotExist()
        fill("当前余额","-bad")
        click("保存账户")
        compose.onNode(hasText("当前余额") and hasSetTextAction()).assertExists()
        assertTrue(runBlocking {app.repository.read()}.assets.accounts.isEmpty())
        fill("当前余额","200")
        compose.onNodeWithContentDescription("计入净资产开关").performScrollTo().performClick()
        compose.onNodeWithContentDescription("计入净资产开关").assertIsOn()
        click("保存账户")
        val saved=await {it.assets.accounts.singleOrNull()?.balance=="200"}
        assertEquals(0,BigDecimal("200").compareTo(Assets.totals(saved).net))
        click("编辑");compose.onNodeWithContentDescription("计入净资产开关").performScrollTo().performClick();click("保存账户")
        val excluded=await {it.assets.accounts.singleOrNull()?.included==false}
        assertEquals(0,Assets.totals(excluded).net.signum());assertTrue(excluded.payments.isEmpty())
    }

    @Test fun selectingExistingAppTypeKeepsItsBalanceAndHistory() {
        addAccount();click("微信")
        compose.onNode(hasText("账户名称") and hasSetTextAction()).assertDoesNotExist()
        fill("当前余额","100");click("保存账户")
        await {it.assets.accounts.singleOrNull()?.name=="微信"}
        click("编辑");fill("当前余额","125");click("保存账户")
        val before=await {it.assets.accounts.singleOrNull()?.balance=="125"}
        assertEquals(2,before.assets.accounts.single().history.size)
        back();addAccount();click("微信")
        compose.onNodeWithText("账户详情",substring=false).assertExists()
        compose.onNodeWithText("保存账户",substring=false).assertDoesNotExist()
        assertEquals(before,runBlocking {app.repository.read()})
    }

    @Test fun unifiedEditorChangesNameTypeCurrencyBalanceAndMetadataTogether() {
        seed(listOf(
            AssetAccount(id="wallet",name="微信",category=AssetCategory.CASH,subtype="微信",balance="100",history=listOf(AssetBalanceRecord(balance="100",date=java.time.LocalDate.now().minusDays(1).toString()))),
            AssetAccount(id="cash",name="现金",category=AssetCategory.CASH,subtype="现金",balance="150")))
        click("微信")
        compose.onNodeWithText("更新余额",substring=false).assertDoesNotExist()
        compose.onNodeWithText("账户设置",substring=false).assertDoesNotExist()
        click("编辑")
        fill("账户名称","自用会员卡")
        compose.onNodeWithContentDescription("编辑账户类型").performScrollTo().performClick()
        click("会员卡")
        fill("当前余额","123")
        compose.onNodeWithContentDescription("选择币种").performScrollTo().performClick()
        compose.onNodeWithText("美元 $",substring=false).performClick()
        fill("折算汇率（1 USD = ? CNY）","7.2")
        fill("备注","本地编辑测试")
        compose.onNodeWithContentDescription("计入净资产开关").performScrollTo().performClick()
        click("保存账户")
        val saved=await {it.assets.accounts.first {a->a.id=="wallet"}.name=="自用会员卡"}
        val account=saved.assets.accounts.first {it.id=="wallet"}
        assertEquals(AssetCategory.PREPAID,account.category);assertEquals("会员卡",account.subtype)
        assertEquals("USD",account.currency);assertEquals("123",account.balance);assertEquals("7.2",account.cnyRate)
        assertEquals("本地编辑测试",account.note);assertFalse(account.included)
        assertEquals(0,Assets.totals(saved).net.compareTo(BigDecimal("150")))
        compose.onNodeWithText("¥100.00",substring=false).performScrollTo().assertExists()
        click("编辑")
        compose.onNode(hasText("账户名称") and hasSetTextAction()).performScrollTo()
        screenshot("assets-unified-editor")
        click("保存账户");compose.waitForIdle()
        assertEquals(saved,runBlocking {app.repository.read()})
        click("编辑");fill("账户名称","未保存名称");back()
        assertEquals(saved,runBlocking {app.repository.read()})
    }

    @Test fun tuitionAccountCanBeEditedLocallyWithoutDownloading() {
        val snapshot=TuitionSnapshot(publishedAt="2026-10-15T08:00:00Z",students=listOf(TuitionStudent("edit-student","示例学生","active",120000,true)))
        runBlocking {app.repository.restore(Backup(data=Assets.importTuition(Ledger(),snapshot,setOf("edit-student"),"2026-10-15T09:00:00Z")))}
        compose.waitForIdle();click("查看学生");click("示例学生");click("编辑")
        fill("账户名称","学生别名");fill("当前余额","900");fill("备注","手动核对")
        compose.onNodeWithText("学费 · 人民币 CNY",substring=false).assertExists()
        compose.onNodeWithContentDescription("编辑账户类型").assertDoesNotExist()
        click("保存账户")
        val saved=await {it.assets.accounts.single().balance=="900"}
        assertEquals("edit-student",saved.assets.accounts.single().studentId)
        assertEquals("学生别名",saved.assets.accounts.single().name)
        assertEquals("手动核对",saved.assets.accounts.single().note)
        assertEquals("2026-10-15T08:00:00Z",saved.assets.tuitionPublishedAt)
    }

    @Test fun rechargeGroupHasOnlyActualTotalAndHidingCoversAllAmounts() {
        seed(listOf(
            AssetAccount(name="示例饭卡",category=AssetCategory.PREPAID,subtype="饭卡",balance="240.74"),
            AssetAccount(name="示例会员卡",category=AssetCategory.PREPAID,subtype="会员卡",balance="100",included=false)
        ))
        compose.onNodeWithContentDescription("收起充值").assertTextContains("¥340.74")
        compose.onAllNodesWithText("其中计入",substring=true).assertCountEquals(0)
        assertEquals(0,Assets.totals(runBlocking {app.repository.read()}).net.compareTo(BigDecimal("240.74")))
        compose.onNodeWithText("不计入",substring=false).assertDoesNotExist()
        compose.onNodeWithContentDescription("隐藏资产金额").performScrollTo().performClick()
        listOf("¥340.74","¥240.74","¥100.00","其中计入净资产 ¥240.74").forEach {
            compose.onAllNodesWithText(it,substring=true).assertCountEquals(0)
        }
        compose.onAllNodesWithText("其中计入",substring=true).assertCountEquals(0)
        compose.onNodeWithContentDescription("显示资产金额").performClick()
        compose.onNodeWithContentDescription("收起充值").assertTextContains("¥340.74")
    }

    @Test fun categoryAndAccountOrderAndFoldingSurviveActivityRecreation() {
        seed(listOf(
            AssetAccount(name="现金",category=AssetCategory.CASH,subtype="现金",balance="1000"),
            AssetAccount(name="微信",category=AssetCategory.CASH,subtype="微信",balance="300"),
            AssetAccount(name="示例饭卡",category=AssetCategory.PREPAID,subtype="饭卡",balance="200")
        ))
        val cashRow=compose.onNodeWithText("现金",substring=false).performScrollTo().fetchSemanticsNode().boundsInRoot
        val wechat=compose.onNodeWithText("微信",substring=false).performScrollTo()
        val from=wechat.fetchSemanticsNode().boundsInRoot
        wechat.performTouchInput {
            down(center);advanceEventTime(650)
            moveTo(center.copy(y=center.y+cashRow.center.y-from.center.y),delayMillis=500);up()
        }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithText("微信").fetchSemanticsNode().positionInRoot.y<compose.onNodeWithText("现金").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("收起资金账户").performScrollTo().performClick()
        compose.onNodeWithContentDescription("收起充值").performScrollTo().performClick()
        compose.waitForIdle()
        val cashHeader=compose.onNodeWithContentDescription("展开资金账户").fetchSemanticsNode().boundsInRoot
        val prepaidHeader=compose.onNodeWithContentDescription("展开充值")
        val prepaidFrom=prepaidHeader.fetchSemanticsNode().boundsInRoot
        prepaidHeader.performTouchInput {
            down(center);advanceEventTime(650)
            moveTo(center.copy(y=center.y+cashHeader.center.y-prepaidFrom.center.y),delayMillis=500);up()
        }
        compose.waitForIdle()
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("展开充值").fetchSemanticsNodes().isNotEmpty()}
        val prepaid=compose.onNodeWithContentDescription("展开充值").fetchSemanticsNode().positionInRoot.y
        val cash=compose.onNodeWithContentDescription("展开资金账户").fetchSemanticsNode().positionInRoot.y
        assertTrue("充值 should retain the chosen first position",prepaid<cash)
        compose.onNodeWithText("现金",substring=false).assertDoesNotExist();compose.onNodeWithText("示例饭卡").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开资金账户").performScrollTo().performClick()
        val wechatTop=compose.onNodeWithText("微信",substring=false).fetchSemanticsNode().positionInRoot.y
        val cashTop=compose.onNodeWithText("现金",substring=false).fetchSemanticsNode().positionInRoot.y
        assertTrue("Account order within 资金账户 must also persist",wechatTop<cashTop)
        compose.onNodeWithContentDescription("展开充值").performScrollTo().performClick()
        compose.onNodeWithText("示例饭卡").assertExists()
    }

    @Test fun borrowingSummaryOpensAccountsAndToggleExcludesLoansPersistently() {
        seed(listOf(
            AssetAccount(name="现金",category=AssetCategory.CASH,subtype="现金",balance="1000"),
            AssetAccount(name="朋友借款",category=AssetCategory.DEBT,subtype="借入款",balance="100"),
            AssetAccount(name="借给朋友",category=AssetCategory.RECEIVABLE,subtype="借出款",balance="300"),
            AssetAccount(name="信用卡",category=AssetCategory.DEBT,subtype="信用卡",balance="200"),
            AssetAccount(name="租房押金",category=AssetCategory.RECEIVABLE,subtype="可退押金",balance="50")
        ))
        compose.onNodeWithText("朋友借款").assertDoesNotExist()
        compose.onNodeWithText("借给朋友").assertDoesNotExist()
        compose.onNodeWithText("¥1,050.00").assertExists()
        click("总借入");click("朋友借款")
        compose.onNodeWithText("编辑").assertExists();back();back()
        click("总借出");click("借给朋友");click("编辑")
        fill("当前余额","350");click("保存账户")
        await {it.assets.accounts.any {a->a.name=="借给朋友" && a.balance=="350"}}
        back();back()
        compose.onNodeWithContentDescription("资产管理菜单").performScrollTo().performClick()
        compose.onNodeWithText("显示借入借出").performClick()
        val off=await {!it.assets.showBorrowing}
        assertEquals(5,off.assets.accounts.size);assertTrue(off.assets.accounts.all {it.included})
        assertEquals(0,Assets.totals(off).net.compareTo(BigDecimal("850")))
        compose.onNodeWithText("总借入").assertDoesNotExist();compose.onNodeWithText("总借出").assertDoesNotExist()
        compose.onNodeWithText("¥850.00").assertExists()
        compose.onNodeWithText("信用卡").assertExists();compose.onNodeWithText("租房押金").assertExists()
        screenshot("assets-borrowing-hidden")
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.onNodeWithText("总借入").assertDoesNotExist()
        assertFalse(runBlocking {app.repository.read()}.assets.showBorrowing)
        compose.onNodeWithContentDescription("资产管理菜单").performScrollTo().performClick()
        compose.onNodeWithText("显示借入借出").performClick()
        val on=await {it.assets.showBorrowing}
        assertEquals(0,Assets.totals(on).net.compareTo(BigDecimal("1100")))
        compose.onNodeWithText("总借入").assertExists();compose.onNodeWithText("总借出").assertExists()
        compose.onNodeWithText("朋友借款").assertDoesNotExist();compose.onNodeWithText("借给朋友").assertDoesNotExist()
        screenshot("assets-borrowing-shown")
    }

    @Test fun studentAccountsAlsoSupportLongPressSorting() {
        val students=listOf(
            AssetAccount(id="student-first",name="示例学生甲",category=AssetCategory.TUITION,studentId="a",balance="1200"),
            AssetAccount(id="student-second",name="示例学生乙",category=AssetCategory.TUITION,studentId="b",balance="500"))
        runBlocking {app.repository.restore(Backup(data=Ledger(assets=AssetBook(accounts=students))))}
        compose.waitForIdle();click("查看学生")
        val first=compose.onNodeWithText("示例学生甲").performScrollTo().fetchSemanticsNode().boundsInRoot
        val second=compose.onNodeWithText("示例学生乙").performScrollTo()
        val from=second.fetchSemanticsNode().boundsInRoot
        second.performTouchInput {
            down(center);advanceEventTime(650)
            moveTo(center.copy(y=center.y+first.center.y-from.center.y),delayMillis=500);up()
        }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithText("示例学生乙").fetchSemanticsNode().positionInRoot.y<compose.onNodeWithText("示例学生甲").fetchSemanticsNode().positionInRoot.y)
        assertEquals(students,runBlocking {app.repository.read()}.assets.accounts)
    }

    @Test fun draggingCategoryAtBottomScrollsToTheNextOffscreenGroup() {
        seed((1..8).map {AssetAccount(name="测试账户$it",category=AssetCategory.CASH,balance="100")}+
            AssetAccount(name="饭卡",category=AssetCategory.PREPAID,subtype="饭卡",balance="200"))
        val header=compose.onNodeWithContentDescription("收起资金账户").performScrollTo()
        val bounds=header.fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        header.performTouchInput {
            down(center);advanceEventTime(650)
            moveTo(center.copy(y=viewport.bottom-bounds.top-12),delayMillis=300)
            repeat(30) {moveBy(androidx.compose.ui.geometry.Offset(0f,0f),delayMillis=64)}
            up()
        }
        compose.waitForIdle()
        val saved=app.getSharedPreferences("asset-display",0).getString("groups","")!!.split(",")
        assertTrue("Dragging at the edge must reach and reorder the initially offscreen recharge group",saved.indexOf("PREPAID")>=0 && saved.indexOf("PREPAID")<saved.indexOf("CASH"))
        compose.onNodeWithText("账户详情",substring=false).assertDoesNotExist()
    }

    @Test fun referenceStyleOverviewShowsBrandsAndOnlyPendingLessonsWithoutDroppingReceivables() {
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
        listOf("现金" to "cash", "支付宝" to "alipay", "农业银行" to "abc").forEach {(label,id)->
            val row=compose.onNodeWithText(label,substring=false).captureToImage().asAndroidBitmap()
            java.io.File(app.filesDir,"assets-$id-icon-row.png").outputStream().use {row.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithText("查看学生",substring=false).performScrollTo()
        compose.onNodeWithText("学费",substring=false).assertIsDisplayed()
        compose.onNodeWithText("尚未同步",substring=true).assertDoesNotExist()
        compose.onNodeWithText("待上课",substring=false).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("待收款",substring=true).assertCountEquals(0)
        compose.onNodeWithText("示例学生甲").assertDoesNotExist()
        compose.onAllNodesWithText("其中计入",substring=true).assertCountEquals(0)
        screenshot("assets-reference-prepaid-tuition")
        val tuitionRow=compose.onNodeWithText("待上课",substring=false).captureToImage().asAndroidBitmap()
        java.io.File(app.filesDir,"assets-tuition-icon-row.png").outputStream().use {tuitionRow.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        click("查看学生")
        compose.onNodeWithText("示例学生乙",substring=false).assertExists()
        compose.onNodeWithText("待收款",substring=false).assertExists()
        assertEquals(0,Assets.totals(runBlocking {app.repository.read()}).net.compareTo(BigDecimal("34640.50")))
    }
}
