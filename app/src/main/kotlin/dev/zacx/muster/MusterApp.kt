package dev.zacx.muster

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.zacx.muster.core.auth.AesGcmAead
import dev.zacx.muster.core.auth.DataStoreDeviceStore
import dev.zacx.muster.core.auth.DataStoreTenantsFile
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.DeviceStore
import dev.zacx.muster.core.auth.KeyKind
import dev.zacx.muster.core.auth.KeystoreStepUpKeys
import dev.zacx.muster.core.auth.Pairing
import dev.zacx.muster.core.auth.StepUpKeys
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.TenantRepository
import dev.zacx.muster.core.model.AskOutcome
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.core.net.InstanceApi
import dev.zacx.muster.feature.notify.AndroidNotifier
import dev.zacx.muster.feature.notify.Channels
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.feature.push.TenantPushRouter
import dev.zacx.muster.feature.shell.TenantSessions
import dev.zacx.muster.feature.shell.WebViewProfiles
import dev.zacx.muster.feature.stepup.StepUpActivity
import dev.zacx.muster.feature.stepup.StepUpHost
import dev.zacx.muster.push.MusterPushService
import dev.zacx.muster.shell.ShellLocalState
import dev.zacx.muster.shell.ShellSnapshot
import dev.zacx.muster.tenants.BrandBitmaps
import dev.zacx.muster.tenants.TenantBranding
import dev.zacx.muster.tenants.TenantOps
import dev.zacx.muster.tenants.notifyBranding
import dev.zacx.muster.tenants.notifyTenant
import dev.zacx.muster.ui.ShownBrand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Wires the modules together. One instance of each store per process. */
class MusterApp : Application(), Configuration.Provider, StepUpHost {
    /**
     * WorkManager starts on first use, not at every process start (its
     * App Startup initializer is removed in the manifest): a cold start of
     * the shell must not pay for it.
     */
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val aead by lazy { AesGcmAead.androidKeystore() }

    /**
     * Every muster server this phone is paired with (core/auth Tenants), in
     * noBackupFilesDir: never part of Auto Backup, whatever the rules say. The
     * single-server `device.sealed` is read once, migrated into tenant
     * "default", and deleted; nothing writes it.
     */
    val tenants: TenantRepository by lazy {
        val legacy = File(noBackupFilesDir, "device.sealed")
        TenantRepository(
            DataStoreTenantsFile(File(noBackupFilesDir, "tenants.sealed"), aead, scope),
            readLegacy = { if (legacy.exists()) DataStoreDeviceStore(legacy, aead, scope).read() else DeviceRecord() },
            deleteLegacy = { withContext(Dispatchers.IO) { legacy.delete() } },
        )
    }

    /** Tenant [id]'s pairing, and nothing else. */
    fun store(id: String): DeviceStore = tenants.deviceStore(id)

    val keys: StepUpKeys by lazy { KeystoreStepUpKeys(this) }

    /** Each tenant's pages in its own WebView profile (feature/shell TenantSessions). */
    val profiles by lazy { WebViewProfiles() }
    val sessions by lazy { TenantSessions(profiles) }

    /**
     * Each tenant's display name and logo (muster `/api/instance`), cached in
     * noBackupFilesDir/brand/<tenant id>/. Read on the first start of a
     * process, when a server is added, and by the background job; never on
     * the UI thread, never blocking it.
     */
    val branding: TenantBranding by lazy { TenantBranding(File(noBackupFilesDir, "brand"), ::InstanceApi) }

    @Volatile private var brandingRead = false

    /** Refreshes every tenant's branding once per process (each read honours the server's Cache-Control). */
    fun refreshBrandingOnce() {
        if (brandingRead) return
        brandingRead = true
        scope.launch { runCatching { branding.refreshAll(tenants.read()) } }
    }

    /**
     * What the switcher shows for each tenant (by id): its server's display
     * name and logo, decoded off the main thread with bounds. Re-emitted when
     * the tenants or any branding change; a tenant with none is left out.
     */
    fun shownBrands(): Flow<Map<String, ShownBrand>> =
        combine(tenants.tenants, branding.changes) { t, _ ->
            branding.brands(t).mapValues { (_, b) -> ShownBrand(b.name, BrandBitmaps.load(b.icon)?.asImageBitmap()) }
        }.flowOn(Dispatchers.IO)

    /** Tenant [t]'s notifier: its label, colour and logo on every notification, its taps and buttons for it only. */
    fun notifierFor(t: TenantRecord) = AndroidNotifier(
        this,
        t.notifyTenant(),
        action = { tag, i, title, path, a -> StepUpActivity.pendingIntent(this, t.id, tag, i, title, path, a) },
        branding = branding.notifyBranding(t),
        tap = { tag, path -> tapPendingIntent(this, t.id, tag, path) },
    )

    override suspend fun stepUpPairing(tenant: String): StepUpHost.Pairing? {
        val r = tenants.read().byId(tenant)?.device ?: return null
        val origin = r.origin ?: return null
        val token = r.token ?: return null
        return StepUpHost.Pairing(origin, token, r.keyAlias, KeyKind.fromWire(r.keyKind) == KeyKind.BIOMETRIC_OR_CREDENTIAL)
    }

    override fun showOutcome(tenant: String, tag: String, title: String, path: String?, outcome: AskOutcome) {
        scope.launch {
            val t = tenants.read().byId(tenant) ?: return@launch
            notifierFor(t).showOutcome(tag, title, path, outcome)
        }
    }

    val pushRouter by lazy { TenantPushRouter(::notifierFor) }

    /** The shell's picture and background job, and when they go ([ShellLocalState]). */
    val local by lazy { ShellLocalState(ShellSnapshot(noBackupFilesDir), { WorkManager.getInstance(this) }) }

    /** Tenant [id]'s push registrar: reads and writes that tenant's pairing only. */
    fun registrarFor(id: String) = newRegistrar(
        store(id), ::DeviceApi, { a -> keys.delete(a) }, local,
        stillPaired = { tenants.read().all.any { it.id != id && it.device.paired } },
    )

    /** Pairing into tenant [id], against its own origin. */
    fun pairing(id: String, origin: String) = Pairing(DeviceApi(origin), keys, store(id))

    val ops by lazy {
        TenantOps(
            tenants, sessions, { profiles.multiProfile }, ::DeviceApi, { a -> keys.delete(a) },
            unregisterPush = { instance -> MusterPushService.unregister(this, instance) },
            dropNotifications = { t ->
                notifierFor(t).cancelAll()
                Channels.delete(this, t.notifyTenant())
                scope.launch { runCatching { branding.forget(t.id) } }
            },
            local = local,
        )
    }

    override fun onCreate() {
        StartupProbe.mark("app.onCreate.start")
        super.onCreate()
        // Each tenant's channels (in its group) are created when the tenants
        // are first read (MainActivity) and before every post (AndroidNotifier).
        StartupProbe.mark("app.onCreate.end")
    }

    companion object {
        /** The tap intent's extra naming its tenant. */
        const val EXTRA_TENANT = "dev.zacx.muster.TENANT"

        /**
         * A notification tap: open tenant [tenant]'s shell at [path], immutable
         * and explicit. The tenant and tag go into the intent's DATA
         * (`muster-tap://<tenant>/<tag>`) as well as the request code:
         * PendingIntents are told apart by request code AND Intent.filterEquals
         * (which ignores extras), so two tags whose hash codes collide
         * ("Aa" and "BB"), or equal tags from two servers, would otherwise
         * share one PendingIntent, and FLAG_UPDATE_CURRENT would make the first
         * notification open the second one's path (or server).
         */
        fun tapPendingIntent(context: Context, tenant: String, tag: String, path: String?): PendingIntent {
            val i = Intent(context, MainActivity::class.java)
                .setData(android.net.Uri.Builder().scheme("muster-tap").authority(tenant).appendPath(tag).build())
                .putExtra(EXTRA_TENANT, tenant)
                .putExtra(MainActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context, 31 * tenant.hashCode() + tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun from(c: Context) = c.applicationContext as MusterApp

        /**
         * One tenant's push registrar, wired so that muster ending that
         * pairing (a 401 seen in the foreground, by the background job or at
         * push registration) deletes its step-up key and forgets the shell's
         * picture and, unless another tenant is still paired ([stillPaired]),
         * the job ([ShellLocalState.forget]) before the call returns.
         */
        fun newRegistrar(
            store: DeviceStore,
            api: (String) -> DeviceApi,
            deleteKey: (String) -> Unit,
            local: ShellLocalState,
            stillPaired: suspend () -> Boolean = { false },
        ) = PushRegistrar(store, api, onForget = { old ->
            old.keyAlias?.let { a -> withContext(Dispatchers.IO) { runCatching { deleteKey(a) } } }
            local.forget(cancelRefresh = !stillPaired())
        })

        fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android phone" }
    }
}
