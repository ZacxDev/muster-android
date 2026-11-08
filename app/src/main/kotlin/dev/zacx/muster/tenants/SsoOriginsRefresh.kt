package dev.zacx.muster.tenants

import android.os.SystemClock

/**
 * When a shell's background read of its tenant's sign-in origins
 * ([TenantOps.refreshSsoOrigins]) rebuilds that shell. Two rules, so a server
 * whose answer changes on every read cannot loop the page:
 *
 * - A shell that IS a refresh's rebuild ([onShellOpened] `byRefresh`) does
 *   not read again. Without this, each rebuild's own read would see the next
 *   answer and rebuild again, forever.
 * - A changed list rebuilds a tenant's shell at most once per
 *   [minRebuildGapMs]. A change inside that gap is stored but waits for the
 *   next shell, so a user reopening the app against a flipping server does
 *   not get a rebuild each time.
 *
 * One per activity; the clock is monotonic ([SystemClock.elapsedRealtime]).
 */
class SsoOriginsRefresh(
    private val refresh: suspend (String) -> Boolean,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val minRebuildGapMs: Long = MIN_REBUILD_GAP_MS,
) {
    private val lastRebuild = HashMap<String, Long>()

    /**
     * Tenant [id]'s shell opened; [byRefresh]: it is the rebuild a previous
     * call asked for. Reads the sign-in origins unless [byRefresh]; true when
     * the shell should be rebuilt on the new list now.
     */
    suspend fun onShellOpened(id: String, byRefresh: Boolean): Boolean {
        if (byRefresh) return false
        if (!refresh(id)) return false
        val t = now()
        val last = lastRebuild[id]
        if (last != null && t - last < minRebuildGapMs) return false
        lastRebuild[id] = t
        return true
    }

    companion object {
        /** At most one rebuild of a tenant's shell per minute for a changed list. */
        const val MIN_REBUILD_GAP_MS = 60_000L
    }
}
