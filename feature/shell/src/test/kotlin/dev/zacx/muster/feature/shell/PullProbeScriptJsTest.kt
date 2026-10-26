package dev.zacx.muster.feature.shell

import dev.zacx.muster.feature.shell.PullGate.Owner
import dev.zacx.muster.feature.shell.PullGate.Probe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.time.Instant

/**
 * The injected script itself ([PullProbeScript.SOURCE]), RUN, not read: Rhino
 * executes the exact source the WebView gets, over a small fake DOM, and every
 * message it posts goes through the app's own parser ([BridgeMessage.parse]).
 *
 * What this cannot see: a real browser's layout, `getComputedStyle` and touch
 * events. The fake DOM hands the script the values a browser would; which
 * values muster's real pages produce is the emulator table's (PR body).
 */
class PullProbeScriptJsTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    private val policy = OriginPolicy.forServer("https://m.example.test")!!

    @Before
    fun setUp() {
        cx = Context.enter()
        scope = cx.initStandardObjects()
        js(PRELUDE)
        js(PullProbeScript.SOURCE)
    }

    @After
    fun tearDown() = Context.exit()

    private fun js(src: String): Any? = cx.evaluateString(scope, src, "test", 1, null)

    private fun posted(): List<String> =
        (js("JSON.stringify(posted)") as String).let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            .let { it as kotlinx.serialization.json.JsonArray }.map { (it as kotlinx.serialization.json.JsonPrimitive).content }

    /** What the app makes of the last message: through the real parser. */
    private fun lastParsed(): BridgeMessage =
        BridgeMessage.parse(posted().last(), "https://m.example.test", true, policy, Instant.parse("2026-10-24T12:00:00Z"))

    private fun lastProbe(): BridgeMessage.TouchStart = lastParsed() as BridgeMessage.TouchStart

    @Test
    fun eachOneFingerGestureIsCountedFromOneInOneDocument() {
        js("touch(body)")
        js("touch(body)")
        js("touch(body)")
        val msgs = posted().map {
            BridgeMessage.parse(it, "https://m.example.test", true, policy, Instant.EPOCH) as BridgeMessage.TouchStart
        }
        assertEquals(listOf(1L, 2L, 3L), msgs.map { it.seq })
        assertEquals("one id for the document", 1, msgs.map { it.doc }.distinct().size)
        assertTrue(msgs.first().doc, Regex("[0-9a-z]{1,16}").matches(msgs.first().doc))
        assertEquals(Probe(0, null, Owner.PAGE, editing = false), msgs.first().probe)
    }

    /**
     * A second finger landing is not a new gesture (the app sees a
     * POINTER_DOWN, not a DOWN): not counted, not sent. Two fingers landing
     * together ARE a new gesture: counted (the app saw its DOWN), not sent.
     */
    @Test
    fun countsGesturesTheWayTheAppCountsDowns() {
        js("touch(body)") // gesture 1
        js("touch(body, 2, 1)") // a second finger joins gesture 1
        js("touch(body, 2, 2)") // gesture 2: two fingers at once
        js("touch(body)") // gesture 3
        assertEquals(2, posted().size)
        assertEquals(3L, lastProbe().seq)
    }

    @Test
    fun scrollOffsetsOfTheDocumentAndTheScrollersUnderTheFinger() {
        js("html.scrollTop = 540; touch(body)")
        assertEquals(Probe(540, null, Owner.PAGE, editing = false), lastProbe().probe)
        // The agent page: the document at 0, #chat-log scrolled inside it.
        js("html.scrollTop = 0; var log = el({ parent: body, oy: 'auto', sh: 4000, ch: 600, st: 366.2 }); var row = el({ parent: log }); touch(row)")
        assertEquals(Probe(0, 367, Owner.PAGE, editing = false), lastProbe().probe)
        // A scroller that does not overflow is not one.
        js("var box = el({ parent: body, oy: 'auto', sh: 300, ch: 300, st: 0 }); touch(el({ parent: box }))")
        assertEquals(Probe(0, null, Owner.PAGE, editing = false), lastProbe().probe)
        // Pinch-zoom offset counts as scrolled.
        js("window.visualViewport.offsetTop = 12.5; touch(body)")
        assertEquals(13, lastProbe().probe.docTop)
    }

    @Test
    fun overlaysAndElementsWithTheirOwnDrags() {
        js("touch(el({ parent: el({ parent: body, pos: 'fixed' }) }))")
        assertEquals(Owner.OVERLAY, lastProbe().probe.owner)
        js("touch(el({ parent: el({ parent: body, dialog: true }) }))")
        assertEquals(Owner.OVERLAY, lastProbe().probe.owner)
        js("touch(el({ parent: el({ parent: body, ta: 'none' }) }))")
        assertEquals(Owner.GESTURES, lastProbe().probe.owner)
        js("touch(el({ parent: el({ parent: body, cls: ['xterm'] }) }))")
        assertEquals(Owner.GESTURES, lastProbe().probe.owner)
        js("touch(el({ parent: el({ parent: body, ta: 'pan-y' }) }))")
        assertEquals(Owner.PAGE, lastProbe().probe.owner)
        js("modal = true; touch(body)")
        assertEquals(Owner.OVERLAY, lastProbe().probe.owner)
    }

    /** `editing` is true only for a focused, non-empty text field, and the text never leaves the page. */
    @Test
    fun editingIsABooleanAndTheTextIsNeverSent() {
        val cases = listOf(
            "el({ parent: body, tag: 'INPUT', type: 'text', value: 'secret-draft' })" to true,
            "el({ parent: body, tag: 'INPUT', value: 'secret-draft' })" to true, // no type: text
            "el({ parent: body, tag: 'INPUT', type: 'search', value: 'secret-draft' })" to true,
            "el({ parent: body, tag: 'TEXTAREA', value: 'secret-draft' })" to true,
            "el({ parent: body, ce: true, text: 'secret-draft' })" to true,
            "el({ parent: body, tag: 'INPUT', type: 'text', value: '' })" to false,
            "el({ parent: body, tag: 'TEXTAREA', value: '' })" to false,
            "el({ parent: body, ce: true, text: '' })" to false,
            "el({ parent: body, tag: 'INPUT', type: 'checkbox', value: 'secret-draft' })" to false,
            "el({ parent: body, tag: 'BUTTON' })" to false,
            "body" to false,
            "null" to false,
        )
        for ((focus, want) in cases) {
            js("document.activeElement = $focus; touch(body)")
            assertEquals(focus, want, lastProbe().probe.editing)
        }
        assertFalse("no text in any message", posted().any { "secret-draft" in it })
    }

    @Test
    fun nothingIsSentWithoutTheBridgeButTheGestureIsStillCounted() {
        js("var saved = musterApp; musterApp = undefined; touch(body); musterApp = saved; touch(body)")
        assertEquals(1, posted().size)
        assertEquals(2L, lastProbe().seq)
    }

    @Test
    fun installedTwiceListensOnce() {
        js(PullProbeScript.SOURCE)
        assertEquals(1.0, Context.toNumber(js("listeners.length")), 0.0)
    }

    @Test
    fun aSubframeRunsNothing() {
        Context.exit()
        cx = Context.enter()
        scope = cx.initStandardObjects()
        js(PRELUDE)
        js("window.top = {}")
        js(PullProbeScript.SOURCE)
        assertEquals(0.0, Context.toNumber(js("listeners.length")), 0.0)
    }

    companion object {
        /** A fake browser: just what the script reads. */
        private val PRELUDE = """
var window = this;
window.top = window;
var posted = [];
var musterApp = { postMessage: function (s) { posted.push(s); } };
var listeners = [];
var modal = false;
function el(o) {
  o = o || {};
  return {
    nodeType: 1, parentElement: o.parent || null,
    scrollHeight: o.sh || 100, clientHeight: o.ch || 100, scrollTop: o.st || 0,
    style: { overflowY: o.oy || 'visible', position: o.pos || 'static', touchAction: o.ta || 'auto' },
    classList: { contains: function (c) { return (o.cls || []).indexOf(c) >= 0; } },
    matches: function (sel) { return !!o.dialog; },
    tagName: o.tag || 'DIV', type: o.type, value: o.value,
    isContentEditable: !!o.ce, textContent: o.text || ''
  };
}
var html = el({ tag: 'HTML' });
var body = el({ tag: 'BODY', parent: html });
var document = {
  documentElement: html, body: body, scrollingElement: html, activeElement: body,
  addEventListener: function (t, f, opts) { if (t === 'touchstart') listeners.push(f); },
  querySelector: function () { return modal ? {} : null; }
};
function getComputedStyle(e) { return e.style; }
window.visualViewport = { offsetTop: 0 };
function touch(target, n, changed) {
  n = n || 1; changed = changed || n;
  var ts = [], cs = [], i;
  for (i = 0; i < n; i++) ts.push({});
  for (i = 0; i < changed; i++) cs.push({});
  for (i = 0; i < listeners.length; i++) listeners[i]({ touches: ts, changedTouches: cs, target: target });
}
"""
    }
}
