package dev.zacx.muster.feature.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.zacx.muster.core.model.PayloadV2Parser
import dev.zacx.muster.core.model.Push
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Raw payload v2 bytes → the parser → the real notifier, as a push arrives:
 * a question's whole text (BigTextStyle, public on the lock screen), a
 * waiting session's summary as sent, and the alert-once decision for muster's
 * `update` re-push.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class QuestionTextAndAlertTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: AndroidNotifier

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifier = AndroidNotifier(app) { tag, _ ->
            PendingIntent.getActivity(app, tag.hashCode(), Intent("test.OPEN"), PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private fun push(json: String) {
        val p = PayloadV2Parser.parse(json.encodeToByteArray())
        assertTrue("got $p", p is Push.Show)
        notifier.show((p as Push.Show).payload)
    }

    private fun shown(tag: String): Notification = shadowOf(nm).activeNotifications.single { it.tag == tag }.notification

    private val question = "Ship the migration tonight? The backup finished an hour ago and the staging run was green.\nOptions: ship it · wait for Monday"

    private fun ask(update: Boolean) =
        """{"v":2,"type":"attention","id":"ask-41","tag":"attention-ask-41","title":"chief is asking",
            "body":"${question.replace("\n", "\\n")}","url":"/attention/open/ask-41",
            "ask":{"id":41,"options":["ship it","wait for Monday"]}${if (update) ""","update":true""" else ""}}"""

    private fun onlyAlertOnce(n: Notification) = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    /** The first push buzzes; muster's `update` re-push of the same tag (the suggestions) does not. */
    @Test
    fun theSuggestionsRePushDoesNotAlertAgain() {
        push(ask(update = false))
        assertEquals(false, onlyAlertOnce(shown("attention-ask-41")))
        push(ask(update = true))
        assertEquals(true, onlyAlertOnce(shown("attention-ask-41")))
        // A later first push (a new question) alerts again.
        push(ask(update = false).replace("ask-41", "ask-42").replace("\"id\":41", "\"id\":42"))
        assertEquals(false, onlyAlertOnce(shown("attention-ask-42")))
    }

    /**
     * The user swiped the question away; the suggestions re-push (`update`)
     * then arrives. It is dropped: nothing posted, so nothing alerts and the
     * dismissed question does not come back.
     */
    @Test
    fun anUpdateRePushAfterTheUserSwipedItAwayPostsNothing() {
        push(ask(update = false))
        assertEquals(1, shadowOf(nm).activeNotifications.size)
        nm.cancel("attention-ask-41", AndroidNotifier.ID) // the user's swipe
        push(ask(update = true))
        assertEquals(0, shadowOf(nm).activeNotifications.size)
    }

    /** Control: the same re-push while the first is still showing updates it in place, without alerting. */
    @Test
    fun anUpdateRePushWhileItIsShowingUpdatesItInPlace() {
        push(ask(update = false))
        push(ask(update = true).replace("Options: ship it", "Options: ship it now"))
        val all = shadowOf(nm).activeNotifications
        assertEquals(1, all.size)
        val n = all.single().notification
        assertEquals(question.replace("Options: ship it", "Options: ship it now"), n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertEquals(true, onlyAlertOnce(n))
    }

    /** The question and its options, whole: BigTextStyle, and public on the lock screen. */
    @Test
    fun aQuestionShowsItsWholeTextOnTheLockScreen() {
        push(ask(update = false))
        val n = shown("attention-ask-41")
        assertEquals(question, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertEquals(Notification.BigTextStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
    }

    /**
     * A held question (a session's AskUserQuestion) has no `ask`: it stays
     * private, but its public version carries the same whole text, so the
     * lock screen shows the question in either privacy setting.
     */
    @Test
    fun aHeldQuestionsPublicVersionCarriesTheWholeText() {
        val held = "1. Which branch?\n2. Push it?\nOptions: main · dev"
        push("""{"v":2,"type":"attention","id":"session-0123abcd","tag":"attention-session-0123abcd","title":"main:1 @ ws is asking","body":"${held.replace("\n", "\\n")}"}""")
        val n = shown("attention-session-0123abcd")
        assertEquals(held, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        val pub = assertNotNull(n.publicVersion).let { n.publicVersion }
        assertEquals(held, pub.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    /** A waiting session is muster's summary line and nothing more: private, as sent, no buttons. */
    @Test
    fun aWaitingSessionStaysSummaryOnly() {
        push("""{"v":2,"type":"attention","id":"session-0123abcd","tag":"attention-session-0123abcd","title":"A session is waiting on you","body":"Waiting: Bash · main:1 @ ws"}""")
        val n = shown("attention-session-0123abcd")
        assertEquals("Waiting: Bash · main:1 @ ws", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(0, n.actions?.size ?: 0)
        assertEquals(false, onlyAlertOnce(n))
    }
}
