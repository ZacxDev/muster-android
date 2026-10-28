package dev.zacx.muster.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * An attention ask's signed actions, as payload v2 carries them (muster
 * docs/design/android.md, "The signed ask actions, as built"):
 *
 *     "ask": {"id": 41, "options": ["ship it", "wait"], "suggestions": ["…"]},
 *     "actions": [
 *       {"id": "ask_answer", "target": "41", "option": 0},
 *       {"id": "ask_answer", "target": "41", "suggestion": 0},
 *       {"id": "ask_answer", "label": "Answer", "target": "41", "input": true},
 *       {"id": "ask_dismiss", "label": "Dismiss", "target": "41"}]
 *
 * An `ask_answer` names its chip BY INDEX into `ask.options` or
 * `ask.suggestions`; that chip's text is both the button's label and the exact
 * answer it sends. [actions] holds only what resolved: an action whose chip is
 * absent (muster dropped it to fit, or left it out as secret-shaped), cut, out
 * of range, or that names another ask, is dropped, never guessed at.
 */
data class AskPush(
    /** The ask's id: the route's `{id}`. */
    val id: Long,
    val actions: List<AskAction>,
) {
    /** The ask's id as muster's routes and challenges name it: decimal, no leading zeros. */
    val target: String get() = id.toString()

    /**
     * The buttons the notification draws: Android shows at most
     * [MAX_BUTTONS]. Dismiss always keeps its place (when muster offered it),
     * and the chips fill the rest in muster's order: every option, then every
     * suggestion. The inline-reply Answer is not drawn: the held-reply flow it
     * needs is not built yet, and a tap on the notification opens the ask's
     * page, where the answer can be typed.
     */
    fun buttons(): List<AskAction> {
        val dismiss = actions.filter { it.kind == AskAction.Kind.DISMISS }.take(1)
        val chips = actions.filter { it.kind == AskAction.Kind.OPTION || it.kind == AskAction.Kind.SUGGESTION }
        return chips.take(MAX_BUTTONS - dismiss.size) + dismiss
    }

    companion object {
        /** Only an `attention` push carries an ask. */
        const val TYPE = "attention"

        /** muster's MaxNativeChip: a longer chip is left out, never cut. */
        const val MAX_CHIP = 140

        /** muster's MaxNativeChips: at most this many options, and suggestions. */
        const val MAX_CHIPS = 3

        /** A fixed label ("Answer", "Dismiss") is cut to this many characters. */
        const val MAX_LABEL = 40

        /** Android's notification templates show at most three actions. */
        const val MAX_BUTTONS = 3
    }
}

/** One resolved action. [answer] is the exact text an answer sends; null for Dismiss and the inline reply. */
data class AskAction(
    val action: ActionId,
    val target: String,
    val label: String,
    val answer: String?,
    val kind: Kind,
) {
    enum class Kind { OPTION, SUGGESTION, INPUT, DISMISS }
}

/** What the notification shows after a tap. */
sealed interface AskOutcome {
    /** Sent: the notification goes (muster withdraws it on every device too). */
    data object Done : AskOutcome

    /** The ask is not there to act on any more: the buttons go, [message] says why. */
    data class Gone(val message: String) : AskOutcome

    /** Not sent: the buttons stay so the user can try again; [message] says why. */
    data class Failed(val message: String) : AskOutcome
}

/**
 * Parses `ask` and `actions`. Anything malformed leaves the push without
 * buttons (null, or an action dropped) rather than refusing it: the question
 * itself is still shown, and the tap still opens its page.
 */
internal object AskPushParser {
    private val decimal = Regex("^[1-9][0-9]{0,18}$")

    fun parse(askElement: JsonElement?, actionsElement: JsonElement?): AskPush? {
        val ask = askElement as? JsonObject ?: return null
        val id = (ask["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        if (id <= 0) return null
        val target = id.toString()
        val options = chips(ask["options"])
        val suggestions = chips(ask["suggestions"])

        val out = mutableListOf<AskAction>()
        for (e in actionsElement as? JsonArray ?: JsonArray(emptyList())) {
            val a = action(e as? JsonObject ?: continue, target, options, suggestions) ?: continue
            val duplicate = out.any {
                it.kind == a.kind && (a.kind == AskAction.Kind.DISMISS || a.kind == AskAction.Kind.INPUT || it.answer == a.answer)
            }
            if (!duplicate) out += a
        }
        return AskPush(id, out)
    }

    private fun action(o: JsonObject, target: String, options: List<String?>, suggestions: List<String?>): AskAction? {
        val wire = str(o, "id") ?: return null
        val t = str(o, "target") ?: return null
        // A button acts on the ask this notification shows, and no other.
        if (!decimal.matches(t) || t != target) return null
        val option = index(o, "option")
        val suggestion = index(o, "suggestion")
        if (option == INVALID || suggestion == INVALID) return null
        val input = (o["input"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        val label = str(o, "label")?.trim()?.takeIf { it.isNotEmpty() }?.let { PayloadV2Parser.cut(it, AskPush.MAX_LABEL) }

        return when (ActionId.fromWire(wire)) {
            ActionId.ASK_ANSWER -> when {
                input && option == null && suggestion == null ->
                    AskAction(ActionId.ASK_ANSWER, t, label ?: "Answer", null, AskAction.Kind.INPUT)
                input -> null
                option != null && suggestion == null ->
                    options.getOrNull(option)?.let { AskAction(ActionId.ASK_ANSWER, t, it, it, AskAction.Kind.OPTION) }
                suggestion != null && option == null ->
                    suggestions.getOrNull(suggestion)?.let { AskAction(ActionId.ASK_ANSWER, t, it, it, AskAction.Kind.SUGGESTION) }
                else -> null
            }
            ActionId.ASK_DISMISS ->
                if (option == null && suggestion == null && !input) {
                    AskAction(ActionId.ASK_DISMISS, t, label ?: "Dismiss", null, AskAction.Kind.DISMISS)
                } else {
                    null
                }
            // Only the ask's two ids are offered on an ask; any other is dropped.
            else -> null
        }
    }

    /**
     * The chips by wire index: a chip that is not a non-blank string of at
     * most [AskPush.MAX_CHIP] characters is null at its index (its action is
     * dropped, the others keep their indexes). Past [AskPush.MAX_CHIPS] none.
     */
    private fun chips(e: JsonElement?): List<String?> {
        val arr = e as? JsonArray ?: return emptyList()
        return arr.take(AskPush.MAX_CHIPS).map { c ->
            (c as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= AskPush.MAX_CHIP }
        }
    }

    private const val INVALID = -1

    /** null when absent; [INVALID] when present but not a non-negative JSON integer. */
    private fun index(o: JsonObject, key: String): Int? {
        val e = o[key] ?: return null
        val p = e as? JsonPrimitive ?: return INVALID
        if (p.isString) return INVALID
        val i = p.intOrNull ?: return INVALID
        return if (i >= 0) i else INVALID
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
