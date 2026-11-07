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
 * The UnifiedPush receiver, for every muster server at once. Each tenant
 * registers its own instance (its id; "default" for the migrated tenant, so
 * a registration made before the upgrade keeps delivering without the app
 * being opened). A thin layer: [dev.zacx.muster.feature.push.TenantPushRouter]
 * decides which tenant a message is for (and drops every undecrypted one
 * first), and each tenant's [dev.zacx.muster.feature.push.PushRegistrar]
 * owns its registration.
 */
class MusterPushService : PushService() {
    private val app get() = MusterApp.from(this)

    override fun onMessage(message: PushMessage, instance: String) {
        // The read is short and local; the outcome must be decided before
        // onMessage returns (the connector holds a wake lock until then).
        val tenants = runBlocking { app.tenants.read() }
        val outcome = app.pushRouter.handle(instance, message.content, message.decrypted, tenants)
        Log.i(TAG, "push: $outcome")
        // A notification the user may tap: start Chromium now, in this
        // already-running process, so the tap opens a warm shell.
        if (outcome == PushHandler.Outcome.SHOWN) WebViewPrewarm.start(applicationContext)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        app.scope.launch {
            val t = app.tenants.read().forInstance(instance) ?: return@launch
            val status = app.registrarFor(t.id).onNewEndpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
            Log.i(TAG, "push registration: $status")
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        app.scope.launch {
            val t = app.tenants.read().forInstance(instance) ?: return@launch
            app.registrarFor(t.id).onRegistrationFailed(reason.name)
        }
    }

    override fun onUnregistered(instance: String) {
        app.scope.launch {
            val t = app.tenants.read().forInstance(instance) ?: return@launch
            app.registrarFor(t.id).onUnregistered()
        }
    }

    companion object {
        private const val TAG = "muster"

        /** Registers [instance] (a tenant's id) with the user's distributor (the ntfy app), with that server's VAPID key. */
        fun register(context: Context, instance: String, vapidKey: String) {
            UnifiedPush.register(context, instance, "muster", vapidKey)
        }

        fun unregister(context: Context, instance: String) {
            UnifiedPush.unregister(context, instance)
        }
    }
}
