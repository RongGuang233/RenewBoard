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
    @Test fun monthlyChargesAreIncludedInForecastButNotPastUnknownBills() {
        val p=plan();val l=Ledger(plans=listOf(p));Book.validate(l)
        money("100",Prepaid.balance(p,d("2024-01-30")))
        money("70",Prepaid.balance(p,d("2024-01-31")))
        money("40",Prepaid.balance(p,d("2024-02-29")))
        money("10",Prepaid.balance(p,d("2024-03-31")))
        assertEquals(d("2024-04-30"),Prepaid.rechargeDate(p))
        assertEquals(d("2024-03-31"),Prepaid.nextDeduction(p,d("2024-02-29")))
        money("0",Book.paidCny(l)!!)
        money("360",Book.forecast(l,d("2024-01-01"),d("2025-01-01"))["CNY"]!!)
        money("60",Book.forecastCny(l,d("2024-01-31"),d("2024-03-01"))!!)
    }
    @Test fun topupsIncreaseBalanceWhileOnlyMonthlyFeesCountAsSpending() {
        val first=Prepaid.topUp(Ledger(plans=listOf(plan())),"phone","50",d("2024-02-29"))
        money("90",Prepaid.balance(first.plans.single(),d("2024-02-29")))
        money("60",Prepaid.balance(first.plans.single(),d("2024-03-31")))
        val second=Prepaid.topUp(first,"phone","20",d("2024-02-29"))
        money("110",Prepaid.balance(second.plans.single(),d("2024-02-29")))
        money("60",Book.paidCny(second)!!)
        assertEquals(4,second.payments.size)
        assertEquals(2,Prepaid.expenses(second).size)
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
        money("75",Prepaid.balance(Prepaid.topUp(l,"phone","1",d("2024-02-29"),false).plans.single(),d("2024-03-01")))
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
    @Test fun monthlyFeesPersistOnceAndDeletingAReceiptDoesNotRecreateIt() {
        val initial=Ledger(plans=listOf(plan()))
        val first=Prepaid.accrue(initial,d("2024-03-31"))
        assertEquals(listOf("2024-01-31","2024-02-29","2024-03-31"),first.payments.map {it.date})
        money("90",Book.paidCny(first)!!)
        money("10",Prepaid.balance(first.plans.single(),d("2024-03-31")))
        assertEquals(first,Prepaid.accrue(first,d("2024-03-31")))
        val short=Prepaid.accrue(first,d("2024-04-30"))
        assertEquals(d("2024-04-30"),Prepaid.rechargeDate(short.plans.single()))
        val deleted=Book.deletePayments(first,setOf(first.payments.last().id))
        assertEquals(deleted,Prepaid.accrue(deleted,d("2024-04-01")))
        assertEquals(deleted,Book.decode(Book.encode(deleted)).data)
    }
    @Test fun changedMonthlyPriceOnlyAffectsLaterRecordedDeductions() {
        val first=Prepaid.accrue(Ledger(plans=listOf(plan())),d("2024-02-29"))
        val changed=first.copy(plans=first.plans.map {it.copy(amount="40")})
        val later=Prepaid.accrue(changed,d("2024-03-31"))
        assertEquals(listOf("30","30","40"),later.payments.map {it.amount})
        money("0",Prepaid.balance(later.plans.single(),d("2024-03-31")))
    }
    @Test fun knownBalanceStartsAccountingAfterItsDateAndArchivePausesFees() {
        val p=plan().copy(balanceAccount=BalanceAccount("100","2024-02-29"))
        val l=Ledger(plans=listOf(p))
        assertEquals(l,Prepaid.accrue(l,d("2024-03-01")))
        val stopped=Prepaid.archive(l,p.id,true,d("2024-03-31"))
        assertEquals(1,stopped.payments.size)
        assertEquals(stopped,Prepaid.accrue(stopped,d("2024-06-01")))
        val resumed=Prepaid.archive(stopped,p.id,false,d("2024-06-01"))
        val later=Prepaid.accrue(resumed,d("2024-06-30"))
        assertEquals(listOf("2024-03-31","2024-06-30"),later.payments.map {it.date})
        money("40",Prepaid.balance(later.plans.single(),d("2024-06-30")))
    }

    @Test fun historicalTopupsCanBeRecordedWithoutRecreditingAndDoNotRewindAccrual() {
        val advanced=Prepaid.accrue(Ledger(plans=listOf(plan())),d("2024-03-31"))
        val recorded=Prepaid.topUp(advanced,"phone","50",d("2024-02-10"),false)
        assertEquals(advanced.plans,recorded.plans)
        money("90",Book.paidCny(recorded)!!)
        val credited=Prepaid.topUp(advanced,"phone","50",d("2024-02-10"),true)
        money("60",Prepaid.balance(credited.plans.single(),d("2024-03-31")))
        assertEquals("2024-03-31",credited.plans.single().balanceAccount!!.asOf)
        assertEquals(credited,Prepaid.accrue(credited,d("2024-03-31")))
        assertEquals("2024-02-10",credited.payments.last().date)
    }
    @Test fun calibrationMayRecordOnlyExtraConsumptionAndKeepsMonthlyChargesOnce() {
        val l=Ledger(plans=listOf(plan()))
        val adjusted=Prepaid.calibrate(l,"phone","25",d("2024-02-29"))
        money("25",Prepaid.balance(adjusted.plans.single(),d("2024-02-29")))
        money("60",Book.paidCny(adjusted)!!)
        val expense=Prepaid.calibrate(l,"phone","25",d("2024-02-29"),true)
        money("75",Book.paidCny(expense)!!)
        assertEquals("话费额外扣费",expense.payments.last().note)
        assertEquals(expense,Prepaid.accrue(expense,d("2024-02-29")))
        assertThrows(Exception::class.java) {Prepaid.calibrate(adjusted,"phone","25",d("2024-02-29"),true)}
        assertThrows(Exception::class.java) {Prepaid.calibrate(adjusted,"phone","50",d("2024-02-29"),true)}
        assertThrows(Exception::class.java) {Prepaid.calibrate(adjusted,"phone","20",d("2024-02-10"))}
        assertEquals(expense,Book.decode(Book.encode(expense)).data)
    }

    @Test fun scheduledPriceSettlesOldAndNewMonthsExactlyOnce() {
        val initial=Ledger(plans=listOf(plan()))
        val scheduled=Prepaid.changeMonthlyFee(initial,"phone","40",d("2024-03-01"),d("2024-01-15"))
        assertEquals("30",scheduled.plans.single().amount)
        money("0",Prepaid.balance(scheduled.plans.single(),d("2024-03-31")))
        assertEquals(d("2024-04-30"),Prepaid.rechargeDate(scheduled.plans.single()))
        val advanced=Prepaid.accrue(scheduled,d("2024-03-31"))
        assertEquals(listOf("30","30","40"),advanced.payments.map {it.amount})
        assertEquals("40",advanced.plans.single().amount)
        assertNull(advanced.plans.single().balanceAccount!!.pendingFee)
        money("0",Prepaid.balance(advanced.plans.single(),d("2024-03-31")))
        assertEquals(advanced,Prepaid.accrue(advanced,d("2024-03-31")))
        assertEquals(scheduled,Book.decode(Book.encode(scheduled)).data)
    }
    @Test fun promotionBetweenDeductionsPreservesOldPriceBalanceAndZeroFeeTransition() {
        val scheduled=Prepaid.changeMonthlyFee(Ledger(plans=listOf(plan())),"phone","0",d("2024-03-15"),d("2024-01-15"))
        val advanced=Prepaid.accrue(scheduled,d("2024-03-16"))
        money("40",Prepaid.balance(advanced.plans.single(),d("2024-04-30")))
        assertEquals(listOf("30","30"),advanced.payments.map {it.amount})
        assertEquals("2024-03-16",advanced.plans.single().balanceAccount!!.asOf)
        assertNull(Prepaid.rechargeDate(advanced.plans.single()))
        val free=plan().copy(amount="0")
        val paid=Prepaid.changeMonthlyFee(Ledger(plans=listOf(free)),"phone","40",d("2024-03-01"),d("2024-01-15"))
        assertEquals(d("2024-05-31"),Prepaid.rechargeDate(paid.plans.single()))
    }
    @Test fun immediatePriceChangeKeepsTodaysExistingDeductionAndCanReplaceOrCancelFutureChange() {
        val initial=Ledger(plans=listOf(plan()))
        val immediate=Prepaid.changeMonthlyFee(initial,"phone","40",d("2024-01-31"),d("2024-01-31"))
        assertEquals(listOf("30"),immediate.payments.map {it.amount})
        money("30",Prepaid.balance(immediate.plans.single(),d("2024-02-29")))
        val future=Prepaid.changeMonthlyFee(immediate,"phone","50",d("2024-04-01"),d("2024-02-01"))
        val replaced=Prepaid.changeMonthlyFee(future,"phone","60",d("2024-05-01"),d("2024-02-01"))
        assertEquals(MonthlyFeeChange("60","2024-05-01"),replaced.plans.single().balanceAccount!!.pendingFee)
        val cancelled=Prepaid.cancelMonthlyFeeChange(replaced,"phone",d("2024-02-01"))
        assertNull(cancelled.plans.single().balanceAccount!!.pendingFee)
        assertEquals("40",cancelled.plans.single().amount)
        assertEquals(immediate.payments,cancelled.payments)
        assertThrows(Exception::class.java) {Prepaid.changeMonthlyFee(cancelled,"phone","20",d("2024-01-01"),d("2024-02-01"))}
    }
    @Test fun balanceOperationsPreserveScheduledFeeAndOldBackupWithoutFieldLoads() {
        val scheduled=Prepaid.changeMonthlyFee(Ledger(plans=listOf(plan())),"phone","40",d("2024-03-01"),d("2024-01-15"))
        val topped=Prepaid.topUp(scheduled,"phone","50",d("2024-01-20"))
        val calibrated=Prepaid.calibrate(topped,"phone","120",d("2024-01-21"))
        assertEquals(scheduled.plans.single().balanceAccount!!.pendingFee,calibrated.plans.single().balanceAccount!!.pendingFee)
        val archived=Prepaid.archive(calibrated,"phone",true,d("2024-01-22"))
        val promoted=Prepaid.accrue(archived,d("2024-03-01"))
        assertEquals("40",promoted.plans.single().amount)
        money("120",Prepaid.balance(promoted.plans.single(),d("2024-03-31")))
        val old=Book.encode(Ledger(plans=listOf(plan()))).replace(",\"pendingFee\":null","")
        assertNull(Book.decode(old).data.plans.single().balanceAccount!!.pendingFee)
    }

}
