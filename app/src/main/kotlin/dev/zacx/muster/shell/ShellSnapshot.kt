package dev.zacx.muster.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacx.muster.core.model.LenientUrl
import dev.zacx.muster.feature.shell.OriginPolicy
import java.io.File

/**
 * The last frame of muster's home page, kept so the next launch shows it at
 * once instead of a blank screen, until the live page paints over it.
 *
 * What it is and is not:
 *
 * - **A picture, never a page.** It is a JPEG of what the WebView last showed
 *   at `<origin>/`, taken when the app left the foreground. The live page
 *   replaces it on its first painted frame; until then it swallows touches,
 *   so a tap can never land on a live control that sits where the picture
 *   showed a different one.
 * - **Only the home page, only for the server it was taken on.** The
 *   launcher opens `<origin>/`, so that is the only page pictured
 *   ([SnapshotPolicy]). A notification tap opens another page and shows no
 *   picture.
 * - **Dropped when it would be wrong:** any page of the shell landing on
 *   muster's sign-in page (sign-out, an expired session) and the home load
 *   landing anywhere but `/` delete it; "change server" deletes it; a
 *   different server or screen shape is never shown.
 * - **Stored like the WebView's own cache:** app-private, in
 *   `noBackupFilesDir`, so never in a backup. Native code never reads the
 *   page or its cookies; this is pixels the user already saw.
 */
class ShellSnapshot(private val dir: File) {
    data class Meta(val origin: String, val orientation: Int, val widthDp: Int)

    private val image get() = File(dir, "shell-snapshot.jpg")
    private val meta get() = File(dir, "shell-snapshot.meta")

    /** Writes [bitmap] as the snapshot of [m]'s origin. Call off the main thread. */
    fun save(bitmap: Bitmap, m: Meta) {
        dir.mkdirs()
        val tmp = File(dir, "shell-snapshot.jpg.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        // The meta is written last, so a half-written pair reads as no snapshot.
        meta.delete()
        if (!tmp.renameTo(image)) { tmp.delete(); return }
        meta.writeText(SnapshotPolicy.encode(m))
    }

    class Stored(val meta: Meta, val bitmap: Bitmap)

    /**
     * The stored snapshot, or null. Call off the main thread. The caller
     * checks [SnapshotPolicy.showable] before showing it: the origin is only
     * known once the device record is read, which happens at the same time.
     */
    fun read(hardware: Boolean = false): Stored? {
        val m = runCatching { SnapshotPolicy.decode(meta.readText()) }.getOrNull() ?: return null
        // A HARDWARE bitmap is uploaded to the GPU here, on the caller's
        // (background) thread, not by the render thread on the first frame
        // that draws it. Not every device can; then a plain one.
        val hw = if (hardware) decode(BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }) else null
        val b = hw ?: decode(null) ?: return null
        return Stored(m, b)
    }

    private fun decode(o: BitmapFactory.Options?): Bitmap? = runCatching { BitmapFactory.decodeFile(image.path, o) }.getOrNull()

    fun delete() {
        meta.delete()
        image.delete()
    }

    companion object {
        /** Text at this quality reads cleanly for the second or so it is shown. */
        const val QUALITY = 80
    }
}

/** The snapshot's rules, pure so they are unit-tested. */
object SnapshotPolicy {
    /** The page the launcher opens, the only one pictured: `<origin>/`, no query, no fragment. */
    fun isHome(url: String?, origin: String): Boolean {
        if (url == null) return false
        val policy = OriginPolicy.forServer(origin) ?: return false
        if (!policy.allowsInWebView(url)) return false
        val u = LenientUrl.parse(url) ?: return false
        return u.rawPath == "/" && u.rawQuery == null && u.rawFragment == null
    }

    /**
     * muster's sign-in page (`GET /login`; sign-out and an expired session
     * land there). Reaching it deletes the snapshot, as muster's own pages
     * purge their per-user stores on it: the next open must not show a
     * signed-in board to whoever opens the app now.
     */
    fun isSignIn(url: String?, origin: String): Boolean {
        if (url == null) return false
        val policy = OriginPolicy.forServer(origin) ?: return false
        if (!policy.allowsInWebView(url)) return false
        return LenientUrl.parse(url)?.rawPath == SIGN_IN_PATH
    }

    const val SIGN_IN_PATH = "/login"

    /** The stored snapshot may be shown for [want]: same server, same screen shape. */
    fun showable(stored: ShellSnapshot.Meta, want: ShellSnapshot.Meta): Boolean = stored == want

    fun encode(m: ShellSnapshot.Meta): String = "1\n${m.origin}\n${m.orientation}\n${m.widthDp}\n"

    fun decode(s: String): ShellSnapshot.Meta? {
        val l = s.split('\n')
        if (l.size < 4 || l[0] != "1") return null
        return ShellSnapshot.Meta(l[1], l[2].toIntOrNull() ?: return null, l[3].toIntOrNull() ?: return null)
    }
}
