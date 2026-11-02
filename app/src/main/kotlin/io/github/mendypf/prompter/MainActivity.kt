package io.github.mendypf.prompter

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * The app's one screen, in the band's glass and navy: the on/off switch, the script, the band's look and size, the
 * button that opens the camera, and the two one-time setup steps, which show only until they are done.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val saveSoon = Runnable { saveScript() }

    private lateinit var heroOrb: OrbDrawable
    private lateinit var powerTitle: TextView
    private lateinit var power: PillSwitch
    private lateinit var scriptBox: EditText
    private lateinit var wordCount: TextView
    private lateinit var tiles: List<LookTile>
    private lateinit var sizeNumber: TextView
    private lateinit var sizePreview: SizePreview
    private lateinit var smaller: TextView
    private lateinit var bigger: TextView
    private lateinit var cameraLine: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var micStep: LinearLayout
    private lateinit var micTitle: TextView
    private lateinit var micHelp: TextView
    private lateinit var micButton: TextView
    private lateinit var switchStep: LinearLayout
    private lateinit var switchTitle: TextView

    // Android stops showing the mic question once it was refused twice; the mic button then opens App info instead.
    private var micBlocked = false
    private var keyboardUp = false
    private var tooLongToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.init(this)
        micBlocked = savedInstanceState?.getBoolean(STATE_MIC_BLOCKED) == true

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(32))
        }

        // The name, with the app's little orb.
        val header = row(column)
        header.addView(View(this).apply { background = OrbDrawable(halo = false) }, LinearLayout.LayoutParams(dp(30), dp(30)))
        label(header, "Prompter", 22f, Palette.TITLE, bold = true, width = 0, weight = 1f, left = 10)

        // The on/off switch. Off (the default) means the camera opens on its own: no band, no mic.
        val hero = card(column, top = 16, radius = 32, dots = true, pad = 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        heroOrb = OrbDrawable(halo = true)
        hero.addView(
            View(this).apply {
                background = heroOrb
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(132), dp(132)),
        )
        // "Prompter is on/off" with the switch right beside it, the pair centered under the orb.
        val powerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hero.addView(powerRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        powerTitle = label(powerRow, "", 26f, Palette.TITLE, bold = true, width = WRAP).apply {
            // As wide as the longer of the two words, so the switch never shifts when it flips.
            minWidth = paint.measureText("Prompter is off").toInt() + 1
        }
        power = PillSwitch(this) { switchPower(it) }
        powerRow.addView(power, LinearLayout.LayoutParams(dp(76), dp(42)).apply { marginStart = dp(18) })

        // The script. It saves by itself half a second after typing stops, and again whenever the screen is left.
        val scriptCard = card(column, top = 14)
        val scriptHead = headRow(scriptCard, LineIcon.Kind.SCRIPT, "Your script").parent as LinearLayout
        wordCount = label(scriptHead, "", 13f, Palette.DIM, width = WRAP)
        scriptBox = EditText(this).apply {
            background = inputBackground()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            textSize = 16f
            setTextColor(Palette.TEXT)
            hint = "Paste or type your script here"
            setHintTextColor(0x99878B9D.toInt())
            highlightColor = 0x669A92E8
            textCursorDrawable = GradientDrawable().apply {
                setColor(Palette.LAVENDER)
                setSize(dp(2), 0)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            setText(ScriptStore.load(this@MainActivity))
            // Set after the text is loaded, so a script saved earlier is never cut; it only limits what is added.
            filters = arrayOf(limitScript())
        }
        scriptCard.addView(scriptBox, fullWidth(top = 14))
        scriptBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                showWords()
                handler.removeCallbacks(saveSoon)
                handler.postDelayed(saveSoon, SAVE_DELAY_MS)
            }
        })
        showWords()

        // The band's look: dark glow, glass (the default) or clear glass. The band uses it the next time the camera
        // opens.
        headRow(column, LineIcon.Kind.LOOK, "Band look", top = 24)
        tiles = listOf(
            LookTile(this, Look.GLOW, "Dark glow") { pickLook(it) },
            LookTile(this, Look.GLASS, "Glass") { pickLook(it) },
            LookTile(this, Look.CLEAR, "Clear glass") { pickLook(it) },
        )
        // The three looks side by side, the same width, each tile a small band in its look.
        val lookRow = row(column, top = 12)
        for ((i, tile) in tiles.withIndex()) {
            lookRow.addView(tile, LinearLayout.LayoutParams(0, dp(104), 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        showLook()

        // The size of the words: - and + in steps of 2. The band's own size comes from a pinch on the camera.
        // Under them, the band itself at that size and in the picked look, so the size can be judged before filming.
        val sizeCard = card(column, top = 24)
        val sizeHead = headRow(sizeCard, LineIcon.Kind.SIZE, "Text size").parent as LinearLayout
        smaller = roundButton(sizeHead, "−", "Smaller text") { changeSize(-SIZE_STEP) } // a minus sign
        sizeNumber = label(sizeHead, "", 18f, Palette.TITLE, bold = true, width = dp(48)).apply { gravity = Gravity.CENTER }
        bigger = roundButton(sizeHead, "+", "Bigger text") { changeSize(SIZE_STEP) }
        sizePreview = SizePreview(this).apply { look = Looks.current(this@MainActivity) }
        sizeCard.addView(sizePreview, fullWidth(top = 16))
        label(
            sizeCard,
            "On the camera, drag the handle under the words or either side of the band to move it. Pinch it side " +
                "to side to change its width, or up and down to change its height. Swipe the words to go back.",
            14f, Palette.DIM, top = 12,
        )
        showSize()

        // The camera button: a glass pill with the orb, two lines and a white play button.
        val cameraRadius = dp(38).toFloat()
        val camera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassPanel(cameraRadius)
            foreground = ripple(cameraRadius)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { open(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), "Could not open the camera.") }
            asButton()
        }
        camera.addView(View(this).apply { background = OrbDrawable(halo = false) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val cameraWords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label(cameraWords, "Open the Pixel Camera", 16f, Palette.TITLE, bold = true)
        cameraLine = label(cameraWords, "", 13f, Palette.DIM, top = 2)
        camera.addView(cameraWords, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(10)
        })
        camera.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Palette.TITLE)
                }
                foreground = InsetDrawable(LineIcon(LineIcon.Kind.PLAY, GlowCard.CORE), dp(13))
            },
            LinearLayout.LayoutParams(dp(52), dp(52)),
        )
        column.addView(camera, fullWidth(top = 24))

        // The one-time setup. Each step shows only until it is done; onResume checks again.
        setupCard = card(column, top = 24)
        label(setupCard, "One-time setup", 17f, Palette.TITLE, bold = true)

        micStep = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        micTitle = headRow(micStep, LineIcon.Kind.MIC, "", top = 16)
        micHelp = label(
            micStep,
            "Android will not ask again, so open App info, tap Permissions, then Microphone, and pick Allow only " +
                "while using the app.",
            14f, Palette.DIM, top = 10,
        )
        micButton = pillButton(micStep, "Allow the mic", top = 12) { askForMic() }
        setupCard.addView(micStep, fullWidth())

        switchStep = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        switchTitle = headRow(switchStep, LineIcon.Kind.ACCESS, "", top = 20)
        pillButton(switchStep, "Open Accessibility settings", top = 12) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Could not open Settings.")
        }
        // The app's own name, as Android shows it in the Accessibility list and in App info.
        val name = applicationInfo.loadLabel(packageManager)
        label(
            switchStep,
            "Find $name in the list and turn it on. If the switch is grey, tap it once, then open App info " +
                "for $name, tap the three dots at the top right, tap Allow restricted settings, and try again.",
            14f, Palette.DIM, top = 10,
        )
        setupCard.addView(switchStep, fullWidth())

        // What happens to the script and the mic.
        val footer = row(column, top = 26).apply { gravity = Gravity.TOP }
        footer.addView(
            View(this).apply { background = LineIcon(LineIcon.Kind.LOCK, Palette.DIM) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(1) },
        )
        label(
            footer,
            "Your script stays on this phone. The mic is used only when the prompter is on, the Pixel Camera is " +
                "open, and you double tap the band.",
            13f, Palette.DIM, width = 0, weight = 1f, left = 10,
        )

        val scroll = ScrollView(this).apply {
            addView(column)
            // With a hardware keyboard Android may rest the focus on the whole page; no grey layer over it then.
            defaultFocusHighlightEnabled = false
        }
        val root = FrameLayout(this).apply {
            background = NightBackground()
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        // The screen runs edge to edge: keep everything clear of the status bar, the navigation bar and the keyboard.
        // The space goes around the scrolling part, so it gets shorter and can still scroll to the typing spot.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val clear = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            view.setPadding(clear.left, clear.top, clear.right, clear.bottom)
            val up = insets.isVisible(WindowInsets.Type.ime())
            if (up && !keyboardUp && scriptBox.hasFocus()) {
                // The keyboard just came up: once the screen has shrunk, scroll so the typing spot shows above it.
                scriptBox.post {
                    val spot = scriptBox.selectionEnd
                    if (spot >= 0) scriptBox.bringPointIntoView(spot)
                }
            }
            keyboardUp = up
            WindowInsets.CONSUMED
        }
        setContentView(root)
        // Light clock, battery and navigation icons, so they show on the navy.
        val darkIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(0, darkIcons)
        power.setOn(ScriptStore.prompterOn(this), animate = false)
    }

    override fun onResume() {
        super.onResume()
        power.setOn(ScriptStore.prompterOn(this), animate = false)
        sizePreview.bandChanged() // a pinch on the band may have changed its size
        showSize()
        showLook()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only this survives a turn of the screen or a restart of the app; the script itself is saved on its own.
        outState.putBoolean(STATE_MIC_BLOCKED, micBlocked)
    }

    override fun onPause() {
        super.onPause()
        // Save right away, so the band has the newest script the moment the camera opens.
        handler.removeCallbacks(saveSoon)
        saveScript()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST && grantResults.isNotEmpty() && !micAllowed() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            micBlocked = true
        }
        render()
    }

    /** Shows each setup step only while it still needs doing. */
    private fun render() {
        val micDone = micAllowed()
        val switchDone = serviceOn()
        if (micDone) micBlocked = false
        micStep.visibility = if (micDone) View.GONE else View.VISIBLE
        switchStep.visibility = if (switchDone) View.GONE else View.VISIBLE
        setupCard.visibility = if (micDone && switchDone) View.GONE else View.VISIBLE
        // Number the steps only while both are left.
        val both = !micDone && !switchDone
        micTitle.text = if (both) "1. Let it use the mic" else "Let it use the mic"
        switchTitle.text = if (both) "2. Turn it on in Accessibility" else "Turn it on in Accessibility"
        micHelp.visibility = if (micBlocked) View.VISIBLE else View.GONE
        micButton.text = if (micBlocked) "Open App info" else "Allow the mic"
        showPower()
    }

    /**
     * The switch was flipped: remember it. The band checks it each time the camera opens. Turning it off also ends
     * anything running now: the mic closes and the band goes, even if the camera is still in front (split screen).
     */
    private fun switchPower(on: Boolean) {
        ScriptStore.setPrompterOn(this, on)
        Probe.log(if (on) "prompter switched on in the app" else "prompter switched off in the app")
        if (!on) ProbeService.running?.prompterSwitchedOff()
        showPower()
    }

    /** The words beside the switch, the orb's light and the camera button's second line follow the switch. */
    private fun showPower() {
        val on = ScriptStore.prompterOn(this)
        heroOrb.lit = on
        powerTitle.text = if (on) "Prompter is on" else "Prompter is off"
        cameraLine.text = if (on) "Double tap the band to start" else "The prompter is off"
    }

    private fun pickLook(look: Look) {
        Looks.set(this, look)
        Probe.log("band look: $look")
        showLook()
    }

    private fun showLook() {
        val now = Looks.current(this)
        for (tile in tiles) tile.picked = tile.look == now
        if (::sizePreview.isInitialized) sizePreview.look = now // the size card is built after the look tiles
    }

    /** Counts the words the way the band cuts them: every run of characters between whitespace is one word. */
    private fun showWords() {
        val text = scriptBox.text
        var n = 0
        var inWord = false
        for (i in 0 until text.length) {
            val word = !text[i].isWhitespace()
            if (word && !inWord) n++
            inWord = word
        }
        wordCount.text = if (n == 1) "1 word" else "$n words"
    }

    /**
     * Keeps the script under [MAX_SCRIPT_CHARS] characters (about two hours of speaking), so pasting something huge
     * cannot freeze the app or the band. What does not fit is dropped, and a short message says so.
     */
    private fun limitScript() = InputFilter { source, start, end, dest, dstart, dend ->
        val room = MAX_SCRIPT_CHARS - (dest.length - (dend - dstart))
        if (end <= start || room >= end - start) return@InputFilter null // a delete, or it fits
        tooLongToast?.cancel()
        tooLongToast = Toast.makeText(this, "The script is too long, so the end was cut off.", Toast.LENGTH_SHORT)
            .also { it.show() }
        if (room <= 0) return@InputFilter ""
        var keep = start + room
        if (Character.isHighSurrogate(source[keep - 1])) keep-- // never cut an emoji in half
        source.subSequence(start, keep)
    }

    private fun micAllowed() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun serviceOn() = getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun askForMic() {
        if (micBlocked) {
            val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            open(appInfo, "Could not open App info.")
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
    }

    private fun saveScript() = ScriptStore.save(this, scriptBox.text.toString())

    private fun changeSize(by: Float) {
        ScriptStore.setTextSizeSp(this, ScriptStore.textSizeSp(this) + by)
        showSize()
    }

    private fun showSize() {
        val sp = ScriptStore.textSizeSp(this)
        sizeNumber.text = sp.roundToInt().toString()
        sizeNumber.contentDescription = "Text size ${sp.roundToInt()}"
        sizePreview.sizeSp = sp
        smaller.isEnabled = sp > ScriptStore.MIN_TEXT_SP
        bigger.isEnabled = sp < ScriptStore.MAX_TEXT_SP
        smaller.alpha = if (smaller.isEnabled) 1f else 0.35f
        bigger.alpha = if (bigger.isEnabled) 1f else 0.35f
    }

    private fun open(intent: Intent, failed: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Probe.log("could not open ${intent.action}: ${e.message}")
            Toast.makeText(this, failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** The script box: a dark inset in the card, with an edge that turns lavender while typing. */
    private fun inputBackground(): StateListDrawable {
        fun box(border: Int, color: Int) = GradientDrawable().apply {
            setColor(0x8C020619.toInt()) // the band's navy at 55%
            cornerRadius = dp(20).toFloat()
            setStroke(border, color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(dp(2), Palette.LAVENDER))
            addState(intArrayOf(), box(dp(1), 0x1FFFFFFF))
        }
    }

    /** A frosted glass card holding a column of things. */
    private fun card(parent: LinearLayout, top: Int, radius: Int = 28, dots: Boolean = false, pad: Int = 20): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassPanel(dp(radius).toFloat(), dots)
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }.also { parent.addView(it, fullWidth(top)) }

    private fun row(parent: LinearLayout, top: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }.also { parent.addView(it, fullWidth(top)) }

    /** A row with a round icon and a title. Returns the title; its parent is the row, for anything that goes after. */
    private fun headRow(parent: LinearLayout, kind: LineIcon.Kind, title: String, top: Int = 0): TextView {
        val r = row(parent, top)
        r.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x249A92E8)
                    setStroke(dp(1), 0x26FFFFFF)
                }
                foreground = InsetDrawable(LineIcon(kind, Palette.TITLE), dp(9))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )
        return label(r, title, 17f, Palette.TITLE, bold = true, width = 0, weight = 1f, left = 12)
    }

    /** A wide white pill with navy words: the band's own button, full width. */
    private fun pillButton(parent: LinearLayout, text: String, top: Int, onTap: () -> Unit): TextView {
        val radius = dp(26).toFloat()
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(GlowCard.CORE)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Palette.TITLE)
                cornerRadius = radius
            }
            foreground = ripple(radius, 0x33020619)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            asButton()
        }.also { parent.addView(it, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(top) }) }
    }

    /** A round glass button for - and +. */
    private fun roundButton(parent: LinearLayout, text: String, description: String, onTap: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Palette.TITLE)
            gravity = Gravity.CENTER
            contentDescription = description
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x269A92E8)
                setStroke(dp(1), 0x33FFFFFF)
            }
            foreground = ripple(dp(23).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            asButton()
        }.also { parent.addView(it, LinearLayout.LayoutParams(dp(46), dp(46))) }

    private fun ripple(radius: Float, color: Int = 0x33FFFFFF): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(color), null, mask)
    }

    /** Screen readers call it a button. */
    private fun View.asButton() {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    private fun label(
        parent: LinearLayout,
        value: CharSequence,
        size: Float,
        color: Int,
        bold: Boolean = false,
        top: Int = 0,
        width: Int = MATCH,
        weight: Float = 0f,
        left: Int = 0,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }.also {
        parent.addView(it, LinearLayout.LayoutParams(width, WRAP, weight).apply {
            topMargin = dp(top)
            marginStart = dp(left)
        })
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SAVE_DELAY_MS = 500L
        const val MAX_SCRIPT_CHARS = 100_000
        const val SIZE_STEP = 2f
        const val MIC_REQUEST = 1
        const val STATE_MIC_BLOCKED = "mic_blocked"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
