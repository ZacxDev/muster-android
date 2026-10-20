package dev.zacx.muster.spikes

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/** S3/S4: EC P-256 signing keys bound to user authentication. */
object StepUpKeys {
    const val BIO = "s3-bio"        // AUTH_BIOMETRIC_STRONG only (the design's key)
    const val CRED = "s4-cred"      // AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL

    fun authFor(alias: String): Int = when (alias) {
        BIO -> KeyProperties.AUTH_BIOMETRIC_STRONG
        CRED -> KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        else -> error("unknown alias $alias")
    }

    fun generate(alias: String): String = try {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, authFor(alias))
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(spec)
        kpg.generateKeyPair()
        "keygen alias=$alias OK ${describe(alias)}"
    } catch (e: Exception) {
        "keygen alias=$alias FAILED ${e.javaClass.name}: ${e.message}"
    }

    fun privateKey(alias: String): PrivateKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as PrivateKey?
    }

    fun publicKey(alias: String) =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCertificate(alias).publicKey

    fun describe(alias: String): String {
        val key = privateKey(alias) ?: return "absent"
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java)
        return "securityLevel=${info.securityLevel} authType=${info.userAuthenticationType} " +
            "timeout=${info.userAuthenticationValidityDurationSeconds} " +
            "invalidatedByEnroll=${info.isInvalidatedByBiometricEnrollment}"
    }

    fun delete(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
