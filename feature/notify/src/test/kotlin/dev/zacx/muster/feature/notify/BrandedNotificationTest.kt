package dev.zacx.muster.feature.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.model.PayloadV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tenant's logo on its notifications (muster `/api/instance`): the large
 * icon when one is cached, on the lock-screen public version too; none
 * otherwise, and never a lost notification. The small icon stays the app's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30, 34])
class BrandedNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val teamA = NotifyTenant("ta", "muster.example.com", 0xFF2BB673.toInt(), legacy = false)
    private val teamB = NotifyTenant("tb", "team.example.org", 0xFFFF8A3D.toInt(), legacy = false)

    /** A logo whose size and colour are its fingerprint. */
    private val logoA: Bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    @Suppress("UNUSED_PARAMETER")
    private fun branding(t: NotifyTenant, icon: () -> Bitmap?) = AndroidNotifier.NotifyBranding { icon() }

    private fun notifier(t: NotifyTenant, b: AndroidNotifier.NotifyBranding?) = AndroidNotifier(
        app, t, tap = { tag, _ -> PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE) },
        branding = b,
    )

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(tag: String): Notification = shadowOf(nm).activeNotifications.single { it.tag == tag }.notification

    private fun largeIconOf(n: Notification): Bitmap? = (n.getLargeIcon()?.loadDrawable(app) as? BitmapDrawable)?.bitmap

    private fun assertIsLogoA(b: Bitmap?) {
        requireNotNull(b) { "no large icon" }
        assertEquals(7 to 5, b.width to b.height)
        assertEquals(Color.RED, b.getPixel(3, 2))
    }

    @Test
    fun theCachedLogoIsTheLargeIconOnTheLockScreenToo() {
        notifier(teamA, branding(teamA) { logoA }).show(PayloadV2("review", "42", "task-42", "Agent ada", "Task 42", "/tasks/42"))
        val n = posted("ta:task-42")
        assertIsLogoA(largeIconOf(n))
        assertIsLogoA(largeIconOf(n.publicVersion))
        // The small icon is still the app's.
        assertEquals(R.drawable.ic_stat_muster, n.smallIcon.resId)
    }

    /** An ask (VISIBILITY_PUBLIC, no separate public version) carries it too. */
    @Test
    fun anAskCarriesTheLogo() {
        notifier(teamA, branding(teamA) { logoA }).show(PayloadV2("attention", "9", "att-9", "chief", "Ship it?", null))
        assertIsLogoA(largeIconOf(posted("ta:att-9")))
    }

    /** No cached logo (an older server, none set, not fetched yet): the default look, no large icon. */
    @Test
    fun noCachedLogoIsNoLargeIcon() {
        notifier(teamB, branding(teamB) { null }).show(PayloadV2("review", "1", "task-1", "A", "b", null))
        assertNull(posted("tb:task-1").getLargeIcon())
        notifier(teamB, null).show(PayloadV2("review", "2", "task-2", "A", "b", null))
        assertNull(posted("tb:task-2").getLargeIcon())
    }

    /** 🔴 Each tenant's notifier shows its own logo: tenant A's never lands on tenant B's notification. */
    @Test
    fun eachServersNotificationCarriesOnlyItsOwnLogo() {
        notifier(teamA, branding(teamA) { logoA }).show(PayloadV2("review", "1", "task-1", "A", "one", null))
        notifier(teamB, branding(teamB) { null }).show(PayloadV2("review", "1", "task-1", "A", "two", null))
        assertIsLogoA(largeIconOf(posted("ta:task-1")))
        assertNull(posted("tb:task-1").getLargeIcon())
    }

    /** A lookup that throws is no icon; the notification is still drawn. */
    @Test
    fun aFailingLookupNeverLosesTheNotification() {
        notifier(teamA, branding(teamA) { error("disk gone") }).show(PayloadV2("review", "3", "task-3", "A", "b", null))
        assertNull(posted("ta:task-3").getLargeIcon())
    }

    /** A failed button's new notification (nothing showing any more) carries the logo too. */
    @Test
    fun anOutcomeNotificationCarriesTheLogo() {
        notifier(teamA, branding(teamA) { logoA }).showOutcome("ta:att-7", "chief", null, AskOutcome.Failed("no network"))
        assertIsLogoA(largeIconOf(posted("ta:att-7")))
    }
}
