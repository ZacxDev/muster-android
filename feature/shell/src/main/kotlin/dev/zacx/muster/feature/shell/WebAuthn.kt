package dev.zacx.muster.feature.shell

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Passkeys in the shell. A WebView exposes no WebAuthn (`PublicKeyCredential`
 * and `navigator.credentials.create/get` are missing) unless the app turns it
 * on, so muster's sign-in page and its "Confirm it's you" step-up offered no
 * passkey in the app while the browser did.
 *
 * [WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP] makes the WebView
 * run a ceremony as this app: Android's credential manager checks that the
 * relying party's site lists this package and signing certificate in
 * `/.well-known/assetlinks.json` (relation
 * `delegate_permission/common.get_login_creds`), and the server sees
 * `clientDataJSON.origin` = `android:apk-key-hash:<base64url SHA-256 of the
 * signing certificate>`, not the page's https origin. muster's server and
 * its auth backend must accept that origin; this switch alone is not enough.
 *
 * The `_FOR_BROWSER` mode is for browser apps, so it is never used here.
 * Conditional mediation (`mediation: "conditional"`, passkey autofill) is not
 * supported in a WebView; an explicit `get()` from a button is.
 */
object WebAuthn {
    /**
     * Turns WebAuthn on for [settings] as this app; false (and nothing set)
     * when this WebView does not have the feature, so the page keeps offering
     * no passkey, as before.
     */
    fun enable(
        settings: WebSettings,
        supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION),
        set: (WebSettings, Int) -> Unit = WebSettingsCompat::setWebAuthenticationSupport,
    ): Boolean {
        if (!supported) return false
        set(settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
        return true
    }
}
