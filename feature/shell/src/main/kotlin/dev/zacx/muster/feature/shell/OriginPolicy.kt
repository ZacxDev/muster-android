package dev.zacx.muster.feature.shell

import java.net.URI

/**
 * The shell's one-origin rule: the WebView navigates only within the muster
 * origin the user entered; everything else opens in a Custom Tab.
 *
 * An origin is scheme + host + effective port. Only `https` is accepted.
 */
class OriginPolicy private constructor(private val origin: Origin) {

    fun allowsInWebView(url: String): Boolean = parse(url) == origin

    /** The exact origin string for `allowedOriginRules` (never `*`). */
    val allowedOriginRule: String get() = origin.toString()

    private data class Origin(val host: String, val port: Int) {
        override fun toString() = if (port == 443) "https://$host" else "https://$host:$port"
    }

    companion object {
        /** Returns null unless [serverUrl] is an absolute `https` URL with a host. */
        fun forServer(serverUrl: String): OriginPolicy? = parse(serverUrl)?.let(::OriginPolicy)

        private fun parse(url: String): Origin? {
            val u = runCatching { URI(url) }.getOrNull() ?: return null
            if (!u.scheme.equals("https", ignoreCase = true)) return null
            if (u.rawUserInfo != null) return null
            val host = u.host?.lowercase()?.trimEnd('.') ?: return null
            if (host.isEmpty()) return null
            val port = if (u.port == -1) 443 else u.port
            return Origin(host, port)
        }
    }
}
