package dev.zacx.muster.core.net

import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskOutcome
import java.util.Base64

/** One tapped ask action: [answer] is the exact text an answer sends, null for a dismiss. */
data class AskRequest(val action: ActionId, val target: String, val answer: String?) {
    init {
        require(action == ActionId.ASK_ANSWER || action == ActionId.ASK_DISMISS) { "not an ask action" }
        require((action == ActionId.ASK_ANSWER) == (answer != null)) { "an answer carries its text; a dismiss none" }
    }

    /** The answer's hash as the challenge binds it, or null for a dismiss. */
    val textSha256: String? get() = answer?.let { DeviceActionMessage.textSha256(it) }
}

/**
 * The signed ask action, minus the prompt: [prepare] asks muster for a
 * challenge and checks it; the caller signs [Prepared.Ready.message] with the
 * step-up key behind the user's authentication; [send] posts the signature.
 * Both block (call them off the main thread).
 */
class SignedAsk(private val api: DeviceApi, private val token: String) {

    sealed interface Prepared {
        /** [message] is the exact bytes to sign, built by the app (never taken from muster's answer). */
        data class Ready(val nonce: String, val message: ByteArray) : Prepared

        data class Refused(val outcome: AskOutcome) : Prepared
    }

    fun prepare(req: AskRequest): Prepared {
        val hash = req.textSha256
        return when (val r = api.challenge(token, req.action, req.target, hash)) {
            is DeviceApi.Result.Ok -> {
                val c = r.value
                if (!NONCE.matches(c.nonce)) return Prepared.Refused(AskOutcome.Failed(BUG))
                val ours = DeviceActionMessage.build(c.nonce, req.action, req.target, hash)
                // muster rebuilds the message from its stored challenge; a
                // different one here means the two disagree on what is signed.
                if (c.message != ours) return Prepared.Refused(AskOutcome.Failed(BUG))
                Prepared.Ready(c.nonce, ours.toByteArray(Charsets.UTF_8))
            }
            else -> Prepared.Refused(outcomeOf(r))
        }
    }

    fun send(req: AskRequest, nonce: String, signatureDer: ByteArray): AskOutcome {
        val sig = Base64.getEncoder().encodeToString(signatureDer)
        return outcomeOf(api.askAction(token, req.action, req.target, nonce, sig, req.answer))
    }

    companion object {
        private val NONCE = Regex("^mdc_[A-Za-z0-9_-]{1,200}$")

        const val BUG = "Not sent: muster's confirmation did not match this answer. Open the question to answer it."
        const val RETRY = "Not sent: the confirmation expired. Tap the button again."
        const val REPAIR = "Not sent: muster did not accept this phone's key. Re-confirm this phone in Phone settings."
        const val READ_ONLY = "Not sent: this phone is paired read-only. Re-confirm this phone in Phone settings."
        const val NOT_OPEN = "Already answered, dismissed or expired."
        const val NOT_FOUND = "This question is no longer there."
        const val REVOKED = "Not sent: muster no longer accepts this phone. Pair it again in Phone settings."
        const val UNREACHABLE = "Not sent: muster could not be reached. Tap the button again."

        /**
         * muster's refusal table (docs/design/android.md, "The signed ask
         * actions, as built", 5. Refusals), as the notification says it.
         * The challenge is spent before it is judged, so every retry asks
         * for a new one; a tap always does.
         */
        fun outcomeOf(r: DeviceApi.Result<*>): AskOutcome = when (r) {
            is DeviceApi.Result.Ok -> AskOutcome.Done
            DeviceApi.Result.Revoked -> AskOutcome.Failed(REVOKED)
            is DeviceApi.Result.Failed -> when (r.code) {
                "challenge_invalid", "challenge_used", "challenge_expired" -> AskOutcome.Failed(RETRY)
                "bad_signature", "key_not_allowed" -> AskOutcome.Failed(REPAIR)
                "no_stepup_key" -> AskOutcome.Failed(READ_ONLY)
                "not_found" -> AskOutcome.Gone(NOT_FOUND)
                "not_open" -> AskOutcome.Gone(NOT_OPEN)
                "invalid_answer" -> AskOutcome.Failed("Not sent: muster refused the answer (${r.message}).")
                "bad_request" -> AskOutcome.Failed("Not sent: muster refused the request (${r.message}).")
                "failed" -> AskOutcome.Failed("Not sent: muster could not record it. Try again later.")
                else -> if (r.status == 0) AskOutcome.Failed(UNREACHABLE) else AskOutcome.Failed("Not sent: muster answered ${r.status}.")
            }
        }
    }
}
