package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRepository
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.feature.shell.TenantSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The seam between the store (which profile each tenant is given) and the
 * shell (which profile a WebView is put in): switching between two real
 * tenants, as the activity does (a new view, prepared with the tenant's
 * webProfile), never shows one server's cookie to the other, and the
 * migrated tenant still sees the sign-in it had before the upgrade.
 */
class TenantSessionSeamTest {
    private class View { var profile = TenantSessions.DEFAULT }

    private class Profiles : TenantSessions.Backend<View> {
        override val multiProfile = true
        val jars = mutableMapOf<String, MutableMap<String, String>>()
        fun jar(v: View) = jars.getOrPut(v.profile) { mutableMapOf() }
        override fun setProfile(view: View, name: String) { view.profile = name }
        override fun delete(name: String) = false
        override fun clear(name: String) {}
        override fun flush(name: String) {}
    }

    @Test
    fun switchingBetweenStoredTenantsNeverSharesASession() = runBlocking {
        val legacy = DeviceRecord(origin = "https://home.example.test", token = "mdv_" + "T".repeat(43), deviceId = 1)
        val repo = TenantRepository(MemTenantsFile(Tenants()), readLegacy = { legacy }, deleteLegacy = {})
        val profiles = Profiles()
        val sessions = TenantSessions(profiles)
        // The sign-in from before the upgrade lives in the default profile.
        profiles.jar(View())["muster_session"] = "home-session"

        repo.add("https://work.example.test", multiProfile = true)
        val t = repo.read()
        val home = t.byId(Tenants.DEFAULT)!!
        val work = t.all.single { it.id != Tenants.DEFAULT }

        fun open(id: String) = View().also { sessions.prepare(it, t.byId(id)!!.webProfile) }

        assertEquals("the migrated tenant keeps its sign-in", "home-session", profiles.jar(open(home.id))["muster_session"])
        val w = open(work.id)
        assertNull("home's session leaked into work", profiles.jar(w)["muster_session"])
        profiles.jar(w)["muster_session"] = "work-session"
        assertEquals("home-session", profiles.jar(open(home.id))["muster_session"])
        assertEquals("work-session", profiles.jar(open(work.id))["muster_session"])
    }
}
