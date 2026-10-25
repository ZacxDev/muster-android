package dev.zacx.muster

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.core.net.toUri
import dev.zacx.muster.feature.shell.ShellWebView
import org.json.JSONObject

/**
 * DEBUG BUILDS ONLY (the release source set has a no-op of the same shape).
 *
 * Logs the cold-start phases under the `MusterStartup` tag, each as
 * milliseconds since the process started (`Process.getStartUptimeMillis`),
 * once per process:
 *
 *     adb logcat -s MusterStartup
 *
 * After the first main-frame load finishes it reads the page's own
 * Navigation and Paint Timing (and LCP, and whether a service worker controls
 * the page) with `evaluateJavascript`, and maps the page clock onto the same
 * uptime axis. Nothing here is a bridge: the page cannot call it.
 */
object StartupProbe {
    private const val TAG = "MusterStartup"
    private val seen = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    private fun sinceStart(uptime: Long) = uptime - Process.getStartUptimeMillis()

    fun mark(phase: String) {
        val now = SystemClock.uptimeMillis()
        synchronized(seen) {
            // A new activity in a live process (a warm start) is measured again.
            if (phase == "activity.onCreate") seen.removeAll { !it.startsWith("app.") }
            if (!seen.add(phase)) return
        }
        if (phase == "app.onCreate.start" && android.os.Build.VERSION.SDK_INT >= 33) {
            Log.i(TAG, "phase=process.startRequested t=${-(Process.getStartUptimeMillis() - Process.getStartRequestedUptimeMillis())}")
            Log.i(TAG, "phase=process.start t=0")
        }
        Log.i(TAG, "phase=$phase t=${sinceStart(now)}")
    }

    /**
     * Debug only: a document-start script on the muster origin that stamps
     * `window.__msContent` at the frame after the board's task list (filled by
     * htmx after load) first settles: the "first meaningful paint" of `/`.
     */
    fun attach(view: WebView, origin: String) {
        WebView.setWebContentsDebuggingEnabled(true) // debug builds only: chrome://inspect, CDP traces
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, CONTENT, setOf(origin))
    }

    private const val CONTENT = """document.addEventListener('htmx:afterSettle',function(e){if(window.__msContent||!e.target||e.target.id!=='tasks-list')return;requestAnimationFrame(function(){setTimeout(function(){if(!window.__msContent)window.__msContent=performance.now()},0)})});"""

    fun page(view: WebView, event: ShellWebView.PageEvent, url: String) {
        val phase = "page.${event.name.lowercase()}"
        val first = synchronized(seen) { phase !in seen }
        mark(phase)
        if (first) Log.i(TAG, "$phase path=${url.toUri().path}")
        if (event != ShellWebView.PageEvent.FINISHED) return
        synchronized(seen) { if (!seen.add("page.timing")) return }
        view.evaluateJavascript(OBSERVE, null)
        main.postDelayed({ view.evaluateJavascript(READ) { json -> report(json) } }, 2000)
    }

    private fun report(raw: String?) {
        // evaluateJavascript hands back a JSON-encoded string value.
        val o = runCatching { JSONObject(JSONObject("{\"v\":$raw}").getString("v")) }.getOrNull()
        if (o == null) {
            Log.i(TAG, "timing unreadable: $raw")
            return
        }
        // Page clock (epoch ms) onto the uptime axis: same device clock, read now.
        val epochMinusUptime = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val origin = sinceStart((o.getDouble("timeOrigin") - epochMinusUptime).toLong())
        fun at(k: String) = if (o.isNull(k) || o.optDouble(k, 0.0) <= 0.0) "-" else (origin + o.getDouble(k)).toLong().toString()
        Log.i(TAG, "phase=js.navigationStart t=$origin")
        for (k in listOf("requestStart", "responseStart", "responseEnd", "domInteractive", "domContentLoaded", "loadEnd", "firstPaint", "fcp", "content", "lcp")) {
            Log.i(TAG, "phase=js.$k t=${at(k)}")
        }
        Log.i(TAG, "page deliveryType=${o.optString("deliveryType")} transfer=${o.optLong("transfer")} encoded=${o.optLong("encoded")} decoded=${o.optLong("decoded")} swControlled=${o.optBoolean("sw")} swScope=${o.optString("swScope")} resources=${o.optString("res")}")
        o.optJSONArray("list")?.let { a -> for (i in 0 until a.length()) Log.i(TAG, "resource ${a.getString(i)} (ms after navigationStart)") }
    }

    private const val OBSERVE = """(function(){try{window.__msLcp=0;new PerformanceObserver(function(l){var e=l.getEntries();if(e.length)window.__msLcp=e[e.length-1].startTime}).observe({type:'largest-contentful-paint',buffered:true})}catch(e){}
try{window.__msSw='none';navigator.serviceWorker.getRegistration().then(function(r){window.__msSw=r?r.scope+(r.active?' active':' inactive'):'none'})}catch(e){window.__msSw='unsupported'}})()"""

    private const val READ = """(function(){var n=performance.getEntriesByType('navigation')[0]||{},p={};performance.getEntriesByType('paint').forEach(function(e){p[e.name]=e.startTime});
var r=performance.getEntriesByType('resource'),c=0,net=0;r.forEach(function(e){if(e.transferSize===0)c++;else net++});
return JSON.stringify({timeOrigin:performance.timeOrigin,requestStart:n.requestStart,responseStart:n.responseStart,responseEnd:n.responseEnd,domInteractive:n.domInteractive,domContentLoaded:n.domContentLoadedEventEnd,loadEnd:n.loadEventEnd,firstPaint:p['first-paint'],fcp:p['first-contentful-paint'],lcp:window.__msLcp,content:window.__msContent||0,deliveryType:n.deliveryType,transfer:n.transferSize,encoded:n.encodedBodySize,decoded:n.decodedBodySize,sw:!!(navigator.serviceWorker&&navigator.serviceWorker.controller),swScope:window.__msSw,res:r.length+' ('+c+' from cache, '+net+' over the network)',list:r.map(function(e){var u=new URL(e.name);return u.pathname+' '+e.initiatorType+' start='+Math.round(e.startTime)+' end='+Math.round(e.responseEnd)+' transfer='+e.transferSize+' size='+e.decodedBodySize})})})()"""
}
