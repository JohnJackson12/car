package com.voicemusic.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Short spoken confirmations for voice commands where you can't glance at the screen (rating a
 * song while driving, etc). The Windows app had an unused module for this (speech.py, never
 * actually called from anywhere) - this wires the same idea up for real using Android's built-in
 * offline TTS, since it's genuinely useful in a car. Every call is safe even if no TTS engine is
 * installed on the device: it just silently does nothing, same as the PC module without pyttsx3.
 */
object SpokenFeedback {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }
        } catch (e: Throwable) { CrashLog.note("TTS init failed", e) }
    }

    fun speak(text: String) {
        if (text.isEmpty()) return
        val engine = tts ?: return
        if (!ready) return
        try { engine.language = Locale.getDefault(); engine.speak(text, TextToSpeech.QUEUE_ADD, null, null) }
        catch (e: Throwable) { /* a TTS hiccup should never take the app down */ }
    }

    fun shutdown() { try { tts?.stop(); tts?.shutdown() } catch (e: Throwable) { }; tts = null; ready = false }
}
