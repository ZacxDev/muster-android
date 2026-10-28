package dev.zacx.muster.core.net

import dev.zacx.muster.core.model.ActionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The client for muster's device tier, `/api/device/...` (muster
 * docs/auth.md, "Paired devices"; internal/api/devices.go).
 *
 * - `POST /api/device/pair` takes no credential: the pairing code is the
 *   authority.
 * - Every other route takes the device credential as `Authorization: Bearer
 *   mdv_…` and nothing else.
 *
 * Redirects are NOT followed: the credential goes to the origin the user
 * entered and nowhere else, and a 3xx is an error.
 */
class DeviceApi(
    origin: String,
    client: OkHttpClient = defaultClient(),
) {
    private val base: HttpUrl = requireNotNull(origin.toHttpUrlOrNull()) { "not a URL" }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    // --- pairing --------------------------------------------------------------

    @Serializable
    data class PairRequest(
        val code: String,
        val name: String,
        @SerialName("app_version") val appVersion: String,
        // All three present, or all three absent (a read-only phone).
        @SerialName("stepup_pubkey") val stepUpPubkey: String? = null,
        @SerialName("stepup_key_type") val stepUpKeyType: String? = null,
        @SerialName("stepup_key_level") val stepUpKeyLevel: String? = null,
    )

    @Serializable
    data class PairedDevice(
        val id: Long,
        val name: String = "",
        @SerialName("stepup_key") val stepUpKey: Boolean = false,
        @SerialName("stepup_key_type") val stepUpKeyType: String = "",
        @SerialName("stepup_key_level") val stepUpKeyLevel: String = "",
    )

    @Serializable
    private data class PairResponse(val token: String, val device: PairedDevice)

    sealed interface PairResult {
        data class Paired(val token: String, val device: PairedDevice) : PairResult

        /** 403: the step-up key's type or level is refused. The code is NOT spent. */
        data class KeyRefused(val message: String) : PairResult

        /** 401: the code is unknown, used, expired, or its session ended. */
        data class BadCode(val message: String) : PairResult

        /** 400: the request was malformed (a bug, or a label muster refuses). */
        data class Invalid(val message: String) : PairResult

        /** 409: the account has its maximum number of phones. */
        data class TooMany(val message: String) : PairResult

        data object Throttled : PairResult

        data class Failed(val message: String) : PairResult
    }

    fun pair(req: PairRequest): PairResult {
        val body = json.encodeToString(PairRequest.serializer(), req).toRequestBody(JSON)
        val call = Request.Builder().url(url("api/device/pair")).post(body).build()
        return try {
            http.newCall(call).execute().use { r ->
                val text = r.body.string()
                when (r.code) {
                    201 -> {
                        val p = runCatching { json.decodeFromString(PairResponse.serializer(), text) }.getOrNull()
                        if (p == null || !CredentialShape.ok(p.token)) PairResult.Failed("muster answered with no usable credential")
                        else PairResult.Paired(p.token, p.device)
                    }
                    400 -> PairResult.Invalid(errorOf(text))
                    401 -> PairResult.BadCode(errorOf(text))
                    403 -> PairResult.KeyRefused(errorOf(text))
                    409 -> PairResult.TooMany(errorOf(text))
                    429 -> PairResult.Throttled
                    else -> PairResult.Failed("muster answered ${r.code}")
                }
            }
        } catch (e: IOException) {
            PairResult.Failed("muster could not be reached")
        }
    }

    // --- the device tier --------------------------------------------------------

    @Serializable
    data class Config(
        @SerialName("server_version") val serverVersion: String = "",
        val push: PushConfig = PushConfig(),
        val pairing: PairingPolicy = PairingPolicy(),
        val features: Features = Features(),
    )

    @Serializable
    data class PushConfig(
        val served: Boolean = false,
        @SerialName("vapid_public_key") val vapidPublicKey: String = "",
        val hosts: List<String> = emptyList(),
    )

    @Serializable
    data class PairingPolicy(@SerialName("pin_keys_allowed") val pinKeysAllowed: Boolean = false)

    @Serializable
    data class Features(
        val push: Boolean = false,
        val actions: Boolean = false,
        val bubbles: Boolean = false,
        val state: Boolean = false,
    )

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /**
         * 401 with muster's device refusal body: the credential was refused
         * (removed, disabled, signed out). Unpair locally.
         */
        data object Revoked : Result<Nothing>

        /**
         * Any other answer. [code] is muster's machine-readable refusal code
         * (`challenge_expired`, `not_open`, …) when the body carries one, else "".
         */
        data class Failed(val status: Int, val message: String, val code: String = "") : Result<Nothing>
    }

    fun config(token: String): Result<Config> = call(token, Request.Builder().url(url("api/device/config")).get()) {
        json.decodeFromString(Config.serializer(), it)
    }

    @Serializable
    data class PushRegistration(val endpoint: String, val p256dh: String, val auth: String)

    /** Registers this phone's UnifiedPush endpoint. muster sends it a test push first. */
    fun putPush(token: String, reg: PushRegistration): Result<Unit> = call(
        token,
        Request.Builder().url(url("api/device/push"))
            .put(json.encodeToString(PushRegistration.serializer(), reg).toRequestBody(JSON)),
    ) { }

    fun deletePush(token: String): Result<Unit> =
        call(token, Request.Builder().url(url("api/device/push")).delete()) { }

    /** Unpairs this phone: muster deletes the device row and its push registration. */
    fun unpair(token: String): Result<Unit> =
        call(token, Request.Builder().url(url("api/device")).delete()) { }

    // --- signed actions -----------------------------------------------------------

    @Serializable
    private data class ChallengeRequest(
        val action: String,
        val target: String,
        @SerialName("text_sha256") val textSha256: String? = null,
    )

    /** A one-time challenge (single use, 2 minutes). [message] is what muster says to sign. */
    @Serializable
    data class Challenge(
        val nonce: String,
        @SerialName("expires_in") val expiresIn: Int = 0,
        val message: String = "",
    )

    /**
     * `POST /api/device/challenge`: a challenge bound to [action], [target]
     * and, for an answer, the sha256 of its exact text
     * ([DeviceActionMessage.textSha256]).
     */
    fun challenge(token: String, action: ActionId, target: String, textSha256: String?): Result<Challenge> {
        requireSignedAsk(action, target)
        val body = json.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(action.wire, target, textSha256))
        return call(token, Request.Builder().url(url("api/device/challenge")).post(body.toRequestBody(JSON))) {
            json.decodeFromString(Challenge.serializer(), it)
        }
    }

    @Serializable
    private data class ActionRequest(val nonce: String, val signature: String, val answer: String? = null)

    /**
     * The signed ask action: `POST` [action]'s fixed route
     * ([ActionId.route], `{id}` = [target]) with the challenge's nonce, the
     * base64 DER signature and, for an answer, the exact answer text.
     */
    fun askAction(token: String, action: ActionId, target: String, nonce: String, signature: String, answer: String?): Result<Unit> {
        requireSignedAsk(action, target)
        require((action == ActionId.ASK_ANSWER) == (answer != null)) { "an answer carries its text; a dismiss none" }
        val body = json.encodeToString(ActionRequest.serializer(), ActionRequest(nonce, signature, answer))
        val path = action.route.removePrefix("/").replace("{id}", target)
        return call(token, Request.Builder().url(url(path)).post(body.toRequestBody(JSON))) { }
    }

    private fun requireSignedAsk(action: ActionId, target: String) {
        require(action == ActionId.ASK_ANSWER || action == ActionId.ASK_DISMISS) { "muster routes only the ask's signed actions" }
        require(ASK_TARGET.matches(target)) { "an ask's id: decimal, no leading zeros" }
    }

    private fun <T> call(token: String, b: Request.Builder, parse: (String) -> T): Result<T> {
        require(CredentialShape.ok(token)) { "not a device credential" }
        val req = b.header("Authorization", "Bearer $token").build()
        return try {
            http.newCall(req).execute().use { r -> answer(r, parse) }
        } catch (e: IOException) {
            Result.Failed(0, "muster could not be reached")
        }
    }

    private fun <T> answer(r: Response, parse: (String) -> T): Result<T> {
        val text = r.body.string()
        return when {
            // 🔴 Only muster's own refusal ends the pairing. A bare 401, or
            // one from a proxy or an edge in front of muster, is an error:
            // forgetting the credential on it would unpair the phone for a
            // fault that is not muster's answer.
            r.code == 401 && errorOf(text) == DEVICE_TOKEN_REFUSAL -> Result.Revoked
            r.code in 200..299 -> runCatching { Result.Ok(parse(text)) }
                .getOrElse { Result.Failed(r.code, "muster's answer could not be read") }
            else -> Result.Failed(r.code, errorOf(text).ifEmpty { "muster answered ${r.code}" }, codeOf(text))
        }
    }

    private fun url(path: String): HttpUrl = base.newBuilder().encodedPath("/$path").query(null).fragment(null).build()

    private fun errorOf(text: String): String =
        runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrDefault("").take(300)

    private fun codeOf(text: String): String =
        runCatching { json.decodeFromString(ErrorBody.serializer(), text).code }.getOrDefault("").take(64)

    @Serializable
    private data class ErrorBody(val error: String = "", val code: String = "")

    companion object {
        /** muster's internal/api DeviceTokenRefusal, exactly. */
        const val DEVICE_TOKEN_REFUSAL = "this route takes a paired device credential (mdv_…)"

        private val JSON = "application/json".toMediaType()
        private val ASK_TARGET = Regex("^[1-9][0-9]{0,18}$")
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** The shapes muster mints: a prefix and 32 random bytes as base64url, no padding. */
object CredentialShape {
    private val token = Regex("^mdv_[A-Za-z0-9_-]{43}$")
    private val code = Regex("^mdp_[A-Za-z0-9_-]{43}$")

    fun ok(s: String): Boolean = token.matches(s)

    fun codeOk(s: String): Boolean = code.matches(s)
}
