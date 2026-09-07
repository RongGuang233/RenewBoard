package cn.renewboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Confirmed balance after that day's charges; subsequent monthly charges are estimates. */
@Serializable data class BalanceAccount(val balance: String, val asOf: String)

object Prepaid {
    private fun indexAfter(p: Plan, day: LocalDate): Long {
        val anchor = LocalDate.parse(p.billingAnchor)
        var index = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(day)).coerceAtLeast(0)
        while (!anchor.plusMonths(index).isAfter(day)) index++
        return index
    }
    fun nextDeduction(p: Plan, after: LocalDate): LocalDate =
        LocalDate.parse(p.billingAnchor).plusMonths(indexAfter(p, after))

    fun balance(p: Plan, day: LocalDate): BigDecimal {
        val account = requireNotNull(p.balanceAccount)
        val asOf = LocalDate.parse(account.asOf)
        require(day >= asOf) { "查询日期不能早于余额记录日期" }
        val count = indexAfter(p, day) - indexAfter(p, asOf)
        return BigDecimal(account.balance) - BigDecimal(p.amount).multiply(BigDecimal(count))
    }
    /** First charge not fully covered by the recorded balance; never an actual payment. */
    fun rechargeDate(p: Plan): LocalDate? {
        val account = requireNotNull(p.balanceAccount)
        val fee = BigDecimal(p.amount)
        if (fee.signum() == 0) return null
        val asOf = LocalDate.parse(account.asOf)
        val funded = BigDecimal(account.balance).max(BigDecimal.ZERO).divideToIntegralValue(fee)
        val first = nextDeduction(p, asOf)
        if (funded > BigDecimal(ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.of(2200, 12)))) return null
        return LocalDate.parse(p.billingAnchor).plusMonths(indexAfter(p, asOf) + funded.toLong())
    }
    fun topUp(l: Ledger, planId: String, amount: String, date: LocalDate): Ledger {
        val p = l.plans.single { it.id == planId }
        require(p.balanceAccount != null) { "请选择余额账户" }
        require(date >= LocalDate.parse(p.balanceAccount.asOf)) { "充值日期不能早于余额记录日期" }
        require(BigDecimal(amount).signum() > 0) { "充值金额应大于0" }
        val updated = p.copy(balanceAccount = BalanceAccount((balance(p, date) + BigDecimal(amount)).toPlainString(), date.toString()))
        return l.copy(plans = l.plans.map { if(it.id == p.id) updated else it }, payments = l.payments +
            Payment(planId = p.id, planName = p.name, amount = amount, currency = "CNY", date = date.toString(), note = "话费充值"))
            .also(Book::validate)
    }
    fun due(l: Ledger, today: LocalDate): List<Plan> = l.plans.filter { p ->
        p.balanceAccount != null && !p.archived && rechargeDate(p)?.let {
            ChronoUnit.DAYS.between(today, it).toInt() in l.settings.reminderDays
        } == true
    }
}
