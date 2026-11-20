package dev.zacx.muster.feature.stepup

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.net.AskRequest
import dev.zacx.muster.core.net.SignedAsk
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
        val pi = StepUpActivity.pendingIntent(app, "t1", "attention-ask-41", 0, "chief is asking", "/attention/open/ask-41", answer)
        val s = shadowOf(pi)
        assertTrue(s.isActivityIntent)
        assertFalse(s.isBroadcastIntent)
        assertFalse(s.isServiceIntent)
        assertEquals(ComponentName(app, StepUpActivity::class.java), s.savedIntent.component)
        assertTrue(s.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)

        val tap = StepUpActivity.Tap.from(s.savedIntent)
        assertEquals(
            StepUpActivity.Tap("t1", "attention-ask-41", "chief is asking", "/attention/open/ask-41", AskRequest(ActionId.ASK_ANSWER, "41", "ship it")),
            tap,
        )
    }

    /** Each button its own PendingIntent, even across tags whose hash codes collide ("Aa", "BB"). */
    @Test
    fun noTwoButtonsShareAPendingIntent() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val a0 = StepUpActivity.pendingIntent(app, "t1", "Aa", 0, "t", null, answer)
        val a1 = StepUpActivity.pendingIntent(app, "t1", "Aa", 1, "t", null, dismiss)
        val b0 = StepUpActivity.pendingIntent(app, "t1", "BB", 0, "t", null, dismiss)
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
        val good = shadowOf(StepUpActivity.pendingIntent(app, "t1", "attention-ask-41", 0, "t", null, answer)).savedIntent
        assertNull(StepUpActivity.Tap.from(null))
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); removeExtra(StepUpActivity.EXTRA_ANSWER) }))
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); putExtra(StepUpActivity.EXTRA_ACTION, "queue_send_now") }))
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); removeExtra(StepUpActivity.EXTRA_TAG) }))
        // No tenant: refused, never resolved to "the current server".
        assertNull(StepUpActivity.Tap.from(good.cloneFilter().apply { putExtras(good); removeExtra(StepUpActivity.EXTRA_TENANT) }))
    }

    /** The prompt repeats the EXACT answer: what the key signs is what the user saw. */
    @Test
    fun thePromptShowsTheExactText() {
        assertEquals("Send this answer?", StepUpActivity.promptTitle(AskRequest(ActionId.ASK_ANSWER, "41", "  ship it ")))
        assertEquals("  ship it ", StepUpActivity.promptDescription(AskRequest(ActionId.ASK_ANSWER, "41", "  ship it ")))
        assertEquals("Dismiss this question?", StepUpActivity.promptTitle(AskRequest(ActionId.ASK_DISMISS, "41", null)))
    }

    private val card = "A session on ws-1 (main:1) asks to answer one of your open asks as you: attention_answer {\"id\":9,\"answer\":\"yes\"}"
    private val ready = SignedAsk.Prepared.Ready("mdc_x", byteArrayOf(1), card)

    /**
     * 🔴 A STEP-UP CARD'S PROMPT SHOWS WHAT IT CONFIRMS (muster
     * docs/mirroring.md, "Confirming a card from its notification"): the
     * prompt built from muster's challenge names the exact answer in its
     * title and carries the card's text, as muster sent it, as its
     * description. Without the challenge's text it is the plain ask's prompt.
     */
    @Test
    fun aStepUpCardsPromptShowsWhatItConfirms() {
        val tap = StepUpActivity.Tap("t1", "attention-ask-41", "step-up is asking", null, AskRequest(ActionId.ASK_ANSWER, "41", "Confirm"))
        val info = StepUpActivity.promptInfo(tap, ready, keyTakesPin = false, cancel = "Cancel")
        assertEquals("Answer \"Confirm\" to this request?", info.title)
        assertEquals(card, info.description)
        assertEquals("step-up is asking", info.subtitle)
        assertTrue(info.isConfirmationRequired)

        val refuse = tap.copy(request = AskRequest(ActionId.ASK_ANSWER, "41", "Refuse"))
        assertEquals("Answer \"Refuse\" to this request?", StepUpActivity.promptInfo(refuse, ready, false, "Cancel").title)
        val dismiss = tap.copy(request = AskRequest(ActionId.ASK_DISMISS, "41", null))
        assertEquals("Dismiss this request?", StepUpActivity.promptInfo(dismiss, ready, false, "Cancel").title)
        assertEquals(card, StepUpActivity.promptInfo(dismiss, ready, false, "Cancel").description)

        // Control: the same tap with no card text from muster is the plain ask's prompt.
        val plain = StepUpActivity.promptInfo(tap, ready.copy(confirms = null), false, "Cancel")
        assertEquals("Send this answer?", plain.title)
        assertEquals("Confirm", plain.description)
    }

    /** A long card is cut to 1,000 characters with a visible marker, never inside a surrogate pair. */
    @Test
    fun aLongCardIsCutVisibly() {
        val r = AskRequest(ActionId.ASK_ANSWER, "41", "Confirm")
        val exact = "a".repeat(1000)
        assertEquals(exact, StepUpActivity.promptDescription(r, exact))
        val long = "b".repeat(999) + "👍" + "c".repeat(50)
        val cut = StepUpActivity.promptDescription(r, long)
        assertEquals("b".repeat(999) + "👍" + StepUpActivity.CARD_CUT, cut)
        assertTrue(StepUpActivity.CARD_CUT.startsWith("…"))
    }
}
