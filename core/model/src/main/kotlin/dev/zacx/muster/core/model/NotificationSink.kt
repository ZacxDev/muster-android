package dev.zacx.muster.core.model

/** Where decoded pushes go: feature/notify draws them; tests record them. */
interface NotificationSink {
    fun show(payload: PayloadV2)

    fun withdraw(tag: String)
}
