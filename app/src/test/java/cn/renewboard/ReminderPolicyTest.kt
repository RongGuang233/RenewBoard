package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReminderPolicyTest {
    private val today = LocalDate.of(2026, 9, 8)
    private val plan = Plan(id = "p", name = "联合套餐", amount = "30", billingAnchor = "2026-08-08")
    private fun ledger(vararg dates: LocalDate) = Ledger(plans = listOf(plan), benefits = dates.mapIndexed { i, date ->
        Benefit(id = "b$i", planId = plan.id, name = "权益$i", anchor = date.toString())
    })

    @Test fun missedRecentEventsAreMergedCandidatesAndStayQuietAfterDelivery() {
        val l = ledger(today.minusDays(1), today.minusDays(4), today.minusDays(8))
        val missed = ReminderPolicy.missed(l, today, emptySet())
        assertEquals(listOf("b0", "b1"), missed.map { it.key.substringBefore(':') })
        val delivered = missed.map { it.catchUpKey }.toSet()
        assertTrue(ReminderPolicy.missed(l, today.plusDays(1), delivered).isEmpty())
        assertTrue(ReminderPolicy.missed(l.copy(settings = Settings(emptyList())), today, emptySet()).isEmpty())
    }

    @Test fun earlierDeliverySuppressesCatchUpButKeepsTheConfiguredExpiryDay() {
        val l = ledger(today)
        val event = ReminderPolicy.events(l).single()
        val delivered = setOf(event.deliveryKey(today.minusDays(3)))
        assertEquals(listOf(event), ReminderPolicy.scheduled(l, today))
        assertTrue(ReminderPolicy.missed(l, today.plusDays(1), delivered).isEmpty())
    }

    @Test fun missedAdvanceReminderCatchesUpBeforeExpiryWithinSevenDaysOnly() {
        val l = ledger(today.plusDays(2), today.plusDays(20)).copy(settings = Settings(listOf(3, 30)))
        assertEquals(listOf("b0"), ReminderPolicy.missed(l, today, emptySet()).map { it.key.substringBefore(':') })
    }

    @Test fun partialBundleRenewalKeepsOnlyUnchangedSnoozeTargetAndNewExpiryCanRemind() {
        val l = ledger(today, today)
        val keys = ReminderPolicy.snoozeTargets(l, plan.id, today).map { it.key }
        val renewed = l.copy(benefits = l.benefits.map { if (it.id == "b0") it.copy(renewals = 1) else it })
        assertEquals(listOf("b1"), ReminderPolicy.unresolved(renewed, keys, today).map { it.key.substringBefore(':') })
        assertEquals(listOf("b0"), ReminderPolicy.scheduled(renewed, today.plusMonths(1)).map { it.key.substringBefore(':') })
        val allRenewed = renewed.copy(benefits = renewed.benefits.map { it.copy(renewals = 1) })
        assertTrue(ReminderPolicy.unresolved(allRenewed, keys, today).isEmpty())
    }

    @Test fun archivingDeletingAndChangingExpiryInvalidateOldTargets() {
        val l = ledger(today)
        val keys = ReminderPolicy.events(l).map { it.key }
        assertTrue(ReminderPolicy.unresolved(l.copy(plans = listOf(plan.copy(archived = true))), keys, today).isEmpty())
        assertTrue(ReminderPolicy.unresolved(Ledger(), keys, today).isEmpty())
        val edited = l.copy(benefits = l.benefits.map { it.copy(giftDays = 1) })
        assertTrue(ReminderPolicy.unresolved(edited, keys, today).isEmpty())
        assertEquals(1, ReminderPolicy.scheduled(edited, today.plusDays(1)).size)
    }

    @Test fun sufficientBalanceInvalidatesOldReminderButUnpaidArrearsRetainIt() {
        val phone = plan.copy(balanceAccount = BalanceAccount("-30", today.toString()))
        val l = Ledger(plans = listOf(phone))
        val keys = ReminderPolicy.events(l).map { it.key }
        val stillOwing = Ledger(plans = listOf(phone.copy(balanceAccount = BalanceAccount("-10", today.plusDays(1).toString()))))
        assertEquals(keys, ReminderPolicy.unresolved(stillOwing, keys, today.plusDays(1)).map { it.key })
        val paid = Ledger(plans = listOf(phone.copy(balanceAccount = BalanceAccount("60", today.plusDays(1).toString()))))
        assertTrue(ReminderPolicy.unresolved(paid, keys, today.plusDays(1)).isEmpty())
    }
}
