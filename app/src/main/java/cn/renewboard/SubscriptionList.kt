package cn.renewboard

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/** Read-only comparisons of the configured price, separate from actual payments. */
internal object SubscriptionList {
    fun matchingBenefits(l:Ledger,p:Plan,query:String):List<String> = query.trim().takeIf {it.isNotEmpty()}?.let {q->
        l.benefits.filter {it.planId==p.id && it.name.contains(q,ignoreCase=true)}.map {it.name}.distinct()
    } ?: emptyList()

    fun matches(l:Ledger,p:Plan,query:String)=p.name.contains(query.trim(),ignoreCase=true) || matchingBenefits(l,p,query).isNotEmpty()

    fun originalAmount(p:Plan,today:LocalDate):BigDecimal = if(p.balanceAccount!=null) Prepaid.feeAt(p,today) else BigDecimal(p.amount)

    fun comparisonCny(l:Ledger,p:Plan,monthly:Boolean,today:LocalDate):BigDecimal? {
        val amount=originalAmount(p,today)
        val cny=if(p.currency=="CNY") amount else {
            val receipt=latestCnySettlement(l,p,today) ?: return null
            amount.multiply(BigDecimal(receipt.cnyAmount!!)).divide(BigDecimal(receipt.amount),MathContext.DECIMAL128)
        }
        if(!monthly || p.balanceAccount!=null) return cny
        val perCycle=cny.divide(p.interval.toBigDecimal(),MathContext.DECIMAL128)
        return when(p.cycle) {
            Cycle.MONTH -> perCycle
            Cycle.YEAR -> perCycle.divide(BigDecimal(12),MathContext.DECIMAL128)
            Cycle.WEEK -> perCycle.multiply(BigDecimal(52)).divide(BigDecimal(12),MathContext.DECIMAL128)
        }
    }

    fun sortByCost(l:Ledger,plans:List<Plan>,monthly:Boolean,today:LocalDate):List<Plan> {
        val amounts=plans.associate {it.id to comparisonCny(l,it,monthly,today)}
        return plans.sortedWith(compareBy<Plan>{amounts[it.id]==null}
            .thenByDescending {amounts[it.id]}.thenBy {it.name})
    }

    fun period(p:Plan):String = if(p.balanceAccount!=null) "月" else when(p.cycle) {
        Cycle.WEEK -> if(p.interval==1) "周" else "${p.interval}周"
        Cycle.MONTH -> if(p.interval==1) "月" else "${p.interval}个月"
        Cycle.YEAR -> if(p.interval==1) "年" else "${p.interval}年"
    }
}
