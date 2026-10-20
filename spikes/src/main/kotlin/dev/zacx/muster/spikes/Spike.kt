package dev.zacx.muster.spikes

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.util.Log

/** Every spike result is one logcat line under this tag, so a run is greppable. */
const val TAG = "MUSTER_SPIKE"

const val CHANNEL = "spike"

fun log(msg: String) {
    Log.i(TAG, msg)
}

fun deviceLine(ctx: Context): String {
    val km = ctx.getSystemService(KeyguardManager::class.java)
    return "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
        "secure=${km.isDeviceSecure} keyguardLocked=${km.isKeyguardLocked}"
}
