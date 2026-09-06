package cn.renewboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

fun newId() = UUID.randomUUID().toString()
@Serializable enum class Cycle(val label: String) { WEEK("周"), MONTH("月"), YEAR("年") }
@Serializable data class Plan(
    val id: String = newId(), val name: String, val amount: String, val currency: String = "CNY",
    val cycle: Cycle = Cycle.MONTH, val interval: Int = 1, val autoRenew: Boolean = true,
    val billingAnchor: String, val paidCycles: Int = 1, val archived: Boolean = false, val note: String = ""
)
@Serializable data class Benefit(
    val id: String = newId(), val planId: String, val name: String,
    val anchor: String, val renewals: Int = 0, val giftDays: Int = 0
)
@Serializable data class Payment(
    val id: String = newId(), val planId: String, val planName: String, val amount: String,
    val currency: String, val date: String, val note: String = "", val benefitIds: List<String> = emptyList()
)
@Serializable data class Settings(val reminderDays: List<Int> = listOf(3, 0), val rates: Map<String, String> = emptyMap())
@Serializable data class Ledger(val plans: List<Plan> = emptyList(), val benefits: List<Benefit> = emptyList(), val payments: List<Payment> = emptyList(), val settings: Settings = Settings())
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
        l.plans.filter { it.autoRenew && !it.archived }.forEach { p ->
            var date = nextCharge(p, from)
            var index = p.paidCycles.toLong()
            while (advance(LocalDate.parse(p.billingAnchor), p.cycle, index * p.interval) < date) index++
            while (date < until) {
                totals[p.currency] = (totals[p.currency] ?: BigDecimal.ZERO) + BigDecimal(p.amount)
                index++; date = advance(LocalDate.parse(p.billingAnchor), p.cycle, index * p.interval)
            }
        }
        return totals
    }
    fun paid(l: Ledger, from: LocalDate? = null, until: LocalDate? = null): Map<String, BigDecimal> =
        l.payments.filter { (from == null || LocalDate.parse(it.date) >= from) && (until == null || LocalDate.parse(it.date) < until) }
            .groupBy { it.currency }.mapValues { (_, ps) -> ps.fold(BigDecimal.ZERO) { a, p -> a + BigDecimal(p.amount) } }
    fun estimate(totals: Map<String, BigDecimal>, rates: Map<String, String>): BigDecimal? {
        if (totals.keys.any { it != "CNY" && !rates.containsKey(it) }) return null
        return totals.entries.fold(BigDecimal.ZERO) { a, (c, v) -> a + v * if (c == "CNY") BigDecimal.ONE else BigDecimal(rates.getValue(c)) }
    }
    fun renew(l: Ledger, planId: String, amount: String, date: LocalDate, selected: Set<String>, note: String): Ledger {
        val p = l.plans.single { it.id == planId }
        require(selected.isNotEmpty()) { "请选择至少一项续费权益" }
        require(selected.all { id -> l.benefits.any { it.id == id && it.planId == planId } })
        val updated = l.benefits.map { b ->
            if (b.id !in selected) b else if (expiry(b, p) < date) b.copy(anchor = date.toString(), renewals = 1, giftDays = 0) else b.copy(renewals = b.renewals + 1)
        }
        // A prepaid package advances the original billing schedule; money is recorded only once.
        var index = p.paidCycles
        while (advance(LocalDate.parse(p.billingAnchor), p.cycle, index.toLong() * p.interval) < date) index++
        return l.copy(plans = l.plans.map { if (it.id == p.id) it.copy(paidCycles = index + 1) else it }, benefits = updated,
            payments = l.payments + Payment(planId = p.id, planName = p.name, amount = amount, currency = p.currency, date = date.toString(), note = note, benefitIds = selected.toList())).also(::validate)
    }
    fun delete(l: Ledger, id: String) = l.copy(plans = l.plans.filterNot { it.id == id }, benefits = l.benefits.filterNot { it.planId == id }) // Preserve actual receipts.
    fun validate(l: Ledger) {
        require(l.plans.size <= 10000 && l.benefits.size <= 50000 && l.payments.size <= 100000) { "记录数量超过支持范围" }
        fun money(s: String) { require(s.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,4})?"))) { "金额格式错误（最多4位小数）" } }
        fun date(s: String) { require(LocalDate.parse(s).year in 1900..2200) { "日期应在1900至2200年" } }
        fun currency(s: String) { Currency.getInstance(s) }
        fun ids(xs: List<String>) { require(xs.all { it.isNotBlank() } && xs.distinct().size == xs.size) { "记录标识重复或缺失" } }
        ids(l.plans.map { it.id }); ids(l.benefits.map { it.id }); ids(l.payments.map { it.id })
        l.plans.forEach { require(it.name.isNotBlank() && it.name.length <= 100); money(it.amount); currency(it.currency); date(it.billingAnchor); require(it.interval in 1..120 && it.paidCycles in 0..10000) }
        l.benefits.forEach { b -> require(b.name.isNotBlank() && b.name.length <= 100 && l.plans.any { it.id == b.planId }); date(b.anchor); require(b.renewals in 0..10000 && b.giftDays in 0..36500); require(expiry(b, l.plans.single { it.id == b.planId }).year <= 2200) }
        l.plans.forEach { p -> require(l.benefits.any { it.planId == p.id }) { "每个订阅至少需要一项权益" } }
        l.payments.forEach { money(it.amount); currency(it.currency); date(it.date); require(it.planName.isNotBlank()) }
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
        !p.archived && java.time.temporal.ChronoUnit.DAYS.between(today, expiry(b, p)).toInt() in l.settings.reminderDays
    }
}
