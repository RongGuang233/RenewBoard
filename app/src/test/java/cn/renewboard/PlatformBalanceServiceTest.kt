package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PlatformBalanceServiceTest {
    private val plan = Plan(id="platform", name="DeepSeek API", amount="0", billingAnchor="2026-10-01",
        autoRenew=false, usageBalance=UsageBalance("50", LocalDate.now().minusDays(1).toString()))
    private val snapshot = DeepSeekBalanceSnapshot("CNY", "110", "10", "100", Instant.now().minusSeconds(1))
    private val receipt = Payment(id="top-up", planId="platform", planName="DeepSeek API", amount="50", currency="CNY", date="2026-10-01", note="平台充值")

    @Test fun refreshChangesOnlyBalanceAndDateAndPreservesRenamedAccountAndReceipts() {
        val renamed = plan.copy(name="我的 AI 余额")
        val book = Ledger(plans=listOf(renamed), payments=listOf(receipt))
        val next = applyPlatformBalance(book, plan, snapshot)
        assertEquals("我的 AI 余额", next.plans.single().name)
        assertEquals("110", next.plans.single().usageBalance!!.balance)
        assertEquals(snapshot.queriedAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(), next.plans.single().usageBalance!!.asOf)
        assertEquals(book.payments, next.payments)
        assertEquals(book.benefits, next.benefits)
        assertEquals(book.settings, next.settings)
    }

    @Test fun rejectsConcurrentBalanceCurrencyArchiveOrDeletionChanges() {
        listOf(
            Ledger(),
            Ledger(plans=listOf(plan.copy(archived=true))),
            Ledger(plans=listOf(plan.copy(currency="USD"))),
            Ledger(plans=listOf(plan.copy(usageBalance=UsageBalance("80", "2026-10-02")))),
            Ledger(plans=listOf(plan.copy(usageBalance=null)))
        ).forEach { book ->
            assertTrue(runCatching { applyPlatformBalance(book, plan, snapshot) }.exceptionOrNull() is PlatformBalanceException)
            assertEquals(book.payments, emptyList<Payment>())
        }
    }

    @Test fun mismatchedSnapshotCurrencyDoesNotUpdateAccount() {
        val book = Ledger(plans=listOf(plan), payments=listOf(receipt))
        assertTrue(runCatching { applyPlatformBalance(book, plan, snapshot.copy(currency="USD")) }.exceptionOrNull() is PlatformBalanceException)
        assertEquals("50", book.plans.single().usageBalance!!.balance)
        assertEquals(listOf(receipt), book.payments)
    }

    @Test fun injectedInvalidSnapshotCannotChangeBalance() {
        val book = Ledger(plans=listOf(plan), payments=listOf(receipt))
        listOf(snapshot.copy(totalBalance="-1"), snapshot.copy(grantedBalance="bad"),
            snapshot.copy(toppedUpBalance="-0.01"), snapshot.copy(queriedAt=Instant.parse("2000-01-01T00:00:00Z")),
            snapshot.copy(queriedAt=Instant.now().plusSeconds(60)))
            .forEach { invalid ->
                assertTrue(runCatching { applyPlatformBalance(book, plan, invalid) }.exceptionOrNull() is PlatformBalanceException)
            }
        assertEquals("50", book.plans.single().usageBalance!!.balance)
    }
}
