package dev.zacx.muster.feature.stepup

/**
 * Phase 2: the invisible FragmentActivity that runs BiometricPrompt and signs
 * a server challenge with the biometric-bound Keystore key. The validated
 * pattern is in spikes/ (S3, S4) and docs/phase0-results.md.
 */
object StepUp {
    /** Keystore alias of the step-up key, generated at pairing (Phase 1). */
    const val KEY_ALIAS = "muster-stepup-v1"
}
