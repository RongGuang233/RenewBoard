package cn.renewboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Balance after this date: confirmed by the user or advanced by configured monthly fees. */
@Serializable data class BalanceAccount(val balance: String, val asOf: String)

object Prepaid {
    fun isTopUp(payment: Payment) = payment.note == "话费充值"
    fun expenses(l: Ledger) = l.payments.filterNot(::isTopUp)

    /** Persist configured monthly fees once; the balance date advances even if receipts are later deleted. */
    fun accrue(l: Ledger, through: LocalDate): Ledger {
        val added=mutableListOf<Payment>()
        val plans=l.plans.map { p ->
            val account=p.balanceAccount
            if(account==null || p.archived || LocalDate.parse(account.asOf)>=through) return@map p
            var date=nextDeduction(p,LocalDate.parse(account.asOf))
            var last:LocalDate?=null
            while(date<=through) {
                if(BigDecimal(p.amount).signum()>0) added += Payment(planId=p.id,planName=p.name,amount=p.amount,
                    currency="CNY",date=date.toString(),note="话费扣费")
                last=date;date=nextDeduction(p,date)
            }
            if(last==null) p else p.copy(balanceAccount=BalanceAccount(balance(p,last).toPlainString(),last.toString()))
        }
        return if(plans==l.plans && added.isEmpty()) l else l.copy(plans=plans,payments=l.payments+added)
    }
    fun archive(l:Ledger,id:String,archived:Boolean,today:LocalDate):Ledger {
        val settled=accrue(l,today)
        return settled.copy(plans=settled.plans.map { p ->
            if(p.id!=id || p.archived==archived) p else p.copy(archived=archived,
                balanceAccount=p.balanceAccount?.copy(balance=balance(p,today).toPlainString(),asOf=today.toString()))
        })
    }

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
        require(day >= asOf) { "查询日期不能早于余额查询日期" }
        if(p.archived) return BigDecimal(account.balance)
        val count = indexAfter(p, day) - indexAfter(p, asOf)
        return BigDecimal(account.balance) - BigDecimal(p.amount).multiply(BigDecimal(count))
    }
    /** First charge not fully covered by the recorded balance; never an actual payment. */
    fun rechargeDate(p: Plan): LocalDate? {
        val account = requireNotNull(p.balanceAccount)
        val fee = BigDecimal(p.amount)
        if (fee.signum() == 0) return null
        val asOf = LocalDate.parse(account.asOf)
        if(BigDecimal(account.balance).signum()<0 && nextDeduction(p,asOf.minusDays(1))==asOf) return asOf
        val funded = BigDecimal(account.balance).max(BigDecimal.ZERO).divideToIntegralValue(fee)
        val first = nextDeduction(p, asOf)
        if (funded > BigDecimal(ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.of(2200, 12)))) return null
        return LocalDate.parse(p.billingAnchor).plusMonths(indexAfter(p, asOf) + funded.toLong())
    }
    fun topUp(l: Ledger, planId: String, amount: String, date: LocalDate, affectBalance: Boolean = true): Ledger {
        val original=l.plans.single { it.id==planId }
        val account=requireNotNull(original.balanceAccount) { "请选择余额账户" }
        require(date<=LocalDate.now()) { "充值日期不能晚于今天" }
        require(BigDecimal(amount).signum() > 0) { "充值金额应大于0" }
        val through=maxOf(date,LocalDate.parse(account.asOf))
        val settled=accrue(l,through)
        val p=settled.plans.single { it.id==planId }
        val updated=if(affectBalance) p.copy(balanceAccount=BalanceAccount(
            (balance(p,through)+BigDecimal(amount)).toPlainString(),through.toString())) else p
        return settled.copy(plans=settled.plans.map { if(it.id==p.id) updated else it },payments=settled.payments+
            Payment(planId=p.id,planName=p.name,amount=amount,currency="CNY",date=date.toString(),note="话费充值"))
            .also(Book::validate)
    }
    fun calibrate(l:Ledger,planId:String,amount:String,date:LocalDate,recordExpense:Boolean=false):Ledger {
        val original=l.plans.single { it.id==planId }
        val account=requireNotNull(original.balanceAccount) { "请选择余额账户" }
        require(date>=LocalDate.parse(account.asOf) && date<=LocalDate.now()) { "请校准最近余额日期至今天的余额" }
        val settled=accrue(l,date)
        val p=settled.plans.single { it.id==planId }
        val difference=balance(p,date)-BigDecimal(amount)
        require(!recordExpense || difference.signum()>0) { "实际余额低于估算余额时，才能将差额记为额外支出" }
        val updated=p.copy(balanceAccount=BalanceAccount(BigDecimal(amount).toPlainString(),date.toString()))
        val payment=if(recordExpense) Payment(planId=p.id,planName=p.name,amount=difference.toPlainString(),
            currency="CNY",date=date.toString(),note="话费额外扣费") else null
        return settled.copy(plans=settled.plans.map { if(it.id==p.id) updated else it },
            payments=settled.payments+listOfNotNull(payment)).also(Book::validate)
    }
    fun due(l: Ledger, today: LocalDate): List<Plan> = l.plans.filter { p ->
        p.balanceAccount != null && !p.archived && rechargeDate(p)?.let {
            ChronoUnit.DAYS.between(today, it).toInt() in l.settings.reminderDays
        } == true
    }
}
