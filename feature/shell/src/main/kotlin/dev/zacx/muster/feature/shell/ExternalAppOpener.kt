package dev.zacx.muster.feature.shell

import android.content.Context
import android.os.SystemClock

/**
 * The gesture and rate gate in front of [ExternalAppPage.open]: an
 * `open-external` is acted on only if a `touch-start` bridge message
 * ([BridgeMessage.TouchStart], the app's own document-start script, posted at
 * a one-finger `touchstart`) arrived within the last [WINDOW_MS], and at most
 * once per [WINDOW_MS]. A page posting `open-external` in a loop, or with no
 * one touching the screen, opens at most one browser tab, and then only
 * right after a touch.
 *
 * `touch-start` exists only where document-start scripts do, and so does the
 * list that advertises `open-external` ([AppMessagesScript]); a WebView
 * without them is never told the message exists, so muster shows its
 * copy-link card instead of a button that could not pass this gate.
 */
class ExternalAppOpener(
    private val context: Context,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val open: (Context, BridgeMessage.OpenExternal) -> Boolean = ExternalAppPage::open,
) {
    enum class Outcome { OPENED, NO_RECENT_TOUCH, TOO_SOON, NOT_STARTED }

    private var lastTouch: Long? = null
    private var lastOpen: Long? = null

    /** A `touch-start` arrived from muster's main frame. */
    fun onTouchStart() {
        lastTouch = clock()
    }

    fun onOpenExternal(message: BridgeMessage.OpenExternal): Outcome {
        val now = clock()
        val touch = lastTouch
        if (touch == null || now - touch > WINDOW_MS) return Outcome.NO_RECENT_TOUCH
        val opened = lastOpen
        if (opened != null && now - opened < WINDOW_MS) return Outcome.TOO_SOON
        if (!open(context, message)) return Outcome.NOT_STARTED
        lastOpen = now
        return Outcome.OPENED
    }

    companion object {
        const val WINDOW_MS = 2_000L
    }
}
