package dev.zacx.muster.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentGateTest {
    /** The switch ships OFF: muster's attachment sandbox is not confirmed. */
    @Test
    fun theGateShipsClosed() {
        assertEquals(false, AttachmentGate.SANDBOX_CONFIRMED)
    }

    @Test
    fun attachmentUrlsAreBlocked() {
        listOf(
            "https://m.example.test/tasks/42/attachments/7",
            "https://m.example.test/tasks/42/attachments/7?download=0",
            "https://m.example.test/tasks/42/attachments",
            "/tasks/42/attachments/7",
            "https://m.example.test/Tasks/42/Attachments/7",
            "https://m.example.test//tasks//42/attachments/7",
            "https://m.example.test/tasks/42/x/../attachments/7",
            "https://m.example.test/tasks/42/%61ttachments/7",
            "https://m.example.test/a/../tasks/42/attachments/7",
        ).forEach { assertTrue(it, AttachmentGate.blocks(it)) }
    }

    @Test
    fun otherMusterPagesAreNot() {
        listOf(
            "https://m.example.test/tasks/42",
            "https://m.example.test/tasks/42/comments",
            "https://m.example.test/attention",
            "https://m.example.test/uploads/abc", // muster sandboxes these itself (uploads.go CSP)
            "https://m.example.test/tasks/42?next=/tasks/1/attachments/2",
        ).forEach { assertFalse(it, AttachmentGate.blocks(it)) }
    }

    @Test
    fun theSwitchOpensIt() {
        assertFalse(AttachmentGate.blocks("/tasks/42/attachments/7", sandboxConfirmed = true))
    }
}
