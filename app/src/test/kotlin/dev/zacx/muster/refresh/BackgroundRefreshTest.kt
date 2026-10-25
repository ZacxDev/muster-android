package dev.zacx.muster.refresh

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.zacx.muster.feature.push.PushRegistrar.ConfigResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** What the background job does with each answer from `/api/device/config`. */
class BackgroundRefreshTest {
    private class Run(private val result: () -> ConfigResult) {
        val log = mutableListOf<String>()
        val refresh = BackgroundRefresh(
            refreshConfig = { log += "config"; result() },
            onRevoked = { log += "revoked" },
            onNotPaired = { log += "stop" },
            prewarm = { log += "prewarm" },
        )
    }

    private fun outcome(r: () -> ConfigResult): Pair<BackgroundRefresh.Outcome, List<String>> {
        val run = Run(r)
        var o: BackgroundRefresh.Outcome? = null
        runTest { o = run.refresh.run() }
        return o!! to run.log
    }

    @Test
    fun aConfigReadIsAReadAndChromiumIsWarmedAfterIt() {
        assertEquals(BackgroundRefresh.Outcome.CONFIG_READ to listOf("config", "prewarm"), outcome { ConfigResult.Register("vapid") })
        assertEquals(BackgroundRefresh.Outcome.CONFIG_READ to listOf("config", "prewarm"), outcome { ConfigResult.NoPush })
    }

    @Test
    fun onlyARevocationUnregistersPush() {
        // Revoked: push unregistered, the job stops, and no Chromium for a phone that is no longer paired.
        assertEquals(BackgroundRefresh.Outcome.REVOKED to listOf("config", "revoked", "stop"), outcome { ConfigResult.Revoked })
        // A failure is not a revocation (only muster's own refusal is): the pairing stays, and so does the job.
        assertEquals(BackgroundRefresh.Outcome.CONFIG_FAILED to listOf("config", "prewarm"), outcome { ConfigResult.Failed("timeout") })
    }

    /** An unpaired phone has nothing for the job to read: it stops itself and warms nothing. */
    @Test
    fun anUnpairedPhoneStopsTheJobAndWarmsNothing() {
        assertEquals(BackgroundRefresh.Outcome.NOT_PAIRED to listOf("config", "stop"), outcome { ConfigResult.NotPaired })
    }

    @Test
    fun aThrowingConfigReadStillWarmsChromium() {
        assertEquals(BackgroundRefresh.Outcome.CONFIG_FAILED to listOf("config", "prewarm"), outcome { throw java.io.IOException("offline") })
    }
}

/** The periodic job's schedule, through WorkManager's own test driver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RefreshScheduleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var wm: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
    }

    /** WorkManager's instance is process-global: leave none behind for WorkManagerOnDemandTest. */
    @After
    fun tearDown() = WorkManagerImpl.setDelegate(null)

    private fun infos() = wm.getWorkInfosForUniqueWork(RefreshSchedule.NAME).get()

    @Test
    fun everyFifteenMinutesOnANetworkAndABatteryThatIsNotLow() {
        val spec = RefreshSchedule.request().workSpec
        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(RefreshWorker::class.java.name, spec.workerClassName)
        val c = spec.constraints
        assertEquals(NetworkType.CONNECTED, c.requiredNetworkType)
        assertTrue(c.requiresBatteryNotLow())
        assertFalse(c.requiresCharging())
        assertFalse(c.requiresDeviceIdle())
    }

    @Test
    fun schedulingTwiceKeepsTheFirstSchedule() {
        RefreshSchedule.ensure(wm)
        val first = infos().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        RefreshSchedule.ensure(wm)
        val again = infos().single()
        assertEquals(first.id, again.id) // KEEP: not replaced, so its period is not restarted
    }

    /**
     * cancel() ends the schedule. Only the primitive: which paths call it
     * (change server, unpair, revocation, an unpaired run) is
     * ShellLocalStateTest's, through the calls those paths make.
     */
    @Test
    fun cancelEndsTheSchedule() {
        RefreshSchedule.ensure(wm)
        RefreshSchedule.cancel(wm)
        assertEquals(WorkInfo.State.CANCELLED, infos().single().state)
    }
}
