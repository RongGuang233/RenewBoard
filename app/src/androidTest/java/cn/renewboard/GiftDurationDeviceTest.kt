package cn.renewboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class GiftDurationDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val plan=Plan(id="gift-plan",name="联合会员",amount="40",billingAnchor="2026-01-31")
    private val benefit=Benefit(id="gift-benefit",planId=plan.id,name="网易云音乐",anchor="2026-01-31",renewals=1,giftDays=2)
    private fun input(value:String)=compose.onNode(hasText("增加天数") and hasSetTextAction()).performTextReplacement(value)

    @Test fun invalidInputExplainsErrorAndValidInputPreviewsActualExpiry() {
        var ledger=Ledger(plans=listOf(plan),benefits=listOf(benefit))
        var saves=0
        compose.setContent {MaterialTheme {GiftDurationDialog(plan,benefit,{},save={saves++;ledger=it(ledger)})}}
        compose.onNodeWithText("网易云音乐",substring=false).assertIsDisplayed()
        compose.onNodeWithText("到期日：2026-03-02 → 2026-03-09").assertIsDisplayed()
        listOf("0","","abc","-1","1.5").forEach {value->
            input(value)
            compose.onNodeWithText("增加",substring=false).performClick()
            compose.onNodeWithText("请输入 1 至 36500 的整数天数").assertIsDisplayed()
            assertEquals(0,saves)
            assertEquals(2,ledger.benefits.single().giftDays)
        }
        input("30")
        compose.onNodeWithText("请输入 1 至 36500 的整数天数").assertDoesNotExist()
        compose.onNodeWithText("到期日：2026-03-02 → 2026-04-01").assertIsDisplayed()
        compose.onNodeWithText("增加",substring=false).performClick()
        compose.waitForIdle()
        assertEquals(1,saves)
        assertEquals(32,ledger.benefits.single().giftDays)
    }

    @Test fun cumulativeAndDateLimitsExplainWhyGiftCannotBeAdded() {
        var currentPlan by mutableStateOf(plan)
        var currentBenefit by mutableStateOf(benefit.copy(giftDays=36500))
        var saves=0
        compose.setContent {MaterialTheme {GiftDurationDialog(currentPlan,currentBenefit,{},save={saves++})}}
        input("1")
        compose.onNodeWithText("增加",substring=false).performClick()
        compose.onNodeWithText("累计赠送时长不能超过 36500 天").assertIsDisplayed()
        compose.runOnIdle {
            currentPlan=plan.copy(billingAnchor="2200-12-31")
            currentBenefit=benefit.copy(id="end-of-range",anchor="2200-12-31",renewals=0,giftDays=0)
        }
        input("1")
        compose.onNodeWithText("增加",substring=false).performClick()
        compose.onNodeWithText("到期日期不能晚于 2200 年").assertIsDisplayed()
        assertEquals(0,saves)
    }

    @Test fun failedSaveRetainsInputAndRetryWaitsWithoutDuplicateSubmission() {
        val other=benefit.copy(id="other-benefit",name="其他权益")
        var ledger=Ledger(plans=listOf(plan),benefits=listOf(benefit,other))
        var attempts=0
        val committed=CompletableDeferred<Unit>()
        compose.setContent {
            var closed by remember {mutableStateOf(false)}
            MaterialTheme {
                if(closed) Text("赠送已保存") else GiftDurationDialog(plan,benefit,{closed=true},save={transform->
                    attempts++
                    if(attempts==1) throw IOException("fixture write failure")
                    committed.await()
                    ledger=transform(ledger)
                })
            }
        }
        input("5")
        compose.onNodeWithText("增加",substring=false).performClick()
        compose.onNodeWithText("保存失败，请重试").assertIsDisplayed()
        compose.onNode(hasText("增加天数") and hasSetTextAction()).assertTextContains("5")
        assertEquals(2,ledger.benefits.first().giftDays)
        compose.onNodeWithText("增加",substring=false).performClick()
        compose.onNodeWithText("正在保存…").assertIsNotEnabled().performClick()
        compose.onNodeWithText("取消",substring=false).assertIsNotEnabled().performClick()
        compose.onNodeWithText("赠送已保存").assertDoesNotExist()
        assertEquals(2,attempts)
        assertEquals(2,ledger.benefits.first().giftDays)
        compose.runOnIdle {committed.complete(Unit)}
        compose.onNodeWithText("赠送已保存").assertExists()
        assertEquals(2,attempts)
        assertEquals(7,ledger.benefits.first().giftDays)
        assertEquals(other,ledger.benefits.last())
    }
}
