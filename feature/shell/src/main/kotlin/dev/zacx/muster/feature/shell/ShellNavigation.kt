package dev.zacx.muster.feature.shell

import dev.zacx.muster.core.model.AttachmentGate

/**
 * Where a URL the WebView is about to load goes. Pure, so it is unit-tested;
 * the WebViewClient only acts on the verdict.
 *
 * - The muster origin loads in the WebView.
 * - A note attachment on the muster origin is refused (AttachmentGate): not
 *   in the WebView, where the bridge answers, and not handed to a browser by
 *   the app either.
 * - `https` elsewhere opens in a Custom Tab.
 * - Anything else (`javascript:`, `file:`, `intent:`, `http:`) goes nowhere.
 */
object ShellNavigation {
    enum class Verdict { WEBVIEW, CUSTOM_TAB, BLOCKED_ATTACHMENT, REFUSED }

    fun decide(url: String, policy: OriginPolicy, sandboxConfirmed: Boolean = AttachmentGate.SANDBOX_CONFIRMED): Verdict = when {
        // 🔴 Fail closed: what no parser can read goes nowhere.
        policy.unparseable(url) -> Verdict.REFUSED
        policy.allowsInWebView(url) ->
            if (AttachmentGate.blocks(url, sandboxConfirmed)) Verdict.BLOCKED_ATTACHMENT else Verdict.WEBVIEW
        url.startsWith("https://", ignoreCase = true) -> Verdict.CUSTOM_TAB
        else -> Verdict.REFUSED
    }

    /**
     * A subresource request (an image, a frame, a fetch) the WebView must not
     * load. Only attachments on the muster origin are refused; other origins'
     * subresources are the page's own business, under muster's CSP. A URL no
     * parser can read is refused whatever its origin (fail closed).
     */
    fun blocksSubresource(url: String, policy: OriginPolicy, sandboxConfirmed: Boolean = AttachmentGate.SANDBOX_CONFIRMED): Boolean =
        policy.unparseable(url) || (policy.allowsInWebView(url) && AttachmentGate.blocks(url, sandboxConfirmed))

    /** The URL a notification tap opens, or null to open the server's home page. */
    fun tapTarget(path: String?, policy: OriginPolicy, sandboxConfirmed: Boolean = AttachmentGate.SANDBOX_CONFIRMED): String {
        val home = policy.allowedOriginRule + "/"
        if (path == null) return home
        val url = policy.allowedOriginRule + path
        return if (decide(url, policy, sandboxConfirmed) == Verdict.WEBVIEW) url else home
    }
}
