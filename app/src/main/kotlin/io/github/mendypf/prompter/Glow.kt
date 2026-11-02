package io.github.mendypf.prompter

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The band's background: a near-black navy middle with a lavender glow fading in from every edge, brighter and
 * deeper on the sides. Every color and depth below was measured from the pixels of a reference card picture; depths
 * are a share of the band's height, so it scales like that card.
 */
class GlowCard(private val radius: Float) : Drawable() {

    private val box = RectF()
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CORE }
    private val left = Paint(Paint.ANTI_ALIAS_FLAG)
    private val right = Paint(Paint.ANTI_ALIAS_FLAG)
    private val top = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottom = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        val h = box.height()
        val side = SIDE_DEPTH * h
        val t = TOP_DEPTH * h
        val b = BOTTOM_DEPTH * h
        left.shader = LinearGradient(box.left, 0f, box.left + side, 0f, SIDE_COLORS, SIDE_STOPS, Shader.TileMode.CLAMP)
        right.shader = LinearGradient(box.right, 0f, box.right - side, 0f, SIDE_COLORS, SIDE_STOPS, Shader.TileMode.CLAMP)
        top.shader = LinearGradient(0f, box.top, 0f, box.top + t, TOP_COLORS, TOP_STOPS, Shader.TileMode.CLAMP)
        bottom.shader = LinearGradient(0f, box.bottom, 0f, box.bottom - b, BOTTOM_COLORS, BOTTOM_STOPS, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (box.isEmpty) return
        // Each glow fades to clear at its depth, so every layer can be drawn as the whole rounded card.
        for (paint in arrayOf(core, left, right, top, bottom)) canvas.drawRoundRect(box, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // Measured from the Sync card: its middle, its text, the switch's knob and its track.
        const val CORE = 0xFF020619.toInt()
        const val TEXT = 0xFFCCD1DF.toInt()
        const val TEXT_DIM = 0xFF878B9D.toInt() // the text color two thirds of the way over the middle
        const val KNOB = 0xFFF1F0F0.toInt()
        const val TRACK = 0xFF282A3C.toInt()

        // The edge glow, from glow_stops.py.
        val SIDE_DEPTH = 0.4536f
        val SIDE_COLORS = intArrayOf(0xFF645C8F.toInt(), 0xF5635A8F.toInt(), 0xE560578F.toInt(), 0xD45D548F.toInt(), 0xC258528F.toInt(), 0xAF554F8F.toInt(), 0x9D514B8F.toInt(), 0x8C4D498F.toInt(), 0x7A49478F.toInt(), 0x6A45438F.toInt(), 0x5B42408F.toInt(), 0x4C3F3D8F.toInt(), 0x413B3C8F.toInt(), 0x3739398F.toInt(), 0x2D36388F.toInt(), 0x2635348F.toInt(), 0x1F302F8F.toInt(), 0x192F288F.toInt(), 0x14312A8F.toInt(), 0x11312A8F.toInt(), 0x0E3B2C8F.toInt(), 0x0B392A8F.toInt(), 0x00000000.toInt())
        val SIDE_STOPS = floatArrayOf(0.0000f, 0.0472f, 0.0945f, 0.1417f, 0.1890f, 0.2362f, 0.2835f, 0.3307f, 0.3780f, 0.4252f, 0.4724f, 0.5197f, 0.5669f, 0.6142f, 0.6614f, 0.7087f, 0.7559f, 0.8031f, 0.8504f, 0.8976f, 0.9449f, 0.9921f, 1.0000f)
        val TOP_DEPTH = 0.3250f
        val TOP_COLORS = intArrayOf(0xFF38345C.toInt(), 0xFF383460.toInt(), 0xFF383667.toInt(), 0xFF2F2F5D.toInt(), 0xE12C2C5C.toInt(), 0xC029295C.toInt(), 0x9F27285C.toInt(), 0x8324265C.toInt(), 0x6720235C.toInt(), 0x531E225C.toInt(), 0x3C1C215C.toInt(), 0x2C1C1C5C.toInt(), 0x2120175C.toInt(), 0x151D155C.toInt(), 0x0B1E185C.toInt(), 0x051B195C.toInt(), 0x00000000.toInt())
        val TOP_STOPS = floatArrayOf(0.0000f, 0.0659f, 0.1319f, 0.1978f, 0.2637f, 0.3297f, 0.3956f, 0.4615f, 0.5275f, 0.5934f, 0.6593f, 0.7253f, 0.7912f, 0.8571f, 0.9231f, 0.9890f, 1.0000f)
        val BOTTOM_DEPTH = 0.3214f
        val BOTTOM_COLORS = intArrayOf(0xFF474374.toInt(), 0xEA444074.toInt(), 0xD1403D74.toInt(), 0xB83D3B74.toInt(), 0x9F393774.toInt(), 0x86343574.toInt(), 0x6B313374.toInt(), 0x562F3174.toInt(), 0x452C3074.toInt(), 0x36282B74.toInt(), 0x262B2974.toInt(), 0x1C2D2474.toInt(), 0x142F2274.toInt(), 0x0C352874.toInt(), 0x06262974.toInt(), 0x00000000.toInt())
        val BOTTOM_STOPS = floatArrayOf(0.0000f, 0.0667f, 0.1333f, 0.2000f, 0.2667f, 0.3333f, 0.4000f, 0.4667f, 0.5333f, 0.6000f, 0.6667f, 0.7333f, 0.8000f, 0.8667f, 0.9333f, 1.0000f)
    }
}
