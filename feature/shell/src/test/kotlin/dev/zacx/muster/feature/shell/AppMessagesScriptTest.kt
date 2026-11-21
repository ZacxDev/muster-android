package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * How muster's page learns which bridge messages this app understands
 * (`window.musterAppMessages`, [AppMessagesScript]), and that the list it is
 * told is the list the parser really accepts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppMessagesScriptTest {
    private val policy = OriginPolicy.forServer("https://m.example.test:8443/agents", listOf("https://sso.example.test"))!!

    /** The advertised list, pinned as a literal: a type added or dropped must change this test. */
    @Test
    fun theAdvertisedTypesAreExactlyThese() {
        assertEquals(
            listOf("pair", "open-notification-settings", "back-claim", "touch-start", "open-external"),
            BridgeMessage.TYPES,
        )
    }

    /** 🔴 Ledger: every advertised type is one the parser has a branch for, and an unadvertised one is not. */
    @Test
    fun everyAdvertisedTypeIsOneTheParserUnderstands() {
        val unknown = BridgeMessage.Refused("unknown message")
        fun parse(t: String) = BridgeMessage.parse("""{"type":"$t"}""", "https://m.example.test:8443", true, policy, Instant.EPOCH)
        BridgeMessage.TYPES.forEach { assertNotEquals(it, unknown, parse(it)) }
        listOf("theme", "open-url", "open", "Open-External", "open-external ").forEach { assertEquals(it, unknown, parse(it)) }
    }

    /** 🔴 Exactly the muster origin (never `*`, never the sign-in origin). */
    @Test
    fun theScriptIsInstalledForExactlyTheMusterOrigin() {
        val wv = WebView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
        val calls = mutableListOf<Pair<String, Set<String>>>()
        assertTrue(AppMessagesScript.install(wv, policy, supported = true) { _, src, rules -> calls += src to rules })
        assertEquals(listOf(AppMessagesScript.SOURCE to setOf("https://m.example.test:8443")), calls)
    }

    @Test
    fun withoutDocumentStartScriptsNothingIsInstalled() {
        val wv = WebView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
        var called = false
        assertFalse(AppMessagesScript.install(wv, policy, supported = false) { _, _, _ -> called = true })
        assertFalse(called)
    }

}
