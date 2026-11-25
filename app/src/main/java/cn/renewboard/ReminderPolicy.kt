package cn.renewboard

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ReminderEvent(val key: String, val planId: String, val name: String, val planName: String,
                         val date: LocalDate, val balance: Boolean = false, val installmentNumber: Int? = null) {
    fun deliveryKey(today: LocalDate) = "$key:$today"
    val catchUpKey get() = "$key:catchup"
}

object ReminderPolicy {
    const val CATCH_UP_DAYS = 7L

    fun dueItems(l: Ledger): List<ReminderEvent> {
        val active = l.plans.filter { !it.archived && it.balanceAccount == null && it.usageBalance == null }
        val plans = active.associateBy { it.id }
        val expiries = l.benefits.mapNotNull { b ->
            val p = plans[b.planId] ?: return@mapNotNull null
            val date = Book.expiry(b, p)
            ReminderEvent("${b.id}:$date", p.id, b.name, p.name, date)
        }
        val installments = active.mapNotNull { p ->
            val date = Book.installmentDue(p) ?: return@mapNotNull null
            ReminderEvent("installment:${p.id}:${p.paidCycles}:$date", p.id, p.name, p.name, date,
                installmentNumber = p.paidCycles + 1)
        }
        return expiries + installments
    }

    fun events(l: Ledger): List<ReminderEvent> = dueItems(l) + l.plans.mapNotNull { p ->
        if (p.archived || p.balanceAccount == null) return@mapNotNull null
        Prepaid.rechargeDate(p)?.let { ReminderEvent("balance:${p.id}:$it", p.id, p.name, p.name, it, balance = true) }
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
        when {
            it.balance -> "${it.name} · 预计 ${it.date} 余额不足"
            it.installmentNumber != null -> "${it.name} · ${it.date} 应还第 ${it.installmentNumber} 期"
            else -> "${it.name} · ${it.date} 到期"
        }
    }
}
