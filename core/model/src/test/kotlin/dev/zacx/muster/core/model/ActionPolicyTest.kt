package dev.zacx.muster.core.model

import dev.zacx.muster.core.model.ActionPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionPolicyTest {
    /** The whole truth table: an action is offered only signed, with a live key, when muster serves it. */
    @Test
    fun everyActionNeedsALiveStepUpKey() {
        val table = mapOf(
            Triple(true, true, true) to Verdict.OFFER_SIGNED,
            Triple(false, true, true) to Verdict.SERVER_HAS_NO_ACTIONS,
            Triple(true, true, false) to Verdict.KEY_INVALIDATED,
            Triple(false, true, false) to Verdict.KEY_INVALIDATED,
            Triple(true, false, true) to Verdict.READ_ONLY,
            Triple(true, false, false) to Verdict.READ_ONLY,
            Triple(false, false, true) to Verdict.READ_ONLY,
            Triple(false, false, false) to Verdict.READ_ONLY,
        )
        table.forEach { (input, want) ->
            assertEquals(input.toString(), want, ActionPolicy.decide(input.first, input.second, input.third))
        }
    }
}
