package dev.zacx.muster.feature.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.PayloadV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Notifications from several muster servers (muster
 * docs/design/multi-tenant.md, "Notifications, taps and deep links"): each is
 * labelled with its server, posts to that server's channels in its group, and
 * is tagged per server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class TenantNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val work = NotifyTenant("t2", "work.example.test", 0xFF2BB673.toInt(), legacy = false)
    private val taps = mutableListOf<Triple<String, String, String?>>()

    private fun notifier(t: NotifyTenant) = AndroidNotifier(app, t) { tag, path ->
        taps += Triple(t.id, tag, path)
        PendingIntent.getActivity(app, (t.id + tag).hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun active() = shadowOf(nm).activeNotifications
    private fun posted(tag: String): Notification = active().single { it.tag == tag }.notification

    /** 🔴 Every notification carries its server's label, on the lock-screen public version too, and its colour. */
    @Test
    fun everyNotificationIsLabelledWithItsServer() {
        notifier(work).show(PayloadV2("review", "42", "task-42", "Agent ada", "Task 42", "/tasks/42"))
        val n = posted("t2:task-42")
        assertEquals("work.example.test", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals("work.example.test", n.publicVersion.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(0xFF2BB673.toInt(), n.color)

        // The migrated server's notifications are labelled too.
        notifier(LEGACY).show(PayloadV2("attention", "9", "att-9", "Agent ada", "Ship it?", "/attention"))
        assertEquals("m.example.test", posted("default:att-9").extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
    }

    /** Each server's channels are in its own group; the migrated server keeps the pre-upgrade ids. */
    @Test
    fun eachServerPostsToItsOwnChannelsInItsOwnGroup() {
        notifier(work).show(PayloadV2("attention", "9", "att-9", "A", "b", null))
        notifier(LEGACY).show(PayloadV2("attention", "9", "att-9", "A", "b", null))
        assertEquals("t2.asks", posted("t2:att-9").channelId)
        assertEquals(Channels.ASKS, posted("default:att-9").channelId)
        val channels: Map<String, NotificationChannel> = nm.notificationChannels.associateBy { it.id }
        assertEquals("tenant.t2", channels.getValue("t2.asks").group)
        assertEquals("tenant.default", channels.getValue(Channels.ASKS).group)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channels.getValue("t2.asks").importance)
        assertEquals("work.example.test", nm.getNotificationChannelGroup("tenant.t2").name.toString())
        assertEquals(setOf("asks", "tasks", "t2.asks", "t2.tasks"), channels.keys)
    }

    /** The same server tag from two servers is two notifications, and a tap is built for its own server. */
    @Test
    fun equalTagsFromTwoServersAreTwoNotifications() {
        notifier(LEGACY).show(PayloadV2("attention", "41", "attention-ask-41", "chief", "one", "/attention/open/a"))
        notifier(work).show(PayloadV2("attention", "41", "attention-ask-41", "chief", "two", "/attention/open/b"))
        assertEquals(setOf("default:attention-ask-41", "t2:attention-ask-41"), active().map { it.tag }.toSet())
        assertEquals(
            listOf(Triple("default", "default:attention-ask-41", "/attention/open/a"), Triple("t2", "t2:attention-ask-41", "/attention/open/b")),
            taps,
        )
    }

    /** A withdrawal clears its own server's notification only. */
    @Test
    fun aWithdrawalClearsOnlyItsServersNotification() {
        notifier(LEGACY).show(PayloadV2("attention", "41", "attention-ask-41", "chief", "one", null))
        notifier(work).show(PayloadV2("attention", "41", "attention-ask-41", "chief", "two", null))
        notifier(work).withdraw("attention-ask-41")
        assertEquals(listOf("default:attention-ask-41"), active().map { it.tag })
    }

    /** The migrated server's withdrawal also clears a notification drawn before the upgrade (bare tag); another server's does not. */
    @Test
    fun theMigratedServerAlsoClearsItsPreUpgradeTag() {
        nm.notify("attention-ask-41", AndroidNotifier.ID, Notification.Builder(app, "x").setSmallIcon(R.drawable.ic_stat_muster).build())
        notifier(work).withdraw("attention-ask-41")
        assertEquals(listOf("attention-ask-41"), active().map { it.tag })
        notifier(LEGACY).withdraw("attention-ask-41")
        assertEquals(emptyList<String>(), active().map { it.tag })
    }

    /** A removed server: its notifications and channel group go; the other server's stay. */
    @Test
    fun removingAServerClearsOnlyItsNotificationsAndChannels() {
        notifier(LEGACY).show(PayloadV2("review", "1", "task-1", "A", "one", null))
        notifier(work).show(PayloadV2("review", "1", "task-1", "A", "two", null))
        notifier(work).show(PayloadV2("review", "2", "task-2", "A", "three", null))
        notifier(work).cancelAll()
        Channels.delete(app, work)
        assertEquals(listOf("default:task-1"), active().map { it.tag })
        assertNull(nm.getNotificationChannel("t2.tasks"))
        assertEquals(Channels.TASKS, nm.getNotificationChannel(Channels.TASKS).id)
    }

    @Test
    fun aTenantWithoutALabelCannotExist() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NotifyTenant("t3", " ", 0, legacy = false) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NotifyTenant("a:b", "x", 0, legacy = false) }
    }
}
