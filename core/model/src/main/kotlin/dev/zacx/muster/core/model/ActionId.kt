package dev.zacx.muster.core.model

/**
 * The action ids a payload-v2 push may name, each mapped to ONE fixed signed
 * route on muster. The server names an id, never a path, so a push cannot
 * make the app POST anywhere else.
 *
 * The ids are DEFINED here. muster's payload v2 must emit exactly these
 * strings, and muster's design doc (docs/design/android.md) and its
 * server-side route ledger must match this table. Each id names its object
 * and its verb (`queue_cancel`, `ask_dismiss`), so one id can never mean two
 * routes: a bare "cancel" would be ambiguous between a queued message and an
 * ask.
 *
 * `{id}` / `{name}` are filled from the action's target. For an ask the
 * target must be the ask's own id in the same push (AskPushParser), checked
 * again as a decimal id before any request (DeviceApi).
 */
enum class ActionId(val wire: String, val route: String) {
    QUEUE_SEND_NOW("queue_send_now", "/api/device/actions/send-queue/{id}/send-now"),
    QUEUE_CANCEL("queue_cancel", "/api/device/actions/send-queue/{id}/cancel"),
    SESSION_QUEUE("session_queue", "/api/device/actions/send-queue"),
    ASK_ANSWER("ask_answer", "/api/device/actions/attention/asks/{id}/answer"),
    ASK_DISMISS("ask_dismiss", "/api/device/actions/attention/asks/{id}/cancel"),
    DRAFT_APPROVE("draft_approve", "/api/device/actions/plugins/drafts/{id}/approve"),
    DRAFT_REJECT("draft_reject", "/api/device/actions/plugins/drafts/{id}/reject"),
    AGENT_SEND("agent_send", "/api/device/actions/agents/{name}/send");

    companion object {
        /** Unknown ids are dropped, never guessed at. */
        fun fromWire(wire: String): ActionId? = entries.firstOrNull { it.wire == wire }
    }
}
