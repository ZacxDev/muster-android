package dev.zacx.muster.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Which page is pictured, and when a stored picture may be shown. Pure. */
class SnapshotPolicyTest {
    private val o = "https://m.example.test"

    @Test
    fun onlyTheExactHomePageIsPictured() {
        assertTrue(SnapshotPolicy.isHome("$o/", o))
        assertTrue(SnapshotPolicy.isHome("https://M.Example.test:443/", o))
        listOf(
            null,
            o, // no path: never what the WebView reports for the home page, and not "/"
            "$o/login", // signed out
            "$o/tasks",
            "$o/?status=open", // a filtered board is not what the launcher opens
            "$o/#x",
            "http://m.example.test/",
            "https://m.example.test:8443/",
            "https://other.example.test/",
            "https://user@m.example.test/",
            "$o/%zz",
        ).forEach { assertFalse("$it", SnapshotPolicy.isHome(it, o)) }
    }

    @Test
    fun theSignInPageIsRecognisedOnThisServerOnly() {
        assertTrue(SnapshotPolicy.isSignIn("$o/login", o))
        assertTrue(SnapshotPolicy.isSignIn("$o/login?next=%2F", o))
        listOf(null, "$o/", "$o/login/x", "$o/account", "https://other.example.test/login", "http://m.example.test/login")
            .forEach { assertFalse("$it", SnapshotPolicy.isSignIn(it, o)) }
    }

    @Test
    fun aPictureIsShownOnlyForTheSameServerAndScreenShape() {
        val m = ShellSnapshot.Meta(o, 1, 411)
        assertTrue(SnapshotPolicy.showable(m, ShellSnapshot.Meta(o, 1, 411)))
        assertFalse(SnapshotPolicy.showable(m, ShellSnapshot.Meta("https://other.example.test", 1, 411)))
        assertFalse(SnapshotPolicy.showable(m, ShellSnapshot.Meta(o, 2, 411))) // rotated
        assertFalse(SnapshotPolicy.showable(m, ShellSnapshot.Meta(o, 1, 600))) // another width (fold, display size)
    }

    @Test
    fun metaRoundTripsAndAnythingElseReadsAsNone() {
        val m = ShellSnapshot.Meta(o, 2, 731)
        assertEquals(m, SnapshotPolicy.decode(SnapshotPolicy.encode(m)))
        listOf("", "1\n$o\n", "2\n$o\n1\n411\n", "1\n$o\nx\n411\n", "1\n$o\n1\n").forEach {
            assertNull(it, SnapshotPolicy.decode(it))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShellSnapshotTest {
    private val dir = File(ApplicationProvider.getApplicationContext<Application>().noBackupFilesDir, "t")
    private val m = ShellSnapshot.Meta("https://m.example.test", 1, 411)

    private fun bitmap() = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(17, 15, 28)) }

    @Test
    fun savedThenReadThenDeleted() {
        val s = ShellSnapshot(dir)
        assertNull(s.read())
        s.save(bitmap(), m)
        val r = s.read()
        assertNotNull(r)
        assertEquals(m, r!!.meta)
        assertEquals(40, r.bitmap.width)
        assertEquals(80, r.bitmap.height)
        s.delete()
        assertNull(s.read())
        assertFalse(File(dir, "shell-snapshot.jpg").exists())
    }

    /** The meta is written last: an image without one is no snapshot. */
    @Test
    fun anImageWithoutItsMetaIsNone() {
        val s = ShellSnapshot(dir)
        s.save(bitmap(), m)
        File(dir, "shell-snapshot.meta").delete()
        assertNull(s.read())
    }
}
