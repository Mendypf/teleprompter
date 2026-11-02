package io.github.mendypf.prompter

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The band's own size on the camera, set by pinching it. It is separate from the text size: the band keeps its size,
 * so bigger words show fewer lines in it and smaller words show more.
 */
object BandBox {
    private const val PREFS = "band_place"
    private const val KEY_W = "w"
    private const val KEY_H = "h"
    private const val SIDE_DP = 10f // room on each side of the band until it is pinched
    private const val START_LINES = 3 // the words' area holds this many lines at the normal text size, until a pinch
    const val LINE_GAP = 0.12f // space between lines, as a share of the text size

    /** The band's font: the phone's own, at one weight for every word. */
    val font: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)

    /** The screen less 10 dp each side: the band's width until it is pinched. */
    fun fullWidth(context: Context): Int = max(1, screenWidth(context) - 2 * dp(context, SIDE_DP))

    /** A pinch can widen the band all the way to the screen's edges. */
    fun maxWidth(context: Context): Int = max(1, screenWidth(context))

    fun minWidth(context: Context): Int = min(fullWidth(context), dp(context, 180f))

    fun minHeight(context: Context): Int = dp(context, 36f)

    fun maxHeight(context: Context): Int = max(minHeight(context), screenHeight(context) * 4 / 5)

    /** The band's width in pixels. */
    fun width(context: Context): Int {
        val w = prefs(context).getInt(KEY_W, 0)
        return if (w > 0) w.coerceIn(minWidth(context), maxWidth(context)) else fullWidth(context)
    }

    /** The height of the words' area in pixels: three lines at the normal text size until the band is pinched. */
    fun wordsHeight(context: Context): Int {
        val h = prefs(context).getInt(KEY_H, 0)
        if (h > 0) return h.coerceIn(minHeight(context), maxHeight(context))
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ScriptStore.DEFAULT_TEXT_SP, context.resources.displayMetrics)
        return START_LINES * lineStep(px)
    }

    fun save(context: Context, width: Int, wordsHeight: Int) {
        prefs(context).edit().putInt(KEY_W, width).putInt(KEY_H, wordsHeight).apply()
    }

    /** One line of words at [px], top to top, the way the band lays its lines out. */
    fun lineStep(px: Float): Int {
        val m = TextPaint().apply {
            typeface = font
            textSize = px
        }.fontMetricsInt
        return m.descent - m.ascent + (px * LINE_GAP).roundToInt()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).roundToInt()

    private fun screenWidth(context: Context): Int {
        val b = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        return if (b != null && b.width() > 0) b.width() else context.resources.displayMetrics.widthPixels
    }

    private fun screenHeight(context: Context): Int {
        val b = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        return if (b != null && b.height() > 0) b.height() else context.resources.displayMetrics.heightPixels
    }
}
