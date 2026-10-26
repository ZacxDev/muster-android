package dev.zacx.muster.shell

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.zacx.muster.MusterApp
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.DeviceStore
import dev.zacx.muster.core.net.DeviceApi
import dev.zacx.muster.feature.push.PushRegistrar
import dev.zacx.muster.refresh.RefreshSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

private class MemStore(initial: DeviceRecord) : DeviceStore {
    private val state = MutableStateFlow(initial)
    override val records: Flow<DeviceRecord> = state
    val value get() = state.value
    override suspend fun update(f: (DeviceRecord) -> DeviceRecord): DeviceRecord {
        state.value = f(state.value)
        return state.value
    }
}

private fun picture() = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(17, 15, 28)) }

/**
 * When the shell's picture and background job go: through the same calls the
 * activity, the job and the push registrar make, against a real snapshot on
 * disk and WorkManager's own test driver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShellLocalStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.noBackupFilesDir, "local-state-test")
    private val origin = "https://m.example.test"
    private val token = "mdv_" + "T".repeat(43)
    private lateinit var wm: WorkManager
    private lateinit var local: ShellLocalState

    @Before
    fun setUp() {
        dir.deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
        local = ShellLocalState(ShellSnapshot(dir), { wm })
    }

    @After
    fun tearDown() {
        WorkManagerImpl.setDelegate(null)
        dir.deleteRecursively()
    }

    private fun saved() = local.snapshot.save(picture(), ShellSnapshot.Meta(origin, 1, 411))
    private val image get() = File(dir, "shell-snapshot.jpg")
    private fun jobState() = wm.getWorkInfosForUniqueWork(RefreshSchedule.NAME).get().singleOrNull()?.state
    private fun scheduled() = RefreshSchedule.ensure(wm)

    @Test
    fun signInDeletesThePictureAndTheHomePageKeepsIt() = runBlocking {
        saved()
        local.onCommit("$origin/", origin, homeLoad = true)
        assertTrue("control: the home page landing home keeps it", image.exists())
        local.onCommit("$origin/tasks", origin, homeLoad = false)
        assertTrue("control: another page, not the home load", image.exists())
        local.onCommit("$origin/login?next=%2F", origin, homeLoad = false)
        assertFalse("reaching sign-in deletes it", image.exists())
        assertNull(local.snapshot.read())
    }

    @Test
    fun theHomeLoadLandingElsewhereDeletesThePicture() = runBlocking {
        saved()
        local.onCommit("$origin/tasks", origin, homeLoad = true)
        assertFalse(image.exists())
    }

    @Test
    fun changingServerForgetsCookiesPictureJobAndServer() = runBlocking {
        val store = MemStore(DeviceRecord(origin = origin))
        saved()
        scheduled()
        assertEquals(WorkInfo.State.ENQUEUED, jobState())
        var cookies = 0
        assertTrue(local.changeServer(store) { cookies++ })
        assertEquals(1, cookies)
        assertFalse(image.exists())
        assertEquals(WorkInfo.State.CANCELLED, jobState())
        assertEquals(DeviceRecord(), store.value)
    }

    @Test
    fun aPairedPhoneCannotChangeServer() = runBlocking {
        val record = DeviceRecord(origin = origin, token = token, deviceId = 1)
        val store = MemStore(record)
        saved()
        scheduled()
        var cookies = 0
        assertFalse(local.changeServer(store) { cookies++ })
        assertEquals(0, cookies)
        assertTrue(image.exists())
        assertEquals(WorkInfo.State.ENQUEUED, jobState())
        assertEquals(record, store.value)
    }

    @Test
    fun unpairingDeletesThePictureAndCancelsTheJob() = runBlocking {
        val store = MemStore(DeviceRecord(origin = origin, token = token, deviceId = 1))
        saved()
        scheduled()
        local.markUnpaired(store)
        assertFalse(store.value.paired)
        assertEquals(origin, store.value.origin)
        assertFalse(image.exists())
        assertEquals(WorkInfo.State.CANCELLED, jobState())
    }

    @Test
    fun theJobIsScheduledOnlyForAPairedPhone() = runBlocking {
        local.syncRefresh(paired = false)
        assertNull(jobState())
        local.syncRefresh(paired = true)
        assertEquals(WorkInfo.State.ENQUEUED, jobState())
    }

    @Test
    fun cancelRefreshLeavesThePicture() = runBlocking {
        saved()
        scheduled()
        local.cancelRefresh()
        assertEquals(WorkInfo.State.CANCELLED, jobState())
        assertTrue(image.exists())
    }

    /**
     * muster ending the pairing, seen by the registrar the app builds (the
     * foreground's refreshPush and the background job both call it):
     * picture, job and key all go before the call returns.
     */
    @Test
    fun aRevocationSeenByTheAppsRegistrarForgetsPictureJobAndKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":"this route takes a paired device credential (mdv_…)"}""").build())
            val srv = server.url("/").toString().trimEnd('/')
            val store = MemStore(DeviceRecord(origin = srv, token = token, deviceId = 1, keyAlias = "muster-stepup-old"))
            saved()
            scheduled()
            val deleted = mutableListOf<String>()
            val r = MusterApp.newRegistrar(store, ::DeviceApi, { deleted += it }, local)
            assertEquals(PushRegistrar.ConfigResult.Revoked, r.refreshConfig())
            assertFalse(store.value.paired)
            assertEquals(listOf("muster-stepup-old"), deleted)
            assertFalse(image.exists())
            assertEquals(WorkInfo.State.CANCELLED, jobState())
        }
    }

    /** Control: a config read that is not a revocation keeps all of it. */
    @Test
    fun aConfigReadKeepsPictureAndJob() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"push":{"served":false,"vapid_public_key":"","hosts":[]},"features":{"push":false}}""").build())
            val srv = server.url("/").toString().trimEnd('/')
            val store = MemStore(DeviceRecord(origin = srv, token = token, deviceId = 1, keyAlias = "k"))
            saved()
            scheduled()
            val deleted = mutableListOf<String>()
            val r = MusterApp.newRegistrar(store, ::DeviceApi, { deleted += it }, local)
            assertEquals(PushRegistrar.ConfigResult.NoPush, r.refreshConfig())
            assertTrue(store.value.paired)
            assertEquals(emptyList<String>(), deleted)
            assertTrue(image.exists())
            assertEquals(WorkInfo.State.ENQUEUED, jobState())
        }
    }
}

/** The onPause cookie flush: only with a WebView, and never a crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShellCookiesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun noWebViewNoFlush() {
        var flushed = 0
        ShellCookies.flushOnPause(null) { flushed++ }
        assertEquals(0, flushed)
    }

    @Test
    fun aWebViewIsFlushed() {
        var flushed = 0
        ShellCookies.flushOnPause(WebView(context)) { flushed++ }
        assertEquals(1, flushed)
    }

    @Test
    fun aThrowingFlushDoesNotEscape() {
        ShellCookies.flushOnPause(WebView(context)) { throw IllegalStateException("WebView mid-update") }
        // Reaching here is the assertion.
    }
}

/** The picture on screen: it swallows taps, shows it is loading, and always goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SnapshotOverlayTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val page = FrameLayout(activity)
    private var taps = 0
    private val live = View(activity).apply { setOnClickListener { taps++ } }

    @Before
    fun setUp() {
        page.addView(live, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        activity.setContentView(page, FrameLayout.LayoutParams(200, 400))
    }

    private fun layout() {
        page.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 200, 400)
    }

    private fun tap() {
        layout()
        val t = SystemClock.uptimeMillis()
        page.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 200f, 0))
        page.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 100f, 200f, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun overlay(onGone: (SnapshotOverlay) -> Unit = {}) = SnapshotOverlay(activity, picture(), Color.BLACK, onGone = onGone)

    @Test
    fun aTapNeverReachesTheLivePageUnderThePicture() {
        tap()
        assertEquals("control: without the picture the tap lands", 1, taps)
        val o = overlay()
        o.show(page)
        tap()
        assertEquals("the picture swallowed it", 1, taps)
        o.dismiss()
        tap()
        assertEquals("gone: taps land again", 2, taps)
    }

    @Test
    fun itShowsALoadingBarWhileUp() {
        val o = overlay()
        o.show(page)
        assertTrue(o.isShowing)
        assertTrue(o.progress.isIndeterminate)
        assertEquals(View.VISIBLE, o.progress.visibility)
        assertTrue(o.progress.parent === o.view)
    }

    @Test
    fun itGoesByItselfAfterTheTimeout() {
        var gone = 0
        val o = overlay { gone++ }
        o.show(page)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SnapshotOverlay.TIMEOUT_MS - 100))
        assertTrue("not yet", o.isShowing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(o.isShowing)
        assertNull(o.view.parent)
        assertEquals(1, gone)
    }

    @Test
    fun dismissedOnceOnly() {
        var gone = 0
        val o = overlay { gone++ }
        o.show(page)
        o.dismiss()
        o.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SnapshotOverlay.TIMEOUT_MS * 2))
        assertEquals(1, gone)
        assertNotNull(page.getChildAt(0))
        assertEquals(1, page.childCount) // only the live page is left
    }
}
