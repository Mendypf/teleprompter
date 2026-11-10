package io.github.mendypf.prompter.core

/**
 * Android tells the prompter each time a window comes up, by app and window name. Only another app's own screen
 * means the camera was left. A window that only floats over the camera does not: the keyboard, a system popup, or
 * the Pixel brightening the screen half a second after recording starts (which used to hide the band mid-take).
 */
object FrontWindow {

    /**
     * True for an app's own screen. Android names an app's screen after the app's class (NexusLauncherActivity);
     * a window that only floats over another app is named after Android's own building blocks
     * (android.widget.FrameLayout, android.app.Dialog). A screen class from Android itself (android.app.NativeActivity,
     * which some games use) and a window with no name still count as screens, so the mic is never left open by mistake.
     */
    fun isAppScreen(className: String?): Boolean {
        val name = className ?: return true
        return !name.startsWith("android.") || name.endsWith("Activity")
    }
}
