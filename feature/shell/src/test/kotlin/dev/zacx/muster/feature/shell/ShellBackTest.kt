package dev.zacx.muster.feature.shell

import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class ShellBackTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val wv = WebView(activity)

    /**
     * Stands in for the system's back below the shell: registered first, so
     * it runs only when [back] is disabled (callbacks run newest first).
     */
    private var systemBacks = 0
    private val system = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { systemBacks++ }
    }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }

    private val back = ShellBack({ wv }, { activity.onBackPressedDispatcher.onBackPressed() })
        .also { activity.onBackPressedDispatcher.addCallback(activity, it) }

    private fun pressBack() = activity.onBackPressedDispatcher.onBackPressed()

    private fun answer(result: String) = shadowOf(wv).lastEvaluatedJavascriptCallback.onReceiveValue(result)

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The page's callbacks as MainActivity forwards them to [back]. */
    private fun configureShell() = ShellWebView.configure(
        wv, OriginPolicy.forServer("https://m.example.test")!!,
        object : ShellWebView.Callbacks {
            override fun onBridgeMessage(message: BridgeMessage) = Unit
            override fun openExternally(url: String) = Unit
            override fun onBlockedAttachment() = Unit
            override fun onRendererGone(view: WebView) = Unit
            override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<android.net.Uri>?) -> Unit) = Unit
            override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
            override fun onPageEvent(view: WebView, event: ShellWebView.PageEvent, url: String) = back.onPageEvent(event)
            override fun onHistoryChanged() = back.onHistoryChanged()
        },
    )

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

    // --- the dock's back-claim (muster docs/design/switcher-dock.md, "The shell change") ---

    /** A dock open on the first page of history still gets the press. */
    @Test
    fun aClaimEnablesBackWithNoHistory() {
        shadowOf(wv).setCanGoBack(false)
        back.sync()
        assertFalse(back.isEnabled)
        back.claim(true)
        assertTrue("a claim must enable back without history", back.isEnabled)
        back.claim(false)
        assertFalse(back.isEnabled)
    }

    /** A claimed press asks the page, and a "true" answer (the dock closed) is the whole press. */
    @Test
    fun aClaimedPressAsksThePageAndDoesNotGoBack() {
        shadowOf(wv).setCanGoBack(true)
        back.claim(true)
        pressBack()
        assertEquals("window.musterBack ? window.musterBack() : false", shadowOf(wv).lastEvaluatedJavascript)
        assertEquals(0, shadowOf(wv).goBackInvocations)
        answer("true")
        advance(ShellBack.CLAIM_TIMEOUT_MS + 50) // the answer cancelled the fallback
        assertEquals(0, shadowOf(wv).goBackInvocations)
        assertEquals(0, systemBacks)
    }

    /** An old page, or one whose dock already closed: back does what it does without a claim. */
    @Test
    fun aFalseAnswerFallsBackToGoBack() {
        shadowOf(wv).setCanGoBack(true)
        back.claim(true)
        pressBack()
        answer("false")
        assertEquals(1, shadowOf(wv).goBackInvocations)
        assertFalse("a false answer clears the claim", back.claimed)
        assertEquals(0, systemBacks)
    }

    /** At the start of history the fallback is the system's back, re-dispatched once. */
    @Test
    fun aFalseAnswerWithNoHistoryGoesToTheSystem() {
        shadowOf(wv).setCanGoBack(false)
        back.claim(true)
        pressBack()
        assertEquals(0, systemBacks)
        answer("false")
        assertFalse(back.isEnabled)
        assertEquals(1, systemBacks)
        assertEquals(0, shadowOf(wv).goBackInvocations)
    }

    /**
     * 🔴 A hung page cannot hold the press: ShadowWebView never answers by
     * itself, so the paused main looper advanced by 300 ms is a hung page.
     * The fallback decides the press once; an answer after it does nothing.
     */
    @Test
    fun noAnswerWithin300msFallsBackAndALateAnswerIsIgnored() {
        assertEquals(300L, ShellBack.CLAIM_TIMEOUT_MS)
        shadowOf(wv).setCanGoBack(true)
        back.claim(true)
        pressBack()
        advance(ShellBack.CLAIM_TIMEOUT_MS - 1)
        assertEquals("not before the timeout", 0, shadowOf(wv).goBackInvocations)
        advance(1)
        assertEquals("the timeout falls back to goBack", 1, shadowOf(wv).goBackInvocations)
        assertFalse(back.claimed)
        answer("false")
        answer("true")
        assertEquals("a late answer is ignored", 1, shadowOf(wv).goBackInvocations)
        assertEquals(0, systemBacks)
    }

    /**
     * 🔴 A second press on a hung page decides the first with the fallback
     * before it is handled, so the first falls back no later than its own
     * deadline: two presses 200 ms apart are two goBacks at 200 ms, and the
     * first press's cancelled timeout adds nothing at 300 ms.
     */
    @Test
    fun aSecondPressOnAHungPageDecidesTheFirstByItsDeadline() {
        shadowOf(wv).setCanGoBack(true)
        back.claim(true)
        pressBack()
        advance(200)
        assertEquals(0, shadowOf(wv).goBackInvocations)
        pressBack()
        assertEquals("the first press's fallback, then the second press unclaimed", 2, shadowOf(wv).goBackInvocations)
        advance(ShellBack.CLAIM_TIMEOUT_MS - 200 + 1) // past the first press's deadline
        assertEquals(2, shadowOf(wv).goBackInvocations)
        advance(ShellBack.CLAIM_TIMEOUT_MS)
        assertEquals(2, shadowOf(wv).goBackInvocations)
        assertFalse(back.claimed)
        assertEquals(0, systemBacks)
    }

    /** The same at the start of history: the first press's fallback is the system's back, once. */
    @Test
    fun aSecondPressOnAHungPageWithNoHistoryGoesToTheSystemOnce() {
        shadowOf(wv).setCanGoBack(false)
        back.claim(true)
        pressBack()
        advance(200)
        pressBack()
        assertEquals(1, systemBacks)
        advance(ShellBack.CLAIM_TIMEOUT_MS * 2)
        assertEquals(1, systemBacks)
        assertEquals(0, shadowOf(wv).goBackInvocations)
    }

    /**
     * 🔴 The audit's probe: 12 presses 250 ms apart on a hung page. Every press
     * after the first decides the one before it, so Back fires in every 300 ms
     * window: goBack counts sampled after each press are 0, 2, 3, … 12.
     */
    @Test
    fun rapidPressesOnAHungPageNeverLoseBack() {
        shadowOf(wv).setCanGoBack(true)
        back.claim(true)
        val counts = mutableListOf<Int>()
        repeat(12) { i ->
            if (i > 0) advance(250)
            pressBack()
            counts += shadowOf(wv).goBackInvocations
        }
        assertEquals(listOf(0) + (2..12).toList(), counts)
        advance(ShellBack.CLAIM_TIMEOUT_MS * 2)
        assertEquals(12, shadowOf(wv).goBackInvocations)
        assertEquals(0, systemBacks)
    }

    /**
     * 🔴 The renderer dies while a press waits on the page: the press is
     * dropped with its timeout, so the app does not leave (no system back)
     * and nothing goes back; a late answer from the dead page is ignored.
     */
    @Test
    fun aReplacedWebViewDropsThePendingPress() {
        shadowOf(wv).setCanGoBack(false)
        back.claim(true)
        pressBack()
        val pageAnswer = shadowOf(wv).lastEvaluatedJavascriptCallback
        advance(100)
        back.onWebViewChanged()
        advance(ShellBack.CLAIM_TIMEOUT_MS * 2)
        pageAnswer.onReceiveValue("false")
        assertEquals(0, systemBacks)
        assertEquals(0, shadowOf(wv).goBackInvocations)
    }

    /**
     * 🔴 A page starts while a press waits on the old one: the press is dropped
     * with its timeout, so the new document is not sent back.
     */
    @Test
    fun aPageStartDropsThePendingPress() {
        configureShell()
        shadowOf(wv).setCanGoBack(true)
        back.claim(true)
        pressBack()
        val pageAnswer = shadowOf(wv).lastEvaluatedJavascriptCallback
        advance(100)
        shadowOf(wv).webViewClient.onPageStarted(wv, "https://m.example.test/b", null)
        advance(ShellBack.CLAIM_TIMEOUT_MS * 2)
        pageAnswer.onReceiveValue("false")
        assertEquals(0, shadowOf(wv).goBackInvocations)
        assertEquals(0, systemBacks)
    }

    /** Every main-frame page start, a reload included, ends the claim. */
    @Test
    fun aPageStartClearsTheClaim() {
        configureShell()
        shadowOf(wv).setCanGoBack(false)
        back.claim(true)
        shadowOf(wv).webViewClient.onPageStarted(wv, "https://m.example.test/b", null)
        assertFalse(back.claimed)
        assertFalse(back.isEnabled)
    }

    /** 🔴 The view swap's replaceState must not end a live claim with the dock still open. */
    @Test
    fun aHistoryChangeKeepsTheClaim() {
        configureShell()
        shadowOf(wv).setCanGoBack(false)
        back.claim(true)
        shadowOf(wv).webViewClient.doUpdateVisitedHistory(wv, "https://m.example.test/a", false)
        assertTrue(back.claimed)
        assertTrue(back.isEnabled)
    }

    /** A new WebView, or a dead renderer, holds no dock. */
    @Test
    fun aReplacedWebViewEndsTheClaim() {
        shadowOf(wv).setCanGoBack(false)
        back.claim(true)
        back.onWebViewChanged()
        assertFalse(back.claimed)
        assertFalse(back.isEnabled)
    }
}
