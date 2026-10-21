package dev.zacx.muster.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Payload v2: what muster sends a paired phone (muster docs/push.md,
 * "Paired phones (payload v2)"; docs/design/android.md, "Payload v2").
 *
 *     {"v":2,"type":"review","id":"42","tag":"task-42",
 *      "title":"<WHO>","body":"<WHERE>","url":"/tasks/42"}
 *
 * IDS AND WHO/WHERE, NEVER WHAT. Title and body are WHO and WHERE; anything
 * longer is fetched later with the device credential.
 *
 * Phase 1 reads `v`, `type`, `id`, `tag`, `title`, `body` and `url`. muster
 * sends no `actions`, `conv` or `state` yet; a field this phase does not know
 * is ignored, never acted on.
 */
data class PayloadV2(
    val type: String,
    val id: String?,
    val tag: String?,
    val title: String,
    val body: String,
    /** A path on the muster origin ("/tasks/42"), or null. Never a full URL. */
    val path: String?,
) {
    companion object {
        /**
         * The largest payload v2 muster sends, in bytes: one 4096-byte
         * aes128gcm record minus its 86-byte header, 16-byte tag and 1-byte
         * padding delimiter (muster internal/push NativeMaxPlaintext).
         */
        const val MAX_PLAINTEXT = 4096 - 86 - 16 - 1 // 3993

        /** muster cuts title and body to these many characters (runes). */
        const val MAX_TITLE = 100
        const val MAX_BODY = 200

        /** Bound on id and tag: muster's are short (ids, "task-42"). */
        const val MAX_ID = 200

        /** The withdrawal: cancel by tag, show nothing. */
        const val TYPE_RESOLVED = "resolved"

        /** muster's registration test push (`{"v":2,"type":"test"}`): show nothing. */
        const val TYPE_TEST = "test"
    }
}

/** What one decrypted push means. Exactly one per message. */
sealed interface Push {
    /** Draw (or replace, by tag) a notification. */
    data class Show(val payload: PayloadV2) : Push

    /** Cancel the notification with this tag; draw nothing. */
    data class Withdraw(val tag: String) : Push

    /** muster's registration probe: nothing to draw. */
    data object Probe : Push

    /** Not a payload this app acts on. [reason] is for the log, never shown. */
    data class Rejected(val reason: String) : Push
}

/**
 * Parses a DECRYPTED push body. Callers must have dropped every undecrypted
 * message before this (the decrypted-only rule lives in feature/push).
 *
 * Refuses rather than guesses: a body over [PayloadV2.MAX_PLAINTEXT], bytes
 * that are not UTF-8, JSON that is not an object, a `v` that is not 2, a
 * missing or non-string `type`, or a field of the wrong JSON type.
 */
object PayloadV2Parser {
    private val json = Json { isLenient = false }

    fun parse(bytes: ByteArray): Push {
        if (bytes.size > PayloadV2.MAX_PLAINTEXT) {
            return Push.Rejected("over ${PayloadV2.MAX_PLAINTEXT} bytes (${bytes.size})")
        }
        val text = strictUtf8(bytes) ?: return Push.Rejected("not UTF-8")
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            return Push.Rejected("not JSON")
        }
        val obj = root as? JsonObject ?: return Push.Rejected("not a JSON object")

        val v = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (v != 2) return Push.Rejected("v is not 2")

        val type = str(obj, "type") ?: return Push.Rejected("no type")
        if (type.isEmpty()) return Push.Rejected("empty type")
        // A field present with the wrong JSON type is a malformed payload.
        for (k in listOf("id", "tag", "title", "body", "url")) {
            val e = obj[k] ?: continue
            if (e !is JsonPrimitive || !e.isString) return Push.Rejected("$k is not a string")
        }
        val id = str(obj, "id")?.takeIf { it.isNotEmpty() }
        val tag = str(obj, "tag")?.takeIf { it.isNotEmpty() }
        if ((id?.length ?: 0) > PayloadV2.MAX_ID || (tag?.length ?: 0) > PayloadV2.MAX_ID) {
            return Push.Rejected("id or tag too long")
        }

        return when (type) {
            PayloadV2.TYPE_TEST -> Push.Probe
            PayloadV2.TYPE_RESOLVED ->
                tag?.let { Push.Withdraw(it) } ?: Push.Rejected("resolved without a tag")
            else -> {
                val title = cut(str(obj, "title").orEmpty(), PayloadV2.MAX_TITLE)
                val body = cut(str(obj, "body").orEmpty(), PayloadV2.MAX_BODY)
                if (title.isBlank()) return Push.Rejected("no title")
                Push.Show(PayloadV2(type, id, tag, title, body, MusterPath.parse(str(obj, "url"))))
            }
        }
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * muster already cuts to these limits; the app enforces them again so a
     * payload can never make a notification larger than designed. Cuts on a
     * code-point boundary, never inside a surrogate pair.
     */
    internal fun cut(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end) + "…"
    }
}

/**
 * A tap target: a path on the muster origin. Only an absolute path is
 * accepted ("/tasks/42", "/attention?x=1"), never a scheme, a host, or a
 * protocol-relative "//host" form, so a push cannot point the shell at
 * another origin.
 */
object MusterPath {
    private val allowed = Regex("""^/[A-Za-z0-9\-._~!$&'()*+,;=:@%/?#]*$""")

    fun parse(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.length > 2048) return null
        if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return null
        if (!allowed.matches(raw)) return null
        return raw
    }
}
