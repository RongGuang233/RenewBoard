package cn.renewboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarDateDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun yearMonthAndLeapDayCanBeSelectedAndOnlyConfirmationApplies() {
        var saved="2026-01-31"
        compose.setContent { MaterialTheme { Field("扣款日期",saved,{saved=it},dateField=true) } }
        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.onNodeWithContentDescription("选择年份").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(123)
        compose.onNodeWithText("2024 年",substring=false).performClick()
        compose.onNodeWithContentDescription("选择月份").performClick()
        compose.onNodeWithText("2 月",substring=false).performClick()
        compose.onNodeWithContentDescription("2024年2月29日").assertExists().performClick()
        compose.onNodeWithContentDescription("2024年2月30日").assertDoesNotExist()
        assertEquals("2026-01-31",saved)
        shot("date-picker")
        compose.onNodeWithText("确定").performClick()
        assertEquals("2024-02-29",saved)
    }
    @Test fun switchingToShortMonthClampsDayAndCancelPreservesOriginal() {
        var saved="2026-01-31"
        compose.setContent { MaterialTheme { Field("到期日期",saved,{saved=it},dateField=true) } }
        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.onNodeWithContentDescription("选择月份").performClick()
        shot("date-months")
        compose.onNodeWithText("2 月",substring=false).performClick()
        compose.onNodeWithContentDescription("2026年2月29日").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        assertEquals("2026-01-31",saved)
        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.onNodeWithContentDescription("选择月份").performClick()
        compose.onNodeWithText("2 月",substring=false).performClick()
        compose.onNodeWithText("确定").performClick()
        assertEquals("2026-02-28",saved)
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val app=ApplicationProvider.getApplicationContext<android.content.Context>()
        check(app.packageName.endsWith(".debug"))
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
