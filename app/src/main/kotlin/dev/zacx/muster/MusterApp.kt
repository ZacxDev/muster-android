package dev.zacx.muster

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.zacx.muster.core.auth.AesGcmAead
import dev.zacx.muster.core.auth.DataStoreDeviceStore
import dev.zacx.muster.core.auth.DeviceStore
import dev.zacx.muster.core.auth.KeystoreStepUpKeys
import dev.zacx.muster.core.auth.Pairing
import dev.zacx.muster.core.auth.StepUpKeys
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.feature.notify.AndroidNotifier
import dev.zacx.muster.feature.notify.Channels
import dev.zacx.muster.feature.push.PushHandler
import dev.zacx.muster.feature.push.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Wires the modules together. One instance of each store per process. */
class MusterApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store: DeviceStore by lazy {
        // noBackupFilesDir: never part of Auto Backup, whatever the rules say.
        DataStoreDeviceStore(File(noBackupFilesDir, "device.sealed"), AesGcmAead.androidKeystore(), scope)
    }

    val keys: StepUpKeys by lazy { KeystoreStepUpKeys(this) }

    val notifier by lazy { AndroidNotifier(this, ::tapIntent) }

    val pushHandler by lazy { PushHandler(notifier) }

    val registrar by lazy { PushRegistrar(store, ::DeviceApi, onForget = { runCatching { keys.delete() } }) }

    fun pairing(origin: String) = Pairing(DeviceApi(origin), keys, store)

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
    }

    /** A notification tap: open the shell at [path], immutable and explicit. */
    private fun tapIntent(tag: String, path: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN)
            .putExtra(MainActivity.EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // A distinct request code per tag, so two notifications never share an intent.
        return PendingIntent.getActivity(this, tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        fun from(c: Context) = c.applicationContext as MusterApp

        fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android phone" }
    }
}
