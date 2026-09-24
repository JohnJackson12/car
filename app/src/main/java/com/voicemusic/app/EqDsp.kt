package com.voicemusic.app

/**
 * Software 10-band equalizer + bass boost + "virtualizer" (stereo widening), Android-free so it can be
 * unit-tested on a PC. Runs on 16-bit interleaved PCM. Software EQ was chosen on purpose: the built-in
 * android.media.audiofx effects are missing or broken on many cheap head units.
 */
class EqDsp {
    companion object {
        val FREQS = doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
        const val BASS_BOOST_DB = 8.0
        const val BASS_BOOST_BANDS = 3
        private const val Q = 1.2
    }

    private val lock = Any()
    @Volatile private var version = 0
    private var preampDb = 0.0
    private var bandDb = DoubleArray(10)
    private var virt = false

    // audio-thread state
    private var appliedVersion = -1
    private var sampleRate = 44100
    private var channels = 2
    private var coef = Array(10) { DoubleArray(5) }   // b0,b1,b2,a1,a2 (normalised)
    private var activeBands = IntArray(0)
    private var z1 = Array(0) { DoubleArray(10) }
    private var z2 = Array(0) { DoubleArray(10) }
    private var preGain = 1.0
    private var widen = false
    private var bypass = true

    fun setParams(preamp: Double, bands: DoubleArray, virtualizer: Boolean) {
        synchronized(lock) {
            preampDb = preamp; bandDb = bands.copyOf(10); virt = virtualizer
            version++
        }
    }

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate; this.channels = Math.max(1, channels)
        z1 = Array(this.channels) { DoubleArray(10) }; z2 = Array(this.channels) { DoubleArray(10) }
        appliedVersion = -1
    }

    fun flush() { for (c in z1.indices) { java.util.Arrays.fill(z1[c], 0.0); java.util.Arrays.fill(z2[c], 0.0) } }

    private fun refresh() {
        val p: Double; val b: DoubleArray; val v: Boolean; val ver: Int
        synchronized(lock) { p = preampDb; b = bandDb.copyOf(); v = virt; ver = version }
        val act = ArrayList<Int>()
        var maxBoost = 0.0
        for (i in 0 until 10) {
            if (FREQS[i] >= sampleRate * 0.45) continue        // above what this sample rate can carry
            if (Math.abs(b[i]) < 0.05) continue
            val a = Math.pow(10.0, b[i] / 40.0)
            val w0 = 2.0 * Math.PI * FREQS[i] / sampleRate
            val alpha = Math.sin(w0) / (2.0 * Q)
            val cs = Math.cos(w0)
            val a0 = 1.0 + alpha / a
            coef[i][0] = (1.0 + alpha * a) / a0
            coef[i][1] = (-2.0 * cs) / a0
            coef[i][2] = (1.0 - alpha * a) / a0
            coef[i][3] = (-2.0 * cs) / a0
            coef[i][4] = (1.0 - alpha / a) / a0
            act.add(i)
            if (b[i] > maxBoost) maxBoost = b[i]
        }
        activeBands = act.toIntArray()
        val headroom = maxBoost * 0.5                              // avoid clipping when boosting
        preGain = Math.pow(10.0, (p - headroom) / 20.0)
        widen = v && channels == 2
        bypass = activeBands.isEmpty() && Math.abs(p) < 0.05 && !widen
        appliedVersion = ver
    }

    /** Processes [frames] interleaved frames in place-style: reads from [src] (offset [so]) into [dst] (offset [dof]). */
    fun process(src: ShortArray, so: Int, dst: ShortArray, dof: Int, frames: Int) {
        if (appliedVersion != version) refresh()
        val n = frames * channels
        if (bypass) { System.arraycopy(src, so, dst, dof, n); return }
        val act = activeBands
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                var x = src[so + f * channels + c].toDouble() * preGain
                val s1 = z1[c]; val s2 = z2[c]
                for (k in act) {
                    val cf = coef[k]
                    val y = cf[0] * x + s1[k]
                    s1[k] = cf[1] * x - cf[3] * y + s2[k]
                    s2[k] = cf[2] * x - cf[4] * y
                    x = y
                }
                dst[dof + f * channels + c] = clip(x)
            }
            if (widen) {
                val i = dof + f * 2
                val l = dst[i].toDouble(); val r = dst[i + 1].toDouble()
                val mid = (l + r) * 0.5; val side = (l - r) * 0.5 * 1.7
                dst[i] = clip((mid + side) * 0.9); dst[i + 1] = clip((mid - side) * 0.9)
            }
        }
    }

    private fun clip(x: Double): Short = if (x > 32767.0) 32767 else if (x < -32768.0) -32768 else x.toInt().toShort()
}

object EqPresets {
    // The 10-band preset curves libVLC ships (Flat first). Values in dB per band.
    val TABLE: LinkedHashMap<String, DoubleArray> = linkedMapOf(
        "Flat" to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "Classical" to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -7.2, -7.2, -7.2, -9.6),
        "Club" to doubleArrayOf(0.0, 0.0, 8.0, 5.6, 5.6, 5.6, 3.2, 0.0, 0.0, 0.0),
        "Dance" to doubleArrayOf(9.6, 7.2, 2.4, 0.0, 0.0, -5.6, -7.2, -7.2, 0.0, 0.0),
        "Full bass" to doubleArrayOf(-8.0, 9.6, 9.6, 5.6, 1.6, -4.0, -8.0, -10.4, -11.2, -11.2),
        "Full bass and treble" to doubleArrayOf(7.2, 5.6, 0.0, -7.2, -4.8, 1.6, 8.0, 11.2, 12.0, 12.0),
        "Full treble" to doubleArrayOf(-9.6, -9.6, -9.6, -4.0, 2.4, 11.2, 16.0, 16.0, 16.0, 16.8),
        "Headphones" to doubleArrayOf(4.8, 11.2, 5.6, -3.2, -2.4, 1.6, 4.8, 9.6, 12.8, 14.4),
        "Large Hall" to doubleArrayOf(10.4, 10.4, 5.6, 5.6, 0.0, -4.8, -4.8, -4.8, 0.0, 0.0),
        "Live" to doubleArrayOf(-4.8, 0.0, 4.0, 5.6, 5.6, 5.6, 4.0, 2.4, 2.4, 2.4),
        "Party" to doubleArrayOf(7.2, 7.2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 7.2, 7.2),
        "Pop" to doubleArrayOf(-1.6, 4.8, 7.2, 8.0, 5.6, 0.0, -2.4, -2.4, -1.6, -1.6),
        "Reggae" to doubleArrayOf(0.0, 0.0, 0.0, -5.6, 0.0, 6.4, 6.4, 0.0, 0.0, 0.0),
        "Rock" to doubleArrayOf(8.0, 4.8, -5.6, -8.0, -3.2, 4.0, 8.8, 11.2, 11.2, 11.2),
        "Ska" to doubleArrayOf(-2.4, -4.8, -4.0, 0.0, 4.0, 5.6, 8.8, 9.6, 11.2, 9.6),
        "Soft" to doubleArrayOf(4.8, 1.6, 0.0, -2.4, 0.0, 4.0, 8.0, 9.6, 11.2, 12.0),
        "Soft rock" to doubleArrayOf(4.0, 4.0, 2.4, 0.0, -4.0, -5.6, -3.2, 0.0, 2.4, 8.8),
        "Techno" to doubleArrayOf(8.0, 5.6, 0.0, -5.6, -4.8, 0.0, 8.0, 9.6, 9.6, 8.0)
    )
    val NAMES: List<String> get() = TABLE.keys.toList()
}
