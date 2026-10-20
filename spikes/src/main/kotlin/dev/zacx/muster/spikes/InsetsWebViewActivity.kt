package dev.zacx.muster.spikes

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat

/**
 * S12: a full-window WebView in an edge-to-edge activity, with NO padding
 * applied by the shell. The test page (assets/insets.html) reads CSS
 * env(safe-area-inset-*) and logs it; this activity logs the native insets
 * the WebView was handed, so the two can be compared.
 *
 *   am start -n dev.zacx.muster.spikes/.InsetsWebViewActivity --es fit cover|auto
 */
class InsetsWebViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val fit = intent.getStringExtra("fit") ?: "cover"
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                log("S12 page fit=$fit ${m.message()}")
                return true
            }
        }
        setContentView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(wv) { v, insets ->
            val d = resources.displayMetrics.density
            fun fmt(t: Int) = insets.getInsets(t).let { "l=${it.left / d} t=${it.top / d} r=${it.right / d} b=${it.bottom / d}" }
            log(
                "S12 native fit=$fit density=$d systemBars[${fmt(WindowInsetsCompat.Type.systemBars())}] " +
                    "cutout[${fmt(WindowInsetsCompat.Type.displayCutout())}] " +
                    "view=${v.width}x${v.height}px",
            )
            // Pass the insets through untouched: the shell does not pad.
            ViewCompat.onApplyWindowInsets(v, insets)
        }

        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        log("S12 webview package=${pkg?.packageName} version=${pkg?.versionName} ${deviceLine(this)}")

        val html = assets.open("insets.html").bufferedReader().readText().replace("__FIT__", fit)
        wv.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "utf-8", null)
    }
}
