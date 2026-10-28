package dev.zacx.muster.core.net

import dev.zacx.muster.core.model.ActionId
import java.security.MessageDigest

/**
 * The exact message a signed device action signs (muster internal/devices
 * Message; docs/design/android.md, "The signed ask actions, as built", step 3):
 *
 *     muster-device-action-v1
 *     <nonce>
 *     <action wire id>
 *     <target>
 *     <lowercase hex sha256 of the text's UTF-8 bytes, or empty for none>
 *
 * joined by "\n", no trailing newline, UTF-8. A Dismiss has no text, so its
 * message ends in "\n" (the empty last line).
 *
 * The app BUILDS this itself and refuses a challenge whose returned `message`
 * differs: the step-up key signs only what the app put in front of the user.
 */
object DeviceActionMessage {
    const val VERSION = "muster-device-action-v1"

    /** The lowercase hex SHA-256 of [text]'s UTF-8 bytes, exactly as sent (no trimming). */
    fun textSha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun build(nonce: String, action: ActionId, target: String, textSha256: String?): String =
        listOf(VERSION, nonce, action.wire, target, textSha256.orEmpty()).joinToString("\n")

    /** The bytes the step-up key signs. */
    fun bytes(nonce: String, action: ActionId, target: String, textSha256: String?): ByteArray =
        build(nonce, action, target, textSha256).toByteArray(Charsets.UTF_8)
}
