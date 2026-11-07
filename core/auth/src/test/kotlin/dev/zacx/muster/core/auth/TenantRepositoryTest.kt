package dev.zacx.muster.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    /** One "process": a repository over the two files; the stores are closed after [block]. */
    private fun <T> open(block: suspend (TenantRepository, DeviceStore) -> T): T = runBlocking {
        val job = SupervisorJob()
        try {
            val scope = CoroutineScope(Dispatchers.IO + job)
            val legacy = DataStoreDeviceStore(deviceFile, AesGcmAead { k }, scope)
            val repo = TenantRepository(
                DataStoreTenantsFile(tenantsFile, AesGcmAead { k }, scope),
                legacy,
                legacyDigest = { TenantRepository.fileDigest(deviceFile) },
                newId = { "t" + (++ids) },
            )
            block(repo, legacy)
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

    /** What an app before multi-tenant reads from device.sealed (AAD v1 only). */
    private fun oldAppReads(): DeviceRecord = runBlocking {
        val job = SupervisorJob()
        try {
            DataStoreDeviceStore(deviceFile, AesGcmAead { k }, CoroutineScope(Dispatchers.IO + job)).read()
        } finally {
            job.cancelAndJoin()
        }
    }

    // --- migration -----------------------------------------------------------------------

    /** The upgrade: today's sealed pairing becomes tenant "default", current, with nothing re-paired and device.sealed untouched. */
    @Test
    fun theExistingPairingMigratesIntoTenantDefault() {
        oldAppWrites(paired)
        val before = deviceFile.readBytes()

        val t = open { repo, _ -> repo.read() }

        val d = t.all.single()
        assertEquals(Tenants.DEFAULT, d.id)
        assertEquals(paired, d.device) // the credential, key alias and push state, all kept
        assertEquals("a.example.test", d.label)
        assertEquals(Tenants.DEFAULT_PROFILE, d.webProfile) // the pre-upgrade cookies' profile
        assertEquals(Tenants.DEFAULT, d.pushInstance) // the pre-upgrade UnifiedPush instance
        assertEquals(Tenants.DEFAULT, t.current)
        assertTrue(t.migrated)
        assertArrayEquals("device.sealed must be left byte for byte", before, deviceFile.readBytes())
        assertEquals("an older app still reads it", paired, oldAppReads())
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
        val t = open { repo, _ -> repo.read() }
        assertEquals(listOf("t1"), t.all.map { it.id })
        assertEquals("removing tenant default empties device.sealed", DeviceRecord(), oldAppReads())
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

    // --- device.sealed stays a v1 copy of tenant default --------------------------------------

    /** A change to tenant default (a rotated credential) is in device.sealed, readable by an older app. */
    @Test
    fun aChangeToTenantDefaultIsWrittenToDeviceSealedAsV1() {
        oldAppWrites(paired)
        open { repo, _ -> repo.updateDevice(Tenants.DEFAULT) { it.copy(token = tokenA2) } }
        assertEquals(paired.copy(token = tokenA2), oldAppReads())
        // A change to another tenant never touches it.
        val bytes = deviceFile.readBytes()
        open { repo, _ -> repo.add(originB, multiProfile = true); repo.updateDevice("t1") { it.copy(token = tokenB) } }
        assertArrayEquals(bytes, deviceFile.readBytes())
    }

    /**
     * Killed between the two writes (device.sealed has the rotated credential,
     * tenants.sealed does not): the restart keeps the NEW credential and
     * changes no other tenant.
     */
    @Test
    fun aKillBetweenTheTwoWritesKeepsTheNewCredential() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true); repo.updateDevice("t1") { it.copy(token = tokenB, deviceId = 9) } }
        val other = open { repo, _ -> repo.read().byId("t1") }
        oldAppWrites(paired.copy(token = tokenA2)) // write 1 of 2, then the kill

        val t = open { repo, _ -> repo.read() }
        assertEquals(tokenA2, t.byId(Tenants.DEFAULT)!!.device.token)
        assertEquals(other, t.byId("t1"))
        assertEquals("t1", t.current)
    }

    /** Control: device.sealed matching the recorded digest changes nothing; the list is authoritative. */
    @Test
    fun aMatchingDeviceSealedChangesNothing() {
        oldAppWrites(paired)
        open { repo, _ -> repo.update { t -> t.copy(all = t.all.map { it.copy(label = "renamed") }) } }
        val t = open { repo, _ -> repo.read() }
        assertEquals("renamed", t.byId(Tenants.DEFAULT)!!.label)
        assertEquals(paired, t.byId(Tenants.DEFAULT)!!.device)
    }

    /** Control: an older app's change to device.sealed (after a downgrade) is taken. */
    @Test
    fun anOlderAppsChangeIsTaken() {
        oldAppWrites(paired)
        open { repo, _ -> repo.read() }
        oldAppWrites(paired.unpaired()) // the old app unpaired
        assertFalse(open { repo, _ -> repo.read() }.byId(Tenants.DEFAULT)!!.device.paired)
    }

    /** An older app re-paired tenant default to another tenant's server: refused, never two tenants on one origin. */
    @Test
    fun anOlderAppsRepairToAnotherTenantsOriginIsRefused() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true); repo.updateDevice("t1") { it.copy(token = tokenB) } }
        oldAppWrites(DeviceRecord(origin = originB, token = "mdv_" + "X".repeat(43), deviceId = 77))

        val t = open { repo, _ -> repo.read() }
        assertEquals(listOf(originA, originB), t.all.map { it.origin })
        assertFalse(t.byId(Tenants.DEFAULT)!!.device.paired)
        assertEquals(tokenB, t.byId("t1")!!.device.token)
        assertNotNull(t.notice)
        assertEquals("device.sealed is a copy of tenant default again", t.byId(Tenants.DEFAULT)!!.device, oldAppReads())
        open { repo, _ -> repo.clearNotice() }
        assertNull(open { repo, _ -> repo.read() }.notice)
    }

    /** A tenants.sealed that cannot be opened (the Keystore key is gone) starts over from device.sealed, under the same key. */
    @Test
    fun anUnreadableListStartsOverFromDeviceSealed() {
        oldAppWrites(paired)
        open { repo, _ -> repo.read() }
        tenantsFile.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(paired, open { repo, _ -> repo.read() }.byId(Tenants.DEFAULT)!!.device)
    }

    @Test
    fun theListIsNeverOnDiskInTheClear() {
        oldAppWrites(paired)
        open { repo, _ -> repo.add(originB, multiProfile = true); repo.updateDevice("t1") { it.copy(token = tokenB) } }
        val disk = String(tenantsFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse(disk.contains("BBBBBBBB"))
        assertFalse(disk.contains("b.example.test"))
    }
}
