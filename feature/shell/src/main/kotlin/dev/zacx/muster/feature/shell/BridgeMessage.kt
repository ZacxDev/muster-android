package dev.zacx.muster.feature.shell

import dev.zacx.muster.core.net.CredentialShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The messages muster's pages send the app over the `musterApp` bridge
 * (muster internal/api/devices.go, appBridgeJS; docs/design/android.md,
 * "The shell"). Each is a JSON string. The bridge never carries a credential
 * from the app to the page.
 *
 * Phase 1 understands the two messages muster sends today:
 *
 *     {"type":"pair","code":"mdp_…","expires_at":"2026-10-21T12:00:00Z"}
 *     {"type":"open-notification-settings"}
 *
 * Everything else is refused. The design also names "request
 * POST_NOTIFICATIONS" and "theme"; muster sends neither yet, and the app asks
 * for POST_NOTIFICATIONS itself right after pairing.
 */
sealed interface BridgeMessage {
    /** [expiresAt] is re-checked when the user taps Pair, not only on arrival. */
    data class Pair(val code: String, val expiresAt: Instant?) : BridgeMessage

    data object OpenNotificationSettings : BridgeMessage

    data class Refused(val reason: String) : BridgeMessage

    companion object {
        /** Messages are tiny; anything larger is not one of muster's. */
        const val MAX_LEN = 1024

        private val json = Json { isLenient = false }

        /**
         * @param sourceOrigin the origin WebView reports for the sender.
         * @param isMainFrame only the top-level muster page may talk to the app.
         */
        fun parse(
            data: String?,
            sourceOrigin: String,
            isMainFrame: Boolean,
            policy: OriginPolicy,
            now: Instant,
        ): BridgeMessage {
            if (!isMainFrame) return Refused("not the main frame")
            // allowedOriginRules already restricts the listener; this re-checks
            // so a wrong rule cannot widen it.
            if (!policy.allowsInWebView(sourceOrigin)) return Refused("wrong origin")
            if (data == null || data.length > MAX_LEN) return Refused("no message")
            val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                ?: return Refused("not a JSON object")
            fun s(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (s("type")) {
                "pair" -> {
                    val code = s("code") ?: return Refused("pair without a code")
                    if (!CredentialShape.codeOk(code)) return Refused("not a pairing code")
                    val exp = s("expires_at")?.let {
                        try { Instant.parse(it) } catch (e: DateTimeParseException) { null }
                    }
                    if (exp != null && !exp.isAfter(now)) return Refused("the pairing code has expired")
                    Pair(code, exp)
                }
                "open-notification-settings" -> OpenNotificationSettings
                else -> Refused("unknown message")
            }
        }
    }
}
