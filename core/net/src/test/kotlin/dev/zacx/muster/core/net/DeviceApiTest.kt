package dev.zacx.muster.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceApiTest {
    private val server = MockWebServer()
    private lateinit var api: DeviceApi

    // Synthetic credentials, shaped like muster's: prefix + 43 base64url chars.
    private val code = "mdp_" + "A".repeat(43)
    private val token = "mdv_" + "B".repeat(43)

    @Before
    fun setUp() {
        server.start()
        api = DeviceApi(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun bodyOf(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun pairSendsTheKeyAndReadsTheCredential() {
        server.enqueue(json(201, """{"token":"$token","device":{"id":7,"name":"Phone","stepup_key":true,"stepup_key_type":"biometric","stepup_key_level":"strongbox"}}"""))
        val r = api.pair(DeviceApi.PairRequest(code, "Phone", "0.1.0", "c3BraQ==", "biometric", "strongbox"))
        assertEquals(
            DeviceApi.PairResult.Paired(token, DeviceApi.PairedDevice(7, "Phone", true, "biometric", "strongbox")),
            r,
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/device/pair", req.url.encodedPath)
        // The pairing route takes no credential: the code is the authority.
        assertNull(req.headers["Authorization"])
        val b = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals(
            mapOf(
                "code" to code, "name" to "Phone", "app_version" to "0.1.0", "stepup_pubkey" to "c3BraQ==",
                "stepup_key_type" to "biometric", "stepup_key_level" to "strongbox",
            ),
            b.mapValues { it.value.jsonPrimitive.content },
        )
    }

    /** muster: "the three step-up fields are all present or all absent". */
    @Test
    fun aReadOnlyPairSendsNoKeyFieldsAtAll() {
        server.enqueue(json(201, """{"token":"$token","device":{"id":8,"stepup_key":false}}"""))
        api.pair(DeviceApi.PairRequest(code, "Phone", "0.1.0"))
        assertEquals(setOf("code", "name", "app_version"), bodyOf().keys)
    }

    @Test
    fun pairRefusalsAreTold() {
        server.enqueue(json(403, """{"error":"this server does not accept biometric-or-PIN keys"}"""))
        server.enqueue(json(401, """{"error":"this pairing code cannot be used"}"""))
        server.enqueue(json(400, """{"error":"bad name"}"""))
        server.enqueue(json(409, """{"error":"too many"}"""))
        server.enqueue(json(429, """{"error":"slow down"}"""))
        server.enqueue(json(500, """{}"""))
        val req = DeviceApi.PairRequest(code, "Phone", "0.1.0")
        assertEquals(DeviceApi.PairResult.KeyRefused("this server does not accept biometric-or-PIN keys"), api.pair(req))
        assertEquals(DeviceApi.PairResult.BadCode("this pairing code cannot be used"), api.pair(req))
        assertEquals(DeviceApi.PairResult.Invalid("bad name"), api.pair(req))
        assertEquals(DeviceApi.PairResult.TooMany("too many"), api.pair(req))
        assertEquals(DeviceApi.PairResult.Throttled, api.pair(req))
        assertEquals(DeviceApi.PairResult.Failed("muster answered 500"), api.pair(req))
    }

    /** A 201 that does not carry an mdv_-shaped credential stores nothing. */
    @Test
    fun aMalformedCredentialIsNotAccepted() {
        server.enqueue(json(201, """{"token":"mst_${"C".repeat(43)}","device":{"id":7}}"""))
        assertTrue(api.pair(DeviceApi.PairRequest(code, "Phone", "0.1.0")) is DeviceApi.PairResult.Failed)
    }

    @Test
    fun theDeviceTierSendsTheBearerCredential() {
        server.enqueue(
            json(
                200,
                """{"server_version":"0.59.0","push":{"served":true,"vapid_public_key":"BPk","hosts":["push.example.test"]},
                   "pairing":{"pin_keys_allowed":false},"features":{"push":true,"actions":false,"bubbles":false,"state":false},"later":1}""",
            ),
        )
        val r = api.config(token)
        assertEquals(
            DeviceApi.Result.Ok(
                DeviceApi.Config(
                    "0.59.0",
                    DeviceApi.PushConfig(true, "BPk", listOf("push.example.test")),
                    DeviceApi.PairingPolicy(false),
                    DeviceApi.Features(push = true),
                ),
            ),
            r,
        )
        val req = server.takeRequest()
        assertEquals("/api/device/config", req.url.encodedPath)
        assertEquals("Bearer $token", req.headers["Authorization"])
    }

    @Test
    fun putPushSendsTheSubscription() {
        server.enqueue(json(200, """{"ok":true}"""))
        assertEquals(DeviceApi.Result.Ok(Unit), api.putPush(token, DeviceApi.PushRegistration("https://push.example.test/upX", "pk", "au")))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/device/push", req.url.encodedPath)
        assertEquals(
            mapOf("endpoint" to "https://push.example.test/upX", "p256dh" to "pk", "auth" to "au"),
            Json.parseToJsonElement(req.body!!.utf8()).jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    /** Only muster's own device-token refusal (internal/api DeviceTokenRefusal) ends the pairing. */
    @Test
    fun a401MeansTheDeviceWasRevoked() {
        server.enqueue(json(401, """{"error":"this route takes a paired device credential (mdv_…)"}"""))
        assertEquals(DeviceApi.Result.Revoked, api.config(token))
        server.enqueue(json(422, """{"error":"the push endpoint did not accept a test push (status 404)"}"""))
        assertEquals(
            DeviceApi.Result.Failed(422, "the push endpoint did not accept a test push (status 404)"),
            api.putPush(token, DeviceApi.PushRegistration("https://push.example.test/upX", "pk", "au")),
        )
    }

    /**
     * A 401 that is not muster's device refusal (a proxy's, an edge's, a bare
     * one) keeps the credential: the phone shows an error, it does not unpair.
     */
    @Test
    fun aForeign401DoesNotRevoke() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "text/html").body("<h1>401 Authorization Required</h1>").build())
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        server.enqueue(json(401, """{"error":"this route takes a paired device credential"}"""))
        repeat(4) {
            val r = api.config(token)
            assertTrue("got $r", r is DeviceApi.Result.Failed && r.status == 401)
        }
    }

    /** The credential goes to the origin the user entered and nowhere else. */
    @Test
    fun redirectsAreNotFollowed() {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/steal").toString()).build())
            val r = api.config(token)
            assertEquals(DeviceApi.Result.Failed(302, "muster answered 302"), r)
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun credentialShapes() {
        assertTrue(CredentialShape.ok(token))
        assertTrue(CredentialShape.codeOk(code))
        assertFalse(CredentialShape.ok(code))
        assertFalse(CredentialShape.codeOk(token))
        assertFalse(CredentialShape.ok("mdv_" + "B".repeat(42)))
        assertFalse(CredentialShape.ok("mdv_" + "B".repeat(42) + "="))
        assertFalse(CredentialShape.codeOk("mdp_" + "A".repeat(42) + "\n"))
    }
}
