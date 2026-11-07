package dev.zacx.muster.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The tenant store (muster docs/design/multi-tenant.md, "The model"): the real
 * DataStore files and the real AES-GCM sealing, a software key standing in for
 * the Keystore's. Each [open] is one process: the files are reopened from disk.
 */
class TenantRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val k: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val deviceFile get() = File(tmp.root, "device.sealed")
    private val tenantsFile get() = File(tmp.root, "tenants.sealed")

    private val tokenA = "mdv_" + "A".repeat(43)
    private val tokenA2 = "mdv_" + "R".repeat(43)
    private val tokenB = "mdv_" + "B".repeat(43)
    private val originA = "https://a.example.test"
    private val originB = "https://b.example.test:8443"

    private val paired = DeviceRecord(
        origin = originA, token = tokenA, deviceId = 5, keyKind = "biometric", keyLevel = "strongbox",
        keyAlias = "muster-stepup-a", pushHosts = listOf("push.example.test"), pushStatus = PushStatus.REGISTERED,
    )

    private var ids = 0

    private var legacyReads = 0

    /** One "process": a repository over the files, wired as the app wires it; the stores are closed after [block]. */
    private fun <T> open(block: suspend (TenantRepository, File) -> T): T = runBlocking {
        val job = SupervisorJob()
        try {
            val scope = CoroutineScope(Dispatchers.IO + job)
            val repo = TenantRepository(
                DataStoreTenantsFile(tenantsFile, AesGcmAead { k }, scope),
                readLegacy = {
                    legacyReads++
                    if (deviceFile.exists()) DataStoreDeviceStore(deviceFile, AesGcmAead { k }, scope).read() else DeviceRecord()
                },
                deleteLegacy = { deviceFile.delete() },
                newId = { "t" + (++ids) },
            )
            block(repo, deviceFile)
        } finally {
            job.cancelAndJoin()
        }
    }

    /** What an app before multi-tenant wrote: one sealed v1 record. */
    private fun oldAppWrites(r: DeviceRecord) = runBlocking {
        val job = SupervisorJob()
        try {
            DataStoreDeviceStore(deviceFile, AesGcmAead { k }, CoroutineScope(Dispatchers.IO + job)).update { r }
        } finally {
            job.cancelAndJoin()
        }
    }

    // --- migration -----------------------------------------------------------------------

    /** The upgrade: today's sealed pairing becomes tenant "default", current, with nothing re-paired; then device.sealed is deleted. */
    @Test
    fun theExistingPairingMigratesIntoTenantDefault() {
        oldAppWrites(paired)
        assertTrue(deviceFile.exists())

        val t = open { repo, _ -> repo.read() }

        val d = t.all.single()
        assertEquals(Tenants.DEFAULT, d.id)
        assertEquals(paired, d.device) // the credential, key alias and push state, all kept
        assertEquals("a.example.test", d.label)
        assertEquals(Tenants.DEFAULT_PROFILE, d.webProfile) // the pre-upgrade cookies' profile
        assertEquals(Tenants.DEFAULT, d.pushInstance) // the pre-upgrade UnifiedPush instance
        assertEquals(Tenants.DEFAULT, t.current)
        assertTrue(t.migrated)
        assertFalse("no second copy of the credential stays on disk", deviceFile.exists())
        assertEquals("and it survives the restart from tenants.sealed alone", paired, open { repo, _ -> repo.read() }.byId(Tenants.DEFAULT)!!.device)
    }

    @Test
    fun aPhoneThatWasNeverSetUpMigratesToNoTenants() {
        val t = open { repo, _ -> repo.read() }
        assertEquals(emptyList<TenantRecord>(), t.all)
        assertNull(t.current)
        assertNull(t.active)
    }

    /** The migration runs once: a later start reads the list, and does not re-add a removed tenant default. */
    @Test
    fun theMigrationRunsOnce() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true); repo.remove(Tenants.DEFAULT) }
        val reads = legacyReads
        val t = open { repo, _ -> repo.read() }
        assertEquals(listOf("t1"), t.all.map { it.id })
        assertEquals("a later start never reads the single-server file again", reads, legacyReads)
    }

    /**
     * Killed after writing the list but before deleting device.sealed: the
     * next start deletes it, does not migrate it a second time, and so cannot
     * bring back a pairing changed or removed since.
     */
    @Test
    fun aDeviceSealedLeftByAKillIsDeletedNotMigratedAgain() {
        oldAppWrites(paired)
        open { repo, _ -> repo.updateDevice(Tenants.DEFAULT) { it.copy(token = tokenA2) } }
        oldAppWrites(paired) // the file as it was before the migration's delete
        val t = open { repo, _ -> repo.read() }
        assertEquals(tokenA2, t.byId(Tenants.DEFAULT)!!.device.token)
        assertFalse(deviceFile.exists())
    }

    /** Nothing writes device.sealed after the migration: not a change to tenant default, not its removal. */
    @Test
    fun nothingWritesDeviceSealedAfterTheMigration() {
        oldAppWrites(paired)
        open { repo, _ ->
            repo.read()
            assertFalse(deviceFile.exists())
            repo.updateDevice(Tenants.DEFAULT) { it.copy(token = tokenA2) }
            assertFalse(deviceFile.exists())
            repo.add(originB, multiProfile = true)
            repo.remove(Tenants.DEFAULT)
            assertFalse(deviceFile.exists())
        }
    }

    // --- add, select, remove, persist -------------------------------------------------------

    @Test
    fun aServerIsAddedUnpairedAndCurrentWithItsOwnColourAndProfile() {
        oldAppWrites(paired)
        val (r, t) = open { repo, _ -> repo.add(originB, multiProfile = true) to repo.read() }
        val added = (r as TenantRepository.AddResult.Added).tenant
        assertEquals("t1", added.id)
        assertEquals(DeviceRecord(origin = originB), added.device)
        assertFalse(added.device.paired)
        assertEquals("b.example.test:8443", added.label)
        assertEquals("tenant-t1", added.webProfile)
        assertNotEquals(t.byId(Tenants.DEFAULT)!!.colour, added.colour)
        assertEquals("t1", t.current)
        assertEquals(listOf(Tenants.DEFAULT, "t1"), t.all.map { it.id })
    }

    @Test
    fun theSameOriginIsNeverAddedTwice() {
        oldAppWrites(paired)
        val r = open { repo, _ -> repo.add(originA, multiProfile = true) }
        assertEquals(Tenants.DEFAULT, (r as TenantRepository.AddResult.Duplicate).tenant.id)
        assertEquals(1, open { repo, _ -> repo.read() }.all.size)
    }

    /** A second server needs WebView profiles; the first never does (it takes the default profile). */
    @Test
    fun aSecondServerNeedsWebViewProfiles() {
        val first = open { repo, _ -> repo.add(originA, multiProfile = false) }
        assertEquals(Tenants.DEFAULT_PROFILE, (first as TenantRepository.AddResult.Added).tenant.webProfile)
        val second = open { repo, _ -> repo.add(originB, multiProfile = false) }
        assertEquals(TenantRepository.AddResult.NeedsWebViewProfiles, second)
        assertEquals(1, open { repo, _ -> repo.read() }.all.size)
    }

    /** The tenant in view is kept across restarts. */
    @Test
    fun theActiveTenantPersistsAcrossRestarts() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true) } // t1, now current
        open { repo, _ -> assertTrue(repo.select(Tenants.DEFAULT)) }
        assertEquals(Tenants.DEFAULT, open { repo, _ -> repo.read() }.current)
        open { repo, _ -> assertTrue(repo.select("t1")) }
        assertEquals("t1", open { repo, _ -> repo.read() }.active!!.id)
        assertFalse(open { repo, _ -> repo.select("nope") })
        assertEquals("t1", open { repo, _ -> repo.read() }.current)
    }

    @Test
    fun removingTheCurrentTenantMovesToTheNextOne() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true) }
        val t = open { repo, _ -> repo.remove("t1"); repo.read() }
        assertEquals(Tenants.DEFAULT, t.current)
        assertEquals(listOf(Tenants.DEFAULT), t.all.map { it.id })
        assertEquals("tenant default untouched", paired, t.byId(Tenants.DEFAULT)!!.device)
        val empty = open { repo, _ -> repo.remove(Tenants.DEFAULT); repo.read() }
        assertNull(empty.current)
        assertEquals(emptyList<TenantRecord>(), empty.all)
    }

    @Test
    fun noTwoTenantsEverShareAnOriginOrAProfile() {
        oldAppWrites(paired)
        open { repo, _ ->
            repo.add(originB, multiProfile = true)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.update { t -> t.copy(all = t.all.map { it.copy(device = it.device.copy(origin = originA)) }) } }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.update { t -> t.copy(all = t.all.map { it.copy(webProfile = "same") }) } }
            }
        }
        val t = open { repo, _ -> repo.read() }
        assertEquals(setOf(originA, originB), t.all.map { it.origin }.toSet())
    }

    /** A tenants.sealed that cannot be opened (the Keystore key is gone) reads as no servers: the credentials cannot be recovered. */
    @Test
    fun anUnreadableListReadsAsNoServers() {
        oldAppWrites(paired)
        open { repo, _ -> repo.read() }
        tenantsFile.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(emptyList<TenantRecord>(), open { repo, _ -> repo.read() }.all)
    }

    @Test
    fun theListIsNeverOnDiskInTheClear() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true); repo.updateDevice("t1") { it.copy(token = tokenB) } }
        val disk = String(tenantsFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse(disk.contains("BBBBBBBB"))
        assertFalse(disk.contains("b.example.test"))
    }

    // --- sign-in origins -----------------------------------------------------------------

    /** A tenant stored before the field existed reads with none; a stored list survives a restart, per tenant. */
    @Test
    fun signInOriginsArePerTenantAndPersist() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true) } // t1
        assertEquals(emptyList<String>(), open { repo, _ -> repo.read() }.byId(Tenants.DEFAULT)!!.ssoOrigins)
        assertTrue(open { repo, _ -> repo.setSsoOrigins("t1", listOf("https://auth.example.test")) })
        assertFalse("the same list again changes nothing", open { repo, _ -> repo.setSsoOrigins("t1", listOf("https://auth.example.test")) })
        assertFalse("a tenant that does not exist is not created", open { repo, _ -> repo.setSsoOrigins("nope", listOf("https://x.example.test")) })
        val t = open { repo, _ -> repo.read() }
        assertEquals(listOf("https://auth.example.test"), t.byId("t1")!!.ssoOrigins)
        assertEquals("the other tenant is untouched", emptyList<String>(), t.byId(Tenants.DEFAULT)!!.ssoOrigins)
        assertEquals(listOf(Tenants.DEFAULT, "t1"), t.all.map { it.id })
    }
}
