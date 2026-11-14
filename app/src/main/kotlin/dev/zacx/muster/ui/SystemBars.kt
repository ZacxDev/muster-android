package dev.zacx.muster.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * The status- and navigation-bar icons, chosen from the colour actually drawn
 * behind the bars, never from the system's dark-mode setting.
 *
 * The bars are edge to edge (transparent): what shows through is the screen's
 * own background. In the shell that is the native container's
 * [dev.zacx.muster.MainActivity.SHELL_BACKGROUND] (the page sits below the
 * switcher, inside the container's inset padding, so the page's own colours
 * never reach the bars); on the native screens it is their Material surface.
 * Neither follows the system's night mode, so the default (`enableEdgeToEdge()`
 * with no styles, which picks icons from night mode) drew dark icons on the
 * dark shell in light mode, and would draw light icons on the light native
 * screens in dark mode.
 */
object SystemBars {
    /**
     * True for light (white) icons over [background]: whichever of white and
     * black has the higher contrast ratio against it (WCAG), so the icons are
     * always the more legible of the two.
     */
    fun lightIconsOver(@ColorInt background: Int): Boolean {
        val opaque = ColorUtils.setAlphaComponent(background, 0xFF)
        return ColorUtils.calculateContrast(Color.WHITE, opaque) >= ColorUtils.calculateContrast(Color.BLACK, opaque)
    }

    /** Both bars transparent over [background], with the icons [lightIconsOver] picks. */
    fun applyOver(activity: ComponentActivity, @ColorInt background: Int) {
        val style = if (lightIconsOver(background)) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
