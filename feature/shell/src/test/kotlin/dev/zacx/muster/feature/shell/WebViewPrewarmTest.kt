package dev.zacx.muster.feature.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewStartUpConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewPrewarmTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Recording : WebViewPrewarm.Starter {
        val calls = mutableListOf<Pair<Context, WebViewStartUpConfig>>()
        var done: (() -> Unit)? = null
        override fun startUp(context: Context, config: WebViewStartUpConfig, done: () -> Unit) {
            calls += context to config
            this.done = done
        }
    }

    @Before fun reset() = WebViewPrewarm.resetForTest()
    @After fun after() = WebViewPrewarm.resetForTest()

    @Test
    fun startsChromiumWithItsMainThreadTasksOnTheApplicationContext() {
        val s = Recording()
        assertTrue(WebViewPrewarm.start(context, s))
        assertEquals(1, s.calls.size)
        val (ctx, config) = s.calls.single()
        assertSame(context.applicationContext, ctx)
        // Without this an old WebView only gets its class loader warmed.
        assertTrue(config.shouldRunUiThreadStartUpTasks())
        assertTrue(WebViewPrewarm.isStarted)
    }

    @Test
    fun theDefaultExecutorRunsOffTheCallingThread() {
        val s = Recording()
        WebViewPrewarm.start(context, s)
        val ran = CountDownLatch(1)
        var on: Thread? = null
        s.calls.single().second.backgroundExecutor.execute { on = Thread.currentThread(); ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertNotSame(Thread.currentThread(), on)
        assertEquals("webview-prewarm", on!!.name)
    }

    @Test
    fun oncePerProcess() {
        val s = Recording()
        assertTrue(WebViewPrewarm.start(context, s))
        assertFalse(WebViewPrewarm.start(context, s))
        assertEquals(1, s.calls.size)
    }

    /** 🔴 A WebView that throws during start-up (missing, mid-update) must not kill the app. */
    @Test
    fun aThrowingStartUpTaskIsContained() {
        val s = Recording()
        WebViewPrewarm.start(context, s, executor = Executor { it.run() })
        s.calls.single().second.backgroundExecutor.execute { throw IllegalStateException("no WebView") }
        // Reaching here is the assertion: the throw did not escape the executor.
    }

    @Test
    fun aThrowingStarterIsContained() {
        assertTrue(WebViewPrewarm.start(context, { _, _, _ -> throw IllegalStateException("no WebView") }))
    }

    @Test
    fun reportsWhenDone() {
        val s = Recording()
        var took = -1L
        WebViewPrewarm.start(context, s, onDone = { took = it })
        assertEquals(-1L, took)
        s.done!!.invoke()
        assertTrue(took >= 0)
    }
}
