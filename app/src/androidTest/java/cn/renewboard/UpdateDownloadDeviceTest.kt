package cn.renewboard

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDownloadDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val release = AppUpdate("1.1.1", "", true,
        "https://github.com/RongGuang233/RenewBoard/releases/download/v1.1.1/RenewBoard-1.1.1-release.apk")

    @Test fun cancellationAndMissingSystemDownloadAreRecoverable() {
        check(context.packageName.endsWith(".debug"))
        val downloads = UpdateDownload(context, "1.1.0")
        downloads.cancel(); assertNull(downloads.current())
        downloads.cancel(); assertNull(downloads.current())
        context.getSharedPreferences("app-update", Context.MODE_PRIVATE).edit()
            .putLong("id", Long.MAX_VALUE).putString("version", "1.1.1").commit()
        assertTrue(downloads.current()!!.failed)
        downloads.cancel(); assertNull(downloads.current())
        downloads.start(release)
        assertNotNull(UpdateDownload(context, "1.1.0").current())
        downloads.cancel(); assertNull(downloads.current())
        assertThrows(IllegalStateException::class.java) { downloads.installerIntent() }
    }

    /** Explicit network acceptance against the existing public APK; does not confirm installation. */
    @Test fun publicApkDownloadsAndOpensAndroidInstaller() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testUpdateDownload") == "true")
        check(context.packageName.endsWith(".debug"))
        val downloads = UpdateDownload(context, "1.1.0")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            downloads.start(release)
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (downloads.current()?.ready != true && android.os.SystemClock.elapsedRealtime() < deadline) {
                assertFalse("DownloadManager reported failure", downloads.current()?.failed == true)
                android.os.SystemClock.sleep(500)
            }
            assertTrue("Download did not complete", downloads.current()?.ready == true)
            // A new instance finds the OS-owned download after returning to the app.
            val resumed = UpdateDownload(context, "1.1.0")
            val intent = resumed.installerIntent()
            assertEquals("content", intent.data!!.scheme)
            val bytes = context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("6f1b46385c775c666c9812257c3ba5d2acbc6496d0dacecdfeba3b198a9f7b35", digest)
            assertTrue(context.packageManager.canRequestPackageInstalls())
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val installerDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var installerVisible = false
            while (!installerVisible && android.os.SystemClock.elapsedRealtime() < installerDeadline) {
                val pkg = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                installerVisible = pkg.contains("packageinstaller")
                android.os.SystemClock.sleep(250)
            }
            assertTrue("Android installer not visible", installerVisible)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.filesDir, "inapp-installer.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        } finally { downloads.cancel() }
    }
}
