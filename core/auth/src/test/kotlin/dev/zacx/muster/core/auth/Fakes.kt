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

/** A Keystore stand-in that records what pairing asked of it. */
class FakeStepUpKeys(
    var caps: StepUpPolicy.Capabilities,
    var level: SecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
    var failGenerate: Boolean = false,
) : StepUpKeys {
    val generated = mutableListOf<KeyKind>()
    var deletes = 0
    var present: KeyKind? = null

    override fun capabilities() = caps

    override fun generate(kind: KeyKind): StepUpKeys.GeneratedKey {
        generated += kind
        if (failGenerate) throw IllegalStateException("keystore said no")
        present = kind
        return StepUpKeys.GeneratedKey("SPKI-${kind.wire}", level)
    }

    override fun delete() {
        deletes++
        present = null
    }

    override fun state() = if (present == null) StepUpKeys.KeyState.ABSENT else StepUpKeys.KeyState.VALID
}
