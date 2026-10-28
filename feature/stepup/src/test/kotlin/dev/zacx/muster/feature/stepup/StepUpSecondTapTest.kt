package dev.zacx.muster.feature.stepup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/** A host whose pairing lookup never returns: the first attempt stays open. */
class PendingHostApp : Application(), StepUpHost {
    val outcomes = mutableListOf<Pair<String, AskOutcome>>()
    private val never = CompletableDeferred<StepUpHost.Pairing?>()
    override suspend fun stepUpPairing(): StepUpHost.Pairing? = never.await()
    override fun showOutcome(tag: String, title: String, path: String?, outcome: AskOutcome) {
        outcomes += tag to outcome
    }
}

/**
 * A second button tapped while the first one's confirmation is still open
 * (singleInstance routes it to onNewIntent): refused with a toast, never
 * sent, and the open attempt is kept as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = PendingHostApp::class)
class StepUpSecondTapTest {
    private val app: PendingHostApp = ApplicationProvider.getApplicationContext()

    private fun tapIntent(tag: String, a: AskAction) =
        shadowOf(StepUpActivity.pendingIntent(app, tag, 0, "chief is asking", null, a)).savedIntent

    @Test
    fun aSecondTapWhileOneIsOpenIsRefusedAndSendsNothing() {
        val first = tapIntent("attention-ask-41", AskAction(ActionId.ASK_ANSWER, "41", "ship it", "ship it", AskAction.Kind.OPTION))
        val c = Robolectric.buildActivity(StepUpActivity::class.java, first).setup()
        ShadowLooper.idleMainLooper()
        assertFalse(c.get().isFinishing)
        assertNull(ShadowToast.getTextOfLatestToast())

        val second = tapIntent("attention-ask-42", AskAction(ActionId.ASK_DISMISS, "42", "Dismiss", null, AskAction.Kind.DISMISS))
        c.newIntent(second)
        ShadowLooper.idleMainLooper()

        assertEquals("Finish the open confirmation first", ShadowToast.getTextOfLatestToast())
        assertEquals(first, c.get().intent) // the open attempt is still the first tap
        assertFalse(c.get().isFinishing)
        assertEquals(emptyList<Pair<String, AskOutcome>>(), app.outcomes)
    }
}
