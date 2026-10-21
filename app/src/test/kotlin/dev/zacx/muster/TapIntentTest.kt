package dev.zacx.muster

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class TapIntentTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** "Aa" and "BB" share a hash code; each notification must still open its own path. */
    @Test
    fun collidingTagsDoNotShareATapIntent() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val a = MusterApp.tapPendingIntent(app, "Aa", "/tasks/1")
        val b = MusterApp.tapPendingIntent(app, "BB", "/tasks/2")
        assertNotEquals(a, b)
        assertEquals("/tasks/1", shadowOf(a).savedIntent.getStringExtra(MainActivity.EXTRA_PATH))
        assertEquals("/tasks/2", shadowOf(b).savedIntent.getStringExtra(MainActivity.EXTRA_PATH))
    }
}
