package dev.zacx.muster

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Configuration
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
class MusterApp : Application(), Configuration.Provider {
    /**
     * WorkManager starts on first use, not at every process start (its
     * App Startup initializer is removed in the manifest): a cold start of
     * the shell must not pay for it.
     */
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store: DeviceStore by lazy {
        // noBackupFilesDir: never part of Auto Backup, whatever the rules say.
        DataStoreDeviceStore(File(noBackupFilesDir, "device.sealed"), AesGcmAead.androidKeystore(), scope)
    }

    val keys: StepUpKeys by lazy { KeystoreStepUpKeys(this) }

    val notifier by lazy { AndroidNotifier(this, ::tapIntent) }

    val pushHandler by lazy { PushHandler(notifier) }

    val registrar by lazy { PushRegistrar(store, ::DeviceApi, onForget = { old -> old.keyAlias?.let { a -> runCatching { keys.delete(a) } } }) }

    fun pairing(origin: String) = Pairing(DeviceApi(origin), keys, store)

    override fun onCreate() {
        StartupProbe.mark("app.onCreate.start")
        super.onCreate()
        Channels.ensure(this)
        StartupProbe.mark("app.onCreate.end")
    }

    private fun tapIntent(tag: String, path: String?): PendingIntent = tapPendingIntent(this, tag, path)

    companion object {
        /**
         * A notification tap: open the shell at [path], immutable and explicit.
         * The tag goes into the intent's DATA as well as the request code:
         * PendingIntents are told apart by request code AND Intent.filterEquals
         * (which ignores extras), so two tags whose hash codes collide
         * ("Aa" and "BB") would otherwise share one PendingIntent, and
         * FLAG_UPDATE_CURRENT would make the first notification open the
         * second one's path.
         */
        fun tapPendingIntent(context: Context, tag: String, path: String?): PendingIntent {
            val i = Intent(context, MainActivity::class.java)
                .setData(android.net.Uri.fromParts("muster-tap", tag, null))
                .putExtra(MainActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun from(c: Context) = c.applicationContext as MusterApp

        fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android phone" }
    }
}
