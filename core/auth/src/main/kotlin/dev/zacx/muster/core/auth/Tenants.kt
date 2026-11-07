package dev.zacx.muster.core.auth

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One muster server this phone is paired with (muster
 * docs/design/multi-tenant.md, "4. Android", "The model"): a separate muster
 * deployment at its own origin, with its own device credential, step-up key,
 * push registration, notification channels and WebView profile.
 */
@Serializable
data class TenantRecord(
    /** Local only, never sent to a server. [Tenants.DEFAULT] for the migrated pairing, else random. */
    val id: String,
    /** What the user reads in the switcher and on every notification. */
    val label: String,
    /** ARGB, chosen when the tenant was added ([Tenants.PALETTE]). */
    val colour: Int,
    /** The WebView profile its pages load in ([Tenants.DEFAULT_PROFILE] or `tenant-<id>`). */
    @SerialName("web_profile") val webProfile: String,
    /** Its pairing, exactly the single-server record; [DeviceRecord.origin] is the pin for its credential. */
    val device: DeviceRecord,
) {
    val origin: String get() = device.origin.orEmpty()

    /** Its UnifiedPush instance: the id ("default" for the migrated tenant, as before the upgrade). */
    val pushInstance: String get() = id
}

/** Every tenant, and the one in view. Sealed as a whole, like [DeviceRecord]. */
@Serializable
data class Tenants(
    /** The tenant in view, or null when there is none. Kept across restarts. */
    val current: String? = null,
    val all: List<TenantRecord> = emptyList(),
    /**
     * SHA-256 (hex) of the `device.sealed` bytes this app last wrote or
     * accepted; a different file at start is a change an older app made
     * ([TenantRepository]).
     */
    @SerialName("device_digest") val deviceDigest: String? = null,
    /** False until the single-server record has been migrated in, once. */
    val migrated: Boolean = false,
    /** Something the user must be told once (an older version paired tenant default to another tenant's server). */
    val notice: String? = null,
) {
    fun byId(id: String?): TenantRecord? = all.firstOrNull { it.id == id }

    /** Origins are unique, so this is at most one tenant. */
    fun byOrigin(origin: String?): TenantRecord? = all.firstOrNull { origin != null && it.device.origin == origin }

    /** The tenant a push on UnifiedPush [instance] belongs to, or null (an unknown instance is dropped). */
    fun forInstance(instance: String): TenantRecord? = all.firstOrNull { it.pushInstance == instance }

    val active: TenantRecord? get() = byId(current) ?: all.firstOrNull()

    companion object {
        /** The migrated single-server pairing: keeps push instance "default", the legacy channels and the default WebView profile. */
        const val DEFAULT = "default"

        /** androidx.webkit's `Profile.DEFAULT_PROFILE_NAME`: the profile every WebView had before multi-tenant. */
        const val DEFAULT_PROFILE = "Default"

        /** Tenant colours, ARGB; a new tenant takes the first one not in use. */
        val PALETTE: List<Int> = listOf(
            0xFF7C5CFF.toInt(), // violet (muster's accent)
            0xFF2BB673.toInt(), // green
            0xFFFF8A3D.toInt(), // orange
            0xFF3DA5FF.toInt(), // blue
            0xFFE84D8A.toInt(), // pink
            0xFFE8C547.toInt(), // yellow
            0xFF47D1C9.toInt(), // teal
            0xFFB0B4C0.toInt(), // grey
        )

        /** The label a tenant gets from its origin: host, and the port when it is not 443. */
        fun labelFor(origin: String): String {
            val u = runCatching { URI(origin) }.getOrNull() ?: return origin
            val host = u.host ?: return origin
            return if (u.port == -1 || u.port == 443) host else "$host:${u.port}"
        }

        private val ID = Regex("[a-z0-9]{1,32}")

        fun validId(id: String) = ID.matches(id)
    }
}

/**
 * The checks every write of [Tenants] passes: unique ids, unique origins,
 * unique WebView profiles, a current tenant that exists. A write that would
 * break one is refused (thrown), never stored.
 */
internal object TenantRules {
    fun check(t: Tenants) {
        val ids = t.all.map { it.id }
        require(ids.size == ids.toSet().size) { "two tenants share an id" }
        require(t.all.all { Tenants.validId(it.id) }) { "a tenant id is not [a-z0-9]{1,32}" }
        val origins = t.all.mapNotNull { it.device.origin }
        require(origins.size == t.all.size) { "a tenant has no origin" }
        require(origins.size == origins.toSet().size) { "two tenants share an origin" }
        val profiles = t.all.map { it.webProfile }
        require(profiles.size == profiles.toSet().size) { "two tenants share a WebView profile" }
        require(t.current == null || t.byId(t.current) != null) { "the current tenant does not exist" }
        require(t.current != null || t.all.isEmpty()) { "tenants exist but none is current" }
    }
}

/**
 * The DataStore serializer for [Tenants]: the whole list sealed with [aead]
 * under the same Keystore record key as `device.sealed`, with its own AAD, so
 * neither file opens as the other.
 */
class SealedTenantsSerializer(private val aead: Aead) : Serializer<Tenants> {
    override val defaultValue: Tenants = Tenants()

    override suspend fun readFrom(input: InputStream): Tenants {
        val sealed = input.readBytes()
        if (sealed.isEmpty()) return defaultValue
        val plain = try {
            aead.decrypt(sealed, AAD)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("the tenant list could not be opened", e)
        }
        return try {
            json.decodeFromString(Tenants.serializer(), plain.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("the tenant list could not be read", e)
        }
    }

    override suspend fun writeTo(t: Tenants, output: OutputStream) {
        output.write(aead.encrypt(json.encodeToString(Tenants.serializer(), t).encodeToByteArray(), AAD))
    }

    companion object {
        val AAD = "dev.zacx.muster/tenants/v1".encodeToByteArray()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** The raw tenant file; [TenantRepository] is the only writer. */
interface TenantsFile {
    val data: Flow<Tenants>

    suspend fun write(t: Tenants)
}

class DataStoreTenantsFile(file: File, aead: Aead, scope: CoroutineScope) : TenantsFile {
    private val store: DataStore<Tenants> = DataStoreFactory.create(
        serializer = SealedTenantsSerializer(aead),
        // Unreadable (the Keystore key is gone): start over from device.sealed,
        // which is sealed under the same key and so reads as unpaired too.
        corruptionHandler = ReplaceFileCorruptionHandler { Tenants() },
        scope = scope,
        produceFile = { file },
    )

    override val data: Flow<Tenants> = store.data

    override suspend fun write(t: Tenants) {
        store.updateData { t }
    }
}

/**
 * Every tenant's pairing, in two files (muster docs/design/multi-tenant.md,
 * "Storage, and why it is two files"):
 *
 * - `tenants.sealed` ([file]) holds the list;
 * - `device.sealed` ([legacy]) stays a valid v1 [DeviceRecord]: a copy of
 *   tenant [Tenants.DEFAULT], written FIRST whenever that tenant changes, and
 *   empty when it is removed. A downgraded app reads it as before.
 *
 * At first use in a process the two are reconciled ([ready]):
 *
 * - never migrated: the single-server record becomes tenant `default`,
 *   current, labelled by its host. Nothing re-pairs, and `device.sealed` is
 *   left byte for byte as it was.
 * - `device.sealed` matching the recorded digest: the list is authoritative.
 * - differing: an older app changed it (after a downgrade), or this app was
 *   killed between its two writes; either way `device.sealed` holds the newer
 *   state of tenant `default`, which is taken. If that names an origin another
 *   tenant holds, tenant `default` becomes unpaired instead and [Tenants.notice]
 *   says why: two tenants never share an origin.
 */
class TenantRepository(
    private val file: TenantsFile,
    private val legacy: DeviceStore,
    /** SHA-256 (hex) of `device.sealed`'s bytes, or null when there is no file. */
    private val legacyDigest: suspend () -> String?,
    private val newId: () -> String = ::randomId,
) {
    private val lock = Mutex()
    @Volatile private var reconciled = false

    /** The tenants, reconciled with `device.sealed` before the first value. */
    val tenants: Flow<Tenants> = flow {
        ready()
        emitAll(file.data)
    }

    suspend fun read(): Tenants = tenants.first()

    /** Reconciles once per process; every read and write waits for it. */
    suspend fun ready() {
        if (reconciled) return
        lock.withLock {
            if (reconciled) return
            reconcile()
            reconciled = true
        }
    }

    private suspend fun reconcile() {
        val t = file.data.first()
        val rec = legacy.read()
        val digest = legacyDigest()
        if (!t.migrated) {
            val all = if (rec.origin != null) {
                listOf(TenantRecord(Tenants.DEFAULT, Tenants.labelFor(rec.origin), Tenants.PALETTE[0], Tenants.DEFAULT_PROFILE, rec))
            } else {
                emptyList()
            }
            file.write(Tenants(current = all.firstOrNull()?.id, all = all, deviceDigest = digest, migrated = true))
            return
        }
        if (digest == t.deviceDigest) return
        // device.sealed changed behind the list's back: an older app's change,
        // or a kill between our two writes. Take it.
        val def = t.byId(Tenants.DEFAULT)
        val others = t.all.filter { it.id != Tenants.DEFAULT }
        var notice = t.notice
        var rewriteLegacy: DeviceRecord? = null
        val newDefault: TenantRecord? = when {
            rec.origin == null -> null // removed (by us, or "change server" in an older app)
            others.any { it.device.origin == rec.origin } -> {
                notice = "An older version of the app paired ${Tenants.labelFor(rec.origin)} again, which another server " +
                    "in this app already uses. That pairing was not kept: remove the extra phone on that server's Account → Devices."
                val kept = def?.copy(device = def.device.unpaired())
                rewriteLegacy = kept?.device ?: DeviceRecord()
                kept
            }
            def != null -> def.copy(device = rec, label = if (def.device.origin == rec.origin) def.label else Tenants.labelFor(rec.origin))
            else -> TenantRecord(
                Tenants.DEFAULT, Tenants.labelFor(rec.origin), freeColour(others), freeProfile(others, Tenants.DEFAULT), rec,
            )
        }
        if (rewriteLegacy != null) legacy.update { rewriteLegacy }
        val all = listOfNotNull(newDefault) + others
        val current = t.current?.takeIf { c -> all.any { it.id == c } } ?: all.firstOrNull()?.id
        val next = t.copy(current = current, all = all, deviceDigest = legacyDigest(), notice = notice)
        TenantRules.check(next)
        file.write(next)
    }

    /**
     * The one write path. [f] gets the current list; the result is checked
     * ([TenantRules]) and stored. A change to tenant `default` (or its removal)
     * writes `device.sealed` first, then the list with the new digest.
     */
    suspend fun update(f: (Tenants) -> Tenants): Tenants {
        ready()
        return lock.withLock {
            val old = file.data.first()
            var next = f(old)
            if (next.current == null && next.all.isNotEmpty()) next = next.copy(current = next.all.first().id)
            TenantRules.check(next)
            val oldDefault = old.byId(Tenants.DEFAULT)?.device
            val newDefault = next.byId(Tenants.DEFAULT)?.device
            if (oldDefault != newDefault) {
                legacy.update { newDefault ?: DeviceRecord() }
                next = next.copy(deviceDigest = legacyDigest())
            }
            if (next != old) file.write(next)
            next
        }
    }

    sealed interface AddResult {
        data class Added(val tenant: TenantRecord) : AddResult

        /** That origin is already a tenant: two tenants never share one. */
        data class Duplicate(val tenant: TenantRecord) : AddResult

        /** A second tenant needs WebView profiles, which this phone's WebView lacks. */
        data object NeedsWebViewProfiles : AddResult
    }

    /**
     * Adds the server at [origin] (already normalised: `https://host[:port]`),
     * unpaired, and makes it current. The first tenant of an empty list uses
     * the default WebView profile; any other needs [multiProfile].
     */
    suspend fun add(origin: String, multiProfile: Boolean): AddResult {
        var result: AddResult? = null
        update { t ->
            t.byOrigin(origin)?.let { result = AddResult.Duplicate(it); return@update t }
            if (t.all.isNotEmpty() && !multiProfile) { result = AddResult.NeedsWebViewProfiles; return@update t }
            var id = newId()
            while (id == Tenants.DEFAULT || t.byId(id) != null) id = newId()
            val rec = TenantRecord(id, Tenants.labelFor(origin), freeColour(t.all), freeProfile(t.all, id), DeviceRecord(origin = origin))
            result = AddResult.Added(rec)
            t.copy(current = id, all = t.all + rec)
        }
        return result!!
    }

    /** Makes [id] the tenant in view; false (nothing changed) when there is no such tenant. */
    suspend fun select(id: String): Boolean {
        var ok = false
        update { t -> if (t.byId(id) == null) t else { ok = true; t.copy(current = id) } }
        return ok
    }

    /** Removes [id]; the tenant in view moves to the first remaining one. Returns the removed record, or null. */
    suspend fun remove(id: String): TenantRecord? {
        var removed: TenantRecord? = null
        update { t ->
            removed = t.byId(id) ?: return@update t
            val all = t.all.filter { it.id != id }
            t.copy(all = all, current = if (t.current == id) all.firstOrNull()?.id else t.current)
        }
        return removed
    }

    /** Changes tenant [id]'s pairing only. A tenant that no longer exists is not recreated: null. */
    suspend fun updateDevice(id: String, f: (DeviceRecord) -> DeviceRecord): DeviceRecord? {
        var out: DeviceRecord? = null
        update { t ->
            val rec = t.byId(id) ?: return@update t
            val dev = f(rec.device)
            // A tenant's origin is its identity: a pairing write never moves it to another server.
            val pinned = dev.copy(origin = rec.device.origin)
            out = pinned
            t.copy(all = t.all.map { if (it.id == id) it.copy(device = pinned) else it })
        }
        return out
    }

    suspend fun clearNotice() {
        update { it.copy(notice = null) }
    }

    /** A [DeviceStore] that reads and writes tenant [id]'s pairing only ([TenantDeviceStore]). */
    fun deviceStore(id: String): DeviceStore = TenantDeviceStore(this, id)

    private fun freeColour(taken: List<TenantRecord>): Int {
        val used = taken.map { it.colour }.toSet()
        return Tenants.PALETTE.firstOrNull { it !in used } ?: Tenants.PALETTE[taken.size % Tenants.PALETTE.size]
    }

    /** The first tenant of an empty list keeps the default profile (its cookies are the pre-upgrade sign-in); every other gets its own. */
    private fun freeProfile(taken: List<TenantRecord>, id: String): String =
        if (taken.isEmpty()) Tenants.DEFAULT_PROFILE else "tenant-$id"

    companion object {
        private val rng = SecureRandom()

        fun randomId(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** [legacyDigest] for a file on disk. */
        fun fileDigest(file: File): String? = if (file.exists()) sha256Hex(file.readBytes()) else null
    }
}

/**
 * One tenant's pairing as a [DeviceStore], so pairing, push registration and
 * revocation run per tenant with the code that ran for the single server.
 * It reads and writes [id] only: another tenant's credential is never in
 * reach. Once [id] is removed it reads as unpaired, and a late write (a push
 * registration finishing after removal) changes nothing.
 */
class TenantDeviceStore(private val repo: TenantRepository, val id: String) : DeviceStore {
    override val records: Flow<DeviceRecord> =
        repo.tenants.map { it.byId(id)?.device ?: DeviceRecord() }.distinctUntilChanged()

    override suspend fun update(f: (DeviceRecord) -> DeviceRecord): DeviceRecord =
        repo.updateDevice(id, f) ?: DeviceRecord()
}
