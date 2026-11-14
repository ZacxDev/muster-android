package dev.zacx.muster.ui

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import dev.zacx.muster.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The system-bar icons follow the colour behind the bars, never the system's
 * night mode: light icons on the dark shell, dark icons on the light native
 * screens, in both light and dark mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class SystemBarsTest {
    @Test
    fun theDarkShellGetsLightIcons() {
        assertTrue(SystemBars.lightIconsOver(MainActivity.SHELL_BACKGROUND))
        assertTrue(SystemBars.lightIconsOver(darkColorScheme().surface.toArgb()))
    }

    @Test
    fun aLightSurfaceGetsDarkIcons() {
        assertFalse(SystemBars.lightIconsOver(lightColorScheme().surface.toArgb()))
        assertFalse(SystemBars.lightIconsOver(android.graphics.Color.WHITE))
    }

    /**
     * Either side of the grey where white and black tie (relative luminance
     * ~0.18, about #757575): #666 (0.13) takes white, #888 (0.25) black. A
     * plain "luminance < 0.5" rule would give #888 white icons at about 3.5:1
     * against black's 5.9:1.
     */
    @Test
    fun theChoiceIsTheHigherContrast() {
        assertTrue(SystemBars.lightIconsOver(0xFF666666.toInt()))
        assertFalse(SystemBars.lightIconsOver(0xFF888888.toInt()))
    }

    private fun appearance(nightMode: Int, background: Int): Pair<Boolean, Boolean> {
        RuntimeEnvironment.setQualifiers(if (nightMode == Configuration.UI_MODE_NIGHT_YES) "+night" else "+notnight")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        SystemBars.applyOver(activity, background)
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return c.isAppearanceLightStatusBars to c.isAppearanceLightNavigationBars
    }

    /** "Light appearance" means DARK icons. Both bars, both night modes. */
    @Test
    fun theWindowGetsTheIconsForItsBackgroundInEitherNightMode() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            assertEquals("shell, night=$night", false to false, appearance(night, MainActivity.SHELL_BACKGROUND))
            assertEquals("native, night=$night", true to true, appearance(night, lightColorScheme().surface.toArgb()))
        }
    }
}
