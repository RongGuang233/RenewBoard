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
class SubscriptionDraftDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var app:RenewApp

    @Before fun prepare() {
        app=ApplicationProvider.getApplicationContext()
        check(app.packageName.endsWith(".debug")) {"Draft tests must only target the debug app"}
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        runBlocking {app.repository.update {Ledger()}}
        compose.waitForIdle()
        awaitNode(hasContentDescription("记一笔订阅"))
    }

    @After fun clean() {
        if(::app.isInitialized && app.packageName.endsWith(".debug")) {
            runBlocking {app.repository.update {Ledger()}}
            app.getSharedPreferences("form-drafts",0).edit().clear().commit()
        }
    }

    private fun awaitNode(matcher:SemanticsMatcher) {
        compose.waitUntil(10000) {compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    private fun field(label:String)=compose.onNode(hasText(label) and hasSetTextAction())
    private fun fill(label:String,value:String) {field(label).performScrollTo().performTextReplacement(value)}
    private fun clickForm(text:String) {compose.onNodeWithText(text,substring=false).performScrollTo().performClick()}
    private fun expiry(index:Int)=compose.onAllNodes(hasText("到期日期") and hasSetTextAction())[index]
    private fun benefits() {
        if(compose.onAllNodes(hasText("权益 1 名称") and hasSetTextAction()).fetchSemanticsNodes().isEmpty()) clickForm("包含的权益")
    }
    private fun notes() {
        if(compose.onAllNodes(hasText("备注") and hasSetTextAction()).fetchSemanticsNodes().isEmpty()) clickForm("更多选项")
    }
    private fun add() {
        compose.onNodeWithContentDescription("记一笔订阅").performClick()
        awaitNode(hasContentDescription("返回"))
    }
    private fun custom() {
        add()
        compose.onNodeWithText("自定义订阅").performClick()
        awaitNode(hasText("订阅 / 套餐名称") and hasSetTextAction())
    }
    private fun back() {compose.onNodeWithContentDescription("返回").performClick()}
    private fun assertEmptyLedger() {
        val l=runBlocking {app.repository.read()}
        assertTrue(l.plans.isEmpty());assertTrue(l.benefits.isEmpty());assertTrue(l.payments.isEmpty())
    }

    @Test fun completeJointSubscriptionDraftSurvivesExitAndRecreateThenSavesAccurately() {
        custom()
        val anchor=LocalDate.now().minusDays(21)
        val manualExpiry=anchor.plusDays(45)
        fill("订阅 / 套餐名称","双权益草稿套餐")
        fill("套餐价格","15.50")
        compose.onNodeWithContentDescription("选择币种").performScrollTo().performClick()
        compose.onNode(hasText("美元 $") and hasAnyAncestor(isPopup())).performClick()
        fill("周期","2")
        compose.onNodeWithContentDescription("选择周期单位").performScrollTo().performClick()
        compose.onNode(hasText("周",substring=false) and hasAnyAncestor(isPopup())).performClick()
        fill("扣款日期",anchor.toString())
        benefits()
        fill("权益 1 名称","手动到期权益")
        expiry(0).performScrollTo().performTextReplacement(manualExpiry.toString())
        clickForm("＋ 添加联合权益")
        fill("权益 2 名称","跟随周期权益")
        expiry(1).performScrollTo().assertTextContains(anchor.plusWeeks(2).toString())
        fill("人民币实付金额","112.25")
        notes();fill("备注","联合套餐优惠，保留手动到期日")
        back()
        compose.onNodeWithText("保留草稿",substring=false).performClick()
        awaitNode(hasContentDescription("记一笔订阅"))
        assertEmptyLedger()
        assertNotNull(DraftStore(app).read("subscription:new"))

        add()
        awaitNode(hasText("订阅 / 套餐名称") and hasSetTextAction())
        compose.onNodeWithText("已恢复草稿").assertExists()
        field("订阅 / 套餐名称").assertTextContains("双权益草稿套餐")
        field("周期").performScrollTo().assertTextContains("2")
        compose.onNodeWithContentDescription("选择周期单位").assertTextContains("周")
        compose.onNodeWithContentDescription("选择币种").assertTextContains("USD")
        benefits()
        expiry(0).performScrollTo().assertTextContains(manualExpiry.toString())
        expiry(1).performScrollTo().assertTextContains(anchor.plusWeeks(2).toString())
        notes();field("备注").performScrollTo().assertTextContains("联合套餐优惠，保留手动到期日")

        compose.activityRule.scenario.recreate()
        awaitNode(hasContentDescription("返回"))
        awaitNode(hasText("订阅 / 套餐名称") and hasSetTextAction())
        field("套餐价格").performScrollTo().assertTextContains("15.50")
        field("人民币实付金额").performScrollTo().assertTextContains("112.25")
        // Both the manual date and the relationship that follows the cycle must survive.
        val revisedAnchor=anchor.plusDays(1)
        fill("扣款日期",revisedAnchor.toString())
        benefits()
        field("权益 1 名称").performScrollTo().assertTextContains("手动到期权益")
        field("权益 2 名称").performScrollTo().assertTextContains("跟随周期权益")
        expiry(0).performScrollTo().assertTextContains(manualExpiry.toString())
        expiry(1).performScrollTo().assertTextContains(revisedAnchor.plusWeeks(2).toString())
        compose.onNodeWithText("保存订阅").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(10000) {runBlocking {app.repository.read()}.payments.size==1}
        awaitNode(hasContentDescription("记一笔订阅"))
        val saved=runBlocking {app.repository.read()}
        val plan=saved.plans.single()
        assertEquals("双权益草稿套餐",plan.name)
        assertEquals("USD",plan.currency)
        assertEquals("15.50",plan.amount)
        assertEquals(Cycle.WEEK,plan.cycle)
        assertEquals(2,plan.interval)
        assertEquals(revisedAnchor.toString(),plan.billingAnchor)
        assertEquals("联合套餐优惠，保留手动到期日",plan.note)
        assertTrue(plan.autoRenew)
        assertEquals(2,saved.benefits.size)
        assertEquals(manualExpiry,Book.expiry(saved.benefits.single {it.name=="手动到期权益"},plan))
        assertEquals(revisedAnchor.plusWeeks(2),Book.expiry(saved.benefits.single {it.name=="跟随周期权益"},plan))
        val payment=saved.payments.single()
        assertEquals(plan.id,payment.planId)
        assertEquals("15.50",payment.amount)
        assertEquals("112.25",payment.cnyAmount)
        assertEquals("USD",payment.currency)
        assertEquals(revisedAnchor.toString(),payment.date)
        assertEquals(saved.benefits.map {it.id}.toSet(),payment.benefitIds.toSet())
        assertNull(DraftStore(app).read("subscription:new"))
    }

    @Test fun discardingDraftDoesNotCreateRecordsAndReopensAnEmptySelectionPage() {
        custom()
        fill("订阅 / 套餐名称","准备放弃的草稿")
        fill("套餐价格","28")
        notes();fill("备注","这份草稿不应落账")
        back()
        compose.onNodeWithText("保留草稿",substring=false).performClick()
        awaitNode(hasContentDescription("记一笔订阅"))
        add()
        awaitNode(hasText("已恢复草稿"))
        compose.onNodeWithText("放弃修改",substring=false).performScrollTo().performClick()
        compose.onNode(hasText("放弃修改") and hasAnyAncestor(isDialog())).performClick()
        awaitNode(hasContentDescription("记一笔订阅"))
        assertNull(DraftStore(app).read("subscription:new"))
        assertEmptyLedger()
        add()
        compose.onNodeWithText("选择会员",substring=false).assertExists()
        compose.onNodeWithText("已恢复草稿").assertDoesNotExist()
        assertEquals("",field("搜索会员").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("自定义订阅").performClick()
        assertEquals("",field("订阅 / 套餐名称").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("",field("套餐价格").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        fill("订阅 / 套餐名称","随后撤回的临时名字")
        compose.waitUntil(10000) {DraftStore(app).read("subscription:new")!=null}
        fill("订阅 / 套餐名称","")
        compose.waitUntil(10000) {DraftStore(app).read("subscription:new")==null}
        back()
        awaitNode(hasContentDescription("记一笔订阅"))
        compose.onNodeWithText("保留未完成的修改？").assertDoesNotExist()
        assertNull(DraftStore(app).read("subscription:new"))
        add()
        compose.onNodeWithText("选择会员",substring=false).assertExists()
        compose.onNodeWithText("已恢复草稿").assertDoesNotExist()
        assertEquals("",field("搜索会员").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertNull(DraftStore(app).read("subscription:new"))
        assertEmptyLedger()
    }
}
