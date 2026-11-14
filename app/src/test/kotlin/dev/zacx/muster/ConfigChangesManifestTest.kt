package dev.zacx.muster

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The configuration changes the shell handles itself (AndroidManifest.xml):
 * each of these re-created MainActivity, which destroyed the WebView and
 * reloaded muster at its home page (seen with `wm density` on an API 36
 * emulator: Tasks jumped to home). Pinned as the exact set, read from the
 * merged manifest, so dropping one (or adding one unreviewed) turns this red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConfigChangesManifestTest {
    @Test
    fun theShellSurvivesTheCommonConfigurationChanges() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, MainActivity::class.java), 0)
        val expected = mapOf(
            "orientation" to ActivityInfo.CONFIG_ORIENTATION,
            "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE,
            "smallestScreenSize" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
            "screenLayout" to ActivityInfo.CONFIG_SCREEN_LAYOUT,
            "density" to ActivityInfo.CONFIG_DENSITY,
            "fontScale" to ActivityInfo.CONFIG_FONT_SCALE,
            "fontWeightAdjustment" to ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT,
            "uiMode" to ActivityInfo.CONFIG_UI_MODE,
            "keyboard" to ActivityInfo.CONFIG_KEYBOARD,
            "keyboardHidden" to ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
            "navigation" to ActivityInfo.CONFIG_NAVIGATION,
        )
        val declared = expected.filterValues { info.configChanges and it != 0 }.keys
        assertEquals(expected.keys, declared)
        // The package parser always sets mcc|mnc in configChanges (they are
        // governed by android:recreateOnConfigChanges instead): left out.
        val parserBits = ActivityInfo.CONFIG_MCC or ActivityInfo.CONFIG_MNC
        assertEquals("nothing beyond the reviewed set", expected.values.fold(0, Int::or), info.configChanges and parserBits.inv())
    }
}
