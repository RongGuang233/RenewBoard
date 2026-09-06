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

/** Real activity, text input and Room persistence. Only the debug application's ledger is reset. */
@RunWith(AndroidJUnit4::class)
class MainActivityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: RenewApp

    @Before fun resetDebugLedger() {
        app = ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug")) { "UI tests must only target the debug application" }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking { app.repository.update { Ledger() } }
        compose.waitForIdle()
    }

    @After fun cleanDebugLedger() {
        if (::app.isInitialized && app.packageName.endsWith(".debug")) {
            runBlocking { app.repository.update { Ledger() } }
        }
    }

    private fun fill(label: String, value: String) {
        compose.onNode(hasText(label) and hasSetTextAction())
            .performScrollTo().performTextReplacement(value)
    }

    private fun click(text: String) {
        if(text=="返回") compose.onNodeWithText(text).performClick()
        else compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun awaitLedger(predicate: (Ledger) -> Boolean): Ledger {
        try { compose.waitUntil(timeoutMillis = 10_000) { predicate(runBlocking { app.repository.read() }) } }
        catch (e: Throwable) { throw AssertionError("Ledger=" + runBlocking { app.repository.read() } + "\nUI=" + compose.onRoot(useUnmergedTree=true).printToString(),e) }
        compose.waitForIdle()
        return runBlocking { app.repository.read() }
    }

    @Test fun createEditRecreateArchiveAndDeleteThroughActualScreens() {
        compose.onNodeWithText("还没有需要记挂的到期日").assertExists()
        // The FAB has no scroll ancestor; other form controls do.
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        fill("订阅 / 套餐名称", "设备录入套餐")
        fill("套餐价格", "36.80")
        fill("扣款日期", "2026-09-01")
        click("包含的权益")
        fill("权益 1 名称", "设备录入权益")
        fill("到期日期", "2026-10-01")
        fill("备注", "设备测试备注")
        click("保存订阅")
        val created = awaitLedger { it.plans.size == 1 && it.payments.size == 1 }
        assertEquals("设备录入套餐", created.plans.single().name)
        assertEquals("36.80", created.payments.single().amount)
        assertEquals(listOf(created.benefits.single().id), created.payments.single().benefitIds)

        compose.onNodeWithText("订阅", substring = false).performClick()
        click("设备录入套餐")
        click("编辑订阅与到期日")
        fill("订阅 / 套餐名称", "已编辑套餐")
        fill("套餐价格", "48.00")
        click("包含的权益")
        fill("权益 1 名称", "已编辑权益")
        fill("到期日期", "2026-11-15")
        click("保存订阅")
        val edited = awaitLedger { it.plans.singleOrNull()?.name == "已编辑套餐" }
        assertEquals(created.plans.single().id, edited.plans.single().id)
        assertEquals("48.00", edited.plans.single().amount)
        assertEquals("2026-11-15", Book.expiry(edited.benefits.single(), edited.plans.single()).toString())
        assertEquals(created.payments, edited.payments)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        click("已编辑套餐")
        compose.onNodeWithText("2026-11-15 到期").assertExists()
        click("归档订阅")
        awaitLedger { it.plans.singleOrNull()?.archived == true }
        click("返回")
        click("已归档")
        click("已编辑套餐")
        click("删除订阅")
        compose.onNodeWithText("删除", substring = false).performClick()
        val deleted = awaitLedger { it.plans.isEmpty() }
        assertTrue(deleted.benefits.isEmpty())
        assertEquals(created.payments, deleted.payments)
        compose.onNodeWithText("账本", substring = false).performClick()
        compose.onNodeWithText("设备录入套餐").performScrollTo().assertIsDisplayed()
    }

    @Test fun monthEndSubscriptionKeepsOriginalAnchorThroughEarlyRenewalAndGift() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        fill("订阅 / 套餐名称", "月末续费套餐")
        fill("套餐价格", "30.00")
        fill("扣款日期", "2024-01-31")
        click("包含的权益")
        fill("权益 1 名称", "月末权益")
        fill("到期日期", "2024-02-29")
        click("保存订阅")
        val created = awaitLedger { it.plans.size == 1 && it.payments.size == 1 }
        assertEquals("2024-02-29", Book.expiry(created.benefits.single(), created.plans.single()).toString())

        compose.onNodeWithText("订阅", substring = false).performClick()
        click("月末续费套餐")
        click("记录付款 / 提前续费")
        fill("付款日期 YYYY-MM-DD", "2024-02-10")
        compose.onNodeWithText("确认付款").performClick()
        val renewed = awaitLedger { it.payments.size == 2 }
        assertEquals(1, renewed.plans.size)
        assertEquals("2024-03-31", Book.expiry(renewed.benefits.single(), renewed.plans.single()).toString())
        assertEquals("2024-02-10", renewed.payments.last().date)
        assertEquals("30.00", renewed.payments.last().amount)
        assertEquals(created.payments.single(), renewed.payments.first())
        compose.onNodeWithText("2024-03-31 到期").assertExists()

        click("增加赠送时长")
        compose.onNode(hasText("增加天数") and hasSetTextAction()).performTextReplacement("7")
        compose.onNodeWithText("增加", substring = false).performClick()
        val gifted = awaitLedger { it.benefits.singleOrNull()?.giftDays == 7 }
        assertEquals("2024-04-07", Book.expiry(gifted.benefits.single(), gifted.plans.single()).toString())
        assertEquals(renewed.payments, gifted.payments)
        assertEquals(renewed.plans, gifted.plans)
        compose.onNodeWithText("2024-04-07 到期").assertExists()
    }

    @Test fun systemBackAndVisibleBackReturnOneLevel() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("还没有需要记挂的到期日").assertExists()
        val plan=Plan(name="返回测试",amount="10",billingAnchor="2026-09-01")
        runBlocking { app.repository.update { Ledger(plans=listOf(plan),benefits=listOf(Benefit(planId=plan.id,name=plan.name,anchor="2026-10-01"))) } }
        compose.waitForIdle()
        compose.onNodeWithText("订阅",substring=false).performClick()
        click("返回测试")
        click("编辑订阅与到期日")
        compose.onNode(hasText("备注") and hasSetTextAction()).performScrollTo()
        compose.onNodeWithText("返回",substring=false).assertIsDisplayed()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("编辑订阅与到期日").assertExists()
        assertEquals("",runBlocking { app.repository.read() }.plans.single().note)
        click("编辑订阅与到期日")
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        compose.onNodeWithText("返回",substring=false).performClick()
        compose.onNodeWithText("编辑订阅与到期日").assertExists()
        click("记录付款 / 提前续费")
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("记录实际付款").assertDoesNotExist()
        compose.onNodeWithText("编辑订阅与到期日").assertExists()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("订阅",substring=false).assertExists()
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        compose.onNodeWithText("返回",substring=false).performClick()
        compose.onNodeWithText("订阅",substring=false).assertExists()
    }

    @Test fun compactCycleSelectorAndCollapsedBenefitsPreserveInput() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        compose.onNodeWithText("权益 1 名称").assertDoesNotExist()
        compose.onNodeWithText("到期日期").assertDoesNotExist()
        fill("订阅 / 套餐名称","双周套餐")
        fill("套餐价格","12")
        fill("周期","2")
        compose.onNodeWithContentDescription("选择周期单位").performScrollTo().performClick()
        compose.onNodeWithText("周",substring=false).performClick()
        fill("扣款日期","2026-09-01")
        click("包含的权益")
        compose.onAllNodesWithContentDescription("选择日期").assertCountEquals(2)
        fill("到期日期","2026-09-15")
        click("包含的权益")
        compose.onNodeWithText("到期日期").assertDoesNotExist()
        screenshot("compact-editor")
        click("保存订阅")
        val saved=awaitLedger { it.plans.size==1 }
        assertEquals(2,saved.plans.single().interval)
        assertEquals(Cycle.WEEK,saved.plans.single().cycle)
        assertEquals("2026-09-15",Book.expiry(saved.benefits.single(),saved.plans.single()).toString())
    }

    @Test fun invalidFormRemainsEditableAndDoesNotWriteAnyLedgerRecords() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        fill("订阅 / 套餐名称", "无效金额测试")
        fill("套餐价格", "不是金额")
        click("保存订阅")
        compose.onNodeWithText("请检查名称、金额、周期和日期：", substring = true).assertExists()
        assertEquals(Ledger(), runBlocking { app.repository.read() })
        fill("套餐价格", "10.00")
        click("保存订阅")
        val saved = awaitLedger { it.plans.size == 1 }
        assertEquals("10.00", saved.plans.single().amount)
        assertEquals(1, saved.payments.size)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400) // Let Android window/compositor animations settle before screenshot.
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }

    @Test fun catalogForeignPaymentAndRenewalFreezeSeparateSettlementAmounts() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        click("选择常见会员")
        screenshot("catalog")
        compose.onNode(hasText("搜索会员") and hasSetTextAction()).performTextReplacement("ChatGPT")
        compose.onNode(hasText("ChatGPT") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNode(hasText("订阅 / 套餐名称") and hasSetTextAction()).assertTextContains("ChatGPT")
        fill("套餐价格","20")
        click("保存订阅")
        assertTrue(runBlocking { app.repository.read() }.payments.isEmpty())
        fill("人民币实付金额","142.80")
        click("保存订阅")
        val saved=awaitLedger { it.payments.size==1 }
        assertEquals("USD",saved.payments.single().currency)
        assertEquals("142.80",saved.payments.single().cnyAmount)
        compose.onNodeWithText("订阅",substring=false).performClick()
        click("ChatGPT")
        click("记录付款 / 提前续费")
        fill("人民币实付金额","145.60")
        compose.onNodeWithText("确认付款").performClick()
        val renewed=awaitLedger { it.payments.size==2 }
        assertEquals("142.80",renewed.payments.first().cnyAmount)
        assertEquals("145.60",renewed.payments.last().cnyAmount)
        runBlocking { app.repository.update { it.copy(settings=it.settings.copy(rates=mapOf("USD" to "99"))) } }
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        click("返回")
        compose.onNodeWithText("账本",substring=false).performClick()
        compose.onNodeWithText("¥288.40",substring=false).assertExists()
        screenshot("frozen-receipts")
    }

    @Test fun legacyPaymentCanBeCompletedOnceAndRestoredWithoutRateRecalculation() {
        val payment=Payment(planId="deleted",planName="Netflix",amount="15",currency="USD",date=java.time.LocalDate.now().toString())
        runBlocking { app.repository.update { Ledger(payments=listOf(payment),settings=Settings(rates=mapOf("USD" to "7"))) } }
        compose.waitForIdle()
        compose.onNodeWithText("账本",substring=false).performClick()
        click("补录人民币")
        compose.onNode(hasText("人民币实付金额") and hasSetTextAction()).performTextReplacement("108.50")
        compose.onNodeWithText("保存金额").performClick()
        val completed=awaitLedger { it.payments.singleOrNull()?.cnyAmount=="108.50" }
        val restored=Book.decode(Book.encode(completed))
        runBlocking { app.repository.restore(restored) }
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.onAllNodesWithText("¥108.50",substring=false).assertCountEquals(2)
        compose.onNodeWithText("补录人民币").assertDoesNotExist()
    }

    @Test fun subscriptionRowsExposeServiceIconsAndExpiryWithCompactLayout() {
        val today=java.time.LocalDate.now()
        val names=listOf("哔哩哔哩大会员","网易云音乐","ChatGPT","哈啰单车","OneDrive")
        val plans=names.mapIndexed { i,name->Plan(id="visual-$i",name=name,amount=listOf("25","18","20","15","10")[i],currency=if(i==2) "USD" else "CNY",billingAnchor=today.minusDays(27-i.toLong()).toString()) }
        val benefits=plans.mapIndexed { i,p->Benefit(planId=p.id,name=p.name,anchor=today.plusDays(i*3L+1).toString()) }
        runBlocking { app.repository.update { Ledger(plans=plans,benefits=benefits,payments=listOf(Payment(planId="visual-2",planName="ChatGPT",amount="20",currency="USD",date=today.minusDays(1).toString(),cnyAmount="142.80"))) } }
        compose.waitForIdle()
        compose.onNodeWithText("最近到期").assertExists()
        compose.waitUntil(5000) { compose.onAllNodesWithText("哔哩哔哩大会员").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("¥210.80",substring=false).assertExists()
        screenshot("overview-v1.1")
        compose.onNodeWithText("订阅",substring=false).performClick()
        compose.onNodeWithText("哔哩哔哩大会员").assertExists()
        screenshot("subscriptions-v1.1")
    }

    @Test fun acceleratorCatalogHasRealEntriesAndFiltersByCategory() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        click("选择常见会员")
        compose.onNode(hasText("游戏加速") and hasClickAction()).performClick()
        compose.onNode(hasText("搜索会员") and hasSetTextAction()).performTextReplacement("小黑盒")
        compose.onNode(hasText("小黑盒加速器") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNode(hasText("订阅 / 套餐名称") and hasSetTextAction()).assertTextContains("小黑盒加速器")
        fill("套餐价格","15")
        click("保存订阅")
        awaitLedger { it.plans.singleOrNull()?.name=="小黑盒加速器" }
    }

}
