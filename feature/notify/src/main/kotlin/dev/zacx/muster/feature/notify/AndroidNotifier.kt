package dev.zacx.muster.feature.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.model.AskPush
import dev.zacx.muster.core.model.NotificationSink
import dev.zacx.muster.core.model.PayloadV2

/**
 * Draws payload-v2 notifications.
 *
 * - The lines as sent: title and body are muster's own (WHO and WHERE for most
 *   events; the question and its options for an ask and a held question,
 *   muster's operator decision "Full text always"). The body is drawn whole
 *   with BigTextStyle, on the lock screen too, and the app adds no text of its
 *   own to it. A waiting session's body is muster's summary line, so it stays
 *   summary only.
 * - An ask (`ask` in the payload) is VISIBILITY_PUBLIC: its full text and its
 *   buttons show on the lock screen (a tap still needs the unlock and the
 *   step-up key). Everything else is VISIBILITY_PRIVATE with a public version
 *   of the same lines.
 * - An ask's buttons ([AskPush.buttons]) are PendingIntents from [action],
 *   which the app module points straight at the step-up activity.
 * - One notification per tag: a later push with the same tag replaces it, and
 *   a `resolved` withdrawal cancels it by tag, as the web worker does. A
 *   re-push marked `update` replaces it WITHOUT alerting again
 *   ([onlyAlertOnce]); a first push alerts. An `update` re-push for a tag
 *   with nothing showing (the user swiped the first away, or it was
 *   withdrawn) is dropped: a question the user dismissed does not come back.
 * - [tap] builds the tap PendingIntent (immutable, explicit) for a path on
 *   the muster origin; the app module owns the activity it opens.
 */
class AndroidNotifier(
    private val context: Context,
    /** An ask button's PendingIntent; null draws no buttons. */
    private val action: ActionIntents? = null,
    private val tap: (tag: String, path: String?) -> PendingIntent,
) : NotificationSink {

    /** Builds the PendingIntent for button [index] of the notification under [tag]. */
    fun interface ActionIntents {
        fun intent(tag: String, index: Int, title: String, path: String?, a: AskAction): PendingIntent
    }

    override fun show(payload: PayloadV2) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tag = tagOf(payload)
        // An update with nothing showing (the user swiped the first away, or
        // the first push never arrived) is posted SILENT: onlyAlertOnce only
        // quiets a notification that is still showing, and dropping it instead
        // would lose a question whose first push was lost.
        val silent = payload.update && active(tag) == null
        Channels.ensure(context)
        val channel = Channels.forType(payload.type)
        val b = base(channel, payload)
            .setSilent(silent)
            .setContentIntent(tap(tag, payload.path))
            .setAutoCancel(true)
            .addExtras(Bundle().apply { putString(EXTRA_QUESTION, payload.body) })
        val ask = payload.ask
        if (ask != null) {
            b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            val intents = action
            if (intents != null) {
                ask.buttons().forEachIndexed { i, a ->
                    b.addAction(NotificationCompat.Action.Builder(0, a.label, intents.intent(tag, i, payload.title, payload.path, a)).build())
                }
            }
        } else {
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(base(channel, payload).build())
        }
        post(nm, tag, b.build())
    }

    /**
     * What a tapped button came to, shown in the notification under [tag]:
     * [AskOutcome.Done] removes it; [AskOutcome.Gone] keeps the question with
     * [AskOutcome.Gone.message] and no buttons; [AskOutcome.Failed] keeps the
     * question AND its buttons, with the message above the question, so the
     * user can try again. Never alerts. When the notification is no longer
     * showing: a Gone outcome posts nothing (the question is settled and the
     * user already let it go); a Failed one posts a SILENT notification that
     * says it (tapping it opens [path]).
     */
    fun showOutcome(tag: String, title: String, path: String?, outcome: AskOutcome) {
        val nm = NotificationManagerCompat.from(context)
        if (outcome is AskOutcome.Done) {
            nm.cancel(tag, ID)
            return
        }
        val message = when (outcome) {
            is AskOutcome.Gone -> outcome.message
            is AskOutcome.Failed -> outcome.message
            AskOutcome.Done -> return
        }
        val shown = active(tag)
        if (shown == null && outcome is AskOutcome.Gone) return
        // The body as muster sent it, never a previous outcome's text: two
        // failures in a row say the latest reason once.
        val question = shown?.extras?.getString(EXTRA_QUESTION)
        val text = if (question.isNullOrEmpty()) message else "$message\n\n$question"
        val n = if (shown != null && outcome is AskOutcome.Failed) {
            // The same notification, buttons and all, with the reason first.
            Notification.Builder.recoverBuilder(context, shown)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .build()
        } else {
            // Gone over a showing question (no buttons: it cannot be acted
            // on), or a failure with nothing showing any more: a new
            // notification that says it. Silent either way: with nothing
            // showing, onlyAlertOnce has nothing to replace and would alert.
            // (A recovered builder cannot drop actions: build() keeps the old
            // ones when the new list is empty.)
            Channels.ensure(context)
            NotificationCompat.Builder(context, Channels.ASKS)
                .setSmallIcon(R.drawable.ic_stat_muster)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(tap(tag, path))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
        }
        post(nm, tag, n)
    }

    private fun post(nm: NotificationManagerCompat, tag: String, n: Notification) {
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            nm.notify(tag, ID, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the post.
        }
    }

    /** The notification showing under [tag], or null. */
    private fun active(tag: String): Notification? =
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications?.firstOrNull { it.tag == tag && it.id == ID }?.notification

    override fun withdraw(tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, ID)
    }

    private fun base(channel: String, p: PayloadV2) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_muster)
        .setContentTitle(p.title)
        .setContentText(p.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
        .setCategory(if (channel == Channels.ASKS) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
        .setOnlyAlertOnce(onlyAlertOnce(p))

    companion object {
        /** Every notification uses this id; the tag tells them apart. */
        const val ID = 1

        /** The extra holding the body as muster sent it, for an outcome's text. */
        private const val EXTRA_QUESTION = "dev.zacx.muster.QUESTION"

        /**
         * A re-push of the same tag marked `update` (an ask's suggested
         * replies arrived) must not buzz a second time; a first push alerts.
         */
        fun onlyAlertOnce(p: PayloadV2): Boolean = p.update

        fun tagOf(p: PayloadV2): String = p.tag ?: "muster:${p.type}:${p.id ?: p.title.hashCode()}"
    }
}
