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

    /** The one query allowed: `?ref=<ref>`, muster's ref rule, and the URL opened carries it. */
    @Test
    fun aRefQueryIsAccepted() {
        assertEquals("https://m.example.test/apps/7?ref=abc", accept("https://m.example.test/apps/7?ref=abc"))
        assertEquals("https://m.example.test/apps/40213?ref=x", accept("https://m.example.test/apps/40213?ref=x"))
        assertEquals("https://m.example.test/apps/52?ref=Zq_9-k", accept("https://M.Example.TEST:443/apps/52?ref=Zq_9-k"))
        val r64 = "Az09_-".repeat(10) + "Bq7_"
        assertEquals(64, r64.length)
        assertEquals("https://m.example.test/apps/7?ref=$r64", accept("https://m.example.test/apps/7?ref=$r64"))
        assertEquals("https://m.example.test:8443/apps/31?ref=k3", accept("https://m.example.test:8443/apps/31?ref=k3", ported))
    }

    /** Anything but exactly `ref=<1..64 of [A-Za-z0-9_-]>`, checked raw, is refused. */
    @Test
    fun aQueryOtherThanOneRefIsRefused() {
        val r65 = "Az09_-".repeat(10) + "Bq7_x"
        assertEquals(65, r65.length)
        refused(
            "https://m.example.test/apps/7?ref=$r65",
            "https://m.example.test/apps/7?ref=",
            "https://m.example.test/apps/7?ref",
            "https://m.example.test/apps/7?=abc",
            "https://m.example.test/apps/7?ref=%61",
            "https://m.example.test/apps/7?ref=a%2Fb",
            "https://m.example.test/apps/7?ref=a%26x=1",
            "https://m.example.test/apps/7?ref=a&x=1",
            "https://m.example.test/apps/7?ref=a&",
            "https://m.example.test/apps/7?&ref=a",
            "https://m.example.test/apps/7?x=1&ref=a",
            "https://m.example.test/apps/7?ref=a&ref=b",
            "https://m.example.test/apps/7?ref=a;x=1",
            "https://m.example.test/apps/7?REF=a",
            "https://m.example.test/apps/7?Ref=a",
            "https://m.example.test/apps/7?refx=a",
            "https://m.example.test/apps/7?xref=a",
            "https://m.example.test/apps/7?x=1",
            "https://m.example.test/apps/7?ref==a",
            "https://m.example.test/apps/7?ref=a=b",
            "https://m.example.test/apps/7?ref=a.b",
            "https://m.example.test/apps/7?ref=a+b",
            "https://m.example.test/apps/7?ref=a/b",
            "https://m.example.test/apps/7?ref=a?b",
            "https://m.example.test/apps/7?ref=\u00e4",
            "https://m.example.test/apps/7??ref=a",
        )
    }

    /** No fragment, with or without a ref, and no empty query. */
    @Test
    fun aFragmentOrAnEmptyQueryIsRefused() = refused(
        "https://m.example.test/apps/7?ref=a#b",
        "https://m.example.test/apps/7?ref=a#",
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
