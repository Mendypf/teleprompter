package io.github.mendypf.prompter

import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.util.function.Consumer
import kotlin.math.ceil

/**
 * The band's three looks: the dark card with the lavender glow, frosted glass in navy (the default), or clear
 * frosted glass with no color of its own.
 */
enum class Look { GLOW, GLASS, CLEAR }

/** Which look the band uses. Kept in the app's own private settings file, on the phone only. */
object Looks {
    private const val FILE = "band_look"
    private const val KEY = "look"

    fun current(context: Context): Look {
        val saved = prefs(context).getString(KEY, null)
        return Look.entries.firstOrNull { it.name == saved } ?: Look.GLASS
    }

    fun set(context: Context, look: Look) {
        prefs(context).edit().putString(KEY, look.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * The glass card, drawn over the camera picture that Android blurs behind the band: a light navy tint, the lavender
 * edges of the glow look at half strength, and a thin bright rim, strongest along the top like light catching the edge
 * of a pane. When Android is not blurring, the tint turns much darker so the text stays easy to read.
 *
 * [clear] makes it clear glass: a lighter neutral tint with no navy and no lavender edges, and a brighter rim.
 * [seeThrough] is for the app's own screen, where clear glass sits on the dark page instead of a bright camera
 * picture: it leaves the tint out, so the button and the preview look as clear as the band does on the camera.
 */
class GlassCard(
    val radius: Float,
    private val clear: Boolean = false,
    private val seeThrough: Boolean = false,
    private val blurring: () -> Boolean,
) : Drawable() {

    private val density = Resources.getSystem().displayMetrics.density
    private val rimWidth = RIM_DP * density
    private val box = RectF()
    private val rimBox = RectF()
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (clear) CLEAR_TINT else GlowCard.CORE }
    private val left = edge()
    private val right = edge()
    private val top = edge()
    private val bottom = edge()
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidth
    }

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        val h = box.height()
        // The glow look's own measured gradients, at the same depths; the paints draw them at half strength.
        val side = GlowCard.SIDE_DEPTH * h
        left.shader = LinearGradient(box.left, 0f, box.left + side, 0f, GlowCard.SIDE_COLORS, GlowCard.SIDE_STOPS, Shader.TileMode.CLAMP)
        right.shader = LinearGradient(box.right, 0f, box.right - side, 0f, GlowCard.SIDE_COLORS, GlowCard.SIDE_STOPS, Shader.TileMode.CLAMP)
        top.shader = LinearGradient(0f, box.top, 0f, box.top + GlowCard.TOP_DEPTH * h, GlowCard.TOP_COLORS, GlowCard.TOP_STOPS, Shader.TileMode.CLAMP)
        bottom.shader = LinearGradient(0f, box.bottom, 0f, box.bottom - GlowCard.BOTTOM_DEPTH * h, GlowCard.BOTTOM_COLORS, GlowCard.BOTTOM_STOPS, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(
            0f, box.top, 0f, box.top + SHEEN_DP * density, if (clear) CLEAR_SHEEN else SHEEN, 0, Shader.TileMode.CLAMP,
        )
        // The rim sits just inside the edge, so all of it shows.
        rimBox.set(box)
        rimBox.inset(rimWidth / 2f, rimWidth / 2f)
        rim.shader = LinearGradient(
            0f, box.top, 0f, box.bottom, if (clear) CLEAR_RIM_COLORS else RIM_COLORS, RIM_STOPS, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (box.isEmpty) return
        tint.alpha = when {
            clear && seeThrough -> 0
            !blurring() -> TINT_SOLID
            clear -> TINT_CLEAR
            else -> TINT_GLASS
        }
        if (tint.alpha > 0) canvas.drawRoundRect(box, radius, radius, tint)
        // Each edge light fades to clear, so every layer can be drawn as the whole rounded card. Clear glass has no
        // lavender edges, only the sheen.
        val layers = if (clear) arrayOf(sheen) else arrayOf(left, right, top, bottom, sheen)
        for (paint in layers) canvas.drawRoundRect(box, radius, radius, paint)
        val r = (radius - rimWidth / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(rimBox, r, r, rim)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = 0f // no shadow
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun edge() = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = EDGE_ALPHA }

    companion object {
        // How much navy covers the picture behind (out of 255). One number each, easy to tune on a real phone.
        // 60% keeps the script text readable over a bright wall (4.7 to 1 contrast; 45% gave 3 to 1) while the blurred
        // picture still shows through. Measured on the test phone's screenshots.
        const val TINT_GLASS = 153
        // 92% when Android is not blurring: at 85% the sharp picture behind showed through as ghost text.
        const val TINT_SOLID = 235
        // Clear glass: a near-black with no color, at 40%. Lighter than the navy glass, so the words get a soft
        // shadow under them (the band adds it) to stay readable over a bright picture.
        const val TINT_CLEAR = 102
        private const val CLEAR_TINT = 0xFF0B0C10.toInt()
        const val EDGE_ALPHA = 128 // the lavender edges at half the glow look's strength

        const val RIM_DP = 1f
        const val SHEEN_DP = 8f // a soft light just under the top edge

        private const val SHEEN = 0x1AFFFFFF // white at 10%, fading to nothing
        private val RIM_COLORS = intArrayOf(0x59FFFFFF, 0x38FFFFFF, 0x26FFFFFF, 0x38FFFFFF) // white 35%, 22%, 15%, 22%
        private val RIM_STOPS = floatArrayOf(0f, 0.18f, 0.55f, 1f)
        private const val CLEAR_SHEEN = 0x29FFFFFF // white at 16%
        private val CLEAR_RIM_COLORS = intArrayOf(0x99FFFFFF.toInt(), 0x59FFFFFF, 0x38FFFFFF, 0x59FFFFFF) // 60, 35, 22, 35%
    }
}

/**
 * Shows the band in a small window of its own, exactly the card's size, that Android blurs behind. Blur needs a real
 * window, so the band goes into a Dialog made from the Accessibility service, with the dark band's window type and
 * flags: it floats over the camera, never takes the keyboard, and taps outside it still reach the camera.
 *
 * [context] must be the Accessibility service itself: its window manager carries the pass that lets the window float
 * over other apps.
 */
class GlassHost(private val context: Context, private val content: View) {

    private val windows: WindowManager = context.getSystemService(WindowManager::class.java)
    private var dialog: Dialog? = null
    private var blurOn = false

    // Android calls this when it turns blur on or off (battery saver, the developer setting, a very hot phone).
    private val blurWatch = Consumer<Boolean> { on ->
        if (dialog != null && on != blurOn) {
            blurOn = on
            Probe.log("glass: blur " + if (on) "on" else "off")
            redraw(content)
        }
    }

    /** True while the band is on screen. */
    val isShown: Boolean get() = dialog != null

    /** False when Android is not blurring: battery saver, the developer setting, or a phone without blur. */
    val blurring: Boolean get() = if (dialog != null) blurOn else windows.isCrossWindowBlurEnabled

    /** Takes the dark band's window settings; returns false if Android refused the window. */
    fun show(params: WindowManager.LayoutParams): Boolean {
        if (dialog != null) return true
        detach()
        val d = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) // see-through: blur only works on these
        try {
            d.setCancelable(false)
            d.setCanceledOnTouchOutside(false)
            d.setContentView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val w = d.window ?: error("the dialog has no window")
            // Draws nothing: Android takes the blurred area's rounded corners from it.
            w.setBackgroundDrawable(RoundOutline(radius()))
            @Suppress("DEPRECATION") // already the rule on Android 15 and later; this covers 13 and 14
            w.setDecorFitsSystemWindows(false) // no extra padding: the card fills the window
            w.setWindowAnimations(0) // appears at once, like the dark band
            w.setFormat(PixelFormat.TRANSLUCENT)
            w.setDimAmount(0f)
            val a = w.attributes
            a.type = params.type
            // The dark band's flags; never dim the camera; drawn by the graphics chip, which the blur needs.
            a.flags = (params.flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            if (params.token != null) a.token = params.token
            // Exactly the card, where the band is, so the blur stops at the card's edge.
            a.gravity = params.gravity
            a.x = params.x
            a.y = params.y
            a.layoutInDisplayCutoutMode = params.layoutInDisplayCutoutMode
            a.width = params.width
            a.height = WindowManager.LayoutParams.WRAP_CONTENT
            a.title = "Prompter glass band"
            w.attributes = a
            blurOn = windows.isCrossWindowBlurEnabled
            windows.addCrossWindowBlurEnabledListener(context.mainExecutor, blurWatch)
            w.setBackgroundBlurRadius(dp(BLUR_DP))
            d.show()
            dialog = d
            Probe.log("glass: shown, blur " + if (blurOn) "on" else "off")
            return true
        } catch (e: Exception) {
            Probe.log("glass: could not show: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { windows.removeCrossWindowBlurEnabledListener(blurWatch) }
            runCatching { d.dismiss() }
            detach()
            return false
        }
    }

    fun hide() {
        val d = dialog ?: return
        dialog = null
        runCatching { windows.removeCrossWindowBlurEnabledListener(blurWatch) }
        runCatching { d.dismiss() }
        detach()
        Probe.log("glass: hidden")
    }

    /** The user moved or resized the band: the glass window follows. */
    fun update(params: WindowManager.LayoutParams) {
        val w = dialog?.window ?: return
        val a = w.attributes
        if (a.x == params.x && a.y == params.y && a.width == params.width) return
        a.x = params.x
        a.y = params.y
        a.width = params.width
        runCatching { w.attributes = a }
    }

    /** Frees the band's view from its old window, so it can go into a new one or back into the dark band. */
    private fun detach() {
        (content.parent as? ViewGroup)?.removeView(content)
    }

    /** Redraws every glass card in the band, so a change in blur shows at once. */
    private fun redraw(v: View) {
        if (v.background is GlassCard) v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) redraw(v.getChildAt(i))
    }

    private fun radius(): Float = (content.background as? GlassCard)?.radius ?: dp(RADIUS_DP).toFloat()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val BLUR_DP = 28 // how soft the camera picture behind the band gets
        const val RADIUS_DP = 18 // the card's corners, if the card is not a GlassCard
    }
}

/** The glass window's own background. It draws nothing; it only tells Android how round the blurred area is. */
private class RoundOutline(private val radius: Float) : Drawable() {

    override fun draw(canvas: Canvas) = Unit

    override fun getOutline(outline: Outline) {
        // Android asks before the window has its size; a stand-in box keeps the corners round from the first frame.
        val b = if (bounds.isEmpty) {
            val side = 2 * ceil(radius).toInt() + 1
            Rect(0, 0, side, side)
        } else {
            bounds
        }
        outline.setRoundRect(b, radius)
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
