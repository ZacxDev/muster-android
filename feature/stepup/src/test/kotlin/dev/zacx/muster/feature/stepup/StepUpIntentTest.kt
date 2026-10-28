package dev.zacx.muster.feature.stepup

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.net.AskRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 🔴 Every ask button is `PendingIntent.getActivity` STRAIGHT to the step-up
 * activity (muster docs/design/android.md, "A gated action, step by step"): a
 * receiver or service trampoline is blocked SILENTLY on targetSdk 31+ (Phase 0,
 * S3), so a button built that way would ship broken with no error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class StepUpIntentTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val answer = AskAction(ActionId.ASK_ANSWER, "41", "ship it", "ship it", AskAction.Kind.OPTION)
    private val dismiss = AskAction(ActionId.ASK_DISMISS, "41", "Dismiss", null, AskAction.Kind.DISMISS)

    @Test
    fun aButtonIsAnImmutableActivityIntentStraightToTheStepUpActivity() {
        val pi = StepUpActivity.pendingIntent(app, "attention-ask-41", 0, "chief is asking", "/attention/open/ask-41", answer)
        val s = shadowOf(pi)
        assertTrue(s.isActivityIntent)
        assertFalse(s.isBroadcastIntent)
        assertFalse(s.isServiceIntent)
        assertEquals(ComponentName(app, StepUpActivity::class.java), s.savedIntent.component)
        assertTrue(s.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)

        val tap = StepUpActivity.Tap.from(s.savedIntent)
        assertEquals(
            StepUpActivity.Tap("attention-ask-41", "chief is asking", "/attention/open/ask-41", AskRequest(ActionId.ASK_ANSWER, "41", "ship it")),
            tap,
        )
    }

    /** Each button its own PendingIntent, even across tags whose hash codes collide ("Aa", "BB"). */
    @Test
    fun noTwoButtonsShareAPendingIntent() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val a0 = StepUpActivity.pendingIntent(app, "Aa", 0, "t", null, answer)
        val a1 = StepUpActivity.pendingIntent(app, "Aa", 1, "t", null, dismiss)
        val b0 = StepUpActivity.pendingIntent(app, "BB", 0, "t", null, dismiss)
        assertNotEquals(a0, a1)
        assertNotEquals(a0, b0)
        assertEquals("ship it", StepUpActivity.Tap.from(shadowOf(a0).savedIntent)!!.request.answer)
        assertEquals(ActionId.ASK_DISMISS, StepUpActivity.Tap.from(shadowOf(a1).savedIntent)!!.request.action)
        assertEquals("BB", StepUpActivity.Tap.from(shadowOf(b0).savedIntent)!!.tag)
    }

    /** The activity is the app's own: not exported, so only these PendingIntents reach it. */
    @Test
    fun theStepUpActivityIsNotExported() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, StepUpActivity::class.java), PackageManager.GET_META_DATA)
        assertFalse(info.exported)
    }

    @Test
    fun aMalformedTapIsRefused() {
        val good = shadowOf(StepUpActivity.pendingIntent(app, "attention-ask-41", 0, "t", null, answer)).savedIntent
        assertNull(StepUpActivity.Tap.from(null))
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); removeExtra(StepUpActivity.EXTRA_ANSWER) }))
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); putExtra(StepUpActivity.EXTRA_ACTION, "queue_send_now") }))
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); removeExtra(StepUpActivity.EXTRA_TAG) }))
    }

    /** The prompt repeats the EXACT answer: what the key signs is what the user saw. */
    @Test
    fun thePromptShowsTheExactText() {
        assertEquals("Send this answer?", StepUpActivity.promptTitle(AskRequest(ActionId.ASK_ANSWER, "41", "  ship it ")))
        assertEquals("  ship it ", StepUpActivity.promptDescription(AskRequest(ActionId.ASK_ANSWER, "41", "  ship it ")))
        assertEquals("Dismiss this question?", StepUpActivity.promptTitle(AskRequest(ActionId.ASK_DISMISS, "41", null)))
    }
}
