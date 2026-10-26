package dev.zacx.muster.core.model

import java.net.URI

/**
 * 🔴 THE GATE ON USER-UPLOADED FILES FROM MUSTER.
 *
 * muster serves uploaded bytes on its own origin, and before its attachment
 * sandbox (muster #207) some of them inline under its ordinary CSP, so an HTML
 * file's script runs on muster's origin (docs/design/android.md, "Risks"). In
 * the shell that origin holds everything the app grants muster's page, so a
 * script there would get all of it:
 *
 * - the bridge listener: it could post a `pair` message for an account that
 *   is not the user's;
 * - 🔴 the microphone: ShellChromeClient grants RESOURCE_AUDIO_CAPTURE to any
 *   muster-origin request, and once the app holds RECORD_AUDIO it does so
 *   without a prompt. A script in an uploaded HTML file served inline would
 *   get the microphone silently.
 *
 * 🔴 With the gate open (as shipped), all of that rests on the SERVER: muster's
 * `sandbox` CSP, `nosniff` and attachment disposition on these routes. The app
 * does not check them, and no app-side check is robust (a page can
 * `pushState` to any URL, so a URL check proves nothing). A muster older than
 * #207, or a regression in its sandbox headers, is the hazard above.
 *
 * With the gate closed the shell refuses every route that serves uploaded
 * bytes, at the top level and as a subresource (frames, images, fetches).
 * Gate open or closed, a notification tap or a launch path never opens one
 * ([isUploadRoute]). The routes, all three of which #207 serves with the same
 * headers:
 *
 * - `GET /tasks/{id}/attachments/{aid}`: a note attachment;
 * - `GET /uploads/{id}/file`: the owner's copy of an upload (the chip thumbnail);
 * - `GET /f/{id}`: an upload's share link.
 *
 * So, while closed, upload thumbnails do not render in the shell either.
 * The closed gate is kept as a one-constant rollback; it is not the shipped
 * state.
 *
 * [SANDBOX_CONFIRMED] is a compile-time switch, deliberately not a server
 * flag: a server flag could be flipped by the same deployment whose files are
 * the hazard. Turning it on is an APK change made only once muster's sandbox
 * has shipped and a live probe has shown its headers.
 *
 * 🔴 IT IS ON, AND WHAT THAT RESTS ON:
 *
 * - #207 (d79f4c20) is an ancestor of d46b22e1, the Muster-Ref of muster
 *   0.78.0, which the deployment manifest pins by digest.
 * - A muster built at d46b22e1 served all three routes with
 *   `Content-Security-Policy: … sandbox`, `X-Content-Type-Options: nosniff`,
 *   and, for everything but png/jpeg/gif/webp, `Content-Disposition:
 *   attachment` with `application/octet-stream` (HTML) or `text/plain` (SVG).
 *   A sandboxed document has an opaque origin, which the bridge's exact
 *   `allowedOriginRules` never match, and runs no script.
 * - The production muster served the task-attachment route with the same
 *   `sandbox` CSP, `nosniff` and, for HTML, `attachment`. The upload routes
 *   were not probed in production; in muster's source they share the same
 *   header function.
 * - It holds for a muster at #207 or later. The app talks to whatever server
 *   it is given, so a server older than #207 is exactly the hazard above.
 *
 * Off, the routes below are refused everywhere; on, they load like any muster
 * page, and a non-image file arrives as a download (DownloadHandoff). An
 * unparseable URL is still refused by ShellNavigation, gate or not.
 */
object AttachmentGate {
    /** On: muster's attachment sandbox (#207) is in the deployed version. */
    const val SANDBOX_CONFIRMED = true

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
    fun blocks(pathOrUrl: String, sandboxConfirmed: Boolean = SANDBOX_CONFIRMED): Boolean =
        !sandboxConfirmed && isUploadRoute(pathOrUrl)

    /**
     * True when [pathOrUrl] is one of the routes above, whatever the gate
     * says. 🔴 FAILS CLOSED: a URL no parser can read counts as one.
     */
    fun isUploadRoute(pathOrUrl: String): Boolean {
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
