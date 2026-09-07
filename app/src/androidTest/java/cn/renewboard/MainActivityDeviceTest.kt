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
        if(label=="备注") openMoreOptions()
        val field=hasText(label) and hasSetTextAction()
        awaitNode(field,"field-before-input")
        compose.onNode(field).performScrollTo()
        compose.waitForIdle()
        awaitNode(field,"field-after-scroll")
        compose.onNode(field).performTextReplacement(value)
    }

    private fun openMoreOptions() {
        if(compose.onAllNodes(hasText("备注") and hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("更多选项").performScrollTo().performClick()
        }
    }

    private fun click(text: String) {
        if(text in setOf("编辑订阅与到期日","仅补记付款","归档订阅","恢复使用","删除订阅","明天提醒")) {
            compose.onNodeWithContentDescription("订阅更多操作").performScrollTo().performClick()
            compose.onNodeWithText(text,substring=false).performClick()
            compose.waitForIdle()
            if(text=="编辑订阅与到期日") awaitNode(hasText("订阅 / 套餐名称") and hasSetTextAction(),"editor-opening")
            return
        }
        if(text=="保存订阅") {
            // IME-driven scrolling can move this button during injected pointer events.
            compose.onNodeWithText(text).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            return
        }
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
        compose.onAllNodesWithText("设备录入套餐").onFirst().performScrollTo().assertIsDisplayed()
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
        fill("付款日期", "2024-02-10")
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
        openMoreOptions()
        compose.onNode(hasText("备注") and hasSetTextAction()).performScrollTo()
        compose.onNodeWithText("返回",substring=false).assertIsDisplayed()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("订阅更多操作").assertExists()
        assertEquals("",runBlocking { app.repository.read() }.plans.single().note)
        click("编辑订阅与到期日")
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        compose.onNodeWithText("返回",substring=false).performClick()
        compose.onNodeWithContentDescription("订阅更多操作").assertExists()
        click("记录付款 / 提前续费")
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("记录实际付款").assertDoesNotExist()
        compose.onNodeWithContentDescription("订阅更多操作").assertExists()
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

    @Test fun newBenefitFollowsBillingCycleUntilManuallyChanged() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        fill("订阅 / 套餐名称","自动权益测试")
        fill("套餐价格","20")
        fill("扣款日期","2024-01-31")
        click("包含的权益")
        compose.onNode(hasText("到期日期") and hasSetTextAction()).assertTextContains("2024-02-29")
        fill("周期","2")
        compose.onNode(hasText("到期日期") and hasSetTextAction()).assertTextContains("2024-03-31")
        fill("到期日期","2024-04-12")
        fill("扣款日期","2024-03-31")
        compose.onNode(hasText("到期日期") and hasSetTextAction()).assertTextContains("2024-04-12")
        click("恢复跟随周期")
        compose.onNode(hasText("到期日期") and hasSetTextAction()).assertTextContains("2024-05-31")
        click("保存订阅")
        val saved=awaitLedger { it.plans.size==1 }
        assertEquals("2024-03-31",saved.benefits.single().anchor)
        assertEquals(1,saved.benefits.single().renewals)
        assertEquals("2024-05-31",Book.expiry(saved.benefits.single(),saved.plans.single()).toString())
    }

    @Test fun editingBillingCycleMovesAutomaticBenefitsAndPreservesCustomExpiry() {
        val plan=Plan(name="混合权益测试",amount="20",billingAnchor="2024-01-31",paidCycles=2)
        val automatic=Benefit(planId=plan.id,name="跟随权益",anchor=plan.billingAnchor,renewals=2,giftDays=7)
        val custom=Benefit(planId=plan.id,name="独立权益",anchor="2024-02-10",renewals=1,giftDays=2)
        runBlocking { app.repository.update { Ledger(plans=listOf(plan),benefits=listOf(automatic,custom)) } }
        compose.waitForIdle()
        compose.onNodeWithText("订阅",substring=false).performClick()
        click(plan.name)
        click("编辑订阅与到期日")
        fill("扣款日期","2024-02-29")
        fill("周期","2")
        click("包含的权益")
        val dates=compose.onAllNodes(hasText("到期日期") and hasSetTextAction())
        dates[0].assertTextContains("2024-07-06")
        dates[1].assertTextContains("2024-03-12")
        click("保存订阅")
        val saved=awaitLedger { it.plans.singleOrNull()?.interval==2 }
        assertEquals("2024-07-06",Book.expiry(saved.benefits.first(),saved.plans.single()).toString())
        assertEquals(7,saved.benefits.first().giftDays)
        assertEquals("2024-03-12",Book.expiry(saved.benefits.last(),saved.plans.single()).toString())
        assertEquals(2,saved.benefits.last().giftDays)
        assertTrue(saved.payments.isEmpty())
        click(plan.name)
        click("编辑订阅与到期日")
        fill("扣款日期","2024-03-31")
        click("包含的权益")
        compose.onAllNodes(hasText("到期日期") and hasSetTextAction())[0].assertTextContains("2024-08-07")
        compose.onAllNodes(hasText("到期日期") and hasSetTextAction())[1].assertTextContains("2024-03-12")
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
    private fun awaitNode(matcher:SemanticsMatcher,diagnosticName:String) {
        try {
            compose.waitUntil(10_000) {
                runCatching {compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)
            }
        } catch(e:Throwable) {
            val ui=runCatching {
                val roots=compose.onAllNodes(isRoot(),useUnmergedTree=true)
                roots.fetchSemanticsNodes().indices.joinToString("\n") {roots[it].printToString()}
            }.getOrElse {"No semantics: $it"}
            java.io.File(app.filesDir,"$diagnosticName.txt").writeText(ui)
            runCatching {
                val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(app.filesDir,"$diagnosticName.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            throw AssertionError("Timed out waiting for $matcher; diagnostic=$diagnosticName\n$ui",e)
        }
    }

    private fun recreateDetails(diagnosticName:String) {
        compose.activityRule.scenario.recreate()
        // The pinned back button is available even when the detail restores a lower scroll position.
        awaitNode(hasText("返回",substring=false) and hasClickAction(),diagnosticName)
        awaitNode(hasText("记录付款 / 提前续费",substring=false),diagnosticName)
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("记录付款 / 提前续费",substring=false)))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {it(0f,-100000f)}
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.activityRule.scenario.onActivity {activity->
            activity.currentFocus?.clearFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
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
        recreateDetails("foreign-after-recreate")
        click("返回")
        compose.onNodeWithText("账本",substring=false).performClick()
        assertTrue(compose.onAllNodesWithText("¥288.40",substring=false).fetchSemanticsNodes().isNotEmpty())
        screenshot("frozen-receipts")
    }

    @Test fun legacyPaymentCanBeCompletedOnceAndRestoredWithoutRateRecalculation() {
        val payment=Payment(planId="deleted",planName="Netflix",amount="15",currency="USD",date=java.time.LocalDate.now().toString())
        runBlocking { app.repository.update { Ledger(payments=listOf(payment),settings=Settings(rates=mapOf("USD" to "7"))) } }
        compose.waitForIdle()
        compose.onNodeWithText("账本",substring=false).performClick()
        click("补录人民币")
        compose.onNode(hasText("人民币实付金额") and hasSetTextAction()).performTextReplacement("108.50")
        compose.onNodeWithText("保存金额").performScrollTo().performClick()
        val completed=awaitLedger { it.payments.singleOrNull()?.cnyAmount=="108.50" }
        val restored=Book.decode(Book.encode(completed))
        runBlocking { app.repository.restore(restored) }
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("¥108.50",substring=false).fetchSemanticsNodes().isNotEmpty())
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
        compose.onNodeWithText("¥25.00",substring=false).assertExists()
        compose.onNodeWithText("CNY 25",substring=false).assertDoesNotExist()
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

    @Test fun historicalReceiptEntryDoesNotChangeBenefitOrBillingSchedule() {
        val today=java.time.LocalDate.now()
        val plan=Plan(id="history-plan",name="补记测试",amount="30",billingAnchor=today.minusMonths(2).toString(),paidCycles=3)
        val benefit=Benefit(id="history-benefit",planId=plan.id,name="已有权益",anchor=today.plusMonths(1).toString(),giftDays=3)
        runBlocking {app.repository.update {Ledger(plans=listOf(plan),benefits=listOf(benefit))}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick();click(plan.name)
        click("仅补记付款")
        compose.onNodeWithText("仅记账").assertIsSelected()
        compose.onNodeWithText("只补记付款，不改变到期日和下次扣款。").assertExists()
        fill("付款金额 ¥","19.50")
        fill("付款日期",today.minusMonths(1).toString())
        screenshot("subscription-record-only")
        compose.onNodeWithText("确认付款").performClick()
        val saved=awaitLedger {it.payments.size==1}
        assertEquals(listOf(plan),saved.plans);assertEquals(listOf(benefit),saved.benefits)
        assertEquals("19.50",saved.payments.single().amount)
        assertEquals(today.minusMonths(1).toString(),saved.payments.single().date)
        assertTrue(saved.payments.single().benefitIds.isEmpty())
    }

    @Test fun disablingAutomaticRenewalKeepsBenefitAndExpiryReminder() {
        val today=java.time.LocalDate.now()
        val plan=Plan(id="cancel-plan",name="关闭续费测试",amount="30",billingAnchor=today.toString())
        val benefit=Benefit(id="cancel-benefit",planId=plan.id,name="仍可使用的权益",anchor=today.plusDays(3).toString())
        runBlocking {app.repository.update {Ledger(plans=listOf(plan),benefits=listOf(benefit))}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick();click(plan.name)
        click("标记已关闭自动续费")
        val saved=awaitLedger {it.plans.singleOrNull()?.autoRenew==false}
        assertFalse(saved.plans.single().archived)
        assertEquals(listOf(benefit),saved.benefits)
        assertTrue(saved.payments.isEmpty())
        assertEquals(listOf(benefit),Book.due(saved,today))
        assertTrue(Book.forecast(saved,today,today.plusDays(30)).isEmpty())
        compose.onNodeWithText("已标记关闭自动续费 · 有效期提醒仍保留").assertExists()
        screenshot("subscription-renewal-disabled")
        click("返回");compose.onNodeWithText("到期",substring=false).performClick()
        compose.onNodeWithText(benefit.name,substring=false).assertExists()
    }

    @Test fun subscriptionSearchFiltersNamesAndCanBeCleared() {
        val today=java.time.LocalDate.now()
        val names=listOf("网易云音乐","QQ音乐","ChatGPT")
        val plans=names.mapIndexed {i,name->Plan(id="search-$i",name=name,amount="18",billingAnchor=today.toString())}
        val benefits=plans.map {Benefit(planId=it.id,name=it.name+"权益",anchor=today.plusMonths(1).toString())}
        runBlocking {app.repository.update {Ledger(plans=plans,benefits=benefits)}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick()
        fill("搜索订阅","音乐")
        compose.onNodeWithText("网易云音乐",substring=false).assertExists()
        compose.onNodeWithText("QQ音乐",substring=false).assertExists()
        compose.onNodeWithText("ChatGPT",substring=false).assertDoesNotExist()
        fill("搜索订阅","QQ")
        compose.onNodeWithText("QQ音乐",substring=false).assertExists()
        compose.onNodeWithText("网易云音乐",substring=false).assertDoesNotExist()
        screenshot("subscription-search")
        fill("搜索订阅","")
        names.forEach {compose.onNodeWithText(it,substring=false).assertExists()}
        assertEquals(plans,runBlocking {app.repository.read()}.plans)
    }

    @Test fun paymentNotificationIntentOpensTheTargetSubscriptionAndConfirmation() {
        val today=java.time.LocalDate.now()
        val first=Plan(id="intent-other",name="其他订阅",amount="10",billingAnchor=today.toString())
        val target=Plan(id="intent-target",name="通知目标订阅",amount="45",billingAnchor=today.toString())
        val benefits=listOf(first,target).map {Benefit(planId=it.id,name=it.name+"权益",anchor=today.plusMonths(1).toString())}
        runBlocking {app.repository.update {Ledger(plans=listOf(first,target),benefits=benefits)}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick();click(first.name)
        click("仅补记付款")
        fill("付款金额 ¥","17.50")
        fill("付款备注","A的未保存草稿")
        compose.onNodeWithText("仅记账").assertIsSelected()
        compose.activityRule.scenario.onActivity {activity->
            // ActivityScenario matches lifecycle callbacks by the launch Intent's identity fields.
            // Preserve those fields so setIntent in onNewIntent does not detach its lifecycle observer.
            activity.startActivity(android.content.Intent(activity.intent)
                .setFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("planId",target.id).putExtra("subscriptionAction","pay"))
        }
        compose.waitForIdle()
        compose.onNodeWithText("记录实际付款").assertExists()
        compose.onNode(hasText("付款金额 ¥") and hasSetTextAction()).assertTextContains("45")
        compose.onNodeWithText("续费",substring=false).assertIsSelected()
        compose.onNodeWithText("A的未保存草稿",substring=false).assertDoesNotExist()
        compose.onNode(hasText(target.name+"权益",substring=false) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText(first.name+"权益",substring=false) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        assertTrue(runBlocking {app.repository.read()}.payments.isEmpty())
        screenshot("subscription-notification-payment")
        compose.onNodeWithText("确认付款").performClick()
        val saved=awaitLedger {it.payments.size==1}
        assertEquals(target.id,saved.payments.single().planId)
        assertEquals("45",saved.payments.single().amount)
        assertEquals(first,saved.plans.single {it.id==first.id})
        assertEquals(target.paidCycles+1,saved.plans.single {it.id==target.id}.paidCycles)
        assertEquals(benefits.first(),saved.benefits.single {it.planId==first.id})
        recreateDetails("notification-after-recreate")
        compose.onNodeWithText("记录实际付款").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(target.name,substring=false).assertExists()
        assertEquals(saved.payments,runBlocking {app.repository.read()}.payments)
    }

    @Test fun deselectingExpiredJointBenefitClearsRestartAndOnlyExtendsActiveBenefit() {
        val today=java.time.LocalDate.now()
        val plan=Plan(id="joint-plan",name="联合权益续费",amount="30",billingAnchor=today.minusMonths(2).toString(),paidCycles=2)
        val expired=Benefit(id="expired-right",planId=plan.id,name="已过期权益",anchor=today.minusMonths(2).toString(),renewals=1)
        val active=Benefit(id="active-right",planId=plan.id,name="有效权益",anchor=today.toString(),renewals=2,giftDays=5)
        runBlocking {app.repository.update {Ledger(plans=listOf(plan),benefits=listOf(expired,active))}}
        compose.waitForIdle();compose.onNodeWithText("订阅",substring=false).performClick();click(plan.name)
        click("记录付款 / 提前续费")
        compose.onNodeWithText("从付款日重新开通",substring=false)
            .performScrollTo().performClick().assertIsSelected()
        // This fixture lists the expired benefit first; verify that only its selection is removed below.
        val benefitChoices=compose.onAllNodes(isToggleable() and hasAnyAncestor(isDialog()))
        benefitChoices.assertCountEquals(2)
        benefitChoices[0].performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("从付款日重新开通",substring=false).assertDoesNotExist()
        val expected=today.plusMonths(3).plusDays(5)
        compose.onNodeWithText("${active.name} → $expected",substring=false).assertExists()
        screenshot("subscription-joint-renewal")
        compose.onNodeWithText("确认付款").performClick()
        val saved=awaitLedger {it.payments.size==1}
        assertEquals(expired,saved.benefits.single {it.id==expired.id})
        assertEquals(expected,Book.expiry(saved.benefits.single {it.id==active.id},saved.plans.single()))
        assertEquals(5,saved.benefits.single {it.id==active.id}.giftDays)
        assertEquals(plan.billingAnchor,saved.plans.single().billingAnchor)
        assertEquals(plan.paidCycles+1,saved.plans.single().paidCycles)
        assertEquals(listOf(active.id),saved.payments.single().benefitIds)
    }

}
