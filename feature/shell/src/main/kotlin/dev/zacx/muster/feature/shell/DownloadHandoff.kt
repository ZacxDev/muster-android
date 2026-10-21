package dev.zacx.muster.feature.shell

import android.webkit.DownloadListener

/**
 * What the shell does with a download (a response muster serves as
 * `Content-Disposition: attachment`, as muster #207 does for every uploaded
 * file that is not an image). Without a DownloadListener a WebView silently
 * does nothing on such a tap.
 *
 * The file is HANDED OFF, never rendered: the URL goes to the user's browser
 * (a Custom Tab), which downloads or shows it under its own session. The
 * handoff carries the URL and nothing else: no cookie, no header, no device
 * credential ("Cookies stay in the WebView"; docs/design/android.md, "The
 * shell"). So the browser may ask the user to sign in to muster there; that
 * is the price of not lifting the WebView's session out of it. DownloadManager
 * is not used, because it would need the session cookie read through
 * CookieManager.
 *
 * Only `https` URLs are handed off. `blob:` and `data:` downloads (made by a
 * page's script) and anything else are dropped.
 *
 * While AttachmentGate is closed the WebView never fetches an upload route,
 * so no upload ever reaches this listener; this is what makes a tap on a
 * non-image file do something once the gate opens.
 */
class DownloadHandoff(private val handOff: (url: String) -> Unit) : DownloadListener {

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        target(url)?.let(handOff)
    }

    companion object {
        /** The URL to hand to the browser, or null to drop the download. */
        fun target(url: String?): String? {
            if (url.isNullOrEmpty() || url.length > 8192) return null
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
            return url
        }
    }
}
