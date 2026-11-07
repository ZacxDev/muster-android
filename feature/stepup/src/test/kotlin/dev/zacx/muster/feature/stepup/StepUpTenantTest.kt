package dev.zacx.muster.feature.stepup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.SignedAsk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Two paired servers: "default" with a step-up key (the one a "current
 * server" lookup would find), "t2" read-only.
 */
class TwoTenantHostApp : Application(), StepUpHost {
    val asked = mutableListOf<String>()
    val outcomes = mutableListOf<Triple<String, String, AskOutcome>>()
    private val token = "mdv_" + "T".repeat(43)
    private val pairings = mapOf(
        "default" to StepUpHost.Pairing("https://a.example.test", token, "muster-stepup-a", keyTakesPin = false),
        "t2" to StepUpHost.Pairing("https://b.example.test", token, keyAlias = null, keyTakesPin = false),
    )

    override suspend fun stepUpPairing(tenant: String): StepUpHost.Pairing? {
        asked += tenant
        return pairings[tenant]
    }

    override fun showOutcome(tenant: String, tag: String, title: String, path: String?, outcome: AskOutcome) {
        outcomes += Triple(tenant, tag, outcome)
    }
}

/**
 * 🔴 A button carries its server, and the step-up activity uses ONLY that
 * server's pairing (muster docs/design/multi-tenant.md: "A button never
 * resolves the current tenant").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = TwoTenantHostApp::class)
class StepUpTenantTest {
    private val app: TwoTenantHostApp = ApplicationProvider.getApplicationContext()
    private val answer = AskAction(ActionId.ASK_ANSWER, "41", "ship it", "ship it", AskAction.Kind.OPTION)

    @Before
    fun setUp() {
        app.asked.clear()
        app.outcomes.clear()
    }

    /**
     * A button on t2's notification asks for t2's pairing, finds it read-only,
     * and says so in t2's notification. It never reaches for "default"'s key,
     * which would have been the one to sign with.
     */
    @Test
    fun aButtonUsesOnlyItsOwnServersPairing() {
        val i = shadowOf(StepUpActivity.pendingIntent(app, "t2", "t2:attention-ask-41", 0, "chief is asking", null, answer)).savedIntent
        Robolectric.buildActivity(StepUpActivity::class.java, i).setup()
        ShadowLooper.idleMainLooper()

        assertEquals(listOf("t2"), app.asked)
        assertEquals(listOf(Triple("t2", "t2:attention-ask-41", AskOutcome.Failed(SignedAsk.READ_ONLY) as AskOutcome)), app.outcomes)
    }

    /** A button for a server that is gone is refused as unpaired; no other server is asked. */
    @Test
    fun aButtonForARemovedServerIsRefused() {
        val i = shadowOf(StepUpActivity.pendingIntent(app, "gone", "gone:attention-ask-41", 0, "chief is asking", null, answer)).savedIntent
        Robolectric.buildActivity(StepUpActivity::class.java, i).setup()
        ShadowLooper.idleMainLooper()

        assertEquals(listOf("gone"), app.asked)
        assertEquals(listOf(Triple("gone", "gone:attention-ask-41", AskOutcome.Failed(SignedAsk.REVOKED) as AskOutcome)), app.outcomes)
    }

    /** Equal tags on two servers: two different PendingIntents, each carrying its own server. */
    @Test
    fun equalTagsOnTwoServersAreDifferentButtons() {
        val a = StepUpActivity.pendingIntent(app, "default", "attention-ask-41", 0, "t", null, answer)
        val b = StepUpActivity.pendingIntent(app, "t2", "attention-ask-41", 0, "t", null, answer)
        org.junit.Assert.assertNotEquals(a, b)
        assertEquals("default", StepUpActivity.Tap.from(shadowOf(a).savedIntent)!!.tenant)
        assertEquals("t2", StepUpActivity.Tap.from(shadowOf(b).savedIntent)!!.tenant)
    }
}
