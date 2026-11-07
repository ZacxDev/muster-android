package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The document-start script for a tenant behind a sign-in gate (a tenant with
 * advertised sign-in origins, [OriginPolicy.ssoOrigins]).
 *
 * The gate's cookie expires (the proxy's cookie lifetime, often hours).
 * After that the gate answers muster's own background requests, not just
 * page loads: an htmx request or an EventSource gets a 302 to the identity
 * provider, which the browser follows cross-origin and then fails on CORS
 * (htmx `htmx:sendError`, an EventSource `error` that leaves it CLOSED), or
 * a 401 (an SSO proxy commonly answers `Accept: application/json` with 401).
 * Left alone, the page stops updating and says nothing.
 *
 * So on any of those signals the script PROBES: a same-origin
 * `GET /manifest.webmanifest` with `redirect: "manual"`. muster serves that
 * file without its own session, so the probe answers 200 whenever the gate
 * lets the request through, and an opaque redirect (or a 401) only when the
 * GATE stopped it. Only then does the page reload, which sends the main
 * frame through the gate to sign-in, which loads in the WebView because its
 * origins are this tenant's sign-in origins. A probe that fails on the
 * network does nothing (offline is not signed out), and a reload happens at
 * most once per [MIN_RELOAD_GAP_MS] per tab, so a gate that keeps refusing
 * cannot loop the page.
 *
 * 🔴 The infra contract this rests on: `/manifest.webmanifest` must stay
 * behind the gate (only `/api/device/` bypasses it). An ungated probe path
 * would always answer 200 and the guard would never fire.
 *
 * It posts nothing to the app and reads nothing from the page. It runs in the
 * main frame only, installed with `allowedOriginRules` set to exactly the
 * muster origin, never a sign-in origin.
 */
object SsoSessionGuard {
    const val PROBE_PATH = "/manifest.webmanifest"
    const val MIN_RELOAD_GAP_MS = 30_000

    /** The rules the script is installed with: the muster origin, exactly (never a sign-in origin, never `*`). */
    fun originRules(policy: OriginPolicy): Set<String> = setOf(policy.allowedOriginRule)

    /**
     * Installs the guard on [webView] when [policy] has sign-in origins;
     * false (nothing installed) for a tenant without them, or a WebView that
     * cannot run document-start scripts.
     */
    fun install(
        webView: WebView,
        policy: OriginPolicy,
        supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        add: (WebView, String, Set<String>) -> Unit = ::addScript,
    ): Boolean {
        if (policy.ssoOrigins.isEmpty() || !supported) return false
        add(webView, SOURCE, originRules(policy))
        return true
    }

    private fun addScript(webView: WebView, source: String, rules: Set<String>) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, source, rules)
        }
    }

    /** ES5 and self-contained: it must not depend on anything muster's page loads. */
    val SOURCE: String = """
(function () {
  if (window.top !== window || window.__musterSsoGuard) return;
  window.__musterSsoGuard = true;
  var KEY = '__musterSsoReloadAt';
  var GAP = $MIN_RELOAD_GAP_MS;
  var probing = false;
  function lastReload() {
    try { return +(window.sessionStorage.getItem(KEY) || 0); } catch (e) { return 0; }
  }
  function reload() {
    if (Date.now() - lastReload() < GAP) return;
    try { window.sessionStorage.setItem(KEY, String(Date.now())); } catch (e) {}
    window.location.reload();
  }
  function probe() {
    if (probing || typeof window.fetch !== 'function') return;
    probing = true;
    window.fetch('$PROBE_PATH', { method: 'GET', redirect: 'manual', credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) {
        probing = false;
        if (r.type === 'opaqueredirect' || r.status === 401) reload();
      }, function () { probing = false; });
  }
  document.addEventListener('htmx:sendError', probe, true);
  document.addEventListener('htmx:responseError', function (e) {
    var x = e && e.detail && e.detail.xhr;
    if (x && (x.status === 401 || x.status === 0)) probe();
  }, true);
  var ES = window.EventSource;
  if (typeof ES === 'function') {
    var Guarded = function (url, opts) {
      var es = arguments.length > 1 ? new ES(url, opts) : new ES(url);
      es.addEventListener('error', function () { if (es.readyState === 2) probe(); });
      return es;
    };
    Guarded.prototype = ES.prototype;
    Guarded.CONNECTING = 0; Guarded.OPEN = 1; Guarded.CLOSED = 2;
    window.EventSource = Guarded;
  }
})();
""".trimIndent()
}
