package dev.zacx.muster.feature.stepup

/**
 * Phase 2: the invisible FragmentActivity that runs BiometricPrompt and signs
 * a server challenge with the biometric-bound Keystore key. The validated
 * pattern is in docs/phase0-results.md (S3, S4) and the Phase 0 spike app at
 * f652f32.
 *
 * Phase 1 generates the key at pairing (core/auth: StepUpPolicy,
 * KeystoreStepUpKeys, Pairing). Nothing signs with it yet: muster serves no
 * signed action route before Phase 2.
 */
internal object StepUp
