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
        compose.onNodeWithText(text).performScrollTo().performClick()
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
        fill("套餐价格（不分摊给权益）", "36.80")
        fill("原始扣款日期 YYYY-MM-DD", "2026-09-01")
        fill("权益 1 名称", "设备录入权益")
        fill("到期日期 YYYY-MM-DD", "2026-10-01")
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
        fill("套餐价格（不分摊给权益）", "48.00")
        fill("权益 1 名称", "已编辑权益")
        fill("到期日期 YYYY-MM-DD", "2026-11-15")
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
        fill("套餐价格（不分摊给权益）", "30.00")
        fill("原始扣款日期 YYYY-MM-DD", "2024-01-31")
        fill("权益 1 名称", "月末权益")
        fill("到期日期 YYYY-MM-DD", "2024-02-29")
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

    @Test fun invalidFormRemainsEditableAndDoesNotWriteAnyLedgerRecords() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        fill("订阅 / 套餐名称", "无效金额测试")
        fill("套餐价格（不分摊给权益）", "不是金额")
        click("保存订阅")
        compose.onNodeWithText("请检查名称、金额、周期和日期：", substring = true).assertExists()
        assertEquals(Ledger(), runBlocking { app.repository.read() })
        fill("套餐价格（不分摊给权益）", "10.00")
        click("保存订阅")
        val saved = awaitLedger { it.plans.size == 1 }
        assertEquals("10.00", saved.plans.single().amount)
        assertEquals(1, saved.payments.size)
    }
}
