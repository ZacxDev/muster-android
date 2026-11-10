package dev.zacx.muster.core.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** A PNG signature and IHDR of [w]×[h], padded to [size] bytes: enough for [PngCheck], not a decodable image. */
internal fun pngBytes(w: Int = 192, h: Int = 192, size: Int = 64): ByteArray {
    val b = ByteArray(maxOf(size, 33))
    val head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
    head.copyInto(b)
    fun put(at: Int, v: Int) { b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte() }
    put(16, w); put(20, h)
    b[24] = 8; b[25] = 6 // bit depth, colour type
    return b
}

class InstanceApiTest {
    private val server = MockWebServer()
    private lateinit var api: InstanceApi

    @Before
    fun setUp() {
        server.start()
        api = InstanceApi(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.body(body).build()

    private fun png(bytes: ByteArray, type: String = "image/png", vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(200).addHeader("Content-Type", type).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.body(Buffer().write(bytes)).build()

    // --- /api/instance --------------------------------------------------------------

    @Test
    fun theFullAnswerIsRead() {
        server.enqueue(
            json(
                200,
                """{"name":"Team Muster","logo":{"url":"/brand/logo.png","icon192":"/brand/icon-192.png","icon512":"/brand/icon-512.png","etag":"abc123"}}""",
                "Cache-Control" to "public, max-age=600",
            ),
        )
        val r = api.instance()
        assertEquals(
            InstanceApi.InstanceResult.Info(InstanceApi.Instance("Team Muster", InstanceApi.Logo("/brand/icon-192.png", "abc123")), 600),
            r,
        )
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/instance", req.url.encodedPath)
        // A public route: nothing identifying goes with it.
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["Cookie"])
    }

    @Test
    fun aNullLogoIsNoLogo() {
        server.enqueue(json(200, """{"name":"Team","logo":null}"""))
        assertEquals(InstanceApi.InstanceResult.Info(InstanceApi.Instance("Team", null), null), api.instance())
    }

    @Test
    fun missingFieldsAreAbsent() {
        server.enqueue(json(200, """{}"""))
        assertEquals(InstanceApi.InstanceResult.Info(InstanceApi.Instance(null, null), null), api.instance())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        server.enqueue(json(200, """{"name":"Team","theme":{"accent":"#fff"},"logo":{"icon192":"/i.png","etag":"e","future":1},"v":3}"""))
        assertEquals(InstanceApi.InstanceResult.Info(InstanceApi.Instance("Team", InstanceApi.Logo("/i.png", "e")), null), api.instance())
    }

    /** No icon192: the logo's url stands in; no etag, or no reference at all, is no logo (nothing to key a cache on). */
    @Test
    fun aLogoNeedsAReferenceAndAnEtag() {
        assertEquals(InstanceApi.Logo("/brand/logo.png", "e"), InstanceParser.parse("""{"logo":{"url":"/brand/logo.png","etag":"e"}}""")!!.logo)
        assertNull(InstanceParser.parse("""{"logo":{"icon192":"/i.png"}}""")!!.logo)
        assertNull(InstanceParser.parse("""{"logo":{"icon192":"/i.png","etag":""}}""")!!.logo)
        assertNull(InstanceParser.parse("""{"logo":{"etag":"e"}}""")!!.logo)
    }

    /** A field of the wrong type reads as absent; a body that is not an object is no answer. */
    @Test
    fun wrongTypesReadAsAbsent() {
        assertEquals(InstanceApi.Instance(null, null), InstanceParser.parse("""{"name":42,"logo":"x"}"""))
        assertEquals(InstanceApi.Instance(null, null), InstanceParser.parse("""{"name":null,"logo":{"icon192":7,"etag":"e"}}"""))
        assertNull(InstanceParser.parse("""[]"""))
        assertNull(InstanceParser.parse("""not json"""))
        server.enqueue(json(200, "<html>"))
        assertTrue(api.instance() is InstanceApi.InstanceResult.Failed)
    }

    /** Format characters outside the BMP go too: the tag characters (U+E0001, U+E0020-U+E007F) are surrogate pairs. */
    @Test
    fun supplementaryFormatCharactersAreRemoved() {
        val tags = String(Character.toChars(0xE0001)) + String(Character.toChars(0xE0041)) + String(Character.toChars(0xE007F))
        assertEquals("TeamMuster", InstanceParser.cleanName("Team${tags}Muster"))
        // An ordinary supplementary character (an emoji) is kept.
        assertEquals("Team 😀", InstanceParser.cleanName("Team 😀"))
    }

    /** 🔴 The whole call is bounded: a server trickling its body (each byte inside the read timeout) cannot hold a refresh. */
    @Test
    fun theWholeCallIsBounded() {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "image/png")
                .body(Buffer().write(pngBytes(size = 64))).throttleBody(1, 100, TimeUnit.MILLISECONDS).build(),
        )
        val quick = InstanceApi(server.url("/").toString(), callTimeoutMs = 500)
        val started = System.nanoTime()
        assertEquals(InstanceApi.IconResult.Failed("muster could not be reached"), quick.icon("/brand/icon-192.png"))
        assertTrue("ended by the call timeout", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(20_000L, InstanceApi.CALL_TIMEOUT_MS)
    }

    /** The name is shown in the app bar: no control or format characters, at most 64 code points, blank is none. */
    @Test
    fun theNameIsCleaned() {
        assertEquals("Team Muster", InstanceParser.parse("{\"name\":\"  Team\\u0000 \\u202eMuster\\n \"}")!!.name)
        assertNull(InstanceParser.parse("""{"name":"   "}""")!!.name)
        val long = "😀".repeat(100)
        assertEquals(64, InstanceParser.parse("""{"name":"$long"}""")!!.name!!.let { it.codePointCount(0, it.length) })
    }

    /** 🔴 An older muster: 404 is "not offered", never an error the user sees. */
    @Test
    fun anOlderServerAnswers404() {
        server.enqueue(json(404, """{"error":"not found"}"""))
        assertEquals(InstanceApi.InstanceResult.NotOffered, api.instance())
    }

    @Test
    fun otherStatusesAndTheNetworkFail() {
        server.enqueue(json(500, "{}"))
        assertEquals(InstanceApi.InstanceResult.Failed("muster answered 500"), api.instance())
        // A redirect is not followed (the answer must come from this origin).
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://team.example.org/api/instance").build())
        assertEquals(InstanceApi.InstanceResult.Failed("muster answered 302"), api.instance())
        server.close()
        assertEquals(InstanceApi.InstanceResult.Failed("muster could not be reached"), api.instance())
    }

    @Test
    fun anOversizedAnswerIsRefused() {
        server.enqueue(json(200, """{"name":"${"a".repeat(70_000)}"}"""))
        assertEquals(InstanceApi.InstanceResult.Failed("muster's answer is too large"), api.instance())
    }


    @Test
    fun cacheControl() {
        assertEquals(600L, CacheControl.maxAgeSeconds("public, max-age=600"))
        assertEquals(0L, CacheControl.maxAgeSeconds("no-cache"))
        assertEquals(0L, CacheControl.maxAgeSeconds("max-age=600, no-store"))
        assertNull(CacheControl.maxAgeSeconds("public"))
        assertNull(CacheControl.maxAgeSeconds(null))
        assertNull(CacheControl.maxAgeSeconds("max-age=soon"))
    }

    // --- icons ------------------------------------------------------------------------

    @Test
    fun anIconIsFetchedFromItsPath() {
        val bytes = pngBytes()
        server.enqueue(png(bytes))
        val r = api.icon("/brand/icon-192.png") as InstanceApi.IconResult.Fetched
        assertArrayEquals(bytes, r.bytes)
        val req = server.takeRequest()
        assertEquals("/brand/icon-192.png", req.url.encodedPath)
        assertNull(req.headers["Authorization"])
    }

    /** 🔴 An icon on another origin is never requested at all. */
    @Test
    fun aCrossOriginIconIsRefusedWithoutARequest() {
        for (ref in listOf("https://team.example.org/brand/icon-192.png", "//team.example.org/brand/icon-192.png")) {
            assertEquals(ref, InstanceApi.IconResult.Refused("not on this server's origin"), api.icon(ref))
        }
        assertEquals(0, server.requestCount)
        // The same origin spelled in full is fine.
        server.enqueue(png(pngBytes()))
        assertTrue(api.icon(server.url("/brand/icon-192.png").toString()) is InstanceApi.IconResult.Fetched)
    }

    @Test
    fun brandUrlResolvesOnlyOnTheOrigin() {
        val base = "https://muster.example.com".toHttpUrl()
        assertEquals("https://muster.example.com/brand/icon-192.png", BrandUrl.resolve(base, "/brand/icon-192.png").toString())
        assertEquals("https://muster.example.com/brand/i.png", BrandUrl.resolve(base, "brand/i.png").toString())
        assertEquals("https://muster.example.com/brand/i.png", BrandUrl.resolve(base, "https://muster.example.com/brand/i.png#x").toString())
        for (bad in listOf(
            "https://team.example.org/i.png", // another host
            "//team.example.org/i.png", // protocol-relative
            "http://muster.example.com/i.png", // another scheme (and so another default port)
            "http://muster.example.com:443/i.png", // another scheme on the SAME port: only the scheme check refuses it
            "https://muster.example.com:8443/i.png", // another port
            "https://muster.example.com.team.example.org/i.png", // a suffix trick
            "https://u:p@muster.example.com/i.png", // user info
            "/\\team.example.org/i.png", // backslash
            // Same-origin once OkHttp has resolved them, so ONLY the control/backslash guard refuses these:
            "/brand\\icon-192.png", // a backslash OkHttp would turn into "/"
            "/brand/icon-192.png\u0007", // a raw control character OkHttp would percent-encode
            "/brand/\nicon-192.png",
            "javascript:alert(1)",
            "",
        )) {
            assertNull(bad, BrandUrl.resolve(base, bad))
        }
    }

    /** 🔴 Only image/png is accepted, and only a real PNG signature. */
    @Test
    fun aNonPngIsRefused() {
        server.enqueue(png(pngBytes(), "image/svg+xml"))
        assertEquals(InstanceApi.IconResult.Refused("not image/png"), api.icon("/brand/icon-192.png"))
        server.enqueue(png("<svg/>".encodeToByteArray().copyOf(64), "image/png"))
        assertEquals(InstanceApi.IconResult.Refused("not a PNG of an accepted size"), api.icon("/brand/icon-192.png"))
        // image/png with parameters is still image/png.
        server.enqueue(png(pngBytes(), "image/png; charset=binary"))
        assertTrue(api.icon("/brand/icon-192.png") is InstanceApi.IconResult.Fetched)
    }

    /** 🔴 More than 1 MiB is refused: by its Content-Length before reading, and by counting when it has none. */
    @Test
    fun anOversizedIconIsRefused() {
        val max = InstanceApi.MAX_ICON_BYTES.toInt()
        server.enqueue(png(pngBytes(size = max + 1)))
        assertEquals(InstanceApi.IconResult.Refused("larger than $max bytes"), api.icon("/brand/icon-192.png"))
        // Chunked: no Content-Length, so the read itself must stop.
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "image/png")
                .chunkedBody(Buffer().write(pngBytes(size = max + 1)), 64 * 1024).build(),
        )
        assertEquals(InstanceApi.IconResult.Refused("larger than $max bytes"), api.icon("/brand/icon-192.png"))
        // Exactly the cap is accepted.
        server.enqueue(png(pngBytes(size = max)))
        assertTrue(api.icon("/brand/icon-192.png") is InstanceApi.IconResult.Fetched)
    }

    /**
     * A declared Content-Length over the cap is refused at once, before any
     * body is read: the body here is short and never completes, so only the
     * header check can answer Refused (reading it would end in a timeout).
     */
    @Test
    fun anOversizedContentLengthIsRefusedBeforeReading() {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "image/png")
                .body(Buffer().write(pngBytes())).setHeader("Content-Length", (InstanceApi.MAX_ICON_BYTES + 1).toString()).build(),
        )
        val quick = InstanceApi(server.url("/").toString(), okhttp3.OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build())
        assertEquals(InstanceApi.IconResult.Refused("larger than ${InstanceApi.MAX_ICON_BYTES} bytes"), quick.icon("/brand/icon-192.png"))
    }

    /** A small file declaring a huge image is refused before anything decodes it. */
    @Test
    fun aPngDeclaringAHugeImageIsRefused() {
        server.enqueue(png(pngBytes(w = 30_000, h = 30_000)))
        assertEquals(InstanceApi.IconResult.Refused("not a PNG of an accepted size"), api.icon("/brand/icon-192.png"))
        assertEquals(2048 to 2048, PngCheck.dimensions(pngBytes(2048, 2048)))
        assertNull(PngCheck.dimensions(pngBytes(2049, 16)))
        assertNull(PngCheck.dimensions(pngBytes(0, 16)))
    }

    @Test
    fun iconFailures() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(InstanceApi.IconResult.Failed("muster answered 404"), api.icon("/brand/icon-192.png"))
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://team.example.org/i.png").build())
        assertEquals(InstanceApi.IconResult.Failed("muster answered 302"), api.icon("/brand/icon-192.png"))
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").headersDelay(2, TimeUnit.SECONDS).build())
        val slow = InstanceApi(server.url("/").toString(), okhttp3.OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build())
        assertEquals(InstanceApi.IconResult.Failed("muster could not be reached"), slow.icon("/brand/icon-192.png"))
    }
}
