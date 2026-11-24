package dev.zacx.muster.feature.stepup

import android.app.Application
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.SignedAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * A host with no pairing for any server: reaching [stepUpPairing] is the
 * step right before `POST /api/device/challenge`, and it ends REVOKED.
 */
class UnpairedHostApp : Application(), StepUpHost {
    val asked = mutableListOf<String>()
    val outcomes = mutableListOf<Triple<String, String, AskOutcome>>()

    override suspend fun stepUpPairing(tenant: String): StepUpHost.Pairing? {
        asked += tenant
        return null
    }

    override fun showOutcome(tenant: String, tag: String, title: String, path: String?, outcome: AskOutcome) {
        outcomes += Triple(tenant, tag, outcome)
    }
}

/**
 * A button tapped on the lock screen: the activity asks for the unlock, and
 * every way that can go ends in the challenge or a visible outcome. 🔴 None
 * leaves a live, invisible activity that refuses every later tap as
 * "Finish the open confirmation first".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = UnpairedHostApp::class)
class StepUpLockedTapTest {
    private val app: UnpairedHostApp = ApplicationProvider.getApplicationContext()
    private val keyguard get() = shadowOf(app.getSystemService(KeyguardManager::class.java))
    private val answer = AskAction(ActionId.ASK_ANSWER, "41", "ship it", "ship it", AskAction.Kind.OPTION)
    private val dismiss = AskAction(ActionId.ASK_DISMISS, "42", "Dismiss", null, AskAction.Kind.DISMISS)

    private fun tapIntent(tenant: String, tag: String, a: AskAction) =
        shadowOf(StepUpActivity.pendingIntent(app, tenant, tag, 0, "chief is asking", null, a)).savedIntent

    private fun failed(m: String): AskOutcome = AskOutcome.Failed(m)

    @Before
    fun setUp() {
        ShadowKeyguardManager.reset()
        app.asked.clear()
        app.outcomes.clear()
        keyguard.setKeyguardLocked(true)
    }

    @Test
    fun aLockedTapGoesOnToTheChallengeOnceTheDismissSucceeds() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t2", "t2:attention-ask-41", answer)).setup()
        ShadowLooper.idleMainLooper()
        assertEquals(StepUpFlow.Phase.UNLOCKING, c.get().phase)
        assertEquals(emptyList<String>(), app.asked)

        keyguard.setKeyguardLocked(false) // the shadow fires onDismissSucceeded
        ShadowLooper.idleMainLooper()

        assertEquals(listOf("t2"), app.asked) // the tap's own server, nothing else
        assertEquals(listOf(Triple("t2", "t2:attention-ask-41", failed(SignedAsk.REVOKED))), app.outcomes)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun aCancelledUnlockSaysSoAndFinishes() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t1", "attention-ask-41", answer)).setup()
        keyguard.setKeyguardLocked(true) // the shadow fires onDismissCancelled
        ShadowLooper.idleMainLooper()

        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Triple("t1", "attention-ask-41", failed(StepUpFlow.UNLOCK_CANCELLED))), app.outcomes)
        assertEquals(emptyList<String>(), app.asked)
    }

    /**
     * 🔴 REGRESSION: the dismiss callback never comes. The attempt ends at
     * its bound with an outcome, and the activity finishes.
     */
    @Test
    fun anUnlockThatNeverReportsBackEndsAtItsBound() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t1", "attention-ask-41", answer)).setup()
        ShadowLooper.idleMainLooper()
        assertFalse(c.get().isFinishing)

        ShadowLooper.idleMainLooper(StepUpFlow.UNLOCK_BOUND_MS + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Triple("t1", "attention-ask-41", failed(StepUpFlow.UNLOCK_TIMEOUT))), app.outcomes)
        assertEquals(emptyList<String>(), app.asked)
    }

    /**
     * 🔴 REGRESSION: the first tap's unlock never reported back; a later tap
     * reaches the same (singleInstance) activity. It is taken, not refused
     * as busy, and only it goes on to the challenge.
     */
    @Test
    fun aLaterTapIsTakenWhileAnUnlockNeverReportedBack() {
        val first = tapIntent("t1", "attention-ask-41", answer)
        val c = Robolectric.buildActivity(StepUpActivity::class.java, first).setup()
        ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)

        // The user unlocked some other way: the shadow's pending callback is
        // dropped, as a lost callback would be. The phone is now unlocked.
        ShadowKeyguardManager.reset()
        val second = tapIntent("t2", "t2:attention-ask-42", dismiss)
        c.newIntent(second)
        ShadowLooper.idleMainLooper()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(second, c.get().intent)
        assertEquals(listOf("t2"), app.asked)
        assertEquals(
            listOf(
                Triple("t1", "attention-ask-41", failed(StepUpFlow.REPLACED)),
                Triple("t2", "t2:attention-ask-42", failed(SignedAsk.REVOKED)),
            ),
            app.outcomes,
        )
        assertTrue(c.get().isFinishing)
    }

    /**
     * 🔴 REGRESSION: the dismiss callback was lost, but the user did unlock.
     * When the activity comes back up with the phone unlocked it goes on.
     */
    @Test
    fun aResumeUnlockedWithoutTheCallbackGoesOnToTheChallenge() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t2", "t2:attention-ask-41", answer)).setup()
        c.pause().stop() // behind the keyguard
        ShadowLooper.idleMainLooper()
        assertFalse(c.get().isFinishing)

        ShadowKeyguardManager.reset() // unlocked, callback dropped
        c.start().resume()
        ShadowLooper.idleMainLooper()

        assertEquals(listOf("t2"), app.asked)
        assertTrue(c.get().isFinishing)
        assertNotEquals(emptyList<Any>(), app.outcomes)
    }

    /** A recreated activity has lost its attempt: it says so instead of finishing silently. */
    @Test
    fun aRecreatedActivitySaysTheAttemptWasInterrupted() {
        val i = tapIntent("t1", "attention-ask-41", answer)
        val c = Robolectric.buildActivity(StepUpActivity::class.java, i).create(android.os.Bundle())
        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Triple("t1", "attention-ask-41", failed(StepUpFlow.INTERRUPTED))), app.outcomes)
    }
}
