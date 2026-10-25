package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The document-start script behind pull to refresh ([PullGate]). At every
 * one-finger `touchstart` it tells the app, over the `musterApp` bridge, what
 * is under the finger:
 *
 *     {"type":"touch-start","doc_top":0,"inner_top":null,"owner":"page"}
 *
 * - `doc_top`: the document's scroll offset plus the pinch-zoom offset
 *   (`visualViewport.offsetTop`), CSS px, rounded up;
 * - `inner_top`: the largest `scrollTop` among the elements between the touch
 *   target and the document whose `overflow-y` scrolls and whose content is
 *   taller than they are; null when there are none;
 * - `owner`: `overlay` when the target is inside a fixed-position element or a
 *   dialog, or a modal `<dialog>` is open; `gestures` when an element on the
 *   way up takes its own vertical drags (`touch-action` without `pan-y`, an
 *   xterm); otherwise `page`.
 *
 * It sends geometry only: no text, no URL, nothing typed, and the app sends
 * nothing back. It runs in the main frame only, and is installed with
 * `allowedOriginRules` set to exactly the paired origin, so no other origin's
 * document runs it. The listener re-checks origin and frame
 * ([BridgeMessage.parse]).
 */
object PullProbeScript {
    const val MESSAGE_TYPE = "touch-start"

    /** The rules the script is installed with: the paired origin, exactly (never `*`). */
    fun originRules(policy: OriginPolicy): Set<String> = setOf(policy.allowedOriginRule)

    /** Installs the script on [webView] for [policy]'s origin; false when this WebView cannot run document-start scripts (then nothing ever pulls). */
    fun install(
        webView: WebView,
        policy: OriginPolicy,
        supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        add: (WebView, String, Set<String>) -> Unit = ::addScript,
    ): Boolean {
        if (!supported) return false
        add(webView, SOURCE, originRules(policy))
        return true
    }

    private fun addScript(webView: WebView, source: String, rules: Set<String>) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, source, rules)
        }
    }

    /** The script itself. ES5 and self-contained: it must not depend on anything muster's page loads. */
    val SOURCE: String = """
(function () {
  if (window.top !== window || window.__musterPullProbe) return;
  window.__musterPullProbe = true;
  var SCROLLS = /(auto|scroll|overlay)/;
  function elementOf(n) { return n && n.nodeType === 1 ? n : (n && n.parentElement) || null; }
  function ownsDrags(cs, el) {
    var ta = cs.touchAction || 'auto';
    if (ta !== 'auto' && ta !== 'manipulation' && ta.indexOf('pan-y') < 0) return true;
    return !!(el.classList && el.classList.contains('xterm'));
  }
  function owner(start) {
    for (var el = start; el && el !== document.documentElement; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (cs.position === 'fixed') return 'overlay';
      if (el.matches && el.matches('dialog[open],[role=dialog],[role=alertdialog],[aria-modal=true]')) return 'overlay';
      if (ownsDrags(cs, el)) return 'gestures';
    }
    if (document.querySelector('dialog[open]:modal')) return 'overlay';
    return 'page';
  }
  document.addEventListener('touchstart', function (e) {
    try {
      if (!e.touches || e.touches.length !== 1) return;
      var bridge = window.${ShellWebView.BRIDGE_NAME};
      if (!bridge || !bridge.postMessage) return;
      var se = document.scrollingElement || document.documentElement;
      var start = elementOf(e.target);
      var inner = null;
      for (var el = start; el && el !== se && el !== document.documentElement; el = el.parentElement) {
        if (el.scrollHeight > el.clientHeight + 1 && SCROLLS.test(getComputedStyle(el).overflowY)) {
          var t = Math.ceil(el.scrollTop);
          inner = inner === null ? t : Math.max(inner, t);
        }
      }
      var vv = window.visualViewport;
      var docTop = Math.ceil(se.scrollTop + (vv ? vv.offsetTop : 0));
      bridge.postMessage(JSON.stringify({ type: '$MESSAGE_TYPE', doc_top: docTop, inner_top: inner, owner: owner(start) }));
    } catch (err) {}
  }, { capture: true, passive: true });
})();
"""
}
