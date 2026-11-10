package io.github.mendypf.prompter.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window names come from the prompter's log on a Pixel 10 (Android 16), 2026-11-10. */
class FrontWindowTest {

    @Test
    fun `the Pixel brightening the screen when recording starts is not leaving the camera`() {
        assertFalse(FrontWindow.isAppScreen("android.widget.FrameLayout")) // com.android.pixeldisplayservice
    }

    @Test
    fun `the keyboard coming or going is not leaving the camera`() {
        assertFalse(FrontWindow.isAppScreen("android.widget.LinearLayout")) // com.google.android.inputmethod.latin
        assertFalse(FrontWindow.isAppScreen("android.inputmethodservice.SoftInputWindow"))
    }

    @Test
    fun `popups and dialogs over the camera are not leaving it`() {
        assertFalse(FrontWindow.isAppScreen("android.app.Dialog"))
        assertFalse(FrontWindow.isAppScreen("android.app.AlertDialog"))
        assertFalse(FrontWindow.isAppScreen("android.widget.PopupWindow\$PopupDecorView"))
    }

    @Test
    fun `going home or to another app is leaving the camera`() {
        assertTrue(FrontWindow.isAppScreen("com.google.android.apps.nexuslauncher.NexusLauncherActivity"))
        assertTrue(FrontWindow.isAppScreen("com.google.android.apps.photos.home.HomeActivity"))
        assertTrue(FrontWindow.isAppScreen("com.android.internal.app.ChooserActivity"))
    }

    @Test
    fun `a game built on Android's own screen class is still an app screen`() {
        assertTrue(FrontWindow.isAppScreen("android.app.NativeActivity"))
    }

    @Test
    fun `a window with no name counts as an app screen, so the mic never stays open by mistake`() {
        assertTrue(FrontWindow.isAppScreen(null))
        assertTrue(FrontWindow.isAppScreen(""))
    }
}
