package com.voicemusic.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Faithful port of feedback.py's synthesized confirmation tones. Three styles (bell/chime/beep,
 * matching the PC app's names exactly) shape the GENERIC "command recognized" tone; rating, delete,
 * undo and error always play their own distinct, fixed tone shapes regardless of style, so you can
 * tell them apart by ear without looking at the screen - same reasoning as the original.
 */
object FeedbackSounds {
    private const val SR = 44100
    val NAMES = listOf("bell", "chime", "beep", "custom")

    private fun envelopeLinear(n: Int, fadeIn: Int): FloatArray {
        val fade = fadeIn.coerceIn(1, n / 2)
        val env = FloatArray(n) { 1f }
        for (i in 0 until fade) { env[i] = i.toFloat() / fade; env[n - 1 - i] = i.toFloat() / fade }
        return env
    }

    private fun tone(freq: Double, duration: Double, base: Double, volume: Double): FloatArray {
        val n = (SR * duration).toInt()
        val env = envelopeLinear(n, n / 4)
        return FloatArray(n) { i -> (sin(freq * 2 * PI * i / SR) * env[i] * base * volume).toFloat() }
    }

    private fun bell(freq: Double, duration: Double, base: Double, volume: Double): FloatArray {
        val n = (SR * duration).toInt()
        val out = FloatArray(n)
        val attack = (SR * 0.004).toInt().coerceIn(1, n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var w = sin(freq * 2 * PI * t) + 0.45 * sin(2 * freq * 2 * PI * t) + 0.20 * sin(3.01 * freq * 2 * PI * t)
            if (i < attack) w *= i.toDouble() / attack
            val decay = exp(-3.2 * t / duration.coerceAtLeast(0.001))
            out[i] = (w * decay * base * 0.7 * volume).toFloat()
        }
        return out
    }

    private fun chime(freq: Double, duration: Double, base: Double, volume: Double): FloatArray {
        val n = (SR * duration).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            val env = 0.5 - 0.5 * cos(2 * PI * (t / duration).coerceIn(0.0, 1.0))
            (sin(freq * 2 * PI * t) * env * base * 0.8 * volume).toFloat()
        }
    }

    private fun note(style: String, freq: Double, duration: Double, base: Double, volume: Double): FloatArray = when (style) {
        "beep" -> tone(freq, duration, base, volume)
        "chime" -> chime(freq, maxOf(duration, 0.22), base, volume)
        else -> bell(freq, maxOf(duration, 0.3), base, volume)          // "bell" (default) and any unrecognized value
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun play(ctx: Context, wave: FloatArray) {
        if (wave.isEmpty()) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(wave.size * 4)
                .build()
            track.write(wave, 0, wave.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // Released once playback naturally finishes; these clips are all under half a second.
            Bg.postDelayed(((wave.size.toDouble() / SR) * 1000).toLong() + 200) { try { track.release() } catch (e: Exception) { } }
        } catch (e: Throwable) { CrashLog.note("feedback tone failed", e) }
    }

    private var customPlayer: android.media.MediaPlayer? = null
    private fun playCustomFile(ctx: Context, path: String, volume: Double): Boolean {
        if (path.isEmpty()) return false
        return try {
            customPlayer?.let { try { it.stop(); it.release() } catch (e: Exception) { } }
            val p = android.media.MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            p.setDataSource(path)
            p.setVolume(volume.toFloat(), volume.toFloat())
            p.setOnCompletionListener { it.release(); if (customPlayer === it) customPlayer = null }
            p.prepare(); p.start()
            customPlayer = p
            true
        } catch (e: Throwable) { CrashLog.note("custom feedback sound failed", e); false }
    }

    /** Generic "command recognized" tone - the user's custom file if style is "custom" and it loads OK. */
    fun playAck(ctx: Context, style: String, volume: Double, customPath: String) {
        if (style == "custom" && playCustomFile(ctx, customPath, volume)) return
        play(ctx, concat(note(style, 880.0, 0.12, 0.55, volume), note(style, 1320.0, 0.12, 0.55, volume)))
    }

    /** Low tone: command not understood, or the action failed. Always synthesized, regardless of style. */
    fun playError(ctx: Context, style: String, volume: Double) { play(ctx, note(style, 220.0, 0.25, 0.65, volume)) }

    /** One short note per star. Always synthesized, regardless of style. */
    fun playRating(ctx: Context, style: String, stars: Int, volume: Double) {
        val gap = FloatArray((SR * 0.06).toInt())
        val parts = ArrayList<FloatArray>()
        for (i in 0 until stars.coerceIn(1, 10)) { parts.add(note(style, 1046.0, 0.1, 0.5, volume)); parts.add(gap) }
        play(ctx, concat(*parts.toTypedArray()))
    }

    /** Descending two-note tone: song removed. Always synthesized, regardless of style. */
    fun playDelete(ctx: Context, style: String, volume: Double) { play(ctx, concat(note(style, 700.0, 0.12, 0.55, volume), note(style, 400.0, 0.12, 0.55, volume))) }

    /** Rising three-note tone: last deletion reversed. Always synthesized, regardless of style. */
    fun playUndo(ctx: Context, style: String, volume: Double) { play(ctx, concat(note(style, 500.0, 0.12, 0.5, volume), note(style, 700.0, 0.12, 0.5, volume), note(style, 900.0, 0.12, 0.5, volume))) }
}
