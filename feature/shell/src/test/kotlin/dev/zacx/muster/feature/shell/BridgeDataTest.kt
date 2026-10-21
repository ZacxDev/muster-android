package dev.zacx.muster.feature.shell

import androidx.webkit.WebMessageCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeDataTest {
    @Test
    fun aStringMessageIsRead() {
        assertEquals("""{"type":"open-notification-settings"}""", ShellWebView.bridgeData(WebMessageCompat("""{"type":"open-notification-settings"}""")))
    }

    /** An ArrayBuffer message is ignored, not read (getData() would throw on it). */
    @Test
    fun aNonStringMessageIsIgnored() {
        assertNull(ShellWebView.bridgeData(WebMessageCompat(byteArrayOf(1, 2, 3))))
    }
}
