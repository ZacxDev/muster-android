package dev.zacx.muster.feature.shell

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Pull to refresh for the shell: a [SwipeRefreshLayout] around the WebView
 * whose "can the content still scroll up?" is [PullGate]'s answer, not the
 * WebView's `scrollY` (see [PullGate] for why).
 *
 * A pull reloads the page (`WebView.reload()`). muster has no page-wide htmx
 * refresh: `muster:resync` is listened for by the task list, the agent list
 * and the task detail card only, and the Agents list treats it as a
 * background refresh that holds new cards back behind its "N new" pill, so a
 * pull would not show them. A reload is
 * the same on every page and shows what the server has now; the pull starts
 * only at the top, so it moves no scroll position either.
 *
 * The spinner shows until the WebView reports the page finished
 * ([onPageFinished], from `WebViewClient.onPageFinished`), or for
 * [TIMEOUT_MS] at most. Its colours are muster's (dark theme: the accent on
 * the raised surface).
 */
@SuppressLint("ViewConstructor")
class ShellRefreshLayout(
    context: Context,
    val webView: WebView,
    private val gate: PullGate = PullGate(ViewConfiguration.get(context).scaledTouchSlop),
) : SwipeRefreshLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (isRefreshing) { Log.i(TAG, "refresh: timed out"); isRefreshing = false } }

    init {
        addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setColorSchemeColors(ACCENT)
        setProgressBackgroundColorSchemeColor(SURFACE)
        setOnRefreshListener { refresh() }
    }

    /** The page's `touch-start` report; the gate keeps it only if it is the gesture in progress's. */
    fun onProbe(m: BridgeMessage.TouchStart) = gate.onProbe(m.doc, m.seq, m.probe)

    /** `WebViewClient.onPageStarted` for this layout's WebView: a new document counts its gestures from 1. */
    fun onPageStarted() = gate.onPageStarted()

    /** `WebViewClient.onPageFinished` for this layout's WebView: the spinner's work is done. */
    fun onPageFinished() {
        Log.i(TAG, "onPageFinished" + if (isRefreshing) " (refresh done)" else "")
        stopSpinner()
    }

    /**
     * True while SwipeRefreshLayout handles an ACTION_DOWN. The page's probe
     * for a gesture can only arrive after its DOWN, and SwipeRefreshLayout
     * records the gesture's pointer at DOWN only if the child "cannot scroll
     * up" then; refusing at DOWN would make it ignore the whole gesture. A
     * DOWN never starts a pull, so letting it through decides nothing: every
     * MOVE asks the gate again.
     */
    private var inDown = false

    override fun canChildScrollUp(): Boolean = !inDown && !gate.allowsPull(webView.url)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // The gate sees every event before SwipeRefreshLayout decides on it.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gate.onDown(ev.x, ev.y)
                inDown = true
                try {
                    return super.onInterceptTouchEvent(ev)
                } finally {
                    inDown = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> gate.onSecondPointer()
            MotionEvent.ACTION_MOVE -> gate.onMove(ev.x, ev.y)
        }
        return super.onInterceptTouchEvent(ev)
    }

    /** What a completed pull does (SwipeRefreshLayout's refresh listener): reload, spinner on, timeout armed. */
    internal fun refresh() {
        Log.i(TAG, "refresh: reload")
        isRefreshing = true // already, after a pull; says so for any other caller
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        webView.reload()
    }

    private fun stopSpinner() {
        handler.removeCallbacks(timeout)
        isRefreshing = false
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(timeout)
        super.onDetachedFromWindow()
    }

    companion object {
        const val TAG = "MusterPull"

        /** The longest the spinner shows when the page never reports finished. */
        const val TIMEOUT_MS = 15_000L

        /** muster's dark-theme accent, `--mu-accent` (#E4B556). */
        const val ACCENT = 0xFFE4B556.toInt()

        /** muster's dark-theme raised surface, `--mu-s1` (#19162A). */
        const val SURFACE = 0xFF19162A.toInt()

        /** The shell's refresh layout around [webView], if it has one. */
        fun of(webView: WebView): ShellRefreshLayout? = webView.parent as? ShellRefreshLayout
    }
}
