package dev.zacx.muster.tenants

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.net.InstanceApi
import dev.zacx.muster.core.net.PngCheck
import dev.zacx.muster.feature.notify.AndroidNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tenant [t]'s logo on ITS notifications ([TenantBranding.brandFor], so bound
 * to [t]'s id and origin), and a push's new logo etag handed to
 * [TenantBranding.onIconEtag] on [scope] (never on the push thread).
 */
fun TenantBranding.notifyBranding(t: TenantRecord, scope: CoroutineScope) = object : AndroidNotifier.NotifyBranding {
    override fun largeIcon() = BrandBitmaps.load(brandFor(t)?.icon)
    override fun onIconEtag(etag: String) {
        scope.launch { runCatching { this@notifyBranding.onIconEtag(t, etag) } }
    }
}

/**
 * Decodes a tenant's cached icon for the switcher and the notification's
 * large icon, with bounds: the PNG's IHDR is checked first ([PngCheck]: a PNG,
 * at most 2048 px a side), the file at most 1 MiB, and the decode is
 * subsampled so neither side exceeds [maxPx]. Anything else is null (the
 * default icon), never a crash.
 */
object BrandBitmaps {
    /** The largest side decoded: 64 dp at xxxhdpi, enough for a notification's large icon. */
    const val MAX_PX = 256

    private val cache = object : LruCache<String, Bitmap>(8 * MAX_PX * MAX_PX * 4) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** [file] decoded (cached by path: a new logo has a new file name), or null. */
    fun load(file: File?, maxPx: Int = MAX_PX): Bitmap? {
        if (file == null) return null
        val key = "${file.absolutePath}@$maxPx"
        cache.get(key)?.let { return it }
        return decode(file, maxPx)?.also { cache.put(key, it) }
    }

    fun decode(file: File, maxPx: Int = MAX_PX): Bitmap? = try {
        if (!file.isFile || file.length() > InstanceApi.MAX_ICON_BYTES) null
        else decode(file.readBytes(), maxPx)
    } catch (e: Exception) {
        null
    }

    fun decode(bytes: ByteArray, maxPx: Int = MAX_PX): Bitmap? {
        val (w, h) = PngCheck.dimensions(bytes) ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, h, maxPx) }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** The power-of-two subsampling that brings both sides to at most [maxPx]. */
    fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        while (w / s > maxPx || h / s > maxPx) s *= 2
        return s
    }
}
