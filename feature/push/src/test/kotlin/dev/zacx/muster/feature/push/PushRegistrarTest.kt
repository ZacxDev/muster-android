package dev.zacx.muster.feature.push

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.core.net.DeviceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushRegistrarTest {
    private val server = MockWebServer()
    private val token = "mdv_" + "T".repeat(43)
    private var forgot = 0

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun paired(hosts: List<String> = listOf("push.example.test")) =
        DeviceRecord(origin = server.url("/").toString().trimEnd('/'), token = token, deviceId = 1, pushHosts = hosts)

    private fun registrar(store: InMemoryDeviceStore) =
        PushRegistrar(store, ::DeviceApi, onForget = { forgot++ }, io = Dispatchers.IO)

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Test
    fun theHostCheckIsExact() {
        val hosts = listOf("push.example.test")
        assertEquals(PushHostCheck.Verdict.Ok, PushHostCheck.check("https://push.example.test/upAbC?up=1", hosts))
        assertEquals(PushHostCheck.Verdict.Ok, PushHostCheck.check("https://PUSH.example.test:443/upAbC", listOf("push.example.test.")))
        listOf(
            "https://ntfy.sh/upAbC", // ntfy's public default
            "https://evil.push.example.test/upAbC",
            "https://push.example.test.evil.test/upAbC",
            "http://push.example.test/upAbC",
            "https://push.example.test:8443/upAbC",
            "https://u@push.example.test/upAbC",
            "not a url",
        ).forEach { assertTrue(it, PushHostCheck.check(it, hosts) is PushHostCheck.Verdict.Mismatch) }
        assertTrue(PushHostCheck.check("https://push.example.test/upAbC", emptyList()) is PushHostCheck.Verdict.Mismatch)
    }

    /** The wrong distributor server: tell the user what to set, and do not register. */
    @Test
    fun aWrongPushHostIsNotRegistered() = runTest {
        val store = InMemoryDeviceStore(paired())
        val s = registrar(store).onNewEndpoint("https://ntfy.sh/upAbC", "pk", "au")
        assertEquals(PushStatus.HOST_MISMATCH, s)
        assertEquals(0, server.requestCount)
        assertTrue(store.value.pushNote!!.contains("https://push.example.test"))
    }

    @Test
    fun theOperatorsHostIsRegistered() = runTest {
        server.enqueue(json(200, """{"ok":true}"""))
        val store = InMemoryDeviceStore(paired())
        assertEquals(PushStatus.REGISTERED, registrar(store).onNewEndpoint("https://push.example.test/upAbC", "pk", "au"))
        val req = server.takeRequest()
        assertEquals("PUT /api/device/push", "${req.method} ${req.url.encodedPath}")
        assertEquals("https://push.example.test/upAbC", store.value.pushEndpoint)
    }

    /** No public key set: the phone could not decrypt, so nothing is registered. */
    @Test
    fun anEndpointWithoutKeysIsRefused() = runTest {
        val store = InMemoryDeviceStore(paired())
        assertEquals(PushStatus.REFUSED, registrar(store).onNewEndpoint("https://push.example.test/upAbC", null, null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mustersRefusalIsShown() = runTest {
        server.enqueue(json(422, """{"error":"the push endpoint did not accept a test push (status 403)"}"""))
        val store = InMemoryDeviceStore(paired())
        assertEquals(PushStatus.REFUSED, registrar(store).onNewEndpoint("https://push.example.test/upAbC", "pk", "au"))
        assertTrue(store.value.pushNote!!.contains("did not accept a test push"))
    }

    @Test
    fun configRecordsHostsAndKeyAndAsksToRegister() = runTest {
        server.enqueue(json(200, """{"server_version":"x","push":{"served":true,"vapid_public_key":"BPk","hosts":["push.example.test"]},"pairing":{"pin_keys_allowed":false},"features":{"push":true,"actions":false}}"""))
        val store = InMemoryDeviceStore(paired(hosts = emptyList()))
        assertEquals(PushRegistrar.ConfigResult.Register("BPk"), registrar(store).refreshConfig())
        assertEquals(listOf("push.example.test"), store.value.pushHosts)
        assertEquals("BPk", store.value.vapidKey)
    }

    @Test
    fun aServerWithoutPushSaysSo() = runTest {
        server.enqueue(json(200, """{"push":{"served":false,"vapid_public_key":"","hosts":[]},"features":{"push":false}}"""))
        val store = InMemoryDeviceStore(paired())
        assertEquals(PushRegistrar.ConfigResult.NoPush, registrar(store).refreshConfig())
        assertEquals(PushStatus.SERVER_HAS_NO_PUSH, store.value.pushStatus)
    }

    /** muster ended the pairing (removed on Account → Devices, disabled, signed out everywhere). */
    @Test
    fun aRevokedDeviceForgetsItsPairingAndKey() = runTest {
        server.enqueue(json(401, """{"error":"this route takes a paired device credential"}"""))
        val store = InMemoryDeviceStore(paired().copy(keyKind = "biometric"))
        assertEquals(PushRegistrar.ConfigResult.Revoked, registrar(store).refreshConfig())
        assertNull(store.value.token)
        assertNull(store.value.keyKind)
        assertEquals(1, forgot)
    }
}
