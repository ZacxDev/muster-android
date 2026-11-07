package dev.zacx.muster.feature.push

import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import dev.zacx.muster.core.model.NotificationSink

/**
 * Which muster server a push belongs to (muster docs/design/multi-tenant.md,
 * "Push"). Each tenant registers its own UnifiedPush instance (its id;
 * `"default"` for the migrated tenant, so its pre-upgrade registration keeps
 * delivering), with its own endpoint and RFC 8291 keys. A push is handed to
 * the sink of the tenant whose instance it arrived on, and to no other:
 *
 * 1. an undecrypted message is dropped FIRST, before the instance is even
 *    looked at ([PushHandler]'s load-bearing rule);
 * 2. an instance no tenant owns (a removed tenant's late delivery) is dropped;
 * 3. the push is drawn by THAT tenant's sink ([sinkFor]), which labels it,
 *    namespaces its tag and makes its tap open that tenant.
 *
 * A message that decrypted under instance B's keys was encrypted by the
 * holder of B's subscription keys, which only this phone and tenant B's server
 * have, so the attribution is cryptographic, not a convention.
 */
class TenantPushRouter(private val sinkFor: (TenantRecord) -> NotificationSink) {
    fun handle(instance: String, content: ByteArray, decrypted: Boolean, tenants: Tenants): PushHandler.Outcome {
        if (!decrypted) return PushHandler.Outcome.DROPPED_UNDECRYPTED
        val t = tenants.forInstance(instance) ?: return PushHandler.Outcome.DROPPED_UNKNOWN_TENANT
        return PushHandler(sinkFor(t)).handle(content, decrypted = true, paired = t.device.paired, canSign = t.device.keyAlias != null)
    }
}
