package dev.zacx.muster.core.auth

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
        assertEquals(Choice.Generate(KeyKind.BIOMETRIC), StepUpPolicy.choose(Capabilities(true, true)))
        assertEquals(Choice.Generate(KeyKind.BIOMETRIC), StepUpPolicy.choose(Capabilities(true, false)))
    }

    @Test
    fun aPinOnlyPhoneOffersAPinKeyForMusterToDecide() {
        assertEquals(Choice.Generate(KeyKind.BIOMETRIC_OR_CREDENTIAL), StepUpPolicy.choose(Capabilities(false, true)))
    }

    @Test
    fun aPhoneWithNoLockIsReadOnly() {
        assertEquals(Choice.ReadOnly(StepUpPolicy.NO_LOCK), StepUpPolicy.choose(Capabilities(false, false)))
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
