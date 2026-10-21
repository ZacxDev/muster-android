package dev.zacx.muster.feature.shell

import android.content.Intent
import android.net.Uri
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class ShellChromeClientTest {
    private val wv = WebView(ApplicationProvider.getApplicationContext())
    private var picked: List<Uri>? = null
    private var asked = mutableListOf<Triple<List<String>, Boolean, Boolean>>()
    private val dialogs = mutableListOf<Pair<String, Boolean>>()
    private var answer = false

    private val callbacks = object : ShellWebView.Callbacks {
        override fun onBridgeMessage(message: BridgeMessage) = Unit
        override fun openExternally(url: String) = Unit
        override fun onBlockedAttachment() = Unit
        override fun onRendererGone(view: WebView) = Unit
        override fun onHistoryChanged() = Unit
        override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<Uri>?) -> Unit) {
            asked += Triple(mimeTypes, multiple, true)
            deliver(picked)
        }
        override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) {
            dialogs += message to confirm
            done(answer)
        }
    }

    private fun chrome(): WebChromeClient {
        ShellWebView.configure(wv, OriginPolicy.forServer("https://m.example.test")!!, callbacks)
        val c = shadowOf(wv).webChromeClient
        assertTrue("the shell sets no WebChromeClient", c is ShellChromeClient)
        return c
    }

    private class Params(private val types: Array<String>, private val m: Int) : WebChromeClient.FileChooserParams() {
        override fun getMode() = m
        override fun getAcceptTypes() = types
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent(): Intent = Intent()
    }

    /** A file input reaches the system picker, and only content:// URIs come back. */
    @Test
    fun aFileInputGetsContentUrisOnly() {
        picked = listOf(Uri.parse("content://media/1"), Uri.parse("file:///sdcard/secret"), Uri.parse("https://x.example.test/a"))
        var got: Array<Uri>? = arrayOf()
        val handled = chrome().onShowFileChooser(
            wv, ValueCallback { got = it },
            Params(arrayOf("image/png,image/jpeg", ""), WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE),
        )
        assertTrue(handled)
        assertEquals(listOf(Triple(listOf("image/png", "image/jpeg"), true, true)), asked)
        assertArrayEquals(arrayOf(Uri.parse("content://media/1")), got)
    }

    @Test
    fun aCancelledPickerAnswersNull() {
        picked = null
        var got: Array<Uri>? = arrayOf()
        chrome().onShowFileChooser(wv, ValueCallback { got = it }, Params(arrayOf(), WebChromeClient.FileChooserParams.MODE_OPEN))
        assertNull(got)
    }

    /** confirm() from muster is shown natively; a "no" cancels the JS result. */
    @Test
    fun mustersConfirmIsShownAndANoCancels() {
        answer = false
        val r = Shadow.newInstanceOf(JsResult::class.java)
        assertTrue(chrome().onJsConfirm(wv, "https://m.example.test/tasks/1", "Delete this task?", r))
        assertEquals(listOf("Delete this task?" to true), dialogs)
        assertTrue(shadowOf(r).wasCancelled())
    }

    /** A dialog from another origin is cancelled without being shown. */
    @Test
    fun aForeignDialogIsCancelledUnshown() {
        val r = Shadow.newInstanceOf(JsResult::class.java)
        assertTrue(chrome().onJsAlert(wv, "https://evil.example.test/", "hi", r))
        assertTrue(dialogs.isEmpty())
        assertTrue(shadowOf(r).wasCancelled())
    }
}
