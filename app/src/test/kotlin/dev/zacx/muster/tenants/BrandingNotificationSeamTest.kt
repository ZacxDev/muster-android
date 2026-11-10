package dev.zacx.muster.tenants

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.core.model.PayloadV2
import dev.zacx.muster.core.net.InstanceApi
import dev.zacx.muster.feature.notify.AndroidNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * 🔴 The seam between the branding cache and the notifier: real stored icons,
 * the real bounded decoder and its in-memory cache, and each tenant's own
 * notifier. Each component is tested on its own elsewhere; this pins the
 * RELATIONSHIP: the notification drawn for tenant B carries B's logo or
 * none, never A's, with both cached at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class BrandingNotificationSeamTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val a = BrandServer()
    private val b = BrandServer()
    private val c = BrandServer()
    private lateinit var branding: TenantBranding
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun tenant(id: String, s: BrandServer) =
        TenantRecord(id, Tenants.labelFor(s.origin), Tenants.PALETTE[1], "tenant-$id", DeviceRecord(origin = s.origin))

    private fun realPng(colour: Int): ByteArray {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        a.start(); b.start(); c.start()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        branding = TenantBranding(tmp.root, { InstanceApi(it) }, io = Dispatchers.Unconfined)
        // A and B use the SAME logo etag, with different pictures; C has no branding (an older muster).
        a.instance = { BrandServer.json(BrandServer.branded("Team A", "v1")) }
        a.icon = { BrandServer.png(realPng(Color.RED)) }
        b.instance = { BrandServer.json(BrandServer.branded("Team B", "v1")) }
        b.icon = { BrandServer.png(realPng(Color.BLUE)) }
        c.instance = { MockResponse.Builder().code(404).build() }
    }

    @After
    fun tearDown() {
        a.server.close(); b.server.close(); c.server.close()
    }

    private fun notifier(t: TenantRecord) = AndroidNotifier(app, t.notifyTenant(), branding = branding.notifyBranding(t, scope)) { tag, _ ->
        PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
    }

    private fun largeIconColour(tag: String): Int? {
        val n: Notification = shadowOf(nm).activeNotifications.single { it.tag == tag }.notification
        val bmp = (n.getLargeIcon()?.loadDrawable(app) as? BitmapDrawable)?.bitmap ?: return null
        return bmp.getPixel(bmp.width / 2, bmp.height / 2)
    }

    @Test
    fun eachTenantsNotificationCarriesItsOwnLogo() {
        val ta = tenant("ta", a); val tb = tenant("tb", b); val tc = tenant("tc", c)
        runBlocking { branding.refreshAll(Tenants(current = "ta", all = listOf(ta, tb, tc), migrated = true)) }
        // Draw A first, so its bitmap is in the decoder's cache when B and C are drawn.
        notifier(ta).show(PayloadV2("review", "1", "task-1", "A", "one", null))
        notifier(tb).show(PayloadV2("review", "1", "task-1", "A", "two", null))
        notifier(tc).show(PayloadV2("review", "1", "task-1", "A", "three", null))
        assertEquals(Color.RED, largeIconColour("ta:task-1"))
        assertEquals(Color.BLUE, largeIconColour("tb:task-1"))
        assertNull(largeIconColour("tc:task-1"))
    }

    /** A push's new icon etag reaches the cache through the notifier, and the NEXT notification carries the new logo. */
    @Test
    fun aPushsNewIconEtagUpdatesTheNextNotification() {
        val ta = tenant("ta", a)
        runBlocking { branding.refresh(ta) }
        a.instance = { BrandServer.json(BrandServer.branded("Team A", "v2")) }
        a.icon = { BrandServer.png(realPng(Color.GREEN)) }
        notifier(ta).show(PayloadV2("review", "1", "task-1", "A", "one", null, iconEtag = "v2"))
        notifier(ta).show(PayloadV2("review", "2", "task-2", "A", "two", null))
        assertEquals(Color.GREEN, largeIconColour("ta:task-2"))
    }
}
