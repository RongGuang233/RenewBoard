package cn.renewboard

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation-only acceptance: does not edit credentials, restore data, or invoke remote operations. */
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
        compose.onNodeWithText("开源许可证").performScrollTo().performClick()
        back()
        compose.onNodeWithText("更新与关于").assertExists()
        try {compose.onNodeWithText("检查更新", substring = false).assertExists()}
        catch(e:AssertionError) {throw AssertionError(compose.onRoot(useUnmergedTree=true).printToString(),e)}
        back()
        compose.onNodeWithText("本地备份", substring = false).assertExists()
    }
}
