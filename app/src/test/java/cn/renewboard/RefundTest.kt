package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class RefundTest {
    private fun d(value: String) = LocalDate.parse(value)
    private fun money(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
    private fun original(currency: String = "CNY", cny: String? = null): Ledger {
        val plan=Plan(id="p",name="会员",amount="100",currency=currency,billingAnchor="2026-01-01")
        return Ledger(plans=listOf(plan),benefits=listOf(Benefit(id="b",planId="p",name="会员",anchor="2026-01-01",renewals=3)),
            payments=listOf(Payment(id="paid",planId="p",planName="会员",amount="100",currency=currency,date="2026-01-01",cnyAmount=cny,benefitIds=listOf("b"))))
    }
    private fun rejects(block: () -> Unit) { try {block();fail("Expected invalid refund to be rejected")} catch(_:IllegalArgumentException) {} }

    @Test fun partialRefundsAreIndependentReceiptsAndReduceOnlyTheirOwnPeriod() {
        val before=original()
        val first=Book.recordRefund(before,"paid","20",d("2026-02-01"),note="部分退款")
        val after=Book.recordRefund(first,"paid","30",d("2026-03-01"))
        assertEquals(before.payments.single(),after.payments.first())
        assertEquals(before.plans,after.plans);assertEquals(before.benefits,after.benefits)
        assertEquals(listOf("paid","paid"),after.payments.drop(1).map {it.refundOf})
        assertTrue(after.payments.drop(1).all {it.benefitIds.isEmpty() && BigDecimal(it.amount).signum()>0})
        money("50",Book.refundable(after,"paid").amount)
        money("100",Book.paidCny(after,d("2026-01-01"),d("2026-02-01")))
        money("-20",Book.paidCny(after,d("2026-02-01"),d("2026-03-01")))
        money("50",Book.paid(after).getValue("CNY"))
        val buckets=LedgerStats.buckets(after.payments,TrendRange.THREE,d("2026-03-10"))
        listOf("100","-20","-30").zip(buckets).forEach { (expected,bucket)->money(expected,bucket.summary.known) }
        assertEquals(after,Book.decode(Book.encode(after)).data)
    }

    @Test fun foreignRefundsUseActualSettlementsAndNeverSetForecastRate() {
        val before=original("USD","700")
        val after=Book.recordRefund(before,"paid","40",d("2026-02-01"),"270")
        money("60",Book.refundable(after,"paid").amount)
        money("-270",Book.paidCny(after,d("2026-02-01"),d("2026-03-01")))
        money("700",Book.forecastCny(after,d("2026-03-01"),d("2026-04-01")))
        money("430",LedgerStats.summary(after.payments).known)
        rejects {Book.recordRefund(after,"paid","61",d("2026-02-02"),"100")}
        for(settled in listOf(null,"0","-1")) rejects {Book.recordRefund(before,"paid","20",d("2026-02-02"),settled)}
        rejects {Book.recordRefund(original("USD"),"paid","20",d("2026-02-02"),"140")}
    }

    @Test fun foreignFullRefundCanSettleAboveOriginalCnyWithoutRevaluingHistory() {
        val base=original("USD","140")
        val before=base.copy(payments=base.payments.map { it.copy(amount="20") })
        val after=Book.recordRefund(before,"paid","20",d("2026-02-01"),"146")
        money("0",Book.refundable(after,"paid").amount)
        assertEquals(before.payments.single(),after.payments.first())
        assertEquals("146",after.payments.last().cnyAmount)
        money("140",Book.paidCny(after,d("2026-01-01"),d("2026-02-01")))
        money("-146",Book.paidCny(after,d("2026-02-01"),d("2026-03-01")))
        money("-6",LedgerStats.summary(after.payments).known)
        val ratesChanged=after.copy(settings=Settings(rates=mapOf("USD" to "99")))
        money("-6",Book.paidCny(ratesChanged))
        assertEquals(after,Book.decode(Book.encode(after)).data)
        rejects {Book.recordRefund(after,"paid","0.01",d("2026-02-02"),"0.07")}
        rejects {Book.recordRefund(before,"paid","20.01",d("2026-02-01"),"146")}
    }

    @Test fun invalidAmountsDatesAndRefundChainsAreRejected() {
        val before=original()
        for(amount in listOf("0","-1","100.0001","bad")) rejects {Book.recordRefund(before,"paid",amount,d("2026-02-01"))}
        rejects {Book.recordRefund(before,"paid","10",d("2025-12-31"))}
        val after=Book.recordRefund(before,"paid","100",d("2026-02-01"))
        rejects {Book.recordRefund(after,"paid","1",d("2026-02-01"))}
        rejects {Book.recordRefund(after,after.payments.last().id,"1",d("2026-02-01"))}
        rejects {Book.validate(after.copy(payments=after.payments.drop(1)))}
        rejects {Book.validate(after.copy(payments=listOf(after.payments.first(),after.payments.last().copy(planId="other"))))}
    }

    @Test fun correctingEitherSidePreservesRefundLimitsAndChronology() {
        val before=Book.recordRefund(original("USD","700"),"paid","40",d("2026-02-01"),"280")
        rejects {Book.editPayment(before,"paid","39",d("2026-01-01"),"","700")}
        money("-1",Book.paidCny(Book.editPayment(before,"paid","100",d("2026-01-01"),"","279")))
        rejects {Book.editPayment(before,"paid","100",d("2026-02-02"),"","700")}
        rejects {Book.editPayment(before,before.payments.last().id,"101",d("2026-02-01"),"","280")}
        val after=Book.editPayment(before,before.payments.last().id,"30",d("2026-03-01"),"更正退款","210")
        money("490",Book.paidCny(after));money("70",Book.refundable(after,"paid").amount)
        assertEquals(before.benefits,after.benefits)
    }

    @Test fun deletingOriginalCascadesRefundsButDeletingRefundDoesNotDeleteOriginal() {
        val before=Book.recordRefund(Book.recordRefund(original(),"paid","20",d("2026-02-01")),"paid","30",d("2026-03-01"))
        val oneRefund=before.payments[1].id
        assertEquals(before.payments.map {it.id}.toSet(),Book.paymentDeletionIds(before,setOf("paid",oneRefund)))
        val oneDeleted=Book.deletePayments(before,setOf(oneRefund))
        assertEquals(listOf("paid",before.payments.last().id),oneDeleted.payments.map {it.id})
        money("70",Book.refundable(oneDeleted,"paid").amount)
        val allDeleted=Book.deletePayments(before,setOf("paid"))
        assertTrue(allDeleted.payments.isEmpty());assertEquals(before.benefits,allDeleted.benefits)
        assertEquals(before.payments,Book.delete(before,"p").payments)
        assertTrue(Book.delete(before,"p",true).payments.isEmpty())
        Book.validate(Book.delete(before,"p"))
    }

    @Test fun oldBackupDefaultsToOrdinaryPaymentAndRefundsAreNotDuplicateReceipts() {
        val before=original()
        val legacy=Book.encode(before).replace(",\"refundOf\":null","")
        assertEquals(before,Book.decode(legacy).data)
        val after=Book.recordRefund(before,"paid","100",d("2026-01-01"))
        assertEquals(listOf(before.payments.single()),Book.suspectedDuplicates(after,"p","100",d("2026-01-01")))
        val prepaid=before.copy(payments=before.payments.map {it.copy(note="话费充值")})
        rejects {Book.recordRefund(prepaid,"paid","20",d("2026-02-01"))}
    }
}
