package dev.zacx.muster.core.auth

/**
 * Which step-up key a phone makes at pairing (docs/design/android.md, "The
 * step-up key", and open question 3, decided):
 *
 * - The default is a BIOMETRIC-ONLY, hardware-backed key: a fresh strong
 *   biometric on every use, destroyed when a new biometric is enrolled.
 * - A biometric-or-PIN key is a muster policy setting, off by default. The
 *   app cannot read that setting before it is paired (`/api/device/config`
 *   needs the device credential), so on a phone WITHOUT a strong biometric it
 *   offers one and muster decides: a refusal (403) does not spend the code,
 *   and the app pairs read-only with it.
 * - 🔴 THE APP NEVER FALLS BACK FROM ONE KIND TO THE OTHER SILENTLY. A phone
 *   that has a strong biometric gets a biometric key or no key: if the
 *   biometric key cannot be made, the phone pairs read-only and says why. It
 *   never quietly makes a PIN key instead.
 * - A key the Keystore reports as software-only is never sent (muster refuses
 *   one anyway); the phone pairs read-only and says why.
 */
object StepUpPolicy {
    data class Capabilities(
        /** BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == SUCCESS. */
        val strongBiometricEnrolled: Boolean,
        /** KeyguardManager.isDeviceSecure: a PIN, pattern or password is set. */
        val deviceSecure: Boolean,
    )

    sealed interface Choice {
        data class Generate(val kind: KeyKind) : Choice

        data class ReadOnly(val reason: String) : Choice
    }

    fun choose(c: Capabilities): Choice = when {
        c.strongBiometricEnrolled -> Choice.Generate(KeyKind.BIOMETRIC)
        c.deviceSecure -> Choice.Generate(KeyKind.BIOMETRIC_OR_CREDENTIAL)
        else -> Choice.ReadOnly(NO_LOCK)
    }

    /** Whether a key at [level] may be sent to muster as a step-up key. */
    fun acceptsLevel(level: SecurityLevel): Boolean = when (level) {
        SecurityLevel.TRUSTED_ENVIRONMENT, SecurityLevel.STRONGBOX, SecurityLevel.UNKNOWN_SECURE -> true
        SecurityLevel.SOFTWARE, SecurityLevel.UNKNOWN -> false
    }

    const val NO_LOCK =
        "This phone has no screen lock, so it cannot hold a step-up key. It is paired read-only: notifications, no actions."
    const val SOFTWARE_KEY =
        "This phone's keystore is software-only, so its step-up key could not gate actions. It is paired read-only."
    const val KEY_FAILED =
        "The biometric step-up key could not be created. This phone is paired read-only; re-pair to try again."
    const val PIN_KEY =
        "This phone has no strong biometric. Actions will accept the device PIN, because this muster server allows PIN keys."
}

/** The step-up key's type, as muster names it (internal/devices KeyBiometric…). */
enum class KeyKind(val wire: String) {
    /** AUTH_BIOMETRIC_STRONG, timeout 0, invalidated by a new enrolment. */
    BIOMETRIC("biometric"),

    /** AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL, timeout 0. */
    BIOMETRIC_OR_CREDENTIAL("biometric_or_credential"),
    ;

    companion object {
        fun fromWire(s: String?): KeyKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * KeyInfo.getSecurityLevel, as muster names it (internal/devices Level…).
 * [UNKNOWN] has no wire name: muster accepts none, so it is never sent.
 */
enum class SecurityLevel(val wire: String?) {
    SOFTWARE("software"),
    TRUSTED_ENVIRONMENT("trusted_environment"),
    STRONGBOX("strongbox"),
    UNKNOWN_SECURE("unknown_secure"),
    UNKNOWN(null),
}
