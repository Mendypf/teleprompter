package io.github.mendypf.prompter

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One log for the whole test: logcat (tag PrompterTest) and a file the laptop reads with `adb shell run-as`. */
object Probe {
    private const val TAG = "PrompterTest"
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "probe-log.txt")
    }

    @Synchronized
    fun log(line: String) {
        Log.i(TAG, line)
        val f = file ?: return
        runCatching {
            if (f.length() > 2_000_000) {
                val old = File(f.parentFile, "probe-log.old.txt")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(clock.format(Date()) + " " + line + "\n")
        }
    }
}
