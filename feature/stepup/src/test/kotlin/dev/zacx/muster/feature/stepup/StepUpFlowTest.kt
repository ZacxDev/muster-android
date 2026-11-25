package dev.zacx.muster.feature.stepup

import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.feature.stepup.StepUpFlow.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step-up state machine on its own (JVM, no Android). Taps are strings,
 * a challenge is a string; [FakeIo] records every effect in order.
 */
class StepUpFlowTest {
    private class FakeIo : StepUpFlow.Io<String, String> {
        var locked = false
        var started = true
        var canPrompt = true
        /** Run inside requestUnlock (a dismiss that reports back at once). */
        var onUnlock: ((Long) -> Unit)? = null
        /** Per outcome: whether it may only update a showing notification. */
        val ifShowing = mutableListOf<Boolean>()
        val calls = mutableListOf<String>()
        val outcomes = mutableListOf<Pair<String, AskOutcome>>()
        var finished = 0
        var busy = 0
        var armed: Pair<Long, Long>? = null

        override fun isLocked() = locked
        override fun isStarted() = started
        override fun requestUnlock(attempt: Long) {
            calls += "unlock:$attempt"
            onUnlock?.invoke(attempt)
        }
        override fun prepare(attempt: Long, tap: String) { calls += "prepare:$attempt:$tap" }
        override fun canPrompt() = canPrompt
        override fun prompt(attempt: Long, tap: String, ready: String) { calls += "prompt:$attempt:$tap:$ready" }
        override fun send(attempt: Long, tap: String, ready: String) { calls += "send:$attempt:$tap" }
        override fun cancelWork() { calls += "cancel" }
        override fun arm(attempt: Long, ms: Long) { armed = attempt to ms }
        override fun disarm() { armed = null }
        override fun showOutcome(tap: String, outcome: AskOutcome, ifShowing: Boolean) {
            outcomes += tap to outcome
            this.ifShowing += ifShowing
        }
        override fun busy() { busy++ }
        override fun finish() { finished++ }
        override fun log(line: String) = Unit

        fun sends() = calls.filter { it.startsWith("send:") }
        fun prepares() = calls.filter { it.startsWith("prepare:") }
    }

    private val io = FakeIo()
    private val flow = StepUpFlow(io)

    private fun failed(m: String): AskOutcome = AskOutcome.Failed(m)

    @Test
    fun anUnlockedTapGoesStraightToTheChallenge() {
        assertTrue(flow.tap("a"))
        assertEquals(Phase.PREPARING, flow.phase)
        assertEquals(listOf("prepare:1:a"), io.calls)
        assertEquals(1L to StepUpFlow.PREPARE_BOUND_MS, io.armed)
    }

    @Test
    fun aLockedTapWaitsForTheUnlockThenRequestsTheChallenge() {
        io.locked = true
        flow.tap("a")
        assertEquals(Phase.UNLOCKING, flow.phase)
        assertEquals(listOf("unlock:1"), io.calls)
        assertEquals(1L to StepUpFlow.UNLOCK_BOUND_MS, io.armed)

        io.locked = false
        flow.unlockSucceeded(1)
        assertEquals(Phase.PREPARING, flow.phase)
        assertEquals(listOf("unlock:1", "prepare:1:a"), io.calls)
        assertEquals(1L to StepUpFlow.PREPARE_BOUND_MS, io.armed) // the challenge's own bound
    }

    /** The unlock bound starts once the unlock is requested, and is not left armed when the request ended the attempt. */
    @Test
    fun theUnlockBoundStartsFromTheRequest() {
        io.locked = true
        io.onUnlock = { a -> assertEquals(null, io.armed); flow.unlockCancelled(a) }
        flow.tap("a")
        assertEquals(Phase.DONE, flow.phase)
        assertEquals(null, io.armed)
    }

    @Test
    fun aCancelledUnlockEndsWithItsOutcomeAndALaterTapIsTaken() {
        io.locked = true
        flow.tap("a")
        flow.unlockCancelled(1)
        assertEquals(listOf("a" to failed(StepUpFlow.UNLOCK_CANCELLED)), io.outcomes)
        assertEquals(listOf(true), io.ifShowing) // never re-posts a notification that is gone
        assertEquals(1, io.finished)
        assertEquals(Phase.DONE, flow.phase)

        io.locked = false
        assertTrue(flow.tap("b"))
        assertEquals(0, io.busy)
        assertEquals("prepare:2:b", io.prepares().last())
    }

    @Test
    fun anUnlockErrorWhileStillLockedEndsWithItsOutcome() {
        io.locked = true
        flow.tap("a")
        flow.unlockError(1)
        assertEquals(listOf("a" to failed(StepUpFlow.UNLOCK_ERROR)), io.outcomes)
        assertEquals(1, io.finished)
        assertTrue(flow.tap("b"))
        assertEquals(0, io.busy)
    }

    @Test
    fun anUnlockErrorOnceThePhoneIsUnlockedGoesOn() {
        io.locked = true
        flow.tap("a")
        io.locked = false
        flow.unlockError(1)
        assertEquals(Phase.PREPARING, flow.phase)
        assertEquals(listOf("prepare:1:a"), io.prepares())
        assertEquals(0, io.finished)
    }

    /** 🔴 The zombie: no dismiss callback ever comes and the phone stays locked. */
    @Test
    fun anUnlockThatNeverReportsBackEndsAtItsBound() {
        io.locked = true
        flow.tap("a")
        val (attempt, _) = io.armed!!
        flow.bound(attempt)
        assertEquals(listOf("a" to failed(StepUpFlow.UNLOCK_TIMEOUT)), io.outcomes)
        assertEquals(1, io.finished)
        assertEquals(Phase.DONE, flow.phase)
        assertTrue(flow.tap("b"))
        assertEquals(0, io.busy)
    }

    /** The callback was dropped but the user did unlock: the next resume goes on to the challenge. */
    @Test
    fun aResumeUnlockedWithoutACallbackGoesOn() {
        io.locked = true
        flow.tap("a")
        flow.resumed() // still locked: nothing
        assertEquals(Phase.UNLOCKING, flow.phase)
        io.locked = false
        flow.resumed()
        assertEquals(Phase.PREPARING, flow.phase)
        assertEquals(listOf("prepare:1:a"), io.prepares())
        // The late callback, if it ever comes, changes nothing.
        flow.unlockSucceeded(1)
        assertEquals(listOf("prepare:1:a"), io.prepares())
    }

    @Test
    fun theBoundElapsingUnlockedWithoutACallbackGoesOn() {
        io.locked = true
        flow.tap("a")
        io.locked = false
        flow.bound(1)
        assertEquals(Phase.PREPARING, flow.phase)
        assertEquals(0, io.finished)
    }

    /** 🔴 D1: unlocked at the bound, but the activity is not up: it ends, and no challenge is asked for. */
    @Test
    fun theBoundElapsingUnlockedWhileStoppedEnds() {
        io.locked = true
        flow.tap("a")
        io.locked = false
        io.started = false
        flow.bound(1)
        assertEquals(emptyList<String>(), io.prepares())
        assertEquals(listOf("a" to failed(StepUpFlow.INTERRUPTED)), io.outcomes)
        assertEquals(1, io.finished)
        assertTrue(flow.tap("b"))
        assertEquals(0, io.busy)
    }

    /** 🔴 A challenge (or lookup) that never answers ends at its own bound: no endless busy refusals. */
    @Test
    fun aChallengeThatNeverAnswersEndsAtItsBound() {
        flow.tap("a")
        flow.bound(1)
        assertEquals(listOf("a" to failed(StepUpFlow.PREPARE_TIMEOUT)), io.outcomes)
        assertEquals(1, io.finished)
        assertTrue(flow.tap("b"))
        assertEquals(0, io.busy)
    }

    /** A tap while an unlock is pending (nothing is in flight) takes over; the old one is told. */
    @Test
    fun aTapWhileUnlockingTakesOverAndTheOldCallbackIsIgnored() {
        io.locked = true
        flow.tap("a")
        assertTrue(flow.tap("b"))
        assertEquals(0, io.busy)
        assertEquals(2L, flow.attempt)
        assertEquals(listOf("a" to failed(StepUpFlow.REPLACED)), io.outcomes)
        assertEquals(0, io.finished)

        io.locked = false
        flow.unlockSucceeded(1) // the replaced attempt's callback
        assertEquals(Phase.UNLOCKING, flow.phase)
        assertEquals(emptyList<String>(), io.prepares())
        flow.unlockSucceeded(2)
        assertEquals(listOf("prepare:2:b"), io.prepares())
    }

    @Test
    fun aTapWhileTheChallengeIsInFlightIsRefused() {
        flow.tap("a")
        assertFalse(flow.tap("b"))
        assertEquals(1, io.busy)
        assertEquals(Phase.PREPARING, flow.phase)
        assertEquals(listOf("prepare:1:a"), io.prepares())
        assertEquals(emptyList<Pair<String, AskOutcome>>(), io.outcomes)
    }

    @Test
    fun aTapWhileThePromptIsOpenIsRefusedAndSendsNothing() {
        flow.tap("a")
        flow.prepared(1, "c1")
        assertEquals(Phase.PROMPTING, flow.phase)
        assertFalse(flow.tap("b"))
        assertEquals(1, io.busy)
        assertEquals(emptyList<String>(), io.sends())
    }

    @Test
    fun aTapWhileSendingIsRefused() {
        flow.tap("a")
        flow.prepared(1, "c1")
        flow.confirmed(1)
        assertEquals(Phase.SENDING, flow.phase)
        assertFalse(flow.tap("b"))
        assertEquals(1, io.busy)
        assertEquals(listOf("send:1:a"), io.sends())
    }

    /** 🔴 Fail closed: only a confirmed prompt of the CURRENT attempt sends. */
    @Test
    fun nothingIsSentWithoutItsOwnConfirmation() {
        flow.confirmed(1) // nothing open
        flow.tap("a")
        flow.confirmed(1) // challenge not back yet, no prompt
        flow.prepared(1, "c1")
        flow.confirmed(2) // another attempt's
        assertEquals(emptyList<String>(), io.sends())
        flow.confirmed(1)
        assertEquals(listOf("send:1:a"), io.sends())
    }

    /** 🔴 A challenge that is back but whose prompt has not shown yet ([Phase.READY]) cannot be confirmed. */
    @Test
    fun aChallengeWaitingForItsPromptCannotBeConfirmed() {
        io.canPrompt = false
        flow.tap("a")
        flow.prepared(1, "c1")
        assertEquals(Phase.READY, flow.phase)
        flow.confirmed(1)
        assertEquals(emptyList<String>(), io.sends())
        assertEquals(Phase.READY, flow.phase)
    }

    @Test
    fun theChallengeWaitsForTheActivityToBeAbleToPrompt() {
        io.canPrompt = false
        flow.tap("a")
        flow.prepared(1, "c1")
        assertEquals(Phase.READY, flow.phase)
        assertEquals(1L to StepUpFlow.PROMPT_BOUND_MS, io.armed)
        io.canPrompt = true
        flow.resumed()
        assertEquals(Phase.PROMPTING, flow.phase)
        assertEquals("prompt:1:a:c1", io.calls.last())
    }

    @Test
    fun aChallengeThatNeverGetsToPromptEndsAtItsBound() {
        io.canPrompt = false
        flow.tap("a")
        flow.prepared(1, "c1")
        flow.bound(1)
        assertEquals(listOf("a" to failed(StepUpFlow.INTERRUPTED)), io.outcomes)
        assertEquals(1, io.finished)
    }

    @Test
    fun stoppingBeforeThePromptEndsTheAttempt() {
        flow.tap("a")
        flow.stopped()
        assertEquals(listOf("a" to failed(StepUpFlow.INTERRUPTED)), io.outcomes)
        assertEquals(1, io.finished)
        assertTrue(io.calls.contains("cancel"))
        flow.prepared(1, "c1") // the challenge coming back late
        assertEquals(emptyList<String>(), io.calls.filter { it.startsWith("prompt:") })
    }

    @Test
    fun stoppingWhileUnlockingOrPromptingIsExpected() {
        io.locked = true
        flow.tap("a")
        flow.stopped() // behind the keyguard
        assertEquals(Phase.UNLOCKING, flow.phase)
        io.locked = false
        flow.unlockSucceeded(1)
        flow.prepared(1, "c1")
        flow.stopped() // a PIN key's credential screen
        assertEquals(Phase.PROMPTING, flow.phase)
        assertEquals(0, io.finished)
    }

    /** 🔴 Operator decision: a prompt the user cancels leaves the notification exactly as it was. */
    @Test
    fun aUserCancelLeavesTheNotificationAlone() {
        flow.tap("a")
        flow.prepared(1, "c1")
        flow.userCancelled(1)
        assertEquals(emptyList<Pair<String, AskOutcome>>(), io.outcomes)
        assertEquals(1, io.finished)
        assertEquals(emptyList<String>(), io.sends())
        assertTrue(flow.tap("b"))
    }

    @Test
    fun aPromptFailureOnlyUpdatesAShowingNotification() {
        flow.tap("a")
        flow.prepared(1, "c1")
        flow.failed(1, "prompt-interrupted", StepUpFlow.INTERRUPTED)
        assertEquals(listOf("a" to failed(StepUpFlow.INTERRUPTED)), io.outcomes)
        assertEquals(listOf(true), io.ifShowing)
        assertEquals(1, io.finished)
    }

    @Test
    fun aPrepareFailureEndsWithItsOutcome() {
        flow.tap("a")
        flow.ended(1, "no-pairing", failed("why"))
        assertEquals(listOf("a" to failed("why")), io.outcomes)
        assertEquals(listOf(false), io.ifShowing) // muster's answers and the pairing state are shown as before
        assertEquals(1, io.finished)
        flow.ended(1, "late", failed("again")) // stale
        assertEquals(1, io.outcomes.size)
    }

    @Test
    fun destroyedMidAttemptSaysSo() {
        flow.tap("a")
        flow.destroyed(changing = false)
        assertEquals(listOf("a" to failed(StepUpFlow.INTERRUPTED)), io.outcomes)
        val sending = StepUpFlow(io)
        sending.tap("b")
        sending.prepared(1, "c")
        sending.confirmed(1)
        sending.destroyed(changing = false)
        assertEquals("b" to failed(StepUpFlow.SEND_UNKNOWN), io.outcomes.last())
        assertEquals(listOf(true, true), io.ifShowing)
    }

    /** A configuration change hands the tap to the new instance: the old one says nothing, so the two never contradict. */
    @Test
    fun destroyedForAConfigurationChangeSaysNothing() {
        io.locked = true
        flow.tap("a")
        flow.destroyed(changing = true)
        val sending = StepUpFlow(io)
        sending.tap("b")
        io.locked = false
        sending.unlockSucceeded(1)
        sending.prepared(1, "c")
        sending.confirmed(1)
        sending.destroyed(changing = true)
        assertEquals(emptyList<Pair<String, AskOutcome>>(), io.outcomes)
        assertEquals(0, io.finished)
    }

    /**
     * INVARIANT GUARD, not regression coverage: the flow's own texts are
     * distinct (two exits never read alike) and one is pinned whole.
     */
    @Test
    fun outcomeTexts() {
        val all = listOf(
            StepUpFlow.UNLOCK_CANCELLED, StepUpFlow.UNLOCK_ERROR, StepUpFlow.UNLOCK_TIMEOUT, StepUpFlow.PREPARE_TIMEOUT,
            StepUpFlow.INTERRUPTED, StepUpFlow.REPLACED, StepUpFlow.PROMPT_NOT_SHOWN, StepUpFlow.SEND_UNKNOWN,
        )
        assertEquals(all.size, all.toSet().size)
        assertEquals(
            "Not sent: the phone was not unlocked in time. Unlock it, then tap the button again.",
            StepUpFlow.UNLOCK_TIMEOUT,
        )
    }
}
