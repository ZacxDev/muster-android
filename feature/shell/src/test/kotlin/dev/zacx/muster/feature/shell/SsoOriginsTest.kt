package dev.zacx.muster.feature.shell

import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import dev.zacx.muster.feature.shell.ShellNavigation.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A tenant behind a sign-in gate (civitai's muster: oauth2-proxy at the edge,
 * sign-in at the hub and an identity provider). Its advertised sign-in
 * origins LOAD in the WebView; they get nothing else. Every privilege stays
 * on the muster origin exactly.
 *
 * Fixture origins are pairwise distinct and none is a sub- or super-domain
 * string of another, so a check that matched by suffix or prefix would show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SsoOriginsTest {
    private val muster = "https://muster-la.civitai.test"
    private val hub = "https://auth.civitai.test"
    private val callback = "https://oauth-new.civitai.test"
    private val idp = "https://github.example"
    private val policy = OriginPolicy.forServer(muster, listOf(hub, callback, idp))!!
    private val plain = OriginPolicy.forServer(muster)!!
    private val code = "mdp_" + "A".repeat(43)

    // --- navigation -----------------------------------------------------------

    @Test
    fun signInOriginsLoadInTheWebView() {
        assertEquals(Verdict.WEBVIEW, ShellNavigation.decide("$hub/login?returnUrl=%2Fapi%2Fauth", policy))
        assertEquals(Verdict.WEBVIEW, ShellNavigation.decide("$callback/oauth2-civitai/callback?code=x&state=y", policy))
        assertEquals(Verdict.WEBVIEW, ShellNavigation.decide("$idp/login/oauth/authorize?client_id=z", policy))
        assertEquals(Verdict.WEBVIEW, ShellNavigation.decide("$muster/", policy))
    }

    /** Control: a tenant that advertises nothing (every muster today) behaves exactly as before; sign-in goes to a Custom Tab. */
    @Test
    fun withoutAdvertisedOriginsTheyStillGoToACustomTab() {
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("$hub/login", plain))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("$callback/oauth2-civitai/callback", plain))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("$idp/login", plain))
    }

    @Test
    fun anythingElseStillGoesToACustomTabOrNowhere() {
        // Other origins, including look-alikes of a sign-in origin.
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("https://accounts.google.example/o/oauth2/v2/auth", policy))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("https://auth.civitai.test.evil.example/login", policy))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("https://evil-auth.civitai.test/login", policy))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("https://auth.civitai.test:8443/login", policy))
        assertEquals(Verdict.CUSTOM_TAB, ShellNavigation.decide("https://auth.civitai.test@evil.example/", policy))
        // Not https at all: nowhere, sign-in host or not.
        assertEquals(Verdict.REFUSED, ShellNavigation.decide("http://auth.civitai.test/login", policy))
        assertEquals(Verdict.REFUSED, ShellNavigation.decide("javascript:alert(1)", policy))
        assertEquals(Verdict.REFUSED, ShellNavigation.decide("intent://auth.civitai.test/#Intent;end", policy))
    }

    @Test
    fun aSignInOriginIsNeverAMusterOrigin() {
        assertFalse(policy.allowsInWebView("$hub/login"))
        assertFalse(policy.allowsInWebView("$idp/"))
        assertTrue(policy.allowsSsoInWebView("$hub/login"))
        assertFalse("the muster origin is not a sign-in origin", policy.allowsSsoInWebView("$muster/"))
        assertEquals(muster, policy.allowedOriginRule)
    }

    /**
     * An exported launcher can be handed any path: a tap never opens a sign-in origin.
     * Invariant guard: no path appended to the muster origin parses as another host
     * today (user info is refused first), so this pins the invariant, not a bug.
     */
    @Test
    fun aTapTargetIsAlwaysOnTheMusterOrigin() {
        assertEquals("$muster/attention", ShellNavigation.tapTarget("/attention", policy))
        // Each of these, appended to the muster origin, could read as another host.
        listOf("@auth.civitai.test/", ".auth.civitai.test/", "\\@auth.civitai.test/").forEach { p ->
            val t = ShellNavigation.tapTarget(p, policy)
            assertTrue("$p -> $t", t == "$muster/" || policy.allowsInWebView(t))
            assertFalse("$p -> $t", policy.allowsSsoInWebView(t))
        }
    }

    // --- what the policy keeps --------------------------------------------------

    @Test
    fun onlyBareHttpsOriginsAreKept() {
        val p = OriginPolicy.forServer(
            muster,
            listOf(
                "https://AUTH.civitai.test", // case: normalised
                "https://auth.civitai.test/", // a trailing slash is still an origin; a duplicate after normalising
                "https://ok.example:8443",
                "http://plain.example", // not https
                "https://path.example/login", // a path: not an origin
                "https://q.example/?x=1", // a query
                "https://f.example/#x", // a fragment
                "https://user@ui.example", // user info
                "*", // a wildcard is not an origin
                "https://*.civitai.test", // nor is a wildcard host
                "", "  ", "not a url",
                muster, "$muster/", // the muster origin itself
            ),
        )!!
        assertEquals(listOf("https://auth.civitai.test", "https://ok.example:8443"), p.ssoOrigins)
    }

    @Test
    fun atMostEightAreKept() {
        val many = (1..12).map { "https://idp$it.example" }
        val p = OriginPolicy.forServer(muster, many)!!
        assertEquals(OriginPolicy.MAX_SSO_ORIGINS, p.ssoOrigins.size)
        assertEquals(many.take(8), p.ssoOrigins)
        assertFalse(p.allowsSsoInWebView("https://idp9.example/"))
    }

    @Test
    fun normalisingASignInOrigin() {
        assertEquals("https://auth.civitai.test", OriginPolicy.normaliseSsoOrigin(" https://Auth.Civitai.Test/ "))
        assertEquals("https://a.example:444", OriginPolicy.normaliseSsoOrigin("https://a.example:444"))
        assertEquals("https://a.example", OriginPolicy.normaliseSsoOrigin("https://a.example:443"))
        assertNull(OriginPolicy.normaliseSsoOrigin("https://a.example/x"))
        assertNull(OriginPolicy.normaliseSsoOrigin("http://a.example"))
    }

    // --- 🔴 no privilege reaches a sign-in origin ---------------------------------

    @Test
    fun theBridgeRefusesASignInOrigin() {
        val now = Instant.parse("2026-11-07T12:00:00Z")
        val pair = """{"type":"pair","code":"$code"}"""
        listOf(hub, callback, idp).forEach { src ->
            assertEquals(src, BridgeMessage.Refused("wrong origin"), BridgeMessage.parse(pair, src, true, policy, now))
        }
        assertTrue(BridgeMessage.parse(pair, muster, true, policy, now) is BridgeMessage.Pair)
    }

    @Test
    fun theMicrophoneIsRefusedToASignInOrigin() {
        val audio = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        listOf(hub, callback, idp).forEach { o -> assertFalse(o, ShellChromeClient.grantsMicrophone(policy, "$o/", audio)) }
        assertTrue(ShellChromeClient.grantsMicrophone(policy, "$muster/", audio))
    }

    @Test
    fun documentStartScriptsAreInstalledForTheMusterOriginOnly() {
        assertEquals(setOf(muster), PullProbeScript.originRules(policy))
        assertEquals(setOf(muster), SsoSessionGuard.originRules(policy))
    }

    /** Nothing a sign-in origin serves is ever subresource-blocked or attachment-gated as muster's. */
    @Test
    fun uploadRoutesAreJudgedOnTheMusterOriginOnly() {
        assertEquals(Verdict.BLOCKED_ATTACHMENT, ShellNavigation.decide("$muster/f/12", policy, sandboxConfirmed = false))
        assertEquals(Verdict.WEBVIEW, ShellNavigation.decide("$hub/f/12", policy, sandboxConfirmed = false))
        assertFalse(ShellNavigation.blocksSubresource("$hub/f/12", policy, sandboxConfirmed = false))
    }

    // --- the session guard's install -------------------------------------------------

    private fun webView() = WebView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())

    @Test
    fun theGuardIsInstalledForAGatedTenantOnItsMusterOriginOnly() {
        val calls = mutableListOf<Pair<String, Set<String>>>()
        assertTrue(SsoSessionGuard.install(webView(), policy, supported = true) { _, src, rules -> calls += src to rules })
        assertEquals(listOf(SsoSessionGuard.SOURCE to setOf(muster)), calls)
    }

    /** A tenant with no sign-in origins (every muster today) gets no new script: nothing changes for it. */
    @Test
    fun aTenantWithoutSignInOriginsGetsNoGuard() {
        var called = false
        assertFalse(SsoSessionGuard.install(webView(), plain, supported = true) { _, _, _ -> called = true })
        assertFalse(SsoSessionGuard.install(webView(), policy, supported = false) { _, _, _ -> called = true })
        assertFalse(called)
    }

    /** The probe path is muster's session-free manifest route, and the script is ES5 with nothing templated left. */
    @Test
    fun theGuardProbesThePathTheInfraKeepsGated() {
        assertEquals("/manifest.webmanifest", SsoSessionGuard.PROBE_PATH)
        assertTrue(SsoSessionGuard.SOURCE.contains("window.fetch('/manifest.webmanifest'"))
        assertTrue(SsoSessionGuard.SOURCE.contains("if (window.top !== window"))
        assertFalse(SsoSessionGuard.SOURCE.contains("$"))
        assertFalse("it never talks to the bridge", SsoSessionGuard.SOURCE.contains(ShellWebView.BRIDGE_NAME))
    }
}
