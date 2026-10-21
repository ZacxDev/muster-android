package dev.zacx.muster.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryDeviceStore(initial: DeviceRecord = DeviceRecord()) : DeviceStore {
    private val state = MutableStateFlow(initial)
    override val records: Flow<DeviceRecord> = state
    val value: DeviceRecord get() = state.value

    override suspend fun update(f: (DeviceRecord) -> DeviceRecord): DeviceRecord {
        state.value = f(state.value)
        return state.value
    }
}

/** A Keystore stand-in that records what pairing asked of it, per alias. */
class FakeStepUpKeys(
    var caps: StepUpPolicy.Capabilities,
    var level: SecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
    var failGenerate: Boolean = false,
) : StepUpKeys {
    val generated = mutableListOf<KeyKind>()
    val deleted = mutableListOf<String>()
    /** alias -> kind of every key that exists now. */
    val present = mutableMapOf<String, KeyKind>()

    override fun capabilities() = caps

    override fun generate(alias: String, kind: KeyKind): StepUpKeys.GeneratedKey {
        generated += kind
        if (failGenerate) throw IllegalStateException("keystore said no")
        present[alias] = kind
        return StepUpKeys.GeneratedKey("SPKI-${kind.wire}", level)
    }

    override fun delete(alias: String) {
        deleted += alias
        present.remove(alias)
    }

    override fun state(alias: String) = if (alias in present) StepUpKeys.KeyState.VALID else StepUpKeys.KeyState.ABSENT
}

/** Capabilities shorthand: a strong biometric enrolled, and a screen lock. */
fun caps(bio: Boolean, secure: Boolean) = StepUpPolicy.Capabilities(
    if (bio) StepUpPolicy.Biometric.Available else StepUpPolicy.Biometric.Absent, secure,
)
