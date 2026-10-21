package dev.zacx.muster.feature.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.zacx.muster.core.model.NotificationSink
import dev.zacx.muster.core.model.PayloadV2

/**
 * Draws payload-v2 notifications (Phase 1: the existing events, no actions).
 *
 * - WHO and WHERE only: payload v2 carries nothing else, and Phase 1 fetches
 *   nothing more. Every notification is still VISIBILITY_PRIVATE with a public
 *   version of the same WHO/WHERE lines, so when Phase 2 adds WHAT (only while
 *   unlocked) the lock-screen version is already in place.
 * - One notification per tag: a later push with the same tag replaces it, and
 *   a `resolved` withdrawal cancels it by tag, as the web worker does.
 * - [tap] builds the tap PendingIntent (immutable, explicit) for a path on
 *   the muster origin; the app module owns the activity it opens.
 */
class AndroidNotifier(
    private val context: Context,
    private val tap: (tag: String, path: String?) -> PendingIntent,
) : NotificationSink {

    override fun show(payload: PayloadV2) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        Channels.ensure(context)
        val tag = tagOf(payload)
        val channel = Channels.forType(payload.type)
        val public = base(channel, payload).build()
        val n = base(channel, payload)
            .setContentIntent(tap(tag, payload.path))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        try {
            nm.notify(tag, ID, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the post.
        }
    }

    override fun withdraw(tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, ID)
    }

    private fun base(channel: String, p: PayloadV2) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_muster)
        .setContentTitle(p.title)
        .setContentText(p.body)
        .setCategory(if (channel == Channels.ASKS) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
        .setOnlyAlertOnce(false)

    companion object {
        /** Every notification uses this id; the tag tells them apart. */
        const val ID = 1

        fun tagOf(p: PayloadV2): String = p.tag ?: "muster:${p.type}:${p.id ?: p.title.hashCode()}"
    }
}
