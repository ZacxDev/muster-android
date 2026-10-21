package dev.zacx.muster.feature.shell

import android.webkit.WebView
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class ShellBackTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val wv = WebView(activity)
    private val back = ShellBack { wv }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }

    private fun pressBack() = activity.onBackPressedDispatcher.onBackPressed()

    /**
     * 🔴 Back keeps working after the first back-out: history at its start lets
     * back through to the system, and as soon as there is history again back
     * goes to the WebView again.
     */
    @Test
    fun backFollowsHistoryForTheActivitysWholeLife() {
        shadowOf(wv).setCanGoBack(true)
        back.sync()
        pressBack()
        assertEquals(1, shadowOf(wv).goBackInvocations)

        shadowOf(wv).setCanGoBack(false)
        back.sync() // doUpdateVisitedHistory after the goBack
        assertFalse(back.isEnabled)
        pressBack() // at the start of history: the system's back
        assertEquals(1, shadowOf(wv).goBackInvocations)

        shadowOf(wv).setCanGoBack(true) // the user navigated again
        back.sync()
        assertTrue("back must come back after a back-out", back.isEnabled)
        pressBack()
        assertEquals(2, shadowOf(wv).goBackInvocations)
    }

    /** The shell's WebViewClient reports every history change, which is what re-syncs back. */
    @Test
    fun historyChangesReachTheShell() {
        var changes = 0
        ShellWebView.configure(
            wv, OriginPolicy.forServer("https://m.example.test")!!,
            object : ShellWebView.Callbacks {
                override fun onBridgeMessage(message: BridgeMessage) = Unit
                override fun openExternally(url: String) = Unit
                override fun onBlockedAttachment() = Unit
                override fun onRendererGone(view: WebView) = Unit
                override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<android.net.Uri>?) -> Unit) = Unit
                override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
                override fun onHistoryChanged() { changes++ }
            },
        )
        shadowOf(wv).webViewClient.doUpdateVisitedHistory(wv, "https://m.example.test/a", false)
        assertEquals(1, changes)
    }
}
