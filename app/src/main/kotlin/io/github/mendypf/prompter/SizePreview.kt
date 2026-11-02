package io.github.mendypf.prompter

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The band as it will look on the camera, shrunk to fit the card and drawn straight on it: the same width and height
 * as the real band, the words at the chosen size and in the chosen look, the next word brightest, the progress line
 * and the handle. The band never changes size here, just as on the camera: bigger words show fewer lines.
 */
class SizePreview(context: Context) : View(context) {

    /** The text size in sp, as on the band. */
    var sizeSp = ScriptStore.DEFAULT_TEXT_SP
        set(value) {
            if (value != field) {
                field = value
                contentDescription = "Preview of the band at text size ${value.toInt()}"
                relayout()
            }
        }

    var look = Look.GLASS
        set(value) {
            if (value != field) {
                field = value
                relayout()
            }
        }

    private val density = resources.displayMetrics.density
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlowCard.TEXT
        typeface = BandBox.font
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.TRACK }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val band = RectF()
    private var card: Drawable? = null
    private var lines: StaticLayout? = null

    // The real band's size in pixels, and how much smaller it is drawn here.
    private var realWidth = 1
    private var realWords = 1
    private var scale = 1f

    // In this view's pixels: the words' area, and how much of it holds whole lines (the rest fades out).
    private var wordsHeight = 0f
    private var wholeHeight = 0f

    init {
        contentDescription = "Preview of the band at text size ${sizeSp.toInt()}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        readBand()
    }

    private fun dp(v: Float) = v * density

    private fun spPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    /** The band may have been pinched on the camera since: reads its size again. */
    fun bandChanged() {
        readBand()
        requestLayout()
        relayout()
    }

    private fun readBand() {
        realWidth = BandBox.width(context)
        realWords = BandBox.wordsHeight(context)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        scale = max(1f, w.toFloat()) / realWidth
        layoutFor(w)
        // Tall enough for the band at every text size (the biggest words still need one whole line), so the - and +
        // buttons above never move while they are being tapped.
        val tallest = max(realWords, BandBox.lineStep(spPx(ScriptStore.MAX_TEXT_SP)))
        setMeasuredDimension(w, (dp(MARGIN) * 2 + cardHeight(tallest * scale)).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeCard(w)
    }

    private fun relayout() {
        if (width > 0) {
            layoutFor(width)
            placeCard(width)
        }
        invalidate()
    }

    /** Lays the words out as the real band does, at its width and the chosen size, shrunk by [scale]. */
    private fun layoutFor(w: Int) {
        if (w <= 0) return
        val px = spPx(sizeSp)
        words.textSize = px * scale
        if (look == Look.CLEAR) words.setShadowLayer(dp(3f) * scale, 0f, dp(1f) * scale, 0xB3000000.toInt()) else words.clearShadowLayer()
        val text = SpannableString(SAMPLE).apply {
            setSpan(ForegroundColorSpan(GlowCard.KNOB), 0, SAMPLE.indexOf(' '), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val l = StaticLayout.Builder.obtain(text, 0, text.length, words, max(1, ((realWidth - dp(PAD_SIDE) * 2) * scale).toInt()))
            .setIncludePad(false)
            .setLineSpacing(words.textSize * BandBox.LINE_GAP, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        lines = l
        // The same count of whole lines as on the camera, worked out at the band's real size.
        val step = BandBox.lineStep(px)
        val area = max(realWords, step)
        val whole = area / step
        val shownStep = if (l.lineCount > 1) (l.getLineTop(1) - l.getLineTop(0)).toFloat() else step * scale
        wordsHeight = area * scale
        wholeHeight = minOf(wordsHeight, whole * shownStep)
        fade.shader = if (wholeHeight < wordsHeight) {
            LinearGradient(0f, wholeHeight, 0f, wordsHeight, FADE_START, 0, Shader.TileMode.CLAMP)
        } else {
            null
        }
    }

    /** The card around [wordsH] of words: padding, the words, the progress line, the handle, padding. */
    private fun cardHeight(wordsH: Float): Float = wordsH + dp(PAD_TOP + 8f + 4f + 2f + 20f + 2f) * scale

    private fun placeCard(w: Int) {
        val cw = realWidth * scale
        val ch = cardHeight(wordsHeight)
        val left = (w - cw) / 2f
        val top = (height - ch) / 2f
        band.set(left, top, left + cw, top + ch)
        val r = dp(18f) * scale
        card = (if (look == Look.GLOW) GlowCard(r) else GlassCard(r, clear = look == Look.CLEAR, seeThrough = true) { true }).apply {
            setBounds(band.left.roundToInt(), band.top.roundToInt(), band.right.roundToInt(), band.bottom.roundToInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        val l = lines ?: return
        card?.draw(canvas)
        val x = band.left + dp(PAD_SIDE) * scale
        val y = band.top + dp(PAD_TOP) * scale

        // The words: whole lines, then the next one fading out in whatever room is left, as on the band.
        val fades = fade.shader != null
        val saved = if (fades) canvas.saveLayer(x - dp(6f), y, band.right, y + wordsHeight, null) else canvas.save()
        canvas.translate(x, y)
        canvas.clipRect(-dp(6f), 0f, band.width(), wordsHeight)
        l.draw(canvas)
        if (fades) canvas.drawRect(-dp(6f), wholeHeight, band.width(), wordsHeight, fade)
        canvas.restoreToCount(saved)

        val barTop = y + wordsHeight + dp(8f) * scale
        val barH = dp(4f) * scale
        canvas.drawRoundRect(x, barTop, band.right - dp(PAD_SIDE) * scale, barTop + barH, barH / 2f, barH / 2f, track)
        val hw = dp(36f) * scale
        val hh = dp(4f) * scale
        val hy = barTop + barH + dp(2f + 10f) * scale
        canvas.drawRoundRect(band.centerX() - hw / 2f, hy - hh / 2f, band.centerX() + hw / 2f, hy + hh / 2f, hh / 2f, hh / 2f, handle)
    }

    private companion object {
        const val MARGIN = 2f // dp above and below the band
        const val PAD_SIDE = 14f // the band's own padding, in dp
        const val PAD_TOP = 10f
        const val FADE_START = 0x8C000000.toInt() // the part-line starts at 55% and fades to nothing, as on the band
        const val SAMPLE = "Read at your own pace. The words follow your voice, and they wait for you when you pause. " +
            "If you skip a line, the band catches up. Swipe the words to go back, or tap a word to jump there. " +
            "Double tap the band to start or stop. The band keeps its size, so bigger words show fewer lines and " +
            "smaller words show more."
    }
}
