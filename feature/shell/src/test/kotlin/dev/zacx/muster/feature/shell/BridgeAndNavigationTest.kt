package dev.zacx.muster.feature.shell

import dev.zacx.muster.feature.shell.ShellNavigation.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BridgeAndNavigationTest {
    private val policy = OriginPolicy.forServer("https://m.example.test")!!
    private val origin = "https://m.example.test"
    private val code = "mdp_" + "A".repeat(43)
    private val now = Instant.parse("2026-10-21T12:00:00Z")

    private fun parse(data: String?, src: String = origin, main: Boolean = true) =
        BridgeMessage.parse(data, src, main, policy, now)

    /** The two messages muster's Account → Devices page sends (internal/api/devices.go appBridgeJS). */
    @Test
    fun mustersMessagesAreUnderstood() {
        assertEquals(
            BridgeMessage.Pair(code, Instant.parse("2026-10-21T12:01:00Z")),
            parse("""{"type":"pair","code":"$code","expires_at":"2026-10-21T12:01:00Z"}"""),
        )
        assertEquals(BridgeMessage.OpenNotificationSettings, parse("""{"type":"open-notification-settings"}"""))
    }

    @Test
    fun onlyTheMainMusterFrameMayTalk() {
        val msg = """{"type":"pair","code":"$code"}"""
        assertTrue(parse(msg, main = false) is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "https://evil.example.test") is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "http://m.example.test") is BridgeMessage.Refused)
        assertTrue(parse(msg, src = "null") is BridgeMessage.Refused)
    }

    @Test
    fun malformedOrExpiredPairMessagesAreRefused() {
        assertTrue(parse("""{"type":"pair"}""") is BridgeMessage.Refused)
        assertTrue(parse("""{"type":"pair","code":"mdv_${"A".repeat(43)}"}""") is BridgeMessage.Refused)
        assertTrue(parse("""{"type":"pair","code":"$code","expires_at":"2026-10-21T11:59:59Z"}""") is BridgeMessage.Refused)
        assertTrue(parse("""{"type":"theme","value":"dark"}""") is BridgeMessage.Refused)
        assertTrue(parse("""not json""") is BridgeMessage.Refused)
        assertTrue(parse(null) is BridgeMessage.Refused)
        assertTrue(parse("""{"type":"pair","code":"$code","pad":"${"x".repeat(1100)}"}""") is BridgeMessage.Refused)
    }

    @Test
    fun navigation() {
        assertEquals(Verdict.WEBVIEW, ShellNavigation.decide("https://m.example.test/attention", policy))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("https://docs.example.test/", policy))
        assertEquals(Verdict.REFUSED, ShellNavigation.decide("intent://x#Intent;end", policy))
        assertEquals(Verdict.REFUSED, ShellNavigation.decide("javascript:alert(1)", policy))
        assertEquals(Verdict.REFUSED, ShellNavigation.decide("http://m.example.test/", policy))
    }

    /** 🔴 With the gate closed, the shell loads no attachment, in any frame. */
    @Test
    fun attachmentsAreNotLoadedWhileTheGateIsClosed() {
        val a = "https://m.example.test/tasks/42/attachments/7"
        assertEquals(Verdict.BLOCKED_ATTACHMENT, ShellNavigation.decide(a, policy, sandboxConfirmed = false))
        assertTrue(ShellNavigation.blocksSubresource(a, policy, sandboxConfirmed = false))
        assertFalse(ShellNavigation.blocksSubresource("https://m.example.test/static/app.js", policy, sandboxConfirmed = false))
        assertFalse(ShellNavigation.blocksSubresource("https://cdn.example.test/tasks/1/attachments/2", policy, sandboxConfirmed = false))
        assertEquals("https://m.example.test/", ShellNavigation.tapTarget("/tasks/42/attachments/7", policy, sandboxConfirmed = false))
    }

    /** As shipped (the gate open, muster #207): the upload routes load like any muster page. */
    @Test
    fun attachmentsLoadAsShipped() {
        listOf("/tasks/42/attachments/7", "/uploads/abc/file", "/f/abc").forEach { p ->
            val u = "https://m.example.test$p"
            assertEquals(u, Verdict.WEBVIEW, ShellNavigation.decide(u, policy))
            assertFalse(u, ShellNavigation.blocksSubresource(u, policy))
            assertEquals(u, ShellNavigation.tapTarget(p, policy))
        }
    }

    @Test
    fun tapTargetsStayOnTheOrigin() {
        assertEquals("https://m.example.test/tasks/42", ShellNavigation.tapTarget("/tasks/42", policy))
        assertEquals("https://m.example.test/", ShellNavigation.tapTarget(null, policy))
        assertEquals("https://m.example.test/", ShellNavigation.tapTarget("@evil.example.test/", policy))
        assertEquals("https://m.example.test/", ShellNavigation.tapTarget(".evil.example.test/", policy))
    }
}
