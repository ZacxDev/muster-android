package dev.zacx.muster.feature.stepup

import android.app.Application
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.ActionId
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.AskRequest
import dev.zacx.muster.core.net.SignedAsk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import kotlinx.coroutines.CompletableDeferred
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/** A host with a real-looking pairing (a step-up key) for "t1" only. */
class PairedHostApp : Application(), StepUpHost {
    val asked = mutableListOf<String>()
    val outcomes = mutableListOf<Shown>()

    override suspend fun stepUpPairing(tenant: String): StepUpHost.Pairing? {
        asked += tenant
        return if (tenant == "t1") StepUpHost.Pairing("https://a.example.test", "mdv_" + "T".repeat(43), "muster-stepup-t1", keyTakesPin = false) else null
    }

    override fun showOutcome(tenant: String, tag: String, title: String, path: String?, outcome: AskOutcome, ifShowing: Boolean) {
        outcomes += Shown(tenant, tag, outcome, ifShowing)
    }
}

/**
 * The prompt and the send, past the seams a JVM cannot run (Keystore,
 * muster, BiometricPrompt): every key is a fresh software EC key, muster
 * answers a fixed challenge, and the test plays the prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = PairedHostApp::class)
class StepUpPromptTest {
    private val app: PairedHostApp = ApplicationProvider.getApplicationContext()
    private val answer = AskAction(ActionId.ASK_ANSWER, "41", "ship it", "ship it", AskAction.Kind.OPTION)

    private class FakeSeams : StepUpSeams {
        val keys = mutableListOf<KeyPair>()
        val signers = mutableListOf<Signature>()
        val sent = mutableListOf<Triple<AskRequest, String, ByteArray>>()
        val prompts = mutableListOf<Pair<Signature, StepUpSeams.PromptResult>>()
        val message = "muster-device-action-v1\nmdc_test".toByteArray()
        var throwOnPrompt = false
        /** When set, [send] suspends on it (the answer stays in flight). */
        var sendGate: CompletableDeferred<Unit>? = null
        var sending = false

        override fun signer(alias: String): Signature {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            keys += kp
            return Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private) }.also { signers += it }
        }

        override suspend fun challenge(p: StepUpHost.Pairing, r: AskRequest) = SignedAsk.Prepared.Ready("mdc_test", message, null)

        override suspend fun send(p: StepUpHost.Pairing, r: AskRequest, nonce: String, der: ByteArray): AskOutcome {
            sending = true
            sent += Triple(r, nonce, der)
            sendGate?.await()
            return AskOutcome.Done
        }

        override fun authenticate(activity: FragmentActivity, info: BiometricPrompt.PromptInfo, signature: Signature, result: StepUpSeams.PromptResult) {
            if (throwOnPrompt) throw IllegalStateException("no prompt")
            prompts += signature to result
        }
    }

    private val seams = FakeSeams()

    @Before
    fun setUp() {
        StepUpActivity.seams = seams
        app.asked.clear()
        app.outcomes.clear()
    }

    @After
    fun tearDown() {
        StepUpActivity.seams = StepUpSeams.Real
    }

    private fun tapIntent(tag: String, a: AskAction = answer) =
        shadowOf(StepUpActivity.pendingIntent(app, "t1", tag, 0, "chief is asking", null, a)).savedIntent

    /** Runs the main looper (every seam answers on it, no other thread), then checks [done]. */
    private fun await(done: () -> Boolean) {
        ShadowLooper.idleMainLooper()
        check(done()) { "not reached after the main looper idled" }
    }

    private fun openPrompt(tag: String = "attention-ask-41"): ActivityController<StepUpActivity> {
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent(tag)).setup()
        await { seams.prompts.isNotEmpty() || app.outcomes.isNotEmpty() }
        assertEquals(StepUpFlow.Phase.PROMPTING, c.get().phase)
        return c
    }

    private fun verifies(kp: KeyPair, der: ByteArray) =
        Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(seams.message) }.verify(der)

    @Test
    fun aConfirmedPromptSignsWithItsOwnKeyAndSends() {
        val c = openPrompt()
        val (sig, result) = seams.prompts.single()
        assertTrue(sig === seams.signers.single()) // the prompt wraps this attempt's signer

        result.succeeded(sig)
        await { app.outcomes.isNotEmpty() }

        val (req, nonce, der) = seams.sent.single()
        assertEquals(AskRequest(ActionId.ASK_ANSWER, "41", "ship it"), req)
        assertEquals("mdc_test", nonce)
        assertTrue(verifies(seams.keys.single(), der))
        assertEquals(listOf(Shown("t1", "attention-ask-41", AskOutcome.Done, false)), app.outcomes)
        assertTrue(c.get().isFinishing)
        assertEquals(listOf("t1"), app.asked)
    }

    /** Two attempts, two keys: the second tap is signed by the second attempt's key, never the first's. */
    @Test
    fun eachAttemptSignsWithItsOwnSignature() {
        val first = openPrompt()
        seams.prompts.single().second.error(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled")
        assertTrue(first.get().isFinishing)

        val second = openPrompt("attention-ask-42")
        val (sig, result) = seams.prompts[1]
        assertTrue(sig === seams.signers[1])
        result.succeeded(sig)
        await { app.outcomes.isNotEmpty() }

        val der = seams.sent.single().third
        assertTrue(verifies(seams.keys[1], der))
        assertFalse(verifies(seams.keys[0], der))
        assertTrue(second.get().isFinishing)
    }

    /** A second tap while a prompt is genuinely open: the busy toast, and nothing sent for it. */
    @Test
    fun aSecondTapWhileThePromptIsOpenIsRefused() {
        val c = openPrompt()
        c.newIntent(tapIntent("attention-ask-42"))
        ShadowLooper.idleMainLooper()
        assertEquals("Finish the open confirmation first", ShadowToast.getTextOfLatestToast())
        assertEquals(1, seams.prompts.size)
        assertEquals(emptyList<Any>(), seams.sent)
        assertEquals(StepUpFlow.Phase.PROMPTING, c.get().phase)
        assertFalse(c.get().isFinishing)
    }

    /** 🔴 Operator decision: the user's own cancel leaves the notification exactly as it was. */
    @Test
    fun aUserCancelPostsNothing() {
        for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
            seams.prompts.clear()
            val c = openPrompt()
            seams.prompts.single().second.error(code, "Cancel")
            ShadowLooper.idleMainLooper()
            assertTrue(c.get().isFinishing)
        }
        assertEquals(emptyList<Shown>(), app.outcomes)
        assertEquals(emptyList<Any>(), seams.sent)
    }

    @Test
    fun aSystemCancelAndOtherErrorsSayWhyInAShowingNotification() {
        val c = openPrompt()
        seams.prompts.single().second.error(BiometricPrompt.ERROR_CANCELED, "Canceled")
        assertTrue(c.get().isFinishing)
        seams.prompts.clear()
        val d = openPrompt("attention-ask-42")
        seams.prompts.single().second.error(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts")
        assertTrue(d.get().isFinishing)
        assertEquals(
            listOf(
                Shown("t1", "attention-ask-41", AskOutcome.Failed(StepUpFlow.INTERRUPTED), true),
                Shown("t1", "attention-ask-42", AskOutcome.Failed("Not sent: Too many attempts"), true),
            ),
            app.outcomes,
        )
        assertEquals(emptyList<Any>(), seams.sent)
    }

    @Test
    fun aPromptThatCannotBeShownSaysSo() {
        seams.throwOnPrompt = true
        val c = Robolectric.buildActivity(StepUpActivity::class.java, tapIntent("attention-ask-41")).setup()
        await { app.outcomes.isNotEmpty() }
        assertEquals(listOf(Shown("t1", "attention-ask-41", AskOutcome.Failed(StepUpFlow.PROMPT_NOT_SHOWN), true)), app.outcomes)
        assertTrue(c.get().isFinishing)
        assertEquals(emptyList<Any>(), seams.sent)
    }

    /** A success whose CryptoObject came back without its signature is never sent. */
    @Test
    fun aSuccessWithoutItsSignatureIsNotSent() {
        val c = openPrompt()
        seams.prompts.single().second.succeeded(null)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(Shown("t1", "attention-ask-41", AskOutcome.Failed(SignedAsk.REPAIR), false)), app.outcomes)
        assertEquals(emptyList<Any>(), seams.sent)
        assertTrue(c.get().isFinishing)
    }

    /**
     * 🔴 Recreated while the answer is being sent: exactly one outcome, "may
     * not have been sent", never a contradicting "Not sent" from either
     * instance, and the late result of the old send changes nothing.
     */
    @Test
    fun aRecreateWhileSendingSaysOnceThatTheResultIsUnknown() {
        val release = CompletableDeferred<Unit>()
        seams.sendGate = release
        val c = openPrompt()
        val (sig, result) = seams.prompts.single()
        result.succeeded(sig)
        await { seams.sending }
        assertEquals(StepUpFlow.Phase.SENDING, c.get().phase)

        c.recreate()
        ShadowLooper.idleMainLooper()
        release.complete(Unit) // the old send's late result
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(Shown("t1", "attention-ask-41", AskOutcome.Failed(StepUpFlow.SEND_UNKNOWN), true)), app.outcomes)
        assertTrue(c.get().isFinishing)
        assertEquals(1, seams.prompts.size) // no second prompt, no second send
    }

    @Test
    fun promptErrorCodes() {
        assertEquals(StepUpActivity.PromptEnd.UserCancelled, StepUpActivity.promptEnd(BiometricPrompt.ERROR_USER_CANCELED, "x"))
        assertEquals(StepUpActivity.PromptEnd.UserCancelled, StepUpActivity.promptEnd(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "x"))
        assertEquals(
            StepUpActivity.PromptEnd.Failed("prompt-interrupted", StepUpFlow.INTERRUPTED),
            StepUpActivity.promptEnd(BiometricPrompt.ERROR_CANCELED, "x"),
        )
        assertEquals(
            StepUpActivity.PromptEnd.Failed("prompt-error-7", "Not sent: Too many attempts"),
            StepUpActivity.promptEnd(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts"),
        )
    }
}
