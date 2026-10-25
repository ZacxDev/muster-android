package dev.zacx.muster.feature.shell

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * The app's own RECORD_AUDIO permission, asked for when muster's page wants
 * the microphone (dictation) and the app does not hold it yet.
 *
 * [request] answers its callback exactly once:
 * - **true** at once when the app already holds RECORD_AUDIO;
 * - otherwise after Android's permission prompt, with the user's answer;
 * - **false** when a newer [request] replaces it while the prompt is up (the
 *   prompt stays and answers the newer one), or when the prompt cannot start.
 *
 * When the answer is "no" and Android showed nothing because the user chose
 * "Don't allow" before (asked before, and Android offers no rationale either
 * before or after the request), [Host.offerSettings] shows a dialog that opens
 * the app's settings page, so a blocked microphone is never a silent refusal.
 */
class MicrophoneAccess(private val host: Host) {

    interface Host {
        fun granted(): Boolean

        /** `shouldShowRequestPermissionRationale(RECORD_AUDIO)`. */
        fun rationale(): Boolean

        /** True once the app has asked for RECORD_AUDIO on this install. */
        fun askedBefore(): Boolean

        fun markAsked()

        /** Shows Android's prompt; its answer comes back through [onResult]. */
        fun launch()

        /** Offers to open the app's settings page, where the microphone can be allowed. */
        fun offerSettings()
    }

    private var waiting: ((Boolean) -> Unit)? = null
    private var prompting = false
    private var askedBefore = false
    private var rationaleBefore = false

    fun request(done: (Boolean) -> Unit) {
        answer(false) // a newer request replaces an unanswered one
        if (host.granted()) {
            done(true)
            return
        }
        waiting = done
        if (prompting) return // the prompt already up answers this request
        askedBefore = host.askedBefore()
        rationaleBefore = host.rationale()
        host.markAsked()
        prompting = true
        if (runCatching { host.launch() }.isFailure) {
            prompting = false
            answer(false)
        }
    }

    /** Android's answer to the prompt [request] started. */
    fun onResult(granted: Boolean) {
        prompting = false
        if (!granted && waiting != null && askedBefore && !rationaleBefore && !host.rationale()) host.offerSettings()
        answer(granted)
    }

    private fun answer(ok: Boolean) {
        val w = waiting
        waiting = null
        w?.invoke(ok)
    }

    companion object {
        private const val PREFS = "shell_permissions"
        private const val ASKED = "record_audio_asked"

        /**
         * Registers the permission launcher on [activity]. Call it before the
         * activity is STARTED; a field initialiser is fine (nothing here
         * touches the activity's context until a request).
         */
        fun register(activity: ComponentActivity): MicrophoneAccess {
            lateinit var access: MicrophoneAccess
            val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                access.onResult(it)
            }
            // Lazily: register runs in the activity's field initialisers, before it has a context.
            val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            access = MicrophoneAccess(object : Host {
                override fun granted() =
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED

                override fun rationale() =
                    activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

                override fun askedBefore() = prefs.getBoolean(ASKED, false)
                override fun markAsked() = prefs.edit { putBoolean(ASKED, true) }
                override fun launch() = launcher.launch(Manifest.permission.RECORD_AUDIO)
                override fun offerSettings() = settingsDialog(activity)
            })
            return access
        }

        private fun settingsDialog(activity: ComponentActivity) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.mic_blocked_title)
                .setMessage(R.string.mic_blocked_message)
                .setPositiveButton(R.string.mic_open_settings) { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                        )
                    }
                }
                .setNegativeButton(R.string.mic_not_now, null)
                .show()
        }
    }
}
