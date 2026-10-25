package dev.zacx.muster.feature.shell

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.activity.ComponentActivity
import dev.zacx.muster.feature.shell.PullGate.Owner
import dev.zacx.muster.feature.shell.PullGate.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * ShellRefreshLayout with real MotionEvents: the seam between
 * SwipeRefreshLayout's own intercept logic and [PullGate].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class ShellRefreshLayoutTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val wv = WebView(activity)
    private val layout = ShellRefreshLayout(activity, wv).also {
        activity.setContentView(it)
        it.layout(0, 0, 1080, 2000)
        wv.layout(0, 0, 1080, 2000)
    }
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val t0 = SystemClock.uptimeMillis()

    private fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)

    /**
     * Down at (500, 300), the page's probe (as the bridge delivers it, after
     * the down), then moves along (dx, dy) in steps. Returns whether the
     * layout took the gesture over (started a pull).
     */
    private fun pull(probe: Probe?, dx: Float = 0f, dy: Float = 10f * slop): Boolean {
        wv.loadUrl(url)
        if (layout.onInterceptTouchEvent(ev(MotionEvent.ACTION_DOWN, 500f, 300f))) return true
        probe?.let(layout::onProbe)
        for (i in 1..10) {
            if (layout.onInterceptTouchEvent(ev(MotionEvent.ACTION_MOVE, 500f + dx * i / 10, 300f + dy * i / 10))) return true
        }
        return false
    }

    private var url = "https://m.example.test/agents"
    private val atTop = Probe(0, null, Owner.PAGE)

    @Test
    fun theLayoutWrapsTheWebView() {
        assertSame(layout, ShellRefreshLayout.of(wv))
        assertSame(wv, layout.getChildAt(layout.childCount - 1))
    }

    /**
     * 🔴 The probe arrives after the DOWN. SwipeRefreshLayout records the
     * pointer at DOWN only if the child "cannot scroll up" then, so a gate
     * that refused at DOWN (no probe yet) would never let any pull start.
     */
    @Test
    fun aPullAtTheTopStartsOnceTheProbeArrives() {
        assertTrue(pull(atTop))
    }

    @Test
    fun aPullWithNoProbeOrNotAtTheTopDoesNotStart() {
        assertFalse(pull(null))
        assertFalse(pull(Probe(docTop = 300, innerTop = null, owner = Owner.PAGE)))
        assertFalse(pull(Probe(docTop = 0, innerTop = 300, owner = Owner.PAGE)))
        assertFalse(pull(Probe(docTop = 0, innerTop = null, owner = Owner.OVERLAY)))
    }

    @Test
    fun aSidewaysSwipeAtTheTopDoesNotStart() {
        assertFalse(pull(atTop, dx = 30f * slop, dy = 10f * slop))
        assertTrue(pull(atTop)) // the same layout, the next vertical pull
    }

    @Test
    fun noPullOnTheTerminal() {
        url = "https://m.example.test/agents/clever-shrew/terminal"
        assertFalse(pull(atTop))
        url = "https://m.example.test/mirror/view?host=h&session=s"
        assertFalse(pull(atTop))
    }

    @Test
    fun aRefreshReloadsAndTheSpinnerStopsWhenThePageFinishes() {
        layout.refresh()
        assertEquals(1, shadowOf(wv).reloadInvocations)
        assertTrue(layout.isRefreshing)
        layout.onPageFinished()
        assertFalse(layout.isRefreshing)
        // The finished page cancelled the timeout: nothing fires later.
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(ShellRefreshLayout.TIMEOUT_MS + 1))
        assertFalse(layout.isRefreshing)
    }

    /** The seam: the shell's own WebViewClient is what tells the layout the page finished. */
    @Test
    fun theShellsWebViewClientStopsTheSpinner() {
        ShellWebView.configure(
            wv, OriginPolicy.forServer("https://m.example.test")!!,
            object : ShellWebView.Callbacks {
                override fun onBridgeMessage(message: BridgeMessage) = Unit
                override fun openExternally(url: String) = Unit
                override fun onBlockedAttachment() = Unit
                override fun onRendererGone(view: WebView) = Unit
                override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<android.net.Uri>?) -> Unit) = Unit
                override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
                override fun onHistoryChanged() = Unit
            },
        )
        layout.refresh()
        assertTrue(layout.isRefreshing)
        shadowOf(wv).webViewClient.onPageFinished(wv, "https://m.example.test/agents")
        assertFalse(layout.isRefreshing)
    }

    @Test
    fun theSpinnerStopsAtTheTimeoutIfThePageNeverFinishes() {
        layout.refresh()
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(ShellRefreshLayout.TIMEOUT_MS - 100))
        assertTrue(layout.isRefreshing)
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(200))
        assertFalse(layout.isRefreshing)
        assertEquals(1, shadowOf(wv).reloadInvocations)
    }

    @Test
    fun aSecondRefreshRestartsTheTimeout() {
        layout.refresh()
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(10_000))
        layout.refresh()
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(10_000))
        assertTrue(layout.isRefreshing) // 20 s after the first, 10 s after the second
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(5_100))
        assertFalse(layout.isRefreshing)
    }
}
