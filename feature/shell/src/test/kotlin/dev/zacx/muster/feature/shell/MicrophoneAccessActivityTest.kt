package dev.zacx.muster.feature.shell

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * MicrophoneAccess.register on a real ComponentActivity: the ActivityResult
 * launcher really asks Android for RECORD_AUDIO, Android's answer reaches the
 * page's callback, and a blocked microphone shows the settings dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class MicrophoneAccessActivityTest {
    /** Registers from a field initialiser, as MainActivity does: before the activity has a context. */
    class Host : ComponentActivity() {
        val access = MicrophoneAccess.register(this)
    }

    private val activity: Host = Robolectric.buildActivity(Host::class.java).setup().get()
    private val access = activity.access

    /** Answers the last permission request the way Android would. */
    private fun answer(granted: Boolean) {
        val req = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), req.requestedPermissions)
        val app = shadowOf(activity.application)
        if (granted) app.grantPermissions(Manifest.permission.RECORD_AUDIO) else app.denyPermissions(Manifest.permission.RECORD_AUDIO)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            req.requestCode, req.requestedPermissions,
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
    }

    @Test
    fun allowReachesThePage() {
        var got: Boolean? = null
        access.request { got = it }
        assertNull("answered before Android was asked", got)
        answer(granted = true)
        assertEquals(true, got)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun denyReachesThePage() {
        var got: Boolean? = null
        access.request { got = it }
        answer(granted = false)
        assertEquals(false, got)
        assertNull("a first refusal shows no settings dialog", ShadowAlertDialog.getLatestAlertDialog())
    }

    /** The second refusal, with Android offering no rationale: the dialog opens the app's settings page. */
    @Test
    fun aBlockedMicOffersSettings() {
        access.request { }
        answer(granted = false)
        var got: Boolean? = null
        access.request { got = it } // asked before; Robolectric's rationale is false
        answer(granted = false)
        assertEquals(false, got)
        val d = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(d.isShowing)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle() // the dialog posts its click
        // Robolectric hands back the most recent start first: the settings page.
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:" + activity.packageName, started.dataString)
    }

    @Test
    fun heldPermissionAsksNothing() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var got: Boolean? = null
        access.request { got = it }
        assertEquals(true, got)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }
}
