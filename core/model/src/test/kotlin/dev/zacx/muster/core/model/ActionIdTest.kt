package dev.zacx.muster.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionIdTest {
    /**
     * The whole table, pinned as literals: adding, removing, renaming or
     * re-routing an action must be a deliberate edit here and in muster's
     * payload v2 and route ledger.
     */
    @Test
    fun tableIsPinned() {
        val expected = listOf(
            "queue_send_now" to "/api/device/actions/send-queue/{id}/send-now",
            "queue_cancel" to "/api/device/actions/send-queue/{id}/cancel",
            "session_queue" to "/api/device/actions/send-queue",
            "ask_answer" to "/api/device/actions/attention/asks/{id}/answer",
            "ask_dismiss" to "/api/device/actions/attention/asks/{id}/cancel",
            "draft_approve" to "/api/device/actions/plugins/drafts/{id}/approve",
            "draft_reject" to "/api/device/actions/plugins/drafts/{id}/reject",
            "agent_send" to "/api/device/actions/agents/{name}/send",
        )
        // A list, not a map: a duplicated wire id cannot collapse away.
        assertEquals(expected, ActionId.entries.map { it.wire to it.route })
    }

    /** Every id resolves to its own entry, so no id shadows another. */
    @Test
    fun everyWireIdResolvesToItself() {
        ActionId.entries.forEach { assertEquals(it, ActionId.fromWire(it.wire)) }
    }

    @Test
    fun unknownOrAmbiguousWireIdIsDropped() {
        assertNull(ActionId.fromWire("../../account/delete"))
        assertNull(ActionId.fromWire(""))
        // Bare verbs are ambiguous between object kinds and are not ids.
        assertNull(ActionId.fromWire("cancel"))
        assertNull(ActionId.fromWire("answer"))
        assertNull(ActionId.fromWire("approve"))
    }
}
