package dev.zacx.muster.tenants

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** A cached icon is decoded with bounds: subsampled to at most MAX_PX a side, and anything not a sane PNG is null. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class BrandBitmapsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun encoded(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return ByteArrayOutputStream().also { b.compress(format, 90, it) }.toByteArray()
    }

    @Test
    fun aSmallIconDecodesAtItsSize() {
        val bmp = BrandBitmaps.decode(encoded(192, 192))!!
        assertEquals(192 to 192, bmp.width to bmp.height)
        assertEquals(Color.BLUE, bmp.getPixel(10, 10))
    }

    /** A large icon is subsampled while decoding, never decoded at full size. */
    @Test
    fun aLargeIconIsSubsampled() {
        val bmp = BrandBitmaps.decode(encoded(1024, 512), maxPx = 256)!!
        assertEquals(256 to 128, bmp.width to bmp.height)
    }

    @Test
    fun theSampleSize() {
        assertEquals(1, BrandBitmaps.sampleSize(256, 256, 256))
        assertEquals(2, BrandBitmaps.sampleSize(257, 10, 256))
        assertEquals(8, BrandBitmaps.sampleSize(2048, 2048, 256))
    }

    /** Not a PNG (a JPEG, garbage, an empty or missing file): null, the default icon. */
    @Test
    fun anythingElseIsNull() {
        assertNull(BrandBitmaps.decode(encoded(64, 64, Bitmap.CompressFormat.JPEG)))
        assertNull(BrandBitmaps.decode(ByteArray(100) { 7 }))
        assertNull(BrandBitmaps.decode(tmp.newFile("empty.png")))
        assertNull(BrandBitmaps.decode(tmp.root.resolve("missing.png")))
        assertNull(BrandBitmaps.load(null))
    }

    /** A file over 1 MiB is not read at all. */
    @Test
    fun anOversizedFileIsNotRead() {
        val f = tmp.newFile("big.png")
        f.writeBytes(encoded(16, 16) + ByteArray((1 shl 20) + 1))
        assertNull(BrandBitmaps.decode(f))
    }
}
