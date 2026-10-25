package dev.zacx.muster.feature.shell

import dev.zacx.muster.core.model.LenientUrl
import kotlin.math.abs

/**
 * Whether a downward pull on the shell may start a refresh: the decision
 * behind [ShellRefreshLayout.canChildScrollUp], with no Android in it.
 *
 * A pull refreshes only when nothing under the finger can scroll up. The
 * WebView's own `scrollY` cannot answer that: muster's agent page scrolls its
 * chat log (`#chat-log`) inside a document that does not scroll at all, so
 * `scrollY` is 0 there however far up the log the reader is. The page answers
 * instead: at every `touchstart` the shell's document-start script
 * ([PullProbeScript]) reports, over the `musterApp` bridge, how far the
 * document is scrolled and how far the vertical scrollers between the finger
 * and the document are ([Probe]).
 *
 * A pull is allowed only when ALL of these hold for the current gesture:
 * - the page allows it ([pageAllowsPull]: not the terminal or a mirrored
 *   session, which own their vertical drags);
 * - a probe arrived for this gesture (one from an earlier gesture is dropped
 *   at [onDown]; no probe, no pull);
 * - the probe says the document and every inner scroller under the finger are
 *   at their top, and the finger is not on an overlay (a sheet, a dialog, the
 *   open sidebar) or on an element that takes its own vertical gestures;
 * - the gesture is one finger, and its first movement past the touch slop was
 *   more vertical than sideways (a swipe along a chip row never refreshes).
 */
class PullGate(private val touchSlop: Int) {

    /** What the page under the finger says at `touchstart` (the `touch-start` bridge message). */
    data class Probe(
        /** The document's scroll offset, CSS px (with any pinch-zoom offset added). */
        val docTop: Int,
        /** The largest offset among the inner vertical scrollers under the finger; null when there are none. */
        val innerTop: Int?,
        val owner: Owner,
    ) {
        val atTop: Boolean get() = docTop <= 0 && (innerTop ?: 0) <= 0
    }

    /** Who owns a vertical drag that starts where the finger is. */
    enum class Owner(val wire: String) {
        /** Nothing in particular: the page's ordinary scrolling. */
        PAGE("page"),

        /** An overlay: anything fixed-position (sheets, menus, the sidebar), a dialog. */
        OVERLAY("overlay"),

        /** An element that handles its own drags (`touch-action` without pan-y, xterm). */
        GESTURES("gestures"),
        ;

        companion object {
            fun fromWire(s: String?): Owner? = entries.firstOrNull { it.wire == s }
        }
    }

    private enum class Direction { UNDECIDED, VERTICAL, SIDEWAYS }

    private var probe: Probe? = null
    private var direction = Direction.UNDECIDED
    private var downX = 0f
    private var downY = 0f

    /** A new gesture: forget the last one's probe and direction. */
    fun onDown(x: Float, y: Float) {
        probe = null
        direction = Direction.UNDECIDED
        downX = x
        downY = y
    }

    /** A second finger: this gesture is a pinch or a multi-touch, never a pull. */
    fun onSecondPointer() {
        direction = Direction.SIDEWAYS
    }

    /** The finger moved: once past the slop, the first dominant axis decides the gesture. */
    fun onMove(x: Float, y: Float) {
        if (direction != Direction.UNDECIDED) return
        val dx = abs(x - downX)
        val dy = abs(y - downY)
        if (dx <= touchSlop && dy <= touchSlop) return
        direction = if (dx >= dy) Direction.SIDEWAYS else Direction.VERTICAL
    }

    /** The page's report for the gesture in progress. */
    fun onProbe(p: Probe) {
        probe = p
    }

    /** True when a pull starting now may refresh the page showing [url]. */
    fun allowsPull(url: String?): Boolean {
        if (!pageAllowsPull(url)) return false
        if (direction == Direction.SIDEWAYS) return false
        val p = probe ?: return false
        return p.owner == Owner.PAGE && p.atTop
    }

    companion object {
        /**
         * Pages that own their vertical drags, where a pull never refreshes:
         * the terminal (`/agents/{name}/terminal`, `/mirror/terminal`: a drag
         * scrolls the remote pane) and a mirrored session (`/mirror/view`: a
         * live log with its own scroll and composer). A URL that does not
         * parse, or is not https, allows nothing.
         */
        fun pageAllowsPull(url: String?): Boolean {
            // The raw path: an agent's name is path-escaped, so a decoded
            // "%2F" must not shift the segments.
            val u = url?.let { LenientUrl.parse(it) } ?: return false
            if (!u.scheme.equals("https", ignoreCase = true)) return false // about:blank, an error page
            val path = u.rawPath ?: return false
            val segs = path.trim('/').split('/')
            return when {
                path == "/mirror/terminal" || path.startsWith("/mirror/terminal/") -> false
                path == "/mirror/view" || path.startsWith("/mirror/view/") -> false
                segs.size >= 3 && segs[0] == "agents" && segs[2] == "terminal" -> false
                else -> true
            }
        }
    }
}
