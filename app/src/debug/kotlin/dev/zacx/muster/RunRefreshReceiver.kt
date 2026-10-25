package dev.zacx.muster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.zacx.muster.refresh.RefreshWorker

/** DEBUG BUILDS ONLY (src/debug): runs the background-refresh worker once, now, for measurements. */
class RunRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
    }
}
