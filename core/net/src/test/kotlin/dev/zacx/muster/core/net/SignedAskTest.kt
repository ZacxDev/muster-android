package dev.zacx.muster.core.net

import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The signed message and the challenge → sign → action flow, against
 * muster's contract (docs/design/android.md, "The signed ask actions, as
 * built"; internal/devices Message).
 *
 * 🔴 The expected messages are LITERALS, and their SHA-256 digests and byte
 * counts were computed outside this code (`printf '…' | sha256sum`, `wc -c`),
 * so a wrong separator, order, trailing newline, hash case or text encoding
 * fails here whatever the implementation believes.
 */
class SignedAskTest {
    private val nonce = "mdc_" + "A".repeat(43)
    private val token = "mdv_" + "B".repeat(43)

    private val answerMessage = "muster-device-action-v1\n" +
        "mdc_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
        "ask_answer\n" +
        "41\n" +
        "bef4261f394bf71fd2b565cd76396ac9ed7953f9110c69ee49d7a82871238fbf"

    private val dismissMessage = "muster-device-action-v1\n" +
        "mdc_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
        "ask_dismiss\n" +
        "41\n"

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun theAnswerMessageIsExactlyMustersBytes() {
        assertEquals("bef4261f394bf71fd2b565cd76396ac9ed7953f9110c69ee49d7a82871238fbf", DeviceActionMessage.textSha256("ship it"))
        val bytes = DeviceActionMessage.bytes(nonce, ActionId.ASK_ANSWER, "41", DeviceActionMessage.textSha256("ship it"))
        assertEquals(answerMessage, String(bytes, Charsets.UTF_8))
        assertEquals(150, bytes.size)
        assertEquals("abe00771925ebd0d68467e333de410503102e304d6bf6d6db62efda4f4296b04", sha256(bytes))
    }

    /** A dismiss carries no text: an EMPTY last line, so the message ends in "\n". */
    @Test
    fun theDismissMessageEndsWithAnEmptyLine() {
        val bytes = DeviceActionMessage.bytes(nonce, ActionId.ASK_DISMISS, "41", null)
        assertEquals(dismissMessage, String(bytes, Charsets.UTF_8))
        assertEquals(87, bytes.size)
        assertEquals("9d8a5541387c7f32ea51e22ee073b23e8aa0974a808ec7c5c3253af74bb62466", sha256(bytes))
    }

    /** The text is hashed as UTF-8, exactly as sent: no trimming, no normalising. */
    @Test
    fun theTextHashIsTheUtf8BytesUntrimmed() {
        assertEquals(
            "1d0862d45e1bcc60d8b48ef437d2e6fdb10745836d122be090180d273d426ed1",
            DeviceActionMessage.textSha256("Wait for Monday — until the backup is verified 👍"),
        )
        // The empty string's well-known digest; a space is not trimmed away.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DeviceActionMessage.textSha256(""))
        assertEquals("36a9e7f1c95b82ffb99743e0c5c4ce95d83c9a430aac59f84ef3cbfab6145068", DeviceActionMessage.textSha256(" "))
    }

    // --- the flow, against a stand-in muster ------------------------------------------

    private val server = MockWebServer()
    private lateinit var signed: SignedAsk

    @Before
    fun setUp() {
        server.start()
        signed = SignedAsk(DeviceApi(server.url("/").toString()), token)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun challengeAnswer(message: String) =
        json(200, """{"nonce":"$nonce","expires_in":120,"message":${JsonPrimitive(message)}}""")

    @Test
    fun challengeSignAnswerSendsTheSignedAnswer() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val req = AskRequest(ActionId.ASK_ANSWER, "41", "ship it")
        server.enqueue(challengeAnswer(answerMessage))
        server.enqueue(json(200, """{"ok":true,"id":41,"status":"answered"}"""))

        val ready = signed.prepare(req) as SignedAsk.Prepared.Ready
        val c = server.takeRequest()
        assertEquals("POST", c.method)
        assertEquals("/api/device/challenge", c.url.encodedPath)
        assertEquals("Bearer $token", c.headers["Authorization"])
        assertEquals(
            mapOf("action" to "ask_answer", "target" to "41", "text_sha256" to "bef4261f394bf71fd2b565cd76396ac9ed7953f9110c69ee49d7a82871238fbf"),
            Json.parseToJsonElement(c.body!!.utf8()).jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertArrayEquals(answerMessage.toByteArray(Charsets.UTF_8), ready.message)

        val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(ready.message); sign() }
        assertEquals(AskOutcome.Done, signed.send(req, ready.nonce, der))

        val a = server.takeRequest()
        assertEquals("/api/device/actions/attention/asks/41/answer", a.url.encodedPath)
        assertEquals("Bearer $token", a.headers["Authorization"])
        val body = Json.parseToJsonElement(a.body!!.utf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(setOf("nonce", "signature", "answer"), body.keys)
        assertEquals(nonce, body["nonce"])
        assertEquals("ship it", body["answer"])
        // What was sent verifies, against the public key, over muster's literal message.
        val sent = Base64.getDecoder().decode(body["signature"])
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(answerMessage.toByteArray()); verify(sent) })
    }

    @Test
    fun aDismissGoesToTheCancelRouteWithNoText() {
        val req = AskRequest(ActionId.ASK_DISMISS, "41", null)
        server.enqueue(challengeAnswer(dismissMessage))
        server.enqueue(json(200, """{"ok":true,"id":41,"status":"cancelled"}"""))
        val ready = signed.prepare(req) as SignedAsk.Prepared.Ready
        assertEquals(
            mapOf("action" to "ask_dismiss", "target" to "41"),
            Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(AskOutcome.Done, signed.send(req, ready.nonce, byteArrayOf(1, 2, 3)))
        val a = server.takeRequest()
        assertEquals("/api/device/actions/attention/asks/41/cancel", a.url.encodedPath)
        assertEquals(setOf("nonce", "signature"), Json.parseToJsonElement(a.body!!.utf8()).jsonObject.keys)
    }

    /** The key signs only what the app built: a challenge whose message differs is refused unsigned. */
    @Test
    fun aChallengeForADifferentMessageIsNotSigned() {
        val req = AskRequest(ActionId.ASK_ANSWER, "41", "ship it")
        server.enqueue(challengeAnswer(answerMessage.replace("\n41\n", "\n42\n")))
        assertEquals(SignedAsk.Prepared.Refused(AskOutcome.Failed(SignedAsk.BUG)), signed.prepare(req))
        server.enqueue(json(200, """{"nonce":"not-a-nonce","message":""}"""))
        assertEquals(SignedAsk.Prepared.Refused(AskOutcome.Failed(SignedAsk.BUG)), signed.prepare(req))
        assertEquals(2, server.requestCount)
    }

    /** A refusal is shown in the notification, never dropped: an expired challenge keeps the buttons. */
    @Test
    fun refusalsBecomeTheNotificationsText() {
        val req = AskRequest(ActionId.ASK_ANSWER, "41", "ship it")
        fun send(code: Int, body: String): AskOutcome {
            server.enqueue(json(code, body))
            return signed.send(req, nonce, byteArrayOf(1))
        }
        assertEquals(AskOutcome.Failed(SignedAsk.RETRY), send(401, """{"error":"this challenge has expired","code":"challenge_expired"}"""))
        assertEquals(AskOutcome.Failed(SignedAsk.RETRY), send(401, """{"error":"used","code":"challenge_used"}"""))
        assertEquals(AskOutcome.Failed(SignedAsk.RETRY), send(401, """{"error":"invalid","code":"challenge_invalid"}"""))
        assertEquals(AskOutcome.Failed(SignedAsk.REPAIR), send(401, """{"error":"bad","code":"bad_signature"}"""))
        assertEquals(AskOutcome.Failed(SignedAsk.REPAIR), send(403, """{"error":"no","code":"key_not_allowed"}"""))
        assertEquals(AskOutcome.Failed(SignedAsk.READ_ONLY), send(403, """{"error":"no","code":"no_stepup_key"}"""))
        assertEquals(AskOutcome.Gone(SignedAsk.NOT_OPEN), send(409, """{"error":"answered","code":"not_open"}"""))
        assertEquals(AskOutcome.Gone(SignedAsk.NOT_FOUND), send(404, """{"error":"no such question","code":"not_found"}"""))
        assertEquals(
            AskOutcome.Failed("Not sent: muster refused the answer (the answer is blank)."),
            send(400, """{"error":"the answer is blank","code":"invalid_answer"}"""),
        )
        assertEquals(AskOutcome.Failed("Not sent: muster could not record it. Try again later."), send(500, """{"error":"x","code":"failed"}"""))
        // An edge's 502 with no code, and muster's own device-credential refusal.
        assertEquals(AskOutcome.Failed("Not sent: muster answered 502."), send(502, "<html>bad gateway</html>"))
        assertEquals(AskOutcome.Failed(SignedAsk.REVOKED), send(401, """{"error":"${DeviceApi.DEVICE_TOKEN_REFUSAL}"}"""))
        // A challenge refused before anything is signed is shown the same way.
        server.enqueue(json(401, """{"error":"expired","code":"challenge_expired"}"""))
        assertEquals(SignedAsk.Prepared.Refused(AskOutcome.Failed(SignedAsk.RETRY)), signed.prepare(req))
    }

    @Test
    fun anUnreachableMusterSaysSo() {
        val api = DeviceApi("http://127.0.0.1:1/")
        assertEquals(AskOutcome.Failed(SignedAsk.UNREACHABLE), SignedAsk(api, token).send(AskRequest(ActionId.ASK_DISMISS, "41", null), nonce, byteArrayOf(1)))
    }
}
