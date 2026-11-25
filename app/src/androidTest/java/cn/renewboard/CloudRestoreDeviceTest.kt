package cn.renewboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudRestoreDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstConnectionPreviewsCancelsAndRestoresOnlyAfterConfirmation() {
        val app = ApplicationProvider.getApplicationContext<RenewApp>()
        check(app.packageName.endsWith(".debug") && !CredentialsStore(app).configured)
        val before = Ledger(settings = Settings(reminderDays = listOf(7)))
        val plan = Plan(name = "云端测试订阅", amount = "16", billingAnchor = "2025-01-01")
        val backup = Ledger(plans = listOf(plan), benefits = listOf(
            Benefit(planId = plan.id, name = "测试权益", anchor = plan.billingAnchor)
        ))
        val filename = "manual-00000000-0000-0000-0000-000000000001.json"
        val listing = """<d:multistatus xmlns:d="DAV:"><d:response>
            <d:href>/dav/RenewBoard/$filename</d:href></d:response></d:multistatus>"""
        val calls = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.method
            val (code, body) = when (request.method to request.url.encodedPath) {
                "PROPFIND" to "/dav/RenewBoard/" -> 207 to listing
                "GET" to "/dav/RenewBoard/$filename" -> 200 to Book.encode(backup)
                else -> 405 to ""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        val originalRepository = app.repository
        val db = Room.inMemoryDatabaseBuilder(app, BookDatabase::class.java).build()
        val repository = Repository(db)
        val messages = CopyOnWriteArrayList<String>()
        try {
            app.repository = repository
            runBlocking { repository.update { before } }
            compose.setContent {
                MaterialTheme {
                    SettingsScreen(before, message = { messages += it }, oneTimeWebDav = { url, user, password ->
                        WebDav(url, user, password, http)
                    })
                }
            }
            compose.onNodeWithText("云端备份").performScrollTo().performClick()
            fun fill(label: String, value: String) {
                compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
            }
            fill("WebDAV 地址", "https://example.invalid/dav/")
            fill("坚果云账号", "fixture")
            fill("专用应用密码", "fixture-password")
            compose.onNodeWithText("从云端恢复备份").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("手动备份").fetchSemanticsNodes().isNotEmpty() }
            fun preview() {
                compose.onNodeWithText("手动备份").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("确认替换本机数据？").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("1 个订阅 · 1 项权益", substring = true).assertIsDisplayed()
            }
            preview()
            assertEquals(before, runBlocking { repository.read() })
            compose.onNodeWithText("取消").performClick()
            assertEquals(before, runBlocking { repository.read() })
            preview()
            compose.onNodeWithText("确认替换").performClick()
            compose.waitUntil(10_000) { "恢复完成" in messages }
            assertEquals(backup, runBlocking { repository.read() })
            assertEquals(listOf("PROPFIND", "GET", "GET"), calls.toList())
            assertFalse(CredentialsStore(app).configured)
        } finally {
            app.repository = originalRepository
            db.close()
        }
    }
}
