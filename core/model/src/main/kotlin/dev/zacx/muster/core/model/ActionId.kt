package dev.zacx.muster.core.model

/**
 * The action ids a payload-v2 push may name, each mapped to ONE fixed signed
 * route on muster. The server names an id, never a path, so a push cannot
 * make the app POST anywhere else. This table must match muster's route
 * ledger (server change 26 in muster's docs/design/android.md).
 *
 * `{id}` / `{name}` are filled from the notification's own target, which the
 * app re-reads from muster before showing the action.
 */
enum class ActionId(val wire: String, val route: String) {
    SEND_NOW("send_now", "/api/device/actions/send-queue/{id}/send-now"),
    CANCEL_QUEUED("cancel", "/api/device/actions/send-queue/{id}/cancel"),
    QUEUE("queue", "/api/device/actions/send-queue"),
    ANSWER("answer", "/api/device/actions/attention/asks/{id}/answer"),
    DISMISS("dismiss", "/api/device/actions/attention/asks/{id}/cancel"),
    APPROVE("approve", "/api/device/actions/plugins/drafts/{id}/approve"),
    REJECT("reject", "/api/device/actions/plugins/drafts/{id}/reject"),
    MESSAGE_AGENT("reply", "/api/device/actions/agents/{name}/send");

    companion object {
        /** Unknown ids are dropped, never guessed at. */
        fun fromWire(wire: String): ActionId? = entries.firstOrNull { it.wire == wire }
    }
}
