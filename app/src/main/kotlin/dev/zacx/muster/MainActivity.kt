package dev.zacx.muster

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.Pairing
import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.core.model.MusterPath
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.feature.shell.BridgeMessage
import dev.zacx.muster.feature.shell.OriginPolicy
import dev.zacx.muster.feature.shell.ShellNavigation
import dev.zacx.muster.feature.shell.ShellWebView
import dev.zacx.muster.push.MusterPushService
import dev.zacx.muster.ui.PhoneSettingsScreen
import dev.zacx.muster.ui.ServerUrlScreen
import dev.zacx.muster.ui.StatusBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The launcher activity: the server-URL screen on first run, then the
 * WebView shell, plus the native phone-settings screen (a launcher shortcut).
 */
class MainActivity : ComponentActivity() {
    private val app get() = MusterApp.from(this)
    private var webView: WebView? = null
    private var policy: OriginPolicy? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the status screen shows the state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { route(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { route(intent) }
    }

    private suspend fun route(intent: Intent) {
        val record = app.store.read()
        val origin = record.origin
        when {
            intent.action == ACTION_SETTINGS -> showSettings()
            origin == null -> showServerUrl()
            // The activity is exported (it is the launcher), so the path is
            // re-checked: a muster path only, and tapTarget refuses attachments.
            else -> showShell(origin, MusterPath.parse(intent.getStringExtra(EXTRA_PATH)), record)
        }
    }

    // --- first run --------------------------------------------------------------------

    private fun showServerUrl() {
        webView = null
        setContent {
            MaterialTheme {
                ServerUrlScreen { url ->
                    val p = OriginPolicy.forServer(url.trim()) ?: return@ServerUrlScreen false
                    lifecycleScope.launch {
                        app.store.update { DeviceRecord(origin = p.allowedOriginRule) }
                        route(Intent())
                    }
                    true
                }
            }
        }
    }

    // --- the shell --------------------------------------------------------------------

    private fun showShell(origin: String, path: String?, record: DeviceRecord) {
        val p = OriginPolicy.forServer(origin) ?: return showServerUrl()
        val target = ShellNavigation.tapTarget(path, p)
        val existing = webView
        if (existing != null && policy?.allowedOriginRule == p.allowedOriginRule) {
            if (path != null) existing.loadUrl(target)
            return
        }
        policy = p
        val wv = WebView(this)
        ShellWebView.configure(wv, p, shellCallbacks(p))
        val banner = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    StatusBanner(app.store.records, keyInvalidated = ::keyInvalidated, onDetails = { openSettings() })
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ShellWebView.padForInsets(root)
        setContentView(root)
        webView = wv
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val v = webView
                if (v != null && v.canGoBack()) v.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        wv.loadUrl(target)
        if (record.paired) refreshPush(registerIfNeeded = record.pushStatus != PushStatus.REGISTERED)
    }

    private fun shellCallbacks(p: OriginPolicy) = object : ShellWebView.Callbacks {
        override fun onBridgeMessage(message: BridgeMessage) {
            runOnUiThread {
                when (message) {
                    is BridgeMessage.Pair -> confirmPairing(p.allowedOriginRule, message.code)
                    BridgeMessage.OpenNotificationSettings -> openNotificationSettings()
                    is BridgeMessage.Refused -> Unit
                }
            }
        }

        override fun openExternally(uri: Uri) {
            runCatching { CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, uri) }
        }

        override fun onRendererGone(view: WebView) {
            runOnUiThread {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (webView === view) {
                    webView = null
                    policy = null
                    lifecycleScope.launch { route(intent) }
                }
            }
        }

        override fun onBlockedAttachment() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.attachment_blocked, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- pairing ----------------------------------------------------------------------

    private fun confirmPairing(origin: String, code: String) {
        lifecycleScope.launch {
            val already = app.store.read().paired
            val msg = getString(R.string.pair_confirm, origin) +
                if (already) "\n\n" + getString(R.string.pair_replaces) else ""
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.pair_title)
                .setMessage(msg)
                .setPositiveButton(R.string.pair_ok) { _, _ -> pair(origin, code) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pair(origin: String, code: String) {
        lifecycleScope.launch {
            val out = app.pairing(origin).pair(code, MusterApp.deviceName(), appVersion())
            val (title, text) = when (out) {
                is Pairing.Outcome.Paired -> getString(R.string.paired_title) to
                    (out.note ?: getString(R.string.paired_biometric))
                is Pairing.Outcome.Failed -> getString(R.string.pair_failed_title) to out.message
            }
            AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(text)
                .setPositiveButton(android.R.string.ok, null).show()
            if (out is Pairing.Outcome.Paired) {
                askNotificationPermission()
                refreshPush(registerIfNeeded = true)
                webView?.reload()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- push -------------------------------------------------------------------------

    /** Reads /api/device/config (and so notices a revoked pairing); registers push when asked. */
    private fun refreshPush(registerIfNeeded: Boolean) {
        lifecycleScope.launch {
            val r = app.registrar.refreshConfig()
            if (r is PushRegistrar.ConfigResult.Revoked) {
                runCatching { MusterPushService.unregister(this@MainActivity) }
                return@launch
            }
            if (r !is PushRegistrar.ConfigResult.Register || !registerIfNeeded) return@launch
            UnifiedPush.tryUseCurrentOrDefaultDistributor(this@MainActivity) { ok ->
                when {
                    ok -> MusterPushService.register(this@MainActivity, r.vapidKey)
                    UnifiedPush.getDistributors(this@MainActivity).isNotEmpty() ->
                        UnifiedPush.tryPickDistributor(this@MainActivity) { picked ->
                            if (picked) MusterPushService.register(this@MainActivity, r.vapidKey)
                            else lifecycleScope.launch { app.registrar.noDistributor() }
                        }
                    else -> lifecycleScope.launch { app.registrar.noDistributor() }
                }
            }
        }
    }

    // --- phone settings ---------------------------------------------------------------

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).setAction(ACTION_SETTINGS))
    }

    private fun showSettings() {
        webView = null
        policy = null
        setContent {
            MaterialTheme {
                PhoneSettingsScreen(
                    records = app.store.records,
                    keyInvalidated = ::keyInvalidated,
                    notificationsEnabled = {
                        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
                    },
                    onNotificationSettings = ::openNotificationSettings,
                    onRetryPush = { refreshPush(registerIfNeeded = true) },
                    onUnpair = ::unpair,
                    onChangeServer = ::changeServer,
                    onClose = { finish() },
                )
            }
        }
    }

    private suspend fun keyInvalidated(): Boolean = withContext(Dispatchers.IO) {
        app.store.read().keyKind != null &&
            runCatching { app.keys.state() }.getOrNull() != dev.zacx.muster.core.auth.StepUpKeys.KeyState.VALID
    }

    private fun unpair() {
        lifecycleScope.launch {
            val r = app.store.read()
            val (origin, token) = r.origin to r.token
            if (origin != null && token != null) {
                withContext(Dispatchers.IO) { runCatching { DeviceApi(origin).unpair(token) } }
            }
            runCatching { MusterPushService.unregister(this@MainActivity) }
            withContext(Dispatchers.IO) { runCatching { app.keys.delete() } }
            app.store.update(DeviceRecord::unpaired)
            Toast.makeText(this@MainActivity, R.string.unpaired, Toast.LENGTH_LONG).show()
        }
    }

    /** Only while unpaired: forget the server and its cookies, back to the first-run screen. */
    private fun changeServer() {
        lifecycleScope.launch {
            if (app.store.read().paired) return@launch
            CookieManager.getInstance().removeAllCookies(null)
            app.store.update { DeviceRecord() }
            startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "dev.zacx.muster.OPEN"
        const val ACTION_SETTINGS = "dev.zacx.muster.SETTINGS"
        const val EXTRA_PATH = "path"
    }
}
