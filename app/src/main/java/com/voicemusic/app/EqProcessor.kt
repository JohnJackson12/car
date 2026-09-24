package com.voicemusic.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** ExoPlayer audio-pipeline hook that runs [EqDsp] on 16-bit PCM. Other encodings pass through untouched. */
class EqProcessor : BaseAudioProcessor() {
    val dsp = EqDsp()
    private var shorts = ShortArray(0)
    private var shortsOut = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        dsp.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = remaining / frameBytes
        val usable = frames * frameBytes
        val out = replaceOutputBuffer(remaining)
        if (frames > 0) {
            val n = usable / 2
            if (shorts.size < n) { shorts = ShortArray(n); shortsOut = ShortArray(n) }
            for (i in 0 until n) shorts[i] = inputBuffer.getShort()
            dsp.process(shorts, 0, shortsOut, 0, frames)
            for (i in 0 until n) out.putShort(shortsOut[i])
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())   // any partial trailing frame
        out.flip()
    }

    override fun onFlush() { dsp.flush() }
    override fun onReset() { dsp.flush() }
}
