package cn.renewboard

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ReminderEvent(val key: String, val planId: String, val name: String, val planName: String,
                         val date: LocalDate, val balance: Boolean = false) {
    fun deliveryKey(today: LocalDate) = "$key:$today"
    val catchUpKey get() = "$key:catchup"
}

object ReminderPolicy {
    const val CATCH_UP_DAYS = 7L

    fun events(l: Ledger): List<ReminderEvent> = l.plans.filterNot { it.archived }.flatMap { p ->
        if (p.balanceAccount != null) listOfNotNull(Prepaid.rechargeDate(p)?.let {
            ReminderEvent("balance:${p.id}:$it", p.id, p.name, p.name, it, balance = true)
        }) else l.benefits.filter { it.planId == p.id }.map { b ->
            val date = Book.expiry(b, p)
            ReminderEvent("${b.id}:$date", p.id, b.name, p.name, date)
        }
    }

    fun scheduled(l: Ledger, today: LocalDate) = events(l).filter {
        ChronoUnit.DAYS.between(today, it.date).toInt() in l.settings.reminderDays
    }

    // A missed scheduled day gets one bounded catch-up per unchanged expiry event.
    // Existing pre-upgrade delivery keys also count, so installing an update is quiet.
    fun missed(l: Ledger, today: LocalDate, delivered: Set<String>): List<ReminderEvent> = events(l).filter { e ->
        e.date >= today.minusDays(CATCH_UP_DAYS) &&
            ChronoUnit.DAYS.between(today, e.date).toInt() !in l.settings.reminderDays &&
            l.settings.reminderDays.any { days ->
                val scheduled = e.date.minusDays(days.toLong())
                scheduled < today && scheduled >= today.minusDays(CATCH_UP_DAYS)
            } && delivered.none { it.startsWith("${e.key}:") }
    }

    fun snoozeTargets(l: Ledger, planId: String, today: LocalDate): List<ReminderEvent> {
        val all = events(l).filter { it.planId == planId }
        val relevant = all.filter { it.date <= today || ChronoUnit.DAYS.between(today, it.date).toInt() in l.settings.reminderDays }
        return relevant.ifEmpty { all.minOfOrNull { it.date }?.let { day -> all.filter { it.date == day } }.orEmpty() }
    }

    fun unresolved(l: Ledger, keys: Collection<String>, today: LocalDate = LocalDate.now()): List<ReminderEvent> {
        val current = events(l).associateBy { it.key }
        return keys.distinct().mapNotNull { key ->
            current[key] ?: l.plans.find { p ->
                p.balanceAccount != null && !p.archived && key.startsWith("balance:${p.id}:")
            }?.let { p ->
                val originalDate = runCatching { LocalDate.parse(key.substringAfterLast(':')) }.getOrNull()
                val day = maxOf(today, LocalDate.parse(p.balanceAccount!!.asOf))
                // A small top-up can move the forecast's anchor without paying off existing arrears.
                if (originalDate != null && originalDate <= day && Prepaid.balance(p, day).signum() < 0)
                    ReminderEvent(key, p.id, p.name, p.name, originalDate, balance = true)
                else null
            }
        }
    }
    fun summary(events: List<ReminderEvent>) = events.joinToString("；") {
        if (it.balance) "${it.name} · 预计 ${it.date} 余额不足" else "${it.name} · ${it.date} 到期"
    }
}
