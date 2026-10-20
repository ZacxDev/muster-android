package dev.zacx.muster.spikes

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.TextView

/**
 * Command entry point, driven over adb:
 *
 *   am start -n dev.zacx.muster.spikes/.SpikeActivity --es cmd <cmd> [--es alias s3-bio|s4-cred] [--ez authreq true]
 *
 * cmd:
 *   keygen          generate the key named by `alias` (logs OK or the exception)
 *   delkey          delete it
 *   describe        log its KeyInfo
 *   probe           Signature.initSign only (detects an invalidated key)
 *   notify-activity post a notification whose "Sign" action is PendingIntent.getActivity(StepUpActivity)
 *   notify-receiver post one whose "Sign" action goes to TrampolineReceiver (the control)
 *   notify-private  S11: a VISIBILITY_PRIVATE notification with a WHO/WHERE public version
 *   notify-nopublic S11 control: VISIBILITY_PRIVATE with no public version
 */
class SpikeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "muster spikes"; setPadding(48, 200, 48, 48) })
        ensureChannel()
        handle(intent)
        finish()
    }

    private fun handle(i: Intent) {
        val cmd = i.getStringExtra("cmd") ?: return
        val alias = i.getStringExtra("alias") ?: StepUpKeys.BIO
        val authReq = i.getBooleanExtra("authreq", false)
        log("cmd=$cmd alias=$alias authreq=$authReq ${deviceLine(this)}")
        when (cmd) {
            "keygen" -> log("S34 " + StepUpKeys.generate(alias))
            "delkey" -> { StepUpKeys.delete(alias); log("S34 deleted $alias") }
            "describe" -> log("S34 describe $alias ${StepUpKeys.describe(alias)}")
            // initSign needs no auth for a per-use key; it throws
            // KeyPermanentlyInvalidatedException once the key is invalidated.
            "probe" -> log("S34 probe $alias " + try {
                java.security.Signature.getInstance("SHA256withECDSA").initSign(StepUpKeys.privateKey(alias)); "initSign OK"
            } catch (e: Exception) { "initSign FAILED ${e.javaClass.name}: ${e.message}" })
            "notify-activity" -> postSign(alias, toActivity = true, authReq = authReq)
            "notify-receiver" -> postSign(alias, toActivity = false, authReq = authReq)
            "notify-private" -> postPrivate(withPublic = true)
            "notify-nopublic" -> postPrivate(withPublic = false)
            else -> log("unknown cmd $cmd")
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            // Channel lock-screen visibility left at its default
            // (VISIBILITY_NO_OVERRIDE) so S11 measures the per-notification value.
            NotificationChannel(CHANNEL, "Spikes", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun postSign(alias: String, toActivity: Boolean, authReq: Boolean) {
        val id = if (toActivity) 31 else 32
        val extras = Bundle().apply {
            putString("alias", alias); putInt("notif", id)
            putString("variant", intent.getStringExtra("variant") ?: "plain")
        }
        val pi = if (toActivity) {
            PendingIntent.getActivity(
                this, id,
                Intent(this, StepUpActivity::class.java).putExtras(extras).putExtra("via", "notification-action-activity"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getBroadcast(
                this, id,
                Intent(this, TrampolineReceiver::class.java).putExtras(extras),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_lock_lock), "Sign", pi,
        ).setAuthenticationRequired(authReq).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("S3 ${if (toActivity) "activity" else "receiver"} $alias")
            .setContentText("Tap Sign")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
        log("S3 posted notif=$id toActivity=$toActivity alias=$alias authreq=$authReq")
    }

    private fun postPrivate(withPublic: Boolean) {
        val id = if (withPublic) 111 else 112
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("agent-x needs you")                       // WHO
            .setContentText("SECRETWHAT-$id: drop table users?")          // WHAT (must not show locked)
            .setStyle(Notification.BigTextStyle().bigText("SECRETWHAT-$id: drop table users?"))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (withPublic) {
            b.setPublicVersion(
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("PUBLICWHO agent-x")
                    .setContentText("PUBLICWHERE repo/foo")
                    .build(),
            )
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
        log("S11 posted notif=$id withPublic=$withPublic")
    }
}
