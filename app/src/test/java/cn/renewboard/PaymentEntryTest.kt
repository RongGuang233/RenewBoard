package cn.renewboard

import org.junit.Assert.*
import org.junit.Test

class PaymentEntryTest {
    private val plan=Plan(id="p",name="季度会员",amount="30.00",interval=3,billingAnchor="2026-01-01")
    @Test fun totalFollowsUntouchedPeriodCountAndKeepsManualDiscount() {
        val entry=PaymentDraft(plan.id,plan.amount,"2026-09-08",amountEdited=false)
        assertEquals("60.00",entry.withPeriods(plan,"2").amount)
        assertEquals("30.00",entry.withPeriods(plan,"2").withPeriods(plan,"1").amount)
        val discounted=entry.copy(amount="45",amountEdited=true)
        assertEquals("45",discounted.withPeriods(plan,"3").amount)
        assertEquals("6个月",renewalDuration(plan,"2"))
        assertEquals("4周",renewalDuration(plan.copy(cycle=Cycle.WEEK,interval=2),"2"))
        assertEquals("2年",renewalDuration(plan.copy(cycle=Cycle.YEAR,interval=1),"2"))
    }
    @Test fun partialInputIsNotTreatedAsZeroAndLegacyDraftKeepsActualAmount() {
        val entry=PaymentDraft(plan.id,"30.00","2026-09-08",amountEdited=false)
        listOf("","0","121","2.5").forEach {assertEquals("30.00",entry.withPeriods(plan,it).amount)}
        val old="""{"planId":"p","amount":"45","date":"2026-09-08","periods":"2"}"""
        val restored=Book.json.decodeFromString<PaymentDraft>(old)
        assertTrue(restored.amountEdited)
        assertEquals("45",restored.withPeriods(plan,"3").amount)
    }
}
