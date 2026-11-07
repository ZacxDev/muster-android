package dev.zacx.muster.tenants

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.TenantRepository
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.core.auth.TenantsFile
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.feature.shell.TenantSessions
import dev.zacx.muster.refresh.RefreshSchedule
import dev.zacx.muster.shell.ShellLocalState
import dev.zacx.muster.shell.ShellSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

internal class MemTenantsFile(initial: Tenants = Tenants(migrated = true)) : TenantsFile {
    val state = MutableStateFlow(initial)
    override val data: Flow<Tenants> = state
    override suspend fun write(t: Tenants) { state.value = t }
}

/** Profiles as cookie jars; records what was forgotten. */
internal class JarProfiles(override val multiProfile: Boolean = true) : TenantSessions.Backend<String> {
    val jars = mutableMapOf<String, MutableMap<String, String>>()
    val deleted = mutableListOf<String>()
    var deleteFails = false
    override fun setProfile(view: String, name: String) {}
    override fun delete(name: String): Boolean {
        if (deleteFails || name == TenantSessions.DEFAULT) return false
        deleted += name; jars.remove(name); return true
    }
    override fun clear(name: String) { jars[name]?.clear() }
    override fun flush(name: String) {}
}

/**
 * Removing a server (operator decision 1): its pairing is ended on ITS server,
 * and its push registration, step-up key, web session and notifications go;
 * the other server keeps every one of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TenantOpsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.noBackupFilesDir, "tenant-ops-test")
    private val tokenA = "mdv_" + "A".repeat(43)
    private val tokenB = "mdv_" + "B".repeat(43)
    private lateinit var wm: WorkManager
    private val servers = mutableListOf<MockWebServer>()

    private val seenA = Collections.synchronizedList(mutableListOf<String>())
    private val seenB = Collections.synchronizedList(mutableListOf<String>())
    private val unregistered = mutableListOf<String>()
    private val deletedKeys = mutableListOf<String>()
    private val dropped = mutableListOf<String>()
    private val profiles = JarProfiles()
    private lateinit var file: MemTenantsFile
    /** For each dropNotifications call: was the tenant's record already gone? */
    private val recordGoneAtDrop = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        dir.deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerImpl.setDelegate(null)
        servers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun muster(seen: MutableList<String>) = MockWebServer().also { s ->
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.method + " " + request.url.encodedPath + " " + request.headers["Authorization"].orEmpty()
                return MockResponse.Builder().code(204).build()
            }
        }
        s.start()
        servers += s
    }

    private fun setUpTwo(): Pair<TenantOps, TenantRepository> {
        val a = muster(seenA).url("/").toString().trimEnd('/')
        val b = muster(seenB).url("/").toString().trimEnd('/')
        val ta = TenantRecord(Tenants.DEFAULT, "a", 1, Tenants.DEFAULT_PROFILE, DeviceRecord(origin = a, token = tokenA, deviceId = 1, keyAlias = "key-a"))
        val tb = TenantRecord("t2", "b", 2, "tenant-t2", DeviceRecord(origin = b, token = tokenB, deviceId = 2, keyAlias = "key-b"))
        profiles.jars[ta.webProfile] = mutableMapOf("muster_session" to "a")
        profiles.jars[tb.webProfile] = mutableMapOf("muster_session" to "b")
        file = MemTenantsFile(Tenants(current = "t2", all = listOf(ta, tb), migrated = true))
        val repo = TenantRepository(file, { DeviceRecord() }, {})
        val ops = TenantOps(
            repo, TenantSessions(profiles), { true }, ::DeviceApi, { deletedKeys += it },
            unregisterPush = { unregistered += it },
            dropNotifications = { t -> dropped += t.id; recordGoneAtDrop += file.state.value.byId(t.id) == null },
            local = ShellLocalState(ShellSnapshot(dir), { wm }),
        )
        RefreshSchedule.ensure(wm)
        return ops to repo
    }

    private fun jobState() = wm.getWorkInfosForUniqueWork(RefreshSchedule.NAME).get().singleOrNull()?.state

    @Test
    fun removingOneServerEndsItsPairingThereAndLeavesTheOtherIntact() = runBlocking {
        val (ops, repo) = setUpTwo()
        val bBefore = repo.read().byId(Tenants.DEFAULT)

        val removed = ops.remove("t2")

        assertEquals("t2", removed!!.id)
        assertEquals("its server is told, with its credential", listOf("DELETE /api/device Bearer $tokenB"), seenB.toList())
        assertEquals("the other server gets nothing", emptyList<String>(), seenA.toList())
        assertEquals(listOf("t2"), unregistered)
        assertEquals(listOf("key-b"), deletedKeys)
        assertEquals(listOf("tenant-t2"), profiles.deleted)
        assertEquals(listOf("t2"), dropped)
        assertEquals("a", profiles.jars.getValue(Tenants.DEFAULT_PROFILE)["muster_session"])
        val t = repo.read()
        assertEquals(listOf(Tenants.DEFAULT), t.all.map { it.id })
        assertEquals(bBefore, t.byId(Tenants.DEFAULT))
        assertEquals("the remaining server is now in view", Tenants.DEFAULT, t.current)
        assertEquals("another server is still paired: the job stays", WorkInfo.State.ENQUEUED, jobState())

        // The last one: its default profile is cleared (it cannot be deleted), and the job goes.
        ops.remove(Tenants.DEFAULT)
        assertTrue(profiles.jars.getValue(Tenants.DEFAULT_PROFILE).isEmpty())
        assertEquals(listOf("DELETE /api/device Bearer $tokenA"), seenA.toList())
        assertNull(repo.read().active)
        assertEquals(WorkInfo.State.CANCELLED, jobState())
    }

    /**
     * The record goes before the notifications and channels: a push arriving
     * mid-removal finds no tenant (TenantPushRouter drops an unknown
     * instance), so it cannot draw and recreate the channels being deleted.
     */
    @Test
    fun theRecordIsGoneBeforeNotificationsAndChannelsAreDropped() = runBlocking {
        val (ops, _) = setUpTwo()
        ops.remove("t2")
        assertEquals(listOf(true), recordGoneAtDrop)
    }

    /** A profile still in use at removal is recorded and deleted at the next start, not left forever. */
    @Test
    fun aProfileThatCouldNotBeDeletedIsDeletedLater() = runBlocking {
        val (ops, repo) = setUpTwo()
        profiles.deleteFails = true
        ops.remove("t2")
        assertEquals(listOf("tenant-t2"), repo.read().profilesToDelete)
        assertEquals(emptyList<String>(), profiles.deleted)

        ops.retryProfileDeletes() // still in use: kept for the next try
        assertEquals(listOf("tenant-t2"), repo.read().profilesToDelete)

        profiles.deleteFails = false // the next start: no WebView holds it
        ops.retryProfileDeletes()
        assertEquals(listOf("tenant-t2"), profiles.deleted)
        assertEquals(emptyList<String>(), repo.read().profilesToDelete)
    }

    @Test
    fun unpairingOneServerKeepsItAndTheOther() = runBlocking {
        val (ops, repo) = setUpTwo()
        ops.unpair("t2")
        val t = repo.read()
        assertFalse(t.byId("t2")!!.device.paired)
        assertEquals("the server stays, to pair again", 2, t.all.size)
        assertEquals(tokenA, t.byId(Tenants.DEFAULT)!!.device.token)
        assertEquals(listOf("t2"), unregistered)
        assertEquals(listOf("key-b"), deletedKeys)
        assertEquals("its sign-in stays: only the pairing ended", "b", profiles.jars.getValue("tenant-t2")["muster_session"])
        assertEquals(emptyList<String>(), seenA.toList())
        assertEquals(WorkInfo.State.ENQUEUED, jobState())
    }

    @Test
    fun aSecondServerNeedsWebViewProfiles() = runBlocking {
        val repo = TenantRepository(MemTenantsFile(), { DeviceRecord() }, {})
        val ops = TenantOps(repo, TenantSessions(JarProfiles(false)), { false }, ::DeviceApi, {}, {}, {}, ShellLocalState(ShellSnapshot(dir), { wm }))
        assertTrue(ops.add("https://a.example.test") is TenantRepository.AddResult.Added)
        assertEquals(TenantRepository.AddResult.NeedsWebViewProfiles, ops.add("https://b.example.test"))
    }

    // --- sign-in origins -----------------------------------------------------------------

    private fun webServer(vararg answers: MockResponse): String {
        val s = MockWebServer()
        answers.forEach { s.enqueue(it) }
        s.start()
        servers += s
        return s.url("/").toString().trimEnd('/')
    }

    private fun opsFor(origin: String, stored: List<String> = emptyList()): Pair<TenantOps, TenantRepository> {
        val t = TenantRecord("t9", "x", 1, "tenant-t9", DeviceRecord(origin = origin), ssoOrigins = stored)
        file = MemTenantsFile(Tenants(current = "t9", all = listOf(t), migrated = true))
        val repo = TenantRepository(file, { DeviceRecord() }, {})
        val ops = TenantOps(
            repo, TenantSessions(profiles), { true }, ::DeviceApi, {}, unregisterPush = {}, dropNotifications = {},
            local = ShellLocalState(ShellSnapshot(dir), { wm }),
        )
        return ops to repo
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun advertisedSignInOriginsAreStoredNormalisedAndFiltered() = runBlocking {
        val origin = webServer(
            json(200, """{"sso_origins":["https://Auth.Example.test/","http://plain.example.test","https://p.example.test/login","https://idp.example.test","https://auth.example.test"]}"""),
        )
        val (ops, repo) = opsFor(origin)
        assertTrue(ops.refreshSsoOrigins("t9"))
        assertEquals(listOf("https://auth.example.test", "https://idp.example.test"), repo.read().byId("t9")!!.ssoOrigins)
    }

    /** A blip never drops a working sign-in: a failed read keeps the stored list. */
    @Test
    fun aFailedReadKeepsWhatIsStored() = runBlocking {
        val stored = listOf("https://auth.example.test")
        val origin = webServer(json(503, "{}"), MockResponse.Builder().code(302).addHeader("Location", "https://auth.example.test/").build())
        val (ops, repo) = opsFor(origin, stored)
        assertFalse(ops.refreshSsoOrigins("t9"))
        assertFalse(ops.refreshSsoOrigins("t9"))
        assertEquals(stored, repo.read().byId("t9")!!.ssoOrigins)
    }

    /** The server stopped advertising (or never had the route): the list goes. */
    @Test
    fun aServerThatAdvertisesNoneClearsTheList() = runBlocking {
        val origin = webServer(json(404, "{}"))
        val (ops, repo) = opsFor(origin, listOf("https://auth.example.test"))
        assertTrue(ops.refreshSsoOrigins("t9"))
        assertEquals(emptyList<String>(), repo.read().byId("t9")!!.ssoOrigins)
    }
}
