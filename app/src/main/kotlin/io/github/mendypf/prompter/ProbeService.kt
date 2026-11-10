package io.github.mendypf.prompter

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import io.github.mendypf.prompter.core.Follower
import io.github.mendypf.prompter.core.FrontWindow
import io.github.mendypf.prompter.core.Script

/**
 * The prompter. Android keeps it running while the screen is on, once it is switched on in Accessibility settings.
 * It shows the band only while the camera app is in front, and listens only after a double tap on the band.
 * What the speech AI hears moves the script along; a tap on a word continues from there.
 *
 * It keeps its first name (ProbeService) on purpose: Android remembers the Accessibility switch by this name, so the
 * switch stays on when the app is updated.
 */
class ProbeService : AccessibilityService() {

    private var band: PrompterBand? = null
    private var listener: Listener? = null
    private var follower: Follower? = null
    private var scriptChanges = -1
    private var textSize = 0f
    private var cameraInFront = false
    private var screenWatch = false
    private var offNoted = false // the "prompter is off" line goes in the log once per camera visit

    // What the band shows now, so 10 updates a second only touch the band when something changed.
    private var shownMode: PrompterBand.Mode? = null
    private var shownNote: String? = null

    // The screen going off ends listening, so the mic is never left open in a pocket. The band goes too, so it is
    // never on the lock screen; Android says the camera is in front again once the phone is unlocked.
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (listener?.isRunning == true) {
                Probe.log("screen off: stopped listening")
                listener?.stop()
            }
            if (cameraInFront) {
                cameraInFront = false
                band?.hide()
                Probe.log("screen off: band hidden")
            }
        }
    }

    override fun onServiceConnected() {
        Probe.init(this)
        band = PrompterBand(this, onToggle = { toggle() }, onWordTap = { token -> jumpTo(token) })
        listener = Listener(this, onUpdate = { snapshot -> showListener(snapshot) }, onHeard = { text, final -> heard(text, final) })
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        screenWatch = true
        running = this
        Probe.log("service on")
    }

    /**
     * A double tap on the band: start or stop. Listening can only start while the camera is in front and the prompter is switched
     * on in the app; stopping always works.
     */
    fun toggle() {
        val l = listener ?: return
        when {
            l.isRunning -> l.stop()
            !cameraInFront -> Probe.log("start ignored: the camera is not in front") // the band is hidden then anyway
            !ScriptStore.prompterOn(this) -> Probe.log("start ignored: the prompter is switched off in the app")
            !loadScript() -> Probe.log("start ignored: no script yet")
            else -> {
                val f = follower ?: return
                if (f.finished) {
                    f.restart()
                    band?.setPosition(f.position)
                }
                l.start()
            }
        }
        refresh()
    }

    /**
     * The prompter was switched off in the app. Anything running ends now: the mic closes and the band goes, so the
     * mic is never left open while the prompter is off.
     */
    fun prompterSwitchedOff() {
        if (listener?.isRunning == true) {
            Probe.log("switched off: stopped listening")
            listener?.stop()
        }
        if (cameraInFront) {
            cameraInFront = false
            band?.hide()
            Probe.log("switched off: band hidden")
        }
        refresh()
    }

    /** The user tapped a word on the band: continue from there. */
    private fun jumpTo(token: Int) {
        val f = follower ?: return
        f.jumpTo(token)
        band?.setPosition(f.position)
        Probe.log("jumped to word ${f.position + 1} of ${f.script.tokens.size}")
        refresh()
    }

    /** What the speech AI wrote down. Partial results come many times a second; a final one closes a stretch. */
    private fun heard(text: String, final: Boolean) {
        val f = follower ?: return
        val before = f.position
        val now = if (final) f.heardFinal(text) else f.heardPartial(text)
        if (now != before) band?.setPosition(now)
        if (final && text.isNotEmpty()) {
            Probe.log(if (f.finished) "at the end of the script" else "at word ${now + 1} of ${f.script.tokens.size}")
        }
        if (f.finished && listener?.isRunning == true) {
            Probe.log("the script is done, so the mic goes off")
            listener?.stop() // the mic goes off when the script is done
        }
        refresh()
    }

    /** For tests from the laptop only (the receiver is locked to adb): feed words as if the speech AI heard them. */
    fun testHeard(text: String, final: Boolean) {
        if (!cameraInFront) {
            Probe.log("test words ignored: the camera is not in front")
            return
        }
        if (follower == null && !loadScript()) return
        heard(text, final)
    }

    /** For tests from the laptop only: a pinch on the band, side to side or up and down. */
    fun testPinch(factor: Float, vertical: Boolean) {
        band?.testPinch(factor, vertical)
    }

    private fun showListener(snapshot: Listener.Snapshot) {
        listenerProblem = snapshot.problem
        refresh()
    }

    private var listenerProblem: String? = null

    /** Puts the right mode and status line on the band, touching it only when something changed. */
    private fun refresh() {
        val b = band ?: return
        val f = follower
        val listening = listener?.isRunning == true
        val mode = when {
            listening -> PrompterBand.Mode.LISTENING
            f == null -> PrompterBand.Mode.NO_SCRIPT
            else -> PrompterBand.Mode.READY
        }
        val note = listenerProblem ?: if (!listening && f != null && f.finished) "Done. Tap a word to go back" else null
        if (mode != shownMode) {
            shownMode = mode
            b.setMode(mode)
            shownNote = null // the band clears its status line when the mode changes
        }
        if (note != shownNote) setNote(note)
    }

    private fun setNote(text: String?) {
        shownNote = text
        band?.note(text)
    }

    /** Reads the script if it changed since last time. False when there is no script yet. */
    private fun loadScript(): Boolean {
        val b = band ?: return false
        val changes = ScriptStore.changes(this)
        if (changes != scriptChanges || follower == null) {
            scriptChanges = changes
            val text = ScriptStore.load(this)
            val f = if (text.isBlank()) null else Follower(Script(text))
            follower = f
            if (f == null) {
                b.setScript(emptyList(), BooleanArray(0))
            } else {
                b.setScript(f.script.tokens, f.script.paragraphStart)
                b.setPosition(f.position)
            }
            Probe.log("script loaded: ${f?.script?.tokens?.size ?: 0} words")
        }
        val size = ScriptStore.textSizeSp(this)
        if (size != textSize) {
            textSize = size
            b.setTextSizeSp(size)
        }
        return follower != null
    }

    fun showBand() {
        loadScript()
        refresh()
        band?.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // While the band is up, every window from another app goes in the log by app and window name (never its
        // contents), so a band that vanishes can be traced to the window that sent it away.
        if (cameraInFront && pkg !in CAMERAS) Probe.log("window: $pkg / ${event.className}")
        // The keyboard, a popup, or the Pixel brightening the screen when recording starts: it floats over the camera,
        // so the camera is still in front.
        val floating = pkg !in CAMERAS && !FrontWindow.isAppScreen(event.className?.toString())
        if (pkg !in CAMERAS && pkg !in PASS_THROUGH && !floating) offNoted = false
        when {
            pkg in CAMERAS -> if (!cameraInFront) {
                // Switched off in the app: the camera opens on its own, with no band and so no mic.
                if (!ScriptStore.prompterOn(this)) {
                    if (!offNoted) Probe.log("camera in front, but the prompter is off: no band")
                    offNoted = true
                    return
                }
                if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    Probe.log("camera in front, but the phone is locked: no band")
                    return
                }
                cameraInFront = true
                Probe.log("camera in front")
                showBand()
            }
            pkg == packageName && event.className?.toString() != MainActivity::class.java.name -> Unit
            pkg in PASS_THROUGH -> Unit
            floating -> Unit
            cameraInFront -> {
                cameraInFront = false
                Probe.log("camera left")
                listener?.stop()
                band?.hide()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (screenWatch) {
            runCatching { unregisterReceiver(screenOff) }
            screenWatch = false
        }
        listener?.stop()
        band?.hide()
        if (running === this) {
            running = null
            Probe.log("service off")
        }
    }

    companion object {
        @Volatile
        var running: ProbeService? = null
            private set

        private val CAMERAS = setOf("com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera")

        // The notification shade and system dialogs come and go over the camera; they do not mean it was left.
        private val PASS_THROUGH = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )
    }
}
