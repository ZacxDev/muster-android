package dev.zacx.muster.feature.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.AskAction
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.model.PayloadV2Parser
import dev.zacx.muster.core.model.Push
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An ask's buttons, and what a tapped one comes back as in the notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class AskButtonsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val built = mutableListOf<Triple<String, Int, AskAction>>()
    private lateinit var notifier: AndroidNotifier

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifier = AndroidNotifier(
            app,
            action = { tag, i, _, _, a ->
                built += Triple(tag, i, a)
                PendingIntent.getActivity(app, i, Intent("test.STEP_UP").setData(Uri.parse("t:$tag#$i")), PendingIntent.FLAG_IMMUTABLE)
            },
        ) { tag, _ -> PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE) }
    }

    private val tag = "attention-ask-41"

    private fun push(askJson: String, actionsJson: String) {
        val p = PayloadV2Parser.parse(
            """{"v":2,"type":"attention","id":"ask-41","tag":"$tag","title":"chief is asking","body":"Ship it?\nOptions: ship it · wait",
                "url":"/attention/open/ask-41","ask":$askJson,"actions":$actionsJson}""".encodeToByteArray(),
        )
        notifier.show((p as Push.Show).payload)
    }

    private fun shown(): Notification = shadowOf(nm).activeNotifications.single { it.tag == tag }.notification

    private fun labels(n: Notification) = n.actions.orEmpty().map { it.title.toString() }

    private val all = """[{"id":"ask_answer","target":"41","option":0},{"id":"ask_answer","target":"41","option":1},
        {"id":"ask_answer","target":"41","suggestion":0},{"id":"ask_answer","label":"Answer","target":"41","input":true},
        {"id":"ask_dismiss","label":"Dismiss","target":"41"}]"""

    /** A suggestion is never a button: a free-text ask gets Dismiss alone. */
    @Test
    fun aSuggestionOnlyAskGetsDismissOnly() {
        push("""{"id":41,"suggestions":["After the backup"]}""", """[{"id":"ask_answer","target":"41","suggestion":0},{"id":"ask_dismiss","label":"Dismiss","target":"41"}]""")
        assertEquals(listOf("Dismiss"), labels(shown()))
        assertEquals(listOf(0 to null), built.map { it.second to it.third.answer })
        assertTrue(built.all { it.first == tag })
    }

    /** One option and suggestions: that option and Dismiss, the third slot left empty. */
    @Test
    fun oneOptionAndSuggestionsGetThatOptionAndDismiss() {
        push(
            """{"id":41,"options":["ship it"],"suggestions":["Wait for Monday","After the backup"]}""",
            """[{"id":"ask_answer","target":"41","option":0},{"id":"ask_answer","target":"41","suggestion":0},
                {"id":"ask_answer","target":"41","suggestion":1},{"id":"ask_dismiss","label":"Dismiss","target":"41"}]""",
        )
        assertEquals(listOf("ship it", "Dismiss"), labels(shown()))
        assertEquals(listOf(0 to "ship it", 1 to null), built.map { it.second to it.third.answer })
    }

    /** Android shows three: the first two options in muster's order, and Dismiss (never the suggestion). */
    @Test
    fun atMostThreeButtonsAndDismissKeepsItsPlace() {
        push("""{"id":41,"options":["ship it","wait"],"suggestions":["After the backup"]}""", all)
        assertEquals(listOf("ship it", "wait", "Dismiss"), labels(shown()))
    }

    @Test
    fun noActionFactoryOrNoAskDrawsNoButtons() {
        AndroidNotifier(app) { t, _ -> PendingIntent.getActivity(app, t.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE) }
            .show((PayloadV2Parser.parse("""{"v":2,"type":"attention","tag":"$tag","title":"chief is asking","ask":{"id":41,"options":["a"]},"actions":$all}""".encodeToByteArray()) as Push.Show).payload)
        assertEquals(emptyList<String>(), labels(shown()))
    }

    /** A failure keeps the question AND its buttons (to try again), says why first, and does not buzz. */
    @Test
    fun aFailureIsShownInTheNotificationWithItsButtons() {
        push("""{"id":41,"options":["ship it","wait"]}""", all)
        notifier.showOutcome(tag, "chief is asking", "/attention/open/ask-41", AskOutcome.Failed("Not sent: the confirmation expired. Tap the button again."))
        val n = shown()
        assertEquals("Not sent: the confirmation expired. Tap the button again.", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(
            "Not sent: the confirmation expired. Tap the button again.\n\nShip it?\nOptions: ship it · wait",
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        )
        assertEquals(listOf("ship it", "wait", "Dismiss"), labels(n))
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun aSecondFailureReplacesTheFirstReason() {
        push("""{"id":41,"options":["ship it"]}""", all)
        notifier.showOutcome(tag, "chief is asking", null, AskOutcome.Failed("first"))
        notifier.showOutcome(tag, "chief is asking", null, AskOutcome.Failed("second"))
        assertEquals("second\n\nShip it?\nOptions: ship it · wait", shown().extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    @Test
    fun aQuestionAlreadySettledLosesItsButtons() {
        push("""{"id":41,"options":["ship it"]}""", all)
        notifier.showOutcome(tag, "chief is asking", null, AskOutcome.Gone("Already answered, dismissed or expired."))
        val n = shown()
        assertEquals("Already answered, dismissed or expired.", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("Already answered, dismissed or expired.\n\nShip it?\nOptions: ship it · wait", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertEquals(emptyList<String>(), labels(n))
    }

    @Test
    fun sentRemovesItAndAFailureWithNothingShowingPostsOne() {
        push("""{"id":41,"options":["ship it"]}""", all)
        notifier.showOutcome(tag, "chief is asking", null, AskOutcome.Done)
        assertEquals(0, shadowOf(nm).activeNotifications.size)

        notifier.showOutcome(tag, "chief is asking", "/attention/open/ask-41", AskOutcome.Failed("Not sent: muster could not be reached. Tap the button again."))
        val n = shown()
        assertEquals("chief is asking", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Not sent: muster could not be reached. Tap the button again.", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(Channels.ASKS, n.channelId)
        // Nothing was showing, so onlyAlertOnce has nothing to replace: it must be posted silent.
        assertSilent(n)
    }

    /** A question settled elsewhere (a `resolved` push cancelled it) and nothing showing: no new notification. */
    @Test
    fun alreadySettledWithNothingShowingPostsNothing() {
        push("""{"id":41,"options":["ship it"]}""", all)
        notifier.withdraw(tag)
        assertEquals(0, shadowOf(nm).activeNotifications.size)
        notifier.showOutcome(tag, "chief is asking", "/attention/open/ask-41", AskOutcome.Gone("Already answered, dismissed or expired."))
        assertEquals(0, shadowOf(nm).activeNotifications.size)
    }

    /** NotificationCompat.setSilent: a non-summary that only the (absent) summary may alert for. */
    private fun assertSilent(n: Notification) {
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertEquals(0, n.flags and Notification.FLAG_GROUP_SUMMARY)
        assertEquals(null, n.sound)
    }
}
