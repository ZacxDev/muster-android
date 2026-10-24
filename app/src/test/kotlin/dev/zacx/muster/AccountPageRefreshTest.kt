package dev.zacx.muster

import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.feature.shell.OriginPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPageRefreshTest {
    private val policy = OriginPolicy.forServer("https://m.example.test")!!
    private val account = "https://m.example.test/account"

    @Test
    fun reloadsAccountWhenPushBecomesRegistered() {
        PushStatus.entries.filter { it != PushStatus.REGISTERED }.forEach { before ->
            assertTrue(before.name, AccountPageRefresh.shouldReload(before, PushStatus.REGISTERED, account, policy))
        }
        assertTrue(AccountPageRefresh.shouldReload(PushStatus.REGISTERING, PushStatus.REGISTERED, "$account/", policy))
        assertTrue(AccountPageRefresh.shouldReload(PushStatus.REGISTERING, PushStatus.REGISTERED, "$account?x=1#devices", policy))
    }

    @Test
    fun noReloadUnlessTheStatusJustBecameRegistered() {
        // The first value read is not a change.
        assertFalse(AccountPageRefresh.shouldReload(null, PushStatus.REGISTERED, account, policy))
        assertFalse(AccountPageRefresh.shouldReload(PushStatus.REGISTERED, PushStatus.REGISTERED, account, policy))
        PushStatus.entries.filter { it != PushStatus.REGISTERED }.forEach { after ->
            assertFalse(after.name, AccountPageRefresh.shouldReload(PushStatus.REGISTERING, after, account, policy))
        }
    }

    @Test
    fun noReloadOffTheAccountPage() {
        listOf(
            "https://m.example.test/tasks",
            "https://m.example.test/account/devices/pair",
            "https://m.example.test/accounts",
            "https://m.example.test/",
            "https://other.example.test/account",
            "http://m.example.test/account",
            "not a url",
            null,
        ).forEach { assertFalse(it.toString(), AccountPageRefresh.shouldReload(PushStatus.REGISTERING, PushStatus.REGISTERED, it, policy)) }
    }
}
