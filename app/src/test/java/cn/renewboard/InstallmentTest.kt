package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class InstallmentTest {
    private val first = LocalDate.of(2026, 1, 31)
    private val plan = Plan(name = "示例相机", amount = "125.50", billingAnchor = first.toString(),
        installmentPeriods = 3, paidCycles = 1)
    private val ledger = Ledger(plans = listOf(plan))

    @Test fun latePaymentMovesOnePeriodWithoutMovingTheMonthlyAnchor() {
        val overdue = LocalDate.of(2026, 3, 1)
        assertEquals(listOf(first.plusMonths(1)), ReminderPolicy.dueItems(ledger).map { it.date })
        assertTrue(ledger.payments.isEmpty())
        assertEquals(listOf(first.plusMonths(1), first.plusMonths(2)),
            Book.forecastCharges(ledger, first, first.plusYears(1)).map { it.date })

        val paid = Book.payInstallment(ledger, plan.id, overdue)
        assertEquals(overdue.toString(), paid.payments.single().date)
        assertEquals("125.50", paid.payments.single().amount)
        assertEquals(first.plusMonths(2), Book.installmentDue(paid.plans.single()))
        assertEquals(3, ReminderPolicy.scheduled(paid, first.plusMonths(2)).single().installmentNumber)
        val done = Book.payInstallment(paid, plan.id, overdue.plusDays(1))
        assertEquals(2, done.payments.size)
        assertTrue(ReminderPolicy.events(done).isEmpty())
        assertTrue(Book.forecastCharges(done, overdue, overdue.plusYears(1)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { Book.payInstallment(done, plan.id, overdue) }
        val backup = Book.decode(Book.encode(done))
        assertEquals(4, backup.version)
        assertEquals(done, backup.data)
    }

    @Test fun archiveAndInvalidTermsCannotProduceInstallmentPayments() {
        val archived = ledger.copy(plans = listOf(plan.copy(archived = true)))
        assertTrue(ReminderPolicy.events(archived).isEmpty())
        assertTrue(Book.forecastCharges(archived, first, first.plusYears(1)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { Book.payInstallment(archived, plan.id, first) }
        for (invalid in listOf(plan.copy(paidCycles = 4), plan.copy(amount = "0"),
                plan.copy(installmentPeriods = 0), plan.copy(billingAnchor = "2200-12-31"))) {
            assertThrows(IllegalArgumentException::class.java) { Book.validate(Ledger(plans = listOf(invalid))) }
        }
    }
}
