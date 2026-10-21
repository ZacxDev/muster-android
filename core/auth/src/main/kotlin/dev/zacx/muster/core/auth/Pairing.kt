package dev.zacx.muster.core.auth

import dev.zacx.muster.core.net.CredentialShape
import dev.zacx.muster.core.net.DeviceApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Redeems a pairing code for the device credential (docs/design/android.md,
 * "The device credential" and "The step-up key"; muster docs/auth.md,
 * "Paired devices").
 *
 * The code arrives from muster's Account → Devices page over the WebView
 * bridge. This class:
 *
 * 1. refuses a string that is not shaped like a pairing code, before any
 *    network call;
 * 2. unpairs the phone's previous pairing, if any (one pairing per phone, and
 *    there is one step-up key alias to replace);
 * 3. picks the step-up key by [StepUpPolicy], generates it, and drops it if
 *    the Keystore reports it as software-only;
 * 4. redeems the code with the key's public half, type and level;
 * 5. if muster refuses the KEY (403, which does not spend the code), deletes
 *    it and redeems the same code read-only, saying why;
 * 6. stores the credential in the sealed [DeviceStore], and never anywhere
 *    else.
 *
 * Every non-biometric outcome carries a [Outcome.Paired.note] the UI shows:
 * the phone never becomes read-only, or PIN-gated, without saying so.
 */
class Pairing(
    private val api: DeviceApi,
    private val keys: StepUpKeys,
    private val store: DeviceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Outcome {
        data class Paired(
            val deviceId: Long,
            /** The key muster recorded, or null: read-only. */
            val keyKind: KeyKind?,
            /** Shown to the user when the phone is read-only or PIN-gated. */
            val note: String?,
        ) : Outcome

        data class Failed(val message: String) : Outcome
    }

    suspend fun pair(code: String, deviceName: String, appVersion: String): Outcome {
        if (!CredentialShape.codeOk(code)) return Outcome.Failed("That is not a pairing code.")

        val before = store.read()
        before.token?.let { old ->
            // Best effort: if muster cannot be reached the old row stays on
            // Account → Devices until removed there; the phone forgets it now.
            runCatching { withContext(io) { api.unpair(old) } }
            store.update { it.unpaired() }
            // Its step-up key goes with it, even if this pairing ends read-only.
            deleteKey()
        }

        var note: String? = null
        var key: StepUpKeys.GeneratedKey? = null
        var kind: KeyKind? = null
        when (val choice = StepUpPolicy.choose(keys.capabilities())) {
            is StepUpPolicy.Choice.ReadOnly -> note = choice.reason
            is StepUpPolicy.Choice.Generate -> {
                val made = runCatching { withContext(io) { keys.generate(choice.kind) } }.getOrNull()
                when {
                    made == null -> { deleteKey(); note = StepUpPolicy.KEY_FAILED }
                    !StepUpPolicy.acceptsLevel(made.level) -> { deleteKey(); note = StepUpPolicy.SOFTWARE_KEY }
                    else -> { key = made; kind = choice.kind }
                }
            }
        }

        var result = withContext(io) { api.pair(request(code, deviceName, appVersion, key, kind)) }
        if (result is DeviceApi.PairResult.KeyRefused && key != null) {
            deleteKey()
            note = if (kind == KeyKind.BIOMETRIC_OR_CREDENTIAL) {
                "This phone has no strong biometric, and this muster server does not accept PIN keys. It is paired read-only."
            } else {
                "muster refused this phone's step-up key (${result.message}). It is paired read-only."
            }
            key = null
            kind = null
            result = withContext(io) { api.pair(request(code, deviceName, appVersion, null, null)) }
        }

        return when (result) {
            is DeviceApi.PairResult.Paired -> {
                // Trust muster's record of what it stored, not what was sent.
                val recorded = if (result.device.stepUpKey) KeyKind.fromWire(result.device.stepUpKeyType) else null
                if (recorded == null && key != null) {
                    deleteKey()
                    note = note ?: "muster did not record this phone's step-up key. It is paired read-only."
                }
                if (recorded == KeyKind.BIOMETRIC_OR_CREDENTIAL && note == null) note = StepUpPolicy.PIN_KEY
                store.update {
                    it.unpaired().copy(
                        token = result.token,
                        deviceId = result.device.id,
                        keyKind = recorded?.wire,
                        keyLevel = if (recorded != null) result.device.stepUpKeyLevel else null,
                        keyNote = note,
                    )
                }
                Outcome.Paired(result.device.id, recorded, note)
            }
            else -> {
                if (key != null) deleteKey()
                Outcome.Failed(failureMessage(result))
            }
        }
    }

    private fun request(code: String, name: String, version: String, key: StepUpKeys.GeneratedKey?, kind: KeyKind?) =
        DeviceApi.PairRequest(
            code = code,
            name = name.take(100),
            appVersion = version.take(40),
            stepUpPubkey = key?.spkiBase64,
            stepUpKeyType = key?.let { kind?.wire },
            stepUpKeyLevel = key?.level?.wire,
        )

    private fun deleteKey() {
        runCatching { keys.delete() }
    }

    private fun failureMessage(r: DeviceApi.PairResult): String = when (r) {
        is DeviceApi.PairResult.BadCode -> "The pairing code was refused: it is used, expired, or from a session that ended. Tap Pair this phone again."
        is DeviceApi.PairResult.TooMany -> r.message.ifEmpty { "This account has the most phones it may pair. Remove one on Account → Devices." }
        is DeviceApi.PairResult.Invalid -> "muster refused the pairing request: ${r.message}"
        is DeviceApi.PairResult.KeyRefused -> "muster refused the pairing: ${r.message}"
        DeviceApi.PairResult.Throttled -> "Too many pairing attempts. Wait a minute and try again."
        is DeviceApi.PairResult.Failed -> r.message
        is DeviceApi.PairResult.Paired -> error("not a failure")
    }
}
