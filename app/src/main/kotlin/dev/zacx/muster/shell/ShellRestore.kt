package dev.zacx.muster.shell

import android.content.Intent
import android.os.Bundle
import dev.zacx.muster.MainActivity
import dev.zacx.muster.MusterApp
import dev.zacx.muster.core.model.MusterPath

/**
 * The shell's page across an activity re-creation the shell does not handle
 * itself, and across process death.
 *
 * The common configuration changes (rotation, window size, density, dark
 * mode, keyboard) never re-create the activity: the manifest declares them,
 * so the live WebView keeps its page, scroll and connections. Any other
 * change (font size among them), and a process the system killed in the
 * background, come back through onCreate with the saved state: this puts the
 * shell back on the tenant and page it was showing, instead of the launch
 * intent's page (the home page, or an old notification's path).
 *
 * Only a path on the tenant's own origin is kept, and the restored intent goes
 * through the same checks as a notification tap (MusterPath, TapRouting,
 * ShellNavigation.tapTarget); a sign-in page on another origin restores to the
 * tenant's home page, which redirects to sign-in again if it is still needed.
 */
object ShellRestore {
    private const val KEY_TENANT = "dev.zacx.muster.shell.TENANT"
    private const val KEY_PATH = "dev.zacx.muster.shell.PATH"

    /** Records the shell showing tenant [tenant] on [origin] at [url] (the WebView's current URL). */
    fun save(out: Bundle, tenant: String, origin: String, url: String?) {
        out.putString(KEY_TENANT, tenant)
        out.putString(KEY_PATH, pathOn(origin, url))
    }

    /** The intent that re-opens the saved shell, or null if [saved] holds none. */
    fun intent(saved: Bundle?): Intent? {
        val tenant = saved?.getString(KEY_TENANT) ?: return null
        return Intent()
            .putExtra(MusterApp.EXTRA_TENANT, tenant)
            .putExtra(MainActivity.EXTRA_PATH, MusterPath.parse(saved.getString(KEY_PATH)))
    }

    /**
     * [url]'s path when it is on [origin] exactly; null otherwise, and for the
     * home page. The query and fragment are dropped: a one-time `?code=` or
     * `?token=` URL, or a GET that changes state, must not be fetched again
     * when the shell comes back after process death.
     */
    fun pathOn(origin: String, url: String?): String? {
        if (url == null || !url.startsWith("$origin/")) return null
        val path = url.substring(origin.length).takeWhile { it != '?' && it != '#' }
        return if (path == "/") null else MusterPath.parse(path)
    }
}
