package dev.zacx.muster.feature.shell

import android.net.Uri
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * The shell's WebChromeClient: muster's file inputs and its JS `alert` /
 * `confirm` dialogs, which a WebView without one leaves dead or cancels.
 *
 * - **Files:** `<input type=file>` opens the system picker (the activity, by
 *   ActivityResult). Only `content://` URIs come back to the page: never a
 *   `file://` path, and the WebView keeps `allowFileAccess = false`.
 * - **Dialogs:** `alert` and `confirm` from the muster origin are shown as
 *   native dialogs. A dialog from any other origin is cancelled unshown.
 *   `prompt` is not used by muster and keeps the WebView's default.
 */
class ShellChromeClient(private val policy: OriginPolicy, private val cb: ShellWebView.Callbacks) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        val types = fileChooserParams.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        cb.chooseFiles(types, multiple) { picked ->
            filePathCallback.onReceiveValue(onlyContent(picked)?.toTypedArray())
        }
        return true
    }

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        dialog(url, message, confirm = false, result)

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        dialog(url, message, confirm = true, result)

    private fun dialog(url: String?, message: String?, confirm: Boolean, result: JsResult): Boolean {
        if (url == null || !policy.allowsInWebView(url)) {
            result.cancel()
            return true
        }
        cb.jsDialog(message.orEmpty().take(2000), confirm) { ok -> if (ok) result.confirm() else result.cancel() }
        return true
    }

    companion object {
        /** The picked URIs a page may receive: `content://` only; null (cancelled) stays null. */
        fun onlyContent(picked: List<Uri>?): List<Uri>? =
            picked?.filter { it.scheme.equals("content", ignoreCase = true) }
    }
}
