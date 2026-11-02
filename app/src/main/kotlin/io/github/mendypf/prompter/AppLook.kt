package io.github.mendypf.prompter

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min

/** The app screen's colors, all from the band: its navy, its lavender glow and its light text. */
object Palette {
    const val NIGHT = 0xFF03061A.toInt() // the page: the band's navy, a touch lifted
    const val TITLE = GlowCard.KNOB
    const val TEXT = GlowCard.TEXT
    const val DIM = GlowCard.TEXT_DIM
    const val LAVENDER = 0xFF9A92E8.toInt() // the band's lavender edge (#645C8F), brighter for small marks on navy
    const val BLUE = 0xFF5B6CF0.toInt()
    const val VIOLET = 0xFFB48CE0.toInt()
}

private fun dpf(v: Float): Float = v * Resources.getSystem().displayMetrics.density

/** A soft round light that fades to nothing. The page's glows and the tiles' little scenes are made of these. */
private fun light(x: Float, y: Float, r: Float, color: Int): Shader =
    RadialGradient(x, y, r.coerceAtLeast(1f), intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)

/** A drawable that only draws, with no alpha or color filter of its own. */
abstract class PlainDrawable : Drawable() {
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The page: the band's navy, lit softly by its lavender and a little blue, like the band's glow spread out. */
class NightBackground : PlainDrawable() {
    private val base = Paint().apply { color = Palette.NIGHT }
    private val glows = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        glows[0].shader = light(w * 1.0f, h * 0.02f, w * 1.0f, 0x99645C8F.toInt()) // lavender, top right
        glows[1].shader = light(w * -0.1f, h * 0.45f, w * 0.9f, 0x553A4AB8) // blue, left
        glows[2].shader = light(w * 0.8f, h * 1.0f, w * 1.0f, 0x80474374.toInt()) // violet, bottom
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
        for (p in glows) canvas.drawRect(bounds, p)
    }
}

/**
 * A frosted glass card in the band's colors: a faint lavender fill with light along the top, the band's lavender glow
 * just inside the edges, and a thin bright rim. [dots] adds a fine dotted texture that fades out downwards.
 */
class GlassPanel(private val radius: Float, private val dots: Boolean = false) : PlainDrawable() {
    private val box = RectF()
    private val rimBox = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A9A92E8 } // lavender at 10%
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edges = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val texture = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.2f)
    }

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        if (box.isEmpty) return
        val depth = dpf(26f)
        val glow = intArrayOf(0x66645C8F, 0x00645C8F)
        edges[0].shader = LinearGradient(box.left, 0f, box.left + depth, 0f, glow, null, Shader.TileMode.CLAMP)
        edges[1].shader = LinearGradient(box.right, 0f, box.right - depth, 0f, glow, null, Shader.TileMode.CLAMP)
        edges[2].shader = LinearGradient(0f, box.top, 0f, box.top + depth, glow, null, Shader.TileMode.CLAMP)
        edges[3].shader = LinearGradient(0f, box.bottom, 0f, box.bottom - depth, glow, null, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, box.top, 0f, box.top + box.height() * 0.5f, 0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rimBox.set(box)
        rimBox.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f)
        rim.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            intArrayOf(0x66FFFFFF, 0x1FFFFFFF, 0x33FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        if (dots) texture.shader = dotShader(box)
    }

    private fun dotShader(b: RectF): Shader {
        val step = dpf(7f).toInt().coerceAtLeast(4)
        val tile = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(step / 2f, step / 2f, dpf(0.85f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF })
        val grid = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val fade = LinearGradient(0f, b.top, 0f, b.top + b.height() * 0.8f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        return ComposeShader(grid, fade, PorterDuff.Mode.DST_IN)
    }

    override fun draw(canvas: Canvas) {
        if (box.isEmpty) return
        canvas.drawRoundRect(box, radius, radius, fill)
        for (p in edges) canvas.drawRoundRect(box, radius, radius, p)
        canvas.drawRoundRect(box, radius, radius, sheen)
        if (dots) canvas.drawRoundRect(box, radius, radius, texture)
        val r = (radius - rim.strokeWidth / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(rimBox, r, r, rim)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = 0f
    }
}

/** The app's mark: a glossy sphere in the band's lavender and blue, with soft light around it. Quieter when not [lit]. */
class OrbDrawable(private val halo: Boolean) : PlainDrawable() {
    var lit = true
        set(value) {
            field = value
            invalidateSelf()
        }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var outer = 0f

    override fun onBoundsChange(bounds: Rect) {
        cx = bounds.exactCenterX()
        cy = bounds.exactCenterY()
        outer = min(bounds.width(), bounds.height()) / 2f
        r = if (halo) outer * 0.6f else outer
        if (r <= 0f) return
        glowPaint.shader = RadialGradient(
            cx, cy + r * 0.1f, outer.coerceAtLeast(1f),
            intArrayOf(0x809A92E8.toInt(), 0x339A92E8, 0x009A92E8), floatArrayOf(0.4f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        ball.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.45f, r * 1.55f,
            intArrayOf(0xFFF6F3FF.toInt(), 0xFFC3BCFF.toInt(), 0xFF8C8AF2.toInt(), 0xFF5260D6.toInt(), 0xFF262C7E.toInt()),
            floatArrayOf(0f, 0.2f, 0.45f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
        shine.shader = light(cx - r * 0.3f, cy - r * 0.42f, r * 0.55f, 0xCCFFFFFF.toInt())
    }

    override fun draw(canvas: Canvas) {
        if (r <= 0f) return
        if (halo) {
            glowPaint.alpha = if (lit) 255 else 60
            canvas.drawRect(bounds, glowPaint) // the light fades to nothing before the edge, so no circle edge to show
        }
        val a = if (lit) 255 else 110
        ball.alpha = a
        shine.alpha = a
        canvas.drawCircle(cx, cy, r, ball)
        canvas.drawCircle(cx, cy, r, shine)
    }
}

/** The big on/off switch: a white knob sliding along a pill that turns lavender when on. */
class PillSwitch(context: Context, private val onChange: (Boolean) -> Unit) : View(context) {
    var isOn = false
        private set
    private var t = 0f // 0 = off, 1 = on, in between while it slides
    private var slide: ValueAnimator? = null
    private val box = RectF()
    private val rimBox = RectF()
    private val offTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.TRACK }
    private val onTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        color = 0x33FFFFFF
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.KNOB }
    private val knobShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Prompter"
    }

    fun setOn(on: Boolean, animate: Boolean) {
        isOn = on
        stateDescription = if (on) "On" else "Off"
        slide?.cancel()
        val target = if (on) 1f else 0f
        if (!animate || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            t = target
            invalidate()
            return
        }
        slide = ValueAnimator.ofFloat(t, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        setOn(!isOn, animate = true)
        onChange(isOn)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        rimBox.set(box)
        rimBox.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f)
        onTrack.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Palette.LAVENDER, Palette.BLUE, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = box.height() / 2f
        canvas.drawRoundRect(box, r, r, offTrack)
        onTrack.alpha = (t * 255).toInt()
        canvas.drawRoundRect(box, r, r, onTrack)
        canvas.drawRoundRect(rimBox, r, r, rim)
        val pad = dpf(4f)
        val kr = r - pad
        val x = box.left + pad + kr + (box.width() - 2 * (pad + kr)) * t
        canvas.drawCircle(x, box.centerY() + dpf(1f), kr, knobShade)
        canvas.drawCircle(x, box.centerY(), kr, knob)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isOn
    }
}

/** A look button's face: the band's own card in that look, filling the button, straight over the page. */
class LookArt(look: Look, private val radius: Float) : PlainDrawable() {
    private val box = RectF()
    private val clip = Path()
    private val card: Drawable = when (look) {
        Look.GLOW -> GlowCard(radius)
        Look.GLASS -> GlassCard(radius) { true }
        Look.CLEAR -> GlassCard(radius, clear = true, seeThrough = true) { true }
    }

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        if (box.isEmpty) return
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        card.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        if (box.isEmpty) return
        canvas.save()
        canvas.clipPath(clip)
        card.draw(canvas)
        canvas.restore()
    }
}

/** The frame around a look tile: bright and thick on the picked one, faint on the other. */
class TileFrame(private val radius: Float) : PlainDrawable() {
    var picked = false
        set(value) {
            field = value
            invalidateSelf()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val w = if (picked) dpf(3f) else dpf(1.2f)
        paint.strokeWidth = w
        paint.color = if (picked) 0xF2F1F0F0.toInt() else 0x2EFFFFFF
        box.set(bounds)
        box.inset(w / 2f, w / 2f)
        val r = (radius - w / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(box, r, r, paint)
    }
}

/** A white disc with a navy check mark on the picked look; an empty ring on the others. */
class CheckBadge : PlainDrawable() {
    var picked = false
        set(value) {
            field = value
            invalidateSelf()
        }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
        color = 0x80FFFFFF.toInt()
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.KNOB }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = GlowCard.CORE
    }
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        path.reset()
        path.moveTo(bounds.left + w * 0.28f, bounds.top + h * 0.52f)
        path.lineTo(bounds.left + w * 0.44f, bounds.top + h * 0.68f)
        path.lineTo(bounds.left + w * 0.74f, bounds.top + h * 0.36f)
    }

    override fun draw(canvas: Canvas) {
        val r = min(bounds.width(), bounds.height()) / 2f
        if (!picked) {
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r - ring.strokeWidth / 2f, ring)
            return
        }
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, disc)
        canvas.drawPath(path, tick)
    }
}

/**
 * A tile that picks one of the band's looks. Its own surface is a small band in that look over a camera-like picture,
 * with sample words, the look's name at the bottom left, a check mark at the top right, and a bright frame when picked.
 */
class LookTile(context: Context, val look: Look, label: String, onPick: (Look) -> Unit) : FrameLayout(context) {
    private val radius = dpf(20f)
    private val art = LookArt(look, radius)
    private val frame = TileFrame(radius)
    private val badge = CheckBadge()
    private val badgeView = View(context).apply {
        background = badge
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val name = TextView(context).apply {
        text = label
        textSize = 14f
        maxLines = 2
        setTextColor(GlowCard.TEXT)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        if (look == Look.CLEAR) setShadowLayer(dpf(3f), 0f, dpf(1f), 0xB3000000.toInt())
    }

    var picked = false
        set(value) {
            field = value
            frame.picked = value
            badge.picked = value
            isSelected = value
        }

    init {
        background = art
        foreground = frame
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
        val size = dpf(20f).toInt()
        val edge = dpf(12f).toInt()
        addView(badgeView, LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            topMargin = edge
            marginEnd = edge
        })
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dpf(14f).toInt()
            marginEnd = edge
            bottomMargin = edge
        })
        isClickable = true
        contentDescription = "$label look"
        setOnClickListener { onPick(look) }
    }
}

/** Small outline icons on a 24-point grid, in one stroke weight. */
class LineIcon(private val kind: Kind, color: Int) : PlainDrawable() {
    enum class Kind { SCRIPT, SIZE, LOOK, MIC, ACCESS, LOCK, PLAY }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val lines = Path()
    private val solid = Path()

    override fun onBoundsChange(bounds: Rect) {
        val s = min(bounds.width(), bounds.height()) / 24f
        val ox = bounds.left + (bounds.width() - 24f * s) / 2f
        val oy = bounds.top + (bounds.height() - 24f * s) / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
            lines.moveTo(x(x1), y(y1))
            lines.lineTo(x(x2), y(y2))
        }
        lines.reset()
        solid.reset()
        when (kind) {
            Kind.SCRIPT -> {
                lines.addRoundRect(x(5.5f), y(3f), x(18.5f), y(21f), 2.5f * s, 2.5f * s, Path.Direction.CW)
                seg(9f, 9f, 15f, 9f)
                seg(9f, 13f, 15f, 13f)
                seg(9f, 17f, 13f, 17f)
            }
            Kind.SIZE -> {
                seg(5f, 19f, 19f, 5f)
                seg(13f, 5f, 19f, 5f)
                seg(19f, 5f, 19f, 11f)
                seg(5f, 13f, 5f, 19f)
                seg(5f, 19f, 11f, 19f)
            }
            Kind.LOOK -> {
                lines.moveTo(x(12f), y(3f))
                lines.cubicTo(x(12.6f), y(8.4f), x(15.6f), y(11.4f), x(21f), y(12f))
                lines.cubicTo(x(15.6f), y(12.6f), x(12.6f), y(15.6f), x(12f), y(21f))
                lines.cubicTo(x(11.4f), y(15.6f), x(8.4f), y(12.6f), x(3f), y(12f))
                lines.cubicTo(x(8.4f), y(11.4f), x(11.4f), y(8.4f), x(12f), y(3f))
                lines.close()
            }
            Kind.MIC -> {
                lines.addRoundRect(x(9f), y(3f), x(15f), y(14f), 3f * s, 3f * s, Path.Direction.CW)
                lines.moveTo(x(6f), y(11f))
                lines.arcTo(x(6f), y(5f), x(18f), y(17f), 180f, -180f, false)
                seg(12f, 17f, 12f, 21f)
                seg(9f, 21f, 15f, 21f)
            }
            Kind.ACCESS -> {
                lines.addCircle(x(12f), y(12f), 9f * s, Path.Direction.CW)
                solid.addCircle(x(12f), y(7.6f), 1.5f * s, Path.Direction.CW)
                seg(7.5f, 10.5f, 16.5f, 10.5f)
                seg(12f, 10.5f, 12f, 14.5f)
                seg(12f, 14.5f, 9.5f, 18.5f)
                seg(12f, 14.5f, 14.5f, 18.5f)
            }
            Kind.LOCK -> {
                lines.addRoundRect(x(6f), y(11f), x(18f), y(20.5f), 2f * s, 2f * s, Path.Direction.CW)
                lines.moveTo(x(8.5f), y(11f))
                lines.lineTo(x(8.5f), y(8f))
                lines.arcTo(x(8.5f), y(4.5f), x(15.5f), y(11.5f), 180f, 180f, false)
                lines.lineTo(x(15.5f), y(11f))
            }
            Kind.PLAY -> {
                solid.moveTo(x(8.5f), y(6f))
                solid.lineTo(x(19f), y(12f))
                solid.lineTo(x(8.5f), y(18f))
                solid.close()
            }
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(lines, stroke)
        canvas.drawPath(solid, fill)
    }
}
