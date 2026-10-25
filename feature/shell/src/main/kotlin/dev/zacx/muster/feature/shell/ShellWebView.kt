package dev.zacx.muster.feature.shell

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * The hardened WebView (docs/design/android.md, "The shell"):
 *
 * - one origin; other https links go to a Custom Tab ([ShellNavigation]);
 * - no file or content access, no `addJavascriptInterface`;
 * - the bridge is `WebViewCompat.addWebMessageListener` named `musterApp`,
 *   restricted to the exact origin (never `*`), main frame only;
 * - uploaded files are refused at the top level and as subresources until
 *   muster's sandbox is confirmed (AttachmentGate);
 * - a download is handed to the browser, URL only ([DownloadHandoff]).
 */
object ShellWebView {
    /**
     * The message's text, or null for any message that is not a string (an
     * ArrayBuffer, a future type). getData() throws on a non-string message,
     * so the type is checked first.
     */
    fun bridgeData(message: WebMessageCompat): String? =
        if (message.type == WebMessageCompat.TYPE_STRING) message.data else null

    /** muster's ui.AppBridgeName. */
    const val BRIDGE_NAME = "musterApp"

    interface Callbacks {
        fun onBridgeMessage(message: BridgeMessage)

        /** Opens [url] in the user's browser (a Custom Tab). Only the URL crosses. */
        fun openExternally(url: String)

        fun onBlockedAttachment()

        /**
         * The page asked for files (`<input type=file>`): show the system
         * picker for [mimeTypes] (empty: any) and call [deliver] once, with
         * the picked URIs or null if cancelled.
         */
        fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<android.net.Uri>?) -> Unit)

        /** A JS alert ([confirm] false) or confirm from muster: show it natively, then call [done] once. */
        fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit)

        /** The WebView's history changed: re-sync back handling ([ShellBack.sync]). */
        fun onHistoryChanged()

        /** The renderer crashed or was killed: drop this WebView and make a new one. */
        fun onRendererGone(view: WebView)

        /**
         * A main-frame load milestone ([PageEvent]). Timing only: the app's
         * debug startup probe and `reportFullyDrawn` listen; nothing here may
         * change what loads.
         */
        fun onPageEvent(view: WebView, event: PageEvent, url: String) {}
    }

    /** Main-frame load milestones, in the order the WebView reports them. */
    enum class PageEvent { STARTED, COMMIT_VISIBLE, FINISHED }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, policy: OriginPolicy, callbacks: Callbacks) {
        webView.settings.apply {
            javaScriptEnabled = true // muster's UI is htmx
            domStorageEnabled = true // its service worker and offline shell
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        webView.webViewClient = Client(policy, callbacks)
        webView.webChromeClient = ShellChromeClient(policy, callbacks)
        webView.setDownloadListener(DownloadHandoff(callbacks::openExternally))

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                setOf(policy.allowedOriginRule),
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                // muster's messages are JSON strings; anything else is ignored.
                val data = bridgeData(message) ?: return@addWebMessageListener
                callbacks.onBridgeMessage(
                    BridgeMessage.parse(data, sourceOrigin.toString(), isMainFrame, policy, Instant.now()),
                )
            }
        }
        // Without WEB_MESSAGE_LISTENER (a very old WebView) there is no bridge:
        // the page then shows no "Pair this phone" button, and nothing pairs.
    }

    private class Client(private val policy: OriginPolicy, private val cb: Callbacks) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (ShellNavigation.decide(url, policy)) {
                ShellNavigation.Verdict.WEBVIEW -> false
                ShellNavigation.Verdict.CUSTOM_TAB -> { cb.openExternally(url); true }
                ShellNavigation.Verdict.BLOCKED_ATTACHMENT -> { cb.onBlockedAttachment(); true }
                ShellNavigation.Verdict.REFUSED -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // loadUrl and history navigation do not pass shouldOverrideUrlLoading.
            if (ShellNavigation.decide(url, policy) == ShellNavigation.Verdict.BLOCKED_ATTACHMENT) {
                view.stopLoading()
                cb.onBlockedAttachment()
                return
            }
            cb.onPageEvent(view, PageEvent.STARTED, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            cb.onPageEvent(view, PageEvent.COMMIT_VISIBLE, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            cb.onPageEvent(view, PageEvent.FINISHED, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            cb.onHistoryChanged()
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // Returning true keeps the app alive; the dead WebView must not be used again.
            cb.onRendererGone(view)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!ShellNavigation.blocksSubresource(request.url.toString(), policy)) return null
            return WebResourceResponse(
                "text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    /**
     * The native shell pads for insets, not the page (Phase 0, S12: CSS
     * `env(safe-area-inset-*)` reads 0 in a WebView). The container takes the
     * system-bar, display-cutout and IME insets as padding and CONSUMES them,
     * so a WebView that does report `env()` sees 0 and nothing pads twice.
     */
    fun padForInsets(container: View) {
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.updatePadding(left = i.left, top = i.top, right = i.right, bottom = i.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
