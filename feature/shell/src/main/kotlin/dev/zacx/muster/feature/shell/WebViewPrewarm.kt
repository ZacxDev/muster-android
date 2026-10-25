package dev.zacx.muster.feature.shell

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewStartUpConfig
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts Chromium (the WebView provider) in a process that has no shell on
 * screen yet, so a later `WebView(context)` in that process does not pay for
 * it on the main thread: the background-refresh job and a shown push call it,
 * and an open while that process is still cached is then a warm start.
 *
 * `WebViewCompat.startUpWebView` loads the WebView provider's class loader on
 * [start]'s background executor. A WebView that supports asynchronous
 * start-up then finishes start-up there as well; an older one (the emulator's
 * WebView 133 among them) gets `WebSettings.getDefaultUserAgent`, which
 * creates the provider and runs Chromium's main-thread start-up tasks.
 *
 * ⚠ Not called at every process start. Measured on the emulator (WebView 133,
 * cold launches, interleaved A/B): from `Application.onCreate` it made the
 * shell's page commit ~65 ms sooner but delayed the activity's first frame
 * by ~150 ms (~80 ms with the class loader alone), because those main-thread
 * tasks land before that frame. The first frame is the one that shows the
 * snapshot, so it wins.
 *
 * Once per process: a second [start] does nothing.
 */
object WebViewPrewarm {
    /** Abstracts the library call, so the config the app passes is unit-tested. */
    fun interface Starter {
        fun startUp(context: Context, config: WebViewStartUpConfig, done: () -> Unit)
    }

    /** The real one: `WebViewCompat.startUpWebView` (experimental in androidx.webkit 1.17). */
    object Compat : Starter {
        @OptIn(WebViewCompat.ExperimentalAsyncStartUp::class)
        override fun startUp(context: Context, config: WebViewStartUpConfig, done: () -> Unit) {
            WebViewCompat.startUpWebView(context, config, WebViewCompat.WebViewStartUpCallback { done() })
        }
    }

    private val started = AtomicBoolean(false)

    /** True once [start] has run in this process. */
    val isStarted: Boolean get() = started.get()

    /**
     * Starts Chromium on [executor] (a new background thread per task by
     * default, which ends with it). [onDone] gets the time it took, on the
     * main thread (as the library calls back).
     * Returns false if this process already started it.
     */
    fun start(
        context: Context,
        starter: Starter = Compat,
        executor: Executor = Executor { r -> Thread(r, "webview-prewarm").start() },
        onDone: (Long) -> Unit = {},
    ): Boolean {
        if (!started.compareAndSet(false, true)) return false
        val t0 = SystemClock.uptimeMillis()
        // 🔴 The library runs its start-up task on this executor, outside any
        // try: a phone whose WebView is missing or mid-update throws there,
        // and an uncaught throw on that thread would kill the app. A failed
        // pre-warm costs nothing: the shell's WebView starts Chromium itself.
        val guarded = Executor { r -> executor.execute { runCatching { r.run() } } }
        val config = WebViewStartUpConfig.Builder(guarded)
            // On a WebView without async start-up this is what creates the
            // provider (off the main thread); without it only the class
            // loader would be warmed.
            .setShouldRunUiThreadStartUpTasks(true)
            .build()
        runCatching { starter.startUp(context.applicationContext, config) { onDone(SystemClock.uptimeMillis() - t0) } }
        return true
    }

    /** Tests only: forget that this process started it. */
    @VisibleForTesting
    fun resetForTest() = started.set(false)
}
