package dev.zacx.muster

import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.feature.shell.OriginPolicy
import java.net.URI

/**
 * When the shell reloads muster's Account page after push registration settles.
 *
 * Pairing reloads the page as soon as the code is redeemed, so the new phone is
 * listed. Push registration finishes later (the ntfy app answers, then muster
 * sends its test push before it accepts the endpoint: seconds), so that reload
 * showed "push not registered" and no "Send a test notification" button, until
 * the user reloaded the page by hand. Measured on an emulator, twice, at the
 * first pairing. The shell now reloads once more when the push status BECOMES
 * registered, and only while the page is Account: a reload anywhere else could
 * throw away what the user is typing.
 */
object AccountPageRefresh {
    /**
     * @param previous the status before this change, or null for the first
     *   value read (opening the shell is not a change).
     */
    fun shouldReload(previous: PushStatus?, current: PushStatus, pageUrl: String?, policy: OriginPolicy): Boolean {
        if (previous == null || previous == current || current != PushStatus.REGISTERED) return false
        val url = pageUrl ?: return false
        if (!policy.allowsInWebView(url)) return false
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return path.trimEnd('/') == ACCOUNT
    }

    private const val ACCOUNT = "/account"
}
