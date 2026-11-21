package dev.zacx.muster.feature.shell

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import dev.zacx.muster.feature.shell.ExternalAppOpener.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 🔴 The gesture and rate gate ([ExternalAppOpener]) with the real
 * [ExternalAppPage.open]: `open-external` opens only within 2 s of a
 * `touch-start`, and at most once per 2 s. Times are a fake monotonic clock,
 * chosen well inside or well outside the window, never on its edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class ExternalAppOpenerTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private var now = 50_000L
    private val opener = ExternalAppOpener(activity, clock = { now })
    private val page = BridgeMessage.OpenExternal("https://m.example.test/apps/40213")

    private fun started(): List<Intent> = generateSequence { shadowOf(activity).nextStartedActivity }.toList()

    @Test
    fun withNoTouchNothingOpens() {
        assertEquals(Outcome.NO_RECENT_TOUCH, opener.onOpenExternal(page))
        assertEquals(Outcome.NO_RECENT_TOUCH, opener.onOpenExternal(page))
        assertEquals(emptyList<Intent>(), started())
    }

    @Test
    fun aTouchLongerAgoThanTheWindowOpensNothing() {
        opener.onTouchStart()
        now += 2_700
        assertEquals(Outcome.NO_RECENT_TOUCH, opener.onOpenExternal(page))
        assertEquals(emptyList<Intent>(), started())
    }

    @Test
    fun aRecentTouchOpensOnce() {
        opener.onTouchStart()
        now += 1_300
        assertEquals(Outcome.OPENED, opener.onOpenExternal(page))
        val opened = started()
        assertEquals(1, opened.size)
        assertEquals(Intent.ACTION_VIEW, opened[0].action)
        assertEquals(Uri.parse("https://m.example.test/apps/40213"), opened[0].data)
    }

    /** Two posts in a row after one touch: one opens. */
    @Test
    fun twoPostsInARowOpenOne() {
        opener.onTouchStart()
        now += 120
        assertEquals(Outcome.OPENED, opener.onOpenExternal(page))
        now += 30
        assertEquals(Outcome.TOO_SOON, opener.onOpenExternal(page))
        assertEquals(1, started().size)
    }

    /** A fresh touch does not lift the once-per-2 s limit. */
    @Test
    fun aNewTouchDoesNotResetTheRateLimit() {
        opener.onTouchStart()
        now += 100
        assertEquals(Outcome.OPENED, opener.onOpenExternal(page))
        now += 400
        opener.onTouchStart()
        now += 100
        assertEquals(Outcome.TOO_SOON, opener.onOpenExternal(page))
        assertEquals(1, started().size)
    }

    /** Positive control for the limit: past the window, with a new touch, a second open is allowed. */
    @Test
    fun afterTheWindowANewTouchOpensAgain() {
        opener.onTouchStart()
        now += 100
        assertEquals(Outcome.OPENED, opener.onOpenExternal(page))
        now += 2_600
        opener.onTouchStart()
        now += 100
        assertEquals(Outcome.OPENED, opener.onOpenExternal(page))
        assertEquals(2, started().size)
    }

    /** An open that started nothing does not use up the limit. */
    @Test
    fun anOpenThatStartedNothingDoesNotCount() {
        var tries = 0
        val o = ExternalAppOpener(activity, clock = { now }) { _, _ -> tries++; tries > 1 }
        o.onTouchStart()
        now += 100
        assertEquals(Outcome.NOT_STARTED, o.onOpenExternal(page))
        now += 100
        assertEquals(Outcome.OPENED, o.onOpenExternal(page))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
