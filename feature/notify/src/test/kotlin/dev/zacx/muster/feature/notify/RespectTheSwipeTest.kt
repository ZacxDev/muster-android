package dev.zacx.muster.feature.notify

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.PayloadV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * RESPECT THE SWIPE (DismissedTags; muster docs/push.md "When a notification
 * is withdrawn"): muster replaces a notification in place with an `update`
 * push (the fleet's "N proposals waiting" with a new count). One the USER
 * swiped away is not brought back by it.
 *
 * The swipe is the real pipe: the posted notification's own delete intent,
 * delivered to [DismissReceiver] as Android would on a swipe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class RespectTheSwipeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: AndroidNotifier

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifier = AndroidNotifier(app, LEGACY) { tag, _ ->
            PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private fun alert(title: String) = PayloadV2("attention", "fleet-proposals", "fleet-proposals", title, "b", "/tasks")
    private fun update(title: String) = alert(title).copy(update = true)
    private fun showing() = shadowOf(nm).activeNotifications.filter { it.tag == "default:fleet-proposals" }
    private fun titleShowing() = showing().singleOrNull()?.notification?.extras?.getString(android.app.Notification.EXTRA_TITLE)

    /** The user swipes the notification under the tag away: its delete intent, delivered. */
    private fun swipe() {
        val n = showing().single().notification
        val pi = assertNotNull("the notification has no delete intent", n.deleteIntent).let { n.deleteIntent }
        val sent = shadowOf(pi).savedIntent
        nm.cancel(showing().single().tag, AndroidNotifier.ID)
        DismissReceiver().onReceive(app, sent)
    }

    private val dismissed get() = DismissedTags.of(app).isDismissed("default:fleet-proposals")

    @Test
    fun anUpdateForADismissedTagIsDropped() {
        notifier.show(alert("2 proposals waiting"))
        swipe()
        assertTrue("the swipe was not recorded", dismissed)
        notifier.show(update("1 proposal waiting"))
        assertEquals("an update brought back a notification the user swiped away", 0, showing().size)
    }

    @Test
    fun anAlertForADismissedTagShowsAndClearsTheMark() {
        notifier.show(alert("2 proposals waiting"))
        swipe()
        notifier.show(alert("3 proposals waiting"))
        assertEquals("3 proposals waiting", titleShowing())
        assertFalse("the alert did not clear the dismissal", dismissed)
        notifier.show(update("2 proposals waiting"))
        assertEquals("after the alert, an update replaces it again", "2 proposals waiting", titleShowing())
    }

    @Test
    fun aWithdrawalClearsTheMark() {
        notifier.show(alert("2 proposals waiting"))
        swipe()
        notifier.withdraw("fleet-proposals")
        assertFalse("the withdrawal did not clear the dismissal", dismissed)
        notifier.show(update("1 proposal waiting"))
        assertEquals("1 proposal waiting", titleShowing())
    }

    @Test
    fun theAppsOwnCancelIsNotADismissal() {
        notifier.show(alert("2 proposals waiting"))
        notifier.withdraw("fleet-proposals")
        notifier.show(alert("2 proposals waiting"))
        nm.cancel("default:fleet-proposals", AndroidNotifier.ID)
        assertFalse("the app's own cancel was recorded as a dismissal", dismissed)
        notifier.show(update("1 proposal waiting"))
        assertEquals("an update after the app's own cancel is posted (silently)", "1 proposal waiting", titleShowing())
    }

    @Test
    fun aDismissalIsPerTenant() {
        val other = NotifyTenant("t2", "Other server", 0, false)
        val two = AndroidNotifier(app, other) { tag, _ ->
            PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
        }
        notifier.show(alert("2 proposals waiting"))
        swipe()
        two.show(alert("4 proposals waiting"))
        two.show(update("3 proposals waiting"))
        assertEquals("3 proposals waiting", shadowOf(nm).activeNotifications.single { it.tag == "t2:fleet-proposals" }
            .notification.extras.getString(android.app.Notification.EXTRA_TITLE))
    }
}
