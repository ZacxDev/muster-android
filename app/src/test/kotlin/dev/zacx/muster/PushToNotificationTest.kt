package dev.zacx.muster

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.feature.notify.AndroidNotifier
import dev.zacx.muster.feature.push.PushHandler
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The design's required test (docs/design/android.md, "Only decrypted pushes
 * are trusted"): feed an undecryptable message through the app's real push
 * handler and notifier, and check that nothing is drawn and nothing is
 * withdrawn. The same bytes, decrypted, are drawn: the control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class PushToNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var handler: PushHandler

    private val body = """{"v":2,"type":"attention","id":"9","tag":"att-9","title":"Agent ada","body":"in muster"}""".encodeToByteArray()
    private val withdraw = """{"v":2,"type":"resolved","id":"9","tag":"att-9"}""".encodeToByteArray()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        handler = PushHandler(
            AndroidNotifier(app) { tag, _ ->
                PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
            },
        )
    }

    @Test
    fun anUndecryptableMessageDrawsNothing() {
        assertEquals(PushHandler.Outcome.DROPPED_UNDECRYPTED, handler.handle(body, decrypted = false, paired = true))
        assertEquals(0, shadowOf(nm).allNotifications.size)

        // Control: the same bytes, decrypted, are drawn.
        assertEquals(PushHandler.Outcome.SHOWN, handler.handle(body, decrypted = true, paired = true))
        assertEquals(1, shadowOf(nm).activeNotifications.size)

        // A forged, undecrypted withdrawal does not remove it.
        assertEquals(PushHandler.Outcome.DROPPED_UNDECRYPTED, handler.handle(withdraw, decrypted = false, paired = true))
        assertEquals(1, shadowOf(nm).activeNotifications.size)
        assertEquals(PushHandler.Outcome.WITHDRAWN, handler.handle(withdraw, decrypted = true, paired = true))
        assertEquals(0, shadowOf(nm).activeNotifications.size)
    }
}
