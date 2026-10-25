package dev.zacx.muster.feature.shell

import dev.zacx.muster.feature.shell.PullGate.Owner
import dev.zacx.muster.feature.shell.PullGate.Probe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The decision behind pull to refresh (PullGate): which pulls may refresh. */
class PullGateTest {
    private val slop = 20
    private val agents = "https://m.example.test/agents"
    private val atTop = Probe(docTop = 0, innerTop = null, owner = Owner.PAGE, editing = false)
    private val doc = "k3x9q2"

    /** The page's count of gestures, per gate: what the script would send. */
    private val counts = HashMap<PullGate, Long>()

    /** One gesture: down at (500, 300), the page's probe for it, then a pull of [dy] px (sideways by [dx]). */
    private fun gesture(probe: Probe?, dx: Float = 0f, dy: Float = 200f, gate: PullGate = PullGate(slop)): PullGate {
        gate.onDown(500f, 300f)
        val n = (counts[gate] ?: 0L) + 1
        counts[gate] = n
        probe?.let { gate.onProbe(doc, n, it) }
        gate.onMove(500f + dx, 300f + dy)
        return gate
    }

    @Test
    fun aPullAtTheTopOfTheDocumentRefreshes() {
        assertTrue(gesture(atTop).allowsPull(agents))
        assertTrue(gesture(atTop).allowsPull("https://m.example.test/"))
    }

    @Test
    fun aPullWhileTheDocumentIsScrolledOnlyScrolls() {
        assertFalse(gesture(atTop.copy(docTop = 1)).allowsPull(agents))
        assertFalse(gesture(atTop.copy(docTop = 540)).allowsPull(agents))
    }

    /**
     * 🔴 The agent page scrolls its chat log inside a document that does not
     * scroll: the document is at 0 (so WebView.scrollY is 0) while the log is
     * scrolled. That pull must scroll the log, not refresh.
     */
    @Test
    fun anInnerScrollerThatIsScrolledKeepsThePullEvenWithTheDocumentAtTop() {
        assertFalse(gesture(atTop.copy(innerTop = 367)).allowsPull(agents))
        assertFalse(gesture(atTop.copy(innerTop = 1)).allowsPull(agents))
    }

    @Test
    fun anInnerScrollerAtItsTopRefreshesOnlyIfTheDocumentIsAtItsTopToo() {
        assertTrue(gesture(atTop.copy(innerTop = 0)).allowsPull(agents))
        assertFalse(gesture(atTop.copy(docTop = 120, innerTop = 0)).allowsPull(agents))
    }

    @Test
    fun noProbeForThisGestureNoPull() {
        assertFalse(gesture(probe = null).allowsPull(agents))
        // The last gesture's probe does not carry over to the next one.
        val gate = gesture(atTop)
        assertTrue(gate.allowsPull(agents))
        gesture(probe = null, gate = gate)
        assertFalse(gate.allowsPull(agents))
    }

    @Test
    fun aSidewaysSwipeNeverRefreshes() {
        // Along a chip row, drifting down well past the slop: sideways wins.
        assertFalse(gesture(atTop, dx = 300f, dy = 120f).allowsPull(agents))
        assertFalse(gesture(atTop, dx = -300f, dy = 120f).allowsPull(agents))
        // Exactly diagonal counts as sideways.
        assertFalse(gesture(atTop, dx = 150f, dy = 150f).allowsPull(agents))
        // Once decided, the gesture stays decided: a sideways swipe that turns down is still sideways.
        val gate = gesture(atTop, dx = 300f, dy = 30f)
        gate.onMove(800f, 900f)
        assertFalse(gate.allowsPull(agents))
    }

    @Test
    fun jitterWithinTheSlopDoesNotDecideTheDirection() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f)
        gate.onProbe(doc, 1, atTop)
        gate.onMove(515f, 305f) // sideways, but within the slop: undecided
        gate.onMove(520f, 500f) // then clearly down
        assertTrue(gate.allowsPull(agents))
    }

    @Test
    fun aSecondFingerNeverRefreshes() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f)
        gate.onProbe(doc, 1, atTop)
        gate.onSecondPointer()
        gate.onMove(500f, 600f)
        assertFalse(gate.allowsPull(agents))
    }

    @Test
    fun overlaysAndElementsWithTheirOwnDragsNeverRefresh() {
        assertFalse(gesture(atTop.copy(owner = Owner.OVERLAY)).allowsPull(agents))
        assertFalse(gesture(atTop.copy(owner = Owner.GESTURES)).allowsPull(agents))
    }

    /** A half-typed message: a reload would throw it away, so the pull only scrolls. */
    @Test
    fun noPullWhileATextFieldHasText() {
        assertFalse(gesture(atTop.copy(editing = true)).allowsPull(agents))
        assertTrue("control: the same probe, nothing typed", gesture(atTop.copy(editing = false)).allowsPull(agents))
    }

    /** Each gesture's own probe, in order: every one is kept. */
    @Test
    fun probesInStepWithTheirGesturesAreKept() {
        val gate = PullGate(slop)
        for (n in 1L..4L) {
            gate.onDown(500f, 300f)
            gate.onProbe(doc, n, atTop)
            gate.onMove(500f, 600f)
            assertTrue("gesture $n", gate.allowsPull(agents))
        }
    }

    /**
     * 🔴 A probe that arrives late, after the NEXT gesture's DOWN, describes
     * the previous gesture's touch point. It must not decide this gesture,
     * and neither may this gesture's own probe right after it (the counts no
     * longer line up); the gesture after that lines up again.
     */
    @Test
    fun aLateProbeFromThePreviousGestureMeansNoPull() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f) // gesture 1: its probe is slow
        gate.onDown(500f, 300f) // gesture 2
        gate.onProbe(doc, 1, atTop) // gesture 1's, late
        gate.onMove(500f, 600f)
        assertFalse("a late probe decides nothing", gate.allowsPull(agents))
        gate.onProbe(doc, 2, atTop)
        assertFalse("out of step: still no pull", gate.allowsPull(agents))
        gate.onDown(500f, 300f) // gesture 3
        gate.onProbe(doc, 3, atTop)
        gate.onMove(500f, 600f)
        assertTrue("in step again", gate.allowsPull(agents))
    }

    @Test
    fun aProbeFromTheFutureOrBeforeAnyDownIsDropped() {
        val gate = PullGate(slop)
        gate.onProbe(doc, 1, atTop) // before any DOWN
        gate.onDown(500f, 300f)
        gate.onMove(500f, 600f)
        assertFalse(gate.allowsPull(agents))
        val g2 = PullGate(slop)
        g2.onDown(500f, 300f)
        g2.onProbe(doc, 2, atTop) // the page counted a gesture the app never saw
        g2.onMove(500f, 600f)
        assertFalse(g2.allowsPull(agents))
    }

    /** A reload starts a new document, which counts from 1 again: its first probe is kept. */
    @Test
    fun aNewDocumentCountsFromOneAfterThePageStarts() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f)
        gate.onProbe(doc, 1, atTop)
        gate.onMove(500f, 600f)
        assertTrue(gate.allowsPull(agents))
        gate.onPageStarted() // the pull's reload
        gate.onDown(500f, 300f)
        gate.onProbe("n3wd0c", 1, atTop)
        gate.onMove(500f, 600f)
        assertTrue(gate.allowsPull(agents))
    }

    /** The old document's last probe, late past the reload and the new document's first DOWN: dropped; the new one's own is kept. */
    @Test
    fun theOldDocumentsLateProbeAfterAReloadIsDropped() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f)
        gate.onProbe(doc, 1, atTop) // gesture 1
        gate.onDown(500f, 300f) // gesture 2: its probe is slow
        gate.onPageStarted() // a reload
        gate.onDown(500f, 300f) // gesture 3, the new document's first
        gate.onProbe(doc, 2, atTop) // gesture 2's, late
        gate.onMove(500f, 600f)
        assertFalse(gate.allowsPull(agents))
        gate.onProbe("n3wd0c", 1, atTop) // gesture 3's own
        assertTrue(gate.allowsPull(agents))
    }

    /** Without the page start, a new document's first probe is not this gesture's: the counts say so. */
    @Test
    fun aNewDocumentWithoutItsPageStartIsOutOfStep() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f)
        gate.onProbe(doc, 1, atTop)
        gate.onDown(500f, 300f)
        gate.onProbe("n3wd0c", 1, atTop) // two DOWNs since the (only) page start
        gate.onMove(500f, 600f)
        assertFalse(gate.allowsPull(agents))
    }

    /** The terminal and a mirrored session own their vertical drags. */
    @Test
    fun theTerminalAndMirrorPagesDisablePull() {
        for (url in listOf(
            "https://m.example.test/agents/clever-shrew/terminal",
            "https://m.example.test/agents/clever-shrew/terminal/",
            "https://m.example.test/agents/a%2Fb/terminal",
            "https://m.example.test/mirror/terminal?host=h&session=s",
            "https://m.example.test/mirror/view?host=h&session=s",
            // Percent-encoded segments are decoded before matching.
            "https://m.example.test/agents/clever-shrew/%74erminal",
            "https://m.example.test/agents/clever-shrew/%74%65rminal",
            "https://m.example.test/%6Dirror/view?host=h",
            "https://m.example.test/mirror/%76iew",
            "https://m.example.test/mirror/%74erminal/",
            "https://m.example.test/%61gents/x/terminal",
            // A segment that does not decode allows nothing.
            "https://m.example.test/agents/x/%zz",
            "https://m.example.test/agents/%C3",
            "https://m.example.test/agents/x/%7",
            "not a url \u0000",
            "about:blank",
            "http://m.example.test/agents",
            null,
        )) {
            assertFalse(url.toString(), gesture(atTop).allowsPull(url))
            assertFalse(url.toString(), PullGate.pageAllowsPull(url))
        }
        for (url in listOf(
            "https://m.example.test/",
            agents,
            "https://m.example.test/attention",
            "https://m.example.test/agents/clever-shrew",
            "https://m.example.test/agents/terminal", // an agent named "terminal"
            "https://m.example.test/agents/a%2Fterminal", // one segment, "a/terminal": not shifted
            "https://m.example.test/agents/%E2%82%AC", // valid UTF-8
            "https://m.example.test/agents/a+b",
            "https://m.example.test/tasks/12",
        )) {
            assertTrue(url, PullGate.pageAllowsPull(url))
        }
    }

    @Test
    fun segmentsDecodeStrictly() {
        assertEquals("terminal", PullGate.decodeSegment("%74erminal"))
        assertEquals("a/b", PullGate.decodeSegment("a%2Fb"))
        assertEquals("a+b", PullGate.decodeSegment("a+b")) // a path's "+" is a plus
        assertEquals("\u20ac", PullGate.decodeSegment("%E2%82%AC"))
        assertEquals("plain", PullGate.decodeSegment("plain"))
        for (bad in listOf("%", "%7", "%zz", "x%G0", "%C3", "%FF")) assertNull(bad, PullGate.decodeSegment(bad))
    }
}
