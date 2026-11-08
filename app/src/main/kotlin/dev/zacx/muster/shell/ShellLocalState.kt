package dev.zacx.muster.shell

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.work.WorkManager
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.DeviceStore
import dev.zacx.muster.refresh.RefreshSchedule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the shell keeps on this phone besides the device record: the last
 * home-page frame ([ShellSnapshot]) and the background-refresh job
 * ([RefreshSchedule]). Every rule for when they go is here, once, so the
 * activity, the job and the push registrar all drop them the same way.
 *
 * - [forget]: a pairing ended (unpaired here, or muster ended it), or a
 *   server was removed. The picture is deleted, and the job cancelled unless
 *   another server is still paired.
 *
 * There is one picture for the whole app, recording the origin it was taken
 * on, and it is only ever shown for that origin ([SnapshotPolicy.showable]),
 * so one server's board never appears while another server loads. Forgetting
 * one server may delete another's picture: that costs one blank start, never
 * a wrong one.
 * - [onCommit]: a page of the shell committed. Landing on muster's sign-in
 *   page or on one of the server's sign-in origins, or the home load landing
 *   anywhere but `/`, deletes the picture.
 * - [syncRefresh]: the job is scheduled only while the phone is paired; an
 *   unpaired phone has nothing for it to read.
 */
class ShellLocalState(
    val snapshot: ShellSnapshot,
    private val workManager: () -> WorkManager,
    /** One writer at a time for the snapshot's two files. */
    val io: CoroutineDispatcher = snapshotIo,
) {
    /**
     * Deletes the picture and, when [cancelRefresh], cancels the job (false
     * while another server is still paired). Safe to call from any thread,
     * any number of times.
     */
    suspend fun forget(cancelRefresh: Boolean = true) {
        withContext(io) { runCatching { snapshot.delete() } }
        if (cancelRefresh) cancelRefresh()
    }

    /** Cancels the job only (it found the phone unpaired). */
    suspend fun cancelRefresh() {
        withContext(Dispatchers.Default) { runCatching { RefreshSchedule.cancel(workManager()) } }
    }

    /** The phone unpaired itself from one server: its record loses its pairing, then [forget]. */
    suspend fun markUnpaired(store: DeviceStore, cancelRefresh: Boolean = true) {
        store.update(DeviceRecord::unpaired)
        forget(cancelRefresh)
    }

    /**
     * A page of the shell committed at [url] on [origin], whose server
     * advertises [ssoOrigins]; [homeLoad]: it was the launcher's home load.
     */
    suspend fun onCommit(url: String, origin: String, homeLoad: Boolean, ssoOrigins: List<String> = emptyList()) {
        if (SnapshotPolicy.dropsOnCommit(url, origin, homeLoad, ssoOrigins)) withContext(io) { runCatching { snapshot.delete() } }
    }

    /** Schedules the job when [paired] (any server is); an unpaired phone schedules nothing (and opens no WorkManager). */
    suspend fun syncRefresh(paired: Boolean) {
        if (!paired) return
        withContext(Dispatchers.Default) { runCatching { RefreshSchedule.ensure(workManager()) } }
    }

    /** The background job's share of [ShellSnapshot.pruneIfStale]. */
    suspend fun pruneStale() {
        withContext(io) { runCatching { snapshot.pruneIfStale() } }
    }

    companion object {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val snapshotIo = Dispatchers.IO.limitedParallelism(1)
    }
}

/** The shell's cookie flush when it leaves the foreground. */
object ShellCookies {
    /**
     * Writes the WebView's cookies to disk (the current server's profile:
     * the app passes [flush]), only while the shell has a
     * WebView ([webView] non-null): with none (the first-run or settings
     * screen) there is nothing to flush, and `CookieManager.getInstance()`
     * would start Chromium for nothing. A throw (a WebView missing or
     * mid-update) is swallowed: a failed flush must not crash `onPause`.
     */
    fun flushOnPause(webView: WebView?, flush: () -> Unit = { CookieManager.getInstance().flush() }) {
        if (webView == null) return
        runCatching(flush)
    }
}
