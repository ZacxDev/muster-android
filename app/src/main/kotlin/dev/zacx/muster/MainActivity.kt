package dev.zacx.muster

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.zacx.muster.core.auth.Pairing
import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.TenantRepository
import dev.zacx.muster.core.model.MusterPath
import dev.zacx.muster.feature.notify.Channels
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.feature.shell.BridgeMessage
import dev.zacx.muster.feature.shell.MicrophoneAccess
import dev.zacx.muster.feature.shell.OriginPolicy
import dev.zacx.muster.feature.shell.ShellBack
import dev.zacx.muster.feature.shell.ShellNavigation
import dev.zacx.muster.feature.shell.ShellRefreshLayout
import dev.zacx.muster.feature.shell.ShellWebView
import dev.zacx.muster.push.MusterPushService
import dev.zacx.muster.shell.ShellCookies
import dev.zacx.muster.shell.ShellRestore
import dev.zacx.muster.shell.ShellSnapshot
import dev.zacx.muster.shell.SnapshotOverlay
import dev.zacx.muster.shell.SnapshotPolicy
import dev.zacx.muster.tenants.SsoOriginsRefresh
import dev.zacx.muster.tenants.TapRouting
import dev.zacx.muster.tenants.notifyTenant
import dev.zacx.muster.ui.PhoneSettingsScreen
import dev.zacx.muster.ui.ServerUrlScreen
import dev.zacx.muster.ui.ServersScreen
import dev.zacx.muster.ui.StatusBanner
import dev.zacx.muster.ui.SystemBars
import dev.zacx.muster.ui.TenantBar
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The launcher activity: the server-URL screen on first run, then the
 * WebView shell for the muster server in view with the server switcher
 * above it, plus the native server-management and phone-settings screens
 * (a launcher shortcut, or a long press on the switcher).
 *
 * One WebView at a time: switching servers destroys it and builds a new one
 * in the other server's WebView profile (feature/shell TenantSessions), whose
 * cookies kept that server's sign-in.
 */
class MainActivity : ComponentActivity() {
    private val app get() = MusterApp.from(this)
    private var webView: WebView? = null
    private var policy: OriginPolicy? = null
    private var reportedDrawn = false

    /** The tenant whose page the shell shows, and its WebView profile; null with no shell. */
    private var shownTenant: String? = null
    private var shownProfile: String? = null

    /**
     * Back on the native screens (servers, settings, add server): returns to
     * the shell. Enabled only while one is shown, so it never takes a press
     * from the shell's own callback (ShellBack) or the page's back-claim.
     */
    private val screenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            isEnabled = false
            lifecycleScope.launch { route(Intent()) }
        }
    }

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

    /** The ONE back callback, registered once in onCreate (ShellBack); a press the page declines goes on to the system. */
    private val back = ShellBack({ webView }, { onBackPressedDispatcher.onBackPressed() })

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
        // Edge to edge from the first frame; each screen then sets the icons
        // for its own background (SystemBars).
        barsOver(SHELL_BACKGROUND)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, back)
        onBackPressedDispatcher.addCallback(this, screenBack)
        // Re-created with the shell up (a change the manifest does not declare,
        // or process death): back to the page it showed (ShellRestore).
        val restored = ShellRestore.intent(savedInstanceState)
        lifecycleScope.launch { route(restored ?: intent) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val t = shownTenant
        val p = policy
        if (t != null && p != null && webView != null) ShellRestore.save(outState, t, p.allowedOriginRule, webView?.url)
    }

    /** The colour behind the system bars now: what [onConfigurationChanged] re-applies. */
    private var barsBackground = SHELL_BACKGROUND

    private fun barsOver(background: Int) {
        barsBackground = background
        SystemBars.applyOver(this, background)
    }

    /** A native screen's Material surface is what shows behind the system bars. */
    @Composable
    private fun NativeSystemBars() {
        val surface = MaterialTheme.colorScheme.surface.toArgb()
        SideEffect { barsOver(surface) }
    }

    /**
     * A change the manifest declares (configChanges) keeps this activity, but
     * not the window's bar appearance: measured on an API 36 emulator, the
     * appearance set here still reads LIGHT_STATUS_BARS|LIGHT_NAVIGATION_BARS
     * when this returns and is 0 (white icons) by the next message on the
     * main thread (what clears it is not established), so a dark-mode toggle
     * or a rotation left white icons on a light native screen. The current
     * screen's icons go back on once the change has been dispatched.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { SystemBars.applyOver(this, barsBackground) }
    }

    override fun onResume() {
        super.onResume()
        // A server removed or switched elsewhere (the background job, another
        // window): the shell follows the tenant in view.
        val shown = shownTenant ?: return
        lifecycleScope.launch {
            val t = app.tenants.read()
            val active = t.active
            when {
                webView == null || shownTenant != shown -> Unit
                active == null -> route(Intent())
                active.id != shown -> showShell(active, null)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { route(intent) }
    }

    private suspend fun route(intent: Intent) {
        // A plain launch opens the home page: read its last frame while the
        // tenants are read (both are disk; neither waits for the other).
        val launchesHome = intent.action != ACTION_SETTINGS && intent.getStringExtra(EXTRA_PATH) == null && webView == null
        val stored = if (launchesHome) lifecycleScope.async(Dispatchers.IO) { runCatching { snapshot.read(hardware = true) }.getOrNull() } else null
        val tenants = app.tenants.read()
        StartupProbe.mark("store.read")
        // No WebView alive: a removed server's profile that was still in use can go now.
        if (webView == null) app.ops.retryProfileDeletes()
        tenants.all.forEach { Channels.ensure(this, it.notifyTenant()) }
        // Each server's name and logo, in the background (never waited for).
        app.refreshBrandingOnce()
        // The activity is exported (it is the launcher): the path is re-checked
        // (a muster path only, and tapTarget refuses upload routes whatever
        // AttachmentGate says), and a tap's tenant only selects which of this
        // phone's own servers is in view.
        val target = TapRouting.resolve(tenants, intent.getStringExtra(MusterApp.EXTRA_TENANT), MusterPath.parse(intent.getStringExtra(EXTRA_PATH)))
        when {
            intent.action == ACTION_SETTINGS -> showServers()
            target == null -> showServerUrl(adding = false)
            else -> {
                if (target.switched) app.tenants.select(target.tenant.id)
                val origin = target.tenant.origin
                val picture = stored?.await()?.takeIf { SnapshotPolicy.showable(it.meta, snapshotMeta(origin)) }
                showShell(target.tenant, target.path, picture?.bitmap)
            }
        }
    }

    private fun snapshotMeta(origin: String) =
        ShellSnapshot.Meta(origin, resources.configuration.orientation, resources.configuration.screenWidthDp)

    // --- first run --------------------------------------------------------------------

    /** First run ([adding] false), or Add server: a server's URL, which becomes a new tenant, unpaired and in view. */
    private fun showServerUrl(adding: Boolean) {
        dropWebView()
        screenBack.isEnabled = adding
        setContent {
            MaterialTheme {
                NativeSystemBars()
                ServerUrlScreen(adding = adding, onCancel = if (adding) ({ lifecycleScope.launch { route(Intent()) } }) else null) { url ->
                    val p = OriginPolicy.forServer(url.trim()) ?: return@ServerUrlScreen getString(R.string.url_invalid)
                    lifecycleScope.launch {
                        when (val r = app.ops.add(p.allowedOriginRule)) {
                            is TenantRepository.AddResult.Added -> {
                                refreshBranding(r.tenant); readSsoOrigins(r.tenant.id); route(Intent())
                            }
                            // Already one of this phone's servers: switch to it.
                            is TenantRepository.AddResult.Duplicate -> {
                                app.tenants.select(r.tenant.id); refreshBranding(r.tenant); readSsoOrigins(r.tenant.id); route(Intent())
                            }
                            TenantRepository.AddResult.NeedsWebViewProfiles -> AlertDialog.Builder(this@MainActivity)
                                .setTitle(R.string.webview_update_title).setMessage(R.string.webview_update)
                                .setPositiveButton(android.R.string.ok, null).show()
                        }
                    }
                    null
                }
            }
        }
    }

    /**
     * Reads the new server's sign-in origins BEFORE its first page loads (a
     * gated server's first page is a redirect to sign-in, which must load in
     * the WebView). At most [SSO_READ_TIMEOUT_MS]; on a timeout or failure the
     * server opens with what is stored (none for a new one), and the shell's
     * own read on open ([refreshSsoOriginsInBackground]) tries again.
     */
    private suspend fun readSsoOrigins(id: String) {
        withTimeoutOrNull(SSO_READ_TIMEOUT_MS) { app.ops.refreshSsoOrigins(id) }
    }

    /** A server just added: its name and logo, read now in the background (the switcher shows its host until then). */
    private fun refreshBranding(t: TenantRecord) {
        app.scope.launch { runCatching { app.branding.refresh(t, force = true) } }
    }

    /** When a changed sign-in list rebuilds the shell: never from a rebuild, at most once a minute ([SsoOriginsRefresh]). */
    private val ssoRefresh by lazy { SsoOriginsRefresh({ app.ops.refreshSsoOrigins(it) }) }

    /**
     * Re-reads tenant [t]'s sign-in origins after its shell opened, unless
     * this shell is itself that read's rebuild ([byRefresh]). When they
     * changed, the rebuild gap allows it and that tenant is still in view,
     * the shell is rebuilt on them (at its home page); otherwise they apply
     * to the next shell.
     */
    private fun refreshSsoOriginsInBackground(t: TenantRecord, byRefresh: Boolean) {
        lifecycleScope.launch {
            if (!ssoRefresh.onShellOpened(t.id, byRefresh)) return@launch
            val fresh = app.tenants.read().byId(t.id) ?: return@launch
            if (shownTenant == t.id) showShell(fresh, null, byRefresh = true)
        }
    }

    // --- the shell --------------------------------------------------------------------

    /**
     * The shell for tenant [t]: its page at [path] on ITS origin. The same
     * tenant already on screen just loads the path; any other tenant gets a
     * new WebView in its own profile (the old one is destroyed first).
     */
    private fun showShell(t: TenantRecord, path: String?, picture: Bitmap? = null, byRefresh: Boolean = false) {
        val p = OriginPolicy.forServer(t.origin, t.ssoOrigins) ?: return showServerUrl(adding = false)
        val target = ShellNavigation.tapTarget(path, p)
        val existing = webView
        screenBack.isEnabled = false
        if (existing != null && shownTenant == t.id && policy?.allowedOriginRule == p.allowedOriginRule &&
            policy?.ssoOrigins == p.ssoOrigins
        ) {
            if (path != null) existing.loadUrl(target)
            return
        }
        dropWebView()
        policy = p
        shownTenant = t.id
        shownProfile = t.webProfile
        val shell = ++shellGeneration
        val bar = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    val tenants by app.tenants.tenants.collectAsState(initial = null)
                    val brands by remember { app.shownBrands() }.collectAsState(initial = emptyMap())
                    tenants?.let { all ->
                        TenantBar(all, onSelect = ::switchTo, onManage = { showServers() }, onAdd = { showServerUrl(adding = true) }, brands = brands)
                    }
                }
            }
        }
        val banner = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    StatusBanner(app.store(t.id).records, keyInvalidated = { keyInvalidated(t.id) }, onDetails = { showServers(detail = t.id) })
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
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ShellWebView.padForInsets(root)
        setContentView(root)
        // The container's background is what shows behind both bars (its inset padding).
        barsOver(SHELL_BACKGROUND)
        val attach = {
            // A newer shell (or none) replaced this one before it got its WebView.
            if (shell == shellGeneration && policy === p) attachWebView(page, p, target, t, byRefresh)
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

    /** The shell's WebView in tenant [t]'s profile, under the picture if there is one, then the load. */
    private fun attachWebView(page: FrameLayout, p: OriginPolicy, target: String, t: TenantRecord, byRefresh: Boolean) {
        StartupProbe.mark("webview.new.start")
        val wv = WebView(this)
        StartupProbe.mark("webview.new.end")
        // 🔴 Its own profile before anything loads: no other server's cookies.
        if (!app.sessions.prepare(wv, t.webProfile)) {
            wv.destroy()
            overlay?.dismiss()
            AlertDialog.Builder(this).setTitle(R.string.webview_update_title).setMessage(R.string.webview_update)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        // muster's own background (its dark theme-color), not the WebView's
        // white, until the page paints.
        wv.setBackgroundColor(SHELL_BACKGROUND)
        ShellWebView.configure(wv, p, shellCallbacks(p))
        StartupProbe.attach(wv, p.allowedOriginRule)
        page.addView(ShellRefreshLayout(this, wv), 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        back.onWebViewChanged()
        StartupProbe.mark("webview.loadUrl")
        wv.loadUrl(target)
        refreshSsoOriginsInBackground(t, byRefresh)
        if (t.device.paired) refreshPush(t.id, registerIfNeeded = t.device.pushStatus != PushStatus.REGISTERED)
    }

    /** The switcher's pick: [id] becomes the tenant in view, and the shell reloads on its server. */
    private fun switchTo(id: String) {
        lifecycleScope.launch {
            if (!app.tenants.select(id)) return@launch
            val t = app.tenants.read().byId(id) ?: return@launch
            showShell(t, null)
        }
    }

    private fun shellCallbacks(p: OriginPolicy) = object : ShellWebView.Callbacks {
        override fun onBridgeMessage(message: BridgeMessage) {
            runOnUiThread {
                when (message) {
                    is BridgeMessage.Pair -> confirmPairing(p.allowedOriginRule, message.code, message.expiresAt)
                    BridgeMessage.OpenNotificationSettings -> openNotificationSettings()
                    is BridgeMessage.TouchStart -> webView?.let(ShellRefreshLayout::of)?.onProbe(message)
                    is BridgeMessage.BackClaim -> back.claim(message.open)
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
                    back.onWebViewChanged()
                    // The tenant in view again, never the last intent (it could switch servers).
                    lifecycleScope.launch {
                        val t = TapRouting.afterRendererGone(app.tenants.read())
                        if (t != null) showShell(t.tenant, null) else route(Intent())
                    }
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
            if (view === webView) {
                // A page start ends the dock's back claim (ShellBack.onPageEvent).
                back.onPageEvent(event)
                onPageMilestone(view, event, url)
            }
            // The first muster page on screen is "fully drawn" for the system's
            // startup metrics (logcat "Fully drawn", am start -W); once per activity.
            if (event == ShellWebView.PageEvent.COMMIT_VISIBLE && !reportedDrawn) {
                reportedDrawn = true
                reportFullyDrawn()
            }
        }

        override fun onHistoryChanged() {
            // Keeps the back claim: the view swap's replaceState happens with the dock open.
            runOnUiThread { back.onHistoryChanged() }
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
            lifecycleScope.launch { local.onCommit(url, p.allowedOriginRule, home, p.ssoOrigins) }
            if (!refreshScheduled) {
                // Off the start-up path: after the first commit, on a background thread; paired phones only.
                refreshScheduled = true
                app.scope.launch { local.syncRefresh(app.tenants.read().all.any { it.device.paired }) }
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
        val profile = shownProfile
        ShellCookies.flushOnPause(webView) { profile?.let(app.sessions::flush) }
        super.onPause()
    }

    // --- pairing ----------------------------------------------------------------------

    /**
     * A pairing code from the page on [origin]: it pairs the tenant AT that
     * origin and no other (origins are unique per tenant, so this is exact).
     */
    private fun confirmPairing(origin: String, code: String, expiresAt: Instant?) {
        lifecycleScope.launch {
            val t = app.tenants.read().byOrigin(origin) ?: return@launch
            val msg = getString(R.string.pair_confirm, origin) +
                if (t.device.paired) "\n\n" + getString(R.string.pair_replaces) else ""
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.pair_title)
                .setMessage(msg)
                .setPositiveButton(R.string.pair_ok) { _, _ -> pair(t.id, origin, code, expiresAt) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pair(id: String, origin: String, code: String, expiresAt: Instant?) {
        lifecycleScope.launch {
            // Pairing re-checks expires_at now, at the tap, before anything else.
            val out = app.pairing(id, origin).pair(code, expiresAt, MusterApp.deviceName(), appVersion())
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
                refreshPush(id, registerIfNeeded = true)
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

    /**
     * Reads tenant [id]'s /api/device/config (and so notices a revoked
     * pairing); registers its push instance when asked.
     */
    private fun refreshPush(id: String, registerIfNeeded: Boolean) {
        lifecycleScope.launch {
            val t = app.tenants.read().byId(id) ?: return@launch
            val registrar = app.registrarFor(id)
            val instance = t.pushInstance
            val r = registrar.refreshConfig()
            if (r is PushRegistrar.ConfigResult.Revoked) {
                runCatching { MusterPushService.unregister(this@MainActivity, instance) }
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
                    ok -> MusterPushService.register(this@MainActivity, instance, r.vapidKey)
                    UnifiedPush.getDistributors(this@MainActivity).isNotEmpty() ->
                        UnifiedPush.tryPickDistributor(this@MainActivity) { picked ->
                            if (picked) MusterPushService.register(this@MainActivity, instance, r.vapidKey)
                            else lifecycleScope.launch { registrar.noDistributor() }
                        }
                    else -> lifecycleScope.launch { registrar.noDistributor() }
                }
            }
        }
    }

    // --- servers and phone settings ----------------------------------------------------

    /**
     * Server management, in this activity (the switcher's long press, Manage
     * servers, the launcher shortcut). [detail]: open that server's phone
     * settings directly. Back returns to the shell.
     */
    private fun showServers(detail: String? = null) {
        dropWebView()
        screenBack.isEnabled = true // back: the shell (or, with no server left, the first-run screen)
        setContent {
            MaterialTheme {
                NativeSystemBars()
                val tenants by app.tenants.tenants.collectAsState(initial = null)
                val brands by remember { app.shownBrands() }.collectAsState(initial = emptyMap())
                var open by remember { mutableStateOf(detail) }
                val all = tenants ?: return@MaterialTheme
                val t = all.byId(open)
                if (t != null) {
                    PhoneSettingsScreen(
                        title = t.label,
                        records = app.store(t.id).records,
                        keyInvalidated = { keyInvalidated(t.id) },
                        notificationsEnabled = {
                            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
                        },
                        onNotificationSettings = ::openNotificationSettings,
                        onRetryPush = { refreshPush(t.id, registerIfNeeded = true) },
                        onUnpair = { unpair(t.id) },
                        onRemoveServer = { open = null; removeServer(t.id) },
                        onClose = { open = null },
                    )
                } else {
                    ServersScreen(
                        tenants = all,
                        onOpen = { id -> lifecycleScope.launch { app.tenants.select(id); route(Intent()) } },
                        onSettings = { id -> open = id },
                        onRemove = ::removeServer,
                        onAdd = { showServerUrl(adding = true) },
                        onClose = if (all.all.isNotEmpty()) ({ lifecycleScope.launch { route(Intent()) } }) else null,
                        brands = brands,
                    )
                }
            }
        }
    }

    private suspend fun keyInvalidated(id: String): Boolean = withContext(Dispatchers.IO) {
        val alias = app.store(id).read().keyAlias
        alias != null && runCatching { app.keys.state(alias) }.getOrNull() != dev.zacx.muster.core.auth.StepUpKeys.KeyState.VALID
    }

    /** Unpairs this phone from tenant [id] only; the server stays, to pair again. */
    private fun unpair(id: String) {
        lifecycleScope.launch {
            app.ops.unpair(id)
            Toast.makeText(this@MainActivity, R.string.unpaired, Toast.LENGTH_LONG).show()
        }
    }

    /** Removes tenant [id] (TenantOps.remove); with none left, back to the first-run screen. */
    private fun removeServer(id: String) {
        lifecycleScope.launch {
            val gone = app.ops.remove(id) ?: return@launch
            Toast.makeText(this@MainActivity, getString(R.string.server_removed, gone.label), Toast.LENGTH_LONG).show()
            if (app.tenants.read().all.isEmpty()) route(Intent())
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
        shownTenant = null
        shownProfile = null
        back.onWebViewChanged()
    }

    companion object {
        const val ACTION_SETTINGS = "dev.zacx.muster.SETTINGS"
        const val EXTRA_PATH = "path"

        /** How long Add server waits for the new server's sign-in origins before opening it anyway. */
        const val SSO_READ_TIMEOUT_MS = 5_000L

        /** muster's dark theme-color (web: `<meta name="theme-color">`), the launcher icon's tile too. */
        const val SHELL_BACKGROUND = 0xFF110F1C.toInt()
    }
}
