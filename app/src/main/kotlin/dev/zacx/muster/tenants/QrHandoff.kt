package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.feature.shell.OriginPolicy
import java.net.URI

/**
 * muster's "Sign in with the muster app" hand-off. A desktop shows a QR code
 * for `https://<host>/auth/qr#mqr_<code>`; on Android the page that QR opens
 * offers an "Open in the muster app" link:
 *
 * `intent://<host>/auth/qr#Intent;scheme=https;package=dev.zacx.muster;S.dev.zacx.muster.QR_CODE=<code>;S.browser_fallback_url=…;end`
 *
 * which Chrome turns into a VIEW intent for `https://<host>/auth/qr`, with
 * the code as the string extra [EXTRA_CODE]. [match] decides, without any
 * Android framework, whether that intent opens the page in one of this
 * phone's own servers' shell. Anything it refuses opens the app as a plain
 * launch would, with no navigation.
 *
 * 🔴 The code is a sign-in secret: it is never logged ([Result.Open.toString]
 * leaves it out), and it reaches the page only as the URL fragment, which no
 * request carries.
 */
object QrHandoff {
    /** The string extra carrying the `mqr_…` code (the intent URI's `S.dev.zacx.muster.QR_CODE`). */
    const val EXTRA_CODE = "dev.zacx.muster.QR_CODE"

    /** The only path a hand-off may name, exactly. */
    const val PATH = "/auth/qr"

    /** A code longer than this is refused (muster's codes are `mqr_` and 43 characters). */
    const val MAX_CODE_LENGTH = 128

    /** `Intent.ACTION_VIEW`, spelled here so this file needs no Android framework. */
    const val ACTION_VIEW = "android.intent.action.VIEW"

    private val CODE = Regex("^mqr_[A-Za-z0-9_-]+$")

    sealed interface Result {
        /** Not a hand-off at all (another action, no data, another URL): routed as before. */
        data object NotHandoff : Result

        /** A hand-off URL whose code is missing or malformed: the app opens, with no navigation. */
        data object BadCode : Result

        /** A well-formed hand-off for a server this phone has not added: the app opens, and says so. */
        data object UnknownServer : Result

        /** Open [path] (`/auth/qr#<code>`) in [tenant]'s shell. */
        data class Open(val tenant: TenantRecord, val path: String) : Result {
            override fun toString() = "Open(tenant=${tenant.id}, path=$PATH#…)"
        }
    }

    /**
     * [action], [data] and [code] are the intent's action, data string and
     * [EXTRA_CODE] extra. [fromHistory]: the intent is being redelivered from
     * Recents (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), so its code was used
     * already and is never replayed.
     *
     * Open only when: the action is VIEW; the data is `https`, with no user
     * info, query or fragment, and its path is exactly [PATH]; the code is
     * `mqr_` plus `[A-Za-z0-9_-]`, at most [MAX_CODE_LENGTH] long; and the
     * data's origin (scheme, host and port) is the origin of a server already
     * added on this phone.
     */
    fun match(tenants: Tenants, action: String?, data: String?, code: String?, fromHistory: Boolean = false): Result {
        if (fromHistory || action != ACTION_VIEW || data == null) return Result.NotHandoff
        // Strict parse: a URL java.net.URI refuses is not a hand-off.
        val u = runCatching { URI(data) }.getOrNull() ?: return Result.NotHandoff
        if (!u.scheme.equals("https", ignoreCase = true)) return Result.NotHandoff
        if (u.rawUserInfo != null || u.host.isNullOrEmpty()) return Result.NotHandoff
        if (u.rawPath != PATH || u.rawQuery != null || u.rawFragment != null) return Result.NotHandoff
        if (code == null || code.length > MAX_CODE_LENGTH || !CODE.matches(code)) return Result.BadCode
        val origin = OriginPolicy.forServer(data)?.allowedOriginRule ?: return Result.NotHandoff
        val tenant = tenants.all.firstOrNull { OriginPolicy.forServer(it.origin)?.allowedOriginRule == origin }
            ?: return Result.UnknownServer
        return Result.Open(tenant, "$PATH#$code")
    }
}
