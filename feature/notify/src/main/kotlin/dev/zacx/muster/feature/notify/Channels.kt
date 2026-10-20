package dev.zacx.muster.feature.notify

/**
 * Notification channel ids (muster docs/design/android.md, "Notifications").
 * Channel ids are permanent once shipped: users' per-channel settings key on them.
 */
object Channels {
    const val ASKS = "asks"
    const val CONVERSATIONS = "conversations"
    const val QUEUE = "queue"
    const val TASKS = "tasks"
    const val AGENT_RUN = "agent_run"
    const val FLEET = "fleet"
}
