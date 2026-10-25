package dev.zacx.muster.feature.shell

import dev.zacx.muster.feature.shell.PullGate.Owner
import dev.zacx.muster.feature.shell.PullGate.Probe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision behind pull to refresh (PullGate): which pulls may refresh. */
class PullGateTest {
    private val slop = 20
    private val agents = "https://m.example.test/agents"
    private val atTop = Probe(docTop = 0, innerTop = null, owner = Owner.PAGE)

    /** One gesture: down at (500, 300), the page's probe, then a pull of [dy] px (sideways by [dx]). */
    private fun gesture(probe: Probe?, dx: Float = 0f, dy: Float = 200f, gate: PullGate = PullGate(slop)): PullGate {
        gate.onDown(500f, 300f)
        probe?.let(gate::onProbe)
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
        assertFalse(gesture(Probe(docTop = 1, innerTop = null, owner = Owner.PAGE)).allowsPull(agents))
        assertFalse(gesture(Probe(docTop = 540, innerTop = null, owner = Owner.PAGE)).allowsPull(agents))
    }

    /**
     * 🔴 The agent page scrolls its chat log inside a document that does not
     * scroll: the document is at 0 (so WebView.scrollY is 0) while the log is
     * scrolled. That pull must scroll the log, not refresh.
     */
    @Test
    fun anInnerScrollerThatIsScrolledKeepsThePullEvenWithTheDocumentAtTop() {
        assertFalse(gesture(Probe(docTop = 0, innerTop = 367, owner = Owner.PAGE)).allowsPull(agents))
        assertFalse(gesture(Probe(docTop = 0, innerTop = 1, owner = Owner.PAGE)).allowsPull(agents))
    }

    @Test
    fun anInnerScrollerAtItsTopRefreshesOnlyIfTheDocumentIsAtItsTopToo() {
        assertTrue(gesture(Probe(docTop = 0, innerTop = 0, owner = Owner.PAGE)).allowsPull(agents))
        assertFalse(gesture(Probe(docTop = 120, innerTop = 0, owner = Owner.PAGE)).allowsPull(agents))
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
        gate.onProbe(atTop)
        gate.onMove(515f, 305f) // sideways, but within the slop: undecided
        gate.onMove(520f, 500f) // then clearly down
        assertTrue(gate.allowsPull(agents))
    }

    @Test
    fun aSecondFingerNeverRefreshes() {
        val gate = PullGate(slop)
        gate.onDown(500f, 300f)
        gate.onProbe(atTop)
        gate.onSecondPointer()
        gate.onMove(500f, 600f)
        assertFalse(gate.allowsPull(agents))
    }

    @Test
    fun overlaysAndElementsWithTheirOwnDragsNeverRefresh() {
        assertFalse(gesture(Probe(0, null, Owner.OVERLAY)).allowsPull(agents))
        assertFalse(gesture(Probe(0, null, Owner.GESTURES)).allowsPull(agents))
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
            "https://m.example.test/tasks/12",
        )) {
            assertTrue(url, PullGate.pageAllowsPull(url))
        }
    }
}
