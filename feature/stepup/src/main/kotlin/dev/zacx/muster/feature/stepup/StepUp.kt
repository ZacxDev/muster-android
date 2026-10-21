package dev.zacx.muster.feature.stepup

import dev.zacx.muster.core.auth.StepUpKeys

/**
 * Phase 2: the invisible FragmentActivity that runs BiometricPrompt and signs
 * a server challenge with the biometric-bound Keystore key. The validated
 * pattern is in spikes/ (S3, S4) and docs/phase0-results.md.
 *
 * Phase 1 generates the key at pairing (core/auth: StepUpPolicy,
 * KeystoreStepUpKeys, Pairing). Nothing signs with it yet: muster serves no
 * signed action route before Phase 2.
 */
object StepUp {
    /** Keystore alias of the step-up key, generated at pairing. */
    const val KEY_ALIAS = StepUpKeys.ALIAS
}
