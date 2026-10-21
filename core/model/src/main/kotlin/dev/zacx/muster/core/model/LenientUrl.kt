package dev.zacx.muster.core.model

import java.net.URI

/**
 * Parses a URL the way the WebView will have read it, closely enough for the
 * shell's checks. java.net.URI is stricter than Chromium: it refuses `|`, `^`,
 * `` ` ``, `{`, `}`, spaces, quotes and backslashes, which Chromium sends. A
 * check that treated "URI refused it" as "not ours" or "not blocked" would
 * fail OPEN, so:
 *
 * 1. the strict parse is tried first;
 * 2. else the URL is normalised as Chromium does for `https`: tabs and
 *    newlines removed, leading and trailing spaces and controls trimmed, a
 *    backslash before the query read as a slash, and every other character
 *    URI refuses percent-encoded;
 * 3. if that still does not parse, the result is null, and every caller
 *    treats null as BLOCKED.
 */
object LenientUrl {
    fun parse(s: String): URI? {
        strict(s)?.let { return it }
        val cleaned = s.filterNot { it == '\t' || it == '\n' || it == '\r' }.trim { it <= ' ' }
        val cut = cleaned.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) cleaned.length else it }
        val slashed = cleaned.substring(0, cut).replace('\\', '/') + cleaned.substring(cut)
        val encoded = buildString {
            for (ch in slashed) {
                if (ch.code < 0x80 && (ch.isLetterOrDigit() || ch in LEGAL)) append(ch)
                else ch.toString().encodeToByteArray().forEach { append('%').append("%02X".format(it.toInt() and 0xff)) }
            }
        }
        return strict(encoded)
    }

    /**
     * URI, plus one more refusal: an http(s) URL whose authority URI could not
     * read as a host parses with a null host, and URI takes a port above
     * 65535, which Chromium refuses; both are unreadable, not "another origin".
     */
    private fun strict(s: String): URI? {
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        val web = u.scheme.equals("https", ignoreCase = true) || u.scheme.equals("http", ignoreCase = true)
        return if (web && (u.host.isNullOrEmpty() || u.port > 65535)) null else u
    }

    // RFC 3986 unreserved + reserved, and '%' (left as written: a malformed
    // escape stays malformed, and so unparseable, and so blocked).
    private const val LEGAL = "-._~:/?#[]@!$&'()*+,;=%"
}
