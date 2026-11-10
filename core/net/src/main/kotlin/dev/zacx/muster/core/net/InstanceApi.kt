package dev.zacx.muster.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * A muster server's public branding (muster: `GET /api/instance` and
 * `/brand/…`): the display name and the logo a tenant shows in the switcher
 * and on its notifications.
 *
 * Neither route takes a credential, and nothing is sent but the request: no
 * cookie, no `Authorization`. Redirects are NOT followed, and every icon is
 * fetched from the tenant's own origin only ([BrandUrl.resolve]): the server
 * names a path, or a URL on its own scheme, host and port; anything else is
 * refused before a request is made.
 *
 * An icon is accepted only as `image/png`, at most [MAX_ICON_BYTES], with a
 * PNG signature and an IHDR no larger than [PngCheck.MAX_DIMENSION] on a side
 * ([IconResult.Refused] otherwise), so the bytes can be decoded with bounds
 * later without risking an out-of-memory decode.
 */
class InstanceApi(
    origin: String,
    client: OkHttpClient = DeviceApi.defaultClient(),
    /** The whole call's limit (connect, headers, body): a server that trickles bytes cannot hold a refresh. */
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
) {
    val base: HttpUrl = requireNotNull(origin.toHttpUrlOrNull()) { "not a URL" }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    /** What `/api/instance` says. Both fields are optional: an older or unbranded muster sends nulls. */
    data class Instance(val name: String?, val logo: Logo?)

    /**
     * The logo: [icon] is the small square icon's reference as sent
     * (`icon192`, else `url`), resolved against the origin only when fetched;
     * [etag] changes whenever the server's logo changes.
     */
    data class Logo(val icon: String, val etag: String)

    sealed interface InstanceResult {
        /** 200. [maxAgeSeconds]: how long the answer holds, from Cache-Control. */
        data class Info(val instance: Instance, val maxAgeSeconds: Long?) : InstanceResult

        /** 404: a muster older than the route. No branding; nothing is an error. */
        data object NotOffered : InstanceResult

        /** No usable answer (network, a 3xx, a 5xx, a body that is not the shape, too big). Keep what is stored. */
        data class Failed(val message: String) : InstanceResult
    }

    /** `GET /api/instance`. */
    fun instance(): InstanceResult {
        val call = Request.Builder().url(url("api/instance")).get().build()
        return try {
            http.newCall(call).execute().use { r ->
                when (r.code) {
                    200 -> {
                        val text = readBounded(r, MAX_INSTANCE_BYTES)?.decodeToString()
                            ?: return InstanceResult.Failed("muster's answer is too large")
                        val inst = InstanceParser.parse(text) ?: return InstanceResult.Failed("muster's answer could not be read")
                        InstanceResult.Info(inst, CacheControl.maxAgeSeconds(r.header("Cache-Control")))
                    }
                    404 -> InstanceResult.NotOffered
                    else -> InstanceResult.Failed("muster answered ${r.code}")
                }
            }
        } catch (e: IOException) {
            InstanceResult.Failed("muster could not be reached")
        }
    }

    sealed interface IconResult {
        /** A validated PNG. */
        class Fetched(val bytes: ByteArray) : IconResult

        /** Not fetched, or fetched and not accepted: another origin, not a PNG, too large. Never retried as-is. */
        data class Refused(val reason: String) : IconResult

        /** No usable answer (network, a 3xx, a 404, a 5xx). */
        data class Failed(val message: String) : IconResult
    }

    /** Fetches the icon [ref] (a path, or a URL on this origin). */
    fun icon(ref: String): IconResult {
        val target = BrandUrl.resolve(base, ref) ?: return IconResult.Refused("not on this server's origin")
        return try {
            http.newCall(Request.Builder().url(target).get().build()).execute().use { r ->
                when (r.code) {
                    200 -> {
                        if (!isPng(r.header("Content-Type"))) return IconResult.Refused("not image/png")
                        val declared = r.body.contentLength()
                        if (declared > MAX_ICON_BYTES) return IconResult.Refused("larger than $MAX_ICON_BYTES bytes")
                        val bytes = readBounded(r, MAX_ICON_BYTES) ?: return IconResult.Refused("larger than $MAX_ICON_BYTES bytes")
                        if (PngCheck.dimensions(bytes) == null) return IconResult.Refused("not a PNG of an accepted size")
                        IconResult.Fetched(bytes)
                    }
                    else -> IconResult.Failed("muster answered ${r.code}")
                }
            }
        } catch (e: IOException) {
            IconResult.Failed("muster could not be reached")
        }
    }

    private fun url(path: String): HttpUrl = base.newBuilder().encodedPath("/$path").query(null).fragment(null).build()

    companion object {
        /** The largest icon accepted, in bytes (1 MiB). */
        const val MAX_ICON_BYTES = 1L shl 20

        /** Every branding call ends within this, whatever the server does. */
        const val CALL_TIMEOUT_MS = 20_000L

        /** The largest `/api/instance` body read. */
        const val MAX_INSTANCE_BYTES = 64L * 1024

        internal fun isPng(contentType: String?): Boolean =
            contentType?.substringBefore(';')?.trim()?.equals("image/png", ignoreCase = true) == true

        /** At most [max] bytes of [r]'s body, or null when it is longer. */
        internal fun readBounded(r: Response, max: Long): ByteArray? {
            val source = r.body.source()
            // request(n) is true when the buffer holds at least n bytes: one past the cap means too long.
            if (source.request(max + 1)) return null
            return source.buffer.readByteArray()
        }
    }
}

/**
 * Where an icon reference from a server may be fetched from: that server's
 * origin and nowhere else. A path (`/brand/icon-192.png`, or a relative one)
 * is resolved against [base]; an absolute URL is accepted only with the same
 * scheme, host and port as [base]. Anything that resolves elsewhere (another
 * host, `//host/…`, another scheme or port, user info) is null.
 */
object BrandUrl {
    fun resolve(base: HttpUrl, ref: String): HttpUrl? {
        if (ref.isEmpty() || ref.length > 2048) return null
        if (ref.any { it.isISOControl() || it == '\\' }) return null
        val u = base.resolve(ref) ?: return null
        if (u.scheme != base.scheme || u.host != base.host || u.port != base.port) return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        return u.newBuilder().fragment(null).build()
    }
}

/** Parses `/api/instance`. Unknown fields are ignored; a field of the wrong type reads as absent. */
object InstanceParser {
    private val json = Json { isLenient = false }

    /** The longest display name kept, in code points. */
    const val MAX_NAME = 64

    fun parse(text: String): InstanceApi.Instance? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val name = str(obj, "name")?.let(::cleanName)
        val logo = (obj["logo"] as? JsonObject)?.let { l ->
            val icon = (str(l, "icon192") ?: str(l, "url"))?.takeIf { it.isNotEmpty() && it.length <= 2048 }
            val etag = str(l, "etag")?.takeIf { it.isNotEmpty() && it.length <= 200 }
            if (icon != null && etag != null) InstanceApi.Logo(icon, etag) else null
        }
        return InstanceApi.Instance(name, logo)
    }

    /**
     * No control or format characters (by code point, so the supplementary
     * tag characters U+E0000-U+E007F go too), trimmed, at most [MAX_NAME]
     * code points; blank is no name.
     */
    internal fun cleanName(raw: String): String? {
        val kept = StringBuilder()
        raw.codePoints().forEach { cp ->
            if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT.toInt()) kept.appendCodePoint(cp)
        }
        val s = kept.toString().trim()
        if (s.isEmpty()) return null
        if (s.codePointCount(0, s.length) <= MAX_NAME) return s
        return s.substring(0, s.offsetByCodePoints(0, MAX_NAME)).trimEnd()
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The freshness a `Cache-Control` header allows, in seconds; null when it says nothing. */
object CacheControl {
    fun maxAgeSeconds(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(',').map { it.trim().lowercase() }
        if (parts.any { it == "no-store" || it == "no-cache" }) return 0
        val v = parts.firstOrNull { it.startsWith("max-age=") }?.removePrefix("max-age=")?.trim('"') ?: return null
        return v.toLongOrNull()?.coerceAtLeast(0)
    }
}

/**
 * The PNG signature and its IHDR's size, read before anything decodes the
 * bytes: a small file can declare a huge image, and decoding that would be
 * an out-of-memory crash.
 */
object PngCheck {
    /** The largest side accepted, in pixels. */
    const val MAX_DIMENSION = 2048

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** Width and height, or null when [b] is not a PNG or a side is 0 or over [MAX_DIMENSION]. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 33) return null
        for (i in SIGNATURE.indices) if (b[i] != SIGNATURE[i]) return null
        // The first chunk is IHDR: length 13, type "IHDR", then width and height (big-endian).
        if (int(b, 8) != 13 || String(b, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        val w = int(b, 16)
        val h = int(b, 20)
        if (w !in 1..MAX_DIMENSION || h !in 1..MAX_DIMENSION) return null
        return w to h
    }

    private fun int(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
