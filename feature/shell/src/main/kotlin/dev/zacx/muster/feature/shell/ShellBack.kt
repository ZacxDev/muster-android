package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.activity.OnBackPressedCallback

/**
 * Back in the shell: ONE callback for the activity's life. It is enabled
 * exactly while the current WebView can go back, so predictive back maps to
 * WebView history and, at the start of history, falls through to the system
 * (which leaves the app). [sync] runs on every history change
 * (WebViewClient.doUpdateVisitedHistory) and whenever the WebView changes, so
 * a back-out never disables it for good.
 */
class ShellBack(private val webView: () -> WebView?) : OnBackPressedCallback(false) {
    fun sync() {
        isEnabled = webView()?.canGoBack() == true
    }

    override fun handleOnBackPressed() {
        val v = webView()
        if (v != null && v.canGoBack()) v.goBack()
        sync()
    }
}
