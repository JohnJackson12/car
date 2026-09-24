package com.voicemusic.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class EffectsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = MainActivity.boundService
        val root = Ui.col(this).apply { setBackgroundColor(Color.rgb(18, 18, 24)) }
        root.addView(Ui.row(this, Ui.iconButton(this, "\u2190") { finish() }, Ui.textView(this, "Sound", 19f, true)))
        val body = Ui.col(this)

        if (svc == null) {
            body.addView(Ui.textView(this, "Start playback first to adjust sound effects.", 14f, false, Color.GRAY).apply { setPadding(Ui.dp(this@EffectsActivity,16),Ui.dp(this@EffectsActivity,16),Ui.dp(this@EffectsActivity,16),0) })
        } else {
            val engine = svc.engine
            val (preamp, bands, flags) = engine.eqState()
            val (bassBoost, virtualizer) = flags

            body.addView(Ui.sectionLabel(this, "Preset"))
            body.addView(Spinner(this).apply {
                val names = listOf("Custom") + EqPresets.NAMES
                adapter = ArrayAdapter(this@EffectsActivity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(names.indexOf(App.instance.config.eqPreset).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                        if (names[pos] != "Custom") { engine.applyPreset(names[pos]); recreate() }
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }.apply { val pad = Ui.dp(this@EffectsActivity, 16); setPadding(pad, 0, pad, 0) })

            body.addView(Ui.sectionLabel(this, "Preamp"))
            body.addView(dbSlider(preamp) { engine.setPreamp(it) })

            body.addView(Ui.sectionLabel(this, "10-band equalizer (dB)"))
            for (i in 0 until 10) {
                val freq = EqDsp.FREQS[i]
                val label = if (freq >= 1000) "${(freq / 1000).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }} kHz" else "${freq.toInt()} Hz"
                body.addView(Ui.textView(this, label, 12.5f, false, Color.GRAY).apply { setPadding(Ui.dp(this@EffectsActivity,16), Ui.dp(this@EffectsActivity,6), 0, 0) })
                body.addView(dbSlider(bands[i]) { v -> engine.setEqBand(i, v) })
            }

            body.addView(Ui.sectionLabel(this, "Extras"))
            body.addView(switchRow("Bass boost", bassBoost) { engine.setBassBoost(it) })
            body.addView(switchRow("Virtualizer (stereo widening)", virtualizer) { engine.setVirtualizer(it) })
            body.addView(Ui.button(this, "Reset all to flat") { engine.resetEq(); recreate() }.apply { val p = Ui.dp(this@EffectsActivity,16); (layoutParams as? ViewGroup.LayoutParams) })

            body.addView(Ui.sectionLabel(this, "Playback speed"))
            body.addView(speedSlider(engine.getRate()) { engine.setRate(it) })
        }

        root.addView(Ui.scroll(this, body), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(this).apply { isChecked = initial; setOnCheckedChangeListener { _, v -> onChange(v) } }
        val pad = Ui.dp(this, 16)
        return Ui.row(this, Ui.textView(this, label, 14.5f).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, sw)
            .apply { setPadding(pad, Ui.dp(this@EffectsActivity,10), pad, Ui.dp(this@EffectsActivity,10)) }
    }

    private fun dbSlider(initial: Double, onChange: (Double) -> Unit): LinearLayout {
        val valueLabel = Ui.textView(this, fmtDb(initial), 12.5f, false, Color.GRAY)
        val sb = SeekBar(this).apply {
            max = 400   // -20..+20 dB, 0.1 dB steps
            progress = (((initial + 20.0) / 40.0) * 400).toInt().coerceIn(0, 400)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = -20.0 + (p / 400.0) * 40.0; valueLabel.text = fmtDb(v); if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val pad = Ui.dp(this, 16)
        return Ui.col(this, valueLabel, sb).apply { setPadding(pad, Ui.dp(this@EffectsActivity,2), pad, Ui.dp(this@EffectsActivity,4)) }
    }

    private fun speedSlider(initial: Double, onChange: (Double) -> Unit): LinearLayout {
        val valueLabel = Ui.textView(this, "${String.format(java.util.Locale.US, "%.2f", initial)}x", 13f, false, Color.GRAY)
        val sb = SeekBar(this).apply {
            max = 275   // 0.25x..3.00x, step 0.01
            progress = (((initial - 0.25) / 2.75) * 275).toInt().coerceIn(0, 275)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.25 + (p / 275.0) * 2.75; valueLabel.text = "${String.format(java.util.Locale.US, "%.2f", v)}x"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val pad = Ui.dp(this, 16)
        return Ui.col(this, valueLabel, sb).apply { setPadding(pad, Ui.dp(this@EffectsActivity,4), pad, Ui.dp(this@EffectsActivity,16)) }
    }

    private fun fmtDb(v: Double) = (if (v >= 0) "+" else "") + String.format(java.util.Locale.US, "%.1f dB", v)
}
