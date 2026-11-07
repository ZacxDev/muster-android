package dev.zacx.muster.core.auth

import dev.zacx.muster.core.net.DeviceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** The tenant file in memory. */
private class MemTenantsFile : TenantsFile {
    val state = MutableStateFlow(Tenants())
    override val data: Flow<Tenants> = state
    override suspend fun write(t: Tenants) {
        state.value = t
    }
}

/**
 * 🔴 Each tenant's credential reaches only that tenant (muster
 * docs/design/multi-tenant.md, "Security invariants"): its [TenantDeviceStore]
 * reads and writes that tenant's pairing and nothing else, and a pairing,
 * an unpair or a revocation through it never touches another tenant.
 */
class TenantCredentialIsolationTest {
    private val tokenA = "mdv_" + "A".repeat(43)
    private val tokenB = "mdv_" + "B".repeat(43)
    private val code = "mdp_" + "C".repeat(43)
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    /** A fake muster that pairs any code as [token] and records every request. */
    private fun muster(token: String, seen: MutableList<String>): MockWebServer = MockWebServer().also { s ->
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.method + " " + request.url.encodedPath + " " + request.headers["Authorization"].orEmpty()
                return when (request.method + " " + request.url.encodedPath) {
                    "POST /api/device/pair" -> MockResponse.Builder().code(201)
                        .body("""{"token":"$token","device":{"id":12,"name":"p","stepup_key":false}}""").build()
                    "DELETE /api/device" -> MockResponse.Builder().code(204).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        s.start()
        servers += s
    }

    private fun repoWith(vararg t: TenantRecord): TenantRepository {
        val f = MemTenantsFile()
        f.state.value = Tenants(current = t.first().id, all = t.toList(), migrated = true)
        return TenantRepository(f, readLegacy = { DeviceRecord() }, deleteLegacy = {})
    }

    private fun tenant(id: String, origin: String, token: String? = null) =
        TenantRecord(id, id, 0, "tenant-$id", DeviceRecord(origin = origin, token = token, deviceId = token?.let { 1L }))

    @Test
    fun eachStoreReadsAndWritesOnlyItsOwnTenant() = runBlocking {
        val repo = repoWith(tenant("a", "https://a.example.test", tokenA), tenant("b", "https://b.example.test", tokenB))
        val a = repo.deviceStore("a")
        val b = repo.deviceStore("b")
        assertEquals(tokenA, a.read().token)
        assertEquals(tokenB, b.read().token)

        a.update { it.copy(token = null, pushStatus = PushStatus.UNREGISTERED) }
        assertNull(a.read().token)
        assertEquals("tenant b untouched", tenant("b", "https://b.example.test", tokenB), repo.read().byId("b"))
        assertEquals(tokenB, b.records.first().token)
    }

    /** A pairing write never moves a tenant to another server: its origin is the pin for its credential. */
    @Test
    fun aWriteCannotMoveATenantsOrigin() = runBlocking {
        val repo = repoWith(tenant("a", "https://a.example.test", tokenA))
        repo.deviceStore("a").update { DeviceRecord() } // what "change server" did to the single record
        assertEquals("https://a.example.test", repo.read().byId("a")!!.origin)
        repo.deviceStore("a").update { it.copy(origin = "https://elsewhere.example.test") }
        assertEquals("https://a.example.test", repo.read().byId("a")!!.origin)
    }

    /** A late write for a removed tenant (a push registration finishing after removal) recreates nothing. */
    @Test
    fun aRemovedTenantsStoreWritesNothing() = runBlocking {
        val repo = repoWith(tenant("a", "https://a.example.test", tokenA), tenant("b", "https://b.example.test", tokenB))
        val b = repo.deviceStore("b")
        repo.remove("b")
        assertEquals(DeviceRecord(), b.update { it.copy(token = tokenB, pushStatus = PushStatus.REGISTERED) })
        assertEquals(listOf("a"), repo.read().all.map { it.id })
        assertEquals(DeviceRecord(), b.read())
    }

    /**
     * Pairing tenant B (through B's store, against B's server) stores B's
     * credential in B only, never unpairs tenant A's credential, and sends
     * nothing at all to A's server.
     */
    @Test
    fun pairingOneTenantNeverTouchesAnother() = runBlocking {
        val seenA = Collections.synchronizedList(mutableListOf<String>())
        val seenB = Collections.synchronizedList(mutableListOf<String>())
        val a = muster(tokenA, seenA)
        val b = muster(tokenB, seenB)
        val originA = a.url("/").toString().trimEnd('/')
        val originB = b.url("/").toString().trimEnd('/')
        val repo = repoWith(tenant("a", originA, tokenA), tenant("b", originB))
        val keys = FakeStepUpKeys(caps(bio = false, secure = false))

        val out = Pairing(DeviceApi(originB), keys, repo.deviceStore("b"), io = Dispatchers.IO).pair(code, null, "p", "1")

        assertTrue("$out", out is Pairing.Outcome.Paired)
        val t = repo.read()
        assertEquals(tokenB, t.byId("b")!!.device.token)
        assertEquals(tokenA, t.byId("a")!!.device.token)
        assertEquals(listOf("POST /api/device/pair "), seenB)
        assertEquals("tenant A's server got nothing", emptyList<String>(), seenA)
    }

    /** Re-pairing tenant A unpairs A's OLD credential at A's server only. */
    @Test
    fun rePairingUnpairsOnlyThatTenantsOldCredential() = runBlocking {
        val seenA = Collections.synchronizedList(mutableListOf<String>())
        val seenB = Collections.synchronizedList(mutableListOf<String>())
        val newA = "mdv_" + "N".repeat(43)
        val a = muster(newA, seenA)
        val b = muster(tokenB, seenB)
        val originA = a.url("/").toString().trimEnd('/')
        val originB = b.url("/").toString().trimEnd('/')
        val repo = repoWith(tenant("a", originA, tokenA), tenant("b", originB, tokenB))

        Pairing(DeviceApi(originA), FakeStepUpKeys(caps(bio = false, secure = false)), repo.deviceStore("a"), io = Dispatchers.IO)
            .pair(code, null, "p", "1")

        assertEquals(listOf("POST /api/device/pair ", "DELETE /api/device Bearer $tokenA"), seenA.toList())
        assertEquals(emptyList<String>(), seenB.toList())
        assertEquals(newA, repo.read().byId("a")!!.device.token)
        assertEquals(tokenB, repo.read().byId("b")!!.device.token)
    }
}
