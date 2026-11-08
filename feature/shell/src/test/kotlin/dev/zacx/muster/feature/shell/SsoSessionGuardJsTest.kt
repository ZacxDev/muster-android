package dev.zacx.muster.feature.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * The guard script ([SsoSessionGuard.SOURCE]), RUN, not read: Rhino executes
 * the exact source the WebView gets over a fake window, document, fetch,
 * EventSource and sessionStorage.
 *
 * What this cannot see: a real browser's fetch (does a cross-origin 302 with
 * `redirect: "manual"` really come back `opaqueredirect`; it does per the
 * Fetch standard, but this test hands the script that answer) and htmx's real
 * events (their names and `detail.xhr` are htmx 1.x/2.x's documented ones).
 */
class SsoSessionGuardJsTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        scope = cx.initStandardObjects()
        js(PRELUDE)
        js(SsoSessionGuard.SOURCE)
    }

    @After
    fun tearDown() = Context.exit()

    private fun js(src: String): Any? = cx.evaluateString(scope, src, "test", 1, null)
    private fun int(src: String): Int = (js(src) as Number).toInt()
    private fun reloads() = int("reloads")
    private fun probes() = int("probes.length")

    // --- what makes it probe ------------------------------------------------------

    @Test
    fun anHtmxSendErrorProbesTheGateAndAGateRedirectReloads() {
        js("answer = {type: 'opaqueredirect', status: 0}")
        js("fire('htmx:sendError', {})")
        assertEquals(1, probes())
        assertEquals("/manifest.webmanifest", js("probes[0].url"))
        assertEquals("manual", js("probes[0].opts.redirect"))
        assertEquals("same-origin", js("probes[0].opts.credentials"))
        assertEquals(1, reloads())
    }

    @Test
    fun aGate401ReloadsToo() {
        js("answer = {type: 'basic', status: 401}")
        js("fire('htmx:responseError', {detail: {xhr: {status: 401}}})")
        assertEquals(1, probes())
        assertEquals(1, reloads())
    }

    @Test
    fun anEventSourceThatCloses_probes_andOneThatIsReconnectingDoesNot() {
        js("answer = {type: 'opaqueredirect', status: 0}")
        js("var es = new EventSource('/events'); es.readyState = 0; es.fire('error')")
        assertEquals("reconnecting (CONNECTING): no probe", 0, probes())
        js("es.readyState = 2; es.fire('error')")
        assertEquals(1, probes())
        assertEquals(1, reloads())
        assertEquals("the page still gets a real EventSource", "/events", js("es.url"))
        assertTrue(js("es instanceof RealES") as Boolean)
        assertEquals(2, int("EventSource.CLOSED"))
    }

    // --- what does NOT reload -------------------------------------------------------

    /** The gate let the probe through: muster or the network failed, not the sign-in. */
    @Test
    fun aProbeAnswered200DoesNotReload() {
        js("answer = {type: 'basic', status: 200}")
        js("fire('htmx:sendError', {})")
        assertEquals(1, probes())
        assertEquals(0, reloads())
    }

    /** Offline is not signed out. */
    @Test
    fun aProbeThatFailsOnTheNetworkDoesNotReload() {
        js("answer = 'network-error'")
        js("fire('htmx:sendError', {})")
        assertEquals(1, probes())
        assertEquals(0, reloads())
    }

    /** muster's own 4xx/5xx (a 403, a 500) are muster's answer: no probe at all. */
    @Test
    fun otherHtmxErrorsDoNotProbe() {
        js("answer = {type: 'opaqueredirect', status: 0}")
        js("fire('htmx:responseError', {detail: {xhr: {status: 403}}})")
        js("fire('htmx:responseError', {detail: {xhr: {status: 500}}})")
        js("fire('htmx:responseError', {})")
        assertEquals(0, probes())
        assertEquals(0, reloads())
    }

    /** A gate that keeps refusing cannot loop the page: one reload per 30 s per tab. */
    @Test
    fun reloadsAreAtMostOnePerGap() {
        js("answer = {type: 'opaqueredirect', status: 0}")
        js("now = 1000000; fire('htmx:sendError', {})")
        js("now = 1000000 + ${SsoSessionGuard.MIN_RELOAD_GAP_MS - 1}; fire('htmx:sendError', {})")
        assertEquals(2, probes())
        assertEquals(1, reloads())
        js("now = 1000000 + ${SsoSessionGuard.MIN_RELOAD_GAP_MS}; fire('htmx:sendError', {})")
        assertEquals(2, reloads())
    }

    @Test
    fun aSecondSignalWhileAProbeIsOutIsNotASecondProbe() {
        js("answer = 'pending'")
        js("fire('htmx:sendError', {}); fire('htmx:sendError', {})")
        assertEquals(1, probes())
        js("settle({type: 'opaqueredirect', status: 0})")
        assertEquals(1, reloads())
        js("answer = {type: 'basic', status: 200}; fire('htmx:sendError', {})")
        assertEquals("the next signal probes again", 2, probes())
    }

    /** Not in a frame, and not twice in one document. */
    @Test
    fun itRunsOnceAndInTheMainFrameOnly() {
        js(SsoSessionGuard.SOURCE) // a second injection: a no-op
        js("answer = {type: 'opaqueredirect', status: 0}; fire('htmx:sendError', {})")
        assertEquals(1, probes())

        Context.exit()
        cx = Context.enter()
        scope = cx.initStandardObjects()
        js(PRELUDE)
        js("window.top = {}") // a subframe
        js(SsoSessionGuard.SOURCE)
        js("answer = {type: 'opaqueredirect', status: 0}; fire('htmx:sendError', {})")
        assertEquals(0, probes())
        assertTrue("no EventSource wrapper in a frame", js("EventSource === RealES") as Boolean)
    }

    companion object {
        private val PRELUDE = """
var window = this;
window.top = window;
var now = 5000000;
Date.now = function () { return now; };
var reloads = 0;
window.location = { reload: function () { reloads++; } };
var store = {};
window.sessionStorage = {
  getItem: function (k) { return store.hasOwnProperty(k) ? store[k] : null; },
  setItem: function (k, v) { store[k] = String(v); }
};
var docListeners = {};
var document = {
  addEventListener: function (t, f) { (docListeners[t] = docListeners[t] || []).push(f); }
};
function fire(t, e) { (docListeners[t] || []).forEach(function (f) { f(e); }); }
var answer = null, probes = [], pending = [];
function settle(a) {
  var p = pending; pending = [];
  p.forEach(function (cb) { if (a === 'network-error') cb.err(new Error('net')); else cb.ok(a); });
}
window.fetch = function (url, opts) {
  probes.push({ url: url, opts: opts });
  return { then: function (ok, err) {
    if (answer === 'pending') pending.push({ ok: ok, err: err });
    else if (answer === 'network-error') err(new Error('net'));
    else ok(answer);
  } };
};
function RealES(url) {
  this.url = url; this.readyState = 0; this.ls = {};
}
RealES.prototype.addEventListener = function (t, f) { (this.ls[t] = this.ls[t] || []).push(f); };
RealES.prototype.fire = function (t) { var self = this; (this.ls[t] || []).forEach(function (f) { f({}); }); };
window.EventSource = RealES;
var EventSource = RealES;
""".trimIndent()
    }
}
