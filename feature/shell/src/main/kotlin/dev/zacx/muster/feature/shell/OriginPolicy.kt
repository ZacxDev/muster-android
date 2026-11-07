package dev.zacx.muster.feature.shell

import dev.zacx.muster.core.model.LenientUrl

/**
 * The shell's one-origin rule: the WebView navigates only within the muster
 * origin the user entered; everything else opens in a Custom Tab.
 *
 * An origin is scheme + host + effective port. Only `https` is accepted.
 *
 * **Sign-in origins.** A muster behind a sign-in gate at its edge (an SSO
 * proxy that redirects to an identity provider before muster's own page) can
 * advertise the origins that sign-in passes through
 * (`GET /api/device/web`, `sso_origins`). Those may LOAD in the WebView
 * ([allowsSsoInWebView]), so the gate's cookie lands in this tenant's WebView
 * profile instead of the browser. They get nothing else: [allowsInWebView] and
 * [allowedOriginRule] stay the muster origin exactly, and every privilege
 * keys on those two (the `musterApp` bridge, the document-start scripts, the
 * microphone, native dialogs, upload routes). A sign-in origin is never the
 * muster origin itself (dropped), and at most [MAX_SSO_ORIGINS] are kept.
 */
class OriginPolicy private constructor(private val origin: Origin, private val sso: Set<Origin>) {

    /** True only for the muster origin. Everything privileged keys on this, never on [allowsSsoInWebView]. */
    fun allowsInWebView(url: String): Boolean = parse(url) == origin

    /** True for one of the tenant's advertised sign-in origins: it may load in the WebView, and nothing more. */
    fun allowsSsoInWebView(url: String): Boolean = parse(url)?.let { it in sso } ?: false

    /** True when no parser can read [url]; every shell check blocks it. */
    fun unparseable(url: String): Boolean = LenientUrl.parse(url) == null

    /** The exact origin string for `allowedOriginRules` (never `*`, never a sign-in origin). */
    val allowedOriginRule: String get() = origin.toString()

    /** The sign-in origins this policy loads in the WebView, normalised, in the order given. */
    val ssoOrigins: List<String> get() = sso.map { it.toString() }

    private data class Origin(val host: String, val port: Int) {
        override fun toString() = if (port == 443) "https://$host" else "https://$host:$port"
    }

    companion object {
        /** More than this many advertised sign-in origins: the rest are ignored. */
        const val MAX_SSO_ORIGINS = 8

        /**
         * Returns null unless [serverUrl] is an absolute `https` URL with a
         * host. [ssoOrigins] are the tenant's advertised sign-in origins; any
         * that is not a bare `https` origin ([normaliseSsoOrigin]) or is the
         * muster origin is dropped.
         */
        fun forServer(serverUrl: String, ssoOrigins: List<String> = emptyList()): OriginPolicy? {
            val o = parse(serverUrl) ?: return null
            val sso = ssoOrigins.asSequence()
                .mapNotNull { s -> normaliseSsoOrigin(s)?.let(::parse) }
                .filter { it != o }
                .distinct()
                .take(MAX_SSO_ORIGINS)
                .toCollection(LinkedHashSet())
            return OriginPolicy(o, sso)
        }

        /**
         * A sign-in origin as advertised, normalised to `https://host[:port]`,
         * or null. It must be an ORIGIN: `https`, a host, no user info, and no
         * path (other than `/`), query or fragment, so a value is never read
         * as "this one page".
         */
        fun normaliseSsoOrigin(s: String): String? {
            val u = LenientUrl.parse(s.trim()) ?: return null
            val path = u.rawPath.orEmpty()
            if (path.isNotEmpty() && path != "/") return null
            if (u.rawQuery != null || u.rawFragment != null) return null
            return parse(s.trim())?.toString()
        }

        private fun parse(url: String): Origin? {
            val u = LenientUrl.parse(url) ?: return null
            if (!u.scheme.equals("https", ignoreCase = true)) return null
            if (u.rawUserInfo != null) return null
            val host = u.host?.lowercase()?.trimEnd('.') ?: return null
            if (host.isEmpty()) return null
            val port = if (u.port == -1) 443 else u.port
            return Origin(host, port)
        }
    }
}
