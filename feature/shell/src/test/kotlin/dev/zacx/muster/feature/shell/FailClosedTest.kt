package dev.zacx.muster.feature.shell

import dev.zacx.muster.core.model.AttachmentGate
import dev.zacx.muster.feature.shell.ShellNavigation.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 The gate fails CLOSED. java.net.URI refuses characters Chromium sends
 * unescaped (`|`, `^`, `` ` ``, `{`, `}`, a backslash, a tab), so a strict
 * parse that returned "not blocked" on failure let an upload URL through.
 */
class FailClosedTest {
    private val policy = OriginPolicy.forServer("https://m.example.test")!!
    private val o = "https://m.example.test"

    /** Upload URLs carrying characters Chromium leaves unescaped. */
    private val lenientUploads = listOf(
        "$o/tasks/1/attachments/2?x=|",
        "$o/tasks/1/attachments/2?x=^",
        "$o/tasks/1/attachments/2?x=`",
        "$o/tasks/1/attachments/2?x={y}",
        "$o/tasks/1/attachments/2|",
        "$o/tasks/1/attachments/^2",
        "$o/uploads/a|b/file",
        "$o/f/a^b",
        "$o/f/a b",
        "$o/f/\"a\"",
        "$o/f/<a>",
        // Chromium reads a backslash as a slash in an https path, and drops tabs and newlines.
        "$o\\tasks\\1\\attachments\\2",
        "$o/tasks/1/attach\tments/2",
        "$o/f/ab\n",
    )

    @Test
    fun uploadUrlsChromiumAcceptsAreBlockedEverywhere() {
        lenientUploads.forEach { u ->
            assertTrue("gate: $u", AttachmentGate.blocks(u))
            assertTrue("subresource: $u", ShellNavigation.blocksSubresource(u, policy))
            assertEquals("navigation: $u", Verdict.BLOCKED_ATTACHMENT, ShellNavigation.decide(u, policy))
        }
    }

    /** What no parser can read is blocked, never waved through. */
    @Test
    fun anUnparseableUrlIsBlocked() {
        listOf("$o/tasks/1/attachments/%zz", "$o/%zz", "https://m.example.test:99999999/x").forEach { u ->
            assertTrue("gate: $u", AttachmentGate.blocks(u))
            assertTrue("subresource: $u", ShellNavigation.blocksSubresource(u, policy))
            assertTrue("never the WebView or a browser: $u", ShellNavigation.decide(u, policy) in setOf(Verdict.REFUSED, Verdict.BLOCKED_ATTACHMENT))
        }
    }

    /** Ordinary muster URLs with those characters still load. */
    @Test
    fun ordinaryPagesWithOddCharactersStillLoad() {
        listOf("$o/attention?q=a|b", "$o/tasks/1?x=^", "$o/search?q={x}").forEach { u ->
            assertEquals(u, Verdict.WEBVIEW, ShellNavigation.decide(u, policy))
            assertTrue(u, !ShellNavigation.blocksSubresource(u, policy))
        }
    }
}
