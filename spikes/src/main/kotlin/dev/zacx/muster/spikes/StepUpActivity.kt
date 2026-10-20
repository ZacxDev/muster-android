package dev.zacx.muster.spikes

import android.app.NotificationManager
import android.os.Bundle
import android.util.Base64
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature

/**
 * S3/S4: the invisible step-up activity. Started straight from a notification
 * action's PendingIntent.getActivity (or, in the control, from a receiver).
 * Builds a server-style challenge, runs BiometricPrompt with a CryptoObject
 * over a Signature from the auth-bound key, signs, verifies with the public
 * key, logs the outcome and finish()es.
 */
class StepUpActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alias = intent.getStringExtra("alias") ?: StepUpKeys.BIO
        val notifId = intent.getIntExtra("notif", 0)
        val via = intent.getStringExtra("via") ?: "?"
        val variant = intent.getStringExtra("variant") ?: "plain"
        log("S3 stepup-activity START via=$via alias=$alias variant=$variant ${deviceLine(this)}")
        if (variant == "showwhenlocked") setShowWhenLocked(true)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (variant == "dismiss" && km.isKeyguardLocked) {
            // Ask for the unlock first; prompt only once the keyguard is gone.
            km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    log("S3 keyguard dismissed ${deviceLine(this@StepUpActivity)}")
                    runStepUp(alias, notifId, via, variant)
                }
                override fun onDismissCancelled() { log("S3 RESULT variant=dismiss keyguard dismiss CANCELLED"); finish() }
                override fun onDismissError() { log("S3 RESULT variant=dismiss keyguard dismiss ERROR"); finish() }
            })
            return
        }
        runStepUp(alias, notifId, via, variant)
    }

    private fun runStepUp(alias: String, notifId: Int, via: String, variant: String) {

        // A server-style challenge: what muster's POST /api/device/challenge
        // would return, bound to device, action, target and text hash.
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val text = "Approve and send the reply"
        val challenge = (
            "{\"nonce\":\"${Base64.encodeToString(nonce, Base64.NO_WRAP)}\"," +
                "\"device\":\"dev-spike\",\"action\":\"send_now\",\"target\":\"q-42\"," +
                "\"text_sha256\":\"${sha256Hex(text)}\"}"
            ).toByteArray()

        val sig = try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(StepUpKeys.privateKey(alias)) }
        } catch (e: Exception) {
            log("S3 RESULT alias=$alias via=$via initSign FAILED ${e.javaClass.name}: ${e.message}")
            finish(); return
        }

        val allowed = when (alias) {
            StepUpKeys.CRED -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            else -> Authenticators.BIOMETRIC_STRONG
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm: Send now")
            .setSubtitle("muster spike")
            .setDescription(text)
            .setAllowedAuthenticators(allowed)
            .apply { if (allowed and Authenticators.DEVICE_CREDENTIAL == 0) setNegativeButtonText("Cancel") }
            .setConfirmationRequired(false)
            .build()

        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val s = result.cryptoObject?.signature
                    val outcome = try {
                        s!!.update(challenge)
                        val der = s.sign()
                        val ok = Signature.getInstance("SHA256withECDSA").run {
                            initVerify(StepUpKeys.publicKey(alias)); update(challenge); verify(der)
                        }
                        "signed=${der.size}B verified=$ok"
                    } catch (e: Exception) {
                        "sign FAILED ${e.javaClass.name}: ${e.message}"
                    }
                    log("S3 RESULT alias=$alias via=$via variant=$variant OK authType=${result.authenticationType} $outcome ${deviceLine(this@StepUpActivity)}")
                    getSystemService(NotificationManager::class.java).cancel(notifId)
                    finish()
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    log("S3 RESULT alias=$alias via=$via variant=$variant ERROR code=$code msg=$msg ${deviceLine(this@StepUpActivity)}")
                    finish()
                }

                override fun onAuthenticationFailed() {
                    log("S3 attempt-failed alias=$alias (not recognised; prompt stays up)")
                }
            })
        try {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
            log("S3 prompt shown alias=$alias allowed=$allowed")
        } catch (e: Exception) {
            log("S3 RESULT alias=$alias via=$via authenticate THREW ${e.javaClass.name}: ${e.message}")
            finish()
        }
    }

    private fun sha256Hex(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
