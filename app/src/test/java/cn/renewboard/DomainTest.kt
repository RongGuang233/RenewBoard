package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DomainTest {
    private fun date(value: String) = LocalDate.parse(value)
    private fun plan(cycle: Cycle = Cycle.MONTH, interval: Int = 1) = Plan(
        id = "p", name = "套餐", amount = "30.00", billingAnchor = "2024-01-31",
        cycle = cycle, interval = interval
    )
    private fun benefit(id: String = "b", anchor: String = "2024-02-29") =
        Benefit(id = id, planId = "p", name = "权益 $id", anchor = anchor)
    private fun ledger(p: Plan = plan(), b: Benefit = benefit()) = Ledger(plans = listOf(p), benefits = listOf(b))
    private fun money(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java) { block() } }

    @Test fun monthlyRenewalsPreserveTheOriginalMonthEnd() {
        val p = plan()
        val b = benefit(anchor = "2024-01-31")
        assertEquals(date("2024-02-29"), Book.expiry(b.copy(renewals = 1), p))
        assertEquals(date("2024-03-31"), Book.expiry(b.copy(renewals = 2), p))
        assertEquals(date("2025-01-31"), Book.expiry(b.copy(renewals = 12), p))
        assertEquals(date("2024-03-31"), Book.nextCharge(p, date("2024-03-01")))
        val first = Book.renew(ledger(p, b), "p", "30", date("2024-01-31"), setOf("b"), "")
        val second = Book.renew(first, "p", "30", date("2024-02-29"), setOf("b"), "")
        assertEquals(date("2024-03-31"), Book.expiry(second.benefits.single(), second.plans.single()))
    }

    @Test fun integerWeekAndMonthIntervalsAdvanceByWholeCycles() {
        assertEquals(date("2024-03-13"), Book.expiry(benefit(anchor = "2024-01-31").copy(renewals = 3), plan(Cycle.WEEK, 2)))
        assertEquals(date("2024-07-31"), Book.expiry(benefit(anchor = "2024-01-31").copy(renewals = 2), plan(Cycle.MONTH, 3)))
    }

    @Test fun leapYearAnchorReturnsToLeapDayWithoutDrifting() {
        val p = plan(Cycle.YEAR)
        val b = benefit(anchor = "2024-02-29")
        assertEquals(date("2025-02-28"), Book.expiry(b.copy(renewals = 1), p))
        assertEquals(date("2028-02-29"), Book.expiry(b.copy(renewals = 4), p))
        assertEquals(date("2032-02-29"), Book.expiry(b.copy(renewals = 2), p.copy(interval = 4)))
    }

    @Test fun earlyRenewalAddsToExistingExpiryAndPreservesGiftDays() {
        val original = ledger(b = benefit(anchor = "2024-01-31").copy(renewals = 1, giftDays = 5))
        val renewed = Book.renew(original, "p", "29.50", date("2024-02-10"), setOf("b"), "提前续费")
        assertEquals(date("2024-04-05"), Book.expiry(renewed.benefits.single(), renewed.plans.single()))
        assertEquals(5, renewed.benefits.single().giftDays)
        assertEquals(date("2024-03-31"), Book.nextCharge(renewed.plans.single(), date("2024-02-10")))
        assertEquals("2024-02-10", renewed.payments.single().date)
        money("29.50", Book.paid(renewed)["CNY"])
        assertEquals(1, original.benefits.single().renewals)
    }

    @Test fun onePackagePaymentRenewsMultipleBenefitsWithDifferentExpiries() {
        val original = ledger().copy(benefits = listOf(benefit("b1", "2024-02-29"), benefit("b2", "2024-03-15"), benefit("b3", "2024-04-01")))
        val renewed = Book.renew(original, "p", "30.00", date("2024-02-01"), setOf("b1", "b2"), "")
        assertEquals(1, renewed.payments.size)
        assertEquals(setOf("b1", "b2"), renewed.payments.single().benefitIds.toSet())
        assertEquals(listOf("2024-03-29", "2024-04-15", "2024-04-01"), renewed.benefits.map { Book.expiry(it, renewed.plans.single()).toString() })
        money("30.00", Book.paid(renewed)["CNY"])
    }

    @Test fun expiredBenefitRestartsFromRenewalDay() {
        val renewed = Book.renew(ledger(), "p", "30", date("2024-04-10"), setOf("b"), "")
        assertEquals(date("2024-05-10"), Book.expiry(renewed.benefits.single(), renewed.plans.single()))
    }

    @Test fun renewalRequiresBenefitsFromTheSelectedPlan() {
        rejects { Book.renew(ledger(), "p", "30", date("2024-02-01"), emptySet(), "") }
        rejects { Book.renew(ledger(), "p", "30", date("2024-02-01"), setOf("unknown"), "") }
    }

    @Test fun actualAndForecastTotalsStaySeparateAndKeepTheirCurrencies() {
        val cny = plan().copy(billingAnchor = "2024-01-01")
        val usd = cny.copy(id = "usd", currency = "USD", amount = "5")
        val l = Ledger(plans = listOf(cny, usd), benefits = listOf(benefit(), benefit("usd-benefit").copy(planId = "usd")), payments = listOf(
            Payment(planId = "p", planName = "套餐", amount = "20", currency = "CNY", date = "2024-01-10"),
            Payment(planId = "usd", planName = "套餐", amount = "4", currency = "USD", date = "2024-01-10")
        ))
        val actual = Book.paid(l)
        val forecast = Book.forecast(l, date("2024-02-01"), date("2024-04-01"))
        money("20", actual["CNY"]); money("4", actual["USD"])
        money("60", forecast["CNY"]); money("10", forecast["USD"])
        assertNull(Book.estimate(actual, emptyMap()))
        money("48", Book.estimate(actual, mapOf("USD" to "7")))
    }

    @Test fun reportRangesIncludeStartAndExcludeEnd() {
        val p = plan().copy(billingAnchor = "2024-01-01")
        val payments = listOf("2024-01-31", "2024-02-01", "2024-03-01").map {
            Payment(planId = p.id, planName = p.name, amount = "10", currency = "CNY", date = it)
        }
        val l = ledger(p).copy(payments = payments)
        money("10", Book.paid(l, date("2024-02-01"), date("2024-03-01"))["CNY"])
        money("30", Book.forecast(l, date("2024-02-01"), date("2024-03-01"))["CNY"])
    }

    @Test fun cnyForecastCombinesCurrenciesAndScalesChangedPricesAcrossOccurrences() {
        val cny = plan().copy(billingAnchor = "2024-01-01")
        val usd = cny.copy(id = "usd", currency = "USD", amount = "30")
        val payment = Payment(planId = "usd", planName = "USD", amount = "20", currency = "USD", date = "2024-01-01", cnyAmount = "140")
        val l = Ledger(plans = listOf(cny, usd), payments = listOf(payment), settings = Settings(rates = mapOf("USD" to "99")))
        money("480", Book.forecastCny(l, date("2024-02-01"), date("2024-04-01")))
        money("140", Book.paidCny(l))
        assertEquals(payment, l.payments.single())
    }

    @Test fun cnyForecastIsUnknownWhenAnActiveForeignPlanHasNoUsableSettlement() {
        val usd = plan().copy(currency = "USD", billingAnchor = "2024-01-01")
        val payment = Payment(planId = "p", planName = "USD", amount = "20", currency = "USD", date = "2024-01-01", cnyAmount = "140")
        listOf(emptyList(), listOf(payment.copy(cnyAmount = null)), listOf(payment.copy(amount = "0")),
            listOf(payment.copy(planId = "other")), listOf(payment.copy(currency = "EUR"))).forEach { payments ->
            assertNull(Book.forecastCny(Ledger(plans = listOf(usd), payments = payments,
                settings = Settings(rates = mapOf("USD" to "7"))), date("2024-02-01"), date("2024-03-01")))
        }
    }

    @Test fun cnyForecastIgnoresPlansWithoutChargesAndFreeForeignPlansNeedNoRatio() {
        val usd = plan().copy(currency = "USD", billingAnchor = "2024-01-01")
        val l = Ledger(plans = listOf(usd.copy(archived = true), usd.copy(id = "manual", autoRenew = false),
            usd.copy(id = "later", paidCycles = 12), usd.copy(id = "free", amount = "0")))
        money("0", Book.forecastCny(l, date("2024-02-01"), date("2024-03-01")))
        money("0", Book.forecastCny(Ledger(), date("2024-02-01"), date("2024-03-01")))
        money("0", Book.forecastCny(Ledger(plans = listOf(usd)), date("2024-02-01"), date("2024-02-01")))
        money("30", Book.forecastCny(l.copy(plans = l.plans + usd.copy(id = "cny", currency = "CNY")),
            date("2024-02-01"), date("2024-03-01")))
    }

    @Test fun cnyForecastUsesLatestEligibleDateAndLastEntryOnTheSameDay() {
        val usd = plan().copy(currency = "USD", amount = "20", billingAnchor = "2024-01-01")
        val payment = Payment(planId = "p", planName = "USD", amount = "20", currency = "USD", date = "2024-02-01", cnyAmount = "140")
        val l = Ledger(plans = listOf(usd), payments = listOf(payment,
            payment.copy(id = "same-day-last", cnyAmount = "144"),
            payment.copy(id = "older", date = "2024-01-01", cnyAmount = "130"),
            payment.copy(id = "future", date = "2024-02-02", cnyAmount = "160")))
        money("144", Book.forecastCny(l, date("2024-02-01"), date("2024-03-01")))
        assertNull(Book.forecastCny(l.copy(payments = listOf(l.payments.last())),
            date("2024-02-01"), date("2024-03-01")))
    }

    @Test fun settledForeignPaymentsDoNotChangeWhenRatesOrPlanChange() {
        val original = ledger(plan().copy(currency = "USD"))
        val first = Book.renew(original, "p", "20", date("2024-02-01"), setOf("b"), "", cnyAmount = "144.25")
        val second = Book.renew(first, "p", "20", date("2024-03-01"), setOf("b"), "", cnyAmount = "145.60")
        money("289.85", Book.paidCny(second))
        val changed = second.copy(
            plans = second.plans.map { it.copy(amount = "50", currency = "EUR") },
            settings = Settings(rates = mapOf("USD" to "9", "EUR" to "10"))
        )
        money("289.85", Book.paidCny(changed))
        money("144.25", Book.paidCny(changed, date("2024-02-01"), date("2024-03-01")))
        money("40", Book.paid(changed)["USD"])
    }

    @Test fun legacyForeignPaymentsRemainUnknownEvenWithExchangeRates() {
        val l = ledger().copy(payments = listOf(
            Payment(planId = "p", planName = "旧外币付款", amount = "10", currency = "USD", date = "2024-01-31"),
            Payment(planId = "p", planName = "人民币付款", amount = "20", currency = "CNY", date = "2024-02-01")
        ), settings = Settings(rates = mapOf("USD" to "7")))
        Book.validate(l)
        assertNull(Book.paidCny(l))
        money("20", Book.paidCny(l, date("2024-02-01"), date("2024-03-01")))
        money("0", Book.paidCny(l, date("2025-01-01")))
    }

    @Test fun backupPreservesFrozenAmountsAndReadsOldVersionOneReceipts() {
        val l = ledger(plan().copy(currency = "USD"))
        val renewed = Book.renew(l, "p", "20", date("2024-02-01"), setOf("b"), "", cnyAmount = "144.25")
        val restored = Book.decode(Book.encode(renewed)).data
        assertEquals(renewed, restored)
        money("144.25", Book.paidCny(restored))
        val oldBackup = Book.encode(renewed).replace(",\"cnyAmount\":\"144.25\"", "")
        assertFalse(oldBackup.contains("cnyAmount"))
        val legacy = Book.decode(oldBackup).data
        assertNull(legacy.payments.single().cnyAmount)
        assertNull(Book.paidCny(legacy))
        rejects { Book.decode(oldBackup.replace("\"benefitIds\":[\"b\"]", "\"unused\":[]")) }
    }

    @Test fun foreignRenewalRequiresExplicitValidSettlementAmount() {
        val l = ledger(plan().copy(currency = "USD"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            Book.renew(l, "p", "20", date("2024-02-01"), setOf("b"), "")
        }
        assertEquals("请填写付款当天的实际人民币金额", error.message)
        listOf("", " ", "-1", "1e2", "1.12345").forEach { amount ->
            rejects { Book.renew(l, "p", "20", date("2024-02-01"), setOf("b"), "", cnyAmount = amount) }
        }
        money("0", Book.paidCny(Book.renew(l, "p", "0", date("2024-02-01"), setOf("b"), "", cnyAmount = "0")))
        assertTrue(l.payments.isEmpty())
    }

    @Test fun cnyPaymentsAlwaysUseTheirOriginalAmount() {
        val renewed = Book.renew(ledger(), "p", "29.50", date("2024-02-01"), setOf("b"), "")
        money("29.50", Book.paidCny(renewed))
        assertNull(renewed.payments.single().cnyAmount)
        val redundantSettlement = renewed.copy(payments = renewed.payments.map { it.copy(cnyAmount = "100") })
        money("29.50", Book.paidCny(redundantSettlement))
        money("0", Book.paidCny(Ledger()))
    }

    @Test fun invalidFrozenAmountsAreRejectedDuringBackupValidation() {
        val payment = Payment(planId = "p", planName = "外币付款", amount = "20", currency = "USD", date = "2024-02-01", cnyAmount = "144.25")
        val l = ledger().copy(payments = listOf(payment))
        listOf("", "-1", "NaN", "1.12345").forEach { amount ->
            rejects { Book.encode(l.copy(payments = listOf(payment.copy(cnyAmount = amount)))) }
        }
        rejects { Book.decode(Book.encode(l).replace("\"cnyAmount\":\"144.25\"", "\"cnyAmount\":\"-1\"")) }
    }

    @Test fun archivedAndManualPlansHaveNoForecastAndArchiveSuppressesReminders() {
        val l = ledger(plan().copy(archived = true))
        assertTrue(Book.forecast(l, date("2024-01-01"), date("2025-01-01")).isEmpty())
        assertTrue(Book.due(l, date("2024-02-29")).isEmpty())
        assertTrue(Book.forecast(ledger(plan().copy(autoRenew = false)), date("2024-01-01"), date("2025-01-01")).isEmpty())
        assertEquals(listOf("b"), Book.due(ledger(), date("2024-02-26")).map { it.id })
    }

    @Test fun archiveAndDeletePreserveHistoricalPaymentsIncludingBackupRoundTrip() {
        val paid = Book.renew(ledger(), "p", "30", date("2024-02-01"), setOf("b"), "收据")
        val archived = paid.copy(plans = paid.plans.map { it.copy(archived = true) })
        assertEquals(paid.payments, archived.payments)
        val deleted = Book.delete(archived, "p")
        assertTrue(deleted.plans.isEmpty()); assertTrue(deleted.benefits.isEmpty())
        assertEquals(paid.payments, deleted.payments)
        assertEquals(deleted, Book.decode(Book.encode(deleted)).data)
    }

    @Test fun fullBackupRoundTripPreservesUnicodeAndSettings() {
        val l = ledger().copy(settings = Settings(listOf(7, 3, 0), mapOf("USD" to "7.1234")))
        assertEquals(l, Book.decode(Book.encode(l)).data)
        assertEquals(Ledger(), Book.decode(Book.encode(Ledger())).data)
    }

    @Test fun malformedUnknownVersionAndUnknownFieldBackupsAreRejected() {
        val valid = Book.encode(ledger())
        rejects { Book.decode("not json") }
        rejects { Book.decode("""{"data":{}}""") }
        rejects { Book.decode(valid.replace("\"version\":1,", "")) }
        rejects { Book.decode(valid.replace("\"version\":1", "\"version\":2")) }
        rejects { Book.decode(valid.dropLast(1) + ",\"unexpected\":true}") }
        rejects { Book.decode(valid.replace(Regex("\"createdAt\":\"[^\"]+\""), "\"createdAt\":\"yesterday\"")) }
        rejects { Book.decode(valid.replace("\"interval\":1", "\"interval\":0")) }
        rejects { Book.decode(valid.replace("\"amount\":\"30.00\"", "\"amount\":\"-1\"")) }
        rejects { Book.decode(" ".repeat(16 * 1024 * 1024 + 1)) }
    }

    @Test fun invalidBusinessRecordsAreRejectedBeforeExport() {
        listOf("-1", "1e2", "NaN", "1.12345").forEach { amount -> rejects { Book.encode(ledger(plan().copy(amount = amount))) } }
        listOf(0, -1, 121).forEach { interval -> rejects { Book.encode(ledger(plan().copy(interval = interval))) } }
        rejects { Book.encode(ledger().copy(benefits = emptyList())) }
        rejects { Book.encode(ledger().copy(benefits = listOf(benefit(), benefit()))) }
        rejects { Book.encode(ledger(b = benefit().copy(planId = "missing"))) }
        rejects { Book.encode(ledger().copy(settings = Settings(listOf(0, 0)))) }
        rejects { Book.encode(ledger().copy(settings = Settings(rates = mapOf("USD" to "0")))) }
    }
    @Test fun lateRenewalRestartsBillingAndExpiryTogether() {
        val p=plan().copy(billingAnchor="2024-01-01")
        val b=benefit(anchor="2024-01-01").copy(renewals=1)
        val renewed=Book.renew(ledger(p,b),p.id,"30",date("2024-02-10"),setOf(b.id),"重新开通")
        assertEquals("2024-02-10",renewed.plans.single().billingAnchor)
        assertEquals(1,renewed.plans.single().paidCycles)
        assertEquals(date("2024-03-10"),Book.expiry(renewed.benefits.single(),renewed.plans.single()))
        assertEquals(date("2024-03-10"),Book.nextCharge(renewed.plans.single(),date("2024-02-10")))
    }

    @Test fun payingAnOverdueOriginalCycleDoesNotSkipAnotherCycle() {
        val p=plan().copy(billingAnchor="2024-01-01")
        val b=benefit(anchor="2024-01-01").copy(renewals=1)
        val renewed=Book.renew(ledger(p,b),p.id,"30",date("2024-02-10"),setOf(b.id),"补交原周期",restart=false)
        assertEquals(p.billingAnchor,renewed.plans.single().billingAnchor)
        assertEquals(2,renewed.plans.single().paidCycles)
        assertEquals(date("2024-03-01"),Book.expiry(renewed.benefits.single(),renewed.plans.single()))
        assertEquals(date("2024-03-01"),Book.nextCharge(renewed.plans.single(),date("2024-02-10")))
    }

    @Test fun restartingOneJointBenefitLeavesOtherExpiryAndGiftIntact() {
        val p=plan().copy(billingAnchor="2024-01-01")
        val selected=benefit("first","2024-01-01").copy(renewals=1)
        val other=benefit("other","2024-01-01").copy(renewals=3,giftDays=5)
        val original=ledger(p,selected).copy(benefits=listOf(selected,other))
        val renewed=Book.renew(original,p.id,"30",date("2024-02-10"),setOf(selected.id),"重新开通")
        assertEquals(other,renewed.benefits.last())
        assertEquals(Book.expiry(other,p),Book.expiry(renewed.benefits.last(),renewed.plans.single()))
        assertEquals(date("2024-03-10"),Book.expiry(renewed.benefits.first(),renewed.plans.single()))
    }

    @Test fun recordingHistoricalPaymentLeavesScheduleAndBenefitsUnchanged() {
        val original=ledger()
        val recorded=Book.recordPayment(original,"p","15",date("2024-01-10"),"补记旧账")
        assertEquals(original.plans,recorded.plans)
        assertEquals(original.benefits,recorded.benefits)
        assertEquals("2024-01-10",recorded.payments.single().date)
        assertTrue(recorded.payments.single().benefitIds.isEmpty())
        money("15",Book.paidCny(recorded))
        assertTrue(original.payments.isEmpty())
    }

    @Test fun correctingForeignReceiptKeepsItsIdentityAndDoesNotRenew() {
        val original=Book.renew(ledger(plan().copy(currency="USD")),"p","20",date("2024-02-01"),setOf("b"),"",cnyAmount="1428")
        val receipt=original.payments.single()
        val corrected=Book.editPayment(original,receipt.id,"19",date("2024-01-31"),"更正手误","142.80")
        assertEquals(original.plans,corrected.plans)
        assertEquals(original.benefits,corrected.benefits)
        assertEquals(receipt.id,corrected.payments.single().id)
        assertEquals(receipt.benefitIds,corrected.payments.single().benefitIds)
        assertEquals("2024-01-31",corrected.payments.single().date)
        assertEquals("更正手误",corrected.payments.single().note)
        money("142.80",Book.paidCny(corrected.copy(settings=Settings(rates=mapOf("USD" to "99")))))
        rejects {Book.editPayment(original,receipt.id,"19",date("2024-01-31"),"",null)}
        rejects {Book.editPayment(original,receipt.id,"-1",date("2024-01-31"),"","142.80")}
    }

    @Test fun phoneReceiptCorrectionsKeepTypeAndBalanceWhileOrdinaryNotesCannotChangeType() {
        val p=plan().copy(balanceAccount=BalanceAccount("150","2024-01-01"))
        for(type in listOf("话费充值","话费扣费","话费额外扣费")) {
            val payment=Payment(planId=p.id,planName=p.name,amount="100",currency="CNY",date="2024-02-01",note=type)
            val original=Ledger(plans=listOf(p),payments=listOf(payment))
            val corrected=Book.editPayment(original,payment.id,"80",date("2024-02-02"),type)
            assertEquals(original.plans,corrected.plans)
            assertEquals(type,corrected.payments.single().note)
            rejects {Book.editPayment(original,payment.id,"80",date("2024-02-02"),"普通备注")}
            rejects {Book.recordPayment(ledger(),"p","10",date("2024-01-10"),type)}
        }
        val ordinary=Book.recordPayment(ledger(),"p","10",date("2024-01-10"))
        rejects {Book.editPayment(ordinary,ordinary.payments.single().id,"10",date("2024-01-10"),"话费充值")}
    }

    @Test fun forecastSummaryRetainsKnownMoneyAndNamesOnlyUnpricedActivePlans() {
        val cny=plan().copy(billingAnchor="2024-01-01")
        val usd=cny.copy(id="usd",currency="USD",amount="20")
        val unknown=usd.copy(id="unpriced")
        val l=Ledger(plans=listOf(cny,usd,unknown,unknown.copy(id="archived",archived=true),unknown.copy(id="manual",autoRenew=false)),
            payments=listOf(Payment(planId=usd.id,planName=usd.name,amount="20",currency="USD",date="2024-01-01",cnyAmount="140")))
        val summary=Book.forecastSummary(l,date("2024-02-01"),date("2024-03-01"))
        money("170",summary.known)
        assertEquals(listOf("unpriced"),summary.missingPlanIds)
        assertNull(Book.forecastCny(l,date("2024-02-01"),date("2024-03-01")))
        val empty=Book.forecastSummary(l,date("2024-02-01"),date("2024-02-01"))
        money("0",empty.known);assertTrue(empty.missingPlanIds.isEmpty())
    }

}
