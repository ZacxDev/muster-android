package dev.zacx.muster.feature.stepup

import dev.zacx.muster.core.model.AskOutcome

/**
 * The step-up activity's state machine, without Android: which phase one
 * button tap is in, what a callback or lifecycle event does to it, and when
 * the activity finishes. [StepUpActivity] is a thin adapter: it turns
 * keyguard, coroutine, BiometricPrompt and lifecycle callbacks into calls
 * here, and performs what [Io] asks for.
 *
 * The rules it enforces:
 * - Every attempt ENDS: it reaches a prompt, or ends (with its reason in the
 *   notification, or none when the user cancelled) and [Io.finish]. Each
 *   wait is bounded: the unlock ([UNLOCK_BOUND_MS], from the moment it is
 *   requested), the challenge ([PREPARE_BOUND_MS]) and the wait for a prompt
 *   to be showable ([PROMPT_BOUND_MS]). A phone found unlocked on resume goes
 *   on without the dismiss callback. Nothing leaves an invisible activity
 *   waiting for nothing.
 * - A new tap is refused only while an attempt is genuinely busy: its
 *   challenge in flight ([Phase.PREPARING]), its prompt open
 *   ([Phase.PROMPTING]), or its answer being sent ([Phase.SENDING]). In any
 *   other phase the new tap takes over, and the one it replaces is told so.
 * - The flow's OWN reasons (an unlock that failed, an interruption, a
 *   replaced tap, an unknown send) only update a notification that is still
 *   showing ([Io.showOutcome] `ifShowing`); they never post a new one. What
 *   muster answered, and the pairing or key state, is shown as before.
 * - Fails closed: a tap is only ever sent after its own prompt succeeded
 *   ([confirmed] from [Phase.PROMPTING]); a callback for an older attempt is
 *   ignored.
 */
internal class StepUpFlow<T, P>(private val io: Io<T, P>) {
    /** What the activity does for the flow. Every call is on the main thread. */
    interface Io<T, P> {
        fun isLocked(): Boolean

        /** Whether the activity is started (visible to the user, so a prompt can come up). */
        fun isStarted(): Boolean

        /** `requestDismissKeyguard`, its callbacks reported with [attempt]. */
        fun requestUnlock(attempt: Long)

        /** The pairing lookup, the key and `POST /api/device/challenge` for [tap]; reported with [attempt]. */
        fun prepare(attempt: Long, tap: T)

        /** Whether a prompt can be shown now (the activity resumed, its state not saved). */
        fun canPrompt(): Boolean

        /** Shows the prompt for [tap] over [ready]; its result reported with [attempt]. */
        fun prompt(attempt: Long, tap: T, ready: P)

        /** Sends [tap], signed, after its prompt succeeded; reported with [attempt]. */
        fun send(attempt: Long, tap: T, ready: P)

        /** Stops whatever [prepare] or [send] has in flight. */
        fun cancelWork()

        /** Calls [bound] with [attempt] after [ms], replacing any earlier one. */
        fun arm(attempt: Long, ms: Long)
        fun disarm()

        /** [outcome] into [tap]'s notification; with [ifShowing], only when that notification is still showing. */
        fun showOutcome(tap: T, outcome: AskOutcome, ifShowing: Boolean)

        /** The "finish the open confirmation first" toast. */
        fun busy()
        fun finish()

        /** One Logcat line. Never a token, nonce, signature or answer text. */
        fun log(line: String)
    }

    enum class Phase {
        IDLE,

        /** The keyguard dismiss was requested; waiting for its callback or an unlocked resume. */
        UNLOCKING,

        /** The pairing lookup and the challenge are in flight. */
        PREPARING,

        /** The challenge came back, but the activity cannot show a prompt yet. */
        READY,
        PROMPTING,
        SENDING,
        DONE,
    }

    var phase = Phase.IDLE
        private set
    var attempt = 0L
        private set
    private var tap: T? = null
    private var ready: P? = null

    /** A new tap (onCreate, or onNewIntent); false when it was refused as busy. */
    fun tap(t: T): Boolean {
        when (phase) {
            Phase.PREPARING, Phase.PROMPTING, Phase.SENDING -> {
                io.log("tap refused: attempt $attempt is busy ($phase)")
                io.busy()
                return false
            }
            Phase.UNLOCKING, Phase.READY -> {
                io.log("attempt $attempt replaced by a new tap ($phase)")
                tap?.let { io.showOutcome(it, AskOutcome.Failed(REPLACED), ifShowing = true) }
                io.cancelWork()
                io.disarm()
            }
            Phase.IDLE, Phase.DONE -> Unit
        }
        attempt += 1
        tap = t
        ready = null
        if (io.isLocked()) {
            val a = attempt
            move(Phase.UNLOCKING)
            io.requestUnlock(a)
            // The bound runs from the request (the unlock UI comes up now),
            // unless the request already ended the attempt.
            if (a == attempt && phase == Phase.UNLOCKING) io.arm(a, UNLOCK_BOUND_MS)
        } else {
            beginPrepare()
        }
        return true
    }

    fun unlockSucceeded(a: Long) {
        if (!current(a, Phase.UNLOCKING, "unlock succeeded")) return
        io.disarm()
        beginPrepare()
    }

    fun unlockCancelled(a: Long) {
        if (!current(a, Phase.UNLOCKING, "unlock cancelled")) return
        local("unlock-cancelled", UNLOCK_CANCELLED)
    }

    fun unlockError(a: Long) {
        if (!current(a, Phase.UNLOCKING, "unlock error")) return
        // The dismiss can error when the keyguard is already going away; the
        // phone may be unlocked by now.
        if (!io.isLocked() && io.isStarted()) {
            io.log("unlock error, but the phone is unlocked: going on")
            io.disarm()
            beginPrepare()
            return
        }
        local("unlock-error", UNLOCK_ERROR)
    }

    /** The activity resumed: an unlock whose callback never came still goes on, and a waiting prompt shows. */
    fun resumed() {
        when (phase) {
            Phase.UNLOCKING -> if (!io.isLocked()) {
                io.log("resumed unlocked without a dismiss callback: going on")
                io.disarm()
                beginPrepare()
            }
            Phase.READY -> showPrompt()
            else -> Unit
        }
    }

    /**
     * The activity stopped. Behind the keyguard (unlocking) and over a PIN
     * screen (prompting) that is expected; between the unlock and the prompt
     * it means the prompt could not show, so the attempt ends.
     */
    fun stopped() {
        if (phase == Phase.PREPARING || phase == Phase.READY) local("stopped-before-prompt", INTERRUPTED)
    }

    /** The bound armed by [Io.arm] elapsed. */
    fun bound(a: Long) {
        if (a != attempt) return
        when (phase) {
            Phase.UNLOCKING -> when {
                io.isLocked() -> local("unlock-timed-out", UNLOCK_TIMEOUT)
                // Unlocked, no callback, and the activity is not up: it could
                // never show a prompt, so no challenge is asked for.
                !io.isStarted() -> local("unlock-bound-not-started", INTERRUPTED)
                else -> {
                    io.log("unlock bound elapsed unlocked without a dismiss callback: going on")
                    beginPrepare()
                }
            }
            Phase.PREPARING -> local("challenge-timed-out", PREPARE_TIMEOUT)
            Phase.READY -> local("prompt-never-shown", INTERRUPTED)
            else -> Unit
        }
    }

    /** [prepare] got a challenge. */
    fun prepared(a: Long, r: P) {
        if (!current(a, Phase.PREPARING, "prepared")) return
        ready = r
        showPrompt()
    }

    /**
     * [prepare] or [send] ended with [outcome]: muster's answer, or the
     * pairing or key state. Shown as the notifier always showed it.
     */
    fun ended(a: Long, why: String, outcome: AskOutcome) {
        if (!live(a, why)) return
        finishWith(why, outcome, ifShowing = false)
    }

    /** The attempt failed on the phone for a reason of the flow's own ([message]): it only updates a showing notification. */
    fun failed(a: Long, why: String, message: String) {
        if (!live(a, why)) return
        local(why, message)
    }

    /** The user cancelled the prompt: nothing is sent and the notification is left exactly as it was. */
    fun userCancelled(a: Long) {
        if (!current(a, Phase.PROMPTING, "user cancelled")) return
        io.log("attempt $attempt ended in $phase: user-cancelled (notification left as it was)")
        end()
        io.finish()
    }

    /** The prompt succeeded: the only way to [Phase.SENDING]. */
    fun confirmed(a: Long) {
        if (!current(a, Phase.PROMPTING, "confirmed")) return
        val t = tap ?: return
        val r = ready ?: return
        move(Phase.SENDING)
        io.send(a, t, r)
    }

    /**
     * The activity is being destroyed with the attempt unfinished. For a
     * configuration change ([changing]) the new instance takes the tap over,
     * so nothing is shown here and the two can never post contradicting
     * outcomes.
     */
    fun destroyed(changing: Boolean) {
        if (phase == Phase.IDLE || phase == Phase.DONE) return
        val t = tap
        when {
            changing -> io.log("destroyed for a configuration change: attempt $attempt ($phase) handed over")
            phase == Phase.SENDING -> {
                io.log("destroyed while sending: attempt $attempt")
                t?.let { io.showOutcome(it, AskOutcome.Failed(SEND_UNKNOWN), ifShowing = true) }
            }
            else -> {
                io.log("destroyed before sending: attempt $attempt ($phase)")
                t?.let { io.showOutcome(it, AskOutcome.Failed(INTERRUPTED), ifShowing = true) }
            }
        }
        end()
    }

    private fun beginPrepare() {
        val t = tap ?: return
        move(Phase.PREPARING)
        io.arm(attempt, PREPARE_BOUND_MS)
        io.prepare(attempt, t)
    }

    private fun showPrompt() {
        val t = tap ?: return
        val r = ready ?: return
        if (!io.canPrompt()) {
            if (phase != Phase.READY) {
                move(Phase.READY)
                io.arm(attempt, PROMPT_BOUND_MS)
            }
            return
        }
        io.disarm()
        move(Phase.PROMPTING)
        io.prompt(attempt, t, r)
    }

    private fun local(why: String, message: String) = finishWith(why, AskOutcome.Failed(message), ifShowing = true)

    private fun finishWith(why: String, outcome: AskOutcome, ifShowing: Boolean) {
        io.log("attempt $attempt ended in $phase: $why -> ${outcome::class.simpleName}")
        val t = tap
        end()
        if (t != null) io.showOutcome(t, outcome, ifShowing)
        io.finish()
    }

    /** Ends the attempt without finishing: a later tap starts a new one. */
    private fun end() {
        io.cancelWork()
        io.disarm()
        phase = Phase.DONE
        tap = null
        ready = null
    }

    private fun live(a: Long, why: String): Boolean {
        if (a == attempt && phase != Phase.IDLE && phase != Phase.DONE) return true
        io.log("stale result ignored: attempt $a ($why)")
        return false
    }

    private fun current(a: Long, expected: Phase, what: String): Boolean {
        if (a == attempt && phase == expected) return true
        io.log("$what ignored: attempt $a in ${if (a == attempt) phase else "an older attempt"}")
        return false
    }

    private fun move(to: Phase) {
        io.log("attempt $attempt: $phase -> $to")
        phase = to
    }

    companion object {
        /** How long a requested unlock may take, from the request, before the attempt ends (the dismiss callback is not guaranteed). */
        const val UNLOCK_BOUND_MS = 60_000L

        /**
         * How long the pairing lookup and the challenge may take. Longer than
         * the challenge request's own connect plus read timeouts, so a slow
         * server still ends with "could not be reached".
         */
        const val PREPARE_BOUND_MS = 50_000L

        /** How long a challenge may wait for the activity to be able to show its prompt. */
        const val PROMPT_BOUND_MS = 15_000L

        const val UNLOCK_CANCELLED = "Not sent: the phone was not unlocked. Unlock it, then tap the button again."
        const val UNLOCK_ERROR = "Not sent: the phone could not ask to be unlocked. Unlock it, then tap the button again."
        const val UNLOCK_TIMEOUT = "Not sent: the phone was not unlocked in time. Unlock it, then tap the button again."
        const val PREPARE_TIMEOUT = "Not sent: muster took too long to answer. Tap the button again."
        const val INTERRUPTED = "Not sent: the confirmation was interrupted. Tap the button again."
        const val REPLACED = "Not sent: another button was tapped before this one was confirmed. Tap this one again to answer it."
        const val PROMPT_NOT_SHOWN = "Not sent: the confirmation could not be shown. Tap the button again."
        const val SEND_UNKNOWN = "The answer may not have been sent. Open the question to check."
    }
}
