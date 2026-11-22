package dev.zacx.muster

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * muster's QR sign-in hand-off (README, "Signing in on a computer with this
 * phone"): the /auth/qr page's intent:// link becomes a VIEW + BROWSABLE
 * intent for `https://<host>/auth/qr` naming this package. Read from the
 * merged manifest: exactly one filter on QrHandoffActivity, BROWSABLE, https
 * on any host, path `/auth/qr` exactly, and NOT autoVerify (muster servers
 * are the user's own: no host can be verified for this app).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class QrHandoffManifestTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val component = ComponentName(ctx.packageName, "dev.zacx.muster.QrHandoffActivity")

    private fun filters(): List<IntentFilter> = shadowOf(ctx.packageManager).getIntentFiltersForActivity(component)

    @Test
    fun theHandoffFilterIsBrowsableHttpsOnAnyHostAtAuthQr() {
        val f = filters().single()
        assertEquals(listOf(Intent.ACTION_VIEW), (0 until f.countActions()).map(f::getAction))
        assertEquals(
            setOf(Intent.CATEGORY_BROWSABLE, Intent.CATEGORY_DEFAULT),
            (0 until f.countCategories()).map(f::getCategory).toSet(),
        )
        assertEquals(listOf("https"), (0 until f.countDataSchemes()).map(f::getDataScheme))
        assertEquals(listOf("*"), (0 until f.countDataAuthorities()).map { f.getDataAuthority(it).host })
        val paths = (0 until f.countDataPaths()).map(f::getDataPath)
        assertEquals(listOf("/auth/qr"), paths.map { it.path })
        assertTrue("a literal path, not a prefix or pattern", paths.all { it.type == android.os.PatternMatcher.PATTERN_LITERAL })
    }

    @Test
    fun theHandoffFilterIsNotAutoVerify() {
        val f = filters().single()
        // IntentFilter.getAutoVerify() is hidden API; the android-all jar Robolectric runs has it.
        val autoVerify = IntentFilter::class.java.getMethod("getAutoVerify").invoke(f) as Boolean
        assertFalse(autoVerify)
    }

    @Test
    fun theActivityIsExportedAndKeptOutOfRecents() {
        val info = ctx.packageManager.getActivityInfo(component, 0)
        assertTrue("the browser starts it", info.exported)
        assertEquals("a task of its own, so MainActivity gets the hand-off as a SINGLE_TOP intent", "", info.taskAffinity.orEmpty())
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
    }

    /** The filter as the package manager reads it: the hand-off URL on any host resolves here, its neighbours do not. */
    @Test
    fun theFilterMatchesTheHandoffUrlOnly() {
        fun matches(url: String): Boolean {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            return filters().single().match(i.action, null, i.scheme, i.data, i.categories, "test") >= 0
        }
        assertTrue(matches("https://one.example.test/auth/qr"))
        assertTrue(matches("https://two.example.test:8443/auth/qr"))
        assertFalse(matches("http://one.example.test/auth/qr"))
        assertFalse(matches("https://one.example.test/auth/qrx"))
        assertFalse(matches("https://one.example.test/auth/qr/x"))
        assertFalse(matches("https://one.example.test/"))
    }
}
