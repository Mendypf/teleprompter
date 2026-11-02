package io.github.mendypf.prompter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.os.SystemClock
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The band under the front camera that shows the user's script while they film. The line with the next word to say
 * sits at the top, right under the lens. Words already said turn dim, the next word is white, the rest stay plain.
 * When the speaker repeats a word or pauses, the text simply holds still. A double tap starts or stops listening,
 * a swipe on the words scrolls back or ahead, the handle under the words moves the band, and a pinch changes its
 * width (fingers side to side) or its height (fingers up and down); it remembers its place and size. The text size is set in the app and never changes the band's
 * size: bigger words show fewer lines in it, smaller words more.
 *
 * Everything runs on the main thread. Any method can be called before [show]: the band keeps what it was told and
 * shows it when it appears.
 */
class PrompterBand(
    private val context: Context,
    private val onToggle: () -> Unit,          // a double tap: start or stop listening
    private val onWordTap: (token: Int) -> Unit, // the user tapped a word to continue from there
) {

    enum class Mode { READY, LISTENING, NO_SCRIPT }

    private val windows: WindowManager = context.getSystemService(WindowManager::class.java)
    private val frame = FrameLayout(context)
    private val card = Card()

    // The two looks the user can pick in the app: the dark glow card in the band's own window, or frosted glass,
    // where the card sits in a window that Android blurs behind.
    private val glow = GlowCard(dp(18).toFloat())
    private val glass = GlassHost(context, card)
    private val glassCard = GlassCard(dp(18).toFloat()) { glass.blurring }
    private val clearCard = GlassCard(dp(18).toFloat(), clear = true) { glass.blurring }
    private var inGlass = false

    // Where the band sits and how big it is, as the user last left them.
    private val place = context.getSharedPreferences("band_place", Context.MODE_PRIVATE)
    private var sizeSp = ScriptStore.textSizeSp(context)
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val status = TextView(context)
    private val script = ScriptView(context)
    private val bar = Bar(context)
    private val handle = Handle(context)
    private var shown = false
    private var mode = Mode.READY
    private var noteText: String? = null

    /** True while the band is on screen. */
    val isShown: Boolean get() = shown

    init {
        // The script uses the status line's font, one weight for every word, so no word changes width when its color does.
        val font = status.typeface
        script.setFont(Typeface.create(font, 600, false))
        script.setTextSizeSp(sizeSp)
        script.boxHeight = BandBox.wordsHeight(context)

        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(2))
            background = glow
            // With a screen reader, its own double tap starts or stops, like a double tap on the band.
            contentDescription = "Script band. Double tap to start or stop."
            setOnClickListener { onToggle() }
            isSoundEffectsEnabled = false // no click sound: the camera may be recording
        }
        status.setTextColor(GlowCard.TEXT_DIM)
        status.textSize = 14f
        status.typeface = Typeface.create(font, 500, false)
        status.maxLines = 2 // a long note ("Allow the mic in the Prompter app first") wraps instead of being cut, at small sizes
        status.ellipsize = TextUtils.TruncateAt.END

        // The script comes first, right under the lens, so the speaker's eyes stay as close to the camera as they
        // can. Its view reaches a little past the card's padding on each side; the words themselves line up with the
        // status line below.
        card.addView(
            script,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = -script.side
                rightMargin = -script.side
            },
        )
        card.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
        card.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)).apply { topMargin = dp(2) })

        frame.addView(card)
        refresh()
    }

    /**
     * Floats over the camera where the user last left it, never takes the keyboard. The window is exactly the card,
     * so taps beside it reach the camera.
     */
    fun show() {
        if (shown) return
        placeWindow()
        val look = Looks.current(context)
        if (look != Look.GLOW) {
            val clear = look == Look.CLEAR
            card.background = if (clear) clearCard else glassCard
            if (glass.show(lp)) {
                inGlass = true
                status.setTextColor(GlowCard.TEXT)
                shadeWords(clear)
                shown = true
                Probe.log(if (clear) "band shown (clear glass)" else "band shown (glass)")
                return
            }
            Probe.log("glass did not show, so the band uses the glow look")
        }
        card.background = glow
        status.setTextColor(GlowCard.TEXT_DIM)
        shadeWords(false)
        if (card.parent == null) frame.addView(card) // back from the glass window
        try {
            windows.addView(frame, lp)
            shown = true
            Probe.log("band shown")
        } catch (e: Exception) {
            Probe.log("band could not show: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A soft dark shadow under the script and the status line, for clear glass, where the picture shows brighter. */
    private fun shadeWords(on: Boolean) {
        val r = dp(3).toFloat()
        if (on) status.setShadowLayer(r, 0f, r / 3f, WORD_SHADOW) else status.setShadowLayer(0f, 0f, 0f, 0)
        script.setShadow(on, r)
    }

    fun hide() {
        if (!shown) return
        if (inGlass) glass.hide() else runCatching { windows.removeViewImmediate(frame) }
        inGlass = false
        shown = false
        Probe.log("band hidden")
    }

    /** A new script. The band starts again at its first word; a paragraph start begins a new line. */
    fun setScript(tokens: List<String>, paragraphStart: BooleanArray) {
        script.setScript(tokens, paragraphStart)
        bar.progress = 0f
        refresh()
    }

    /**
     * The next word to say (tokens.size = the end). Called up to 10 times a second. The same word again does nothing;
     * a new word only moves the colors, and the text slides up when that word sits on a new line.
     */
    fun setPosition(token: Int) {
        if (script.setPosition(token)) bar.progress = script.done
    }

    /** A different mode also clears a [note]. */
    fun setMode(mode: Mode) {
        if (mode != this.mode) noteText = null
        this.mode = mode
        refresh()
    }

    /** A short status line in place of the mode's own words, until note(null) or a different mode. */
    fun note(text: String?) {
        noteText = text
        refresh()
    }

    /** The size from the app's - and + buttons. The band keeps its own size: bigger words show fewer lines in it. */
    fun setTextSizeSp(sp: Float) {
        if (sp.isNaN()) return
        val size = sp.coerceIn(ScriptStore.MIN_TEXT_SP, ScriptStore.MAX_TEXT_SP)
        if (size == sizeSp) return
        sizeSp = size
        script.setTextSizeSp(size)
    }

    private fun refresh() {
        val hasScript = script.count > 0
        // Ready with nothing to read: ask for a script instead of offering Start.
        val look = if (mode == Mode.READY && !hasScript) Mode.NO_SCRIPT else mode
        val message = noteText ?: if (look == Mode.NO_SCRIPT) "Add your script in the Prompter app" else null
        status.text = message.orEmpty()
        status.visibility = if (message != null) View.VISIBLE else View.GONE
        handle.listening = mode == Mode.LISTENING
        card.stateDescription = if (mode == Mode.LISTENING) "Listening" else "Not listening"
        val lines = if (look != Mode.NO_SCRIPT && hasScript) View.VISIBLE else View.GONE
        script.visibility = lines
        bar.visibility = lines
    }

    /**
     * For tests from the laptop only (adb cannot touch with two fingers): plays a pinch into the card, two fingers
     * moving apart (factor above 1) or together (below 1), side to side or [vertical], through the same touch code
     * real fingers use.
     */
    fun testPinch(factor: Float, vertical: Boolean = false) {
        if (!shown) return
        val cx = card.width / 2f
        val cy = card.height / 2f
        val from = (if (vertical) card.height else card.width) / 4f
        val to = from * factor
        val t0 = SystemClock.uptimeMillis()
        val props = Array(2) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, fingers: Int, half: Float, t: Long) {
            val coords = Array(2) { i ->
                MotionEvent.PointerCoords().apply {
                    val apart = if (i == 0) -half else half
                    x = cx + if (vertical) 0f else apart
                    y = cy + if (vertical) apart else 0f
                    pressure = 1f
                    size = 1f
                }
            }
            val e = MotionEvent.obtain(t0, t, action, fingers, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            card.dispatchTouchEvent(e)
            e.recycle()
        }
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(MotionEvent.ACTION_DOWN, 1, from, t0)
        send(MotionEvent.ACTION_POINTER_DOWN or second, 2, from, t0 + 16)
        for (k in 1..12) send(MotionEvent.ACTION_MOVE, 2, from + (to - from) * k / 12, t0 + 16 + 16L * k)
        send(MotionEvent.ACTION_POINTER_UP or second, 2, to, t0 + 240)
        send(MotionEvent.ACTION_UP, 1, to, t0 + 256)
    }

    private fun wordTapped(token: Int) {
        Probe.log("band: word $token tapped")
        onWordTap(token)
    }

    private fun scrolledTo(token: Int) {
        Probe.log("band: scrolled to word $token")
        onWordTap(token)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(32)
    }

    /** Puts the band where the user last left it (at first: centered, just under the status bar), all of it on screen. */
    private fun placeWindow() {
        lp.width = BandBox.width(context)
        script.boxHeight = BandBox.wordsHeight(context)
        lp.x = place.getInt(KEY_X, (screen().width() - lp.width) / 2)
        lp.y = place.getInt(KEY_Y, statusBarHeight() + dp(4))
        keepOnScreen()
    }

    /** A pinch: a new width and height for the band, its words the same size. The band stays centered where it was. */
    private fun resizeBox(width: Int, height: Int) {
        val w = width.coerceIn(BandBox.minWidth(context), BandBox.maxWidth(context))
        val h = height.coerceIn(BandBox.minHeight(context), BandBox.maxHeight(context))
        if (w == lp.width && h == script.boxHeight) return
        val center = lp.x + lp.width / 2
        lp.width = w
        lp.x = center - w / 2
        script.boxHeight = h
        keepOnScreen()
        applyWindow()
    }

    private fun moveTo(x: Int, y: Int) {
        lp.x = x
        lp.y = y
        keepOnScreen()
        applyWindow()
    }

    /** The whole card stays on screen, wherever it is dragged. */
    private fun keepOnScreen() {
        val s = screen()
        val height = if (card.height > 0) card.height else dp(170)
        lp.x = lp.x.coerceIn(0, max(0, s.width() - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, s.height() - height))
    }

    private fun applyWindow() {
        if (!shown) return
        if (inGlass) glass.update(lp) else runCatching { windows.updateViewLayout(frame, lp) }
    }

    /** Saves where the band is and how big, for the next time the camera opens. */
    private fun remember() {
        place.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
        BandBox.save(context, lp.width, script.boxHeight)
        Probe.log("band placed: x=${lp.x} y=${lp.y} width=${lp.width} height=${script.boxHeight} size=${sizeSp.roundToInt()}")
    }

    private fun screen(): Rect {
        val b = windows.maximumWindowMetrics.bounds
        if (b.width() > 0 && b.height() > 0) return b
        val m = context.resources.displayMetrics
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    /**
     * The card, and every touch on it. A tap on a word continues from that word. A double tap anywhere starts or stops
     * listening. A swipe on the words scrolls the script, and the line that settles at the top is where reading
     * goes on. A drag on the handle (the progress line and below) moves the band. Two fingers anywhere on it change
     * its width when they move apart or together side to side, and its height when they move up and down.
     */
    @SuppressLint("ViewConstructor")
    private inner class Card : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var startX = 0
        private var startY = 0
        private var onHandle = false // this touch began on the handle or a side, so a drag moves the band
        private val sideZone = 24 * context.resources.displayMetrics.density // the strip along each side that moves the band
        private var gesture = Gesture.NONE
        private var pinching = false // two fingers are on the band
        private var startDx = 0f // how far apart the two fingers were, side to side and up and down, when they landed
        private var startDy = 0f
        private var axis = Axis.NONE // which way this pinch resizes, picked once the fingers have moved
        private var startWidth = 0
        private var startHeight = 0

        // Taps and double taps. It only sees a touch until that touch turns into a swipe, a drag or a pinch.
        private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val word = script.wordAtPoint(e.x - script.left, e.y - script.top)
                if (word >= 0) wordTapped(word)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Probe.log("band: double tap")
                onToggle()
                return true
            }
        }).apply { setIsLongpressEnabled(false) } // a slow tap still counts as a tap

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lastY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    gesture = Gesture.NONE
                    // The handle (the progress line and below) and the strips along both sides move the band. No
                    // words showing (no script yet): the whole band is the handle.
                    onHandle = script.visibility != View.VISIBLE || e.y >= bar.top - dp(10) ||
                        e.x < sideZone || e.x > width - sideZone
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount == 2) {
                        pinching = true
                        axis = Axis.NONE
                        startDx = abs(e.getRawX(1) - e.getRawX(0))
                        startDy = abs(e.getRawY(1) - e.getRawY(0))
                        startWidth = lp.width
                        startHeight = script.boxHeight
                    }
                    begin(Gesture.PINCH, e)
                }
                MotionEvent.ACTION_MOVE -> when (gesture) {
                    // One pinch, one direction: once the fingers have moved a little, the way they moved most
                    // decides it. Side to side changes only the width, up and down only the height, by as much as
                    // the fingers moved from there on. The words stay the same size.
                    Gesture.PINCH -> if (e.pointerCount >= 2 && pinching) {
                        val spreadX = abs(e.getRawX(1) - e.getRawX(0))
                        val spreadY = abs(e.getRawY(1) - e.getRawY(0))
                        if (axis == Axis.NONE) {
                            val dx = spreadX - startDx
                            val dy = spreadY - startDy
                            if (max(abs(dx), abs(dy)) > slop) {
                                axis = if (abs(dx) >= abs(dy)) Axis.WIDTH else Axis.HEIGHT
                                startDx = spreadX // the resize follows the fingers from here, so the band never jumps
                                startDy = spreadY
                                Probe.log("band: pinch ${if (axis == Axis.WIDTH) "side to side" else "up and down"}")
                            }
                        }
                        when (axis) {
                            Axis.WIDTH -> resizeBox(startWidth + (spreadX - startDx).roundToInt(), startHeight)
                            Axis.HEIGHT -> resizeBox(startWidth, startHeight + (spreadY - startDy).roundToInt())
                            Axis.NONE -> Unit
                        }
                    }
                    Gesture.MOVE -> moveTo(startX + (e.rawX - downX).roundToInt(), startY + (e.rawY - downY).roundToInt())
                    Gesture.SCROLL -> {
                        script.dragBy(e.rawY - lastY)
                        lastY = e.rawY
                    }
                    Gesture.NONE -> if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) {
                        lastY = e.rawY // the words start moving from here, so they never jump
                        begin(if (onHandle) Gesture.MOVE else Gesture.SCROLL, e)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2) pinching = false // one finger left: the pinch ends
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (gesture) {
                        Gesture.MOVE, Gesture.PINCH -> remember()
                        Gesture.SCROLL -> {
                            val token = script.endDrag()
                            if (token >= 0 && e.actionMasked == MotionEvent.ACTION_UP) scrolledTo(token)
                        }
                        Gesture.NONE -> taps.onTouchEvent(e)
                    }
                    gesture = Gesture.NONE
                    return true
                }
            }
            if (gesture == Gesture.NONE) taps.onTouchEvent(e)
            return true // the card keeps every touch that starts on it
        }


        /** This touch is now a swipe, a drag or a pinch: it is no longer a tap. */
        private fun begin(next: Gesture, e: MotionEvent) {
            if (gesture == next) return
            if (gesture == Gesture.NONE) {
                val cancel = MotionEvent.obtain(e)
                cancel.action = MotionEvent.ACTION_CANCEL
                taps.onTouchEvent(cancel)
                cancel.recycle()
            }
            if (gesture == Gesture.SCROLL) script.cancelDrag() // a pinch in the middle of a swipe: back to the reading spot
            gesture = next
        }
    }

    private enum class Gesture { NONE, SCROLL, MOVE, PINCH }

    private enum class Axis { NONE, WIDTH, HEIGHT }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /**
     * The script's lines, as many as fit the band's height. The text is laid out once (per script, text size and
     * width); after that a new word only moves two color marks, and a new line slides the text up over 200 ms. A line
     * that only partly fits at the bottom fades out. A tap finds the word under the finger.
     */
    @SuppressLint("ViewConstructor")
    private class ScriptView(context: Context) : View(context) {

        /** Room on each side for the pill around the first or last word of a line. */
        val side = (6 * resources.displayMetrics.density).roundToInt()

        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.TEXT }
        private val said = ForegroundColorSpan(GlowCard.TEXT_DIM)
        private val next = ForegroundColorSpan(GlowCard.KNOB)

        // The whole script as one text: where each word starts and ends in it, and which word each character belongs
        // to (a space belongs to the word before it).
        private var text = SpannableString("")
        private var starts = IntArray(0)
        private var ends = IntArray(0)
        private var wordAt = IntArray(1)
        private var textLayout: StaticLayout? = null

        /** The next word to say; [count] means the script is finished. */
        var position = 0
            private set
        val count: Int get() = starts.size
        val done: Float get() = if (count == 0) 0f else position.toFloat() / count

        // Sizes in pixels, all set from the text size.
        private var ascent = 0 // from the baseline up (a negative number)
        private var descent = 0 // from the baseline down
        private var gap = 0 // extra space between lines
        private var lineStep = 0 // one line, top to top

        // The text is drawn moved up by `offset` pixels. A new line slides it from `from` to `to`.
        private var offset = 0f
        private var from = 0f
        private var to = 0f
        private var startedAt = 0L
        private var sliding = false
        private var topLine = -1
        private var dragging = false // a finger is scrolling the words: the reading spot does not move them

        /** The height of the words' area in pixels, from the band's own size; never less than one line. 0 = three lines. */
        var boxHeight = 0
            set(value) {
                if (value != field) {
                    field = value
                    requestLayout()
                    invalidate()
                }
            }

        // Fades the part of a line that does not fully fit at the bottom.
        private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private var fadeFrom = -1
        private var fadeTo = -1

        init {
            isSoundEffectsEnabled = false
            applySize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_SP, resources.displayMetrics))
        }

        /** Turns the soft shadow under every word on or off. */
        fun setShadow(on: Boolean, radius: Float) {
            if (on) paint.setShadowLayer(radius, 0f, radius / 3f, WORD_SHADOW) else paint.clearShadowLayer()
            invalidate()
        }

        fun setFont(font: Typeface) {
            paint.typeface = font
            applySize(paint.textSize)
        }

        fun setTextSizeSp(sp: Float) {
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.coerceIn(MIN_SP, MAX_SP), resources.displayMetrics)
            if (px != paint.textSize) applySize(px)
        }

        fun setScript(tokens: List<String>, paragraphStart: BooleanArray) {
            val n = tokens.size
            val all = StringBuilder()
            val s = IntArray(n)
            val e = IntArray(n)
            for (i in 0 until n) {
                if (i > 0) all.append(if (i < paragraphStart.size && paragraphStart[i]) '\n' else ' ')
                s[i] = all.length
                all.append(tokens[i])
                e[i] = all.length
            }
            val map = IntArray(all.length + 1)
            for (i in 0 until n) {
                val stop = if (i + 1 < n) s[i + 1] else map.size
                for (c in s[i] until stop) map[c] = i
            }
            text = SpannableString(all)
            starts = s
            ends = e
            wordAt = map
            position = 0
            recolor()
            rebuild()
        }

        /** Returns true when the next word changed. */
        fun setPosition(token: Int): Boolean {
            val p = token.coerceIn(0, count)
            if (p == position) return false // the same word: nothing moves, nothing is redrawn
            position = p
            recolor()
            place(slide = true)
            invalidate()
            return true
        }

        /** Sets the text size and everything that follows from it, then lays the script out again. */
        private fun applySize(px: Float) {
            paint.textSize = px
            val metrics = paint.fontMetricsInt
            ascent = metrics.ascent
            descent = metrics.descent
            gap = (px * LINE_GAP).roundToInt()
            lineStep = descent - ascent + gap // the same sum the text layout uses for every line
            dragging = false
            rebuild()
            requestLayout()
        }

        /** Moves the two color marks: dim up to the next word, white on the next word. Nothing is laid out again. */
        private fun recolor() {
            val n = count
            val saidEnd = if (position < n) starts[position] else text.length
            if (saidEnd > 0) text.setSpan(said, 0, saidEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) else text.removeSpan(said)
            if (position < n && ends[position] > starts[position]) {
                text.setSpan(next, starts[position], ends[position], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                text.removeSpan(next)
            }
        }

        /** Lays the script out for the current width and text size, then jumps straight to the next word's line. */
        private fun rebuild() {
            val w = width - 2 * side
            textLayout = if (w > 0 && count > 0) {
                StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                    .setIncludePad(false)
                    .setLineSpacing(gap.toFloat(), 1f)
                    .setUseLineSpacingFromFallbacks(false) // every line the same height, so every step up is the same
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE) // never split a word across lines
                    .build()
            } else {
                null
            }
            topLine = -1
            sliding = false
            place(slide = false)
            invalidate()
        }

        /** The finger moved [dy] pixels: down shows earlier lines, up shows later ones. */
        fun dragBy(dy: Float) {
            val l = textLayout ?: return
            dragging = true
            sliding = false
            val last = l.getLineTop(max(0, l.lineCount - 1)).toFloat()
            offset = (offset - dy).coerceIn(0f, last)
            invalidate()
        }

        /** The finger lifted: the nearest line slides to the top. Returns that line's first word, or -1. */
        fun endDrag(): Int {
            if (!dragging) return -1
            dragging = false
            val l = textLayout ?: return -1
            val line = l.getLineForVertical((offset + lineStep / 2f).toInt())
            slideTo(line, slide = true)
            return wordAt[l.getLineStart(line).coerceIn(0, wordAt.size - 1)]
        }

        /** The swipe was cut short: back to the line with the next word. */
        fun cancelDrag() {
            if (!dragging) return
            dragging = false
            topLine = -1
            place(slide = true)
        }

        /** When the next word is on another line, brings its line to the top. */
        private fun place(slide: Boolean) {
            if (dragging) return // the finger has the words; reading picks up from where they settle
            val l = textLayout ?: return
            val n = count
            if (n == 0) return
            // Once the script is finished, the last line stays where it is.
            val line = l.getLineForOffset(starts[min(position, n - 1)])
            if (line != topLine) slideTo(line, slide)
        }

        private fun slideTo(line: Int, slide: Boolean) {
            val l = textLayout ?: return
            topLine = line
            val target = l.getLineTop(line).toFloat()
            // Slides only while it can be seen, and never when the phone is set to remove animations.
            if (slide && isShown && ValueAnimator.areAnimatorsEnabled()) {
                from = offset
                to = target
                startedAt = AnimationUtils.currentAnimationTimeMillis()
                sliding = true
            } else {
                offset = target
                sliding = false
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = if (boxHeight > 0) max(boxHeight, lineStep) else LINES * lineStep
            setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w != oldw) rebuild()
        }

        override fun onDraw(canvas: Canvas) {
            val l = textLayout ?: return
            if (sliding) {
                val t = ((AnimationUtils.currentAnimationTimeMillis() - startedAt) / SLIDE_MS).coerceIn(0f, 1f)
                val rest = 1f - t
                offset = from + (to - from) * (1f - rest * rest * rest) // quick start, gentle stop
                if (t < 1f) postInvalidateOnAnimation() else sliding = false
            }
            // The lines that fully fit, from the top; whatever is left below shows the next line fading out.
            val whole = if (lineStep > 0) height / lineStep * lineStep else height
            val fades = whole < height
            val saved = if (fades) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.save()
            canvas.translate(side.toFloat(), -offset)
            l.draw(canvas) // draws only the lines inside the window
            canvas.restore()
            if (fades) {
                if (fadeFrom != whole || fadeTo != height) {
                    fadeFrom = whole
                    fadeTo = height
                    fade.shader = LinearGradient(0f, whole.toFloat(), 0f, height.toFloat(), FADE_START, 0, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, whole.toFloat(), width.toFloat(), height.toFloat(), fade)
            }
            canvas.restoreToCount(saved)
        }

        /** The word under a finger at (x, y) in this view, or -1 for the empty space under the script's last line. */
        fun wordAtPoint(x: Float, y: Float): Int {
            val l = textLayout ?: return -1
            if (count == 0) return -1
            val inText = y + offset
            if (inText < 0f || inText >= l.height) return -1
            val line = l.getLineForVertical(inText.toInt())
            val at = l.getOffsetForHorizontal(line, x - side)
            return wordAt[at.coerceIn(0, wordAt.size - 1)]
        }

        private companion object {
            const val LINES = 3
            const val DEFAULT_SP = 24f
            const val MIN_SP = 12f
            const val MAX_SP = 64f
            const val SLIDE_MS = 200f
            const val LINE_GAP = BandBox.LINE_GAP
            const val FADE_START = 0x8C000000.toInt() // the part-line starts at 55% and fades to nothing
        }
    }

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val WORD_SHADOW = 0xB3000000.toInt() // black at 70%
    }

    /** The handle under the words: drag it to move the band. Lavender while listening, faint when not. */
    private class Handle(context: Context) : View(context) {
        var listening = false
            set(value) {
                if (value != field) {
                    field = value
                    invalidate()
                }
            }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val density = context.resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val w = 36 * density
            val h = 4 * density
            box.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            pill.color = if (listening) Palette.LAVENDER else 0x59FFFFFF // white at 35%
            canvas.drawRoundRect(box, h / 2f, h / 2f, pill)
        }
    }

    /** How much of the script is done: a thin white line on a dark track. */
    private class Bar(context: Context) : View(context) {
        var progress = 0f
            set(value) {
                val v = value.coerceIn(0f, 1f)
                if (v != field) {
                    field = v
                    invalidate()
                }
            }
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.TRACK }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlowCard.KNOB }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            box.set(0f, 0f, w, h)
            canvas.drawRoundRect(box, r, r, back)
            if (progress > 0f) {
                val filled = max(w * progress, h) // never thinner than a dot
                if (layoutDirection == LAYOUT_DIRECTION_RTL) box.set(w - filled, 0f, w, h) else box.set(0f, 0f, filled, h)
                canvas.drawRoundRect(box, r, r, fill)
            }
        }
    }
}
