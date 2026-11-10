package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.core.net.InstanceApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A tenant's branding as shown: its server's display name and the cached icon file. Either may be absent. */
data class TenantBrand(val name: String?, val icon: File?)

/**
 * Each tenant's branding (muster `GET /api/instance`): the display name and
 * the small logo the switcher and that tenant's notifications show.
 *
 * - **Stored per tenant**, in `<root>/<tenant id>/`: `meta.json` and the icon
 *   as `icon-<hash of the logo's etag>.png`. A tenant's lookup reads its own
 *   directory only, and only while the stored origin is still that tenant's
 *   origin ([brandFor]): tenant A's logo is never shown for tenant B.
 * - **Never blocks**: everything here is called off the UI thread; until a
 *   read lands, the switcher and notifications show the defaults.
 * - **Fresh as the server says**: a read is skipped while the last answer's
 *   `Cache-Control: max-age` holds ([DEFAULT_TTL_MS] without one), unless
 *   forced (a tenant just added, or a push naming a new icon etag); a stale
 *   read is conditional (`If-None-Match`), and the icon is downloaded only
 *   when the logo's etag changed, itself conditional on the icon's ETag.
 * - **Falls back**: a muster without the route (404) has no branding, and any
 *   branding stored for it is dropped; a network or server failure keeps what
 *   is stored; an icon refused (another origin, not a PNG, over 1 MiB) is no
 *   icon.
 */
class TenantBranding(
    private val root: File,
    private val api: (origin: String) -> InstanceApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    internal data class Meta(
        /** The origin this branding was read from: the binding [brandFor] checks. */
        val origin: String,
        val name: String? = null,
        /** `/api/instance`'s own ETag, for the next conditional read. */
        @SerialName("instance_etag") val instanceEtag: String? = null,
        /** The logo's reference and etag as `/api/instance` sent them. */
        @SerialName("logo_ref") val logoRef: String? = null,
        @SerialName("logo_etag") val logoEtag: String? = null,
        /** The icon response's ETag, for a conditional icon read. */
        @SerialName("icon_http_etag") val iconHttpEtag: String? = null,
        /** The icon's file name in the tenant's directory, or null for none. */
        @SerialName("icon_file") val iconFile: String? = null,
        /** Epoch ms until which no read is made (unless forced). */
        @SerialName("fresh_until") val freshUntil: Long = 0,
    )

    enum class Outcome { FRESH, UPDATED, UNCHANGED, NOT_OFFERED, FAILED, NO_TENANT }

    private val metas = MutableStateFlow<Map<String, Meta>>(emptyMap())
    @Volatile private var loaded = false
    private val locks = HashMap<String, Mutex>()
    private val lastHint = HashMap<String, Long>()

    /** Changes whenever any tenant's branding changes (for the UI to re-read [brandFor]). */
    internal val changes: StateFlow<Map<String, Meta>> by lazy { load(); metas.asStateFlow() }

    /**
     * Tenant [t]'s branding, or null. 🔴 Bound to the tenant's id AND its
     * origin: a stored record whose origin is not [t]'s (another server once
     * held this id) is never shown, and the icon must be a file in [t]'s own
     * directory.
     */
    fun brandFor(t: TenantRecord): TenantBrand? {
        load()
        val m = metas.value[t.id] ?: return null
        if (m.origin != t.origin) return null
        val icon = m.iconFile?.let { File(dirOf(t.id) ?: return@let null, it) }?.takeIf { it.isFile }
        if (m.name == null && icon == null) return null
        return TenantBrand(m.name, icon)
    }

    /** Every tenant's branding, by tenant id (tenants with none are left out). */
    fun brands(tenants: Tenants): Map<String, TenantBrand> =
        tenants.all.mapNotNull { t -> brandFor(t)?.let { t.id to it } }.toMap()

    /** Refreshes every tenant, each on its own; one failing never stops the others. */
    suspend fun refreshAll(tenants: Tenants, force: Boolean = false) {
        for (t in tenants.all) runCatching { refresh(t, force) }
    }

    /** Reads tenant [t]'s `/api/instance` (and its icon when it changed). */
    suspend fun refresh(t: TenantRecord, force: Boolean = false): Outcome {
        val dir = dirOf(t.id) ?: return Outcome.NO_TENANT
        if (t.origin.isEmpty()) return Outcome.NO_TENANT
        load()
        return lockFor(t.id).withLock {
            withContext(io) { refreshLocked(t, dir, force) }
        }
    }

    private fun refreshLocked(t: TenantRecord, dir: File, force: Boolean): Outcome {
        // A record read from another origin is not this tenant's: start over, and never send its etags here.
        val old = metas.value[t.id]?.takeIf { it.origin == t.origin }
        if (!force && old != null && now() < old.freshUntil) return Outcome.FRESH
        val client = runCatching { api(t.origin) }.getOrNull() ?: return Outcome.FAILED
        val oldIcon = old?.iconFile?.let { File(dir, it) }?.takeIf { it.isFile }
        return when (val r = client.instance(ifNoneMatch = old?.instanceEtag?.takeIf { oldIcon != null || old.logoRef == null })) {
            is InstanceApi.InstanceResult.Failed -> Outcome.FAILED
            InstanceApi.InstanceResult.NotOffered -> {
                // An older muster: no branding. Drop what is stored; look again after the default time.
                val m = Meta(origin = t.origin, freshUntil = freshUntil(null))
                save(t.id, dir, m)
                if (old != null && (old.name != null || old.iconFile != null)) Outcome.UPDATED else Outcome.NOT_OFFERED
            }
            is InstanceApi.InstanceResult.NotModified -> {
                // Only a conditional read can be answered 304; without a stored record it is no answer.
                if (old == null) return Outcome.FAILED
                save(t.id, dir, old.copy(freshUntil = freshUntil(r.maxAgeSeconds)))
                Outcome.UNCHANGED
            }
            is InstanceApi.InstanceResult.Info -> {
                val logo = r.instance.logo
                var m = Meta(
                    origin = t.origin,
                    name = r.instance.name,
                    instanceEtag = r.httpEtag,
                    logoRef = logo?.icon,
                    logoEtag = logo?.etag,
                    freshUntil = freshUntil(r.maxAgeSeconds),
                )
                m = when {
                    logo == null -> m
                    old?.logoEtag == logo.etag && oldIcon != null && old.logoRef == logo.icon ->
                        m.copy(iconFile = oldIcon.name, iconHttpEtag = old.iconHttpEtag)
                    else -> when (val ir = client.icon(logo.icon, ifNoneMatch = old?.iconHttpEtag?.takeIf { oldIcon != null })) {
                        is InstanceApi.IconResult.Fetched -> {
                            val name = iconName(logo.etag)
                            dir.mkdirs()
                            writeAtomically(File(dir, name), ir.bytes)
                            m.copy(iconFile = name, iconHttpEtag = ir.httpEtag)
                        }
                        InstanceApi.IconResult.NotModified -> {
                            // The same bytes under the new logo etag: keep the file, renamed to its new key.
                            val name = iconName(logo.etag)
                            if (oldIcon != null && oldIcon.name != name) oldIcon.renameTo(File(dir, name))
                            if (File(dir, name).isFile) m.copy(iconFile = name, iconHttpEtag = old?.iconHttpEtag) else m
                        }
                        // Refused: the server's logo is not one the app shows; the default icon.
                        is InstanceApi.IconResult.Refused -> m
                        // A failure: no icon for now, and the next chance reads again.
                        is InstanceApi.IconResult.Failed -> m.copy(logoEtag = null, freshUntil = 0)
                    }
                }
                save(t.id, dir, m)
                if (m.copy(freshUntil = 0, instanceEtag = null) == old?.copy(freshUntil = 0, instanceEtag = null)) Outcome.UNCHANGED else Outcome.UPDATED
            }
        }
    }

    /**
     * A push named the icon etag [etag] for tenant [t]: when it is not the one
     * stored, re-read `/api/instance` now (forced), at most once a minute per
     * tenant. The push's own icon URL is never fetched: `/api/instance` on the
     * tenant's origin stays the only source. True when a read was made.
     */
    suspend fun onIconEtag(t: TenantRecord, etag: String): Boolean {
        load()
        val m = metas.value[t.id]?.takeIf { it.origin == t.origin }
        if (m?.logoEtag == etag) return false
        synchronized(lastHint) {
            val last = lastHint[t.id]
            val at = now()
            if (last != null && at - last < MIN_HINT_GAP_MS) return false
            lastHint[t.id] = at
        }
        refresh(t, force = true)
        return true
    }

    /** Tenant [id] was removed: its branding goes, from disk and memory. */
    suspend fun forget(id: String) {
        val dir = dirOf(id) ?: return
        load()
        lockFor(id).withLock {
            withContext(io) { dir.deleteRecursively() }
            metas.update { it - id }
        }
    }

    // --- storage ---------------------------------------------------------------------

    private fun dirOf(id: String): File? = if (Tenants.validId(id)) File(root, id) else null

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun freshUntil(maxAgeSeconds: Long?): Long {
        val ttl = maxAgeSeconds?.let { (it * 1000).coerceIn(0, MAX_TTL_MS) } ?: DEFAULT_TTL_MS
        return now() + ttl
    }

    private fun save(id: String, dir: File, m: Meta) {
        dir.mkdirs()
        writeAtomically(File(dir, META), json.encodeToString(Meta.serializer(), m).encodeToByteArray())
        // Only the current icon stays.
        dir.listFiles()?.filter { it.name.startsWith("icon-") && it.name != m.iconFile }?.forEach { it.delete() }
        metas.update { it + (id to m) }
    }

    /** Reads every stored record once per process (small files; safe from any thread). */
    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val found = root.listFiles()?.filter { it.isDirectory && Tenants.validId(it.name) }.orEmpty().mapNotNull { d ->
                runCatching { json.decodeFromString(Meta.serializer(), File(d, META).readText()) }.getOrNull()?.let { d.name to it }
            }.toMap()
            metas.update { found + it }
            loaded = true
        }
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        private const val META = "meta.json"

        /** Without a Cache-Control max-age, a read holds for an hour. */
        const val DEFAULT_TTL_MS = 60 * 60 * 1000L

        /** A server's max-age is honoured up to a week. */
        const val MAX_TTL_MS = 7 * 24 * 60 * 60 * 1000L

        /** A push's new icon etag forces a read at most once a minute per tenant. */
        const val MIN_HINT_GAP_MS = 60_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** The icon's file name: keyed by the logo's etag (hashed, so any etag is a safe file name). */
        internal fun iconName(logoEtag: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(logoEtag.encodeToByteArray())
            return "icon-" + d.take(16).joinToString("") { "%02x".format(it) } + ".png"
        }
    }
}
