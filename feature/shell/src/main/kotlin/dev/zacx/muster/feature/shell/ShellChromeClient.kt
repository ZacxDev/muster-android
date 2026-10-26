package dev.zacx.muster.feature.shell

import android.net.Uri
import android.webkit.JsResult
import android.webkit.PermissionRequest
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
 * - **Microphone:** a page's permission request is granted only when its
 *   origin is exactly the muster origin and it asks for audio capture and
 *   nothing else ([grantsMicrophone]); then the app asks Android for
 *   RECORD_AUDIO if it lacks it ([ShellWebView.Callbacks.requestMicrophone])
 *   and grants or denies by the answer. Every other request (camera, MIDI,
 *   protected media, another origin, audio bundled with anything else) is
 *   denied at once. One request waits at a time: a newer one denies the older,
 *   and a request the page withdraws is forgotten, so none is left hanging and
 *   a late answer never reaches a request that is no longer current.
 *
 *   Frames: WebView gives a permission request no main-frame bit, so the
 *   origin is the whole rule. A same-origin subframe is muster's own page and
 *   is treated like the main frame; any other frame's origin is refused here,
 *   and muster's `Permissions-Policy: microphone=(self)` refuses it before
 *   it reaches the app.
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

    /** The microphone request waiting for Android's answer; null when none is. */
    private var pendingMic: PermissionRequest? = null

    override fun onPermissionRequest(request: PermissionRequest) {
        if (!grantsMicrophone(policy, request.origin?.toString(), request.resources)) {
            request.deny()
            return
        }
        pendingMic?.deny() // a newer request replaces an unanswered one
        pendingMic = request
        cb.requestMicrophone { ok ->
            if (pendingMic === request) {
                pendingMic = null
                if (ok) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
            }
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pendingMic === request) pendingMic = null
    }

    companion object {
        /**
         * True only for a request from exactly the muster origin for audio
         * capture alone. Anything else is denied, including audio asked for
         * together with another resource.
         */
        fun grantsMicrophone(policy: OriginPolicy, origin: String?, resources: Array<String>?): Boolean =
            origin != null && policy.allowsInWebView(origin) &&
                !resources.isNullOrEmpty() && resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }

        /** The picked URIs a page may receive: `content://` only; null (cancelled) stays null. */
        fun onlyContent(picked: List<Uri>?): List<Uri>? =
            picked?.filter { it.scheme.equals("content", ignoreCase = true) }
    }
}
