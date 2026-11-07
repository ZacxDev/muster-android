package dev.zacx.muster.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stand-in for WebView profiles: each profile is a cookie jar, and a view
 * reads and writes the jar of the profile it was put in (the default one
 * unless set). That is the property androidx.webkit documents for profiles:
 * "nothing is shared between profiles".
 */
class FakeProfiles(override val multiProfile: Boolean = true) : TenantSessions.Backend<FakeProfiles.View> {
    class View { var profile: String = TenantSessions.DEFAULT; var loaded = false }

    val jars = mutableMapOf<String, MutableMap<String, String>>()
    val deleted = mutableListOf<String>()
    val cleared = mutableListOf<String>()
    var deleteFails = false

    fun jar(name: String) = jars.getOrPut(name) { mutableMapOf() }

    /** The page on [v] sets a cookie, in [v]'s profile. */
    fun setCookie(v: View, k: String, value: String) { v.loaded = true; jar(v.profile)[k] = value }

    fun cookie(v: View, k: String): String? = jar(v.profile)[k]

    override fun setProfile(view: View, name: String) {
        check(!view.loaded) { "the profile must be set before the first load" }
        view.profile = name
    }

    override fun delete(name: String): Boolean {
        if (deleteFails || name == TenantSessions.DEFAULT) return false
        deleted += name
        jars.remove(name)
        return true
    }

    override fun clear(name: String) {
        cleared += name
        jars[name]?.clear()
    }

    override fun flush(name: String) {}
}

/** Each muster server's web session stays its own (muster docs/design/multi-tenant.md, "WebView data and cookies"). */
class TenantSessionsTest {
    private val p = FakeProfiles()
    private val sessions = TenantSessions(p)

    /** 🔴 Switching servers carries no session cookie across; switching back finds the first still signed in. */
    @Test
    fun switchingServersNeverCarriesASessionCookieAcross() {
        val a = FakeProfiles.View()
        assertTrue(sessions.prepare(a, TenantSessions.DEFAULT))
        p.setCookie(a, "muster_session", "session-of-a")

        val b = FakeProfiles.View() // the switch: a new WebView for the other server
        assertTrue(sessions.prepare(b, "tenant-t2"))
        assertNull("server A's cookie leaked into server B", p.cookie(b, "muster_session"))
        p.setCookie(b, "muster_session", "session-of-b")

        val a2 = FakeProfiles.View() // and back
        assertTrue(sessions.prepare(a2, TenantSessions.DEFAULT))
        assertEquals("session-of-a", p.cookie(a2, "muster_session"))
        val b2 = FakeProfiles.View()
        sessions.prepare(b2, "tenant-t2")
        assertEquals("session-of-b", p.cookie(b2, "muster_session"))
    }

    /** Without MULTI_PROFILE, a non-default server never loads (it would share the default profile's cookies). */
    @Test
    fun withoutProfilesASecondServerIsNeverLoadedInTheDefaultProfile() {
        val old = FakeProfiles(multiProfile = false)
        val s = TenantSessions(old)
        assertTrue("the one server keeps working", s.prepare(FakeProfiles.View(), TenantSessions.DEFAULT))
        val v = FakeProfiles.View()
        assertFalse(s.prepare(v, "tenant-t2"))
        assertFalse(s.canAddTenant(existing = 1))
        assertTrue(s.canAddTenant(existing = 0))
        assertTrue(TenantSessions(p).canAddTenant(existing = 3))
    }

    /** A removed server's profile is deleted; the default profile (never deletable) is cleared instead. */
    @Test
    fun aRemovedServersWebDataGoes() {
        val other = FakeProfiles.View()
        sessions.prepare(other, TenantSessions.DEFAULT)
        p.setCookie(other, "muster_session", "a")
        val v = FakeProfiles.View()
        sessions.prepare(v, "tenant-t2")
        p.setCookie(v, "muster_session", "b")

        sessions.forget("tenant-t2")
        assertEquals(listOf("tenant-t2"), p.deleted)
        assertEquals("the other server's session is untouched", "a", p.jar(TenantSessions.DEFAULT)["muster_session"])

        sessions.forget(TenantSessions.DEFAULT)
        assertEquals(listOf(TenantSessions.DEFAULT), p.cleared)
        assertTrue(p.jar(TenantSessions.DEFAULT).isEmpty())
    }

    /** A profile still in use cannot be deleted: its data is cleared instead, never left behind. */
    @Test
    fun aProfileThatCannotBeDeletedIsCleared() {
        val v = FakeProfiles.View()
        sessions.prepare(v, "tenant-t2")
        p.setCookie(v, "muster_session", "b")
        p.deleteFails = true
        sessions.forget("tenant-t2")
        assertEquals(listOf("tenant-t2"), p.cleared)
        assertTrue(p.jar("tenant-t2").isEmpty())
    }
}
