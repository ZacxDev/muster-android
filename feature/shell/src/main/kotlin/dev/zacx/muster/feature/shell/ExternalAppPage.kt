package dev.zacx.muster.feature.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URI

/**
 * The `open-external` bridge message (muster docs/design/meetings.md, "The
 * Android path for a server app"): muster's page for a server-installed app,
 * `/apps/<id>`, cannot run in the app's WebView, so the page asks the app to
 * open that page in the system browser instead:
 *
 *     {"type":"open-external","url":"https://<muster host>/apps/<id>"}
 *
 * The app accepts exactly one shape of URL ([accept]) and opens it with an
 * `ACTION_VIEW` intent ([open]), never in the WebView. Everything else is
 * refused ([BridgeMessage.Refused]) and nothing opens.
 */
object ExternalAppPage {
    const val MESSAGE_TYPE = "open-external"

    /** A plugin id: digits, no leading zero, at most 18 of them (a positive 64-bit id). */
    private val PATH = Regex("/apps/[1-9][0-9]{0,17}")

    /**
     * The one query allowed: `ref=<ref>`, muster's ref rule
     * (`^[A-Za-z0-9_-]{1,64}$`, the plugin's own id for what a knock is about;
     * muster docs/design/meetings.md, "Core seam: knock notification"),
     * matched against the RAW query, so an encoded ref is refused.
     */
    private val QUERY = Regex("ref=[A-Za-z0-9_-]{1,64}")

    /**
     * The URL to open, or null. Accepted only when [url] is, as written:
     *
     * - an absolute `https` URL that java.net.URI reads strictly (no lenient
     *   repair, as LenientUrl does: a backslash, space or control character
     *   is unparseable, so refused, never guessed at);
     * - on EXACTLY [policy]'s muster origin: `https://<host>[:<port>]` built
     *   from the URL (host lower-cased, a default port dropped) must equal the
     *   policy's origin string, so a trailing-dot host, a look-alike or another
     *   port is refused; no user info;
     * - with a RAW path (never decoded) of exactly `/apps/<digits>`: no
     *   trailing slash, no dot segment, and an encoded slash, dot or digit
     *   (`%2F`, `%2E`, `%37`) is refused rather than decoded, so the path
     *   checked is the path sent;
     * - with no query, or a RAW query of exactly `ref=<ref>` ([QUERY]): an
     *   empty `?` or `ref=`, an encoded ref, `&`, a second or repeated
     *   parameter and any other key (`REF` included) are refused;
     * - with no fragment, not even an empty `#`.
     *
     * The result is rebuilt from the policy's origin, the checked path and the
     * checked query, never the input string, so a variant that passed (an upper-case host, an
     * explicit default port) opens the canonical URL.
     */
    fun accept(url: String, policy: OriginPolicy): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.isOpaque || !u.isAbsolute) return null
        if (!u.scheme.equals("https", ignoreCase = true)) return null
        val authority = u.rawAuthority ?: return null
        if ('@' in authority || u.rawUserInfo != null) return null
        if (u.rawFragment != null) return null
        val query = u.rawQuery
        if (query != null && !QUERY.matches(query)) return null
        val host = u.host?.lowercase() ?: return null
        if (host.isEmpty()) return null
        val port = when (u.port) {
            -1, 443 -> ""
            in 1..65535 -> ":${u.port}"
            else -> return null
        }
        if ("https://$host$port" != policy.allowedOriginRule) return null
        val path = u.rawPath ?: return null
        if (!PATH.matches(path)) return null
        return policy.allowedOriginRule + path + (query?.let { "?$it" } ?: "")
    }

    /** The intent that opens [url] in the system browser. */
    fun viewIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * Opens [message]'s URL with `ACTION_VIEW`; false when nothing was
     * started. Refuses when this app is among the activities the intent could
     * start (`MATCH_DEFAULT_ONLY`: an implicit start, chooser included, only
     * ever picks a `CATEGORY_DEFAULT` activity), not only when it is the
     * default: with no default Android shows a chooser that would offer this
     * app, and a future link filter
     * must never bring the page back into the WebView (the loop the design
     * names). Package visibility does not hide an app from itself. It takes
     * the parsed message, not a string, so only a URL that passed [accept]
     * can reach it.
     */
    fun open(context: Context, message: BridgeMessage.OpenExternal): Boolean {
        val intent = viewIntent(message.url)
        @Suppress("DEPRECATION") // the int-flags overload is the one minSdk 30 has
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.any { it.activityInfo?.packageName == context.packageName }) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
