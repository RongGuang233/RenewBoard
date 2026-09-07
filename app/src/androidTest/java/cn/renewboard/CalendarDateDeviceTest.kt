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

    @Test fun monthArrowsClampAtBoundariesAndTodayRequiresConfirmation() {
        var initial by mutableStateOf("1900-01-31")
        var saved="unchanged"
        compose.setContent { MaterialTheme { key(initial) {CalendarDateDialog("日期",initial,{}, {saved=it})} } }
        compose.onNodeWithContentDescription("上个月").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下个月").performClick()
        compose.onNodeWithContentDescription("1900年2月28日").assertExists()
        compose.onNodeWithContentDescription("上个月").performClick()
        compose.onNodeWithText("确定").performClick()
        assertEquals("1900-01-28",saved)
        compose.runOnIdle {initial="2200-12-31"}
        compose.onNodeWithContentDescription("下个月").assertIsNotEnabled()
        compose.onNodeWithText("今天",substring=false).performClick()
        assertEquals("1900-01-28",saved)
        compose.onNodeWithText("确定").performClick()
        assertEquals(java.time.LocalDate.now().toString(),saved)
    }
    @Test fun largeFontCalendarDaysStaySingleLineAndSelectable() {
        // The host runs this case separately with the real system font scale set to 1.6.
        org.junit.Assume.assumeTrue(ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration.fontScale >= 1.59f)
        var saved=""
        // Run this case with the device's system font_scale set to 1.6; Dialog owns a separate window density.
        compose.setContent { MaterialTheme {CalendarDateDialog("日期","2026-08-31",{}, {saved=it})} }
        val day=compose.onNodeWithContentDescription("2026年8月31日")
        day.performScrollTo().assertIsDisplayed()
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("31",substring=false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
        val layout=layouts.single()
        shot("date-large-font-before-assert")
        val diagnostics="date31 size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
            "intrinsicMin=${layout.multiParagraph.intrinsics.minIntrinsicWidth}, intrinsicMax=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, " +
            "paragraphWidth=${layout.multiParagraph.width}, paragraphHeight=${layout.multiParagraph.height}, " +
            "lineTop=${layout.getLineTop(0)}, lineBottom=${layout.getLineBottom(0)}, lineLeft=${layout.getLineLeft(0)}, lineRight=${layout.getLineRight(0)}, " +
            "fontSize=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}, " +
            "density=${layout.layoutInput.density.density}, fontScale=${layout.layoutInput.density.fontScale}, dayBounds=${day.fetchSemanticsNode().boundsInRoot}"
        android.util.Log.i("CalendarDateTest",diagnostics)
        org.junit.Assert.assertTrue("This case requires actual system font_scale >= 1.6; $diagnostics",layout.layoutInput.density.fontScale >= 1.59f)
        assertEquals(diagnostics,1,layout.lineCount)
        org.junit.Assert.assertFalse(diagnostics,layout.hasVisualOverflow)
        org.junit.Assert.assertTrue(diagnostics,layout.getLineLeft(0) >= -0.5f && layout.getLineRight(0) <= layout.size.width + 0.5f)
        org.junit.Assert.assertTrue(diagnostics,layout.multiParagraph.height <= layout.size.height + 0.5f)
        day.performClick()
        shot("date-large-font")
        compose.onNodeWithText("确定").performClick()
        assertEquals("2026-08-31",saved)
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
