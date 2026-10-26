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

    /**
     * A notification never opens an upload, gate open or closed: muster never
     * sends one, and a notification never needs raw bytes. The tap target is
     * dropped (the tap opens the home page); the notification is still shown.
     */
    @Test
    fun anUploadTapTargetIsDropped() {
        listOf("/tasks/4/attachments/2", "/uploads/abc/file", "/f/abc").forEach { path ->
            val p = """{"v":2,"type":"comment","tag":"c","title":"x","url":"$path"}""".encodeToByteArray()
            val s = RecordingSink()
            assertEquals(path, Outcome.SHOWN, PushHandler(s).handle(p, true, true))
            assertEquals(path, null, s.shown.single().path)
        }
        // Control: an ordinary path is kept.
        assertEquals(Outcome.SHOWN, handler.handle(show, true, true))
        assertEquals("/attention", sink.shown.single().path)
    }
}
