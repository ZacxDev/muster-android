package dev.zacx.muster

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 The debug-only receiver that runs the background-refresh job now
 * (src/debug) is exported, for adb, and so must require a permission only
 * adb's shell user holds: DUMP. Without it any app on a phone with a debug
 * build could start the job at will. Read from the merged debug manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RunRefreshReceiverManifestTest {
    @Test
    fun theDebugReceiverIsExportedForAdbOnly() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getReceiverInfo(
            ComponentName(ctx.packageName, "dev.zacx.muster.RunRefreshReceiver"),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertTrue("exported, so adb's shell user can send it", info.exported)
        assertEquals("android.permission.DUMP", info.permission)
    }
}
