package dev.zacx.muster.feature.stepup

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.AskRequest
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.core.net.SignedAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

/** What the step-up activity needs from the app: the pairing, and where an outcome is shown. */
interface StepUpHost {
    /** The pairing's credential and step-up key. */
    data class Pairing(
        val origin: String,
        val token: String,
        /** The step-up key's Keystore alias; null: the phone paired read-only. */
        val keyAlias: String?,
        /** The key also takes the device PIN (a biometric-or-PIN key). */
        val keyTakesPin: Boolean,
    )

    /**
     * 🔴 Tenant [tenant]'s pairing (muster docs/design/multi-tenant.md: "a
     * button carries the tenant, and the step-up activity uses only that
     * tenant's pairing"), never the tenant in view. null when that tenant is
     * gone or not paired.
     */
    suspend fun stepUpPairing(tenant: String): Pairing?

    /**
     * Shows [outcome] in tenant [tenant]'s notification under [tag]. With
     * [ifShowing], only when that notification is still showing: a reason of
     * the step-up flow's own never posts a notification the user already
     * let go.
     */
    fun showOutcome(tenant: String, tag: String, title: String, path: String?, outcome: AskOutcome, ifShowing: Boolean)
}

/**
 * What the step-up activity does that a JVM test cannot: load the Keystore
 * key, call muster, and show the system prompt. [Real] in the app; a test
 * swaps [StepUpActivity.seams].
 */
internal interface StepUpSeams {
    /** A Signature initialised for signing with key [alias], or null (no key, or a new enrolment destroyed it). */
    fun signer(alias: String): Signature?

    /** `POST /api/device/challenge` for [r], off the main thread. */
    suspend fun challenge(p: StepUpHost.Pairing, r: AskRequest): SignedAsk.Prepared

    /** The signed ask action, off the main thread. */
    suspend fun send(p: StepUpHost.Pairing, r: AskRequest, nonce: String, der: ByteArray): AskOutcome

    /** BiometricPrompt over `CryptoObject(signature)`; may throw when it cannot be shown. */
    fun authenticate(activity: FragmentActivity, info: BiometricPrompt.PromptInfo, signature: Signature, result: PromptResult)

    interface PromptResult {
        /** The prompt succeeded; [signature] is the CryptoObject's, now authenticated. */
        fun succeeded(signature: Signature?)
        fun error(code: Int, message: CharSequence)
    }

    object Real : StepUpSeams {
        override fun signer(alias: String): Signature? = try {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? PrivateKey
            key?.let { Signature.getInstance("SHA256withECDSA").apply { initSign(it) } }
        } catch (e: KeyPermanentlyInvalidatedException) {
            null // a new enrolment destroyed the key: "Re-confirm this phone"
        } catch (e: Exception) {
            null
        }

        override suspend fun challenge(p: StepUpHost.Pairing, r: AskRequest) =
            withContext(Dispatchers.IO) { SignedAsk(DeviceApi(p.origin), p.token).prepare(r) }

        override suspend fun send(p: StepUpHost.Pairing, r: AskRequest, nonce: String, der: ByteArray) =
            withContext(Dispatchers.IO) { SignedAsk(DeviceApi(p.origin), p.token).send(r, nonce, der) }

        override fun authenticate(activity: FragmentActivity, info: BiometricPrompt.PromptInfo, signature: Signature, result: PromptResult) {
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) =
                    result.succeeded(r.cryptoObject?.signature)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = result.error(errorCode, errString)
            }
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                .authenticate(info, BiometricPrompt.CryptoObject(signature))
        }
    }
}

/**
 * The invisible step-up activity (muster docs/design/android.md, "A gated
 * action, step by step"; validated by Phase 0 spikes S3 and S4). A button on
 * an ask's notification starts it straight from `PendingIntent.getActivity`
 * (a receiver trampoline is blocked silently on targetSdk 31+, Phase 0 S3):
 *
 * 1. Locked: `requestDismissKeyguard()` first, and go on only in
 *    `onDismissSucceeded` (a prompt behind the keyguard is cancelled). That
 *    callback is not guaranteed, so the activity also goes on when it
 *    resumes with the phone unlocked, and the wait is bounded
 *    ([StepUpFlow.UNLOCK_BOUND_MS]). A cancelled, failed or timed-out unlock
 *    says so in the notification and finishes.
 * 2. `POST /api/device/challenge`, bound to the action, the ask and the
 *    sha256 of the exact answer ([SignedAsk.prepare]).
 * 3. BiometricPrompt over a `CryptoObject(Signature)` from the step-up key,
 *    its description the exact answer — for a step-up confirmation card,
 *    the card's own text muster sent with the challenge (`confirms`), so
 *    the user sees what a Confirm or Refuse decides: the key's own authentication (a
 *    strong biometric, or the PIN for a biometric-or-PIN key) is the
 *    confirmation, so a tapped chip is NEVER sent without it.
 * 4. The signed action ([SignedAsk.send]); the outcome goes back into the
 *    notification ([StepUpHost.showOutcome]). A prompt the user cancels sends
 *    nothing and leaves the notification exactly as it was.
 *
 * Every attempt reaches a prompt or ends and finishes: the phases and their
 * rules are [StepUpFlow], which this activity only adapts. It declares the
 * configuration changes it can take (manifest), so a rotation does not
 * recreate it mid-unlock; if it is recreated anyway it takes the tap up
 * again, except mid-send, when it says the answer may not have been sent.
 * Logcat tag [TAG], one line per phase change and exit.
 */
class StepUpActivity : FragmentActivity() {
    /** Everything the challenge gave, kept between it and the prompt. */
    private class Ready(
        val pairing: StepUpHost.Pairing,
        val signature: Signature,
        val prepared: SignedAsk.Prepared.Ready,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var work: Job? = null
    private var boundTask: Runnable? = null
    private lateinit var host: StepUpHost

    private val io = object : StepUpFlow.Io<Tap, Ready> {
        override fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

        override fun isStarted() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        override fun requestUnlock(attempt: Long) {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
                this@StepUpActivity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = flow.unlockSucceeded(attempt)
                    override fun onDismissCancelled() = flow.unlockCancelled(attempt)
                    override fun onDismissError() = flow.unlockError(attempt)
                },
            )
        }

        override fun prepare(attempt: Long, tap: Tap) {
            work = lifecycleScope.launch { prepareTap(attempt, tap) }
        }

        // STARTED, not RESUMED: inside onResume the lifecycle still reads
        // STARTED, so a RESUMED check would leave a challenge that came back
        // before the resume waiting until its bound. A prompt can be shown
        // from a started activity whose state is not saved.
        override fun canPrompt() =
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !supportFragmentManager.isStateSaved

        override fun prompt(attempt: Long, tap: Tap, ready: Ready) = showPrompt(attempt, tap, ready)

        override fun send(attempt: Long, tap: Tap, ready: Ready) {
            // The Signature the prompt's CryptoObject wrapped, that the user just authenticated.
            val s = ready.signature
            val der = try {
                s.update(ready.prepared.message)
                s.sign()
            } catch (e: Exception) {
                null
            }
            if (der == null) {
                flow.ended(attempt, "sign-failed", AskOutcome.Failed(SignedAsk.REPAIR))
                return
            }
            work = lifecycleScope.launch {
                val outcome = seams.send(ready.pairing, tap.request, ready.prepared.nonce, der)
                flow.ended(attempt, "sent", outcome)
            }
        }

        override fun cancelWork() {
            work?.cancel()
            work = null
        }

        override fun arm(attempt: Long, ms: Long) {
            disarm()
            val r = Runnable { flow.bound(attempt) }
            boundTask = r
            handler.postDelayed(r, ms)
        }

        override fun disarm() {
            boundTask?.let { handler.removeCallbacks(it) }
            boundTask = null
        }

        override fun showOutcome(tap: Tap, outcome: AskOutcome, ifShowing: Boolean) =
            host.showOutcome(tap.tenant, tap.tag, tap.title, tap.path, outcome, ifShowing)

        override fun busy() = Toast.makeText(this@StepUpActivity, R.string.stepup_busy, Toast.LENGTH_SHORT).show()

        override fun finish() = this@StepUpActivity.finish()

        override fun log(line: String) {
            Log.i(TAG, line)
        }
    }

    private val flow: StepUpFlow<Tap, Ready> = StepUpFlow(io)

    /** The phase of the open attempt (tests). */
    internal val phase: StepUpFlow.Phase get() = flow.phase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tap = Tap.from(intent)
        val h = application as? StepUpHost
        if (tap == null || h == null) {
            Log.w(TAG, if (tap == null) "no tap in the intent: finishing" else "the application is not a step-up host: finishing")
            finish()
            return
        }
        host = h
        val was = savedInstanceState?.getString(STATE_PHASE)
        if (was == StepUpFlow.Phase.SENDING.name) {
            // Recreated mid-send: the old instance said nothing (a
            // configuration change hands over), so this is the one outcome.
            Log.i(TAG, "recreated while sending: the result is unknown")
            host.showOutcome(tap.tenant, tap.tag, tap.title, tap.path, AskOutcome.Failed(StepUpFlow.SEND_UNKNOWN), ifShowing = true)
            finish()
            return
        }
        // Recreated before sending: nothing was sent, so the tap starts over
        // (with its own unlock and prompt).
        if (savedInstanceState != null) Log.i(TAG, "recreated in ${was ?: "an unknown phase"}: taking the tap up again")
        Log.i(TAG, "tap: ${tap.describe()}")
        flow.tap(tap)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PHASE, flow.phase.name)
    }

    /**
     * Another button tapped while this activity is up (singleInstance
     * delivers it here). Refused, never queued, only while the open attempt
     * is genuinely busy (its challenge in flight, its prompt open or its
     * answer being sent): the toast says so and the new tap sends nothing.
     * Otherwise (e.g. an unlock that never reported back) the new tap takes
     * over. Fails closed either way: no tap is sent without its own prompt.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tap = Tap.from(intent) ?: return
        if (!::host.isInitialized) return
        Log.i(TAG, "new tap: ${tap.describe()}")
        if (flow.tap(tap)) setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        flow.resumed()
    }

    override fun onStop() {
        super.onStop()
        flow.stopped()
    }

    override fun onDestroy() {
        if (::host.isInitialized) flow.destroyed(changing = isChangingConfigurations)
        io.disarm()
        super.onDestroy()
    }

    /** Pairing (tap's tenant only), key, challenge: every failure ends with its own outcome. */
    private suspend fun prepareTap(attempt: Long, tap: Tap) {
        val pairing = host.stepUpPairing(tap.tenant)
        if (pairing == null) {
            flow.ended(attempt, "no-pairing", AskOutcome.Failed(SignedAsk.REVOKED))
            return
        }
        val alias = pairing.keyAlias
        if (alias == null) {
            flow.ended(attempt, "read-only", AskOutcome.Failed(SignedAsk.READ_ONLY))
            return
        }
        val signature = seams.signer(alias)
        if (signature == null) {
            flow.ended(attempt, "no-key", AskOutcome.Failed(SignedAsk.REPAIR))
            return
        }
        Log.i(TAG, "attempt $attempt: requesting the challenge")
        when (val p = seams.challenge(pairing, tap.request)) {
            is SignedAsk.Prepared.Refused -> flow.ended(attempt, "challenge-refused", p.outcome)
            is SignedAsk.Prepared.Ready -> flow.prepared(attempt, Ready(pairing, signature, p))
        }
    }

    private fun showPrompt(attempt: Long, tap: Tap, ready: Ready) {
        val info = promptInfo(tap, ready.prepared, ready.pairing.keyTakesPin, getString(R.string.stepup_cancel))
        val result = object : StepUpSeams.PromptResult {
            override fun succeeded(signature: Signature?) {
                if (signature == null) {
                    flow.ended(attempt, "no-signature", AskOutcome.Failed(SignedAsk.REPAIR))
                    return
                }
                flow.confirmed(attempt)
            }

            override fun error(code: Int, message: CharSequence) = when (val e = promptEnd(code, message)) {
                PromptEnd.UserCancelled -> flow.userCancelled(attempt)
                is PromptEnd.Failed -> flow.failed(attempt, e.why, e.message)
            }
        }
        try {
            seams.authenticate(this, info, ready.signature, result)
            Log.i(TAG, "attempt $attempt: prompt shown")
        } catch (e: Exception) {
            flow.failed(attempt, "prompt-not-shown", StepUpFlow.PROMPT_NOT_SHOWN)
        }
    }

    /** How a prompt that did not succeed ends. */
    internal sealed interface PromptEnd {
        /** The user said no: nothing is sent and the notification is left as it was. */
        data object UserCancelled : PromptEnd

        /** Anything else: [message] goes into a still-showing notification. */
        data class Failed(val why: String, val message: String) : PromptEnd
    }

    /** One button's tap, as the notification's PendingIntent carries it; [tenant] is the server it came from. */
    internal data class Tap(val tenant: String, val tag: String, val title: String, val path: String?, val request: AskRequest) {
        /** For Logcat: the server, the notification and the action. Never the answer text. */
        fun describe() = "tenant=$tenant tag=$tag action=${request.action.wire}"

        companion object {
            fun from(i: Intent?): Tap? {
                i ?: return null
                // No tenant, no tap: a button never falls back to "the current server".
                val tenant = i.getStringExtra(EXTRA_TENANT)?.takeIf { it.isNotEmpty() } ?: return null
                val tag = i.getStringExtra(EXTRA_TAG)?.takeIf { it.isNotEmpty() } ?: return null
                val action = ActionId.fromWire(i.getStringExtra(EXTRA_ACTION) ?: return null) ?: return null
                val target = i.getStringExtra(EXTRA_TARGET) ?: return null
                val answer = i.getStringExtra(EXTRA_ANSWER)
                val request = runCatching { AskRequest(action, target, answer) }.getOrNull() ?: return null
                return Tap(tenant, tag, i.getStringExtra(EXTRA_TITLE).orEmpty(), i.getStringExtra(EXTRA_PATH), request)
            }
        }
    }

    companion object {
        internal const val TAG = "MusterStepUp"
        private const val STATE_PHASE = "dev.zacx.muster.stepup.PHASE"

        /** The key, muster and the prompt; a test replaces it (and puts [StepUpSeams.Real] back). */
        @Volatile
        internal var seams: StepUpSeams = StepUpSeams.Real

        /** A BiometricPrompt error [code] (with the system's [message]) as the way the attempt ends. */
        internal fun promptEnd(code: Int, message: CharSequence): PromptEnd = when (code) {
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> PromptEnd.UserCancelled
            // The system took the prompt away (the screen went off, another app came up).
            BiometricPrompt.ERROR_CANCELED -> PromptEnd.Failed("prompt-interrupted", StepUpFlow.INTERRUPTED)
            else -> PromptEnd.Failed("prompt-error-$code", "Not sent: $message")
        }
        internal const val EXTRA_TENANT = "dev.zacx.muster.stepup.TENANT"
        internal const val EXTRA_TAG = "dev.zacx.muster.stepup.TAG"
        internal const val EXTRA_TITLE = "dev.zacx.muster.stepup.TITLE"
        internal const val EXTRA_PATH = "dev.zacx.muster.stepup.PATH"
        internal const val EXTRA_ACTION = "dev.zacx.muster.stepup.ACTION"
        internal const val EXTRA_TARGET = "dev.zacx.muster.stepup.TARGET"
        internal const val EXTRA_ANSWER = "dev.zacx.muster.stepup.ANSWER"

        /**
         * The prompt for [tap] once muster's challenge is [ready]: its title
         * and description ([promptTitle], [promptDescription], from the tap's
         * exact answer and the challenge's `confirms`), the tap's notification
         * title as subtitle, the key's authenticators, and an explicit
         * Confirm even for a face unlock.
         */
        internal fun promptInfo(tap: Tap, ready: SignedAsk.Prepared.Ready, keyTakesPin: Boolean, cancel: String): BiometricPrompt.PromptInfo {
            val allowed = if (keyTakesPin) {
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            } else {
                Authenticators.BIOMETRIC_STRONG
            }
            return BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle(tap.request, ready.confirms))
                .setSubtitle(tap.title)
                .setDescription(promptDescription(tap.request, ready.confirms))
                .setAllowedAuthenticators(allowed)
                .apply { if (!keyTakesPin) setNegativeButtonText(cancel) }
                // A face unlock still asks for a tap on Confirm: never a silent send.
                .setConfirmationRequired(true)
                .build()
        }

        /**
         * The prompt's title: what the tap does. For a step-up confirmation
         * card ([confirms], muster's text for it from the challenge) it names
         * the exact answer and that it decides a request:
         * `Answer "Confirm" to this request?`.
         */
        fun promptTitle(r: AskRequest, confirms: String? = null): String = when {
            confirms != null && r.action == ActionId.ASK_DISMISS -> "Dismiss this request?"
            confirms != null -> "Answer \"${r.answer}\" to this request?"
            r.action == ActionId.ASK_DISMISS -> "Dismiss this question?"
            else -> "Send this answer?"
        }

        /**
         * The prompt's description: the EXACT answer text (the hash binding
         * means something only if the user sees it). For a step-up
         * confirmation card, WHAT the answer decides: the card's own text as
         * muster handed it with the challenge (the title names the answer),
         * cut to [MAX_CARD] characters with a visible marker ([CARD_CUT]).
         */
        fun promptDescription(r: AskRequest, confirms: String? = null): String =
            confirms?.let { cutCard(it) } ?: r.answer ?: "Dismiss without answering."

        /** The most of a card's text the prompt shows (muster docs/mirroring.md says so). */
        const val MAX_CARD = 1000

        /** Follows a card's text the prompt cut. */
        const val CARD_CUT = "…\n\nThe rest is on the card in muster: refuse, or open it, if you have not read it."

        /** [s] cut to at most [MAX_CARD] code points, never inside a surrogate pair, marked when cut. */
        internal fun cutCard(s: String): String {
            if (s.codePointCount(0, s.length) <= MAX_CARD) return s
            return s.substring(0, s.offsetByCodePoints(0, MAX_CARD)) + CARD_CUT
        }

        /**
         * Button [index] of tenant [tenant]'s notification under [tag]:
         * `getActivity` STRAIGHT to this activity, immutable and explicit. The
         * tenant, the tag and the index go into the intent's DATA, so no two
         * buttons (of one notification, of two whose tags' hash codes collide,
         * or of two servers) share a PendingIntent and its extras.
         */
        fun pendingIntent(context: Context, tenant: String, tag: String, index: Int, title: String, path: String?, a: AskAction): PendingIntent {
            val i = Intent(context, StepUpActivity::class.java)
                .setData(Uri.Builder().scheme("muster-action").opaquePart("$tenant/$tag").fragment(index.toString()).build())
                .putExtra(EXTRA_TENANT, tenant)
                .putExtra(EXTRA_TAG, tag)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_ACTION, a.action.wire)
                .putExtra(EXTRA_TARGET, a.target)
                .putExtra(EXTRA_ANSWER, a.answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return PendingIntent.getActivity(
                context,
                31 * (31 * tenant.hashCode() + tag.hashCode()) + index,
                i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
