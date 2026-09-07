package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LedgerStatsTest {
    private fun d(value: String) = LocalDate.parse(value)
    private fun payment(date: String, amount: String = "1", currency: String = "CNY", cny: String? = null) =
        Payment(planId = "music", planName = "音乐会员", amount = amount, currency = currency, date = date, cnyAmount = cny)
    private fun money(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun summariesKeepFrozenSettlementsAndDistinguishMissingFromZero() {
        val payments = listOf(
            payment("2026-09-01", "10.12"),
            payment("2026-09-01", "20", "USD", "140.56"),
            payment("2026-09-01", "20", "USD"),
            payment("2026-09-01", "0", "USD", "0"),
            payment("2026-09-01", "0")
        )
        val result = LedgerStats.summary(payments)
        money("150.68", result.known)
        assertEquals(1, result.missing)
        assertEquals(5, result.count)
        val ledger = Ledger(payments = payments, settings = Settings(rates = mapOf("USD" to "8")))
        assertEquals(result, LedgerStats.summary(ledger.copy(settings = Settings(rates = mapOf("USD" to "10"))).payments))
        assertEquals(CashSummary(BigDecimal.ZERO, 0, 0), LedgerStats.summary(emptyList()))
        assertEquals(CashSummary(BigDecimal.ZERO, 1, 1), LedgerStats.summary(listOf(payments[2])))
    }

    @Test fun thirtyDaysIncludeTodayAndLeapDayWithHalfOpenBounds() {
        val payments = listOf(
            payment("2024-01-31", "100"), payment("2024-02-01", "1"),
            payment("2024-02-29", "2"), payment("2024-03-01", "3"), payment("2024-03-02", "100")
        )
        val buckets = LedgerStats.buckets(payments, TrendRange.MONTH, d("2024-03-01"))
        assertEquals(30, buckets.size)
        assertEquals(d("2024-02-01"), buckets.first().from)
        assertEquals(d("2024-03-02"), buckets.last().until)
        money("2", buckets.single { it.from == d("2024-02-29") }.summary.known)
        money("6", buckets.fold(BigDecimal.ZERO) { sum, bucket -> sum + bucket.summary.known })
        assertEquals(3, buckets.sumOf { it.summary.count })
        assertTrue(buckets.zipWithNext().all { (left, right) -> left.until == right.from })
    }

    @Test fun rollingMonthsCrossYearsAndDoNotCountFuturePayments() {
        val buckets = LedgerStats.buckets(listOf(
            payment("2025-11-30", "100"), payment("2025-12-31", "2"),
            payment("2026-01-01", "3"), payment("2026-02-10", "4"), payment("2026-02-11", "100")
        ), TrendRange.THREE, d("2026-02-10"))
        assertEquals(listOf("2025/12", "2026/1", "2026/2"), buckets.map { it.label })
        assertEquals(d("2025-12-01"), buckets.first().from)
        assertEquals(d("2026-02-11"), buckets.last().until)
        listOf("2", "3", "4").zip(buckets).forEach { (amount, bucket) -> money(amount, bucket.summary.known) }
        val six = LedgerStats.buckets(emptyList(), TrendRange.SIX, d("2026-02-10"))
        assertEquals(6, six.size)
        assertEquals(d("2025-09-01"), six.first().from)
        assertTrue(six.all { it.summary.count == 0 })
    }

    @Test fun calendarYearAndRollingTwelveMonthsHaveDifferentBoundaries() {
        val payments = listOf(payment("2025-10-01", "10"), payment("2026-01-01", "20"))
        val rolling = LedgerStats.buckets(payments, TrendRange.TWELVE, d("2026-09-07"))
        val calendar = LedgerStats.buckets(payments, TrendRange.YEAR, d("2026-09-07"))
        assertEquals(12, rolling.size)
        assertEquals(d("2025-10-01"), rolling.first().from)
        assertEquals(2, rolling.sumOf { it.summary.count })
        assertEquals(d("2026-01-01"), calendar.first().from)
        assertEquals(d("2026-09-08"), calendar.last().until)
        assertEquals(1, calendar.sumOf { it.summary.count })
        val historical = LedgerStats.buckets(listOf(payment("2024-12-31")), TrendRange.YEAR, d("2026-09-07"), 2024)
        assertEquals(12, historical.size)
        assertEquals(d("2025-01-01"), historical.last().until)
        assertEquals(1, historical.last().summary.count)
    }

    @Test fun fiveYearsUseCalendarYearsAndKeepPartialCurrentYear() {
        val buckets = LedgerStats.buckets(listOf(
            payment("2021-12-31", "100"), payment("2022-01-01", "2"),
            payment("2026-09-07", "3"), payment("2026-12-31", "100")
        ), TrendRange.FIVE, d("2026-09-07"))
        assertEquals(listOf("2022", "2023", "2024", "2025", "2026"), buckets.map { it.label })
        assertEquals(d("2026-09-08"), buckets.last().until)
        assertEquals(2, buckets.sumOf { it.summary.count })
        money("2", buckets.first().summary.known)
        money("3", buckets.last().summary.known)
    }

    @Test fun allTimeSwitchesFromMonthlyToYearlyAfterTwelveCalendarMonths() {
        val today = d("2026-09-07")
        val monthly = LedgerStats.buckets(listOf(payment("2025-10-31"), payment("2027-01-01")), TrendRange.ALL, today)
        assertEquals(12, monthly.size)
        assertEquals(d("2025-10-01"), monthly.first().from)
        assertEquals(1, monthly.sumOf { it.summary.count })
        val yearly = LedgerStats.buckets(listOf(payment("2025-09-30")), TrendRange.ALL, today)
        assertEquals(2, yearly.size)
        assertEquals(d("2025-01-01"), yearly.first().from)
        assertEquals(d("2026-09-08"), yearly.last().until)
        assertTrue(LedgerStats.buckets(emptyList(), TrendRange.ALL, today).isEmpty())
        assertTrue(LedgerStats.buckets(listOf(payment("2027-01-01")), TrendRange.ALL, today).isEmpty())
    }

    @Test fun foreignUnknownPaymentsStayVisibleInTheirDateBucket() {
        val bucket = LedgerStats.buckets(listOf(payment("2026-09-07", "20", "USD")), TrendRange.MONTH, d("2026-09-07")).last()
        money("0", bucket.summary.known)
        assertEquals(1, bucket.summary.missing)
        assertEquals(1, bucket.summary.count)
    }

    private fun ledger(): Ledger {
        val music = Plan(id = "music", name = "音乐会员", amount = "20", billingAnchor = "2026-01-01", paidCycles = 2)
        val phone = Plan(id = "phone", name = "中国移动", amount = "30", billingAnchor = "2026-01-01",
            balanceAccount = BalanceAccount("80", "2026-01-01"))
        return Ledger(plans = listOf(music, phone), benefits = listOf(Benefit(id = "right", planId = "music", name = "音乐会员", anchor = "2026-01-01", renewals = 2)),
            payments = listOf(payment("2026-01-01", "20").copy(id = "one", benefitIds = listOf("right")),
                payment("2026-02-01", "20").copy(id = "two", benefitIds = listOf("right")),
                payment("2026-01-01", "100").copy(id = "topup", planId = "phone", planName = "中国移动", note = "话费充值")))
    }

    @Test fun deletingSubscriptionRetainsReceiptsUnlessExplicitlySelected() {
        val original = ledger()
        val retained = Book.delete(original, "music")
        assertEquals(original.payments, retained.payments)
        assertEquals(listOf(original.plans[1]), retained.plans)
        assertTrue(retained.benefits.isEmpty())
        val deleted = Book.delete(original, "music", deletePayments = true)
        assertEquals(listOf(original.payments[2]), deleted.payments)
        assertEquals(retained.plans, deleted.plans)
        assertEquals(retained.benefits, deleted.benefits)
        Book.validate(retained)
        Book.validate(deleted)
    }

    @Test fun deletingIndividualAndBatchReceiptsNeverRollsBackRightsOrBalance() {
        val original = ledger()
        val single = Book.deletePayments(original, setOf("one"))
        assertEquals(listOf("two", "topup"), single.payments.map { it.id })
        assertEquals(original.plans, single.plans)
        assertEquals(original.benefits, single.benefits)
        val batch = Book.deletePayments(original, setOf("one", "topup", "not-present"))
        assertEquals(listOf("two"), batch.payments.map { it.id })
        assertEquals(original.plans, batch.plans)
        assertEquals(original.benefits, batch.benefits)
        assertEquals(original.settings, batch.settings)
        assertEquals(original, Book.deletePayments(original, emptySet()))
        assertEquals(batch, Book.deletePayments(batch, setOf("one", "topup")))
        Book.validate(batch)
    }
    @Test fun correctingReceiptMovesSpendingToItsCorrectDateWithoutChangingBenefits() {
        val original=ledger()
        val corrected=Book.editPayment(original,"one","35",d("2026-03-02"),"日期金额更正")
        val buckets=LedgerStats.buckets(Prepaid.expenses(corrected),TrendRange.THREE,d("2026-03-07"))
        assertEquals(listOf("2026/1","2026/2","2026/3"),buckets.map {it.label})
        money("0",buckets[0].summary.known)
        money("20",buckets[1].summary.known)
        money("35",buckets[2].summary.known)
        assertEquals(original.plans,corrected.plans)
        assertEquals(original.benefits,corrected.benefits)
    }

}
