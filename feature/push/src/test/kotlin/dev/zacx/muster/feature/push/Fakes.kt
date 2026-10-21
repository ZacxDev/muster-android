package dev.zacx.muster.feature.push

import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.DeviceStore
import dev.zacx.muster.core.model.NotificationSink
import dev.zacx.muster.core.model.PayloadV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryDeviceStore(initial: DeviceRecord = DeviceRecord()) : DeviceStore {
    private val state = MutableStateFlow(initial)
    override val records: Flow<DeviceRecord> = state
    val value: DeviceRecord get() = state.value
    var writes = 0

    override suspend fun update(f: (DeviceRecord) -> DeviceRecord): DeviceRecord {
        writes++
        state.value = f(state.value)
        return state.value
    }
}

class RecordingSink : NotificationSink {
    val shown = mutableListOf<PayloadV2>()
    val withdrawn = mutableListOf<String>()

    override fun show(payload: PayloadV2) {
        shown += payload
    }

    override fun withdraw(tag: String) {
        withdrawn += tag
    }
}
