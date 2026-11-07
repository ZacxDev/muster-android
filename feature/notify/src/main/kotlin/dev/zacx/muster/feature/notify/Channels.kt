package dev.zacx.muster.feature.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context

/**
 * The muster server a notification belongs to, as the notifier needs it
 * (muster docs/design/multi-tenant.md, "Notifications, taps and deep links"):
 * its local [id], the [label] every notification shows as its subtext, and
 * its [colour].
 *
 * [legacy] is the migrated single-server tenant: its channels keep the ids
 * the app used before multi-tenant (`asks`, `tasks`), so the user's channel
 * settings survive the upgrade. Every other tenant's channels are
 * `<id>.asks` and so on.
 */
data class NotifyTenant(val id: String, val label: String, val colour: Int, val legacy: Boolean) {
    init {
        require(id.isNotEmpty() && ':' !in id) { "a tenant id is non-empty and has no ':'" }
        require(label.isNotBlank()) { "every tenant's notifications carry a label" }
    }

    /** `<tenant>:<server tag>`: equal tags from two servers never replace each other. */
    fun namespace(serverTag: String): String = "$id:$serverTag"

    /** The channel id for one of [Channels]' base ids. */
    fun channel(base: String): String = if (legacy) base else "$id.$base"

    /** This tenant's channel group, so the user can mute one server in system settings. */
    val group: String get() = "tenant.$id"
}

/**
 * Notification channel ids (muster docs/design/android.md, "Notifications").
 * Channel ids are permanent once shipped: users' per-channel settings key on them.
 *
 * Phase 1 creates the two channels the existing events use, per tenant, in
 * that tenant's group. Conversations, queued messages, the agent run and
 * fleet status are created by the phases that send to them, so the system
 * settings never list a channel that nothing posts to.
 */
object Channels {
    const val ASKS = "asks"
    const val CONVERSATIONS = "conversations"
    const val QUEUE = "queue"
    const val TASKS = "tasks"
    const val AGENT_RUN = "agent_run"
    const val FLEET = "fleet"

    /**
     * The base channel a payload-v2 `type` posts to. Asks and approvals:
     * `attention`, `plugin`, `connector`. Everything else muster sends today
     * (`review`, `created`, `comment`, `agent`, and the account page's test
     * notification) is Tasks.
     */
    fun forType(type: String): String = when (type) {
        "attention", "plugin", "connector" -> ASKS
        else -> TASKS
    }

    /**
     * Creates [t]'s group (named by its label) and its two channels in it.
     * For the legacy tenant this moves the pre-upgrade `asks` and `tasks`
     * channels into the group: `setGroup` is allowed on an existing channel
     * that is not yet part of a group, and their other settings are kept.
     */
    fun ensure(context: Context, t: NotifyTenant) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(t.group, t.label))
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(t.channel(ASKS), context.getString(R.string.channel_asks), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_asks_desc); group = t.group },
                NotificationChannel(t.channel(TASKS), context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_tasks_desc); group = t.group },
            ),
        )
    }

    /** A removed tenant: its group and every channel in it go. */
    fun delete(context: Context, t: NotifyTenant) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (base in listOf(ASKS, TASKS, CONVERSATIONS, QUEUE, AGENT_RUN, FLEET)) nm.deleteNotificationChannel(t.channel(base))
        nm.deleteNotificationChannelGroup(t.group)
    }
}
