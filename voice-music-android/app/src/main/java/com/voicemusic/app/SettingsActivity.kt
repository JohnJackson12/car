package com.voicemusic.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class SettingsActivity : Activity() {
    private lateinit var config: Config
    private lateinit var library: Library
    private val FOLDER_REQ = 1001

    private lateinit var foldersBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        config = app.config; library = app.library
        val root = Ui.col(this).apply { setBackgroundColor(Color.rgb(18, 18, 24)) }
        val topBar = Ui.row(this, Ui.iconButton(this, "\u2190") { finish() }, Ui.textView(this, "Settings", 19f, true))
        root.addView(topBar)
        val body = Ui.col(this)

        // -- Voice ------------------------------------------------------------------------------
        body.addView(Ui.sectionLabel(this, "Voice control"))
        body.addView(switchRow("Voice commands enabled", config.voiceEnabled) { v -> config.voiceEnabled = v; svc()?.setVoiceEnabled(v) })
        body.addView(textFieldRow("Wake word", config.wakeWord) { config.wakeWord = it; svc()?.voice?.onWakeWordChanged() })
        body.addView(textFieldRow("Extra wake words (comma-separated)", config.wakeWordAliases.joinToString(", ")) {
            config.wakeWordAliases = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }; svc()?.voice?.onWakeWordChanged()
        })
        body.addView(textFieldRow("Custom \"approve\" word (blank = keep/yes/good)", config.approveWord) { config.approveWord = it; svc()?.voice?.onCommandWordsChanged() })
        body.addView(textFieldRow("Custom \"delete\" word (blank = delete/remove/trash)", config.deleteWord) { config.deleteWord = it; svc()?.voice?.onCommandWordsChanged() })
        body.addView(spinnerRow("Microphone source", listOf("auto", "voice_recognition", "mic", "voice_communication", "camcorder", "unprocessed", "default"), config.micSource) {
            config.micSource = it; restartVoiceHint()
        })
        body.addView(micDevicePicker())
        body.addView(switchRow("Spoken confirmations (rating, delete, undo, status\u2026)", config.spokenFeedback) { config.spokenFeedback = it })
        body.addView(sliderRow("Mic sensitivity (gain)", config.micGain, 0.5, 12.0) { config.micGain = it })
        body.addView(sliderRow("Command cooldown (sec)", config.commandCooldownSec, 0.3, 5.0) { config.commandCooldownSec = it })
        body.addView(sliderRow("Duck volume while listening", config.duckVolume, 0.0, 1.0) { config.duckVolume = it })
        body.addView(sliderRow("Listening window after wake word (sec)", config.duckListenWindowSec, 1.0, 10.0) { config.duckListenWindowSec = it })
        body.addView(switchRow("Two-stage listening (wake word alone opens a window)", config.twoStageListening) { config.twoStageListening = it })
        body.addView(switchRow("\"Play <title>\" by voice", config.voicePlayByTitle) { config.voicePlayByTitle = it; pushTitles() })
        body.addView(sliderRowInt("Max star rating by voice", config.maxRating, 3, 10) { config.maxRating = it; svc()?.voice?.onCommandWordsChanged() })
        body.addView(spinnerRow("Feedback sound", FeedbackSounds.NAMES, config.feedbackSound) { config.feedbackSound = it })
        body.addView(sliderRow("Feedback volume", config.feedbackVolume, 0.0, 1.0) { config.feedbackVolume = it })
        levelMeter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        body.addView(Ui.col(this, Ui.textView(this, "Live mic level", 13f, false, Color.GRAY), levelMeter))

        // -- Library folders ---------------------------------------------------------------------
        body.addView(Ui.sectionLabel(this, "Library folders"))
        foldersBox = Ui.col(this)
        body.addView(foldersBox)
        body.addView(Ui.button(this, "+ Add folder") {
            startActivityForResult(Intent(this, FolderBrowserActivity::class.java), FOLDER_REQ)
        })
        renderFolders()

        // -- Playback ------------------------------------------------------------------------------
        body.addView(Ui.sectionLabel(this, "Playback"))
        body.addView(switchRow("ReplayGain volume leveling", config.enableReplayGain) { config.enableReplayGain = it })
        body.addView(switchRow("Confirm before deleting a song", config.confirmListDelete) { config.confirmListDelete = it })
        body.addView(textFieldRow("Extra file extensions to scan (e.g. .m4a, .aac)", config.supportedExtensions.joinToString(", ")) {
            val list = it.split(",").map { s -> s.trim().lowercase() }.filter { s -> s.isNotEmpty() }.map { s -> if (s.startsWith(".")) s else ".$s" }
            config.supportedExtensions = list.ifEmpty { listOf(".mp3", ".ogg", ".wav", ".flac") }
        })
        body.addView(Ui.button(this, "Sound effects (equalizer, bass, speed)") { startActivity(Intent(this, EffectsActivity::class.java)) })

        // -- Appearance / device -------------------------------------------------------------------
        body.addView(Ui.sectionLabel(this, "Appearance & device"))
        body.addView(spinnerRow("Theme", ThemeCatalog.NAMES, config.themeName) { config.themeName = it })
        body.addView(sliderRow("UI scale", config.uiScale, 0.8, 1.6) { config.uiScale = it; Ui.toast(this, "Takes effect next launch") })
        body.addView(switchRow("Keep screen on while app is open", config.keepScreenOn) { config.keepScreenOn = it })
        body.addView(switchRow("Start automatically when the car/phone boots", config.startOnBoot) { config.startOnBoot = it })
        body.addView(switchRow("Auto-resume playback on boot start", config.autoPlayOnBoot) { config.autoPlayOnBoot = it })

        // -- Help -----------------------------------------------------------------------------------
        body.addView(Ui.sectionLabel(this, "Voice commands reference"))
        body.addView(commandReferenceTable())
        body.addView(Ui.sectionLabel(this, "Library list tips"))
        body.addView(libraryTipsTable())

        // -- Diagnostics -----------------------------------------------------------------------------
        body.addView(Ui.sectionLabel(this, "Diagnostics"))
        body.addView(Ui.button(this, "View crash / error log") {
            android.app.AlertDialog.Builder(this).setTitle("Log").setMessage(CrashLog.read()).setPositiveButton("Close", null)
                .setNeutralButton("Clear") { _, _ -> CrashLog.clear() }.show()
        })
        body.addView(Ui.textView(this, "Log file: ${CrashLog.path()}", 11f, false, Color.GRAY).apply { setPadding(Ui.dp(this@SettingsActivity,16),0,Ui.dp(this@SettingsActivity,16),Ui.dp(this@SettingsActivity,20)) })

        root.addView(Ui.scroll(this, body), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private lateinit var levelMeter: ProgressBar
    private val meterHandler = android.os.Handler(mainLooper)
    private val meterTick = object : Runnable {
        override fun run() { levelMeter.progress = ((svc()?.voice?.currentLevel() ?: 0.0) * 100).toInt(); meterHandler.postDelayed(this, 200) }
    }
    override fun onResume() { super.onResume(); meterHandler.post(meterTick) }
    override fun onPause() { super.onPause(); meterHandler.removeCallbacks(meterTick) }

    private fun svc(): PlaybackService? = (application as? App)?.let { MainActivity.boundService }
    private fun restartVoiceHint() { Ui.toast(this, "Mic source change applies next time voice restarts.") ; svc()?.let { it.setVoiceEnabled(false); it.setVoiceEnabled(true) } }
    private fun pushTitles() { svc()?.voice?.updateSongTitles(library.songs.values.map { it.title }) }

    private fun renderFolders() {
        foldersBox.removeAllViews()
        val list = library.listFolders()
        if (list.isEmpty()) foldersBox.addView(Ui.textView(this, "No folders added yet.", 14f, false, Color.GRAY).apply { setPadding(Ui.dp(this@SettingsActivity,16),Ui.dp(this@SettingsActivity,4),Ui.dp(this@SettingsActivity,16),Ui.dp(this@SettingsActivity,4)) })
        for (f in list) {
            val label = Ui.textView(this, f.path + if (!f.exists) "  (offline)" else "", 13.5f).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val remove = Ui.iconButton(this, "\u2716", 16f) {
                Ui.confirm(this, "Remove folder?", "Songs found in ${f.path} will be removed from the library. Files on disk aren't touched.") {
                    library.removeFolder(f.path); renderFolders()
                }
            }
            val rowPad = Ui.dp(this, 16)
            foldersBox.addView(Ui.row(this, label, remove).apply { setPadding(rowPad, Ui.dp(this@SettingsActivity,6), rowPad, Ui.dp(this@SettingsActivity,6)) })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FOLDER_REQ && resultCode == Activity.RESULT_OK) {
            val path = data?.getStringExtra(FolderBrowserActivity.EXTRA_PATH) ?: return
            val (ok, msg) = library.addFolder(path)
            if (!ok) Ui.toast(this, msg ?: "Couldn't add that folder.")
            renderFolders()
            svc()?.engine?.let { }
            Bg.io { library.scanAsync({ _, _ -> }, { added, pruned, err -> Bg.post { Ui.toast(this, if (err != null) "Scan error: $err" else "Added $added song(s).") } }) }
        }
    }

    // -- small control-row builders --------------------------------------------------------------------
    private fun labeled(label: String, control: android.view.View): LinearLayout {
        val pad = Ui.dp(this, 16)
        return Ui.col(this, Ui.textView(this, label, 14f, false, Color.argb(255, 210, 213, 220)), control).apply { setPadding(pad, Ui.dp(this@SettingsActivity,10), pad, Ui.dp(this@SettingsActivity,10)) }
    }

    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(this).apply { isChecked = initial; setOnCheckedChangeListener { _, v -> onChange(v) } }
        val pad = Ui.dp(this, 16)
        return Ui.row(this, Ui.textView(this, label, 14.5f).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, sw)
            .apply { setPadding(pad, Ui.dp(this@SettingsActivity,10), pad, Ui.dp(this@SettingsActivity,10)) }
    }

    private fun textFieldRow(label: String, initial: String, onChange: (String) -> Unit): LinearLayout {
        val et = EditText(this).apply { setText(initial); setSingleLine(true)
            setOnFocusChangeListener { _, has -> if (!has) onChange(text.toString().trim()) }
        }
        return labeled(label, et)
    }

    private fun spinnerRow(label: String, options: List<String>, initial: String, onChange: (String) -> Unit): LinearLayout {
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
            val idx = options.indexOf(initial); setSelection(if (idx >= 0) idx else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { onChange(options[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        return labeled(label, sp)
    }

    private fun sliderRow(label: String, initial: Double, min: Double, max: Double, onChange: (Double) -> Unit): LinearLayout {
        val valueLabel = Ui.textView(this, fmt2(initial), 13f, false, Color.GRAY)
        val sb = SeekBar(this).apply {
            this.max = 1000
            progress = (((initial - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 1000.0; valueLabel.text = fmt2(v); if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val pad = Ui.dp(this, 16)
        return Ui.col(this, Ui.row(this, Ui.textView(this, label, 14f).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, valueLabel), sb)
            .apply { setPadding(pad, Ui.dp(this@SettingsActivity,10), pad, Ui.dp(this@SettingsActivity,10)) }
    }

    private fun sliderRowInt(label: String, initial: Int, min: Int, max: Int, onChange: (Int) -> Unit): LinearLayout =
        sliderRow(label, initial.toDouble(), min.toDouble(), max.toDouble()) { onChange(Math.round(it).toInt()) }

    private fun refRow(left: String, right: String, mono: Boolean = true): LinearLayout {
        val pad = Ui.dp(this, 16)
        val l = Ui.textView(this, left, 12.5f, true, Color.rgb(224, 169, 75)).apply { layoutParams = Ui.lp(Ui.dp(this@SettingsActivity, 150), ViewGroup.LayoutParams.WRAP_CONTENT) }
        val r = Ui.textView(this, right, 12.5f, false, Color.argb(255, 190, 195, 205)).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        return Ui.row(this, l, r).apply { setPadding(pad, Ui.dp(this@SettingsActivity, 4), pad, Ui.dp(this@SettingsActivity, 4)) }
    }

    /** Generated the same way the PC app generates it: straight from the current wake word/rating, so it can't drift out of date. */
    private fun commandReferenceTable(): LinearLayout {
        val wake = config.wakeWord
        val max = config.maxRating
        val rows = listOf(
            "\"$wake 1\" ... \"$wake $max\"" to "Rate the current song (also written into the file's own tags)",
            "\"$wake skip 10\"" to "Jump forward 10s in the CURRENT song only, one time (5-60s, steps of 5)",
            "\"$wake skip all songs 10\"" to "Set the GLOBAL skip - every song starts 10s in, until changed again",
            "\"$wake trim ten twenty\"" to "Cut 10s off the start and 20s off the end of the current song",
            "\"$wake delete\"" to "Move the current song to trash and move on",
            "\"$wake keep\"" to "Mark the current song approved and move on",
            "\"$wake undo\"" to "Restore the most recently deleted song",
            "\"$wake next\" / \"previous\"" to "Change track",
            "\"$wake pause\" / \"play\"" to "Pause / resume",
            "\"$wake status\"" to "Speak the current song's name (if spoken confirmations are on)",
            "\"$wake play <title>\"" to "Play a song by its title, if voice play-by-title is on"
        )
        val col = Ui.col(this)
        for ((l, r) in rows) col.addView(refRow(l, r))
        col.addView(Ui.textView(this, "Every command starts with the wake word - say \"$wake\" then the command.", 11.5f, false, Color.GRAY)
            .apply { val p = Ui.dp(this@SettingsActivity, 16); setPadding(p, Ui.dp(this@SettingsActivity, 6), p, Ui.dp(this@SettingsActivity, 4)) })
        return col
    }

    private fun libraryTipsTable(): LinearLayout {
        val rows = listOf(
            "Tap a row" to "Play that song",
            "Long-press a row" to "More actions - rate, favorite, edit tags, trim, playlists, move/copy, delete",
            "\u2699 top-right" to "Settings (this screen)",
            "\u21BB top-right" to "Rescan your library folders now",
            "\uD83C\uDF99 top-right" to "Turn voice commands on/off"
        )
        val col = Ui.col(this)
        for ((l, r) in rows) col.addView(refRow(l, r))
        return col
    }

    private fun micDevicePicker(): LinearLayout {
        val label = "Preferred input device"
        if (Build.VERSION.SDK_INT < 23) return labeled(label, Ui.textView(this, "System default (device picking needs Android 6+)", 13f, false, Color.GRAY))
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val devices = try { am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).toList() } catch (e: Exception) { emptyList() }
        val names = mutableListOf("System default (auto)")
        val ids = mutableListOf(-1)
        for (d in devices) { names.add("${d.productName} (${deviceTypeLabel(d.type)})"); ids.add(d.id) }
        val current = ids.indexOf(config.micDeviceId).let { if (it < 0) 0 else it }
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(current)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (ids[pos] != config.micDeviceId) { config.micDeviceId = ids[pos]; restartVoiceHint() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        return labeled(label, sp)
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        else -> "input"
    }

    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
