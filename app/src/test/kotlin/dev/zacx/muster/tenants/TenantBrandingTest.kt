package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.core.net.InstanceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/** PNG signature + IHDR (192×192), then [fill] bytes of [marker]: distinct bytes per server. */
internal fun fakePng(marker: Int, fill: Int = 64): ByteArray {
    val head = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 192.toByte(), 0, 0, 0, 192.toByte(), 8, 6, 0, 0, 0,
    )
    return head + ByteArray(fill) { marker.toByte() }
}

/**
 * A muster server's branding routes, scripted per test: what `/api/instance`
 * answers, what the icon answers, and every request it saw.
 */
internal class BrandServer {
    val server = MockWebServer()
    val seen: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    @Volatile var instance: () -> MockResponse = { MockResponse.Builder().code(404).build() }
    @Volatile var icon: () -> MockResponse = { MockResponse.Builder().code(404).build() }

    val origin: String get() = server.url("/").toString().removeSuffix("/")

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when (request.url.encodedPath) {
                    "/api/instance" -> instance()
                    else -> icon()
                }
            }
        }
        server.start()
    }

    fun paths() = seen.map { it.url.encodedPath }

    companion object {
        fun json(body: String, vararg headers: Pair<String, String>): MockResponse =
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(body).build()

        fun png(bytes: ByteArray, type: String = "image/png", vararg headers: Pair<String, String>): MockResponse =
            MockResponse.Builder().code(200).addHeader("Content-Type", type)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(Buffer().write(bytes)).build()

        fun branded(name: String?, etag: String, icon: String = "/brand/icon-192.png") =
            """{"name":${name?.let { "\"$it\"" } ?: "null"},"logo":{"url":"/brand/logo.png","icon192":"$icon","icon512":"/brand/icon-512.png","etag":"$etag"}}"""
    }
}

class TenantBrandingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = BrandServer()
    private val b = BrandServer()
    private var clock = 1_000_000L
    private lateinit var branding: TenantBranding

    private fun tenant(id: String, s: BrandServer) =
        TenantRecord(id, Tenants.labelFor(s.origin), Tenants.PALETTE[0], "tenant-$id", DeviceRecord(origin = s.origin))

    private lateinit var ta: TenantRecord
    private lateinit var tb: TenantRecord

    private fun newBranding() = TenantBranding(tmp.root, { InstanceApi(it) }, now = { clock }, io = Dispatchers.Unconfined)

    @Before
    fun setUp() {
        a.start(); b.start()
        ta = tenant("ta", a)
        tb = tenant("tb", b)
        branding = newBranding()
    }

    @After
    fun tearDown() {
        a.server.close(); b.server.close()
    }

    private fun refresh(t: TenantRecord, force: Boolean = false) = runBlocking { branding.refresh(t, force) }
    private fun iconBytes(t: TenantRecord) = branding.brandFor(t)?.icon?.readBytes()

    @Test
    fun aBrandedServersNameAndIconAreStored() {
        a.instance = { BrandServer.json(BrandServer.branded("Team Muster", "e1")) }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        assertEquals(TenantBranding.Outcome.UPDATED, refresh(ta))
        val brand = branding.brandFor(ta)!!
        assertEquals("Team Muster", brand.name)
        assertArrayEquals(fakePng(0xA), brand.icon!!.readBytes())
        // Keyed by the logo's etag, in the tenant's own directory.
        assertEquals(TenantBranding.iconName("e1"), brand.icon!!.name)
        assertEquals("ta", brand.icon!!.parentFile!!.name)
        assertEquals(listOf("/api/instance", "/brand/icon-192.png"), a.paths())
    }

    /** 🔴 An older muster answers 404: no branding, nothing broken; and branding stored before is dropped. */
    @Test
    fun anOldServerHasNoBranding() {
        assertEquals(TenantBranding.Outcome.NOT_OFFERED, refresh(ta))
        assertNull(branding.brandFor(ta))
        assertEquals(listOf("/api/instance"), a.paths())

        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1")) }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta, force = true)
        assertNotNull(branding.brandFor(ta))
        a.instance = { MockResponse.Builder().code(404).build() }
        assertEquals(TenantBranding.Outcome.UPDATED, refresh(ta, force = true))
        assertNull(branding.brandFor(ta))
        assertEquals("the icon file is deleted", listOf("meta.json"), tmp.root.resolve("ta").list()!!.toList())
    }

    /** A null logo is the name alone; a server with neither has no branding. */
    @Test
    fun aNullLogoIsTheNameAlone() {
        a.instance = { BrandServer.json("""{"name":"Team","logo":null}""") }
        refresh(ta)
        assertEquals(TenantBrand("Team", null), branding.brandFor(ta))
        a.instance = { BrandServer.json("""{"name":null,"logo":null}""") }
        refresh(ta, force = true)
        assertNull(branding.brandFor(ta))
        assertEquals(listOf("/api/instance", "/api/instance"), a.paths())
    }

    /** 🔴 A network failure keeps what is stored, and with nothing stored there is nothing. */
    @Test
    fun aNetworkErrorKeepsWhatIsStored() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1")) }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta)
        a.server.close()
        assertEquals(TenantBranding.Outcome.FAILED, refresh(ta, force = true))
        assertEquals("Team", branding.brandFor(ta)!!.name)
        assertArrayEquals(fakePng(0xA), iconBytes(ta))

        b.server.close()
        assertEquals(TenantBranding.Outcome.FAILED, refresh(tb))
        assertNull(branding.brandFor(tb))
    }

    /** A 5xx is a failure too: what is stored stays. */
    @Test
    fun aServerErrorKeepsWhatIsStored() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1")) }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta)
        a.instance = { MockResponse.Builder().code(503).build() }
        assertEquals(TenantBranding.Outcome.FAILED, refresh(ta, force = true))
        assertArrayEquals(fakePng(0xA), iconBytes(ta))
    }

    /** Cache-Control max-age is honoured: no request while fresh; then a conditional one, and a 304 changes nothing. */
    @Test
    fun freshnessAndTheConditionalRead() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1"), "ETag" to "\"i1\"", "Cache-Control" to "max-age=600") }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta)
        a.seen.clear()
        clock += 599_000
        assertEquals(TenantBranding.Outcome.FRESH, refresh(ta))
        assertEquals(emptyList<String>(), a.paths())

        clock += 2_000
        a.instance = { MockResponse.Builder().code(304).addHeader("Cache-Control", "max-age=600").build() }
        assertEquals(TenantBranding.Outcome.UNCHANGED, refresh(ta))
        assertEquals(listOf("/api/instance"), a.paths())
        assertEquals("\"i1\"", a.seen.single().headers["If-None-Match"])
        assertArrayEquals(fakePng(0xA), iconBytes(ta))
        // The 304's max-age starts a new fresh period.
        a.seen.clear()
        assertEquals(TenantBranding.Outcome.FRESH, refresh(ta))
        assertEquals(emptyList<String>(), a.paths())
    }

    /** Without Cache-Control, an answer holds for the default time. */
    @Test
    fun theDefaultFreshness() {
        a.instance = { BrandServer.json("""{"name":"Team"}""") }
        refresh(ta)
        clock += TenantBranding.DEFAULT_TTL_MS - 1
        assertEquals(TenantBranding.Outcome.FRESH, refresh(ta))
        clock += 2
        assertEquals(TenantBranding.Outcome.UNCHANGED, refresh(ta))
        assertEquals(2, a.paths().size)
    }

    /** 🔴 The etag cache: the same logo etag downloads nothing; a new one is a conditional icon read, and a 304 keeps the bytes. */
    @Test
    fun theIconIsDownloadedOnlyWhenTheLogoEtagChanges() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1"), "ETag" to "\"i1\"") }
        a.icon = { BrandServer.png(fakePng(0xA), "image/png", "ETag" to "\"p1\"") }
        refresh(ta)
        a.seen.clear()

        // The instance answer changed (new name), the logo did not: no icon request.
        a.instance = { BrandServer.json(BrandServer.branded("Team Two", "e1"), "ETag" to "\"i2\"") }
        refresh(ta, force = true)
        assertEquals(listOf("/api/instance"), a.paths())
        assertEquals("Team Two", branding.brandFor(ta)!!.name)

        // A new logo etag: the icon is asked for, conditional on its ETag; 304 keeps the bytes under the new key.
        a.seen.clear()
        a.instance = { BrandServer.json(BrandServer.branded("Team Two", "e2"), "ETag" to "\"i3\"") }
        a.icon = { MockResponse.Builder().code(304).build() }
        refresh(ta, force = true)
        assertEquals(listOf("/api/instance", "/brand/icon-192.png"), a.paths())
        assertEquals("\"p1\"", a.seen[1].headers["If-None-Match"])
        assertEquals(TenantBranding.iconName("e2"), branding.brandFor(ta)!!.icon!!.name)
        assertArrayEquals(fakePng(0xA), iconBytes(ta))

        // A new logo etag with new bytes replaces the file; only the current icon stays on disk.
        a.instance = { BrandServer.json(BrandServer.branded("Team Two", "e3")) }
        a.icon = { BrandServer.png(fakePng(0xC)) }
        refresh(ta, force = true)
        assertArrayEquals(fakePng(0xC), iconBytes(ta))
        assertEquals(setOf("meta.json", TenantBranding.iconName("e3")), tmp.root.resolve("ta").list()!!.toSet())
    }

    /** 🔴 A logo on another origin is never requested: the name stays, the icon is the default. */
    @Test
    fun aCrossOriginLogoIsNeverFetched() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1", icon = "${b.origin}/brand/icon-192.png")) }
        b.icon = { BrandServer.png(fakePng(0xB)) }
        refresh(ta)
        assertEquals(TenantBrand("Team", null), branding.brandFor(ta))
        assertEquals(listOf("/api/instance"), a.paths())
        assertEquals("the other origin saw nothing", emptyList<String>(), b.paths())
    }

    /** 🔴 An oversized or non-PNG icon is refused: no icon, nothing cached. */
    @Test
    fun anOversizedOrNonPngIconIsRefused() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1")) }
        a.icon = { BrandServer.png(fakePng(0xA, fill = InstanceApi.MAX_ICON_BYTES.toInt())) }
        refresh(ta)
        assertEquals(TenantBrand("Team", null), branding.brandFor(ta))

        a.instance = { BrandServer.json(BrandServer.branded("Team", "e2")) }
        a.icon = { BrandServer.png("<svg/>".encodeToByteArray(), "image/svg+xml") }
        refresh(ta, force = true)
        assertEquals(TenantBrand("Team", null), branding.brandFor(ta))
        assertEquals(listOf("meta.json"), tmp.root.resolve("ta").list()!!.toList())
    }

    /**
     * 🔴 Per-tenant isolation: two servers with the SAME logo etag and different
     * bytes each keep their own icon, and a server without branding never shows
     * the other's.
     */
    @Test
    fun eachTenantKeepsItsOwnLogo() {
        a.instance = { BrandServer.json(BrandServer.branded("Team A", "v1")) }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        b.instance = { BrandServer.json(BrandServer.branded("Team B", "v1")) }
        b.icon = { BrandServer.png(fakePng(0xB)) }
        runBlocking { branding.refreshAll(Tenants(current = "ta", all = listOf(ta, tb), migrated = true)) }
        assertArrayEquals(fakePng(0xA), iconBytes(ta))
        assertArrayEquals(fakePng(0xB), iconBytes(tb))
        assertEquals("Team A", branding.brandFor(ta)!!.name)
        assertEquals("Team B", branding.brandFor(tb)!!.name)

        b.instance = { MockResponse.Builder().code(404).build() }
        refresh(tb, force = true)
        assertNull(branding.brandFor(tb))
        assertArrayEquals(fakePng(0xA), iconBytes(ta))
        assertEquals(mapOf("ta" to branding.brandFor(ta)), branding.brands(Tenants(current = "ta", all = listOf(ta, tb), migrated = true)))
    }

    /**
     * 🔴 Branding is bound to the origin it was read from: a tenant id that now
     * names another server shows nothing of the old one, and the old record's
     * etags are never sent to the new server.
     */
    @Test
    fun brandingIsBoundToItsOrigin() {
        a.instance = { BrandServer.json(BrandServer.branded("Team A", "v1"), "ETag" to "\"ia\"") }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta)
        val moved = ta.copy(device = DeviceRecord(origin = b.origin))
        assertNull(branding.brandFor(moved))

        b.instance = { BrandServer.json("""{"name":"Team B"}""") }
        refresh(moved)
        assertNull(b.seen.single().headers["If-None-Match"])
        assertEquals(TenantBrand("Team B", null), branding.brandFor(moved))
    }

    /** Stored branding survives a process restart: a new instance reads it from disk. */
    @Test
    fun brandingIsReadBackAfterARestart() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1"), "Cache-Control" to "max-age=600") }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta)
        branding = newBranding()
        assertEquals("Team", branding.brandFor(ta)!!.name)
        assertArrayEquals(fakePng(0xA), iconBytes(ta))
        a.seen.clear()
        assertEquals(TenantBranding.Outcome.FRESH, refresh(ta))
        assertEquals(emptyList<String>(), a.paths())
    }

    /** A removed tenant's branding goes, from disk and memory; the other tenant's stays. */
    @Test
    fun forgetRemovesOnlyThatTenant() {
        a.instance = { BrandServer.json(BrandServer.branded("Team A", "v1")) }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        b.instance = { BrandServer.json("""{"name":"Team B"}""") }
        refresh(ta); refresh(tb)
        runBlocking { branding.forget("ta") }
        assertNull(branding.brandFor(ta))
        assertFalse(tmp.root.resolve("ta").exists())
        assertEquals("Team B", branding.brandFor(tb)!!.name)
        assertNull(newBranding().brandFor(ta))
    }

    /** A push's icon etag: the stored one does nothing; a new one forces a read, at most once a minute. */
    @Test
    fun aPushsNewIconEtagForcesARead() {
        a.instance = { BrandServer.json(BrandServer.branded("Team", "e1"), "Cache-Control" to "max-age=86400") }
        a.icon = { BrandServer.png(fakePng(0xA)) }
        refresh(ta)
        a.seen.clear()
        assertFalse(runBlocking { branding.onIconEtag(ta, "e1") })
        assertEquals(emptyList<String>(), a.paths())

        a.instance = { BrandServer.json(BrandServer.branded("Team", "e2")) }
        a.icon = { BrandServer.png(fakePng(0xC)) }
        assertTrue(runBlocking { branding.onIconEtag(ta, "e2") })
        assertArrayEquals(fakePng(0xC), iconBytes(ta))

        a.seen.clear()
        clock += 30_000
        assertFalse("throttled", runBlocking { branding.onIconEtag(ta, "e3") })
        assertEquals(emptyList<String>(), a.paths())
        clock += TenantBranding.MIN_HINT_GAP_MS
        assertTrue(runBlocking { branding.onIconEtag(ta, "e3") })
    }

    /** An id that is not a tenant id never becomes a path. */
    @Test
    fun anInvalidIdIsNoTenant() {
        val bad = ta.copy(id = "../x")
        assertEquals(TenantBranding.Outcome.NO_TENANT, refresh(bad))
        assertEquals(emptyList<String>(), a.paths())
    }
}
