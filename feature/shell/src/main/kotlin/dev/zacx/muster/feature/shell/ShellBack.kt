package dev.zacx.muster.feature.shell

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback

/**
 * Back in the shell: ONE callback for the activity's life. It is enabled
 * while the current WebView can go back or the page holds a back claim, so
 * predictive back maps to WebView history and, at the start of history with
 * no claim, falls through to the system (which leaves the app). [sync] runs
 * on every history change (WebViewClient.doUpdateVisitedHistory) and whenever
 * the WebView changes, so a back-out never disables it for good.
 *
 * **The claim** (muster docs/design/switcher-dock.md, "The shell change"):
 * while muster's switcher dock is open the page posts `back-claim` `true`
 * ([BridgeMessage.BackClaim]), and a press is offered to the page first:
 * `window.musterBack()` closes the dock and answers `true`. Any other answer,
 * or none within [CLAIM_TIMEOUT_MS] (a hung page never answers), clears the
 * claim and falls back to what back does without one: `goBack()` if it can,
 * else the press goes to the system ([toSystem]). Whichever comes first, the
 * answer, the timeout, or the next press (which decides it with the fallback
 * before being handled itself), decides the press, once. So a stale or hung
 * claim delays one press by at most [CLAIM_TIMEOUT_MS]; it never takes it away.
 *
 * The claim ends on every main-frame page start ([onPageEvent]) and when the
 * WebView is replaced or its renderer is gone ([onWebViewChanged]), but NOT
 * on a history change ([onHistoryChanged]): muster's view swap calls
 * `history.replaceState` while the dock can be open. Where it ends that way, a
 * press still waiting on the old page is dropped, with its timeout.
 *
 * @param toSystem re-dispatches a press the page declined, once this callback
 *   is disabled (MainActivity: `onBackPressedDispatcher.onBackPressed()`).
 */
class ShellBack(
    private val webView: () -> WebView?,
    private val toSystem: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : OnBackPressedCallback(false) {
    /** The page's dock is open and gets the next press first. */
    var claimed: Boolean = false
        private set

    /** The press the page has been asked about, until the answer or the timeout decides it. */
    private var pending: Any? = null

    /** [pending]'s posted fallback, removed whenever [pending] is decided or dropped. */
    private var pendingTimeout: Runnable? = null

    fun claim(open: Boolean) {
        claimed = open
        sync()
    }

    fun sync() {
        isEnabled = claimed || webView()?.canGoBack() == true
    }

    /** Every main-frame page start, a reload included, is a new document: no dock is open in it. */
    fun onPageEvent(event: ShellWebView.PageEvent) {
        if (event == ShellWebView.PageEvent.STARTED) reset() else sync()
    }

    /** A history change re-syncs and keeps the claim (the view swap's replaceState). */
    fun onHistoryChanged() = sync()

    /** A new WebView, none, or a dead renderer: whatever claimed back is gone. */
    fun onWebViewChanged() = reset()

    /**
     * The document or WebView that claimed back is gone: the claim ends, and a
     * press still waiting on it is dropped, not decided. Its fallback would act
     * on what replaced it (goBack on the new document, or the system's back
     * when the renderer died), and its answer, if one still comes, is ignored.
     */
    private fun reset() {
        dropPending()
        claim(false)
    }

    private fun dropPending() {
        pendingTimeout?.let(handler::removeCallbacks)
        pendingTimeout = null
        pending = null
    }

    override fun handleOnBackPressed() {
        // A press still waiting on the page is decided now with the fallback,
        // before this one: on a hung page presses closer together than
        // CLAIM_TIMEOUT_MS would otherwise keep superseding each other and
        // Back would never fire. This press then sees the claim that leaves.
        pending?.let { decide(it, closed = false) }
        val v = webView()
        if (claimed && v != null) {
            val press = Any()
            pending = press
            val timeout = Runnable { decide(press, closed = false) }
            pendingTimeout = timeout
            handler.postDelayed(timeout, CLAIM_TIMEOUT_MS)
            v.evaluateJavascript(SCRIPT) { result -> decide(press, closed = result == "true") }
            return
        }
        if (v != null && v.canGoBack()) v.goBack()
        sync()
    }

    /** Decides [press] once; an answer or timeout for an already decided press does nothing. */
    private fun decide(press: Any, closed: Boolean) {
        if (pending !== press) return
        dropPending()
        if (closed) return sync() // the dock closed; the page posts back-claim false itself
        claimed = false
        val v = webView()
        if (v != null && v.canGoBack()) {
            v.goBack()
            sync()
        } else {
            sync() // disabled: no claim, no history
            if (!isEnabled) toSystem()
        }
    }

    companion object {
        /** How long a claimed press waits for the page before falling back. */
        const val CLAIM_TIMEOUT_MS = 300L

        const val SCRIPT = "window.musterBack ? window.musterBack() : false"
    }
}
