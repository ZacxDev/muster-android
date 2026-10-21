package dev.zacx.muster.core.auth

import dev.zacx.muster.core.net.DeviceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Pairing against a fake muster that applies muster's own pairing rules
 * (internal/devices checkKey + Redeem): a software key and, with the PIN-key
 * policy off, a biometric-or-PIN key are refused with 403 WITHOUT spending
 * the code; a good code is spent once.
 */
class PairingTest {
    private val server = MockWebServer()
    private val code = "mdp_" + "A".repeat(43)
    private val newToken = "mdv_" + "N".repeat(43)
    private val oldToken = "mdv_" + "O".repeat(43)

    private var pinKeysAllowed = false
    private var codeSpent = false
    private val pairBodies = mutableListOf<JsonObject>()
    private val unpairAuth = mutableListOf<String?>()
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@PairingTest) {
                when (request.method + " " + request.url.encodedPath) {
                    "POST /api/device/pair" -> pairRoute(Json.parseToJsonElement(request.body!!.utf8()).jsonObject)
                    "DELETE /api/device" -> {
                        calls += "unpair"
                        unpairAuth += request.headers["Authorization"]
                        MockResponse.Builder().code(204).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun pairRoute(b: JsonObject): MockResponse {
        calls += "pair"
        pairBodies += b
        fun s(k: String) = b[k]?.jsonPrimitive?.content
        val key = s("stepup_pubkey")
        if (key != null) {
            if (s("stepup_key_level") == "software") return err(403, "a software-only key cannot gate actions")
            if (s("stepup_key_type") == "biometric_or_credential" && !pinKeysAllowed) {
                return err(403, "this server does not accept biometric-or-PIN keys")
            }
        }
        if (s("code") != code || codeSpent) return err(401, "this pairing code cannot be used")
        codeSpent = true
        val dev = if (key == null) """{"id":11,"name":"P","stepup_key":false,"stepup_key_type":"","stepup_key_level":""}"""
        else """{"id":11,"name":"P","stepup_key":true,"stepup_key_type":"${s("stepup_key_type")}","stepup_key_level":"${s("stepup_key_level")}"}"""
        return MockResponse.Builder().code(201).body("""{"token":"$newToken","device":$dev}""").build()
    }

    private fun err(code: Int, msg: String) = MockResponse.Builder().code(code).body("""{"error":"$msg"}""").build()

    private val clock = Instant.parse("2026-10-21T12:00:00Z")
    private val future = clock.plusSeconds(60)
    private var aliases = 0

    private fun pairing(keys: StepUpKeys, store: DeviceStore) =
        Pairing(DeviceApi(server.url("/").toString()), keys, store, Dispatchers.IO, now = { clock }, newAlias = { "alias-${++aliases}" })

    private val origin = "https://m.example.test"

    @Test
    fun aBiometricPhonePairsWithABiometricKey() = runTest {
        val keys = FakeStepUpKeys(caps(true, true))
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")

        assertEquals(Pairing.Outcome.Paired(11, KeyKind.BIOMETRIC, null), out)
        assertEquals(listOf(KeyKind.BIOMETRIC), keys.generated)
        assertEquals(mapOf("alias-1" to KeyKind.BIOMETRIC), keys.present)
        assertEquals("SPKI-biometric", pairBodies.single()["stepup_pubkey"]!!.jsonPrimitive.content)
        assertEquals("trusted_environment", pairBodies.single()["stepup_key_level"]!!.jsonPrimitive.content)
        assertEquals(
            DeviceRecord(origin = origin, token = newToken, deviceId = 11, keyKind = "biometric", keyLevel = "trusted_environment", keyAlias = "alias-1"),
            store.value,
        )
    }

    /**
     * 🔴 NEVER A SILENT FALLBACK. A phone WITH a strong biometric whose key
     * cannot be made pairs read-only and says so; it does not quietly make a
     * PIN key instead.
     */
    @Test
    fun aFailedBiometricKeyIsReadOnlyNeverAPinKey() = runTest {
        val keys = FakeStepUpKeys(caps(true, true), failGenerate = true)
        pinKeysAllowed = true // even where muster would accept a PIN key
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")

        assertEquals(Pairing.Outcome.Paired(11, null, StepUpPolicy.KEY_FAILED), out)
        assertEquals(listOf(KeyKind.BIOMETRIC), keys.generated)
        assertEquals(setOf("code", "name", "app_version"), pairBodies.single().keys)
        assertNull(store.value.keyKind)
        assertEquals(StepUpPolicy.KEY_FAILED, store.value.keyNote)
    }

    /** muster refuses software keys; the app does not even send one. */
    @Test
    fun aSoftwareKeyIsDroppedAndThePhonePairsReadOnly() = runTest {
        val keys = FakeStepUpKeys(caps(true, true), level = SecurityLevel.SOFTWARE)
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")

        assertEquals(Pairing.Outcome.Paired(11, null, StepUpPolicy.SOFTWARE_KEY), out)
        assertEquals(1, pairBodies.size)
        assertEquals(setOf("code", "name", "app_version"), pairBodies.single().keys)
        assertTrue(keys.present.isEmpty())
    }

    /**
     * A PIN-only phone offers a biometric-or-PIN key. With muster's policy off
     * the 403 does not spend the code, and the SAME code pairs read-only, with
     * a note saying why.
     */
    @Test
    fun aRefusedPinKeyRetriesReadOnlyWithTheSameCode() = runTest {
        val keys = FakeStepUpKeys(caps(false, true))
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")

        out as Pairing.Outcome.Paired
        assertNull(out.keyKind)
        assertTrue(out.note!!.contains("does not accept PIN keys"))
        assertEquals(2, pairBodies.size)
        assertEquals("biometric_or_credential", pairBodies[0]["stepup_key_type"]!!.jsonPrimitive.content)
        assertEquals(setOf("code", "name", "app_version"), pairBodies[1].keys)
        assertEquals(code, pairBodies[1]["code"]!!.jsonPrimitive.content)
        assertTrue("the refused key is deleted", keys.present.isEmpty())
        assertEquals(newToken, store.value.token)
    }

    /** With muster's PIN-key policy on, the PIN key is kept, and the phone says it is PIN-gated. */
    @Test
    fun anAcceptedPinKeyIsSaidOutLoud() = runTest {
        pinKeysAllowed = true
        val keys = FakeStepUpKeys(caps(false, true))
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")

        assertEquals(Pairing.Outcome.Paired(11, KeyKind.BIOMETRIC_OR_CREDENTIAL, StepUpPolicy.PIN_KEY), out)
        assertEquals("biometric_or_credential", store.value.keyKind)
    }

    @Test
    fun aBadCodeStoresNothingAndDeletesTheNewKey() = runTest {
        codeSpent = true
        val keys = FakeStepUpKeys(caps(true, true))
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")

        assertTrue(out is Pairing.Outcome.Failed)
        assertNull(store.value.token)
        assertTrue(keys.present.isEmpty())
    }

    @Test
    fun aStringThatIsNotACodeNeverReachesMuster() = runTest {
        val keys = FakeStepUpKeys(caps(true, true))
        val out = pairing(keys, InMemoryDeviceStore(DeviceRecord(origin = origin))).pair("mdv_" + "A".repeat(43), future, "P", "0.1.0")
        assertEquals(Pairing.Outcome.Failed("That is not a pairing code."), out)
        assertEquals(0, server.requestCount)
        assertTrue(keys.generated.isEmpty())
    }

    /**
     * 🔴 Re-pairing REDEEMS FIRST, with the old credential still in hand, and
     * only then unpairs the old pairing (with ITS credential) and deletes ITS key.
     */
    @Test
    fun rePairingRedeemsBeforeUnpairingTheOld() = runTest {
        val keys = FakeStepUpKeys(caps(true, true)).apply { present["alias-old"] = KeyKind.BIOMETRIC }
        val store = InMemoryDeviceStore(
            DeviceRecord(origin = origin, token = oldToken, deviceId = 3, keyKind = "biometric", keyAlias = "alias-old", pushStatus = PushStatus.REGISTERED),
        )
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")
        assertTrue(out is Pairing.Outcome.Paired)
        assertEquals(listOf("pair", "unpair"), calls)
        assertEquals(listOf("Bearer $oldToken"), unpairAuth)
        assertEquals(newToken, store.value.token)
        assertEquals("alias-1", store.value.keyAlias)
        assertEquals(mapOf("alias-1" to KeyKind.BIOMETRIC), keys.present)
        assertEquals(PushStatus.NONE, store.value.pushStatus)
    }

    /** "Re-confirm this phone" with an expired code: nothing is touched, not even the network. */
    @Test
    fun anExpiredCodeOnReconfirmLeavesTheOldPairingIntact() = runTest {
        val keys = FakeStepUpKeys(caps(true, true)).apply { present["alias-old"] = KeyKind.BIOMETRIC }
        val oldRecord = DeviceRecord(origin = origin, token = oldToken, deviceId = 3, keyKind = "biometric", keyAlias = "alias-old")
        val store = InMemoryDeviceStore(oldRecord)
        val out = pairing(keys, store).pair(code, clock.minusSeconds(1), "P", "0.1.0")
        assertEquals(Pairing.Outcome.Failed(Pairing.EXPIRED), out)
        assertEquals(0, server.requestCount)
        assertEquals(oldRecord, store.value)
        assertEquals(mapOf("alias-old" to KeyKind.BIOMETRIC), keys.present)
        assertTrue(keys.generated.isEmpty())
        // Expiring exactly now is expired too.
        assertEquals(Pairing.Outcome.Failed(Pairing.EXPIRED), pairing(keys, store).pair(code, clock, "P", "0.1.0"))
    }

    /** A code muster refuses on re-confirm leaves the old pairing and its key intact. */
    @Test
    fun aRefusedCodeOnReconfirmLeavesTheOldPairingIntact() = runTest {
        codeSpent = true
        val keys = FakeStepUpKeys(caps(true, true)).apply { present["alias-old"] = KeyKind.BIOMETRIC }
        val oldRecord = DeviceRecord(origin = origin, token = oldToken, deviceId = 3, keyKind = "biometric", keyAlias = "alias-old")
        val store = InMemoryDeviceStore(oldRecord)
        assertTrue(pairing(keys, store).pair(code, future, "P", "0.1.0") is Pairing.Outcome.Failed)
        assertEquals(listOf("pair"), calls)
        assertEquals(oldRecord, store.value)
        assertEquals(mapOf("alias-old" to KeyKind.BIOMETRIC), keys.present)
    }

    /** Re-pairing a phone that can no longer make a key leaves no old key behind. */
    @Test
    fun aReadOnlyRepairDeletesTheOldKey() = runTest {
        val keys = FakeStepUpKeys(caps(false, false)).apply { present["alias-old"] = KeyKind.BIOMETRIC }
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin, token = oldToken, deviceId = 3, keyKind = "biometric", keyAlias = "alias-old"))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")
        assertEquals(Pairing.Outcome.Paired(11, null, StepUpPolicy.NO_LOCK), out)
        assertTrue(keys.present.isEmpty())
        assertTrue(keys.generated.isEmpty())
        assertNull(store.value.keyAlias)
    }

    /** A strong biometric that is only unavailable right now never yields a PIN key. */
    @Test
    fun aTransientBiometricErrorPairsReadOnlyNotPin() = runTest {
        pinKeysAllowed = true
        val keys = FakeStepUpKeys(StepUpPolicy.Capabilities(StepUpPolicy.Biometric.Unavailable(1), deviceSecure = true))
        val store = InMemoryDeviceStore(DeviceRecord(origin = origin))
        val out = pairing(keys, store).pair(code, future, "P", "0.1.0")
        assertEquals(Pairing.Outcome.Paired(11, null, StepUpPolicy.BIOMETRIC_UNAVAILABLE), out)
        assertTrue(keys.generated.isEmpty())
        assertEquals(setOf("code", "name", "app_version"), pairBodies.single().keys)
    }
}
