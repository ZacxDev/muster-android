package dev.zacx.muster.core.model

import java.net.URI

/**
 * 🔴 THE APP RENDERS NO USER-UPLOADED FILE FROM MUSTER, YET.
 *
 * muster serves uploaded bytes on its own origin, and before its attachment
 * sandbox (muster #207) some of them inline under its ordinary CSP, so an HTML
 * file's script runs on muster's origin (docs/design/android.md, "Risks"). In
 * the shell that origin is where the app's bridge listener answers, so a
 * script there could post a `pair` message for an account that is not the
 * user's. Until the sandbox is confirmed deployed, the shell refuses every
 * route that serves uploaded bytes, at the top level and as a subresource
 * (frames, images, fetches), and a notification never targets one. The
 * routes, all three of which #207 serves with the same headers:
 *
 * - `GET /tasks/{id}/attachments/{aid}`: a note attachment;
 * - `GET /uploads/{id}/file`: the owner's copy of an upload (the chip thumbnail);
 * - `GET /f/{id}`: an upload's share link.
 *
 * So, while closed, upload thumbnails do not render in the shell either.
 *
 * [SANDBOX_CONFIRMED] is a compile-time switch, deliberately not a server
 * flag: a server flag could be flipped by the same deployment whose files are
 * the hazard. Turning it on is an APK change made only once muster's sandbox
 * has shipped and a live probe has shown its headers.
 */
object AttachmentGate {
    /** Off until muster's attachment sandbox is confirmed shipped. */
    const val SANDBOX_CONFIRMED = false

    // Each route and anything below it. Matched on the decoded,
    // dot-segment-normalised path, case-insensitively, so "%2e%2e" tricks and
    // "/Tasks/" spellings cannot step around it.
    private val uploadPaths = listOf(
        Regex("""^/tasks/[^/]+/attachments(/.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^/uploads/[^/]+/file(/.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^/f/.+$""", RegexOption.IGNORE_CASE),
    )

    /**
     * True when the shell must not load [pathOrUrl]. 🔴 FAILS CLOSED: a URL no
     * parser can read ([LenientUrl]) is blocked.
     */
    fun blocks(pathOrUrl: String, sandboxConfirmed: Boolean = SANDBOX_CONFIRMED): Boolean {
        if (sandboxConfirmed) return false
        val path = pathOf(pathOrUrl) ?: return true
        return uploadPaths.any { it.matches(path) }
    }

    private fun pathOf(s: String): String? {
        val u = LenientUrl.parse(s) ?: return null
        // Decoded by URI. Repeated slashes collapsed first ("/tasks//1/attachments"
        // reaches the same handler, and a leading "//" would read as a host).
        val raw = (u.path ?: return null).replace(Regex("/{2,}"), "/")
        return runCatching { URI(null, null, raw, null).normalize().path }.getOrNull()
    }
}
