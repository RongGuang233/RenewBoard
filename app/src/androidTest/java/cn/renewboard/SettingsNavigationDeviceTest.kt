package cn.renewboard

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/** Settings acceptance uses only the debug ledger and local backup status; no remote operations. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun back() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    /** Change the real channel switch in Android Settings; app APIs cannot restore user importance. */
    private fun setExpiryChannelEnabled(enabled: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        if ((manager.getNotificationChannel(EXPIRY_CHANNEL_ID).importance != android.app.NotificationManager.IMPORTANCE_NONE) == enabled) return
        compose.runOnUiThread {
            compose.activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, compose.activity.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, EXPIRY_CHANNEL_ID))
        }
        fun firstSwitch(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isCheckable && node.className?.toString()?.contains("Switch") == true) return node
            for (index in 0 until node.childCount) firstSwitch(node.getChild(index))?.let { return it }
            return null
        }
        compose.waitUntil(10_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            root?.packageName?.toString() == "com.android.settings" && firstSwitch(root) != null
        }
        var control = requireNotNull(firstSwitch(instrumentation.uiAutomation.rootInActiveWindow))
        if (control.isChecked != enabled) {
            while (!control.isClickable) control = requireNotNull(control.parent)
            assertTrue(control.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        compose.waitUntil(10_000) {
            (manager.getNotificationChannel(EXPIRY_CHANNEL_ID).importance != android.app.NotificationManager.IMPORTANCE_NONE) == enabled
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    @Test fun reminderChannelDisabledAndRestoredRefreshesAfterSystemSettings() {
        check(compose.activity.packageName.endsWith(".debug"))
        val appAllowed = appNotificationsAllowed(compose.activity)
        check(appAllowed) { "Run this channel-only acceptance with the debug app notification permission enabled" }
        notificationsAllowed(compose.activity) // Ensure the existing expiry channel is registered.
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val wasEnabled = manager.getNotificationChannel(EXPIRY_CHANNEL_ID).importance != android.app.NotificationManager.IMPORTANCE_NONE
        try {
            compose.onNodeWithText("设置", substring = false).performClick()
            compose.onNodeWithText("到期提醒", substring = false).performClick()
            setExpiryChannelEnabled(false)
            assertTrue(appNotificationsAllowed(compose.activity))
            compose.onNodeWithText("到期提醒未开启").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("到期提醒通知：已开启").assertDoesNotExist()
            val settingsIntent = reminderNotificationSettingsIntent(compose.activity)
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settingsIntent.action)
            assertEquals(EXPIRY_CHANNEL_ID, settingsIntent.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
            compose.onNodeWithText("前往开启通知").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            setExpiryChannelEnabled(true)
            compose.onNodeWithText("到期提醒通知：已开启").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("管理通知设置").assertExists()
        } finally {
            setExpiryChannelEnabled(wasEnabled)
            assertEquals(wasEnabled, manager.getNotificationChannel(EXPIRY_CHANNEL_ID).importance != android.app.NotificationManager.IMPORTANCE_NONE)
            assertEquals(appAllowed, appNotificationsAllowed(compose.activity))
        }
    }

    @Test fun webdavResultRefreshesWhileSettingsStayVisible() {
        check(compose.activity.packageName.endsWith(".debug"))
        val config = CredentialsStore(compose.activity)
        check(!config.configured) { "Only an unconfigured debug installation may run this fixture" }
        val prefs = compose.activity.getSharedPreferences("webdav", android.content.Context.MODE_PRIVATE)
        val previous = listOf("url", "user", "secret", "status", "success").associateWith { prefs.getString(it, null) }
        fun backgroundResult(error: String? = null) = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CredentialsStore(compose.activity).result(error) }
        }
        fun successText(value: String): String = "最后成功：" + java.time.Instant.parse(value)
            .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        try {
            // Layout-only credentials never invoke a cloud client or schedule a backup.
            prefs.edit().putString("url", "https://example.invalid/dav/").putString("user", "status-fixture")
                .putString("secret", "layout-only").putString("status", "尚未备份").remove("success").commit()
            compose.onNodeWithText("设置", substring = false).performClick()
            compose.onNodeWithText("尚未备份", substring = false).assertExists()
            backgroundResult("自动备份失败，请检查网络")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("最近备份失败").fetchSemanticsNodes().isNotEmpty() }
            backgroundResult()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("最近成功", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("坚果云 · WebDAV", substring = false).performClick()
            compose.onNodeWithText("备份成功", substring = false).assertExists()
            compose.onNodeWithText(successText(config.lastSuccess), substring = false).assertExists()
            backgroundResult("自动备份失败，请检查网络")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("自动备份失败，请检查网络", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(successText(config.lastSuccess), substring = false).assertExists()
            // Use a distinct fixture time to prove the visible last-success field follows prefs too.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    prefs.edit().putString("status", "备份成功").putString("success", "2026-09-01T03:04:00Z").commit()
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(successText("2026-09-01T03:04:00Z"), substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("备份成功", substring = false).assertExists()
        } finally {
            prefs.edit().also { edit -> previous.forEach { (key, value) ->
                if (value == null) edit.remove(key) else edit.putString(key, value)
            } }.commit()
        }
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
            val allowed=notificationsAllowed(compose.activity)
            val notificationIntent=reminderNotificationSettingsIntent(compose.activity)
            assertEquals(if(appNotificationsAllowed(compose.activity)) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,notificationIntent.action)
            compose.onNodeWithText(if(allowed) "到期提醒通知：已开启" else "到期提醒未开启").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(if(allowed) "管理通知设置" else "前往开启通知").assertExists()
            compose.waitForIdle(); android.os.SystemClock.sleep(400)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(compose.activity.filesDir,"settings-reminders.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
            back()
            compose.onNodeWithText(if(allowed) "3 个提醒时间" else "到期提醒未开启").assertExists()
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
