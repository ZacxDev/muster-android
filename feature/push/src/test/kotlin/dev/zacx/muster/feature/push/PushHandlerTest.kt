package dev.zacx.muster.feature.push

import dev.zacx.muster.core.model.PayloadV2
import dev.zacx.muster.feature.push.PushHandler.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushHandlerTest {
    private val sink = RecordingSink()
    private val handler = PushHandler(sink)

    private val show = """{"v":2,"type":"attention","id":"9","tag":"att-9","title":"Agent ada","body":"in muster","url":"/attention"}"""
        .encodeToByteArray()
    private val resolved = """{"v":2,"type":"resolved","id":"9","tag":"att-9"}""".encodeToByteArray()

    /**
     * 🔴 THE LOAD-BEARING RULE. ntfy accepts anonymous publishes to `up*`
     * topics and does not check VAPID, so an undecrypted message may be
     * anyone's. A perfectly valid payload that did NOT decrypt draws nothing
     * and withdraws nothing.
     */
    @Test
    fun anUndecryptedMessageDoesNothing() {
        assertEquals(Outcome.DROPPED_UNDECRYPTED, handler.handle(show, decrypted = false, paired = true))
        assertEquals(Outcome.DROPPED_UNDECRYPTED, handler.handle(resolved, decrypted = false, paired = true))
        assertTrue(sink.shown.isEmpty())
        assertTrue(sink.withdrawn.isEmpty())
    }

    @Test
    fun aDecryptedMessageIsShownAndWithdrawnByTag() {
        assertEquals(Outcome.SHOWN, handler.handle(show, decrypted = true, paired = true))
        assertEquals(listOf(PayloadV2("attention", "9", "att-9", "Agent ada", "in muster", "/attention")), sink.shown)
        assertEquals(Outcome.WITHDRAWN, handler.handle(resolved, decrypted = true, paired = true))
        assertEquals(listOf("att-9"), sink.withdrawn)
    }

    @Test
    fun aLatePushAfterUnpairingIsNotShown() {
        assertEquals(Outcome.DROPPED_UNPAIRED, handler.handle(show, decrypted = true, paired = false))
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun theRegistrationProbeAndJunkDrawNothing() {
        assertEquals(Outcome.PROBE, handler.handle("""{"v":2,"type":"test"}""".encodeToByteArray(), true, true))
        assertEquals(Outcome.REJECTED, handler.handle("""{"v":1,"type":"review","title":"x"}""".encodeToByteArray(), true, true))
        assertTrue(sink.shown.isEmpty())
    }

    /** With the gate closed a notification never opens an attachment: the tap target is dropped. */
    @Test
    fun anAttachmentTapTargetIsDroppedWhileTheGateIsClosed() {
        val p = """{"v":2,"type":"comment","tag":"c","title":"x","url":"/tasks/4/attachments/2"}""".encodeToByteArray()
        assertEquals(Outcome.SHOWN, PushHandler(sink, sandboxConfirmed = false).handle(p, true, true))
        assertEquals(null, sink.shown.single().path)
    }

    /** As shipped (the gate open): an attachment tap target is kept. */
    @Test
    fun anAttachmentTapTargetIsKeptAsShipped() {
        val p = """{"v":2,"type":"comment","tag":"c","title":"x","url":"/tasks/4/attachments/2"}""".encodeToByteArray()
        assertEquals(Outcome.SHOWN, handler.handle(p, true, true))
        assertEquals("/tasks/4/attachments/2", sink.shown.single().path)
    }
}
