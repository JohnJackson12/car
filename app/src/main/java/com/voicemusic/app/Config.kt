package com.voicemusic.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All user settings (port of config.py). Backed by SharedPreferences; every change persists immediately. */
class Config(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("voice_music_config", Context.MODE_PRIVATE)

    private class Str(val sp: SharedPreferences, val key: String, val def: String) : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { sp.edit().putString(key, value).apply() }
    }
    private class Flt(val sp: SharedPreferences, val key: String, val def: Double) : ReadWriteProperty<Any?, Double> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Double =
            try { java.lang.Double.longBitsToDouble(sp.getLong(key, java.lang.Double.doubleToLongBits(def))) } catch (e: Exception) { def }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Double) {
            sp.edit().putLong(key, java.lang.Double.doubleToLongBits(value)).apply()
        }
    }
    private class Int_(val sp: SharedPreferences, val key: String, val def: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { sp.edit().putInt(key, value).apply() }
    }
    private class Bool(val sp: SharedPreferences, val key: String, val def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    }
    private class StrList(val sp: SharedPreferences, val key: String, val def: List<String>) : ReadWriteProperty<Any?, List<String>> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): List<String> {
            val raw = sp.getString(key, null) ?: return def
            return try {
                val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
            } catch (e: Exception) { def }
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<String>) {
            sp.edit().putString(key, JSONArray(value).toString()).apply()
        }
    }

    // -- voice -----------------------------------------------------------------------------
    var wakeWord by Str(sp, "wake_word", "john")
    var approveWord by Str(sp, "approve_word", "")
    var deleteWord by Str(sp, "delete_word", "")
    var wakeWordAliases by StrList(sp, "wake_word_aliases", listOf("sam"))
    var maxRating by Int_(sp, "max_rating", 5)
    var voiceEnabled by Bool(sp, "voice_enabled", true)
    var voiceModelPath by Str(sp, "vosk_model_path", "")        // empty = the bundled model
    var micSource by Str(sp, "mic_source", "auto")               // auto|voice_recognition|mic|voice_communication|camcorder|unprocessed|default
    var micDeviceId by Int_(sp, "mic_device_id", -1)             // -1 = system default
    var commandCooldownSec by Flt(sp, "command_cooldown_sec", 1.5)
    var micGain by Flt(sp, "mic_gain", 2.4)
    var duckVolume by Flt(sp, "duck_volume", 0.25)
    var duckListenWindowSec by Flt(sp, "duck_listen_window_sec", 4.0)
    var twoStageListening by Bool(sp, "two_stage_listening", true)
    var voicePlayByTitle by Bool(sp, "voice_play_by_title", true)
    var voiceMaxTitles by Int_(sp, "voice_max_titles", 1500)
    var spokenFeedback by Bool(sp, "spoken_feedback", true)

    // -- library / playback ----------------------------------------------------------------
    var libraryFolders by StrList(sp, "library_folders", emptyList())
    var skipSeconds by Flt(sp, "skip_seconds", 0.0)
    var masterVolume by Flt(sp, "master_volume", 1.0)
    // Matches the Windows app's default list exactly. m4a/aac/opus/oga decode fine on Android too
    // (Library.collectAudioFiles honors whatever is in this list) - add them from Settings if wanted.
    var supportedExtensions by StrList(sp, "supported_extensions", listOf(".mp3", ".ogg", ".wav", ".flac"))
    var enableReplayGain by Bool(sp, "enable_replaygain", true)
    var resumeSongId by Str(sp, "resume_song_id", "")
    var resumePositionSec by Flt(sp, "resume_position_sec", 0.0)
    var lastViewKind by Str(sp, "last_view_kind", "songs")
    var lastViewArg by Str(sp, "last_view_arg", "")
    var songColumnsVisible by StrList(sp, "song_columns_visible",
        listOf("artist", "album", "genre", "length", "rating", "count", "trim"))
    var confirmListDelete by Bool(sp, "confirm_list_delete", false)

    // -- effects (persisted, unlike the PC app: nobody wants to redo the EQ every time the car starts)
    var eqPreamp by Flt(sp, "eq_preamp", 0.0)
    var eqBands by StrList(sp, "eq_bands", List(10) { "0" })
    var eqPreset by Str(sp, "eq_preset", "Custom")
    var bassBoost by Bool(sp, "bass_boost", false)
    var virtualizer by Bool(sp, "virtualizer", false)
    var playbackSpeed by Flt(sp, "playback_speed", 1.0)

    // -- feedback sounds ------------------------------------------------------------------
    var feedbackSound by Str(sp, "feedback_sound", "bell")
    var feedbackVolume by Flt(sp, "feedback_volume", 0.18)
    var feedbackCustomPath by Str(sp, "feedback_custom_path", "")

    // -- appearance / device -----------------------------------------------------------------
    var themeName by Str(sp, "theme_name", "Mixtape")
    var accentColor by Str(sp, "accent_color", "")
    var uiScale by Flt(sp, "ui_scale", 1.0)
    var keepScreenOn by Bool(sp, "keep_screen_on", false)
    var startOnBoot by Bool(sp, "start_on_boot", false)
    var autoPlayOnBoot by Bool(sp, "auto_play_on_boot", false)
    var permissionsAsked by Bool(sp, "permissions_asked", false)

    fun eqBandValues(): DoubleArray {
        val l = eqBands
        return DoubleArray(10) { i -> l.getOrNull(i)?.toDoubleOrNull() ?: 0.0 }
    }
    fun setEqBandValues(v: DoubleArray) { eqBands = v.map { it.toString() } }
}
