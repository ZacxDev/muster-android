package dev.zacx.muster.core.auth

import androidx.biometric.BiometricManager
import dev.zacx.muster.core.auth.StepUpPolicy.Biometric
import dev.zacx.muster.core.auth.StepUpPolicy.Capabilities
import dev.zacx.muster.core.auth.StepUpPolicy.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepUpPolicyTest {
    /** A phone with a strong biometric gets a biometric key, PIN or not. Never a PIN key. */
    @Test
    fun aBiometricPhoneGetsABiometricKey() {
        assertEquals(Choice.Generate(KeyKind.BIOMETRIC), StepUpPolicy.choose(caps(true, true)))
        assertEquals(Choice.Generate(KeyKind.BIOMETRIC), StepUpPolicy.choose(caps(true, false)))
    }

    @Test
    fun aPinOnlyPhoneOffersAPinKeyForMusterToDecide() {
        assertEquals(Choice.Generate(KeyKind.BIOMETRIC_OR_CREDENTIAL), StepUpPolicy.choose(caps(false, true)))
    }

    @Test
    fun aPhoneWithNoLockIsReadOnly() {
        assertEquals(Choice.ReadOnly(StepUpPolicy.NO_LOCK), StepUpPolicy.choose(caps(false, false)))
    }

    /**
     * 🔴 Every canAuthenticate status, end to end. Only "none enrolled" and
     * "no hardware" may lead to the PIN-key offer; any other non-success may
     * be transient on a phone that HAS a strong biometric, so it pairs
     * read-only with an honest note, never with a PIN key.
     */
    @Test
    fun eachBiometricStatusLeadsWhereItShould() {
        val pin = Choice.Generate(KeyKind.BIOMETRIC_OR_CREDENTIAL)
        val unavailable = Choice.ReadOnly(StepUpPolicy.BIOMETRIC_UNAVAILABLE)
        val expected = linkedMapOf(
            BiometricManager.BIOMETRIC_SUCCESS to Choice.Generate(KeyKind.BIOMETRIC),
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED to pin,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE to pin,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE to unavailable,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED to unavailable,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED to unavailable,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN to unavailable,
            9999 to unavailable,
        )
        expected.forEach { (status, want) ->
            assertEquals("status $status", want, StepUpPolicy.choose(Capabilities(StepUpPolicy.biometricFrom(status), deviceSecure = true)))
        }
        // Absent with no screen lock is the no-lock case, not a PIN key.
        assertEquals(
            Choice.ReadOnly(StepUpPolicy.NO_LOCK),
            StepUpPolicy.choose(Capabilities(StepUpPolicy.biometricFrom(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED), false)),
        )
        assertEquals(Biometric.Unavailable(1), StepUpPolicy.biometricFrom(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    /** muster refuses `software`, and has no name for UNKNOWN; the app sends neither. */
    @Test
    fun onlyHardwareLevelsAreSent() {
        assertTrue(StepUpPolicy.acceptsLevel(SecurityLevel.TRUSTED_ENVIRONMENT))
        assertTrue(StepUpPolicy.acceptsLevel(SecurityLevel.STRONGBOX))
        assertTrue(StepUpPolicy.acceptsLevel(SecurityLevel.UNKNOWN_SECURE))
        assertFalse(StepUpPolicy.acceptsLevel(SecurityLevel.SOFTWARE))
        assertFalse(StepUpPolicy.acceptsLevel(SecurityLevel.UNKNOWN))
    }

    /** The wire names are muster's (internal/devices): pinned as literals. */
    @Test
    fun wireNamesAreMusters() {
        assertEquals(listOf("biometric", "biometric_or_credential"), KeyKind.entries.map { it.wire })
        assertEquals(
            listOf("software", "trusted_environment", "strongbox", "unknown_secure", null),
            SecurityLevel.entries.map { it.wire },
        )
    }
}
