package cn.renewboard

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpdateRetryDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var context:Context
    private val finishCheck=CountDownLatch(1)
    @Before fun seedFailure() {
        context=ApplicationProvider.getApplicationContext()
        check(context.packageName.endsWith(".debug"))
        UpdateDownload(context).cancel()
        context.getSharedPreferences("app-update",Context.MODE_PRIVATE).edit()
            .putLong("id",Long.MAX_VALUE).putString("version","99.0.0").commit()
    }
    @After fun clean() {finishCheck.countDown();UpdateDownload(context).cancel()}

    @Test fun failedDownloadRetryImmediatelyChecksOnceAndShowsResult() {
        val calls=AtomicInteger()
        compose.setContent {MaterialTheme {Column {UpdateSection {
            calls.incrementAndGet()
            check(finishCheck.await(10,TimeUnit.SECONDS))
            AppUpdate("99.0.0","测试新版说明",true)
        }}}}
        compose.onNodeWithText("下载失败或安装包已移除，请重新下载。").assertExists()
        compose.onNodeWithText("重新检查更新").performClick()
        compose.waitUntil(5000) {calls.get()==1}
        compose.onNodeWithText("正在检查…").assertIsNotEnabled()
        assertNull(UpdateDownload(context).current())
        finishCheck.countDown()
        compose.waitUntil(5000) {compose.onAllNodesWithText("发现新版本 99.0.0").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("测试新版说明").assertExists()
        assertEquals(1,calls.get())
        compose.onNodeWithText("稍后").performClick()
    }

    @Test fun failedRecheckShowsErrorAndNormalCheckCanRecover() {
        val calls=AtomicInteger()
        compose.setContent {MaterialTheme {Column {UpdateSection {
            if(calls.incrementAndGet()==1) throw IOException("offline fixture")
            AppUpdate(BuildConfig.VERSION_NAME,"",false)
        }}}}
        compose.onNodeWithText("重新检查更新").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("无法检查更新，请检查网络后重试。").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("检查更新").assertIsEnabled().performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("当前已是最新版本").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(2,calls.get())
        compose.onNodeWithText("知道了").performClick()
    }
}
