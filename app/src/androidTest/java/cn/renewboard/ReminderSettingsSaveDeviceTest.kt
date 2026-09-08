package cn.renewboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises failed and delayed Room writes without credentials or remote calls. */
@RunWith(AndroidJUnit4::class)
class ReminderSettingsSaveDeviceTest {
    @get:Rule val compose=createComposeRule()

    @Test fun choicesStayUnselectedOnFailureAndUntilCommitCompletes():Unit = runBlocking {
        val app=ApplicationProvider.getApplicationContext<RenewApp>()
        check(app.packageName.endsWith(".debug"))
        val original=app.repository
        val failedDatabase=Room.inMemoryDatabaseBuilder(app,BookDatabase::class.java).build()
        val database=Room.inMemoryDatabaseBuilder(app,BookDatabase::class.java).build()
        val initial=Ledger(settings=Settings(reminderDays=listOf(0,3)))
        val observed=Repository(database)
        observed.update {initial}
        val messages=mutableListOf<String>()
        val commit=CompletableDeferred<Unit>()
        var held:kotlinx.coroutines.Job?=null
        try {
            app.repository=Repository(failedDatabase)
            app.repository.update {initial}
            compose.setContent {
                val ledger by observed.flow.collectAsState(initial=initial)
                MaterialTheme { SettingsScreen(ledger, message={messages+=it}) }
            }
            compose.onNodeWithText("到期提醒",substring=false).performClick()
            // Room can reopen a closed database; reject the actual write inside SQLite instead.
            failedDatabase.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_reminder BEFORE INSERT ON BookRow BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
            compose.onNodeWithText("提前 7 天").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("提醒保存失败，请重试").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("提醒保存失败，请重试").assertExists()
            compose.onNodeWithText("提前 7 天").assertIsNotSelected()
            assertTrue(messages.isEmpty())

            app.repository=observed
            val started=CompletableDeferred<Unit>()
            held=launch(Dispatchers.IO) {
                database.withTransaction { started.complete(Unit);commit.await() }
            }
            started.await()
            compose.onNodeWithText("提前 7 天").performClick()
            compose.onNodeWithText("提前 7 天").assertIsNotSelected().assertIsNotEnabled()
            compose.onNodeWithText("返回",substring=false).performClick()
            compose.onNodeWithText("提醒时间",substring=false).assertExists()
            assertTrue(messages.isEmpty())
            commit.complete(Unit)
            held.join()
            compose.waitUntil(10000) {messages.contains("提醒已保存")}
            assertEquals(listOf(0,3,7),app.repository.read().settings.reminderDays)
            compose.onNodeWithText("提前 7 天").assertIsSelected().assertIsEnabled()
        } finally {
            commit.complete(Unit);held?.join()
            app.repository=original
            failedDatabase.close();database.close()
        }
    }
}
