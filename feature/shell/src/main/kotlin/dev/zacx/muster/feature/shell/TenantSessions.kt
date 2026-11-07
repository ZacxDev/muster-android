package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Keeps each muster server's web session apart (muster
 * docs/design/multi-tenant.md, "WebView data and cookies"): every tenant's
 * pages load in that tenant's own WebView profile, which has its own
 * cookies, web storage and service workers, shared with no other profile.
 * Switching servers therefore never carries one server's session cookie to
 * another, and switching back finds the first one still signed in.
 *
 * - The profile is set on a NEW WebView before it loads anything ([prepare]).
 * - The migrated tenant keeps the default profile, which holds the sign-in
 *   from before the upgrade; every other tenant has `tenant-<id>`.
 * - 🔴 A tenant whose profile is not the default one is never loaded without
 *   it: on a WebView that lacks `MULTI_PROFILE` [prepare] refuses, and the
 *   app shows "update Android System WebView" instead of loading that server
 *   in the default profile, where the other server's cookies live. (Such a
 *   phone can only ever have added one tenant, which uses the default.)
 * - A removed tenant's profile is deleted, or for the default profile
 *   cleared ([forget]).
 *
 * [V] is the view type (WebView in the app; a fake in the JVM tests).
 */
class TenantSessions<V>(private val backend: Backend<V>) {
    interface Backend<V> {
        /** The WebView supports profiles (`WebViewFeature.MULTI_PROFILE`). */
        val multiProfile: Boolean

        /** Puts [view] in profile [name], creating the profile if needed. */
        fun setProfile(view: V, name: String)

        /** Deletes profile [name] and all its data; false if it could not be (in use, or the default). */
        fun delete(name: String): Boolean

        /** Removes every cookie and all web storage of profile [name], keeping the profile. */
        fun clear(name: String)

        /** Writes profile [name]'s cookies to disk now. */
        fun flush(name: String)
    }

    /** Puts [view] in [profile] before its first load; false: it must not load (see the class comment). */
    fun prepare(view: V, profile: String): Boolean {
        if (!backend.multiProfile) return profile == DEFAULT
        backend.setProfile(view, profile)
        return true
    }

    /** A second tenant needs its own profile, so it needs `MULTI_PROFILE`; the first uses the default. */
    fun canAddTenant(existing: Int): Boolean = existing == 0 || backend.multiProfile

    /** A removed tenant's web data goes: its profile is deleted, or cleared when it cannot be (the default). */
    fun forget(profile: String) {
        if (profile == DEFAULT || !backend.multiProfile || !runCatching { backend.delete(profile) }.getOrDefault(false)) {
            runCatching { backend.clear(profile) }
        }
    }

    fun flush(profile: String) {
        if (profile != DEFAULT && !backend.multiProfile) return
        runCatching { backend.flush(profile) }
    }

    companion object {
        /** androidx.webkit `Profile.DEFAULT_PROFILE_NAME` (core/auth `Tenants.DEFAULT_PROFILE`). */
        const val DEFAULT = "Default"
    }
}

/**
 * [TenantSessions.Backend] on androidx.webkit's multi-profile API. Every
 * profile call sits under its own `MULTI_PROFILE` check. Needs a real WebView
 * provider; not run on the JVM.
 */
class WebViewProfiles : TenantSessions.Backend<WebView> {
    override val multiProfile: Boolean
        get() = supported()

    private fun supported() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    override fun setProfile(view: WebView, name: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(view, name)
        } else {
            error("WebView profiles are not supported")
        }
    }

    override fun delete(name: String): Boolean =
        name != TenantSessions.DEFAULT && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            ProfileStore.getInstance().deleteProfile(name)

    override fun clear(name: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val p = ProfileStore.getInstance().getProfile(name) ?: return
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        } else {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    override fun flush(name: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getProfile(name)?.cookieManager?.flush()
        } else {
            android.webkit.CookieManager.getInstance().flush()
        }
    }
}
