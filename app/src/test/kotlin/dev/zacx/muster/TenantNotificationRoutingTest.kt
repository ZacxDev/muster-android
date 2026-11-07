package dev.zacx.muster

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.feature.push.PushHandler
import dev.zacx.muster.tenants.TapRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 🔴 Notifications from every server, labelled, and a tap that opens the
 * right server (operator decision 3; muster docs/design/multi-tenant.md,
 * "Notifications, taps and deep links"), through the app's own wiring: the
 * push router and per-tenant notifier MusterApp builds, the tap PendingIntent
 * it builds, and the activity's routing of that intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = MusterApp::class)
class TenantNotificationRoutingTest {
    private val app: MusterApp = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val token = "mdv_" + "T".repeat(43)

    private val home = TenantRecord(Tenants.DEFAULT, "home.example.test", Tenants.PALETTE[0], Tenants.DEFAULT_PROFILE,
        DeviceRecord(origin = "https://home.example.test", token = token, deviceId = 1, keyAlias = "k"))
    private val work = TenantRecord("t2", "work.example.test", Tenants.PALETTE[1], "tenant-t2",
        DeviceRecord(origin = "https://work.example.test", token = token, deviceId = 2, keyAlias = "k2"))
    /** Home is in view; work's push arrives. */
    private val tenants = Tenants(current = home.id, all = listOf(home, work), migrated = true)

    private fun push(tag: String, path: String) =
        """{"v":2,"type":"attention","id":"41","tag":"$tag","title":"chief is asking","body":"Ship it?","url":"$path"}""".encodeToByteArray()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(tag: String): Notification = shadowOf(nm).activeNotifications.single { it.tag == tag }.notification

    @Test
    fun aPushFromTheServerNotInViewIsLabelledAndItsTapSwitchesToItAndOpensTheItem() {
        val out = app.pushRouter.handle("t2", push("attention-ask-41", "/attention/open/ask-41"), decrypted = true, tenants = tenants)
        assertEquals(PushHandler.Outcome.SHOWN, out)

        val n = posted("t2:attention-ask-41")
        // The label: which server is asking, on the notification and its lock-screen version.
        assertEquals("work.example.test", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(Tenants.PALETTE[1], n.color)

        // The tap: an intent to MainActivity naming the tenant and the path.
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, tap.component!!.className)
        assertEquals("t2", tap.getStringExtra(MusterApp.EXTRA_TENANT))
        assertEquals("/attention/open/ask-41", tap.getStringExtra(MainActivity.EXTRA_PATH))
        assertEquals("muster-tap", tap.data!!.scheme)
        assertEquals("t2", tap.data!!.authority)

        // ... which MainActivity routes as: switch to work, open the item on work's origin.
        val target = TapRouting.resolve(tenants, tap.getStringExtra(MusterApp.EXTRA_TENANT), tap.getStringExtra(MainActivity.EXTRA_PATH))!!
        assertEquals(work, target.tenant)
        assertEquals("/attention/open/ask-41", target.path)
        assertEquals(true, target.switched)
    }

    /** Equal tags from both servers: two notifications, two taps, each opening its own server. */
    @Test
    fun equalTagsFromTwoServersOpenTheirOwnServers() {
        app.pushRouter.handle("default", push("attention-ask-41", "/attention/open/a"), decrypted = true, tenants = tenants)
        app.pushRouter.handle("t2", push("attention-ask-41", "/attention/open/b"), decrypted = true, tenants = tenants)
        val a = posted("default:attention-ask-41")
        val b = posted("t2:attention-ask-41")
        assertNotEquals(a.contentIntent, b.contentIntent)
        assertEquals("default", shadowOf(a.contentIntent).savedIntent.getStringExtra(MusterApp.EXTRA_TENANT))
        assertEquals("/attention/open/a", shadowOf(a.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_PATH))
        assertEquals("t2", shadowOf(b.contentIntent).savedIntent.getStringExtra(MusterApp.EXTRA_TENANT))
        assertEquals("/attention/open/b", shadowOf(b.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_PATH))
        assertEquals("home.example.test", a.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
    }

    /** A button on work's notification is built for work: the step-up activity will use work's pairing only. */
    @Test
    fun anAskButtonCarriesItsServer() {
        val ask = """{"v":2,"type":"attention","tag":"attention-ask-41","title":"chief is asking","body":"Ship it?",
            "ask":{"id":41,"options":["ship it"]},"actions":[{"id":"ask_answer","target":"41","option":0},
            {"id":"ask_dismiss","label":"Dismiss","target":"41"}]}""".encodeToByteArray()
        app.pushRouter.handle("t2", ask, decrypted = true, tenants = tenants)
        val button = posted("t2:attention-ask-41").actions.first().actionIntent
        assertEquals("t2", shadowOf(button).savedIntent.getStringExtra("dev.zacx.muster.stepup.TENANT"))
    }
}
