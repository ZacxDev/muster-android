package dev.zacx.muster.core.model

/**
 * Where decoded pushes go: feature/notify draws them; tests record them. One
 * sink per muster server: the app hands a push to the sink of the server it
 * arrived from.
 */
interface NotificationSink {
    fun show(payload: PayloadV2)

    /** muster withdrew the notification it sent under [serverTag] (the tag as muster sent it). */
    fun withdraw(serverTag: String)
}
