package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class LedgerBreakdownTest {
    @Test fun expenseComponentsIncludeMonthlyFeesExcludeTopUpsAndUseActualForeignRefunds() {
        val original=Payment(id="original",planId="ai",planName="ChatGPT",amount="20",currency="USD",date="2026-09-01",cnyAmount="140")
        val refund=original.copy(id="refund",date="2026-09-02",cnyAmount="150",refundOf=original.id)
        val fee=Payment(planId="phone",planName="中国移动",amount="30",currency="CNY",date="2026-09-01",note="话费扣费")
        val unknown=original.copy(id="unknown",cnyAmount=null)
        val topUp=fee.copy(id="top-up",amount="200",note="话费充值")
        val ledger=Ledger(payments=listOf(original,refund,fee,unknown,topUp))
        Book.validate(ledger)
        val result=LedgerStats.breakdown(ledger.payments)
        assertEquals(0,result.payments.known.compareTo(BigDecimal("170")))
        assertEquals(3,result.payments.count)
        assertEquals(1,result.payments.missing)
        assertEquals(0,result.refunds.known.compareTo(BigDecimal("150")))
        assertEquals(1,result.refunds.count)
        assertEquals(0,result.refunds.missing)
        val net=LedgerStats.summary(Prepaid.expenses(ledger))
        assertEquals(0,(result.payments.known-result.refunds.known).compareTo(net.known))
        assertEquals(result.payments.missing+result.refunds.missing,net.missing)
    }

    @Test fun emptyAndRechargeOnlyLedgersHaveNoExpenseComponents() {
        val zero=CashSummary(BigDecimal.ZERO,0,0)
        assertEquals(CashBreakdown(zero,zero),LedgerStats.breakdown(emptyList()))
        val topUp=Payment(planId="phone",planName="中国移动",amount="100",currency="CNY",date="2026-09-01",note="话费充值")
        assertEquals(CashBreakdown(zero,zero),LedgerStats.breakdown(listOf(topUp)))
    }
}
