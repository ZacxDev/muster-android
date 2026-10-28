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

    /**
     * [canSign]: this pairing has a step-up key. A read-only phone shows an
     * ask's question but no buttons, since every one of them is a signed
     * action it cannot make.
     */
    fun handle(content: ByteArray, decrypted: Boolean, paired: Boolean, canSign: Boolean = false): Outcome {
        if (!decrypted) return Outcome.DROPPED_UNDECRYPTED
        // A push that arrives after unpairing (a late delivery) is not shown.
        if (!paired) return Outcome.DROPPED_UNPAIRED
        return when (val p = PayloadV2Parser.parse(content)) {
            is Push.Rejected -> Outcome.REJECTED
            Push.Probe -> Outcome.PROBE
            is Push.Withdraw -> { sink.withdraw(p.tag); Outcome.WITHDRAWN }
            is Push.Show -> {
                // A tap never opens an upload, gate open or closed (AttachmentGate.isUploadRoute).
                var payload = p.payload.path?.let { if (AttachmentGate.isUploadRoute(it)) p.payload.copy(path = null) else null }
                    ?: p.payload
                val ask = payload.ask
                if (!canSign && ask != null) payload = payload.copy(ask = ask.copy(actions = emptyList()))
                sink.show(payload)
                Outcome.SHOWN
            }
        }
    }
}
