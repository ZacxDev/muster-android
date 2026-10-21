package dev.zacx.muster.feature.push

import dev.zacx.muster.core.model.AttachmentGate
import dev.zacx.muster.core.model.NotificationSink
import dev.zacx.muster.core.model.Push
import dev.zacx.muster.core.model.PayloadV2Parser

/**
 * What one UnifiedPush message does.
 *
 * 🔴 ONLY DECRYPTED PUSHES ARE TRUSTED, AND THE CHECK IS LOAD-BEARING
 * (docs/design/android.md, "Only decrypted pushes are trusted"). ntfy's `up*`
 * topics accept anonymous publishes and ntfy does not check VAPID (Phase 0,
 * S9), so anyone who learns the endpoint can publish to it. The connector
 * hands the app every message as `PushMessage(content, decrypted)`; a message
 * that decrypted was encrypted to this subscription's keys (RFC 8291), which
 * only this phone and muster hold. An undecrypted message is dropped BEFORE
 * it is parsed: it draws nothing, withdraws nothing and changes no state.
 */
class PushHandler(private val sink: NotificationSink) {

    enum class Outcome { DROPPED_UNDECRYPTED, DROPPED_UNPAIRED, REJECTED, PROBE, SHOWN, WITHDRAWN }

    fun handle(content: ByteArray, decrypted: Boolean, paired: Boolean): Outcome {
        if (!decrypted) return Outcome.DROPPED_UNDECRYPTED
        // A push that arrives after unpairing (a late delivery) is not shown.
        if (!paired) return Outcome.DROPPED_UNPAIRED
        return when (val p = PayloadV2Parser.parse(content)) {
            is Push.Rejected -> Outcome.REJECTED
            Push.Probe -> Outcome.PROBE
            is Push.Withdraw -> { sink.withdraw(p.tag); Outcome.WITHDRAWN }
            is Push.Show -> {
                // A tap never opens a note attachment (AttachmentGate).
                val payload = p.payload.path?.let { if (AttachmentGate.blocks(it)) p.payload.copy(path = null) else null }
                    ?: p.payload
                sink.show(payload)
                Outcome.SHOWN
            }
        }
    }
}
