package dev.zacx.muster

import android.app.Application
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class LauncherIconTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The manifest names the launcher icon; without it the launcher shows a generic one. */
    @Test
    fun applicationIconIsTheMusterAdaptiveIcon() {
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        for (id in intArrayOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = app.getDrawable(id)
            assertTrue("$id is $icon", icon is AdaptiveIconDrawable)
            assertNotNull((icon as AdaptiveIconDrawable).foreground)
            assertNotNull(icon.background)
        }
    }

    /** Android 13+ themed icons draw the monochrome layer; without it they fall back to the colour icon. */
    @Test
    @Config(sdk = [34])
    fun adaptiveIconHasAMonochromeLayer() {
        val icon = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
    }
}
