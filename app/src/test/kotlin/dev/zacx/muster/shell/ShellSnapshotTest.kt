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
    fun aCommitDeletesThePictureOnSignInOrWhenTheHomeLoadLandsElsewhere() {
        // Any page reaching sign-in, home load or not.
        assertTrue(SnapshotPolicy.dropsOnCommit("$o/login", o, homeLoad = false))
        assertTrue(SnapshotPolicy.dropsOnCommit("$o/login", o, homeLoad = true))
        // The home load landing anywhere but home.
        assertTrue(SnapshotPolicy.dropsOnCommit("$o/tasks", o, homeLoad = true))
        assertTrue(SnapshotPolicy.dropsOnCommit("https://other.example.test/", o, homeLoad = true))
        // Kept: the home load landing home, and any later page that is not sign-in.
        assertFalse(SnapshotPolicy.dropsOnCommit("$o/", o, homeLoad = true))
        assertFalse(SnapshotPolicy.dropsOnCommit("$o/tasks", o, homeLoad = false))
        assertFalse(SnapshotPolicy.dropsOnCommit("$o/", o, homeLoad = false))
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

    private val HOUR = 60L * 60 * 1000

    @org.junit.Before
    fun clean() { dir.deleteRecursively() }

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

    /** A picture older than a day is deleted unshown; a younger one is shown. */
    @Test
    fun aStalePictureIsDeletedWhenRead() {
        ShellSnapshot(dir).save(bitmap(), m)
        val taken = File(dir, "shell-snapshot.jpg").lastModified()
        // 23 h on: still shown.
        assertNotNull(ShellSnapshot(dir) { taken + 23 * HOUR }.read())
        // 25 h on: deleted, both files.
        assertNull(ShellSnapshot(dir) { taken + 25 * HOUR }.read())
        assertFalse(File(dir, "shell-snapshot.jpg").exists())
        assertFalse(File(dir, "shell-snapshot.meta").exists())
    }

    /** The clock moved back past the picture: its age is unknown, so it goes. */
    @Test
    fun aPictureFromTheFutureIsDeleted() {
        ShellSnapshot(dir).save(bitmap(), m)
        val taken = File(dir, "shell-snapshot.jpg").lastModified()
        assertNull(ShellSnapshot(dir) { taken - 3 * HOUR }.read())
        assertFalse(File(dir, "shell-snapshot.jpg").exists())
    }

    @Test
    fun pruneIfStaleKeepsAFreshPictureAndReportsNoneLeft() {
        val s0 = ShellSnapshot(dir)
        s0.save(bitmap(), m)
        val taken = File(dir, "shell-snapshot.jpg").lastModified()
        assertFalse(ShellSnapshot(dir) { taken + 2 * HOUR }.pruneIfStale())
        assertTrue(File(dir, "shell-snapshot.jpg").exists())
        assertTrue(ShellSnapshot(dir) { taken + 30 * HOUR }.pruneIfStale())
        assertFalse(File(dir, "shell-snapshot.jpg").exists())
        assertTrue("nothing there: none left", s0.pruneIfStale())
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
