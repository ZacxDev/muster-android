package dev.zacx.muster

import android.webkit.WebView
import dev.zacx.muster.feature.shell.ShellWebView

/** The release build's startup probe: does nothing (the debug one logs; src/debug). */
object StartupProbe {
    @Suppress("UNUSED_PARAMETER")
    fun mark(phase: String) {}

    @Suppress("UNUSED_PARAMETER")
    fun attach(view: WebView, origin: String) {}

    @Suppress("UNUSED_PARAMETER")
    fun page(view: WebView, event: ShellWebView.PageEvent, url: String) {}
}
