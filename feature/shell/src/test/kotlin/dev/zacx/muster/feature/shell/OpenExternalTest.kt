package dev.zacx.muster.feature.shell

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The `open-external` message end to end inside the shell module: the raw
 * bridge string, through [BridgeMessage.parse], to the intent [ExternalAppPage.open]
 * starts. A valid message starts exactly one `ACTION_VIEW`; an invalid one
 * starts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class OpenExternalTest {
    private val policy = OriginPolicy.forServer("https://m.example.test")!!
    private val origin = "https://m.example.test"
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun parse(data: String, src: String = origin, main: Boolean = true) =
        BridgeMessage.parse(data, src, main, policy, now)

    private fun msg(url: String) = """{"type":"open-external","url":"$url"}"""

    /** What MainActivity does with a parsed message: only an OpenExternal can reach open(). */
    private fun deliver(m: BridgeMessage): Boolean = m is BridgeMessage.OpenExternal && ExternalAppPage.open(activity, m)

    @Test
    fun aValidMessageParsesToTheCanonicalUrl() {
        assertEquals(BridgeMessage.OpenExternal("https://m.example.test/apps/7"), parse(msg("https://m.example.test/apps/7")))
        assertEquals(BridgeMessage.OpenExternal("https://m.example.test/apps/40213"), parse(msg("https://M.EXAMPLE.test:443/apps/40213")))
    }

    @Test
    fun aValidMessageStartsOneActionViewForThatUrl() {
        assertTrue(deliver(parse(msg("https://m.example.test/apps/40213"))))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://m.example.test/apps/40213"), started.data)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        // The system browser: no component or package pinned, and nothing of the page's but the URL.
        assertNull(started.component)
        assertNull(started.`package`)
        assertNull(started.extras)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /** A knock tap's page (`/apps/<id>?ref=<ref>`) opens with its ref. */
    @Test
    fun aValidRefMessageStartsOneActionViewCarryingTheRef() {
        assertTrue(deliver(parse(msg("https://m.example.test/apps/40213?ref=Kn_0-c"))))
        assertEquals(Uri.parse("https://m.example.test/apps/40213?ref=Kn_0-c"), shadowOf(activity).nextStartedActivity.data)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun anInvalidMessageStartsNothing() {
        val bad = listOf(
            msg("https://evil.example.test/apps/7"),
            msg("https://m.example.test.evil.test/apps/7"),
            msg("http://m.example.test/apps/7"),
            msg("https://m.example.test:8443/apps/7"),
            msg("https://u@m.example.test/apps/7"),
            msg("https://m.example.test/apps/1/../x"),
            msg("https://m.example.test/apps/7?ref=a&x=1"),
            msg("https://m.example.test/apps/7?REF=a"),
            msg("https://m.example.test/apps/7?ref=a#b"),
            msg("https://m.example.test/apps/7?ref=%61"),
            msg("https://m.example.test/apps/abc"),
            msg("https://m.example.test/apps%2F7"),
            msg("https://m.example.test/attention"),
            """{"type":"open-external"}""",
            """{"type":"open-external","url":7}""",
            """{"type":"open-external","url":null}""",
            """{"type":"open-external","url":["https://m.example.test/apps/7"]}""",
        )
        bad.forEach { raw ->
            val m = parse(raw)
            assertTrue(raw, m is BridgeMessage.Refused)
            assertFalse(raw, deliver(m))
        }
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun refusalsSayWhy() {
        assertEquals(BridgeMessage.Refused("open-external without a url"), parse("""{"type":"open-external"}"""))
        assertEquals(
            BridgeMessage.Refused("open-external with a url that is not a muster app page"),
            parse(msg("https://evil.example.test/apps/7")),
        )
    }

    /** Invariant guard: the frame and source-origin checks come first for this message as for every other. */
    @Test
    fun onlyTheMainMusterFrameMaySendIt() {
        val good = msg("https://m.example.test/apps/7")
        assertEquals(BridgeMessage.Refused("not the main frame"), parse(good, main = false))
        assertEquals(BridgeMessage.Refused("wrong origin"), parse(good, src = "https://evil.example.test"))
    }

    /** 🔴 If this app ever handled the link itself, the page would come back into the WebView: the loop. */
    @Test
    fun anIntentThatResolvesToThisAppIsNotStarted() {
        val pm = shadowOf(activity.packageManager)
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE); addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https")
        }
        val self = ComponentName(activity.packageName, "dev.zacx.muster.LinkActivity")
        pm.addActivityIfNotPresent(self)
        pm.addIntentFilterForActivity(self, filter)
        assertFalse(deliver(parse(msg("https://m.example.test/apps/7"))))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /** The positive control for the test above: another app (a browser) handling it is started. */
    @Test
    fun anIntentThatResolvesToABrowserIsStarted() {
        val pm = shadowOf(activity.packageManager)
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE); addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https")
        }
        val browser = ComponentName("org.example.browser", "org.example.browser.Main")
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(browser, filter)
        assertTrue(deliver(parse(msg("https://m.example.test/apps/7"))))
        assertEquals(Uri.parse("https://m.example.test/apps/7"), shadowOf(activity).nextStartedActivity.data)
    }
}
