package dev.zacx.muster.feature.stepup

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.AskRequest
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.core.net.SignedAsk
import kotlinx.coroutines.Dispatchers
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

    /** null when the phone is not paired. */
    suspend fun stepUpPairing(): Pairing?

    fun showOutcome(tag: String, title: String, path: String?, outcome: AskOutcome)
}

/**
 * The invisible step-up activity (muster docs/design/android.md, "A gated
 * action, step by step"; validated by Phase 0 spikes S3 and S4). A button on
 * an ask's notification starts it straight from `PendingIntent.getActivity`
 * (a receiver trampoline is blocked silently on targetSdk 31+, Phase 0 S3):
 *
 * 1. Locked: `requestDismissKeyguard()` first, and go on only in
 *    `onDismissSucceeded` (a prompt behind the keyguard is cancelled). A
 *    cancelled unlock finishes and leaves the notification as it was.
 * 2. `POST /api/device/challenge`, bound to the action, the ask and the
 *    sha256 of the exact answer ([SignedAsk.prepare]).
 * 3. BiometricPrompt over a `CryptoObject(Signature)` from the step-up key,
 *    its description the exact answer: the key's own authentication (a
 *    strong biometric, or the PIN for a biometric-or-PIN key) is the
 *    confirmation, so a tapped chip is NEVER sent without it.
 * 4. The signed action ([SignedAsk.send]); the outcome goes back into the
 *    notification ([StepUpHost.showOutcome]). A prompt the user cancels sends
 *    nothing and leaves the notification as it was.
 */
class StepUpActivity : FragmentActivity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tap = Tap.from(intent)
        if (tap == null || savedInstanceState != null) {
            finish()
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = run(tap)
                    override fun onDismissCancelled() = finish()
                    override fun onDismissError() = finish()
                },
            )
            return
        }
        run(tap)
    }

    /**
     * Another button tapped while this one's unlock or prompt is still up
     * (singleInstance delivers it here, not to a new activity). The open
     * attempt is kept and the new tap is refused, never queued: it sends
     * nothing, and the user taps it again once the open one is done. Fails
     * closed: no tap is ever sent without its own confirmation.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Tap.from(intent) != null) {
            Toast.makeText(this, R.string.stepup_busy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun run(tap: Tap) {
        if (started) return
        started = true
        val host = application as? StepUpHost ?: return finish()
        lifecycleScope.launch {
            val pairing = host.stepUpPairing()
            val alias = pairing?.keyAlias
            if (pairing == null || alias == null) {
                done(host, tap, AskOutcome.Failed(if (pairing == null) SignedAsk.REVOKED else SignedAsk.READ_ONLY))
                return@launch
            }
            val signature = try {
                val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? PrivateKey
                key?.let { Signature.getInstance("SHA256withECDSA").apply { initSign(it) } }
            } catch (e: KeyPermanentlyInvalidatedException) {
                null // a new enrolment destroyed the key: "Re-confirm this phone"
            } catch (e: Exception) {
                null
            }
            if (signature == null) {
                done(host, tap, AskOutcome.Failed(SignedAsk.REPAIR))
                return@launch
            }
            val signed = SignedAsk(DeviceApi(pairing.origin), pairing.token)
            when (val p = withContext(Dispatchers.IO) { signed.prepare(tap.request) }) {
                is SignedAsk.Prepared.Refused -> done(host, tap, p.outcome)
                is SignedAsk.Prepared.Ready -> prompt(host, tap, pairing, signature, signed, p)
            }
        }
    }

    private fun prompt(
        host: StepUpHost,
        tap: Tap,
        pairing: StepUpHost.Pairing,
        signature: Signature,
        signed: SignedAsk,
        ready: SignedAsk.Prepared.Ready,
    ) {
        val allowed = if (pairing.keyTakesPin) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_STRONG
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle(tap.request))
            .setSubtitle(tap.title)
            .setDescription(promptDescription(tap.request))
            .setAllowedAuthenticators(allowed)
            .apply { if (!pairing.keyTakesPin) setNegativeButtonText(getString(R.string.stepup_cancel)) }
            // A face unlock still asks for a tap on Confirm: never a silent send.
            .setConfirmationRequired(true)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature
                val der = try {
                    s?.run { update(ready.message); sign() }
                } catch (e: Exception) {
                    null
                }
                if (der == null) {
                    done(host, tap, AskOutcome.Failed(SignedAsk.REPAIR))
                    return
                }
                lifecycleScope.launch {
                    done(host, tap, withContext(Dispatchers.IO) { signed.send(tap.request, ready.nonce, der) })
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    // The user said no: nothing is sent and the notification stays as it was.
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> finish()
                    else -> done(host, tap, AskOutcome.Failed("Not sent: $errString"))
                }
            }
        }
        try {
            BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
                .authenticate(info, BiometricPrompt.CryptoObject(signature))
        } catch (e: Exception) {
            done(host, tap, AskOutcome.Failed("Not sent: the confirmation could not be shown."))
        }
    }

    private fun done(host: StepUpHost, tap: Tap, outcome: AskOutcome) {
        host.showOutcome(tap.tag, tap.title, tap.path, outcome)
        finish()
    }

    /** One button's tap, as the notification's PendingIntent carries it. */
    internal data class Tap(val tag: String, val title: String, val path: String?, val request: AskRequest) {
        companion object {
            fun from(i: Intent?): Tap? {
                i ?: return null
                val tag = i.getStringExtra(EXTRA_TAG)?.takeIf { it.isNotEmpty() } ?: return null
                val action = ActionId.fromWire(i.getStringExtra(EXTRA_ACTION) ?: return null) ?: return null
                val target = i.getStringExtra(EXTRA_TARGET) ?: return null
                val answer = i.getStringExtra(EXTRA_ANSWER)
                val request = runCatching { AskRequest(action, target, answer) }.getOrNull() ?: return null
                return Tap(tag, i.getStringExtra(EXTRA_TITLE).orEmpty(), i.getStringExtra(EXTRA_PATH), request)
            }
        }
    }

    companion object {
        internal const val EXTRA_TAG = "dev.zacx.muster.stepup.TAG"
        internal const val EXTRA_TITLE = "dev.zacx.muster.stepup.TITLE"
        internal const val EXTRA_PATH = "dev.zacx.muster.stepup.PATH"
        internal const val EXTRA_ACTION = "dev.zacx.muster.stepup.ACTION"
        internal const val EXTRA_TARGET = "dev.zacx.muster.stepup.TARGET"
        internal const val EXTRA_ANSWER = "dev.zacx.muster.stepup.ANSWER"

        /** The prompt's title: what the tap does. */
        fun promptTitle(r: AskRequest): String =
            if (r.action == ActionId.ASK_DISMISS) "Dismiss this question?" else "Send this answer?"

        /** The prompt's description: the EXACT answer text (the hash binding means something only if the user sees it). */
        fun promptDescription(r: AskRequest): String = r.answer ?: "Dismiss without answering."

        /**
         * Button [index] of the notification under [tag]: `getActivity`
         * STRAIGHT to this activity, immutable and explicit. The tag and the
         * index go into the intent's DATA, so no two buttons (of one
         * notification, or of two whose tags' hash codes collide) share a
         * PendingIntent and its extras.
         */
        fun pendingIntent(context: Context, tag: String, index: Int, title: String, path: String?, a: AskAction): PendingIntent {
            val i = Intent(context, StepUpActivity::class.java)
                .setData(Uri.Builder().scheme("muster-action").opaquePart(tag).fragment(index.toString()).build())
                .putExtra(EXTRA_TAG, tag)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_ACTION, a.action.wire)
                .putExtra(EXTRA_TARGET, a.target)
                .putExtra(EXTRA_ANSWER, a.answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return PendingIntent.getActivity(
                context,
                31 * tag.hashCode() + index,
                i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
