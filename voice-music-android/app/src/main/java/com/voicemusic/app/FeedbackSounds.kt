package com.voicemusic.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short confirmation sounds played after a voice command (port of feedback.py's synth tones), plus
 * support for a user-picked custom sound file. Renders once and reuses the PCM buffer.
 */
object FeedbackSounds {
    private const val SR = 22050
    private val cache = HashMap<String, ShortArray>()

    private fun tone(freqs: List<Pair<Double, Double>>, totalMs: Int): ShortArray {
        val n = SR * totalMs / 1000
        val out = ShortArray(n)
        var t = 0
        for ((freq, ms) in freqs) {
            val segN = Math.min(n - t, SR * ms.toInt() / 1000)
            for (i in 0 until segN) {
                val time = i.toDouble() / SR
                val env = Math.min(1.0, Math.min(i / 200.0, (segN - i) / 400.0))
                out[t + i] = (Math.sin(2 * PI * freq * time) * 9000 * env).toInt().toShort()
            }
            t += segN
        }
        return out
    }

    private fun render(name: String): ShortArray = cache.getOrPut(name) {
        when (name) {
            "bell" -> tone(listOf(1200.0 to 90.0, 1800.0 to 120.0), 220)
            "chime" -> tone(listOf(880.0 to 80.0, 1320.0 to 80.0, 1760.0 to 140.0), 300)
            "click" -> tone(listOf(2200.0 to 30.0), 40)
            "pop" -> tone(listOf(600.0 to 40.0, 300.0 to 40.0), 90)
            "error" -> tone(listOf(400.0 to 90.0, 300.0 to 140.0), 240)
            "rise" -> tone(listOf(500.0 to 60.0, 700.0 to 60.0, 1000.0 to 100.0), 220)
            "none" -> ShortArray(0)
            else -> tone(listOf(1200.0 to 90.0, 1800.0 to 120.0), 220)
        }
    }

    val NAMES = listOf("bell", "chime", "click", "pop", "error", "rise", "none")

    private var track: AudioTrack? = null
    fun play(ctx: Context, name: String, volume: Double, customPath: String) {
        try {
            val data = if (name == "custom" && customPath.isNotEmpty()) return playFile(ctx, customPath, volume) else render(name)
            if (data.isEmpty()) return
            track?.let { try { it.stop(); it.release() } catch (e: Exception) { } }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            t.write(data, 0, data.size)
            t.setVolume((volume.coerceIn(0.0, 1.0)).toFloat())
            t.play()
            track = t
        } catch (e: Throwable) { CrashLog.note("feedback tone failed", e) }
    }

    private var mp: android.media.MediaPlayer? = null
    private fun playFile(ctx: Context, path: String, volume: Double) {
        try {
            mp?.let { try { it.stop(); it.release() } catch (e: Exception) { } }
            val p = android.media.MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            p.setDataSource(path)
            p.setVolume(volume.toFloat(), volume.toFloat())
            p.setOnCompletionListener { it.release(); if (mp === it) mp = null }
            p.prepare(); p.start()
            mp = p
        } catch (e: Throwable) { CrashLog.note("custom feedback sound failed", e) }
    }
}
