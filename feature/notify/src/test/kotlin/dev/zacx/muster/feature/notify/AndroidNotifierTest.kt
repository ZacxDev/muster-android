package dev.zacx.muster.feature.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.PayloadV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real NotificationManager path, on Robolectric's shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class AndroidNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val taps = mutableListOf<Pair<String, String?>>()
    private lateinit var notifier: AndroidNotifier

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifier = AndroidNotifier(app) { tag, path ->
            taps += tag to path
            PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private fun posted() = shadowOf(nm).allNotifications

    @Test
    fun anAskIsPostedPrivateOnItsChannelWithAWhoWherePublicVersion() {
        notifier.show(PayloadV2("attention", "9", "att-9", "Agent ada", "in muster", "/attention"))
        val n: Notification = posted().single()
        assertEquals(Channels.ASKS, n.channelId)
        assertEquals("Agent ada", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("in muster", n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val pub = assertNotNull(n.publicVersion).let { n.publicVersion }
        assertEquals("Agent ada", pub.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("att-9" to "/attention"), taps)
        assertEquals("att-9", shadowOf(nm).activeNotifications.single().tag)
    }

    @Test
    fun taskEventsGoToTasks() {
        notifier.show(PayloadV2("review", "42", "task-42", "Agent ada", "Task 42", "/tasks/42"))
        assertEquals(Channels.TASKS, posted().single().channelId)
    }

    /** One notification per tag: a newer push replaces; `resolved` cancels by tag. */
    @Test
    fun sameTagReplacesAndResolvedWithdraws() {
        notifier.show(PayloadV2("review", "42", "task-42", "A", "one", null))
        notifier.show(PayloadV2("review", "42", "task-42", "A", "two", null))
        notifier.show(PayloadV2("review", "43", "task-43", "B", "three", null))
        assertEquals(2, shadowOf(nm).activeNotifications.size)
        notifier.withdraw("task-42")
        assertEquals(listOf("task-43"), shadowOf(nm).activeNotifications.map { it.tag })
    }

    @Test
    fun nothingIsPostedWhenNotificationsAreOff() {
        shadowOf(nm).setNotificationsEnabled(false)
        notifier.show(PayloadV2("review", "42", "task-42", "A", "one", null))
        assertEquals(0, posted().size)
    }

    @Test
    fun phaseOneCreatesExactlyTheChannelsItPostsTo() {
        Channels.ensure(app)
        assertEquals(setOf(Channels.ASKS, Channels.TASKS), nm.notificationChannels.map { it.id }.toSet())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Channels.ASKS).importance)
    }
}
