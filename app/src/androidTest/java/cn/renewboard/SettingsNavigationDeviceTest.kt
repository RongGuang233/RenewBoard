package cn.renewboard

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings acceptance uses only the debug ledger and local backup status; no remote operations. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun back() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    @Test fun settingsShowCompactEntriesAndSubpagesReturnOneLevel() {
        check(compose.activity.packageName.endsWith(".debug"))
        compose.onNodeWithText("设置", substring = false).performClick()
        compose.waitForIdle()
        compose.waitForIdle(); android.os.SystemClock.sleep(400)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.filesDir,"settings-home.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        compose.onNodeWithText("WebDAV 地址").assertDoesNotExist()
        compose.onNodeWithText("保存提醒").assertDoesNotExist()
        compose.onNodeWithText("导出 JSON").assertDoesNotExist()

        compose.onNodeWithText("坚果云 · WebDAV").performScrollTo().performClick()
        compose.onNode(hasText("WebDAV 地址") and hasSetTextAction()).assertExists()
        compose.onNodeWithText("返回", substring = false).assertIsDisplayed()
        compose.onNodeWithText("订阅", substring = false).assertDoesNotExist()
        back()
        compose.onNodeWithText("WebDAV 地址").assertDoesNotExist()
        compose.onNodeWithText("订阅", substring = false).assertIsDisplayed()

        compose.onNodeWithText("本地备份", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("从文件恢复", substring = false).performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("本地备份", substring = false).assertExists()
        compose.onNodeWithText("返回", substring = false).performClick()

        compose.onNodeWithText("更新与关于").performScrollTo().performClick()
        compose.onNodeWithText("作者 RongGuang233", substring = false).assertIsDisplayed()
        compose.onNodeWithText("当前版本 ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.waitForIdle()
        val aboutBitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.filesDir, "settings-about.png").outputStream().use {
            aboutBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        aboutBitmap.recycle()
        compose.onNodeWithText("开源许可证").performScrollTo().performClick()
        back()
        compose.onNodeWithText("更新与关于").assertExists()
        compose.onNodeWithText("作者 RongGuang233", substring = false).assertIsDisplayed()
        try {compose.onNodeWithText("检查更新", substring = false).assertExists()}
        catch(e:AssertionError) {throw AssertionError(compose.onRoot(useUnmergedTree=true).printToString(),e)}
        back()
        compose.onNodeWithText("本地备份", substring = false).assertExists()
    }
    @Test fun reminderChoicesPersistCustomDaysAndRefreshPermissionAfterResume() {
        check(compose.activity.packageName.endsWith(".debug"))
        val repository=compose.activity.repository()
        val original=kotlinx.coroutines.runBlocking {repository.read()}
        try {
            kotlinx.coroutines.runBlocking {repository.update {it.copy(settings=it.settings.copy(reminderDays=listOf(0,3)))}}
            compose.waitForIdle()
            compose.onNodeWithText("设置",substring=false).performClick()
            compose.onNodeWithText("到期提醒",substring=false).performClick()
            compose.onNodeWithText("当天",substring=false).assertIsSelected()
            compose.onNodeWithText("提前 1 天").performClick()
            compose.waitUntil(10000) {kotlinx.coroutines.runBlocking {repository.read()}.settings.reminderDays==listOf(0,1,3)}
            back()
            compose.onNodeWithText("到期提醒",substring=false).performClick()
            compose.onNodeWithText("提前 1 天").assertIsSelected()
            compose.onNode(hasText("自定义提前天数") and hasSetTextAction()).performScrollTo().performTextReplacement("14")
            compose.onNodeWithText("添加",substring=false).performScrollTo().performClick()
            compose.onNodeWithText("保存提醒").assertDoesNotExist()
            compose.waitUntil(10000) {kotlinx.coroutines.runBlocking {repository.read()}.settings.reminderDays==listOf(0,1,3,14)}
            compose.onNodeWithText("提前 14 天").assertIsSelected()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("提前 14 天").performScrollTo().performClick()
            compose.onNodeWithText("提前 14 天").assertDoesNotExist()
            compose.waitUntil(10000) {kotlinx.coroutines.runBlocking {repository.read()}.settings.reminderDays==listOf(0,1,3)}
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            val allowed=androidx.core.app.NotificationManagerCompat.from(compose.activity).areNotificationsEnabled()
            compose.onNodeWithText(if(allowed) "系统通知：已开启" else "系统通知：未开启").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(if(allowed) "管理通知设置" else "前往开启通知").assertExists()
            compose.waitForIdle(); android.os.SystemClock.sleep(400)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(compose.activity.filesDir,"settings-reminders.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
            back()
            compose.onNodeWithText(if(allowed) "3 个提醒时间" else "系统通知未开启").assertExists()
        } finally {kotlinx.coroutines.runBlocking {repository.update {it.copy(settings=original.settings)}}}
    }

    @Test fun webdavSummaryRefreshesAfterReturningFromBackground() {
        check(compose.activity.packageName.endsWith(".debug"))
        val prefs=compose.activity.getSharedPreferences("webdav",android.content.Context.MODE_PRIVATE)
        val oldStatus=prefs.getString("status",null);val oldSuccess=prefs.getString("success",null)
        try {
            prefs.edit().putString("status","尚未备份").apply()
            compose.onNodeWithText("设置",substring=false).performClick()
            compose.onNodeWithText("尚未备份",substring=false).assertExists()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            prefs.edit().putString("status","WebDAV 返回 HTTP 401，请检查地址、应用密码与网络").apply()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.onNodeWithText("最近备份失败").assertExists()
            compose.onNodeWithText("本地备份",substring=false).performClick()
            prefs.edit().putString("status","备份成功").putString("success","2026-09-07T00:00:00Z").apply()
            compose.onNodeWithText("返回",substring=false).performClick()
            compose.onNodeWithText("最近成功",substring=true).assertExists()
        } finally {prefs.edit().putString("status",oldStatus).putString("success",oldSuccess).apply()}
    }

    @Test fun configuredWebdavShowsActionsBeforeSeparateConnectionPage() {
        check(compose.activity.packageName.endsWith(".debug"))
        val prefs=compose.activity.getSharedPreferences("webdav",android.content.Context.MODE_PRIVATE)
        check(!CredentialsStore(compose.activity).configured) { "Only an unconfigured debug installation may run this fixture" }
        val keys=listOf("url","user","secret","status","success")
        val previous=keys.associateWith { prefs.getString(it,null) }
        try {
            // A non-decryptable marker is sufficient for layout; no client or backup is invoked.
            prefs.edit().putString("url","https://example.invalid/dav/").putString("user","layout-fixture")
                .putString("secret","layout-only").putString("status","备份成功")
                .putString("success","2026-09-08T00:00:00Z").commit()
            compose.onNodeWithText("设置",substring=false).performClick()
            compose.onNodeWithText("坚果云 · WebDAV",substring=false).performClick()
            compose.onNodeWithText("立即备份",substring=false).assertIsDisplayed()
            compose.onNodeWithText("云端备份",substring=false).assertIsDisplayed()
            compose.onNodeWithText("WebDAV 地址").assertDoesNotExist()
            compose.onNodeWithText("断开 WebDAV").assertDoesNotExist()
            compose.onNodeWithText("连接设置",substring=false).performScrollTo().performClick()
            compose.onNode(hasText("WebDAV 地址") and hasSetTextAction()).assertTextContains("https://example.invalid/dav/")
            compose.onNodeWithText("断开 WebDAV").assertExists()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("连接设置",substring=false).assertExists()
            back()
            compose.onNodeWithText("立即备份",substring=false).assertIsDisplayed()
            compose.onNodeWithText("WebDAV 地址").assertDoesNotExist()
            back()
            compose.onNodeWithText("本地备份",substring=false).assertExists()
        } finally {
            prefs.edit().also { edit -> previous.forEach { (key,value) ->
                if(value==null) edit.remove(key) else edit.putString(key,value)
            } }.commit()
        }
    }

}
