package dev.zacx.muster

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.tenants.QrHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Once MainActivity has routed a hand-off, its current intent is replaced by
 * [QrHandoffActivity.consumed]: the same intent without the hand-off's data
 * and code, so a recreation with no saved shell (which routes getIntent()
 * again) does not reopen `/auth/qr#<code>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QrHandoffConsumedTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val alpha = TenantRecord(Tenants.DEFAULT, "alpha", 1, Tenants.DEFAULT_PROFILE, DeviceRecord(origin = "https://alpha.example.test"))
    private val tenants = Tenants(current = alpha.id, all = listOf(alpha), migrated = true)
    private val code = "mqr_" + "Ab3-_x".repeat(7) + "Q"
    private val key = QrHandoff.extraCode(ctx.packageName)

    /** What QrHandoffActivity forwards to MainActivity. */
    private fun handoff() = Intent(ctx, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("https://alpha.example.test/auth/qr"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(key, code)

    private fun match(i: Intent) =
        QrHandoff.match(tenants, ctx.packageName, i.action, i.dataString, i::getStringExtra)

    @Test
    fun aConsumedHandoffNoLongerOpensThePage() {
        val original = handoff()
        // Control: the intent as delivered does open the page.
        assertEquals(QrHandoff.Result.Open(alpha, "/auth/qr#$code"), match(original))

        val consumed = QrHandoffActivity.consumed(original, ctx.packageName)
        assertNull(consumed.data)
        assertFalse(consumed.hasExtra(key))
        assertEquals(QrHandoff.Result.NotHandoff, match(consumed))
        // Still this activity's intent, just without the hand-off.
        assertEquals(ComponentName(ctx, MainActivity::class.java), consumed.component)
    }

    @Test
    fun theDeliveredIntentIsLeftAsItWas() {
        val original = handoff()
        QrHandoffActivity.consumed(original, ctx.packageName)
        assertEquals("https://alpha.example.test/auth/qr", original.dataString)
        assertEquals(code, original.getStringExtra(key))
    }
}
