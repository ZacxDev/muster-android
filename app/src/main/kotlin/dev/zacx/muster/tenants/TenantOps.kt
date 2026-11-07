package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.TenantRepository
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.feature.notify.NotifyTenant
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.feature.shell.TenantSessions
import dev.zacx.muster.shell.ShellLocalState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The notifier's view of a tenant: the migrated one keeps the pre-upgrade channel ids. */
fun TenantRecord.notifyTenant(): NotifyTenant = NotifyTenant(id, label, colour, legacy = id == Tenants.DEFAULT)

/**
 * Adding and removing muster servers, with everything that goes with each
 * (muster docs/design/multi-tenant.md, "4. Android"). Removing one tenant
 * never touches another's credential, step-up key, push registration, web
 * session or notifications.
 */
class TenantOps(
    val repo: TenantRepository,
    private val sessions: TenantSessions<*>,
    private val multiProfile: () -> Boolean,
    private val api: (origin: String) -> DeviceApi,
    private val deleteKey: (alias: String) -> Unit,
    /** Drops the UnifiedPush registration of one instance. */
    private val unregisterPush: (instance: String) -> Unit,
    /** Clears a removed tenant's notifications and its channel group. */
    private val dropNotifications: (TenantRecord) -> Unit,
    private val local: ShellLocalState,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Adds the server at [origin] (normalised by OriginPolicy), unpaired and current. */
    suspend fun add(origin: String): TenantRepository.AddResult = repo.add(origin, multiProfile())

    /**
     * Removes tenant [id]. The record goes FIRST, so a push arriving during
     * the removal finds no tenant and is dropped instead of drawing (and
     * recreating the channels being deleted). Then: its pairing is ended on
     * its server (the app's own unpair, `DELETE /api/device`; best effort, so
     * a server that cannot be reached keeps the row on its Account → Devices
     * until it is removed there), and its push registration, step-up key,
     * WebView profile (or, if it cannot be deleted yet, a retry recorded),
     * notifications and channels go. The background job stays while another
     * tenant is paired. Returns the removed record, or null.
     */
    suspend fun remove(id: String): TenantRecord? {
        val t = repo.remove(id) ?: return null
        val token = t.device.token
        if (token != null) withContext(io) { runCatching { api(t.origin).unpair(token) } }
        runCatching { unregisterPush(t.pushInstance) }
        t.device.keyAlias?.let { a -> withContext(io) { runCatching { deleteKey(a) } } }
        if (!sessions.forget(t.webProfile)) {
            repo.update { it.copy(profilesToDelete = (it.profilesToDelete + t.webProfile).distinct()) }
        }
        runCatching { dropNotifications(t) }
        local.forget(cancelRefresh = repo.read().all.none { it.device.paired })
        return t
    }

    /**
     * Deletes the profiles a removal could not delete. Called with no WebView
     * alive (before the shell builds one). A name a live tenant uses is only
     * dropped from the list, never deleted.
     */
    suspend fun retryProfileDeletes() {
        val t = repo.read()
        if (t.profilesToDelete.isEmpty()) return
        val inUse = t.all.map { it.webProfile }.toSet()
        val done = t.profilesToDelete.filter { it in inUse || sessions.forget(it) }.toSet()
        if (done.isNotEmpty()) repo.update { it.copy(profilesToDelete = it.profilesToDelete - done) }
    }

    /** Unpairs tenant [id] and keeps it as a server to sign in to and pair again. */
    suspend fun unpair(id: String) {
        val t = repo.read().byId(id) ?: return
        val token = t.device.token
        if (token != null) withContext(io) { runCatching { api(t.origin).unpair(token) } }
        runCatching { unregisterPush(t.pushInstance) }
        t.device.keyAlias?.let { a -> withContext(io) { runCatching { deleteKey(a) } } }
        repo.updateDevice(id) { it.unpaired() }
        local.forget(cancelRefresh = repo.read().all.none { it.device.paired })
    }
}

/**
 * Where a launch or a notification tap opens (muster
 * docs/design/multi-tenant.md: "MainActivity switches to that tenant, then
 * loads the path on that tenant's origin").
 */
object TapRouting {
    data class Target(val tenant: TenantRecord, val path: String?, val switched: Boolean)

    /**
     * [tenantId] is the tap's tenant (null for a plain launch). A tap for a
     * tenant that is gone opens the tenant in view at its home page: its
     * path belonged to another server, and is never opened on this one.
     */
    /**
     * The renderer died: the shell is rebuilt on the tenant in view, at its
     * home page. Never the activity's last intent, whose tap could name
     * another server (and switch to it).
     */
    fun afterRendererGone(tenants: Tenants): Target? = tenants.active?.let { Target(it, null, switched = false) }

    fun resolve(tenants: Tenants, tenantId: String?, path: String?): Target? {
        val active = tenants.active ?: return null
        if (tenantId == null) return Target(active, path, switched = false)
        val t = tenants.byId(tenantId) ?: return Target(active, null, switched = false)
        return Target(t, path, switched = t.id != active.id)
    }
}

/**
 * The background job's config read, for every paired tenant, each on its own
 * ([PushRegistrar.refreshConfig]); a slow or failing server never stops the
 * others. A tenant that muster revoked has its push instance dropped.
 * The combined result: [PushRegistrar.ConfigResult.NotPaired] only when no
 * tenant is paired; otherwise a failure if any read failed (still paired),
 * else a read.
 */
suspend fun refreshAllTenants(
    tenants: Tenants,
    refresh: suspend (TenantRecord) -> PushRegistrar.ConfigResult,
    onRevoked: suspend (TenantRecord) -> Unit,
): PushRegistrar.ConfigResult {
    var anyPaired = false
    var failed: PushRegistrar.ConfigResult? = null
    var revoked = false
    for (t in tenants.all.filter { it.device.paired }) {
        when (val r = runCatching { refresh(t) }.getOrElse { PushRegistrar.ConfigResult.Failed(it.toString()) }) {
            PushRegistrar.ConfigResult.Revoked -> { revoked = true; onRevoked(t) }
            PushRegistrar.ConfigResult.NotPaired -> Unit
            is PushRegistrar.ConfigResult.Failed -> { anyPaired = true; failed = r }
            else -> anyPaired = true
        }
    }
    return when {
        failed != null -> failed!!
        anyPaired -> PushRegistrar.ConfigResult.NoPush
        revoked -> PushRegistrar.ConfigResult.Revoked
        else -> PushRegistrar.ConfigResult.NotPaired
    }
}
