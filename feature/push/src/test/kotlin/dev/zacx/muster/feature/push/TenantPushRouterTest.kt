package dev.zacx.muster.feature.push

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.feature.push.PushHandler.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A push is drawn by the tenant whose UnifiedPush instance it arrived on, and
 * by no other (muster docs/design/multi-tenant.md, "Push").
 */
class TenantPushRouterTest {
    private val token = "mdv_" + "T".repeat(43)
    private val a = TenantRecord(Tenants.DEFAULT, "a.example.test", 1, Tenants.DEFAULT_PROFILE,
        DeviceRecord(origin = "https://a.example.test", token = token, deviceId = 1, keyAlias = "k-a"))
    private val b = TenantRecord("t2", "b.example.test", 2, "tenant-t2",
        DeviceRecord(origin = "https://b.example.test", token = token, deviceId = 2))
    private val tenants = Tenants(current = a.id, all = listOf(a, b), migrated = true)

    private val sinks = mapOf(a.id to RecordingSink(), b.id to RecordingSink())
    private val router = TenantPushRouter { t -> sinks.getValue(t.id) }

    private val show = """{"v":2,"type":"attention","id":"9","tag":"att-9","title":"Agent ada","body":"in muster","url":"/attention"}""".encodeToByteArray()
    private val resolved = """{"v":2,"type":"resolved","id":"9","tag":"att-9"}""".encodeToByteArray()

    @Test
    fun aPushIsDrawnOnlyByTheTenantItArrivedFor() {
        assertEquals(Outcome.SHOWN, router.handle("t2", show, decrypted = true, tenants = tenants))
        assertEquals(1, sinks.getValue("t2").shown.size)
        assertTrue("tenant default drew nothing", sinks.getValue(Tenants.DEFAULT).shown.isEmpty())

        // The migrated tenant's pre-upgrade instance "default" still delivers, to it.
        assertEquals(Outcome.SHOWN, router.handle("default", show, decrypted = true, tenants = tenants))
        assertEquals(1, sinks.getValue(Tenants.DEFAULT).shown.size)

        assertEquals(Outcome.WITHDRAWN, router.handle("t2", resolved, decrypted = true, tenants = tenants))
        assertEquals(listOf("att-9"), sinks.getValue("t2").withdrawn)
        assertTrue(sinks.getValue(Tenants.DEFAULT).withdrawn.isEmpty())
    }

    /** A removed tenant's late delivery (an instance no tenant owns) is dropped. */
    @Test
    fun anUnknownInstanceIsDropped() {
        assertEquals(Outcome.DROPPED_UNKNOWN_TENANT, router.handle("gone", show, decrypted = true, tenants = tenants))
        assertTrue(sinks.values.all { it.shown.isEmpty() })
    }

    /** 🔴 Undecrypted is dropped first, whatever the instance says. */
    @Test
    fun anUndecryptedPushIsDroppedBeforeTheInstanceIsLookedAt() {
        assertEquals(Outcome.DROPPED_UNDECRYPTED, router.handle("t2", show, decrypted = false, tenants = tenants))
        assertEquals(Outcome.DROPPED_UNDECRYPTED, router.handle("gone", show, decrypted = false, tenants = tenants))
        assertTrue(sinks.values.all { it.shown.isEmpty() })
    }

    /** Pairing state and the step-up key are the arriving tenant's, not another's. */
    @Test
    fun pairingAndButtonsAreThatTenants() {
        val ask = """{"v":2,"type":"attention","tag":"attention-ask-41","title":"chief is asking","body":"Ship it?",
            "ask":{"id":41,"options":["ship it"]},"actions":[{"id":"ask_answer","target":"41","option":0},
            {"id":"ask_dismiss","label":"Dismiss","target":"41"}]}""".encodeToByteArray()
        router.handle("default", ask, decrypted = true, tenants = tenants)
        router.handle("t2", ask, decrypted = true, tenants = tenants)
        assertEquals("a has a key: buttons", 2, sinks.getValue(Tenants.DEFAULT).shown.single().ask!!.buttons().size)
        assertEquals("b is read-only: none", 0, sinks.getValue("t2").shown.single().ask!!.actions.size)

        val unpairedB = tenants.copy(all = listOf(a, b.copy(device = b.device.unpaired())))
        assertEquals(Outcome.DROPPED_UNPAIRED, router.handle("t2", show, decrypted = true, tenants = unpairedB))
    }
}
