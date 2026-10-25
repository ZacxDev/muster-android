package dev.zacx.muster

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
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
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.Pairing
import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.core.model.MusterPath
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.feature.shell.BridgeMessage
import dev.zacx.muster.feature.shell.MicrophoneAccess
import dev.zacx.muster.feature.shell.OriginPolicy
import dev.zacx.muster.feature.shell.ShellBack
import dev.zacx.muster.feature.shell.ShellNavigation
import dev.zacx.muster.feature.shell.ShellWebView
import dev.zacx.muster.push.MusterPushService
import dev.zacx.muster.shell.ShellCookies
import dev.zacx.muster.shell.ShellSnapshot
import dev.zacx.muster.shell.SnapshotOverlay
import dev.zacx.muster.shell.SnapshotPolicy
import dev.zacx.muster.ui.PhoneSettingsScreen
import dev.zacx.muster.ui.ServerUrlScreen
import dev.zacx.muster.ui.StatusBanner
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private var reportedDrawn = false

    /** The last home-page frame (ShellSnapshot) and the background job, and when they go. */
    private val local get() = app.local
    private val snapshot get() = local.snapshot

    /** The last frame on screen until the live page paints (or it times out). */
    private var overlay: SnapshotOverlay? = null

    /** The shell's current load was the launcher's home page: its first commit decides the snapshot's fate. */
    private var homeLoadPending = false
    private var refreshScheduled = false

    /** Bumped by every showShell: a WebView attach posted for an older shell does nothing. */
    private var shellGeneration = 0

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

    /** RECORD_AUDIO for the page's dictation mic; registered before STARTED. */
    private val mic = MicrophoneAccess.register(this)

    private fun answerFiles(uris: List<Uri>?) {
        val d = pendingFiles
        pendingFiles = null
        d?.invoke(uris)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the status screen shows the state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupProbe.mark("activity.onCreate")
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
        // A plain launch opens the home page: read its last frame while the
        // device record is read (both are disk; neither waits for the other).
        val launchesHome = intent.action != ACTION_SETTINGS && intent.getStringExtra(EXTRA_PATH) == null && webView == null
        val stored = if (launchesHome) lifecycleScope.async(Dispatchers.IO) { runCatching { snapshot.read(hardware = true) }.getOrNull() } else null
        val record = app.store.read()
        StartupProbe.mark("store.read")
        val origin = record.origin
        val picture = stored?.await()?.takeIf { origin != null && SnapshotPolicy.showable(it.meta, snapshotMeta(origin)) }
        when {
            intent.action == ACTION_SETTINGS -> showSettings()
            origin == null -> showServerUrl()
            // The activity is exported (it is the launcher), so the path is
            // re-checked: a muster path only, and tapTarget refuses attachments.
            else -> showShell(origin, MusterPath.parse(intent.getStringExtra(EXTRA_PATH)), record, picture?.bitmap)
        }
    }

    private fun snapshotMeta(origin: String) =
        ShellSnapshot.Meta(origin, resources.configuration.orientation, resources.configuration.screenWidthDp)

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

    private fun showShell(origin: String, path: String?, record: DeviceRecord, picture: Bitmap? = null) {
        val p = OriginPolicy.forServer(origin) ?: return showServerUrl()
        val target = ShellNavigation.tapTarget(path, p)
        val existing = webView
        if (existing != null && policy?.allowedOriginRule == p.allowedOriginRule) {
            if (path != null) existing.loadUrl(target)
            return
        }
        policy = p
        val shell = ++shellGeneration
        val banner = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    StatusBanner(app.store.records, keyInvalidated = ::keyInvalidated, onDetails = { openSettings() })
                }
            }
        }
        val page = FrameLayout(this)
        homeLoadPending = path == null
        overlay = if (picture != null && path == null) {
            SnapshotOverlay(this, picture, SHELL_BACKGROUND, onGone = { o ->
                if (overlay === o) overlay = null
                StartupProbe.mark("shell.placeholder.gone")
            }).also { it.show(page) }
        } else null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHELL_BACKGROUND)
            addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ShellWebView.padForInsets(root)
        setContentView(root)
        val attach = {
            // A newer shell (or none) replaced this one before it got its WebView.
            if (shell == shellGeneration && policy === p) attachWebView(page, p, target, record)
        }
        if (overlay != null) {
            // The picture reaches the screen first: constructing the WebView
            // (Chromium start-up, ~130 ms on the emulator) blocks this thread,
            // and a posted task runs only after the frame setContentView
            // scheduled has been laid out and drawn.
            page.post {
                StartupProbe.mark("shell.placeholder") // its frame has been laid out and drawn
                attach()
            }
        } else {
            attach()
        }
    }

    /** The shell's WebView, under the picture if there is one, then the load. */
    private fun attachWebView(page: FrameLayout, p: OriginPolicy, target: String, record: DeviceRecord) {
        StartupProbe.mark("webview.new.start")
        val wv = WebView(this)
        StartupProbe.mark("webview.new.end")
        // muster's own background (its dark theme-color), not the WebView's
        // white, until the page paints.
        wv.setBackgroundColor(SHELL_BACKGROUND)
        ShellWebView.configure(wv, p, shellCallbacks(p))
        StartupProbe.attach(wv, p.allowedOriginRule)
        page.addView(wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        back.sync()
        StartupProbe.mark("webview.loadUrl")
        wv.loadUrl(target)
        if (record.paired) refreshPush(registerIfNeeded = record.pushStatus != PushStatus.REGISTERED)
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

        override fun requestMicrophone(done: (Boolean) -> Unit) {
            runOnUiThread { mic.request(done) }
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

        override fun onPageEvent(view: WebView, event: ShellWebView.PageEvent, url: String) {
            StartupProbe.page(view, event, url)
            if (view === webView) onPageMilestone(view, event, url)
            // The first muster page on screen is "fully drawn" for the system's
            // startup metrics (logcat "Fully drawn", am start -W); once per activity.
            if (event == ShellWebView.PageEvent.COMMIT_VISIBLE && !reportedDrawn) {
                reportedDrawn = true
                reportFullyDrawn()
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

    // --- the snapshot (ShellSnapshot) --------------------------------------------------

    /**
     * A main-frame milestone of the shell's page: settle the snapshot on a
     * commit or finish, then take the picture away. A commit takes it away
     * once the page has painted (no blank frame between picture and page);
     * a finish or a main-frame error takes it away at once, as a page that
     * never paints must not leave the picture up (SnapshotOverlay also times
     * out by itself).
     */
    private fun onPageMilestone(view: WebView, event: ShellWebView.PageEvent, url: String) {
        val p = policy ?: return
        if (event == ShellWebView.PageEvent.STARTED) return
        if (event != ShellWebView.PageEvent.ERROR) {
            val home = homeLoadPending
            homeLoadPending = false
            lifecycleScope.launch { local.onCommit(url, p.allowedOriginRule, home) }
            if (!refreshScheduled) {
                // Off the start-up path: after the first commit, on a background thread; paired phones only.
                refreshScheduled = true
                app.scope.launch { local.syncRefresh(app.store.read().paired) }
            }
        }
        val o = overlay ?: return
        if (event == ShellWebView.PageEvent.COMMIT_VISIBLE && WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            // Fires once the frame holding this commit is on screen.
            WebViewCompat.postVisualStateCallback(view, 0L) { runOnUiThread { o.dismiss() } }
        } else {
            o.dismiss()
        }
    }

    /** Leaving the foreground on the home page: keep its last frame for the next launch. */
    private fun captureSnapshot() {
        val wv = webView ?: return
        val p = policy ?: return
        if (overlay?.isShowing == true || wv.width == 0 || wv.height == 0) return // the live page has not painted yet
        if (!SnapshotPolicy.isHome(wv.url, p.allowedOriginRule)) return
        val meta = snapshotMeta(p.allowedOriginRule)
        val loc = IntArray(2).also(wv::getLocationInWindow)
        val bmp = runCatching { createBitmap(wv.width, wv.height) }.getOrNull() ?: return
        val done = PixelCopy.OnPixelCopyFinishedListener { r ->
            if (r != PixelCopy.SUCCESS) { bmp.recycle(); return@OnPixelCopyFinishedListener }
            app.scope.launch(local.io) {
                runCatching { snapshot.save(bmp, meta) }
                bmp.recycle()
            }
        }
        runCatching {
            PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height), bmp, done, Handler(Looper.getMainLooper()))
        }.onFailure { bmp.recycle() }
    }

    override fun onPause() {
        captureSnapshot()
        // Write the WebView's cookies to disk now. Chromium otherwise flushes
        // them on its own timer, and a process killed before that (a swipe
        // from Recents, a force-stop) reopens with the session it had before:
        // signed out again after a sign-in, or signed in again after a
        // sign-out (both seen on the emulator, a few seconds after signing).
        // Native code still never reads them.
        // Only with a WebView, and never a crash (ShellCookies).
        ShellCookies.flushOnPause(webView)
        super.onPause()
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
                app.scope.launch { local.syncRefresh(paired = true) }
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
            // The record loses its pairing; the picture and the background job go with it.
            local.markUnpaired(app.store)
            Toast.makeText(this@MainActivity, R.string.unpaired, Toast.LENGTH_LONG).show()
        }
    }

    /** Only while unpaired: forget the server and its cookies, back to the first-run screen. */
    private fun changeServer() {
        lifecycleScope.launch {
            if (!local.changeServer(app.store) { CookieManager.getInstance().removeAllCookies(null) }) return@launch
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
        shellGeneration++
        overlay?.dismiss()
        overlay = null
        homeLoadPending = false
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

        /** muster's dark theme-color (web: `<meta name="theme-color">`), the launcher icon's tile too. */
        const val SHELL_BACKGROUND = 0xFF110F1C.toInt()
    }
}
