package cn.renewboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate

/** User-confirmed platform credit; usage changes credit, while top-ups are cash expenses. */
@Serializable data class UsageBalance(val balance: String, val asOf: String)

object UsageBalances {
    fun isTopUp(payment:Payment)=payment.note=="平台充值" && payment.refundOf==null
    fun balance(p:Plan)=BigDecimal(requireNotNull(p.usageBalance) { "请选择平台余额账户" }.balance)

    fun topUp(l:Ledger,id:String,amount:String,date:LocalDate,cnyAmount:String?=null,affectBalance:Boolean=true):Ledger {
        val p=l.plans.single {it.id==id}
        val account=requireNotNull(p.usageBalance) { "请选择平台余额账户" }
        require(date<=LocalDate.now()) { "充值日期不能晚于今天" }
        val value=BigDecimal(amount.trim())
        require(value.signum()>0) { "充值金额应大于0" }
        require(p.currency=="CNY" || !cnyAmount.isNullOrBlank()) { "请填写付款当天的实际人民币金额" }
        val updated=if(affectBalance) p.copy(usageBalance=account.copy(
            balance=(balance(p)+value).toPlainString(),asOf=maxOf(date,LocalDate.parse(account.asOf)).toString())) else p
        val payment=Payment(planId=p.id,planName=p.name,amount=amount.trim(),currency=p.currency,date=date.toString(),
            note="平台充值",cnyAmount=if(p.currency=="CNY") null else cnyAmount?.trim())
        return l.copy(plans=l.plans.map {if(it.id==id) updated else it},payments=l.payments+payment).also(Book::validate)
    }

    /** A queried remaining credit already includes consumption; it creates no additional expense. */
    fun calibrate(l:Ledger,id:String,balance:String,date:LocalDate):Ledger {
        val p=l.plans.single {it.id==id}
        val account=requireNotNull(p.usageBalance) { "请选择平台余额账户" }
        require(date>=LocalDate.parse(account.asOf) && date<=LocalDate.now()) { "请校准最近余额日期至今天的余额" }
        require(BigDecimal(balance.trim()).signum()>=0) { "余额不能为负数" }
        val updated=p.copy(usageBalance=account.copy(balance=balance.trim(),asOf=date.toString()))
        return l.copy(plans=l.plans.map {if(it.id==id) updated else it}).also(Book::validate)
    }
}
