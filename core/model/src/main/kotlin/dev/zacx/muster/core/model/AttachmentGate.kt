package dev.zacx.muster.core.model

import java.net.URI

/**
 * 🔴 THE APP RENDERS NO NOTE ATTACHMENT FROM MUSTER, YET.
 *
 * muster serves `GET /tasks/{id}/attachments/{aid}` with the stored content
 * type and `Content-Disposition: inline` under its ordinary CSP, so an HTML
 * attachment's script runs on muster's origin (docs/design/android.md,
 * "Risks"). In the shell that origin is where the app's bridge listener
 * answers, so a script there could post a `pair` message for an account that
 * is not the user's. Until muster sandboxes attachments, the shell refuses to
 * load any attachment URL, at the top level or as a subresource (frames,
 * images, fetches), and a notification never targets one.
 *
 * [SANDBOX_CONFIRMED] is a compile-time switch, deliberately not a server
 * flag: a server flag could be flipped by the same deployment whose
 * attachments are the hazard. Turning it on is an APK change made only once
 * muster's attachment sandbox has shipped and been confirmed.
 */
object AttachmentGate {
    /** Off until muster's attachment sandbox is confirmed shipped. */
    const val SANDBOX_CONFIRMED = false

    // /tasks/{id}/attachments and anything below it. Matched on the decoded,
    // dot-segment-normalised path, case-insensitively, so "%2e%2e" tricks and
    // "/Tasks/" spellings cannot step around it.
    private val attachmentPath = Regex("""^/tasks/[^/]+/attachments(/.*)?$""", RegexOption.IGNORE_CASE)

    /** True when the shell must not load [pathOrUrl]. */
    fun blocks(pathOrUrl: String, sandboxConfirmed: Boolean = SANDBOX_CONFIRMED): Boolean {
        if (sandboxConfirmed) return false
        val path = pathOf(pathOrUrl) ?: return false
        return attachmentPath.matches(path)
    }

    private fun pathOf(s: String): String? {
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        // Decoded by URI. Repeated slashes collapsed first ("/tasks//1/attachments"
        // reaches the same handler, and a leading "//" would read as a host).
        val raw = (u.path ?: return null).replace(Regex("/{2,}"), "/")
        return runCatching { URI(null, null, raw, null).normalize().path }.getOrNull() ?: raw
    }
}
