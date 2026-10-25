package dev.zacx.muster.feature.shell

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Passkeys in the shell: without WebAuthn turned on, the WebView has no
 * `PublicKeyCredential`, and muster's sign-in and "Confirm it's you" offer no
 * passkey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class WebAuthnTest {
    private val wv = WebView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())

    /**
     * Turned on as THIS APP: androidx.webkit's WEB_AUTHENTICATION_SUPPORT_FOR_APP
     * is 1 (NONE is 0, FOR_BROWSER is 2). The literal is pinned so a switch to
     * the browser mode, or to none, fails here.
     */
    @Test
    fun aWebViewWithTheFeatureGetsWebAuthnForTheApp() {
        val calls = mutableListOf<Pair<WebSettings, Int>>()
        WebAuthn.enable(wv.settings, supported = true) { s, mode -> calls += s to mode }
        assertEquals(1, calls.size)
        assertSame(wv.settings, calls[0].first)
        assertEquals(1, calls[0].second)
    }

    /** A WebView without the feature is left alone: the setter is never called there. */
    @Test
    fun aWebViewWithoutTheFeatureIsLeftAlone() {
        var called = false
        WebAuthn.enable(wv.settings, supported = false) { _, _ -> called = true }
        assertFalse(called)
    }

    /** The shell's own setup turns it on, on the WebView it configures. */
    @Test
    fun theShellTurnsItOn() {
        val seen = mutableListOf<WebSettings>()
        ShellWebView.configure(
            wv, OriginPolicy.forServer("https://m.example.test")!!,
            object : ShellWebView.Callbacks {
                override fun onBridgeMessage(message: BridgeMessage) = Unit
                override fun openExternally(url: String) = Unit
                override fun onBlockedAttachment() = Unit
                override fun onRendererGone(view: WebView) = Unit
                override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<android.net.Uri>?) -> Unit) = Unit
                override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
                override fun onHistoryChanged() = Unit
            },
            enableWebAuthn = { seen += it },
        )
        assertEquals(1, seen.size)
        assertSame(wv.settings, seen[0])
    }
}
