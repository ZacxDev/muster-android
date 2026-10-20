package dev.zacx.muster.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionIdTest {
    /**
     * The whole table, pinned as literals: adding, removing or re-routing an
     * action must be a deliberate edit here and in muster's route ledger.
     */
    @Test
    fun tableIsPinned() {
        val expected = mapOf(
            "send_now" to "/api/device/actions/send-queue/{id}/send-now",
            "cancel" to "/api/device/actions/send-queue/{id}/cancel",
            "queue" to "/api/device/actions/send-queue",
            "answer" to "/api/device/actions/attention/asks/{id}/answer",
            "dismiss" to "/api/device/actions/attention/asks/{id}/cancel",
            "approve" to "/api/device/actions/plugins/drafts/{id}/approve",
            "reject" to "/api/device/actions/plugins/drafts/{id}/reject",
            "reply" to "/api/device/actions/agents/{name}/send",
        )
        assertEquals(expected, ActionId.entries.associate { it.wire to it.route })
    }

    @Test
    fun everyRouteIsUnderTheDeviceActionTier() {
        ActionId.entries.forEach {
            assert(it.route.startsWith("/api/device/actions/")) { it.route }
        }
    }

    @Test
    fun unknownWireIdIsDropped() {
        assertNull(ActionId.fromWire("../../account/delete"))
        assertNull(ActionId.fromWire(""))
        assertEquals(ActionId.SEND_NOW, ActionId.fromWire("send_now"))
    }
}
