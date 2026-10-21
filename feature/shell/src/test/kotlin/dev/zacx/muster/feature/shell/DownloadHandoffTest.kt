package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A download (muster #207 serves every non-image upload as
 * `Content-Disposition: attachment`) is handed to the browser with its URL
 * and nothing else, and never loaded in the app's WebView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class DownloadHandoffTest {
    private val policy = OriginPolicy.forServer("https://m.example.test")!!
    private val handedOff = mutableListOf<String>()

    private val callbacks = object : ShellWebView.Callbacks {
        override fun onBridgeMessage(message: BridgeMessage) = Unit
        override fun openExternally(url: String) { handedOff += url }
        override fun onBlockedAttachment() = Unit
        override fun onRendererGone(view: WebView) = Unit
        override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<android.net.Uri>?) -> Unit) = Unit
                override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
                override fun onHistoryChanged() = Unit
    }

    /** The seam: the shell's WebView really has the listener, and it hands off. */
    @Test
    fun theShellHandsADownloadToTheBrowserAndDoesNotLoadIt() {
        val wv = WebView(ApplicationProvider.getApplicationContext())
        ShellWebView.configure(wv, policy, callbacks)
        val listener = shadowOf(wv).downloadListener
        assertTrue("no DownloadListener on the shell's WebView", listener is DownloadHandoff)

        val url = "https://m.example.test/tasks/42/attachments/7"
        listener.onDownloadStart(url, "UA", "attachment; filename=\"x.html\"", "text/html", 120)
        assertEquals(listOf(url), handedOff)
        assertNull("the WebView must not load the file", shadowOf(wv).lastLoadedUrl)
    }

    @Test
    fun onlyHttpsUrlsCross() {
        val h = DownloadHandoff { handedOff += it }
        listOf(
            "blob:https://m.example.test/0f3c",
            "data:text/html,<script>1</script>",
            "http://m.example.test/f/abc",
            "https://u:p@m.example.test/f/abc",
            "javascript:alert(1)",
            "",
        ).forEach { h.onDownloadStart(it, "UA", "attachment", "text/html", 1) }
        h.onDownloadStart(null, null, null, null, 0)
        assertTrue(handedOff.toString(), handedOff.isEmpty())
        h.onDownloadStart("https://m.example.test/f/abc", "UA", "attachment", "application/pdf", 1)
        assertEquals(listOf("https://m.example.test/f/abc"), handedOff)
    }
}
