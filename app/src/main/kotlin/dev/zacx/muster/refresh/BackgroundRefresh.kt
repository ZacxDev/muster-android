package dev.zacx.muster.refresh

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacx.muster.MusterApp
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.feature.shell.WebViewPrewarm
import dev.zacx.muster.push.MusterPushService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * What "background refresh" can honestly do for this app, and nothing more.
 *
 * muster's pages need the session cookie, which lives in the WebView and
 * which native code never reads (docs/design/android.md, "The shell"). The
 * device credential opens `/api/device/...` only, and none of those routes is
 * a page or anything the WebView caches. And a WebView renders nothing in
 * the background. So no job can make the PAGE fresher while the app is
 * closed. What one can do:
 *
 * 1. **Keep the pairing current**: read `/api/device/config` with the device
 *    credential (one small GET), so a pairing muster ended is forgotten and
 *    its push registration dropped while the app is closed.
 * 2. **Keep Chromium warm**: start the WebView provider in this process
 *    ([WebViewPrewarm]), so an open while the process is still cached is a
 *    warm start, not a cold one. Android decides how long a cached process
 *    lives; nothing here keeps it alive.
 *
 * The page itself is refreshed in place when the app opens: the last frame
 * (ShellSnapshot) shows at once and the live page replaces it.
 */
class BackgroundRefresh(
    private val refreshConfig: suspend () -> PushRegistrar.ConfigResult,
    private val onRevoked: () -> Unit,
    private val prewarm: suspend () -> Unit,
) {
    enum class Outcome { CONFIG_READ, NOT_PAIRED, REVOKED, CONFIG_FAILED }

    suspend fun run(): Outcome {
        val outcome = when (runCatching { refreshConfig() }.getOrNull()) {
            is PushRegistrar.ConfigResult.Register, PushRegistrar.ConfigResult.NoPush -> Outcome.CONFIG_READ
            PushRegistrar.ConfigResult.NotPaired -> Outcome.NOT_PAIRED
            PushRegistrar.ConfigResult.Revoked -> Outcome.REVOKED.also { onRevoked() }
            is PushRegistrar.ConfigResult.Failed, null -> Outcome.CONFIG_FAILED
        }
        prewarm()
        return outcome
    }
}

/** The periodic job: its name, period and constraints. */
object RefreshSchedule {
    const val NAME = "muster-background-refresh"

    /** Android's minimum period for periodic work; Doze stretches it further while the phone is idle. */
    const val PERIOD_MINUTES = 15L

    val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED) // the config read needs the network
        .setRequiresBatteryNotLow(true) // never on a low battery
        .build()

    fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<RefreshWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

    /** Schedules the job once; an existing schedule is kept (not restarted). */
    fun ensure(wm: WorkManager) {
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request())
    }

    fun cancel(wm: WorkManager) {
        wm.cancelUniqueWork(NAME)
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = MusterApp.from(applicationContext)
        val outcome = BackgroundRefresh(
            refreshConfig = { app.registrar.refreshConfig() },
            onRevoked = { runCatching { MusterPushService.unregister(applicationContext) } },
            prewarm = { prewarmAndWait(applicationContext) },
        ).run()
        android.util.Log.i("muster", "background refresh: $outcome")
        // Never a retry: the next period is the retry.
        return Result.success()
    }

    companion object {
        /** Starts Chromium and waits (at most 10 s) for it, so the job is not over before it is warm. */
        suspend fun prewarmAndWait(context: Context) {
            val warm = CompletableDeferred<Unit>()
            val started = WebViewPrewarm.start(context, onDone = { ms ->
                android.util.Log.i("muster", "background refresh: Chromium started in $ms ms")
                warm.complete(Unit)
            })
            if (!started) warm.complete(Unit) // already started in this process
            withTimeoutOrNull(10_000) { warm.await() }
        }
    }
}

/** Schedules the job off the main thread (WorkManager initialises on first use). */
suspend fun scheduleBackgroundRefresh(context: Context) = withContext(Dispatchers.Default) {
    runCatching { RefreshSchedule.ensure(WorkManager.getInstance(context)) }
}
