package dev.zacx.muster.spikes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * S3 control ("is an activity really required?"): a notification action
 * delivered here, which then tries to start the step-up activity. At
 * targetSdk 31+ the platform should block this start (notification
 * trampoline); ActivityTaskManager logs the block.
 */
class TrampolineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        log("S3 trampoline-receiver onReceive ${deviceLine(context)}")
        try {
            context.startActivity(
                Intent(context, StepUpActivity::class.java)
                    .putExtras(intent)
                    .putExtra("via", "receiver-trampoline")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            log("S3 trampoline-receiver startActivity returned without exception")
        } catch (e: Exception) {
            log("S3 trampoline-receiver startActivity THREW ${e.javaClass.name}: ${e.message}")
        }
    }
}
