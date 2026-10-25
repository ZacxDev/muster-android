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
 * - THIS gesture's probe arrived ([onProbe]: no probe, no pull). Each probe
 *   names its gesture: the script counts the gestures that start in its
 *   document (`seq`, with a per-document id `doc`), and the gate counts the
 *   DOWNs it sees. A probe whose count is not this gesture's (one from an
 *   earlier gesture arriving late, or a count that drifted) is dropped, so
 *   no pull; the next gesture's probe lines up again;
 * - the probe says the document and every inner scroller under the finger are
 *   at their top, and the finger is not on an overlay (a sheet, a dialog, the
 *   open sidebar) or on an element that takes its own vertical gestures;
 * - no text is being edited: the focused element is not a non-empty text
 *   field (an input, a textarea or a contenteditable), so a reload never
 *   throws away a half-typed message;
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
        /** The focused element is a non-empty text field. The script sends this boolean, never the text. */
        val editing: Boolean,
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

    // Pairing a probe with its gesture: DOWNs seen here, and the last probe's place.
    private var downs = 0L
    private var downsAtPageStart = 0L
    private var lastDoc: String? = null
    private var lastSeq = 0L
    private var downsAtLastProbe = 0L

    /** A new gesture: forget the last one's probe and direction, and count it. */
    fun onDown(x: Float, y: Float) {
        downs++
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

    /**
     * A main-frame page started loading: its new document counts its gestures
     * from 1, starting with the next DOWN.
     */
    fun onPageStarted() {
        downsAtPageStart = downs
    }

    /**
     * The page's report, from the document [doc], for the [seq]th gesture
     * that started in it. Kept only if that is the gesture in progress:
     * in the same document as the last probe, [seq] must have moved on by
     * exactly the DOWNs seen since then; in a new document, it must equal the
     * DOWNs seen since the page started. Anything else (a late probe, a count
     * that drifted, a document restored from the back-forward cache) is
     * dropped, and this probe becomes the reference for the next one.
     */
    fun onProbe(doc: String, seq: Long, p: Probe) {
        val expected = if (doc == lastDoc) lastSeq + (downs - downsAtLastProbe) else downs - downsAtPageStart
        lastDoc = doc
        lastSeq = seq
        downsAtLastProbe = downs
        probe = if (seq == expected) p else null
    }

    /** True when a pull starting now may refresh the page showing [url]. */
    fun allowsPull(url: String?): Boolean {
        if (!pageAllowsPull(url)) return false
        if (direction == Direction.SIDEWAYS) return false
        val p = probe ?: return false
        return p.owner == Owner.PAGE && p.atTop && !p.editing
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
            val u = url?.let { LenientUrl.parse(it) } ?: return false
            if (!u.scheme.equals("https", ignoreCase = true)) return false // about:blank, an error page
            val path = u.rawPath ?: return false
            // Split the RAW path first, then decode each segment: an agent's
            // name is path-escaped, so a decoded "%2F" must not shift the
            // segments, while "/agents/x/%74erminal" is still the terminal.
            // A segment that does not decode allows nothing.
            val segs = path.trim('/').split('/').map { decodeSegment(it) ?: return false }
            return when {
                segs.size >= 2 && segs[0] == "mirror" && (segs[1] == "terminal" || segs[1] == "view") -> false
                segs.size >= 3 && segs[0] == "agents" && segs[2] == "terminal" -> false
                else -> true
            }
        }

        /** Strict percent-decoding of one path segment as UTF-8 (`+` stays `+`); null for a bad escape or bad UTF-8. */
        fun decodeSegment(s: String): String? {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    // A truncated escape ("%4" at the end) reads as a non-hex digit.
                    val hi = Character.digit(s.getOrElse(i + 1) { 'x' }, 16)
                    val lo = Character.digit(s.getOrElse(i + 2) { 'x' }, 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
            }.getOrNull()
        }
    }
}
