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
            BridgeMessage.TouchStart("k3x9q2", 1, Probe(0, null, Owner.PAGE, editing = false)),
            parse("""{"type":"touch-start","doc":"k3x9q2","seq":1,"doc_top":0,"inner_top":null,"owner":"page","editing":false}"""),
        )
        assertEquals(
            BridgeMessage.TouchStart("k3x9q2", 2, Probe(0, null, Owner.PAGE, editing = true)),
            parse("""{"type":"touch-start","doc":"k3x9q2","seq":2,"doc_top":0,"owner":"page","editing":true}"""),
        )
        assertEquals(
            BridgeMessage.TouchStart("a", 41, Probe(12, 367, Owner.OVERLAY, editing = false)),
            parse("""{"type":"touch-start","doc":"a","seq":41,"doc_top":12,"inner_top":367,"owner":"overlay","editing":false}"""),
        )
        assertEquals(
            BridgeMessage.TouchStart("0123456789abcdef", 3, Probe(0, 0, Owner.GESTURES, editing = false)),
            parse("""{"type":"touch-start","doc":"0123456789abcdef","seq":3,"doc_top":0,"inner_top":0,"owner":"gestures","editing":false}"""),
        )
    }

    @Test
    fun touchStartFromAnotherFrameOrOriginIsRefused() {
        val msg = """{"type":"touch-start","doc":"k3","seq":1,"doc_top":0,"inner_top":null,"owner":"page","editing":false}"""
        assertTrue(parse(msg) is BridgeMessage.TouchStart) // control
        assertTrue(parse(msg, main = false) is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "https://m.example.test") is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "https://evil.example.test:8443") is BridgeMessage.Refused)
    }

    @Test
    fun malformedTouchStartIsRefused() {
        val ok = """"doc":"k3","seq":1,"doc_top":0,"inner_top":null,"owner":"page","editing":false"""
        assertTrue("control", parse("""{"type":"touch-start",$ok}""") is BridgeMessage.TouchStart)
        for (m in listOf(
            ok.replace(""""doc_top":0,""", ""),
            ok.replace(""""doc_top":0""", """"doc_top":"0""""),
            ok.replace(""""doc_top":0""", """"doc_top":0.5"""),
            ok.replace(""""doc_top":0""", """"doc_top":true"""),
            ok.replace(""""inner_top":null""", """"inner_top":"0""""),
            ok.replace(""""owner":"page"""", """"owner":"refresh""""),
            ok.replace(""","owner":"page"""", ""),
            // The gesture's identity: a document id and a count from 1.
            ok.replace(""""doc":"k3",""", ""),
            ok.replace(""""doc":"k3"""", """"doc":"K3""""),
            ok.replace(""""doc":"k3"""", """"doc":"""""),
            ok.replace(""""doc":"k3"""", """"doc":"0123456789abcdefg""""),
            ok.replace(""""doc":"k3"""", """"doc":3"""),
            ok.replace(""""seq":1,""", ""),
            ok.replace(""""seq":1""", """"seq":0"""),
            ok.replace(""""seq":1""", """"seq":-1"""),
            ok.replace(""""seq":1""", """"seq":"1""""),
            ok.replace(""""seq":1""", """"seq":1.5"""),
            // editing: a JSON boolean, always sent.
            ok.replace(""","editing":false""", ""),
            ok.replace(""""editing":false""", """"editing":"false""""),
            ok.replace(""""editing":false""", """"editing":0"""),
            ok.replace(""""editing":false""", """"editing":null"""),
        )) {
            assertTrue(m, parse("""{"type":"touch-start",$m}""") is BridgeMessage.Refused)
        }
    }
}
