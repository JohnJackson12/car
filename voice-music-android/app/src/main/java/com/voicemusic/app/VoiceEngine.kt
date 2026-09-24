package com.voicemusic.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.tanh

/**
 * Continuous offline speech recognition (port of voice_control.py's device-thread loop).
 * Runs entirely on its own thread; talks to the rest of the app only through [Listener] callbacks,
 * which are always delivered on the main thread.
 */
class VoiceEngine(private val ctx: Context, private val config: Config) {

    interface Listener {
        fun onListenStart()                          // wake word heard / duck-worthy partial: duck volume
        fun onListenEnd()                             // command resolved or window timed out: restore volume
        fun onCommand(cmd: VoiceCommand)
        fun onRawText(text: String) {}                // for the on-screen "last heard" debug line
        fun onError(message: String)
        fun onReady() {}
    }
    var listener: Listener? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BLOCK_FRAMES = 4000                 // 250 ms, matches the PC app exactly
        private const val PARTIAL_WAKE_DEBOUNCE = 4
        private val SOURCES_AUTO = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.CAMCORDER)
    }

    private var songTitleMap: Map<String, String> = emptyMap()   // normalized -> original
    @Volatile private var grammarVersion = 0
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var listeningActive = false
    @Volatile private var awaitingUntil = 0L
    @Volatile private var lastTriggerMs = 0L
    @Volatile private var lastLevel = 0.0                        // 0..1 mic level meter, for Settings

    private var model: Model? = null

    fun currentLevel() = lastLevel

    fun updateSongTitles(titles: List<String>) {
        val cap = config.voiceMaxTitles.coerceAtLeast(0)
        val trimmed = if (config.voicePlayByTitle) titles.take(if (cap > 0) cap else titles.size) else emptyList()
        songTitleMap = trimmed.filter { it.isNotBlank() }.associateBy { VoiceParser.normalizeTitle(it) }
        grammarVersion++
    }

    fun onWakeWordChanged() { grammarVersion++ }
    fun onCommandWordsChanged() { grammarVersion++ }

    private fun wakePhrases() = VoiceParser.wakePhrases(config.wakeWord, config.wakeWordAliases)

    // -- model preparation (copy bundled asset model to internal storage once) -------------------
    private fun resolveModelDir(): File? {
        val custom = config.voiceModelPath
        if (custom.isNotEmpty() && File(custom, "conf").isDirectory) return File(custom)
        val dest = File(ctx.filesDir, "vosk-model")
        val marker = File(dest, ".copied_ok")
        if (marker.exists()) return dest
        try {
            copyAssetDir("vosk-model", dest)
            marker.createNewFile()
            return dest
        } catch (e: Throwable) {
            CrashLog.note("copying bundled speech model failed", e)
            return null
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val am = ctx.assets
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyAssetDir("$assetPath/$c", File(dest, c))
    }

    // -- lifecycle ------------------------------------------------------------------------------------
    fun start() {
        if (running.get()) return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Bg.post { listener?.onError("Microphone permission isn't granted, so voice commands are off.") }
            return
        }
        running.set(true)
        thread = Thread({ runLoop() }, "voice-engine").apply { priority = Thread.NORM_PRIORITY + 1; isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.let { try { it.join(1500) } catch (e: InterruptedException) { } }
        thread = null
        try { model?.close() } catch (e: Throwable) { }
        model = null
    }

    // -- gain: same soft (tanh) limiter as the PC app -----------------------------------------------------
    private fun applyGain(samples: ShortArray, n: Int, gain: Double) {
        if (gain == 1.0) return
        for (i in 0 until n) samples[i] = (tanh(samples[i] * gain / 32767.0) * 32767.0).toInt().toShort()
    }

    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val bufSize = Math.max(minBuf, BLOCK_FRAMES * 2 * 4)
        val sourcesToTry = when (config.micSource) {
            "voice_recognition" -> intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            "mic" -> intArrayOf(MediaRecorder.AudioSource.MIC)
            "voice_communication" -> intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            "camcorder" -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER)
            "unprocessed" -> if (Build.VERSION.SDK_INT >= 24) intArrayOf(MediaRecorder.AudioSource.UNPROCESSED) else SOURCES_AUTO
            "default" -> intArrayOf(MediaRecorder.AudioSource.DEFAULT)
            else -> SOURCES_AUTO
        }
        for (src in sourcesToTry) {
            try {
                val rec = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
                if (config.micDeviceId >= 0 && Build.VERSION.SDK_INT >= 23) {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == config.micDeviceId }
                    if (dev != null) rec.setPreferredDevice(dev)
                }
                return rec
            } catch (e: Exception) { CrashLog.note("mic source $src unavailable", e) }
        }
        return null
    }

    // -- confidence / result parsing ----------------------------------------------------------------------
    private fun avgWordConfidence(result: JSONObject): Double {
        val words = result.optJSONArray("result") ?: return 1.0
        if (words.length() == 0) return 1.0
        var sum = 0.0
        for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 1.0)
        return sum / words.length()
    }

    private fun buildRecognizer(wakePhrases: List<String>): Recognizer {
        val phrases = VoiceParser.buildGrammarPhrases(wakePhrases, config.maxRating, songTitleMap.keys, config.approveWord, config.deleteWord)
        val arr = JSONArray()
        for (p in phrases) arr.put(p)
        arr.put("[unk]")
        val rec = Recognizer(model, SAMPLE_RATE.toFloat(), arr.toString())
        rec.setWords(true)
        return rec
    }

    private fun beginListening(wakeAlone: Boolean) {
        var newlyActive = false
        synchronized(this) {
            if (wakeAlone) awaitingUntil = System.currentTimeMillis() + (config.duckListenWindowSec * 1000).toLong()
            if (!listeningActive) { listeningActive = true; newlyActive = true }
        }
        if (newlyActive) Bg.post { listener?.onListenStart() }
    }

    private fun endListeningWindow() {
        var wasActive: Boolean
        synchronized(this) { wasActive = listeningActive; listeningActive = false; awaitingUntil = 0L }
        if (wasActive) Bg.post { listener?.onListenEnd() }
    }

    private fun checkTimeout() {
        val timedOut = synchronized(this) { awaitingUntil != 0L && System.currentTimeMillis() > awaitingUntil }
        if (timedOut) endListeningWindow()
    }

    private fun handleText(text: String, confidence: Double) {
        Bg.post { listener?.onRawText(text) }
        val twoStage = config.twoStageListening
        val wakePhrases = wakePhrases()
        val isAwaiting = synchronized(this) { awaitingUntil != 0L }

        if (twoStage && text in wakePhrases) { beginListening(true); return }

        val parsed: VoiceCommand?
        if (isAwaiting) {
            if (confidence < VoiceParser.MIN_COMMAND_CONFIDENCE) { endListeningWindow(); return }
            parsed = VoiceParser.parseCommand(text, config.maxRating, songTitleMap, config.approveWord, config.deleteWord)
            endListeningWindow()
        } else {
            val matched = VoiceParser.findMatchingWake(text, wakePhrases) ?: run { endListeningWindow(); return }
            val remainder = text.substring(matched.length).trim()
            if (confidence < VoiceParser.MIN_COMMAND_CONFIDENCE) { endListeningWindow(); return }
            parsed = VoiceParser.parseCommand(remainder, config.maxRating, songTitleMap, config.approveWord, config.deleteWord)
            endListeningWindow()
        }
        if (parsed != null) {
            val fire = synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerMs < (config.commandCooldownSec * 1000).toLong()) false
                else { lastTriggerMs = now; true }
            }
            if (fire) Bg.post { listener?.onCommand(parsed) }
        }
    }

    // -- main loop --------------------------------------------------------------------------------------------
    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try { LibVosk.setLogLevel(LogLevel.WARNINGS) } catch (e: Throwable) { }
        val dir = resolveModelDir()
        if (dir == null) { Bg.post { listener?.onError("Couldn't prepare the offline speech model. Voice commands are off.") }; running.set(false); return }
        try { model = Model(dir.absolutePath) } catch (e: Throwable) {
            Bg.post { listener?.onError("Vosk couldn't load the speech model: ${e.message}") }; running.set(false); return
        }
        val record = openRecord()
        if (record == null) {
            Bg.post { listener?.onError("Couldn't open the microphone (no working audio source on this device).") }
            running.set(false); return
        }
        Bg.post { listener?.onReady() }
        var localRecognizer: Recognizer? = null
        var localVersion = -1
        var partialStreak = 0
        val buf = ShortArray(BLOCK_FRAMES)
        try {
            record.startRecording()
            while (running.get()) {
                val gv = grammarVersion
                val wakePhrases = wakePhrases()
                if (localRecognizer == null || gv != localVersion) {
                    try { localRecognizer?.close() } catch (e: Throwable) { }
                    localRecognizer = buildRecognizer(wakePhrases)
                    localVersion = gv
                }
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) { checkTimeout(); continue }
                var peak = 0
                for (i in 0 until n) { val a = Math.abs(buf[i].toInt()); if (a > peak) peak = a }
                lastLevel = (peak / 32767.0).coerceIn(0.0, 1.0)
                applyGain(buf, n, config.micGain)
                val rec = localRecognizer!!
                if (rec.acceptWaveForm(buf, n)) {
                    partialStreak = 0
                    val result = JSONObject(rec.getResult())
                    val text = result.optString("text", "").trim()
                    if (text.isNotEmpty()) handleText(text, avgWordConfidence(result))
                } else if (!listeningActive && wakePhrases.isNotEmpty()) {
                    val partial = JSONObject(rec.getPartialResult()).optString("partial", "").trim()
                    if (wakePhrases.any { partial.startsWith(it) }) {
                        partialStreak++
                        if (partialStreak >= PARTIAL_WAKE_DEBOUNCE) beginListening(false)
                    } else partialStreak = 0
                }
                checkTimeout()
            }
        } catch (e: Throwable) {
            CrashLog.note("voice engine loop failed", e)
            Bg.post { listener?.onError("Voice recognition stopped unexpectedly: ${e.message}") }
        } finally {
            try { record.stop() } catch (e: Throwable) { }
            try { record.release() } catch (e: Throwable) { }
            try { localRecognizer?.close() } catch (e: Throwable) { }
        }
    }
}
