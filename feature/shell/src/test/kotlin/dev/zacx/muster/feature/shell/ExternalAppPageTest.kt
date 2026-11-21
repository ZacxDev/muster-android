package dev.zacx.muster.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 The `open-external` URL gate ([ExternalAppPage.accept]): muster's own
 * origin, exactly, and the path `/apps/<digits>`, exactly; nothing else
 * opens. Expected URLs are literals, never derived from the policy.
 */
class ExternalAppPageTest {
    private val policy = OriginPolicy.forServer("https://m.example.test")!!
    private val ported = OriginPolicy.forServer("https://m.example.test:8443")!!

    private fun accept(url: String, p: OriginPolicy = policy) = ExternalAppPage.accept(url, p)

    private fun refused(p: OriginPolicy, vararg urls: String) = urls.forEach { assertNull(it, accept(it, p)) }
    private fun refused(vararg urls: String) = refused(policy, *urls)

    @Test
    fun theExactOriginAndAnAppPathAreAccepted() {
        assertEquals("https://m.example.test/apps/7", accept("https://m.example.test/apps/7"))
        assertEquals("https://m.example.test/apps/40213", accept("https://m.example.test/apps/40213"))
        assertEquals("https://m.example.test/apps/999999999999999999", accept("https://m.example.test/apps/999999999999999999"))
    }

    /** Case and an explicit default port are the same origin; the URL opened is rebuilt, canonical. */
    @Test
    fun caseAndTheDefaultPortAreCanonicalised() {
        assertEquals("https://m.example.test/apps/52", accept("https://M.Example.TEST/apps/52"))
        assertEquals("https://m.example.test/apps/52", accept("HTTPS://m.example.test/apps/52"))
        assertEquals("https://m.example.test/apps/52", accept("https://m.example.test:443/apps/52"))
    }

    @Test
    fun anotherOriginIsRefused() = refused(
        "https://other.example.test/apps/7",
        "https://example.test/apps/7",
        "https://n.example.test/apps/7",
    )

    @Test
    fun aLookAlikeHostIsRefused() = refused(
        "https://m.example.test.evil.test/apps/7",
        "https://evilm.example.test/apps/7",
        "https://xm.example.test/apps/7",
        "https://m-example.test/apps/7",
        "https://m.example.tes/apps/7",
        "https://m.example.testx/apps/7",
        "https://m.example.test./apps/7",
        "https://m.examp1e.test/apps/7",
    )

    @Test
    fun anythingButHttpsIsRefused() = refused(
        "http://m.example.test/apps/7",
        "wss://m.example.test/apps/7",
        "ftp://m.example.test/apps/7",
        "intent://m.example.test/apps/7#Intent;scheme=https;end",
        "javascript:location='https://m.example.test/apps/7'",
        "//m.example.test/apps/7",
        "/apps/7",
        "apps/7",
    )

    @Test
    fun aPortOtherThanTheOriginsIsRefused() {
        refused("https://m.example.test:8443/apps/7", "https://m.example.test:444/apps/7", "https://m.example.test:0/apps/7", "https://m.example.test:99999/apps/7")
        // The ported origin: its own port only, never the default.
        assertEquals("https://m.example.test:8443/apps/31", accept("https://m.example.test:8443/apps/31", ported))
        refused(ported, "https://m.example.test/apps/31", "https://m.example.test:443/apps/31", "https://m.example.test:8444/apps/31")
    }

    @Test
    fun userInfoIsRefused() = refused(
        "https://user@m.example.test/apps/7",
        "https://u:p@m.example.test/apps/7",
        "https://@m.example.test/apps/7",
        "https://evil.test@m.example.test/apps/7",
        "https://m.example.test@evil.test/apps/7",
        "https://m.example.test:443@evil.test/apps/7",
    )

    @Test
    fun pathTraversalAndExtraSegmentsAreRefused() = refused(
        "https://m.example.test/apps/1/../x",
        "https://m.example.test/apps/7/..",
        "https://m.example.test/apps/../apps/7",
        "https://m.example.test/apps/./7",
        "https://m.example.test/./apps/7",
        "https://m.example.test/x/../apps/7",
        "https://m.example.test/apps/7/",
        "https://m.example.test/apps/7/x",
        "https://m.example.test//apps/7",
        "https://m.example.test/apps//7",
        "https://m.example.test/apps/7;x",
    )

    /** No query or fragment at all, not even an empty one (the design's optional `?ref=` is not accepted: PR body). */
    @Test
    fun aQueryOrFragmentIsRefused() = refused(
        "https://m.example.test/apps/7?ref=abc",
        "https://m.example.test/apps/7?",
        "https://m.example.test/apps/7#",
        "https://m.example.test/apps/7#x",
        "https://m.example.test/apps/7?a#b",
        "https://m.example.test?/apps/7",
        "https://m.example.test#/apps/7",
    )

    @Test
    fun aNonDigitIdIsRefused() = refused(
        "https://m.example.test/apps/abc",
        "https://m.example.test/apps/7a",
        "https://m.example.test/apps/",
        "https://m.example.test/apps",
        "https://m.example.test/apps/-7",
        "https://m.example.test/apps/+7",
        "https://m.example.test/apps/1e3",
        "https://m.example.test/apps/0",
        "https://m.example.test/apps/007",
        "https://m.example.test/apps/1234567890123456789",
        "https://m.example.test/apps/٧",
        "https://m.example.test/apps/１",
        "https://m.example.test/APPS/7",
        "https://m.example.test/Apps/7",
        "https://m.example.test/app/7",
    )

    /** An encoded slash, dot or digit is refused, not decoded: the raw path is what is checked. */
    @Test
    fun percentEncodingIsRefused() = refused(
        "https://m.example.test/apps%2F7",
        "https://m.example.test/apps%2f7",
        "https://m.example.test/apps/7%2F..%2Fx",
        "https://m.example.test/apps/7%2f",
        "https://m.example.test/apps/%37",
        "https://m.example.test/%61pps/7",
        "https://m.example.test/apps/7%00",
        "https://m%2Eexample.test/apps/7",
        "https://m.example.test/apps/1%2F..%2F..%2Fx",
    )

    /** What Chromium would repair (backslashes, whitespace, controls) is refused, not repaired. */
    @Test
    fun whatOnlyALenientParserReadsIsRefused() = refused(
        "https://m.example.test\\apps\\7",
        "https://evil.test\\@m.example.test/apps/7",
        "https://m.example.test/apps\\7",
        " https://m.example.test/apps/7",
        "https://m.example.test/apps/7 ",
        "https://m.example.test/apps/7\n",
        "https://m.example.test/ap\tps/7",
        "https://m.exämple.test/apps/7",
        "",
        "https://m.example.test/apps/" + "1".repeat(600),
    )
}
