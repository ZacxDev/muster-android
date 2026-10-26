package dev.zacx.muster.refresh

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.MusterApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WorkManager must not start with every process (a cold start of the shell
 * would pay for opening its database): the merged manifest drops its App
 * Startup initializer and MusterApp is its Configuration.Provider, so it
 * starts on first use. Robolectric does not run App Startup, so this reads
 * the merged manifest's provider entry instead of watching a process start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerOnDemandTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theMergedManifestStartsNoWorkManagerAtProcessStart() {
        val info = context.packageManager.getProviderInfo(
            ComponentName(context, InitializationProvider::class.java),
            PackageManager.GET_META_DATA,
        )
        val keys = info.metaData?.keySet().orEmpty()
        // Control: this is the App Startup entry, read with its other initializers.
        assertTrue(keys.toString(), "androidx.lifecycle.ProcessLifecycleInitializer" in keys)
        assertFalse(keys.toString(), "androidx.work.WorkManagerInitializer" in keys)
    }

    @Test
    fun theApplicationConfiguresWorkManagerOnDemand() {
        assertTrue(context.applicationContext is androidx.work.Configuration.Provider)
        assertTrue(context.applicationContext is MusterApp)
    }
}
