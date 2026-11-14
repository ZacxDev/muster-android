package dev.zacx.muster.shell

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import dev.zacx.muster.MainActivity
import dev.zacx.muster.MusterApp
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.feature.shell.OriginPolicy
import dev.zacx.muster.feature.shell.ShellNavigation
import dev.zacx.muster.tenants.TapRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's page across a re-creation (an undeclared configuration change,
 * process death): saved from the WebView's URL, restored through the same
 * checks as a notification tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShellRestoreTest {
    private val origin = "https://a.example.test"
    private val a = TenantRecord(Tenants.DEFAULT, "a", 1, Tenants.DEFAULT_PROFILE, DeviceRecord(origin = origin))
    private val b = TenantRecord("t2", "b", 2, "tenant-t2", DeviceRecord(origin = "https://b.example.test"))
    private val tenants = Tenants(current = a.id, all = listOf(a, b), migrated = true)

    /** Saved, parcelled as the system does, read back. */
    private fun roundTrip(tenant: String, url: String?): Bundle {
        val out = Bundle().also { ShellRestore.save(it, tenant, origin, url) }
        val p = Parcel.obtain()
        try {
            out.writeToParcel(p, 0)
            p.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(p).also { it.classLoader = javaClass.classLoader }
        } finally {
            p.recycle()
        }
    }

    /** Where the restored activity's route() loads: TapRouting, then tapTarget, as MainActivity does. */
    private fun loads(saved: Bundle?): String? {
        val i = ShellRestore.intent(saved) ?: return null
        val target = TapRouting.resolve(tenants, i.getStringExtra(MusterApp.EXTRA_TENANT), i.getStringExtra(MainActivity.EXTRA_PATH))!!
        return ShellNavigation.tapTarget(target.path, OriginPolicy.forServer(target.tenant.origin)!!)
    }

    @Test
    fun theTasksPageComesBackOnTasks() {
        assertEquals("$origin/tasks", loads(roundTrip(a.id, "$origin/tasks")))
        assertEquals("$origin/tasks/2?tab=log#c3", loads(roundTrip(a.id, "$origin/tasks/2?tab=log#c3")))
    }

    @Test
    fun theHomePageComesBackHome() {
        assertEquals("$origin/", loads(roundTrip(a.id, "$origin/")))
        assertEquals("$origin/", loads(roundTrip(a.id, null)))
    }

    /** A sign-in page on another origin, or a look-alike host, is never restored: the tenant's home page instead. */
    @Test
    fun aPageOffTheTenantsOriginRestoresToItsHome() {
        assertNull(ShellRestore.pathOn(origin, "https://login.example.test/?rd=x"))
        assertNull(ShellRestore.pathOn(origin, "https://a.example.test.evil.test/tasks"))
        assertNull(ShellRestore.pathOn(origin, "https://a.example.test@evil.test/tasks"))
        assertEquals("$origin/", loads(roundTrip(a.id, "https://login.example.test/tasks")))
    }

    /** The tenant that was in view, not the launch intent's: a re-created shell stays on its server. */
    @Test
    fun theSavedTenantIsTheOneRestored() {
        val i = ShellRestore.intent(roundTrip(b.id, "https://b.example.test/tasks"))!!
        assertEquals(b.id, i.getStringExtra(MusterApp.EXTRA_TENANT))
        assertNull("the path was saved against tenant a's origin", i.getStringExtra(MainActivity.EXTRA_PATH))
    }

    /** No saved shell (a first launch, or a native screen was up): the launch intent routes as before. */
    @Test
    fun nothingSavedRestoresNothing() {
        assertNull(ShellRestore.intent(null))
        assertNull(ShellRestore.intent(Bundle()))
    }
}
