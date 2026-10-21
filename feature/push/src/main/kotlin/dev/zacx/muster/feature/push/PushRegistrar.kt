package dev.zacx.muster.feature.push

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.DeviceStore
import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.core.net.DeviceApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * The push-host check (docs/design/android.md, "The push host is checked at
 * pairing"). The UnifiedPush endpoint's host is whatever server the ntfy app
 * is set to, and with none set ntfy uses its public server (Phase 0, S10).
 * muster would refuse that endpoint, and the user would only see "push
 * registration failed", so the app compares hosts first and says what to fix.
 *
 * The rule mirrors muster's allowlist for operator hosts: `https`, the
 * default port, the host named EXACTLY (no suffix match).
 */
object PushHostCheck {
    sealed interface Verdict {
        data object Ok : Verdict

        data class Mismatch(val message: String) : Verdict
    }

    fun check(endpoint: String, operatorHosts: List<String>): Verdict {
        val hosts = operatorHosts.map(::norm).filter { it.isNotEmpty() }
        if (hosts.isEmpty()) {
            return Verdict.Mismatch("This muster server names no push host (MUSTER_PUSH_EXTRA_HOSTS), so it cannot send to this phone.")
        }
        val u = runCatching { URI(endpoint) }.getOrNull()
        val host = u?.host?.let(::norm)
        val defaultPort = u != null && (u.port == -1 || u.port == 443)
        val ok = u != null && u.scheme.equals("https", ignoreCase = true) && u.rawUserInfo == null &&
            defaultPort && host != null && host in hosts
        if (ok) return Verdict.Ok
        val want = hosts.joinToString(" or ")
        val got = host ?: "an unreadable address"
        return Verdict.Mismatch(
            "The ntfy app is using $got. Set ntfy's default server to https://$want " +
                "(ntfy → Settings → Default server), then reopen muster.",
        )
    }

    private fun norm(h: String) = h.trim().lowercase().trimEnd('.')
}

/**
 * Keeps the phone's push registration in step with muster:
 *
 * - [refreshConfig] reads `/api/device/config`, records the operator push
 *   hosts and hands back the VAPID key to register with; a 401 there means
 *   muster ended this pairing, and the phone forgets it.
 * - [onNewEndpoint] runs the push-host check and then `PUT /api/device/push`,
 *   which muster accepts only after its own test push to the endpoint got a
 *   2xx.
 * - [onUnregistered] tells muster to drop the registration.
 *
 * The Android side (UnifiedPush.register, the PushService) is a thin layer in
 * the app module; everything that decides is here.
 */
class PushRegistrar(
    private val store: DeviceStore,
    private val api: (origin: String) -> DeviceApi,
    /** Runs when muster has ended the pairing: deletes the local step-up key. */
    private val onForget: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface ConfigResult {
        /** Register with the distributor, using this VAPID public key. */
        data class Register(val vapidKey: String) : ConfigResult

        data object NotPaired : ConfigResult

        data object NoPush : ConfigResult

        data object Revoked : ConfigResult

        data class Failed(val message: String) : ConfigResult
    }

    suspend fun refreshConfig(): ConfigResult {
        val r = store.read()
        val (origin, token) = r.origin to r.token
        if (origin == null || token == null) return ConfigResult.NotPaired
        return when (val c = withContext(io) { api(origin).config(token) }) {
            is DeviceApi.Result.Ok -> {
                val cfg = c.value
                val served = cfg.push.served && cfg.features.push && cfg.push.vapidPublicKey.isNotEmpty()
                store.update {
                    it.copy(
                        pushHosts = cfg.push.hosts,
                        pushStatus = if (served) it.pushStatus else PushStatus.SERVER_HAS_NO_PUSH,
                        pushNote = if (served) it.pushNote else "This muster server does not send push notifications.",
                    )
                }
                if (served) ConfigResult.Register(cfg.push.vapidPublicKey) else ConfigResult.NoPush
            }
            DeviceApi.Result.Revoked -> { forget(); ConfigResult.Revoked }
            is DeviceApi.Result.Failed -> ConfigResult.Failed(c.message)
        }
    }

    suspend fun noDistributor() {
        store.update {
            it.copy(
                pushStatus = PushStatus.NO_DISTRIBUTOR,
                pushNote = "Install the ntfy app and set its default server to this muster's push host, then reopen muster.",
            )
        }
    }

    /**
     * @param p256dh and [auth] are the subscription's public key set. Without
     *   one the phone could not decrypt anything, so the endpoint is refused.
     */
    suspend fun onNewEndpoint(endpoint: String, p256dh: String?, auth: String?): PushStatus {
        val r = store.read()
        val (origin, token) = r.origin to r.token
        if (origin == null || token == null) return PushStatus.NONE
        if (p256dh.isNullOrEmpty() || auth.isNullOrEmpty()) {
            return set(PushStatus.REFUSED, "The push distributor gave no encryption keys, so this phone could not read its notifications.")
        }
        when (val v = PushHostCheck.check(endpoint, r.pushHosts)) {
            is PushHostCheck.Verdict.Mismatch -> return set(PushStatus.HOST_MISMATCH, v.message)
            PushHostCheck.Verdict.Ok -> Unit
        }
        set(PushStatus.REGISTERING, null)
        return when (val res = withContext(io) { api(origin).putPush(token, DeviceApi.PushRegistration(endpoint, p256dh, auth)) }) {
            is DeviceApi.Result.Ok -> {
                store.update { it.copy(pushStatus = PushStatus.REGISTERED, pushNote = null) }
                PushStatus.REGISTERED
            }
            DeviceApi.Result.Revoked -> { forget(); PushStatus.NONE }
            is DeviceApi.Result.Failed -> set(PushStatus.REFUSED, "muster refused this phone's push registration: ${res.message}")
        }
    }

    suspend fun onUnregistered() {
        val r = store.read()
        val (origin, token) = r.origin to r.token
        if (origin != null && token != null) {
            runCatching { withContext(io) { api(origin).deletePush(token) } }
        }
        store.update { it.copy(pushStatus = PushStatus.UNREGISTERED, pushNote = "The push distributor dropped this phone's registration.") }
    }

    suspend fun onRegistrationFailed(reason: String) {
        set(PushStatus.REFUSED, "The push distributor refused to register muster ($reason).")
    }

    private suspend fun set(s: PushStatus, note: String?): PushStatus {
        store.update { it.copy(pushStatus = s, pushNote = note) }
        return s
    }

    private suspend fun forget() {
        store.update(DeviceRecord::unpaired)
        runCatching(onForget)
    }
}
