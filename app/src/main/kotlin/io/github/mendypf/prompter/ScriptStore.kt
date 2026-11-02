package io.github.mendypf.prompter

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Keeps the script and the text size on this phone. The script is a plain text file in the app's private folder; the
 * text size and a count of script changes sit in the app's private settings. The app has no internet and backup is
 * off, so none of it leaves the phone. Every call is quick enough for the main thread: a script is a few pages.
 */
object ScriptStore {
    const val MIN_TEXT_SP = 14f
    const val MAX_TEXT_SP = 48f
    const val DEFAULT_TEXT_SP = 24f

    private const val FILE_NAME = "script.txt"
    private const val PREFS_NAME = "prompter"
    private const val KEY_TEXT_SP = "text_size_sp"
    private const val KEY_CHANGES = "script_changes"
    private const val KEY_ON = "prompter_on"

    // The saved script, kept in memory after the first read. Only save() writes the file, so this copy stays true.
    private var current: String? = null

    /** The saved script, or "" before there is one. */
    @Synchronized
    fun load(context: Context): String {
        current?.let { return it }
        return try {
            val text = scriptFile(context).readFully().toString(Charsets.UTF_8)
            current = text
            text
        } catch (e: FileNotFoundException) {
            current = "" // nothing saved yet
            ""
        } catch (e: IOException) {
            Probe.log("script could not be read: ${e.message}")
            ""
        }
    }

    /** Saves the script. The old one is replaced in a single step, so a crash never leaves half a script. */
    @Synchronized
    fun save(context: Context, text: String) {
        if (text == load(context)) return
        val file = scriptFile(context)
        var out: FileOutputStream? = null
        try {
            out = file.startWrite() // writes to a new file next to the old one
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out) // makes sure it is on storage, then renames it over the old one
        } catch (e: IOException) {
            out?.let { file.failWrite(it) } // throws the new file away; the old script stays as it was
            Probe.log("script could not be saved: ${e.message}")
            return
        }
        current = text
        val prefs = prefs(context)
        val count = prefs.getInt(KEY_CHANGES, 0) + 1
        prefs.edit().putInt(KEY_CHANGES, count).apply()
        Probe.log("script saved: ${text.length} characters, change $count")
    }

    /** The size of the words on the band, 14 to 48. */
    @Synchronized
    fun textSizeSp(context: Context): Float =
        prefs(context).getFloat(KEY_TEXT_SP, DEFAULT_TEXT_SP).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)

    @Synchronized
    fun setTextSizeSp(context: Context, sp: Float) {
        val size = if (sp.isNaN()) DEFAULT_TEXT_SP else sp.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        prefs(context).edit().putFloat(KEY_TEXT_SP, size).apply()
    }

    /** Whether the band shows over the camera. Off until the user turns it on in the app. */
    @Synchronized
    fun prompterOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    @Synchronized
    fun setPrompterOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** Goes up by one every time a save changes the script, so the band knows to start over on the new one. */
    @Synchronized
    fun changes(context: Context): Int = prefs(context).getInt(KEY_CHANGES, 0)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun scriptFile(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
}
