package dev.zacx.muster.feature.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Notification channel ids (muster docs/design/android.md, "Notifications").
 * Channel ids are permanent once shipped: users' per-channel settings key on them.
 *
 * Phase 1 creates the two channels the existing events use. Conversations,
 * queued messages, the agent run and fleet status are created by the phases
 * that send to them, so the system settings never list a channel that
 * nothing posts to.
 */
object Channels {
    const val ASKS = "asks"
    const val CONVERSATIONS = "conversations"
    const val QUEUE = "queue"
    const val TASKS = "tasks"
    const val AGENT_RUN = "agent_run"
    const val FLEET = "fleet"

    /**
     * The channel a payload-v2 `type` posts to. Asks and approvals:
     * `attention`, `plugin`, `connector`. Everything else muster sends today
     * (`review`, `created`, `comment`, `agent`, and the account page's test
     * notification) is Tasks.
     */
    fun forType(type: String): String = when (type) {
        "attention", "plugin", "connector" -> ASKS
        else -> TASKS
    }

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(ASKS, context.getString(R.string.channel_asks), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_asks_desc) },
                NotificationChannel(TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_tasks_desc) },
            ),
        )
    }
}
