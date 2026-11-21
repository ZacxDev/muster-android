package dev.zacx.muster.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context

/**
 * [AppMessagesScript.SOURCE] RUN in Rhino (plain JUnit: Rhino does not load
 * under Robolectric's class loader, as with PullProbeScriptJsTest): what
 * muster's page sees, and the page's own detection expression.
 */
class AppMessagesScriptJsTest {
    /** The script RUN (Rhino), not read: what the page sees, and the page's own detection expression. */
    @Test
    fun thePageSeesAFrozenListAndDetectsOpenExternal() {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            fun js(src: String): Any? = cx.evaluateString(scope, src, "test", 1, null)
            js("var window = this; window.top = window; window.musterApp = { postMessage: function () {} };")
            js(AppMessagesScript.SOURCE)
            assertEquals(
                "pair,open-notification-settings,back-claim,touch-start,open-external",
                js("window.musterAppMessages.join(',')"),
            )
            // Read-only and frozen: a page script cannot change what it was told.
            js("try { window.musterAppMessages = ['x']; } catch (e) {} try { window.musterAppMessages.push('x'); } catch (e) {}")
            assertEquals(5.0, (js("window.musterAppMessages.length") as Number).toDouble(), 0.0)
            assertEquals(true, js(DETECT))
            // Without the bridge object the same list detects nothing.
            js("window.musterApp = undefined;")
            assertEquals(false, js(DETECT))
        } finally {
            Context.exit()
        }
    }

    /** A frame inside muster's page is told nothing. */
    @Test
    fun aSubframeIsToldNothing() {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            fun js(src: String): Any? = cx.evaluateString(scope, src, "test", 1, null)
            js("var window = this; window.top = {}; window.musterApp = {};")
            js(AppMessagesScript.SOURCE)
            assertEquals("undefined", js("typeof window.musterAppMessages"))
            assertEquals(false, js(DETECT))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        /** The detection muster's page uses (PR body; AppMessagesScript's doc). */
        const val DETECT = "!!(window.musterApp && Array.isArray(window.musterAppMessages) && window.musterAppMessages.indexOf('open-external') !== -1)"
    }
}
