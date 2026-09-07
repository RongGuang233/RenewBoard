package cn.renewboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Balance after this date: confirmed by the user or advanced by configured monthly fees. */
@Serializable data class MonthlyFeeChange(val amount: String, val effectiveDate: String)
@Serializable data class BalanceAccount(val balance: String, val asOf: String, val pendingFee: MonthlyFeeChange? = null)

object Prepaid {
    fun isTopUp(payment: Payment) = payment.note == "话费充值"
    fun expenses(l: Ledger) = l.payments.filterNot(::isTopUp)

    fun feeAt(p:Plan,date:LocalDate):BigDecimal {
        val pending=p.balanceAccount?.pendingFee
        return BigDecimal(if(pending!=null && date>=LocalDate.parse(pending.effectiveDate)) pending.amount else p.amount)
    }
    /** Settle with both prices before promoting a scheduled fee, including dates between deductions. */
    fun accrue(l: Ledger, through: LocalDate): Ledger {
        val added=mutableListOf<Payment>()
        val plans=l.plans.map { p ->
            val account=p.balanceAccount ?: return@map p
            if(LocalDate.parse(account.asOf)>through) return@map p
            var last:LocalDate?=null
            if(!p.archived) {
                var date=nextDeduction(p,LocalDate.parse(account.asOf))
                while(date<=through) {
                    val fee=feeAt(p,date)
                    if(fee.signum()>0) added += Payment(planId=p.id,planName=p.name,amount=fee.toPlainString(),
                        currency="CNY",date=date.toString(),note="话费扣费")
                    last=date;date=nextDeduction(p,date)
                }
            }
            val pending=account.pendingFee
            val promote=pending!=null && LocalDate.parse(pending.effectiveDate)<=through
            val settledThrough=if(promote) through else last
            if(settledThrough==null) p else p.copy(amount=if(promote) pending!!.amount else p.amount,
                balanceAccount=account.copy(balance=balance(p,settledThrough).toPlainString(),asOf=settledThrough.toString(),
                    pendingFee=if(promote) null else pending))
        }
        return if(plans==l.plans && added.isEmpty()) l else l.copy(plans=plans,payments=l.payments+added)
    }
    fun changeMonthlyFee(l:Ledger,id:String,amount:String,effectiveDate:LocalDate,today:LocalDate):Ledger {
        require(effectiveDate>=today) { "生效时间不能早于今天" }
        require(BigDecimal(amount).signum()>=0) { "月费不能为负数" }
        val settled=accrue(l,today)
        val p=settled.plans.single {it.id==id}
        val account=requireNotNull(p.balanceAccount) { "请选择余额账户" }
        require(today>=LocalDate.parse(account.asOf)) { "生效时间不能早于最近余额日期" }
        val updated=if(effectiveDate==today) p.copy(amount=amount,balanceAccount=account.copy(
            balance=balance(p,today).toPlainString(),asOf=today.toString(),pendingFee=null))
        else p.copy(balanceAccount=account.copy(pendingFee=MonthlyFeeChange(amount,effectiveDate.toString())))
        return settled.copy(plans=settled.plans.map {if(it.id==id) updated else it}).also(Book::validate)
    }
    fun cancelMonthlyFeeChange(l:Ledger,id:String,today:LocalDate):Ledger {
        val settled=accrue(l,today)
        return settled.copy(plans=settled.plans.map {if(it.id==id) it.copy(balanceAccount=it.balanceAccount?.copy(pendingFee=null)) else it})
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
        val pending=account.pendingFee
        val newCount=if(pending==null || LocalDate.parse(pending.effectiveDate)>day) 0L else {
            val before=maxOf(asOf,LocalDate.parse(pending.effectiveDate).minusDays(1))
            indexAfter(p,day)-indexAfter(p,before)
        }
        return BigDecimal(account.balance)-BigDecimal(p.amount).multiply(BigDecimal(count-newCount))-
            (pending?.let {BigDecimal(it.amount)} ?: BigDecimal.ZERO).multiply(BigDecimal(newCount))
    }
    /** First deduction not fully covered by the balance, honoring a scheduled price. */
    fun rechargeDate(p: Plan): LocalDate? {
        val account = requireNotNull(p.balanceAccount)
        val asOf = LocalDate.parse(account.asOf)
        var available=BigDecimal(account.balance)
        if(available.signum()<0 && nextDeduction(p,asOf.minusDays(1))==asOf) return asOf
        var date=nextDeduction(p,asOf)
        while(date.year<=2200) {
            val fee=feeAt(p,date)
            if(fee.signum()>0 && available<fee) return date
            available-=fee
            if(fee.signum()==0 && (account.pendingFee==null || LocalDate.parse(account.pendingFee.effectiveDate)<=date)) return null
            date=nextDeduction(p,date)
        }
        return null
    }
    fun topUp(l: Ledger, planId: String, amount: String, date: LocalDate, affectBalance: Boolean = true): Ledger {
        val original=l.plans.single { it.id==planId }
        val account=requireNotNull(original.balanceAccount) { "请选择余额账户" }
        require(date<=LocalDate.now()) { "充值日期不能晚于今天" }
        require(BigDecimal(amount).signum() > 0) { "充值金额应大于0" }
        val through=maxOf(date,LocalDate.parse(account.asOf))
        val settled=accrue(l,through)
        val p=settled.plans.single { it.id==planId }
        val updated=if(affectBalance) p.copy(balanceAccount=p.balanceAccount!!.copy(
            balance=(balance(p,through)+BigDecimal(amount)).toPlainString(),asOf=through.toString())) else p
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
        val updated=p.copy(balanceAccount=p.balanceAccount!!.copy(balance=BigDecimal(amount).toPlainString(),asOf=date.toString()))
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
