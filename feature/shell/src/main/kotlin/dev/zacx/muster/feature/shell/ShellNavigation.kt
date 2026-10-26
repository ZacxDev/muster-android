package dev.zacx.muster.feature.shell

import dev.zacx.muster.core.model.AttachmentGate

/**
 * Where a URL the WebView is about to load goes. Pure, so it is unit-tested;
 * the WebViewClient only acts on the verdict.
 *
 * - The muster origin loads in the WebView.
 * - An upload route on the muster origin (AttachmentGate) loads in the
 *   WebView like any muster page while the gate is open (as shipped); a
 *   non-image file then arrives as a download, handed to a Custom Tab
 *   (DownloadHandoff). With the gate closed it is refused.
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
     * load. A URL no parser can read is refused whatever its origin (fail
     * closed). Otherwise, with the gate open (as shipped), nothing is; with it
     * closed, only upload routes on the muster origin are. Other origins'
     * subresources are the page's own business, under muster's CSP.
     */
    fun blocksSubresource(url: String, policy: OriginPolicy, sandboxConfirmed: Boolean = AttachmentGate.SANDBOX_CONFIRMED): Boolean =
        policy.unparseable(url) || (policy.allowsInWebView(url) && AttachmentGate.blocks(url, sandboxConfirmed))

    /**
     * The URL a notification tap or a launch path opens: [path] on the muster
     * origin, or the home page. 🔴 An upload route is never a tap target, gate
     * open or closed: the launcher activity is exported, so any app can hand
     * it a path, and on a cold start a non-image upload (a download, handed to
     * a Custom Tab) would leave an empty shell. muster never sends an upload
     * path in a push.
     */
    fun tapTarget(path: String?, policy: OriginPolicy, sandboxConfirmed: Boolean = AttachmentGate.SANDBOX_CONFIRMED): String {
        val home = policy.allowedOriginRule + "/"
        if (path == null) return home
        val url = policy.allowedOriginRule + path
        if (AttachmentGate.isUploadRoute(url)) return home
        return if (decide(url, policy, sandboxConfirmed) == Verdict.WEBVIEW) url else home
    }
}
