package dev.zacx.muster.feature.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The notifications the USER dismissed, by namespaced tag (`<tenant>:<server
 * tag>`, [NotifyTenant.namespace]), so each server's are its own.
 *
 * Muster replaces a notification in place with an `update` push (a new count,
 * no alert). Posting one for a tag the user swiped away would bring it back,
 * silently or not, so [AndroidNotifier] DROPS an update for a dismissed tag
 * (muster's operator decision "respect the swipe"). A push that is not an
 * update — a genuine new alert — shows again and clears the mark; so does a
 * withdrawal.
 *
 * 🔴 ONLY THE USER'S DISMISSAL MARKS A TAG: the mark is written by
 * [DismissReceiver], reached only through a notification's delete intent,
 * which Android sends when the user swipes it or clears all — never for the
 * app's own cancel (a withdrawal, a tapped outcome, a removed tenant) or a
 * tap's auto-cancel.
 */
class DismissedTags private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDismissed(tag: String): Boolean = prefs.getBoolean(tag, false)

    fun mark(tag: String) {
        prefs.edit().putBoolean(tag, true).apply()
    }

    fun clear(tag: String) {
        if (prefs.contains(tag)) prefs.edit().remove(tag).apply()
    }

    /** Clears every mark under [prefix] (a removed tenant's). */
    fun clearPrefix(prefix: String) {
        val gone = prefs.all.keys.filter { it.startsWith(prefix) }
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove(it) } }.apply()
    }

    companion object {
        private const val PREFS = "dev.zacx.muster.notify.dismissed"

        fun of(context: Context) = DismissedTags(context)

        /** The extra naming the dismissed notification's namespaced tag. */
        const val EXTRA_TAG = "dev.zacx.muster.notify.DISMISSED_TAG"

        /**
         * The delete intent for the notification posted under namespaced
         * [tag]: an explicit, immutable broadcast to [DismissReceiver]. The tag
         * is in the DATA too, so two tags never share one PendingIntent.
         */
        fun deleteIntent(context: Context, tag: String): PendingIntent {
            val i = Intent(context, DismissReceiver::class.java)
                .setData(android.net.Uri.Builder().scheme("muster-dismiss").appendPath(tag).build())
                .putExtra(EXTRA_TAG, tag)
            return PendingIntent.getBroadcast(context, tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/** Records the user's dismissal of a notification ([DismissedTags]). Not exported. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(DismissedTags.EXTRA_TAG) ?: return
        DismissedTags.of(context).mark(tag)
    }
}
