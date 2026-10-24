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
import dev.zacx.muster.feature.shell.ShellBack
import dev.zacx.muster.feature.shell.ShellNavigation
import dev.zacx.muster.feature.shell.ShellWebView
import dev.zacx.muster.push.MusterPushService
import dev.zacx.muster.ui.PhoneSettingsScreen
import dev.zacx.muster.ui.ServerUrlScreen
import dev.zacx.muster.ui.StatusBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The launcher activity: the server-URL screen on first run, then the
 * WebView shell, plus the native phone-settings screen (a launcher shortcut).
 */
class MainActivity : ComponentActivity() {
    private val app get() = MusterApp.from(this)
    private var webView: WebView? = null
    private var policy: OriginPolicy? = null

    /** Reloads Account when push registration settles (AccountPageRefresh); one per shell. */
    private var pushWatch: Job? = null

    /** The ONE back callback, registered once in onCreate (ShellBack). */
    private val back = ShellBack { webView }

    /** The page's pending file request: answered exactly once, null if cancelled or replaced. */
    private var pendingFiles: ((List<Uri>?) -> Unit)? = null
    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        answerFiles(uri?.let { listOf(it) })
    }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        answerFiles(uris)
    }

    private fun answerFiles(uris: List<Uri>?) {
        val d = pendingFiles
        pendingFiles = null
        d?.invoke(uris)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the status screen shows the state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, back)
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
        dropWebView()
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
        back.sync()
        wv.loadUrl(target)
        watchPush(wv, p)
        if (record.paired) refreshPush(registerIfNeeded = record.pushStatus != PushStatus.REGISTERED)
    }

    private fun watchPush(wv: WebView, p: OriginPolicy) {
        pushWatch?.cancel()
        pushWatch = lifecycleScope.launch {
            var previous: PushStatus? = null
            app.store.records.map { it.pushStatus }.distinctUntilChanged().collect { status ->
                if (webView === wv && AccountPageRefresh.shouldReload(previous, status, wv.url, p)) wv.reload()
                previous = status
            }
        }
    }

    private fun shellCallbacks(p: OriginPolicy) = object : ShellWebView.Callbacks {
        override fun onBridgeMessage(message: BridgeMessage) {
            runOnUiThread {
                when (message) {
                    is BridgeMessage.Pair -> confirmPairing(p.allowedOriginRule, message.code, message.expiresAt)
                    BridgeMessage.OpenNotificationSettings -> openNotificationSettings()
                    is BridgeMessage.Refused -> Unit
                }
            }
        }

        override fun openExternally(url: String) {
            runOnUiThread {
                runCatching { CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(url)) }
            }
        }

        override fun onRendererGone(view: WebView) {
            runOnUiThread {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (webView === view) {
                    webView = null
                    policy = null
                    back.sync()
                    lifecycleScope.launch { route(intent) }
                }
            }
        }

        override fun chooseFiles(mimeTypes: List<String>, multiple: Boolean, deliver: (List<Uri>?) -> Unit) {
            runOnUiThread {
                answerFiles(null) // a newer request replaces an unanswered one
                pendingFiles = deliver
                val types = mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
                val launched = runCatching { if (multiple) openMany.launch(types) else openOne.launch(types) }
                if (launched.isFailure) answerFiles(null) // no document picker on this phone
            }
        }

        override fun jsDialog(message: String, confirm: Boolean, done: (Boolean) -> Unit) {
            runOnUiThread {
                var answered = false
                fun answer(ok: Boolean) { if (!answered) { answered = true; done(ok) } }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> answer(true) }
                    .apply { if (confirm) setNegativeButton(android.R.string.cancel) { _, _ -> answer(false) } }
                    .setOnDismissListener { answer(!confirm) } // an alert dismissed is acknowledged; a confirm dismissed is "no"
                    .show()
            }
        }

        override fun onHistoryChanged() {
            runOnUiThread { back.sync() }
        }

        override fun onBlockedAttachment() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.attachment_blocked, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- pairing ----------------------------------------------------------------------

    private fun confirmPairing(origin: String, code: String, expiresAt: Instant?) {
        lifecycleScope.launch {
            val already = app.store.read().paired
            val msg = getString(R.string.pair_confirm, origin) +
                if (already) "\n\n" + getString(R.string.pair_replaces) else ""
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.pair_title)
                .setMessage(msg)
                .setPositiveButton(R.string.pair_ok) { _, _ -> pair(origin, code, expiresAt) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pair(origin: String, code: String, expiresAt: Instant?) {
        lifecycleScope.launch {
            // Pairing re-checks expires_at now, at the tap, before anything else.
            val out = app.pairing(origin).pair(code, expiresAt, MusterApp.deviceName(), appVersion())
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
            if (r is PushRegistrar.ConfigResult.Failed) {
                // Not a revocation (only muster's own refusal is): the pairing stays.
                Toast.makeText(this@MainActivity, getString(R.string.config_failed, r.message), Toast.LENGTH_LONG).show()
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
        dropWebView()
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
        val alias = app.store.read().keyAlias
        alias != null && runCatching { app.keys.state(alias) }.getOrNull() != dev.zacx.muster.core.auth.StepUpKeys.KeyState.VALID
    }

    private fun unpair() {
        lifecycleScope.launch {
            val r = app.store.read()
            val (origin, token) = r.origin to r.token
            if (origin != null && token != null) {
                withContext(Dispatchers.IO) { runCatching { DeviceApi(origin).unpair(token) } }
            }
            runCatching { MusterPushService.unregister(this@MainActivity) }
            r.keyAlias?.let { a -> withContext(Dispatchers.IO) { runCatching { app.keys.delete(a) } } }
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
        dropWebView()
        super.onDestroy()
    }

    /** Detaches and DESTROYS the shell's WebView (its renderer, its page), and re-syncs back. */
    private fun dropWebView() {
        pushWatch?.cancel()
        pushWatch = null
        webView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.destroy()
        }
        webView = null
        policy = null
        back.sync()
    }

    companion object {
        const val ACTION_SETTINGS = "dev.zacx.muster.SETTINGS"
        const val EXTRA_PATH = "path"
    }
}
