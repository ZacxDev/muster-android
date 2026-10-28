package dev.zacx.muster.push

import android.content.Context
import android.util.Log
import dev.zacx.muster.MusterApp
import dev.zacx.muster.feature.push.PushHandler
import dev.zacx.muster.feature.shell.WebViewPrewarm
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The UnifiedPush receiver. A thin layer: [dev.zacx.muster.feature.push.PushHandler]
 * decides what a message does (and drops every undecrypted one first), and
 * [dev.zacx.muster.feature.push.PushRegistrar] owns registration.
 */
class MusterPushService : PushService() {
    private val app get() = MusterApp.from(this)

    override fun onMessage(message: PushMessage, instance: String) {
        // The record read is short and local; the outcome must be decided
        // before onMessage returns (the connector holds a wake lock until then).
        val record = runBlocking { app.store.read() }
        val outcome = app.pushHandler.handle(message.content, message.decrypted, record.paired, canSign = record.keyAlias != null)
        Log.i(TAG, "push: $outcome")
        // A notification the user may tap: start Chromium now, in this
        // already-running process, so the tap opens a warm shell.
        if (outcome == PushHandler.Outcome.SHOWN) WebViewPrewarm.start(applicationContext)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        app.scope.launch {
            val status = app.registrar.onNewEndpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
            Log.i(TAG, "push registration: $status")
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        app.scope.launch { app.registrar.onRegistrationFailed(reason.name) }
    }

    override fun onUnregistered(instance: String) {
        app.scope.launch { app.registrar.onUnregistered() }
    }

    companion object {
        private const val TAG = "muster"
        const val INSTANCE = "default"

        /** Registers with the user's distributor (the ntfy app), with muster's VAPID key. */
        fun register(context: Context, vapidKey: String) {
            UnifiedPush.register(context, INSTANCE, "muster", vapidKey)
        }

        fun unregister(context: Context) {
            UnifiedPush.unregister(context, INSTANCE)
        }
    }
}
