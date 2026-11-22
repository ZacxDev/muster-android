package dev.zacx.muster.tenants

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.tenants.QrHandoff.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The QR sign-in hand-off's matcher: which browser intents open
 * `/auth/qr#<code>` in which of this phone's servers. Every fixture differs
 * from every other (hosts, ports, codes), so a check that compared the wrong
 * field, or a host without its port, cannot pass by accident.
 */
class QrHandoffTest {
    private val alpha = TenantRecord(Tenants.DEFAULT, "alpha", 1, Tenants.DEFAULT_PROFILE, DeviceRecord(origin = "https://alpha.example.test"))
    private val beta = TenantRecord("t2", "beta", 2, "tenant-t2", DeviceRecord(origin = "https://beta.example.test:8443"))
    private val tenants = Tenants(current = alpha.id, all = listOf(alpha, beta), migrated = true)

    /** muster's codes: `mqr_` and 43 base64url characters. Two distinct ones. */
    private val code1 = "mqr_" + "Ab3-_x".repeat(7) + "Q"
    private val code2 = "mqr_" + "zY9_-k".repeat(7) + "W"
    private val view = QrHandoff.ACTION_VIEW

    private fun match(data: String?, code: String?, action: String? = view, fromHistory: Boolean = false) =
        QrHandoff.match(tenants, action, data, code, fromHistory)

    @Test
    fun aKnownServersOriginWithAValidCodeOpensItsPage() {
        assertEquals(Result.Open(alpha, "/auth/qr#$code1"), match("https://alpha.example.test/auth/qr", code1))
        // The tenant not in view, on a non-default port: it is the one opened.
        assertEquals(Result.Open(beta, "/auth/qr#$code2"), match("https://beta.example.test:8443/auth/qr", code2))
    }

    @Test
    fun theOriginIsComparedNormalised() {
        // Host case, a trailing dot and an explicit :443 are the same origin.
        assertEquals(Result.Open(alpha, "/auth/qr#$code2"), match("https://ALPHA.Example.test:443/auth/qr", code2))
        assertEquals(Result.Open(alpha, "/auth/qr#$code1"), match("HTTPS://alpha.example.test./auth/qr", code1))
    }

    @Test
    fun anUnknownHostIsAnUnknownServer() {
        assertEquals(Result.UnknownServer, match("https://gamma.example.test/auth/qr", code1))
        assertEquals(Result.UnknownServer, match("https://alpha.example.test.evil.test/auth/qr", code1))
    }

    /** Same host, another port: another origin, never that server. */
    @Test
    fun aHostThatDiffersOnlyInPortIsUnknown() {
        assertEquals(Result.UnknownServer, match("https://alpha.example.test:8443/auth/qr", code1))
        assertEquals(Result.UnknownServer, match("https://beta.example.test/auth/qr", code2))
        assertEquals(Result.UnknownServer, match("https://beta.example.test:9443/auth/qr", code2))
    }

    @Test
    fun httpIsNotAHandoff() {
        assertEquals(Result.NotHandoff, match("http://alpha.example.test/auth/qr", code1))
        assertEquals(Result.NotHandoff, match("http://beta.example.test:8443/auth/qr", code2))
        // Refused as not a hand-off before the code is looked at.
        assertEquals(Result.NotHandoff, match("http://alpha.example.test/auth/qr", "mqr_bad+code"))
    }

    @Test
    fun anyOtherPathIsNotAHandoff() {
        for (path in listOf("/auth/qr/x", "/auth/qrx", "/auth/qr/", "/auth/QR", "/auth", "/", "", "//auth/qr", "/auth/%71r")) {
            assertEquals(path, Result.NotHandoff, match("https://alpha.example.test$path", code1))
        }
    }

    @Test
    fun aQueryFragmentOrUserInfoIsNotAHandoff() {
        assertEquals(Result.NotHandoff, match("https://alpha.example.test/auth/qr?next=1", code1))
        assertEquals(Result.NotHandoff, match("https://alpha.example.test/auth/qr#$code1", code1))
        assertEquals(Result.NotHandoff, match("https://user@alpha.example.test/auth/qr", code1))
        assertEquals(Result.NotHandoff, match("https://user@alpha.example.test/auth/qr", "mqr_bad+code"))
    }

    @Test
    fun aMissingCodeOpensNothing() {
        assertEquals(Result.BadCode, match("https://alpha.example.test/auth/qr", null))
        assertEquals(Result.BadCode, match("https://alpha.example.test/auth/qr", ""))
    }

    @Test
    fun aMalformedCodeOpensNothing() {
        val bad = listOf(
            "mqr_", // nothing after the prefix
            "mqr_abc+def", "mqr_abc/def", "mqr_abc=", "mqr_abc def", "mqr_abc#x", "mqr_abc\n", "mqr_abc%41",
            "mqr_ébc", "MQR_abcdef", "xqr_abcdef", "abcdef", " mqr_abcdef",
        )
        for (c in bad) assertEquals(c, Result.BadCode, match("https://alpha.example.test/auth/qr", c))
    }

    @Test
    fun anOverlongCodeOpensNothing() {
        val atMax = "mqr_" + "a".repeat(QrHandoff.MAX_CODE_LENGTH - 4)
        assertEquals(128, atMax.length)
        assertEquals(Result.Open(alpha, "/auth/qr#$atMax"), match("https://alpha.example.test/auth/qr", atMax))
        assertEquals(Result.BadCode, match("https://alpha.example.test/auth/qr", atMax + "b"))
        assertEquals(Result.BadCode, match("https://alpha.example.test/auth/qr", "mqr_" + "c".repeat(5000)))
    }

    @Test
    fun anotherActionNoDataOrARecentsReplayIsNotAHandoff() {
        assertEquals(Result.NotHandoff, match("https://alpha.example.test/auth/qr", code1, action = null))
        assertEquals(Result.NotHandoff, match("https://alpha.example.test/auth/qr", code1, action = "android.intent.action.MAIN"))
        assertEquals(Result.NotHandoff, match(null, code1))
        assertEquals(Result.NotHandoff, match("https://alpha.example.test/auth/qr", code1, fromHistory = true))
    }

    @Test
    fun noServersMeansUnknown() {
        assertEquals(Result.UnknownServer, QrHandoff.match(Tenants(migrated = true), view, "https://alpha.example.test/auth/qr", code1))
    }

    /** 🔴 The code is a sign-in secret: an Open logged by mistake must not carry it. */
    @Test
    fun anOpensTextNeverCarriesTheCode() {
        val open = match("https://alpha.example.test/auth/qr", code1)
        assertFalse(open.toString().contains(code1))
        assertFalse(open.toString().contains(code1.removePrefix("mqr_")))
    }
}
