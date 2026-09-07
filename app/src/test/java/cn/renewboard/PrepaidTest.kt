package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PrepaidTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun plan() = Plan(id="phone",name="中国移动",amount="30",billingAnchor="2024-01-31",paidCycles=0,
        balanceAccount=BalanceAccount("100","2024-01-01"))
    private fun money(value: String, actual: BigDecimal) = assertEquals(0,BigDecimal(value).compareTo(actual))
    @Test fun monthlyChargesEstimateBalanceWithoutRecordingPaymentsOrForecastCash() {
        val p=plan();val l=Ledger(plans=listOf(p));Book.validate(l)
        money("100",Prepaid.balance(p,d("2024-01-30")))
        money("70",Prepaid.balance(p,d("2024-01-31")))
        money("40",Prepaid.balance(p,d("2024-02-29")))
        money("10",Prepaid.balance(p,d("2024-03-31")))
        assertEquals(d("2024-04-30"),Prepaid.rechargeDate(p))
        assertEquals(d("2024-03-31"),Prepaid.nextDeduction(p,d("2024-02-29")))
        money("0",Book.paidCny(l)!!)
        assertTrue(Book.forecast(l,d("2024-01-01"),d("2025-01-01")).isEmpty())
    }
    @Test fun topupsIncreaseBalanceAndCountOnceAsCashWithoutChangingMonthEnd() {
        val first=Prepaid.topUp(Ledger(plans=listOf(plan())),"phone","50",d("2024-02-29"))
        money("90",Prepaid.balance(first.plans.single(),d("2024-02-29")))
        money("60",Prepaid.balance(first.plans.single(),d("2024-03-31")))
        val second=Prepaid.topUp(first,"phone","20",d("2024-02-29"))
        money("110",Prepaid.balance(second.plans.single(),d("2024-02-29")))
        money("70",Book.paidCny(second)!!)
        assertEquals(2,second.payments.size)
        assertEquals(plan().billingAnchor,second.plans.single().billingAnchor)
        assertEquals(second,Book.decode(Book.encode(second)).data)
        val removed=Book.delete(second,"phone")
        assertEquals(second.payments,removed.payments)
    }
    @Test fun insufficientZeroFeeAndReminderBoundariesAreExplicit() {
        val p=plan().copy(balanceAccount=BalanceAccount("30","2024-01-01"))
        assertEquals(d("2024-02-29"),Prepaid.rechargeDate(p))
        val l=Ledger(plans=listOf(p))
        assertEquals(listOf(p),Prepaid.due(l,d("2024-02-26")))
        assertTrue(Prepaid.due(l.copy(plans=listOf(p.copy(archived=true))),d("2024-02-26")).isEmpty())
        assertNull(Prepaid.rechargeDate(p.copy(amount="0")))
        assertEquals(d("2024-01-31"),Prepaid.rechargeDate(p.copy(balanceAccount=BalanceAccount("-10","2024-01-01"))))
    }
    @Test fun calibrationRebasesEstimateWithoutAddingCashAndInvalidTopupsAreRejected() {
        val p=plan().copy(amount="40",balanceAccount=BalanceAccount("75","2024-03-01"))
        money("35",Prepaid.balance(p,d("2024-03-31")))
        val l=Ledger(plans=listOf(p));assertTrue(l.payments.isEmpty())
        assertThrows(Exception::class.java) { Prepaid.topUp(l,"phone","-1",d("2024-03-02")) }
        assertThrows(Exception::class.java) { Prepaid.topUp(l,"phone","0",d("2024-03-02")) }
        assertThrows(Exception::class.java) { Prepaid.topUp(l,"phone","1",d("2024-02-29")) }
        assertThrows(Exception::class.java) { Book.validate(l.copy(plans=listOf(p.copy(currency="USD")))) }
        assertThrows(Exception::class.java) { Book.validate(l.copy(plans=listOf(p.copy(balanceAccount=BalanceAccount("75",LocalDate.now().plusDays(1).toString()))))) }
        assertThrows(Exception::class.java) { Book.renew(l,"phone","40",d("2024-03-02"),emptySet(),"") }
    }
    @Test fun oldBackupsRemainOrdinarySubscriptions() {
        val p=plan().copy(balanceAccount=null)
        val old=Book.encode(Ledger(plans=listOf(p),benefits=listOf(Benefit(planId=p.id,name=p.name,anchor="2024-02-29"))))
            .replace(",\"balanceAccount\":null","")
        assertNull(Book.decode(old).data.plans.single().balanceAccount)
    }
}
