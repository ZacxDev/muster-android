package dev.zacx.muster.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginPolicyTest {
    private val policy = OriginPolicy.forServer("https://muster.example.test")!!

    @Test
    fun sameOriginIsAllowed() {
        assertTrue(policy.allowsInWebView("https://muster.example.test/attention"))
        assertTrue(policy.allowsInWebView("https://MUSTER.example.test:443/x?y=1"))
    }

    @Test
    fun otherOriginsAreRefused() {
        assertFalse(policy.allowsInWebView("http://muster.example.test/"))
        assertFalse(policy.allowsInWebView("https://muster.example.test:8443/"))
        assertFalse(policy.allowsInWebView("https://evil.example.test/"))
        assertFalse(policy.allowsInWebView("https://muster.example.test.evil.test/"))
        assertFalse(policy.allowsInWebView("https://muster.example.test@evil.test/"))
        assertFalse(policy.allowsInWebView("javascript:alert(1)"))
        assertFalse(policy.allowsInWebView("file:///sdcard/x.html"))
    }

    @Test
    fun serverUrlMustBeHttps() {
        assertNull(OriginPolicy.forServer("http://muster.example.test"))
        assertNull(OriginPolicy.forServer("muster.example.test"))
        assertNull(OriginPolicy.forServer("https://user:pw@muster.example.test"))
    }

    @Test
    fun allowedOriginRuleIsExact() {
        assertEquals("https://muster.example.test", policy.allowedOriginRule)
        assertEquals(
            "https://m.example.test:8443",
            OriginPolicy.forServer("https://m.example.test:8443/")!!.allowedOriginRule,
        )
    }
}
