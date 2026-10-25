package dev.zacx.muster.feature.shell

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
import android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX
import android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
import android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The page's microphone: ShellChromeClient's grant rule and MicrophoneAccess's
 * RECORD_AUDIO flow, wired together the way MainActivity wires them (the
 * client's requestMicrophone calls MicrophoneAccess.request).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class MicrophonePermissionTest {
    private val wv = WebView(ApplicationProvider.getApplicationContext())

    /** A fake Android: what the app holds, and what the user answers. */
    private inner class FakeAndroid : MicrophoneAccess.Host {
        var granted = false
        var rationale = false
        var asked = false
        var launches = 0
        var launchFails = false
        var settingsOffered = 0
        override fun granted() = granted
        override fun rationale() = rationale
        override fun askedBefore() = asked
        override fun markAsked() { asked = true }
        override fun launch() { if (launchFails) error("no permission UI"); launches++ }
        override fun offerSettings() { settingsOffered++ }
    }

    private val android = FakeAndroid()
    private val access = MicrophoneAccess(android)

    private val callbacks = object : ShellWebView.Callbacks {
        override fun onBridgeMessage(message: BridgeMessage) = Unit
        override fun openExternally(url: String) = Unit
        override fun onBlockedAttachment() = Unit
        override fun onRendererGone(view: WebView) = Unit
        override fun onHistoryChanged() = Unit
        override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<Uri>?) -> Unit) = Unit
        override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
        override fun requestMicrophone(done: (Boolean) -> Unit) = access.request(done)
    }

    private val chrome: ShellChromeClient by lazy {
        ShellWebView.configure(wv, OriginPolicy.forServer("https://m.example.test")!!, callbacks)
        shadowOf(wv).webChromeClient as ShellChromeClient
    }

    /** A WebView permission request; records how it was answered. */
    private class Req(private val o: String, private vararg val r: String) : PermissionRequest() {
        val answers = mutableListOf<String>()
        override fun getOrigin(): Uri = Uri.parse(o)
        override fun getResources(): Array<String> = arrayOf(*r)
        override fun grant(resources: Array<String>) { answers += "grant:" + resources.joinToString(",") }
        override fun deny() { answers += "deny" }
    }

    private val muster = "https://m.example.test/"

    /** The user's own case: no RECORD_AUDIO yet; Android asks; "Allow" grants the page audio only. */
    @Test
    fun mustersAudioIsGrantedAfterTheUserAllows() {
        val r = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(r)
        assertEquals("Android's prompt is shown once", 1, android.launches)
        assertEquals("nothing is answered while the prompt is up", emptyList<String>(), r.answers)
        android.granted = true
        access.onResult(true)
        assertEquals(listOf("grant:$RESOURCE_AUDIO_CAPTURE"), r.answers)
    }

    @Test
    fun alreadyHeldPermissionGrantsWithoutAPrompt() {
        android.granted = true
        val r = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(r)
        assertEquals(0, android.launches)
        assertEquals(listOf("grant:$RESOURCE_AUDIO_CAPTURE"), r.answers)
    }

    /** "Don't allow" in Android's prompt: the page is refused, and no settings dialog on a first refusal. */
    @Test
    fun aRefusalInThePromptDeniesThePage() {
        val r = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(r)
        android.rationale = true // Android sets this after a first "Don't allow"
        access.onResult(false)
        assertEquals(listOf("deny"), r.answers)
        assertEquals(0, android.settingsOffered)
    }

    /** Asked before, and Android offers no rationale either side: it showed nothing, so offer settings. */
    @Test
    fun aPermanentlyBlockedMicOffersTheSettingsPage() {
        android.asked = true
        val r = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(r)
        access.onResult(false)
        assertEquals(listOf("deny"), r.answers)
        assertEquals(1, android.settingsOffered)
    }

    /** Asked before but the last answer left a rationale: Android does show its prompt, so no dialog. */
    @Test
    fun aRationaleBeforeTheAskMeansThePromptWasShown() {
        android.asked = true
        android.rationale = true
        chrome.onPermissionRequest(Req(muster, RESOURCE_AUDIO_CAPTURE))
        android.rationale = false // a second "Don't allow" makes it permanent
        access.onResult(false)
        assertEquals(0, android.settingsOffered)
    }

    /** Asked before (that prompt was dismissed), and this time the user said "Don't allow": Android showed its prompt. */
    @Test
    fun aRefusalThatLeavesARationaleOffersNoSettings() {
        android.asked = true
        chrome.onPermissionRequest(Req(muster, RESOURCE_AUDIO_CAPTURE))
        android.rationale = true
        access.onResult(false)
        assertEquals(0, android.settingsOffered)
    }

    /** MicrophoneAccess on its own answers each caller once: a replaced caller gets false, the newest the result. */
    @Test
    fun eachCallerIsAnsweredOnce() {
        val got = mutableListOf<Pair<Int, Boolean>>()
        access.request { got += 1 to it }
        access.request { got += 2 to it }
        assertEquals(listOf(1 to false), got)
        access.onResult(true)
        access.onResult(true) // a stray second result answers nobody
        assertEquals(listOf(1 to false, 2 to true), got)
        assertEquals(1, android.launches)
    }

    /** First ask, dismissed without an answer: not blocked, no dialog. */
    @Test
    fun aFirstPromptDismissedOffersNoSettings() {
        chrome.onPermissionRequest(Req(muster, RESOURCE_AUDIO_CAPTURE))
        access.onResult(false)
        assertEquals(0, android.settingsOffered)
    }

    @Test
    fun noPromptMeansDenied() {
        android.launchFails = true
        val r = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(r)
        assertEquals(listOf("deny"), r.answers)
    }

    /** Video, MIDI, protected media, unknown or no resources, and audio bundled with anything: denied, nothing asked. */
    @Test
    fun otherResourcesAreDenied() {
        android.granted = true
        val cases = listOf(
            Req(muster, RESOURCE_VIDEO_CAPTURE),
            Req(muster, RESOURCE_AUDIO_CAPTURE, RESOURCE_VIDEO_CAPTURE),
            Req(muster, RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE),
            Req(muster, RESOURCE_MIDI_SYSEX),
            Req(muster, RESOURCE_PROTECTED_MEDIA_ID),
            Req(muster, RESOURCE_AUDIO_CAPTURE, RESOURCE_PROTECTED_MEDIA_ID),
            Req(muster, "android.webkit.resource.SOMETHING_NEW"),
            Req(muster),
        )
        for (r in cases) {
            chrome.onPermissionRequest(r)
            assertEquals(r.resources.toList().toString(), listOf("deny"), r.answers)
        }
        assertEquals(0, android.launches)
    }

    /** Any origin but exactly the muster one: denied, nothing asked. */
    @Test
    fun otherOriginsAreDenied() {
        android.granted = true
        for (o in listOf(
            "https://evil.example.test/",
            "http://m.example.test/",
            "https://m.example.test:8443/",
            "https://sub.m.example.test/",
            "https://m.example.test.evil.test/",
            "null",
            "",
        )) {
            val r = Req(o, RESOURCE_AUDIO_CAPTURE)
            chrome.onPermissionRequest(r)
            assertEquals(o, listOf("deny"), r.answers)
        }
        assertEquals(0, android.launches)
        // The same origin, spelt with its default port, is muster's.
        val same = Req("https://m.example.test:443", RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(same)
        assertEquals(listOf("grant:$RESOURCE_AUDIO_CAPTURE"), same.answers)
    }

    /** A second request while the prompt is up: the first is denied, the one prompt answers the second. */
    @Test
    fun aNewerRequestSupersedesAnUnansweredOne() {
        val first = Req(muster, RESOURCE_AUDIO_CAPTURE)
        val second = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(first)
        chrome.onPermissionRequest(second)
        assertEquals(listOf("deny"), first.answers)
        assertEquals(emptyList<String>(), second.answers)
        assertEquals("one prompt, not two", 1, android.launches)
        android.granted = true
        access.onResult(true)
        assertEquals(listOf("deny"), first.answers)
        assertEquals(listOf("grant:$RESOURCE_AUDIO_CAPTURE"), second.answers)
    }

    /** The page withdrew its request: a late answer reaches nothing, and the next request still works. */
    @Test
    fun aCancelledRequestIsNotAnsweredLate() {
        val gone = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(gone)
        chrome.onPermissionRequestCanceled(gone)
        android.granted = true
        access.onResult(true)
        assertEquals(emptyList<String>(), gone.answers)

        val next = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(next)
        assertEquals(listOf("grant:$RESOURCE_AUDIO_CAPTURE"), next.answers)
    }

    /** A cancel for some other request leaves the waiting one waiting. */
    @Test
    fun cancellingAnotherRequestKeepsThePendingOne() {
        val waiting = Req(muster, RESOURCE_AUDIO_CAPTURE)
        chrome.onPermissionRequest(waiting)
        chrome.onPermissionRequestCanceled(Req(muster, RESOURCE_AUDIO_CAPTURE))
        android.granted = true
        access.onResult(true)
        assertEquals(listOf("grant:$RESOURCE_AUDIO_CAPTURE"), waiting.answers)
    }

    /** Callbacks that do not wire the microphone refuse it. */
    @Test
    fun theDefaultCallbackRefuses() {
        var got: Boolean? = null
        object : ShellWebView.Callbacks {
            override fun onBridgeMessage(message: BridgeMessage) = Unit
            override fun openExternally(url: String) = Unit
            override fun onBlockedAttachment() = Unit
            override fun onRendererGone(view: WebView) = Unit
            override fun onHistoryChanged() = Unit
            override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<Uri>?) -> Unit) = Unit
            override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) = Unit
        }.requestMicrophone { got = it }
        assertTrue(got == false)
    }
}
