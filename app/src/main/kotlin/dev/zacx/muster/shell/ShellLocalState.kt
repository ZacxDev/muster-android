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
 * - [forget]: the pairing ended (unpaired here, or muster ended it), or the
 *   server is being changed. The picture is deleted and the job cancelled.
 * - [onCommit]: a page of the shell committed. Landing on muster's sign-in
 *   page, or the home load landing anywhere but `/`, deletes the picture.
 * - [syncRefresh]: the job is scheduled only while the phone is paired; an
 *   unpaired phone has nothing for it to read.
 */
class ShellLocalState(
    val snapshot: ShellSnapshot,
    private val workManager: () -> WorkManager,
    /** One writer at a time for the snapshot's two files. */
    val io: CoroutineDispatcher = snapshotIo,
) {
    /** Deletes the picture and cancels the job. Safe to call from any thread, any number of times. */
    suspend fun forget() {
        withContext(io) { runCatching { snapshot.delete() } }
        cancelRefresh()
    }

    /** Cancels the job only (it found the phone unpaired). */
    suspend fun cancelRefresh() {
        withContext(Dispatchers.Default) { runCatching { RefreshSchedule.cancel(workManager()) } }
    }

    /** The phone unpaired itself: the record loses its pairing, then [forget]. */
    suspend fun markUnpaired(store: DeviceStore) {
        store.update(DeviceRecord::unpaired)
        forget()
    }

    /**
     * Only while unpaired: forget the server, its cookies ([clearCookies]),
     * the picture and the job, back to the first-run screen. False (and
     * nothing changed) while paired.
     */
    suspend fun changeServer(store: DeviceStore, clearCookies: () -> Unit): Boolean {
        if (store.read().paired) return false
        clearCookies()
        forget()
        store.update { DeviceRecord() }
        return true
    }

    /** A page of the shell committed at [url] on [origin]; [homeLoad]: it was the launcher's home load. */
    suspend fun onCommit(url: String, origin: String, homeLoad: Boolean) {
        if (SnapshotPolicy.dropsOnCommit(url, origin, homeLoad)) withContext(io) { runCatching { snapshot.delete() } }
    }

    /** Schedules the job when [paired]; an unpaired phone schedules nothing (and opens no WorkManager). */
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
     * Writes the WebView's cookies to disk, only while the shell has a
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
