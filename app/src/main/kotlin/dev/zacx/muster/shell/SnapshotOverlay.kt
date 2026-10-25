package dev.zacx.muster.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

/**
 * The last home-page frame ([ShellSnapshot]) on screen over the WebView while
 * the live page loads.
 *
 * - **It swallows taps:** a tap must never reach a live control under a stale
 *   picture of a different one.
 * - **It says it is loading:** a thin indeterminate bar in muster's accent
 *   runs along its top for as long as it is up.
 * - **It always goes:** [dismiss] is called by the shell when the page has
 *   painted, on any page finish and on a main-frame error; and whatever
 *   happens, [timeoutMs] after [show] it goes by itself, so a page that never
 *   commits cannot leave the user facing a picture that ignores every tap.
 */
class SnapshotOverlay(
    context: Context,
    picture: Bitmap,
    background: Int,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val onGone: (SnapshotOverlay) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { dismiss() }
    private var gone = false

    /** The picture drawn 1:1 from the top left (same width: SnapshotPolicy.showable), and the bar. */
    val view: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(background)
        isClickable = true // swallows every tap: see the class comment
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(
            ImageView(context).apply {
                setImageBitmap(picture)
                scaleType = ImageView.ScaleType.MATRIX
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(ACCENT)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
    }

    /** The loading bar (for tests). */
    internal val progress: ProgressBar get() = view.getChildAt(1) as ProgressBar

    val isShowing: Boolean get() = !gone && view.parent != null

    /** Puts the picture over everything in [parent] and arms the timeout. */
    fun show(parent: FrameLayout) {
        parent.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        handler.postDelayed(timeout, timeoutMs)
    }

    /** Takes the picture away; the first call only. */
    fun dismiss() {
        if (gone) return
        gone = true
        handler.removeCallbacks(timeout)
        (view.parent as? ViewGroup)?.removeView(view)
        onGone(this)
    }

    companion object {
        /**
         * The longest the picture stays up. On the measured edge path the live
         * page commits about 1.2 s after launch (TTFB up to 1.5 s in
         * production), so 5 s leaves room for a slow network while keeping a
         * page that never commits from holding a frozen screen for long.
         */
        const val TIMEOUT_MS = 5_000L

        /** muster's dark-theme accent, `--mu-accent` (#E4B556). */
        const val ACCENT = 0xFFE4B556.toInt()
    }
}
