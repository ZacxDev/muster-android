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
     * Removes tenant [id]: ends its pairing on its server (the app's own
     * unpair, `DELETE /api/device/pair`; best effort, so a server that cannot
     * be reached keeps the row on its Account → Devices until it is removed
     * there), drops its push registration, step-up key, WebView profile,
     * notifications and channels, then the record. The background job stays
     * while another tenant is paired. Returns the removed record, or null.
     */
    suspend fun remove(id: String): TenantRecord? {
        val t = repo.read().byId(id) ?: return null
        val token = t.device.token
        if (token != null) withContext(io) { runCatching { api(t.origin).unpair(token) } }
        runCatching { unregisterPush(t.pushInstance) }
        t.device.keyAlias?.let { a -> withContext(io) { runCatching { deleteKey(a) } } }
        sessions.forget(t.webProfile)
        runCatching { dropNotifications(t) }
        val removed = repo.remove(id)
        local.forget(cancelRefresh = repo.read().all.none { it.device.paired })
        return removed
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
