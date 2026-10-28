package dev.zacx.muster.core.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload v2's `ask`, `actions` and `update` (muster docs/design/android.md,
 * "The signed ask actions, as built"). Every expectation is a literal: the
 * chip texts, the order, and which buttons survive.
 */
class AskPushParserTest {
    private fun show(json: String): PayloadV2 {
        val p = PayloadV2Parser.parse(json.encodeToByteArray())
        assertTrue("got $p", p is Push.Show)
        return (p as Push.Show).payload
    }

    private fun ask(askJson: String, actionsJson: String, type: String = "attention"): AskPush? =
        show("""{"v":2,"type":"$type","id":"ask-41","tag":"attention-ask-41","title":"chief is asking","body":"Ship it?","url":"/attention/open/ask-41","ask":$askJson,"actions":$actionsJson}""").ask

    private fun labels(a: AskPush?) = a!!.buttons().map { it.label }

    /** muster's own example, as the design doc prints it. */
    private val example = """{"v": 2, "type": "attention", "id": "ask-41", "tag": "attention-ask-41",
 "title": "chief is asking",
 "body": "Ship the migration tonight?\nOptions: ship it · wait for Monday",
 "url": "/attention/open/ask-41",
 "ask": {"id": 41, "options": ["ship it", "wait for Monday"],
         "suggestions": ["Wait for Monday, until the backup is verified"]},
 "actions": [
   {"id": "ask_answer", "target": "41", "option": 0},
   {"id": "ask_answer", "target": "41", "option": 1},
   {"id": "ask_answer", "target": "41", "suggestion": 0},
   {"id": "ask_answer", "label": "Answer", "target": "41", "input": true},
   {"id": "ask_dismiss", "label": "Dismiss", "target": "41"}],
 "update": true}"""

    @Test
    fun mustersExampleResolvesEveryChipByIndex() {
        val p = show(example)
        assertEquals("Ship the migration tonight?\nOptions: ship it · wait for Monday", p.body)
        assertTrue(p.update)
        val a = p.ask!!
        assertEquals(41L, a.id)
        assertEquals(
            listOf(
                AskAction(ActionId.ASK_ANSWER, "41", "ship it", "ship it", AskAction.Kind.OPTION),
                AskAction(ActionId.ASK_ANSWER, "41", "wait for Monday", "wait for Monday", AskAction.Kind.OPTION),
                AskAction(
                    ActionId.ASK_ANSWER, "41", "Wait for Monday, until the backup is verified",
                    "Wait for Monday, until the backup is verified", AskAction.Kind.SUGGESTION,
                ),
                AskAction(ActionId.ASK_ANSWER, "41", "Answer", null, AskAction.Kind.INPUT),
                AskAction(ActionId.ASK_DISMISS, "41", "Dismiss", null, AskAction.Kind.DISMISS),
            ),
            a.actions,
        )
        // Three buttons at most: the first two chips, and Dismiss keeps its place.
        assertEquals(listOf("ship it", "wait for Monday", "Dismiss"), labels(a))
    }

    @Test
    fun suggestionsBecomeButtonsWhenTheyFit() {
        val a = ask(
            """{"id":41,"suggestions":["Yes, after the backup","No"]}""",
            """[{"id":"ask_answer","target":"41","suggestion":1},{"id":"ask_answer","target":"41","suggestion":0},
               {"id":"ask_dismiss","label":"Dismiss","target":"41"}]""",
        )
        // muster's order, not the chip list's.
        assertEquals(listOf("No", "Yes, after the backup", "Dismiss"), labels(a))
        assertEquals(listOf("No", "Yes, after the backup", null), a!!.buttons().map { it.answer })
    }

    /** The first push carries no `update`; only JSON `true` is an update. */
    @Test
    fun updateIsOnlyJsonTrue() {
        val base = """{"v":2,"type":"attention","tag":"attention-ask-41","title":"chief is asking""""
        assertFalse(show("$base}").update)
        assertTrue(show("""$base,"update":true}""").update)
        assertFalse(show("""$base,"update":false}""").update)
        assertFalse(show("""$base,"update":"true"}""").update)
        assertFalse(show("""$base,"update":1}""").update)
    }

    /**
     * muster leaves a secret-shaped chip out of `ask` (never sent redacted)
     * and builds `actions` from the chips it kept. Whatever the phone gets,
     * a button exists only for a chip that is in the push: the secret's text
     * reaches no button, and an action naming an index past the chips (a
     * chip that is gone) is dropped while the others keep their indexes.
     */
    @Test
    fun aChipThatIsAbsentHasNoButton() {
        val secret = "password=not-a-real-secret-0123456789"
        val a = ask(
            """{"id":41,"options":["rotate it"]}""",
            """[{"id":"ask_answer","target":"41","option":1},{"id":"ask_answer","target":"41","option":0},
               {"id":"ask_answer","target":"41","suggestion":0},{"id":"ask_dismiss","label":"Dismiss","target":"41"}]""",
        )!!
        assertEquals(listOf("rotate it", "Dismiss"), labels(a))
        assertTrue(a.actions.none { secret == it.label || secret == it.answer })
    }

    /** A chip is whole or absent: muster's MaxNativeChip is 140 characters (code points, not chars). */
    @Test
    fun anOversizedBlankOrNonStringChipLosesOnlyItsOwnButton() {
        val at = "🤖".repeat(140) // 140 code points, 280 chars
        val over = "a".repeat(141)
        val a = ask(
            """{"id":41,"options":["$over","   ",7,"$at"]}""",
            """[{"id":"ask_answer","target":"41","option":0},{"id":"ask_answer","target":"41","option":1},
               {"id":"ask_answer","target":"41","option":2},{"id":"ask_answer","target":"41","option":3}]""",
        )!!
        // Index 3 is past muster's three chips, so even a good chip there is dropped.
        assertEquals(emptyList<String>(), a.actions.map { it.label })

        val b = ask("""{"id":41,"options":["$over","$at"]}""", """[{"id":"ask_answer","target":"41","option":1}]""")!!
        assertEquals(listOf(at), b.actions.map { it.answer })
    }

    @Test
    fun anActionThatDoesNotResolveIsDropped() {
        fun one(action: String) = ask("""{"id":41,"options":["ship it"],"suggestions":["later"]}""", "[$action]")!!.actions
        // Another ask's id, a zero-padded one, a number instead of a string.
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"42","option":0}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"041","option":0}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":41,"option":0}"""))
        // An index that is a string, negative, fractional or null; both kinds at once.
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41","option":"0"}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41","option":-1}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41","option":0.5}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41","option":null}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41","option":0,"suggestion":0}"""))
        // An answer naming no chip and not the inline reply.
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41"}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_answer","target":"41","option":0,"input":true}"""))
        // A dismiss carrying a chip; an id that is not the ask's; a bare verb.
        assertEquals(emptyList<AskAction>(), one("""{"id":"ask_dismiss","target":"41","option":0}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"queue_send_now","target":"41"}"""))
        assertEquals(emptyList<AskAction>(), one("""{"id":"cancel","target":"41"}"""))
        assertEquals(emptyList<AskAction>(), one(""""ask_dismiss""""))
        // The control: the same chip, well formed, resolves.
        assertEquals(listOf("later"), one("""{"id":"ask_answer","target":"41","suggestion":0}""").map { it.answer })
    }

    @Test
    fun aMalformedAskShowsTheQuestionWithoutButtons() {
        val actions = """[{"id":"ask_dismiss","label":"Dismiss","target":"41"}]"""
        assertNull(ask(""""41"""", actions))
        assertNull(ask("""{"id":"41"}""", actions))
        assertNull(ask("""{"id":0}""", actions))
        assertNull(ask("""{"id":-41}""", actions))
        assertNull(ask("""{"options":["x"]}""", actions))
        assertNull(ask("""[41]""", actions))
        // Only an attention push carries an ask.
        assertNull(ask("""{"id":41}""", actions, type = "review"))
        // Options of the wrong shape: no chips, Dismiss still resolves.
        assertEquals(listOf("Dismiss"), labels(ask("""{"id":41,"options":"ship it"}""", """[{"id":"ask_answer","target":"41","option":0},${actions.removePrefix("[")}""")))
        // actions not an array: none.
        assertEquals(emptyList<AskAction>(), ask("""{"id":41}""", """{"id":"ask_dismiss"}""")!!.actions)
    }

    @Test
    fun theInlineReplyIsParsedButNotDrawn() {
        val a = ask("""{"id":41}""", """[{"id":"ask_answer","label":"Answer","target":"41","input":true},{"id":"ask_dismiss","label":"Dismiss","target":"41"}]""")!!
        assertEquals(listOf(AskAction.Kind.INPUT, AskAction.Kind.DISMISS), a.actions.map { it.kind })
        assertEquals(listOf("Dismiss"), labels(a))
    }

    @Test
    fun labelsDefaultAndAreCutDuplicatesCollapse() {
        val long = "D".repeat(60)
        val a = ask(
            """{"id":41,"options":["ship it","ship it"]}""",
            """[{"id":"ask_answer","target":"41","option":0},{"id":"ask_answer","target":"41","option":1},
               {"id":"ask_dismiss","target":"41","label":${JsonPrimitive(long)}},{"id":"ask_dismiss","target":"41"}]""",
        )!!
        assertEquals(listOf("ship it", "D".repeat(39) + "…"), a.actions.map { it.label })
        assertEquals("Dismiss", ask("""{"id":41}""", """[{"id":"ask_dismiss","target":"41"}]""")!!.actions.single().label)
    }

    /** The cap fixture (an attention push with no ask) still parses, with no buttons and no update. */
    @Test
    fun aPushWithoutAnAskHasNoButtons() {
        val p = show("""{"v":2,"type":"attention","tag":"attention-session-0123abcd","title":"A session is waiting on you","body":"Waiting: Bash · main:1 @ ws"}""")
        assertNull(p.ask)
        assertFalse(p.update)
        assertEquals("Waiting: Bash · main:1 @ ws", p.body)
    }
}
