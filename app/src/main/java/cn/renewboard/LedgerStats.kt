package cn.renewboard

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class TrendRange(val label: String) {
    MONTH("近30天"), THREE("近3个月"), SIX("近6个月"), TWELVE("近12个月"), YEAR("按年"), FIVE("近5年"), ALL("全部")
}

data class CashSummary(val known: BigDecimal, val missing: Int, val count: Int)
data class CashBreakdown(val payments: CashSummary, val refunds: CashSummary)
data class CashBucket(val label: String, val from: LocalDate, val until: LocalDate, val summary: CashSummary)

object LedgerStats {
    /** Expense components use frozen receipts; top-ups only add balance and are excluded. */
    fun breakdown(payments: List<Payment>): CashBreakdown {
        val expenses = payments.filterNot(Prepaid::isTopUp)
        val refunds = summary(expenses.filter { it.refundOf != null })
        return CashBreakdown(summary(expenses.filter { it.refundOf == null }), refunds.copy(known = refunds.known.abs()))
    }

    fun summary(payments: List<Payment>): CashSummary {
        var known = BigDecimal.ZERO
        var missing = 0
        payments.forEach { payment ->
            val amount = payment.signedCny()
            if (amount == null) missing++ else known += amount
        }
        return CashSummary(known, missing, payments.size)
    }

    private enum class Grain {
        DAY, MONTH, YEAR;
        fun start(date: LocalDate): LocalDate = when (this) {
            DAY -> date
            MONTH -> date.withDayOfMonth(1)
            YEAR -> date.withDayOfYear(1)
        }
        fun next(date: LocalDate): LocalDate = when (this) {
            DAY -> date.plusDays(1)
            MONTH -> date.plusMonths(1)
            YEAR -> date.plusYears(1)
        }
        fun label(date: LocalDate): String = when (this) {
            DAY -> "${date.monthValue}/${date.dayOfMonth}"
            MONTH -> "${date.year}/${date.monthValue}"
            YEAR -> "${date.year}"
        }
    }

    fun buckets(payments: List<Payment>, range: TrendRange, today: LocalDate, year: Int = today.year): List<CashBucket> {
        val dated = payments.map { LocalDate.parse(it.date) to it }.filter { it.first <= today }
        val month = today.withDayOfMonth(1)
        val yearStart = today.withDayOfYear(1)
        val grain: Grain
        val from: LocalDate
        when (range) {
            TrendRange.MONTH -> { grain = Grain.DAY; from = today.minusDays(29) }
            TrendRange.THREE -> { grain = Grain.MONTH; from = month.minusMonths(2) }
            TrendRange.SIX -> { grain = Grain.MONTH; from = month.minusMonths(5) }
            TrendRange.TWELVE -> { grain = Grain.MONTH; from = month.minusMonths(11) }
            TrendRange.YEAR -> { grain = Grain.MONTH; from = LocalDate.of(year, 1, 1) }
            TrendRange.FIVE -> { grain = Grain.YEAR; from = yearStart.minusYears(4) }
            TrendRange.ALL -> {
                val first = dated.minOfOrNull { it.first } ?: return emptyList()
                grain = if (ChronoUnit.MONTHS.between(first.withDayOfMonth(1), month) < 12) Grain.MONTH else Grain.YEAR
                from = grain.start(first)
            }
        }
        val until = if (range == TrendRange.YEAR) minOf(LocalDate.of(year + 1, 1, 1), today.plusDays(1)) else today.plusDays(1)
        val grouped = dated.filter { it.first >= from && it.first < until }
            .groupBy({ grain.start(it.first) }, { it.second })
        return generateSequence(from) { grain.next(it) }.takeWhile { it < until }.map { start ->
            CashBucket(grain.label(start), start, minOf(grain.next(start), until), summary(grouped[start].orEmpty()))
        }.toList()
    }
}
