package dev.zacx.muster.feature.stepup

import android.app.Application
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.SignedAsk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.TimeUnit

/** One outcome as the activity handed it to the host. */
data class Shown(val tenant: String, val tag: String, val outcome: AskOutcome, val ifShowing: Boolean)

/**
 * A host with no pairing for any server: reaching [stepUpPairing] is the
 * step right before `POST /api/device/challenge`, and it ends REVOKED. With
 * [hang], the lookup never returns.
 */
class UnpairedHostApp : Application(), StepUpHost {
    val asked = mutableListOf<String>()
    val outcomes = mutableListOf<Shown>()
    var hang = false

    override suspend fun stepUpPairing(tenant: String): StepUpHost.Pairing? {
        asked += tenant
        if (hang) CompletableDeferred<Unit>().await()
        return null
    }

    override fun showOutcome(tenant: String, tag: String, title: String, path: String?, outcome: AskOutcome, ifShowing: Boolean) {
        outcomes += Shown(tenant, tag, outcome, ifShowing)
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
        app.hang = false
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
        assertEquals(listOf(Shown("t2", "t2:attention-ask-41", failed(SignedAsk.REVOKED), false)), app.outcomes)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun aCancelledUnlockSaysSoAndFinishes() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t1", "attention-ask-41", answer)).setup()
        keyguard.setKeyguardLocked(true) // the shadow fires onDismissCancelled
        ShadowLooper.idleMainLooper()

        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Shown("t1", "attention-ask-41", failed(StepUpFlow.UNLOCK_CANCELLED), true)), app.outcomes)
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

        ShadowLooper.idleMainLooper(StepUpFlow.UNLOCK_BOUND_MS + 1_000, TimeUnit.MILLISECONDS)

        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Shown("t1", "attention-ask-41", failed(StepUpFlow.UNLOCK_TIMEOUT), true)), app.outcomes)
        assertEquals(emptyList<String>(), app.asked)
    }

    /**
     * 🔴 REGRESSION (D1): unlocked at the bound with no callback, but the
     * activity is stopped: it could not show a prompt, so it ends without
     * asking for a challenge.
     */
    @Test
    fun theBoundUnlockedWhileStoppedEndsWithoutAChallenge() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t1", "attention-ask-41", answer)).setup()
        c.pause().stop()
        ShadowKeyguardManager.reset() // unlocked, callback dropped
        ShadowLooper.idleMainLooper(StepUpFlow.UNLOCK_BOUND_MS + 1_000, TimeUnit.MILLISECONDS)

        assertEquals(emptyList<String>(), app.asked)
        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Shown("t1", "attention-ask-41", failed(StepUpFlow.INTERRUPTED), true)), app.outcomes)
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
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)

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
                Shown("t1", "attention-ask-41", failed(StepUpFlow.REPLACED), true),
                Shown("t2", "t2:attention-ask-42", failed(SignedAsk.REVOKED), false),
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
        assertEquals(listOf(Shown("t2", "t2:attention-ask-41", failed(SignedAsk.REVOKED), false)), app.outcomes)
    }

    /**
     * 🔴 REGRESSION: recreated (a configuration change not declared, or a
     * forced recreate) mid-unlock: the tap is taken up again, nothing is
     * posted, and the unlock still leads on to the challenge.
     */
    @Test
    fun aRecreateDuringTheUnlockKeepsTheTap() {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t2", "t2:attention-ask-41", answer)).setup()
        // The shadow keeps ONE callback and errors any second request made
        // while it is held; the platform registers each request. Drop the old
        // instance's callback (still locked) so the new instance's request is
        // taken as the platform would take it.
        ShadowKeyguardManager.reset()
        keyguard.setKeyguardLocked(true)
        c.recreate()
        ShadowLooper.idleMainLooper()

        assertFalse(c.get().isFinishing)
        assertEquals(StepUpFlow.Phase.UNLOCKING, c.get().phase)
        assertEquals(emptyList<Shown>(), app.outcomes)

        keyguard.setKeyguardLocked(false)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("t2"), app.asked)
    }

    /** 🔴 REGRESSION: a lookup (or challenge) that never returns ends at its bound instead of refusing taps forever. */
    @Test
    fun aHungChallengeEndsAtItsBound() {
        app.hang = true
        ShadowKeyguardManager.reset()
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("t1", "attention-ask-41", answer)).setup()
        ShadowLooper.idleMainLooper()
        assertEquals(StepUpFlow.Phase.PREPARING, c.get().phase)

        ShadowLooper.idleMainLooper(StepUpFlow.PREPARE_BOUND_MS + 1_000, TimeUnit.MILLISECONDS)

        assertTrue(c.get().isFinishing)
        assertEquals(listOf(Shown("t1", "attention-ask-41", failed(StepUpFlow.PREPARE_TIMEOUT), true)), app.outcomes)
    }
}
