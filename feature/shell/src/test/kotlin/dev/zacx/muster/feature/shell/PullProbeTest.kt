package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.activity.ComponentActivity
import dev.zacx.muster.feature.shell.PullGate.Owner
import dev.zacx.muster.feature.shell.PullGate.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The probe's two halves: the document-start script's install, and its bridge message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PullProbeTest {
    private val policy = OriginPolicy.forServer("https://m.example.test:8443/agents")!!
    private val origin = "https://m.example.test:8443"
    private val now = Instant.parse("2026-10-24T12:00:00Z")

    private fun parse(data: String, src: String = origin, main: Boolean = true) =
        BridgeMessage.parse(data, src, main, policy, now)

    /** 🔴 The script runs in the paired origin's documents only: exactly that origin, never `*`. */
    @Test
    fun theScriptIsInstalledForExactlyThePairedOrigin() {
        val wv = WebView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
        val calls = mutableListOf<Pair<String, Set<String>>>()
        assertTrue(PullProbeScript.install(wv, policy, supported = true) { _, src, rules -> calls += src to rules })
        assertEquals(listOf(PullProbeScript.SOURCE to setOf("https://m.example.test:8443")), calls)
    }

    @Test
    fun withoutDocumentStartScriptsNothingIsInstalled() {
        val wv = WebView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
        var called = false
        assertFalse(PullProbeScript.install(wv, policy, supported = false) { _, _, _ -> called = true })
        assertFalse(called)
    }

    /** The script reports through the shell's bridge object, as the message type the parser reads, from the main frame only. */
    @Test
    fun theScriptTalksToTheBridgeTheParserReads() {
        val src = PullProbeScript.SOURCE
        assertTrue(src.contains("window.${ShellWebView.BRIDGE_NAME};"))
        assertTrue(src.contains("type: '${PullProbeScript.MESSAGE_TYPE}'"))
        assertTrue(src.contains("if (window.top !== window"))
        assertFalse(src.contains("$"))
    }

    @Test
    fun touchStartMessagesAreUnderstood() {
        assertEquals(
            BridgeMessage.TouchStart(Probe(0, null, Owner.PAGE)),
            parse("""{"type":"touch-start","doc_top":0,"inner_top":null,"owner":"page"}"""),
        )
        assertEquals(
            BridgeMessage.TouchStart(Probe(0, null, Owner.PAGE)),
            parse("""{"type":"touch-start","doc_top":0,"owner":"page"}"""),
        )
        assertEquals(
            BridgeMessage.TouchStart(Probe(12, 367, Owner.OVERLAY)),
            parse("""{"type":"touch-start","doc_top":12,"inner_top":367,"owner":"overlay"}"""),
        )
        assertEquals(
            BridgeMessage.TouchStart(Probe(0, 0, Owner.GESTURES)),
            parse("""{"type":"touch-start","doc_top":0,"inner_top":0,"owner":"gestures"}"""),
        )
    }

    @Test
    fun touchStartFromAnotherFrameOrOriginIsRefused() {
        val msg = """{"type":"touch-start","doc_top":0,"inner_top":null,"owner":"page"}"""
        assertTrue(parse(msg, main = false) is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "https://m.example.test") is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "https://evil.example.test:8443") is BridgeMessage.Refused)
    }

    @Test
    fun malformedTouchStartIsRefused() {
        for (m in listOf(
            """{"type":"touch-start","inner_top":null,"owner":"page"}""",
            """{"type":"touch-start","doc_top":"0","inner_top":null,"owner":"page"}""",
            """{"type":"touch-start","doc_top":0.5,"inner_top":null,"owner":"page"}""",
            """{"type":"touch-start","doc_top":0,"inner_top":"0","owner":"page"}""",
            """{"type":"touch-start","doc_top":0,"inner_top":null,"owner":"refresh"}""",
            """{"type":"touch-start","doc_top":0,"inner_top":null}""",
            """{"type":"touch-start","doc_top":true,"inner_top":null,"owner":"page"}""",
        )) {
            assertTrue(m, parse(m) is BridgeMessage.Refused)
        }
    }
}
