package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.feature.push.PushRegistrar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a launch or tap opens, and the background job's read of every server. */
class TenantRoutingTest {
    private val token = "mdv_" + "T".repeat(43)
    private val a = TenantRecord(Tenants.DEFAULT, "a", 1, Tenants.DEFAULT_PROFILE, DeviceRecord(origin = "https://a.example.test", token = token, deviceId = 1))
    private val b = TenantRecord("t2", "b", 2, "tenant-t2", DeviceRecord(origin = "https://b.example.test"))
    private val tenants = Tenants(current = a.id, all = listOf(a, b), migrated = true)

    @Test
    fun aPlainLaunchOpensTheTenantInView() {
        assertEquals(TapRouting.Target(a, null, switched = false), TapRouting.resolve(tenants, null, null))
        assertEquals(TapRouting.Target(b, null, switched = false), TapRouting.resolve(tenants.copy(current = "t2"), null, null))
    }

    @Test
    fun aTapForAnotherTenantSwitchesAndKeepsItsPath() {
        assertEquals(TapRouting.Target(b, "/tasks/4", switched = true), TapRouting.resolve(tenants, "t2", "/tasks/4"))
        assertEquals(TapRouting.Target(a, "/tasks/4", switched = false), TapRouting.resolve(tenants, "default", "/tasks/4"))
    }

    /** A tap for a removed tenant never opens its path on another server. */
    @Test
    fun aTapForAGoneTenantOpensTheTenantInViewAtHome() {
        assertEquals(TapRouting.Target(a, null, switched = false), TapRouting.resolve(tenants, "gone", "/tasks/4"))
    }

    @Test
    fun noTenantsNoShell() {
        assertNull(TapRouting.resolve(Tenants(migrated = true), null, null))
        assertNull(TapRouting.resolve(Tenants(migrated = true), "t2", "/x"))
    }

    @Test
    fun theBackgroundReadCoversEveryPairedServerOnItsOwn() = runBlocking {
        val c = TenantRecord("t3", "c", 3, "tenant-t3", DeviceRecord(origin = "https://c.example.test", token = token, deviceId = 3))
        val all = Tenants(current = a.id, all = listOf(a, b, c), migrated = true)
        val read = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val results = mapOf(a.id to PushRegistrar.ConfigResult.Revoked, c.id to PushRegistrar.ConfigResult.NoPush)
        val out = refreshAllTenants(all, { t -> registrar(t, read, results.getValue(t.id)) }) { revoked += it.id }
        assertEquals("only paired servers are read", listOf(a.id, c.id), read)
        assertEquals(listOf(a.id), revoked)
        assertEquals("c is still paired: the job keeps going", PushRegistrar.ConfigResult.NoPush, out)
    }

    @Test
    fun theBackgroundReadReportsNotPairedOnlyWhenNoServerIs() = runBlocking {
        val none = Tenants(current = b.id, all = listOf(b), migrated = true)
        assertEquals(PushRegistrar.ConfigResult.NotPaired, refreshAllTenants(none, { error("no read") }) {})
        val read = mutableListOf<String>()
        val onlyRevoked = refreshAllTenants(tenants, { t -> registrar(t, read, PushRegistrar.ConfigResult.Revoked) }) {}
        assertEquals(PushRegistrar.ConfigResult.Revoked, onlyRevoked)
        val failed = refreshAllTenants(tenants, { t -> registrar(t, read, PushRegistrar.ConfigResult.Failed("down")) }) {}
        assertEquals(PushRegistrar.ConfigResult.Failed("down"), failed)
        assertFalse(read.contains("t2"))
    }

    /** Tenant [t]'s config read, recorded, answering [r]. */
    private fun registrar(t: TenantRecord, read: MutableList<String>, r: PushRegistrar.ConfigResult): PushRegistrar.ConfigResult {
        read += t.id
        return r
    }
}
