package dev.zacx.muster.core.model

/**
 * The phone's one step-up rule (docs/design/android.md, "Operator
 * decisions"): EVERY action that changes server state is signed with the
 * biometric-bound step-up key, refusals included. There is no per-action
 * exception and no unsigned path, so this policy has no action-specific
 * input: it decides whether the phone may offer ANY action at all.
 *
 * Phase 1 draws no action buttons at all (the payload parser does not read
 * `actions`; the signed-action activity is Phase 2). This verdict is what the
 * phone's status screen shows, so the user knows what the phone will be able
 * to do: muster's `/api/device/config` reports `features.actions = false`
 * until the signed `/api/device/actions/...` routes exist.
 */
object ActionPolicy {
    enum class Verdict {
        /** Offer the action; tapping it runs the biometric step-up. */
        OFFER_SIGNED,

        /** muster does not serve signed actions yet (Phase 1). */
        SERVER_HAS_NO_ACTIONS,

        /** Paired with no step-up key: notifications only. */
        READ_ONLY,

        /** The key was invalidated (new biometric enrolled): re-pair first. */
        KEY_INVALIDATED,
    }

    /**
     * @param serverServesActions `features.actions` from `/api/device/config`.
     * @param hasStepUpKey the device paired with a step-up key.
     * @param keyValid the Keystore key still exists and is not invalidated.
     */
    fun decide(serverServesActions: Boolean, hasStepUpKey: Boolean, keyValid: Boolean): Verdict = when {
        !hasStepUpKey -> Verdict.READ_ONLY
        !keyValid -> Verdict.KEY_INVALIDATED
        !serverServesActions -> Verdict.SERVER_HAS_NO_ACTIONS
        else -> Verdict.OFFER_SIGNED
    }
}
