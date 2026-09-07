package cn.renewboard

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class TrendRange(val label: String) {
    MONTH("近1月"), THREE("3月"), SIX("6月"), TWELVE("12月"), YEAR("1年"), FIVE("5年"), ALL("全部")
}

data class CashSummary(val known: BigDecimal, val missing: Int, val count: Int)
data class CashBucket(val label: String, val from: LocalDate, val until: LocalDate, val summary: CashSummary)

object LedgerStats {
    fun summary(payments: List<Payment>): CashSummary {
        var known = BigDecimal.ZERO
        var missing = 0
        payments.forEach { payment ->
            val amount = if (payment.currency == "CNY") payment.amount else payment.cnyAmount
            if (amount == null) missing++ else known += BigDecimal(amount)
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
