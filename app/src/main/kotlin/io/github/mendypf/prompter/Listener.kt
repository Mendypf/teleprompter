package io.github.mendypf.prompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The prompter's ears. It opens the mic in voice-recognition mode, the one mode Android lets an accessibility app
 * share with a camera that is recording, and hands every 0.1 s of sound to the Pixel's built-in offline speech AI
 * through a pipe, so the speech AI never opens the mic itself. What the speech AI writes down goes to [onHeard]:
 * partial results while a sentence is still going, then the final text of that stretch. Nothing is kept.
 */
class Listener(
    private val context: Context,
    private val onUpdate: (Snapshot) -> Unit,
    private val onHeard: (text: String, final: Boolean) -> Unit,
) {

    /** [problem] is a short line for the band when something is wrong, or null when all is well. */
    data class Snapshot(val running: Boolean, val problem: String?)

    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val chunks = ArrayBlockingQueue<ByteArray>(64)

    @Volatile private var running = false
    @Volatile private var record: AudioRecord? = null
    @Volatile private var feed: OutputStream? = null
    @Volatile private var hearing: Boolean? = null
    @Volatile private var lastPartial = ""
    @Volatile private var speech = "speech AI: off"
    @Volatile private var dropped = 0
    @Volatile private var session = 0 // each Start gets a new number, so an old mic loop can never keep going

    // Touched on the main thread only.
    private var recognizer: SpeechRecognizer? = null
    private var pipeIn: ParcelFileDescriptor? = null
    private var restarts = 0 // tries since the speech AI last gave words; only these count toward giving up
    private var totalRestarts = 0
    private var supportChecked = false
    private var heardAny = false
    private var segmented = true
    private var lastError = ""

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // checked on the first lines
    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Probe.log("start refused: the mic is not allowed yet")
            speech = "Allow the mic in the Prompter app first"
            publish()
            return
        }
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, RATE * 2))
                .build()
        } catch (e: Exception) {
            Probe.log("start failed: ${e.javaClass.simpleName}: ${e.message}")
            speech = "The mic would not open"
            publish()
            return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Probe.log("start failed: the mic did not initialize")
            r.release()
            speech = "The mic would not open"
            publish()
            return
        }
        // Notes when Android silences the mic or another app starts recording. Removed when the mic closes.
        val watch = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId }
                Probe.log("mic change: silenced=${mine?.isClientSilenced} others=${others(r)}")
            }
        }
        r.registerAudioRecordingCallback(context.mainExecutor, watch)
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Probe.log("start failed: the mic did not start, others=${others(r)}")
            runCatching { r.unregisterAudioRecordingCallback(watch) }
            r.release()
            speech = "The mic would not start"
            publish()
            return
        }
        Probe.log("start: recording=true others=${others(r)}")
        speech = "speech AI: starting"
        record = r
        val gen = ++session
        running = true
        lastPartial = ""
        hearing = null
        restarts = 0
        totalRestarts = 0
        supportChecked = false
        dropped = 0
        heardAny = false
        segmented = true
        chunks.clear()
        thread(name = "mic") { readLoop(r, gen, watch) }
        thread(name = "speech-feed") { feedLoop(gen) }
        startSpeech()
        heardWords() // the quiet countdown starts with the mic
        publish()
    }

    fun stop() = close(null)

    /** Listening cannot work: the mic closes, and [problem] stays on the band until the next start. */
    private fun fail(problem: String) = close(problem)

    private fun close(problem: String?) {
        if (!running) return
        running = false
        main.removeCallbacks(quiet)
        Probe.log("stop: slices dropped=$dropped speech restarts=$totalRestarts" + if (problem != null) " ($problem)" else "")
        runCatching { record?.stop() } // wakes the mic thread, which then lets go of the mic
        stopSpeech()
        hearing = null
        speech = problem ?: "speech AI: off"
        savePlace()
        publish()
    }

    /** The speech AI stopped without a final result: what it heard so far counts, and the next words start from there. */
    private fun savePlace() {
        lastPartial = ""
        onHeard("", true)
    }

    private fun readLoop(r: AudioRecord, gen: Int, watch: AudioManager.AudioRecordingCallback) {
        val buf = ShortArray(SLICE)
        val bytes = ByteBuffer.allocate(SLICE * 2).order(ByteOrder.LITTLE_ENDIAN)
        var slices = 0
        var sum = 0.0
        var samples = 0
        var zeros = 0
        var peak = 0
        var second = 0
        var lastUi = 0L
        var badReads = 0
        var lastBadLog = 0L
        var badSince = 0L // when the current run of failed reads began
        try {
            while (running && session == gen) {
                val n = r.read(buf, 0, SLICE)
                if (n <= 0) {
                    if (running && session == gen) {
                        badReads++
                        val now = SystemClock.uptimeMillis()
                        if (badSince == 0L) badSince = now
                        if (now - lastBadLog >= 1000) {
                            lastBadLog = now
                            Probe.log("mic read error $n (x$badReads)")
                            badReads = 0
                        }
                        // The mic is gone, or has failed for 3 s: close it rather than keep it open for nothing.
                        if (n == AudioRecord.ERROR_DEAD_OBJECT || now - badSince >= 3000) {
                            Probe.log("mic failing for ${now - badSince} ms: closing it")
                            break
                        }
                        hearing = false
                        if (now - lastUi >= 100) {
                            lastUi = now
                            publish()
                        }
                    }
                    Thread.sleep(20)
                    continue
                }
                badSince = 0L
                var s2 = 0.0
                var z = 0
                var p = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    s2 += (v * v).toDouble()
                    if (v == 0) z++
                    val a = if (v < 0) -v else v
                    if (a > p) p = a
                }
                bytes.clear()
                bytes.asShortBuffer().put(buf, 0, n)
                if (!chunks.offer(bytes.array().copyOf(n * 2))) dropped++

                sum += s2
                samples += n
                zeros += z
                if (p > peak) peak = p
                if (++slices == 50) {
                    second += 5
                    val silenced = r.activeRecordingConfiguration?.isClientSilenced
                    val others = others(r)
                    hearing = !(silenced == true || zeros == samples)
                    Probe.log(
                        String.format(
                            Locale.US, "mic at %d s: level=%.1fdBFS peak=%d zeros=%d%% silenced=%s others=%s",
                            second, dbfs(sqrt(sum / samples)), peak, zeros * 100 / samples, silenced, others,
                        ),
                    )
                    slices = 0
                    sum = 0.0
                    samples = 0
                    zeros = 0
                    peak = 0
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastUi >= 100) {
                    lastUi = now
                    publish()
                }
            }
        } catch (e: Exception) {
            Probe.log("mic thread stopped: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { r.stop() }
            runCatching { r.unregisterAudioRecordingCallback(watch) }
            r.release()
            if (record === r) record = null
            // The mic closed by itself, not by Stop: end listening, and say so on the band.
            if (running && session == gen) {
                main.post { if (running && session == gen) fail("The mic stopped. Double tap to try again") }
            }
        }
    }

    /** Hands each slice to the speech AI. Runs apart from the mic so a slow reader can never stall the mic. */
    private fun feedLoop(gen: Int) {
        while (running && session == gen) {
            val chunk = chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val out = feed ?: continue
            try {
                out.write(chunk)
            } catch (e: IOException) {
                if (running && feed === out) Probe.log("speech feed closed: ${e.message}")
                if (feed === out) feed = null
            }
        }
    }

    private fun startSpeech() {
        if (!running) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            Probe.log("speech: this phone has no built-in offline speech AI")
            fail("This phone has no built-in speech AI")
            return
        }
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            Probe.log("speech: pipe failed: ${e.message}")
            fail("The speech AI would not start")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            if (segmented) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        val sr = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            Probe.log("speech: could not create: ${e.javaClass.simpleName}: ${e.message}")
            pipe.forEach { runCatching { it.close() } }
            fail("The speech AI would not start")
            return
        }
        if (!supportChecked) {
            supportChecked = true
            checkSupport(intent)
        }
        sr.setRecognitionListener(heard)
        sr.startListening(intent)
        recognizer = sr
        pipeIn = pipe[0] // kept open until stop: the speech AI may read it only once it is connected
        feed = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        speech = "speech AI: starting"
        Probe.log("speech: started (try ${totalRestarts + 1}, segmented=$segmented)")
        publish()
    }

    /** Asks, without downloading anything, which languages the built-in speech AI has on the phone. */
    private fun checkSupport(intent: Intent) {
        val checker = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            Probe.log("speech: support check could not start: ${e.message}")
            return
        }
        val plain = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        }
        checker.checkRecognitionSupport(plain, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                Probe.log(
                    "speech: on the phone=${support.installedOnDeviceLanguages} downloading=${support.pendingOnDeviceLanguages} " +
                        "could download=${support.supportedOnDeviceLanguages.size} languages",
                )
                checker.destroy()
            }

            override fun onError(error: Int) {
                Probe.log("speech: support check error ${errorName(error)}")
                checker.destroy()
            }
        })
    }

    private fun stopSpeech() {
        main.removeCallbacks(restart)
        recognizer?.let { sr ->
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
        recognizer = null
        feed?.let { runCatching { it.close() } }
        feed = null
        pipeIn?.let { runCatching { it.close() } }
        pipeIn = null
    }

    private val restart = Runnable { if (running) startSpeech() }

    // Start left on with nobody talking: after this long with no words the mic closes by itself.
    private val quiet = Runnable {
        if (running) {
            Probe.log("no words for ${QUIET_MS / 60_000} minutes, so the mic goes off")
            fail("No words for 2 minutes. Double tap to go on")
        }
    }

    /** Words came in: the quiet countdown starts over. */
    private fun heardWords() {
        main.removeCallbacks(quiet)
        main.postDelayed(quiet, QUIET_MS)
    }

    private fun restartSpeechSoon() {
        if (!running) return
        stopSpeech()
        if (restarts >= MAX_RESTARTS) {
            Probe.log("speech: gave up after $restarts tries, last error: $lastError")
            fail("Speech stopped. Double tap to try again")
            return
        }
        restarts++
        totalRestarts++
        main.postDelayed(restart, 400)
    }

    private val heard = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            speech = "speech AI: listening"
            Probe.log("speech: ready")
            publish()
        }

        override fun onBeginningOfSpeech() = Probe.log("speech: speech began")

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Probe.log("speech: end of speech")

        override fun onError(error: Int) {
            if (!running) return
            lastError = errorName(error)
            Probe.log("speech: error ${errorName(error)}")
            if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            ) {
                fail("English speech is not on this phone") // trying again would not change that
                return
            }
            speech = "speech AI: ${errorName(error)}"
            if (!heardAny && segmented) {
                segmented = false
                Probe.log("speech: next try without segments")
            }
            savePlace()
            publish()
            restartSpeechSoon()
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            take(results, "final")
            restartSpeechSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            val text = best(partialResults)
            if (text.isNotEmpty() && text != lastPartial) {
                lastPartial = text
                heardAny = true
                restarts = 0
                heardWords()
                onHeard(text, false)
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (running) take(segmentResults, "segment")
        }

        override fun onEndOfSegmentedSession() {
            if (!running) return
            Probe.log("speech: segmented session ended")
            savePlace()
            restartSpeechSoon()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** The end of one stretch of speech. An empty final keeps what the last partial said, so nothing is lost. */
    private fun take(bundle: Bundle?, kind: String) {
        val text = best(bundle).ifEmpty { lastPartial }
        Probe.log("speech: $kind (${if (text.isEmpty()) 0 else text.split(' ').size} words)")
        if (text.isNotEmpty()) {
            heardAny = true
            restarts = 0
            heardWords()
        }
        lastPartial = ""
        onHeard(text, true)
        publish()
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    /** Every other app recording right now, by mic mode, and whether Android is giving it sound. */
    private fun otherRecorders(r: AudioRecord): List<AudioRecordingConfiguration> =
        runCatching { audio.activeRecordingConfigurations }.getOrDefault(emptyList())
            .filter { it.clientAudioSessionId != r.audioSessionId }

    private fun others(r: AudioRecord): String {
        val list = otherRecorders(r)
        if (list.isEmpty()) return "none"
        return list.joinToString(",") { sourceName(it.clientAudioSource) + if (it.isClientSilenced) ":silenced" else ":live" }
    }

    /** The band is drawn from the values as they are when it draws, so an old picture never lands late. */
    private fun publish() {
        if (Looper.myLooper() == Looper.getMainLooper()) onUpdate(snapshot()) else main.post { onUpdate(snapshot()) }
    }

    private fun snapshot(): Snapshot {
        val problem = when {
            speech.first().isUpperCase() -> speech // written for the band: why it did not start, or why it stopped
            !running -> null
            hearing == false -> "Getting no sound from the mic"
            else -> null
        }
        return Snapshot(running, problem)
    }

    private companion object {
        const val RATE = 16_000
        const val SLICE = 1_600 // 0.1 s at 16,000 samples a second
        const val MAX_RESTARTS = 40
        const val QUIET_MS = 120_000L // 2 minutes

        fun dbfs(rms: Double): Double = if (rms <= 0.0) -120.0 else 20.0 * log10(rms / 32768.0)

        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            1999 -> "HOTWORD"
            else -> "SOURCE_$source"
        }

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "speech service disconnected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English not on the phone"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check support"
            else -> "error $error"
        }
    }
}
