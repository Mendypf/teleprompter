package io.github.mendypf.prompter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test hooks for the laptop, e.g. `adb shell am broadcast -a io.github.mendypf.prompter.TOGGLE -n
 * io.github.mendypf.prompter/.AdbReceiver`. Locked with the DUMP permission in the manifest: only adb and the system
 * hold it, so no app on the phone can use these.
 * - TOGGLE: Start or Stop, as if the band's button was tapped.
 * - SHOW: show the band.
 * - HEAR (text, final): words as if the speech AI heard them (the laptop's test phone has no speech AI).
 * - SCRIPT (text): save a script, as if it was typed in the app.
 * - LOOK (look = glow or glass): pick the band's look, as if it was picked in the app.
 * - PINCH (factor, vertical): a two-finger pinch on the band, above 1 apart, below 1 together; side to side (width)
 *   unless vertical is true (height).
 */
class AdbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Probe.init(context)
        if (intent.action == "io.github.mendypf.prompter.SCRIPT") {
            val text = intent.getStringExtra("text").orEmpty()
            ScriptStore.save(context, text)
            Probe.log("adb: SCRIPT (${text.length} characters)")
            return
        }
        if (intent.action == "io.github.mendypf.prompter.LOOK") {
            val look = when (intent.getStringExtra("look")?.lowercase()) {
                "glass" -> Look.GLASS
                "clear" -> Look.CLEAR
                else -> Look.GLOW
            }
            Looks.set(context, look)
            Probe.log("adb: LOOK $look")
            return
        }
        val service = ProbeService.running
        if (service == null) {
            Probe.log("adb: ${intent.action} ignored, the service is not on")
            return
        }
        Probe.log("adb: ${intent.action}")
        when (intent.action) {
            "io.github.mendypf.prompter.TOGGLE" -> service.toggle()
            "io.github.mendypf.prompter.SHOW" -> service.showBand()
            "io.github.mendypf.prompter.HEAR" ->
                service.testHeard(intent.getStringExtra("text").orEmpty(), intent.getBooleanExtra("final", false))
            "io.github.mendypf.prompter.PINCH" -> {
                val factor = intent.getFloatExtra("factor", 1f)
                // Only real pinch sizes; anything else (NaN, infinity, zero) is ignored.
                if (factor.isFinite() && factor in 0.2f..5f) {
                    service.testPinch(factor, intent.getBooleanExtra("vertical", false))
                } else {
                    Probe.log("adb: PINCH ignored")
                }
            }
        }
    }
}
