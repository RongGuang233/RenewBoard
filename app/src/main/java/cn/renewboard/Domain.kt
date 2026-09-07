package cn.renewboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

fun newId() = UUID.randomUUID().toString()
@Serializable enum class Cycle(val label: String) { WEEK("周"), MONTH("月"), YEAR("年") }
@Serializable data class Plan(
    val id: String = newId(), val name: String, val amount: String, val currency: String = "CNY",
    val cycle: Cycle = Cycle.MONTH, val interval: Int = 1, val autoRenew: Boolean = true,
    val billingAnchor: String, val paidCycles: Int = 1, val archived: Boolean = false, val note: String = "",
    val balanceAccount: BalanceAccount? = null
)
@Serializable data class Benefit(
    val id: String = newId(), val planId: String, val name: String,
    val anchor: String, val renewals: Int = 0, val giftDays: Int = 0
)
@Serializable data class Payment(
    val id: String = newId(), val planId: String, val planName: String, val amount: String,
    val currency: String, val date: String, val note: String = "", val benefitIds: List<String> = emptyList(),
    val cnyAmount: String? = null
)
data class ForecastSummary(val known: BigDecimal, val missingPlanIds: List<String>)

@Serializable data class Settings(val reminderDays: List<Int> = listOf(3, 0), val rates: Map<String, String> = emptyMap())
@Serializable data class Ledger(val plans: List<Plan> = emptyList(), val benefits: List<Benefit> = emptyList(), val payments: List<Payment> = emptyList(), val settings: Settings = Settings(), val devices: List<Device> = emptyList())
@Serializable data class Backup(val version: Int = 1, val createdAt: String = Instant.now().toString(), val data: Ledger)

object Book {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun advance(anchor: LocalDate, cycle: Cycle, n: Long): LocalDate = when(cycle) {
        Cycle.WEEK -> anchor.plusWeeks(n)
        Cycle.MONTH -> anchor.plusMonths(n)
        Cycle.YEAR -> anchor.plusYears(n)
    }
    fun expiry(b: Benefit, p: Plan): LocalDate = advance(LocalDate.parse(b.anchor), p.cycle, b.renewals.toLong() * p.interval).plusDays(b.giftDays.toLong())
    fun nextCharge(p: Plan, today: LocalDate): LocalDate {
        val anchor = LocalDate.parse(p.billingAnchor)
        var i = p.paidCycles.toLong()
        var date = advance(anchor, p.cycle, i * p.interval)
        while (date < today) { i++; date = advance(anchor, p.cycle, i * p.interval) }
        return date
    }
    fun forecast(l: Ledger, from: LocalDate, until: LocalDate): Map<String, BigDecimal> {
        val totals = mutableMapOf<String, BigDecimal>()
        l.plans.filter { it.autoRenew && !it.archived && it.balanceAccount == null }.forEach { p ->
            var date = nextCharge(p, from)
            var index = p.paidCycles.toLong()
            while (advance(LocalDate.parse(p.billingAnchor), p.cycle, index * p.interval) < date) index++
            while (date < until) {
                totals[p.currency] = (totals[p.currency] ?: BigDecimal.ZERO) + BigDecimal(p.amount)
                index++; date = advance(LocalDate.parse(p.billingAnchor), p.cycle, index * p.interval)
            }
        }
        l.plans.filter { it.autoRenew && !it.archived && it.balanceAccount != null }.forEach { p ->
            var after=maxOf(from.minusDays(1),LocalDate.parse(p.balanceAccount!!.asOf))
            var date=Prepaid.nextDeduction(p,after)
            while(date<until) {
                totals["CNY"]=(totals["CNY"] ?: BigDecimal.ZERO)+BigDecimal(p.amount)
                after=date;date=Prepaid.nextDeduction(p,after)
            }
        }
        return totals
    }
    // Forecast uses the latest recorded settlement ratio for each plan, not mutable settings rates.
    fun forecastSummary(l: Ledger, from: LocalDate, until: LocalDate): ForecastSummary {
        var total = BigDecimal.ZERO
        val missing=mutableListOf<String>()
        l.plans.forEach { plan ->
            val amount = forecast(l.copy(plans = listOf(plan)), from, until)[plan.currency] ?: return@forEach
            if (plan.currency == "CNY" || amount.signum() == 0) {
                total += amount
            } else {
                val payment = l.payments.withIndex().filter { (_, p) ->
                    p.planId == plan.id && p.currency == plan.currency && p.cnyAmount != null &&
                        BigDecimal(p.amount).signum() > 0 && LocalDate.parse(p.date) <= from
                }.maxWithOrNull(compareBy<IndexedValue<Payment>> { LocalDate.parse(it.value.date) }.thenBy { it.index })?.value
                if(payment==null) { missing += plan.id; return@forEach }
                total += amount.multiply(BigDecimal(payment.cnyAmount!!))
                    .divide(BigDecimal(payment.amount), MathContext.DECIMAL128)
            }
        }
        return ForecastSummary(total,missing)
    }
    fun forecastCny(l: Ledger, from: LocalDate, until: LocalDate): BigDecimal? =
        forecastSummary(l,from,until).let { if(it.missingPlanIds.isEmpty()) it.known else null }
    fun paid(l: Ledger, from: LocalDate? = null, until: LocalDate? = null): Map<String, BigDecimal> =
        Prepaid.expenses(l).filter { (from == null || LocalDate.parse(it.date) >= from) && (until == null || LocalDate.parse(it.date) < until) }
            .groupBy { it.currency }.mapValues { (_, ps) -> ps.fold(BigDecimal.ZERO) { a, p -> a + BigDecimal(p.amount) } }
    // Historical payments use their recorded settlement amount, never today's exchange rate.
    fun paidCny(l: Ledger, from: LocalDate? = null, until: LocalDate? = null): BigDecimal? {
        var total = BigDecimal.ZERO
        Prepaid.expenses(l).filter { (from == null || LocalDate.parse(it.date) >= from) && (until == null || LocalDate.parse(it.date) < until) }
            .forEach { p -> total += BigDecimal(if (p.currency == "CNY") p.amount else p.cnyAmount ?: return null) }
        return total
    }
    fun estimate(totals: Map<String, BigDecimal>, rates: Map<String, String>): BigDecimal? {
        if (totals.keys.any { it != "CNY" && !rates.containsKey(it) }) return null
        return totals.entries.fold(BigDecimal.ZERO) { a, (c, v) -> a + v * if (c == "CNY") BigDecimal.ONE else BigDecimal(rates.getValue(c)) }
    }
    private val paymentTypes=setOf("话费充值","话费扣费","话费额外扣费")
    private fun receipt(p: Plan, amount: String, date: LocalDate, note: String, cnyAmount: String?, selected: Set<String> = emptySet()): Payment {
        require(p.currency=="CNY" || !cnyAmount.isNullOrBlank()) { "请填写付款当天的实际人民币金额" }
        require(note.trim() !in paymentTypes) { "话费类型请通过话费账户记录" }
        return Payment(planId=p.id,planName=p.name,amount=amount.trim(),currency=p.currency,date=date.toString(),note=note.trim(),
            benefitIds=selected.toList(),cnyAmount=if(p.currency=="CNY") null else cnyAmount?.trim())
    }
    /** Add a historical receipt without extending benefits or changing a balance. */
    fun recordPayment(l: Ledger, planId: String, amount: String, date: LocalDate, note: String = "", cnyAmount: String? = null): Ledger {
        val p=l.plans.single { it.id==planId }
        require(p.balanceAccount==null) { "话费账户请使用充值或余额校准" }
        return l.copy(payments=l.payments+receipt(p,amount,date,note,cnyAmount)).also(::validate)
    }
    /** Correct the receipt only: the recorded benefit term and account balance remain unchanged. */
    fun editPayment(l: Ledger, paymentId: String, amount: String, date: LocalDate, note: String, cnyAmount: String? = null): Ledger {
        val p=l.payments.single { it.id==paymentId }
        require(p.currency=="CNY" || !cnyAmount.isNullOrBlank()) { "请填写付款当天的实际人民币金额" }
        val revisedNote=note.trim()
        if(p.note in paymentTypes) require(revisedNote==p.note) { "话费记录的类型不能更改" }
        else require(revisedNote !in paymentTypes) { "不能将普通付款改为话费类型" }
        val revised=p.copy(amount=amount.trim(),date=date.toString(),note=revisedNote,cnyAmount=if(p.currency=="CNY") null else cnyAmount?.trim())
        return l.copy(payments=l.payments.map { if(it.id==paymentId) revised else it }).also(::validate)
    }
    /** restart=null restarts only when all selected benefits have expired; false pays the original next cycle. */
    fun renew(l: Ledger, planId: String, amount: String, date: LocalDate, selected: Set<String>, note: String, cnyAmount: String? = null, restart: Boolean? = null): Ledger {
        val p = l.plans.single { it.id == planId }
        require(p.balanceAccount == null) { "余额账户请记录充值" }
        require(selected.isNotEmpty()) { "请选择至少一项续费权益" }
        require(selected.all { id -> l.benefits.any { it.id == id && it.planId == planId } })
        val payment=receipt(p,amount,date,note,cnyAmount,selected)
        val reopening=restart ?: l.benefits.filter {it.id in selected}.all {expiry(it,p)<date}
        val updated=l.benefits.map { b ->
            when {
                b.id !in selected -> b
                reopening -> b.copy(anchor=date.toString(),renewals=1,giftDays=0)
                else -> b.copy(renewals=b.renewals+1)
            }
        }
        val renewedPlan=if(reopening) p.copy(billingAnchor=date.toString(),paidCycles=1) else p.copy(paidCycles=p.paidCycles+1)
        return l.copy(plans=l.plans.map {if(it.id==p.id) renewedPlan else it},benefits=updated,payments=l.payments+payment).also(::validate)
    }
    fun delete(l: Ledger, id: String, deletePayments: Boolean = false) = l.copy(
        plans = l.plans.filterNot { it.id == id },
        benefits = l.benefits.filterNot { it.planId == id },
        payments = if (deletePayments) l.payments.filterNot { it.planId == id } else l.payments
    )
    // Removing a receipt does not undo recorded renewals or a balance calibration/top-up.
    fun deletePayments(l: Ledger, ids: Set<String>) = l.copy(payments = l.payments.filterNot { it.id in ids })
    fun validate(l: Ledger) {
        require(l.plans.size <= 10000 && l.benefits.size <= 50000 && l.payments.size <= 100000) { "记录数量超过支持范围" }
        fun money(s: String) { require(s.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,4})?"))) { "金额格式错误（最多4位小数）" } }
        fun date(s: String) { require(LocalDate.parse(s).year in 1900..2200) { "日期应在1900至2200年" } }
        fun currency(s: String) { Currency.getInstance(s) }
        fun ids(xs: List<String>) { require(xs.all { it.isNotBlank() } && xs.distinct().size == xs.size) { "记录标识重复或缺失" } }
        require(l.devices.size <= 10000) { "设备数量超过支持范围" }
        ids(l.devices.map { it.id }); l.devices.forEach { Devices.validate(it) }
        ids(l.plans.map { it.id }); ids(l.benefits.map { it.id }); ids(l.payments.map { it.id })
        l.plans.forEach { require(it.name.isNotBlank() && it.name.length <= 100); money(it.amount); currency(it.currency); date(it.billingAnchor); require(it.interval in 1..120 && it.paidCycles in 0..10000) }
        l.benefits.forEach { b -> require(b.name.isNotBlank() && b.name.length <= 100 && l.plans.any { it.id == b.planId }); date(b.anchor); require(b.renewals in 0..10000 && b.giftDays in 0..36500); require(expiry(b, l.plans.single { it.id == b.planId }).year <= 2200) }
        l.plans.forEach { p ->
            if(p.balanceAccount == null) require(l.benefits.any { it.planId == p.id }) { "每个订阅至少需要一项权益" }
            else {
                val account=p.balanceAccount
                require(p.currency == "CNY" && p.cycle == Cycle.MONTH && p.interval == 1) { "余额账户按人民币月费管理" }
                money(account.balance.removePrefix("-"))
                date(account.asOf)
                require(LocalDate.parse(account.asOf)<=LocalDate.now()) { "余额查询日期不能晚于今天" }
            }
        }
        l.payments.forEach { money(it.amount); it.cnyAmount?.let(::money); currency(it.currency); date(it.date); require(it.planName.isNotBlank()) }
        require(l.settings.reminderDays.distinct().size == l.settings.reminderDays.size && l.settings.reminderDays.all { it in 0..365 }) { "提醒天数应为0至365且不能重复" }
        l.settings.rates.forEach { (c, r) -> currency(c); money(r); require(BigDecimal(r) > BigDecimal.ZERO) }
    }
    fun encode(l: Ledger): String { validate(l); return json.encodeToString(Backup(data = l)) }
    fun decode(s: String): Backup {
        require(s.toByteArray().size <= 16 * 1024 * 1024) { "备份超过16MB" }
        val root = json.parseToJsonElement(s).jsonObject
        fun fields(obj: JsonObject, names: String) { require(obj.keys.containsAll(names.split(","))) { "备份缺少必要字段" } }
        fields(root,"version,createdAt,data")
        val data = root.getValue("data").jsonObject
        fields(data,"plans,benefits,payments,settings")
        fields(data.getValue("settings").jsonObject,"reminderDays,rates")
        data.getValue("plans").jsonArray.forEach { fields(it.jsonObject,"id,name,amount,currency,cycle,interval,autoRenew,billingAnchor,paidCycles,archived,note") }
        data.getValue("benefits").jsonArray.forEach { fields(it.jsonObject,"id,planId,name,anchor,renewals,giftDays") }
        data.getValue("payments").jsonArray.forEach { fields(it.jsonObject,"id,planId,planName,amount,currency,date,note,benefitIds") }
        return json.decodeFromString<Backup>(s).also { require(it.version == 1) { "不支持的备份版本" }; Instant.parse(it.createdAt); validate(it.data) }
    }
    fun reminderKey(b: Benefit, p: Plan, today: LocalDate) = "${b.id}:${expiry(b,p)}:$today"
    fun due(l: Ledger, today: LocalDate) = l.benefits.filter { b ->
        val p = l.plans.single { it.id == b.planId }
        !p.archived && p.balanceAccount == null && java.time.temporal.ChronoUnit.DAYS.between(today, expiry(b, p)).toInt() in l.settings.reminderDays
    }
}
