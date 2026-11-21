package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Tells muster's page which bridge messages this app understands. A
 * document-start script defines, in the main frame of the paired origin only:
 *
 *     window.musterAppMessages = ["pair", "open-notification-settings", "back-claim", "touch-start", "open-external"]
 *
 * a frozen, read-only array of [BridgeMessage.TYPES]. muster's page detects a
 * message with
 *
 *     window.musterApp && Array.isArray(window.musterAppMessages) &&
 *       window.musterAppMessages.indexOf('open-external') !== -1
 *
 * and never with an app version string. An app without this script (every
 * release before it, or a WebView without document-start scripts) has no
 * `musterAppMessages`, which reads as "no `open-external`": muster then shows
 * its copy-link card. That is the safe direction: a missing list can only hide
 * a button, never post a message the app would drop.
 *
 * It is a separate global, not a property on `window.musterApp`: that object
 * is the WebView's own (addWebMessageListener), and whether a page-world
 * property set on it survives, or is set before it exists, is not something
 * the app controls.
 *
 * Installed only alongside the bridge ([ShellWebView.configure]), with
 * `allowedOriginRules` exactly the muster origin (never `*`, never a sign-in
 * origin), so no other document is told anything.
 */
object AppMessagesScript {
    const val GLOBAL = "musterAppMessages"

    /** The rules the script is installed with: the paired origin, exactly. */
    fun originRules(policy: OriginPolicy): Set<String> = setOf(policy.allowedOriginRule)

    /** Installs the script; false when this WebView cannot run document-start scripts (then the page sees no list). */
    fun install(
        webView: WebView,
        policy: OriginPolicy,
        supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        add: (WebView, String, Set<String>) -> Unit = ::addScript,
    ): Boolean {
        if (!supported) return false
        add(webView, SOURCE, originRules(policy))
        return true
    }

    private fun addScript(webView: WebView, source: String, rules: Set<String>) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, source, rules)
        }
    }

    /** ES5 and self-contained. The types are plain lowercase words and hyphens, so they are written as-is. */
    val SOURCE: String = run {
        require(BridgeMessage.TYPES.all { Regex("[a-z-]+").matches(it) })
        val list = BridgeMessage.TYPES.joinToString(",") { "'$it'" }
        """
(function () {
  if (window.top !== window) return;
  try {
    Object.defineProperty(window, '$GLOBAL', {
      value: Object.freeze([$list]),
      writable: false, configurable: false, enumerable: false
    });
  } catch (e) {}
})();
""".trimIndent()
    }
}
