package dev.zacx.muster.core.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The step-up key, behind an interface so pairing is testable off-device. */
interface StepUpKeys {
    fun capabilities(): StepUpPolicy.Capabilities

    /** Generates a step-up key of [kind] under [alias]. Throws on failure. */
    fun generate(alias: String, kind: KeyKind): GeneratedKey

    fun delete(alias: String)

    fun state(alias: String): KeyState

    data class GeneratedKey(
        /** base64 SubjectPublicKeyInfo DER of the EC P-256 public key. */
        val spkiBase64: String,
        val level: SecurityLevel,
    )

    enum class KeyState { ABSENT, VALID, INVALIDATED }

    companion object {
        /**
         * A fresh Keystore alias for one pairing. Each pairing has its own, so
         * a re-pairing can make its key while the old pairing's key still
         * exists, and delete the old one only once the new pairing is made.
         */
        fun newAlias(): String {
            val b = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
            return "muster-stepup-" + b.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The real step-up key in the Android Keystore: EC P-256, SHA-256, a fresh
 * authentication on EVERY use (timeout 0), invalidated by a new biometric
 * enrolment. The pattern was validated by Phase 0 spikes S3 and S4 (on
 * emulator software keys only; hardware behaviour expected, not measured).
 */
class KeystoreStepUpKeys(private val context: Context) : StepUpKeys {

    override fun capabilities(): StepUpPolicy.Capabilities {
        val status = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val secure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        return StepUpPolicy.Capabilities(StepUpPolicy.biometricFrom(status), deviceSecure = secure)
    }

    override fun generate(alias: String, kind: KeyKind): StepUpKeys.GeneratedKey {
        val auth = when (kind) {
            KeyKind.BIOMETRIC -> KeyProperties.AUTH_BIOMETRIC_STRONG
            KeyKind.BIOMETRIC_OR_CREDENTIAL ->
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, auth)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return StepUpKeys.GeneratedKey(
            spkiBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
            level = levelOf(pair.private),
        )
    }

    override fun delete(alias: String) {
        keyStore().deleteEntry(alias)
    }

    /**
     * ABSENT, VALID, or INVALIDATED (a new biometric was enrolled: "Re-confirm
     * this phone"). initSign on a per-use key does not need an authentication,
     * but it does throw KeyPermanentlyInvalidatedException for a dead key.
     */
    override fun state(alias: String): StepUpKeys.KeyState {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return StepUpKeys.KeyState.ABSENT
        return try {
            Signature.getInstance("SHA256withECDSA").initSign(key)
            StepUpKeys.KeyState.VALID
        } catch (e: KeyPermanentlyInvalidatedException) {
            StepUpKeys.KeyState.INVALIDATED
        }
    }

    private fun levelOf(key: PrivateKey): SecurityLevel {
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // API 30 has no getSecurityLevel; isInsideSecureHardware says TEE
            // or StrongBox without saying which.
            @Suppress("DEPRECATION")
            return if (info.isInsideSecureHardware) SecurityLevel.UNKNOWN_SECURE else SecurityLevel.SOFTWARE
        }
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> SecurityLevel.UNKNOWN_SECURE
            else -> SecurityLevel.UNKNOWN
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}
