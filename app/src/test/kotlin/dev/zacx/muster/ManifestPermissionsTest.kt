package dev.zacx.muster

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capture permissions the merged manifest asks for: a page's microphone
 * (dictation) needs RECORD_AUDIO, and WebView's audio capture also needs
 * MODIFY_AUDIO_SETTINGS. Nothing asks for the camera or any other capture.
 * Only this set is pinned, so a library that adds an unrelated permission
 * does not turn this red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManifestPermissionsTest {
    private val capture = setOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_EXTERNAL_STORAGE",
    )

    @Test
    fun theOnlyCapturePermissionIsTheMicrophone() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(
            setOf("android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_SETTINGS"),
            info.requestedPermissions.orEmpty().toSet() intersect capture,
        )
    }
}
