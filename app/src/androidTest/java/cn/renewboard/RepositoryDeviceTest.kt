package cn.renewboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the Android SQLite/Room implementation, rather than an in-memory fake. */
@RunWith(AndroidJUnit4::class)
class RepositoryDeviceTest {
    private lateinit var context: Context
    private lateinit var database: BookDatabase
    private lateinit var repository: Repository
    private val databaseName = "instrumentation-${UUID.randomUUID()}.db"
    private var changeCount = 0

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, BookDatabase::class.java, databaseName).build()
        repository = Repository(database) { changeCount++ }
    }

    @After fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun emptyDatabaseThenSavedLedgerSurvivesCloseAndReopen() = runBlocking {
        assertEquals(Ledger(), repository.read())
        assertEquals(Ledger(), repository.flow.first())
        val ledger = deviceLedger()
        repository.update { ledger }
        assertEquals(1, changeCount)
        database.close()
        openDatabase()
        assertEquals(ledger, repository.read())
        assertEquals(ledger, repository.flow.first())
    }

    @Test fun editArchiveAndDeletePreserveActualReceipts() = runBlocking {
        val original = deviceLedger()
        repository.update { original }
        repository.update { l -> l.copy(plans = l.plans.map { it.copy(name = "更新后的套餐", amount = "88.50") }) }
        assertEquals("更新后的套餐", repository.read().plans.single().name)
        assertEquals(original.payments, repository.read().payments)
        repository.update { l -> l.copy(plans = l.plans.map { it.copy(archived = true) }) }
        assertTrue(repository.read().plans.single().archived)
        assertEquals(original.benefits, repository.read().benefits)
        repository.update { Book.delete(it, "plan-device") }
        database.close()
        openDatabase()
        val deleted = repository.read()
        assertTrue(deleted.plans.isEmpty())
        assertTrue(deleted.benefits.isEmpty())
        assertEquals(original.payments, deleted.payments)
    }

    @Test fun invalidUpdatesAndDamagedBackupsLeaveLocalLedgerUntouched() = runBlocking {
        val local = deviceLedger()
        repository.update { local }
        val invalid = local.copy(benefits = emptyList())
        try {
            repository.update { invalid }
            fail("Invalid ledger must not be committed")
        } catch (_: IllegalArgumentException) { }
        try {
            repository.restore(Backup(data = invalid))
            fail("Invalid backup must not be committed")
        } catch (_: IllegalArgumentException) { }
        for (text in listOf("{broken", Book.encode(local).replace("\"version\":1", "\"version\":99"))) {
            try {
                repository.restore(Book.decode(text))
                fail("Damaged or unsupported backup must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, changeCount)
        database.close()
        openDatabase()
        assertEquals(local, repository.read())
    }

    @Test fun validBackupAtomicallyReplacesAllLedgerSections() = runBlocking {
        repository.update { deviceLedger() }
        val replacement = Ledger(settings = Settings(listOf(7, 1), mapOf("USD" to "7.2")))
        repository.restore(Book.decode(Book.encode(replacement)))
        database.close()
        openDatabase()
        assertEquals(replacement, repository.read())
        repository.restore(Book.decode(Book.encode(deviceLedger())))
        assertEquals(deviceLedger(), repository.read())
    }

    @Test fun concurrentReminderClaimsDeduplicateAndPersistUntilReleased() = runBlocking {
        val event = ReminderRow("benefit-device:2026-09-09:2026-09-06", "2026-09-06")
        val results = coroutineScope {
            List(12) { async(Dispatchers.IO) { database.book().claim(event) } }.awaitAll()
        }
        assertEquals(1, results.count { it != -1L })
        database.close()
        openDatabase()
        assertEquals(-1L, database.book().claim(event))
        assertTrue(database.book().claim(event.copy(event = "benefit-device:2026-09-10:2026-09-06")) != -1L)
        database.book().release(event.event)
        assertTrue(database.book().claim(event) != -1L)
        database.book().prune("2026-09-07")
        assertTrue(database.book().claim(event) != -1L)
    }
}

internal fun deviceLedger(): Ledger {
    val plan = Plan(id = "plan-device", name = "设备测试套餐", amount = "29.90", billingAnchor = "2026-09-01")
    val benefit = Benefit(id = "benefit-device", planId = plan.id, name = "设备测试权益", anchor = "2026-10-01")
    val payment = Payment(id = "payment-device", planId = plan.id, planName = plan.name,
        amount = plan.amount, currency = "CNY", date = "2026-09-01", benefitIds = listOf(benefit.id))
    return Ledger(listOf(plan), listOf(benefit), listOf(payment))
}
