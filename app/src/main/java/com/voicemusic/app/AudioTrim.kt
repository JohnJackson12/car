package com.voicemusic.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Creates a real, standalone trimmed audio file (port of audio_trim.py). No ffmpeg on Android, so:
 *  WAV  - pure copy of the PCM slice
 *  MP3  - lossless cut on MPEG frame boundaries (same algorithm as the PC app)
 *  M4A/AAC (and OGG/Opus on Android 10+) - lossless re-mux with Android's MediaMuxer
 * Other formats (e.g. FLAC) can't be baked on Android; the in-app trim points still work for playback.
 */
object AudioTrim {
    fun trim(src: File, dest: File, startSeconds: Double, stopSeconds: Double?): Pair<Boolean, String?> {
        if (!src.exists()) return Pair(false, "Source file not found: ${src.path}")
        if (src.canonicalPath == dest.canonicalPath) return Pair(false, "Trimmed copy can't overwrite the original file - pick a different name.")
        val start = Math.max(0.0, startSeconds)
        if (stopSeconds != null && stopSeconds <= start) return Pair(false, "Trim range is empty - the end point is at or before the start point.")
        return when (src.extension.lowercase()) {
            "wav" -> trimWav(src, dest, start, stopSeconds)
            "mp3" -> trimMp3(src, dest, start, stopSeconds)
            "m4a", "aac", "mp4" -> trimMux(src, dest, start, stopSeconds, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            "ogg", "oga", "opus" ->
                if (Build.VERSION.SDK_INT >= 29) trimMux(src, dest, start, stopSeconds, 4 /* MUXER_OUTPUT_OGG */)
                else Pair(false, "Trimming ${src.extension} files needs Android 10 or newer. MP3, WAV and M4A/AAC work on any version.")
            else -> Pair(false, "Baking a trim into .${src.extension} files isn't supported on Android (MP3, WAV, M4A/AAC and OGG/Opus are). The in-app trim points still apply during playback.")
        }
    }

    // -- WAV --------------------------------------------------------------------------------
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun trimWav(src: File, dest: File, start: Double, stop: Double?): Pair<Boolean, String?> {
        try {
            RandomAccessFile(src, "r").use { raf ->
                val head = ByteArray(12)
                raf.readFully(head)
                if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return Pair(false, "Not a valid WAV file.")
                var fmt: ByteArray? = null
                var dataPos = -1L; var dataLen = 0L
                val ch = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(ch)
                    val id = String(ch, 0, 4)
                    val sz = le32(ch, 4).toLong() and 0xFFFFFFFFL
                    if (id == "fmt ") { fmt = ByteArray(sz.toInt()); raf.readFully(fmt) }
                    else if (id == "data") { dataPos = raf.filePointer; dataLen = Math.min(sz, raf.length() - dataPos); break }
                    else raf.seek(raf.filePointer + sz + (sz and 1L))
                    if (id == "fmt " && (sz and 1L) == 1L) raf.seek(raf.filePointer + 1)
                }
                if (fmt == null || dataPos < 0 || fmt.size < 16) return Pair(false, "Couldn't read this WAV file's format.")
                val byteRate = le32(fmt, 8)
                val blockAlign = (fmt[12].toInt() and 0xFF) or ((fmt[13].toInt() and 0xFF) shl 8)
                if (blockAlign <= 0 || byteRate <= 0) return Pair(false, "Unsupported WAV header.")
                val totalFrames = dataLen / blockAlign
                val rate = byteRate.toDouble() / blockAlign
                val startFrame = Math.max(0L, (start * rate).toLong())
                val endFrame = Math.min(totalFrames, if (stop != null) (stop * rate).toLong() else totalFrames)
                if (startFrame >= endFrame) return Pair(false, "Trim range is empty - the end point is at or before the start point.")
                val outLen = (endFrame - startFrame) * blockAlign
                dest.parentFile?.mkdirs()
                RandomAccessFile(dest, "rw").use { out ->
                    out.setLength(0)
                    fun w32(v: Long) { out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
                    out.writeBytes("RIFF"); w32(4 + 8 + fmt.size + (fmt.size and 1) + 8 + outLen)
                    out.writeBytes("WAVE"); out.writeBytes("fmt "); w32(fmt.size.toLong()); out.write(fmt)
                    if (fmt.size and 1 == 1) out.write(0)
                    out.writeBytes("data"); w32(outLen)
                    raf.seek(dataPos + startFrame * blockAlign)
                    val buf = ByteArray(65536)
                    var left = outLen
                    while (left > 0) {
                        val n = raf.read(buf, 0, Math.min(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n); left -= n
                    }
                }
            }
            return Pair(true, null)
        } catch (e: Exception) { return Pair(false, "Couldn't trim WAV file: ${e.message}") }
    }

    // -- MP3 (frame-accurate lossless cut) ------------------------------------------------------
    private val BR_V1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0),   // L1
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0),      // L2
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0))      // L3
    private val BR_V2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0),      // L1
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),           // L2
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0))           // L3
    private val SR = mapOf(3 to intArrayOf(44100, 48000, 32000, 0), 2 to intArrayOf(22050, 24000, 16000, 0), 0 to intArrayOf(11025, 12000, 8000, 0))

    /** Returns intArrayOf(frameLen, samplesPerFrame, sampleRate) or null. */
    internal fun parseMp3Header(d: ByteArray, o: Int, end: Int = d.size): IntArray? {
        if (o + 4 > end) return null
        val b1 = d[o].toInt() and 0xFF; val b2 = d[o + 1].toInt() and 0xFF; val b3 = d[o + 2].toInt() and 0xFF
        if (b1 != 0xFF || (b2 and 0xE0) != 0xE0) return null
        val vbits = (b2 shr 3) and 3          // 0=2.5, 2=2, 3=1, 1=reserved
        val lbits = (b2 shr 1) and 3          // 1=L3, 2=L2, 3=L1, 0=reserved
        if (vbits == 1 || lbits == 0) return null
        val layer = when (lbits) { 1 -> 3; 2 -> 2; else -> 1 }
        val brIdx = (b3 shr 4) and 0xF; val srIdx = (b3 shr 2) and 3; val padding = (b3 shr 1) and 1
        val brk = (if (vbits == 3) BR_V1 else BR_V2)[layer - 1][brIdx]
        val sr = SR[vbits]!![srIdx]
        if (brk == 0 || sr == 0) return null
        val bps = brk * 1000
        val frameLen = if (layer == 1) (12 * bps / sr + padding) * 4 else (if (vbits == 3) 144 else 72) * bps / sr + padding
        if (frameLen <= 4) return null
        val spf = when {
            layer == 1 -> 384
            layer == 2 -> 1152
            vbits == 3 -> 1152
            else -> 576
        }
        return intArrayOf(frameLen, spf, sr)
    }

    private fun id3v2Size(d: ByteArray): Int {
        if (d.size < 10 || d[0] != 'I'.code.toByte() || d[1] != 'D'.code.toByte() || d[2] != '3'.code.toByte()) return 0
        return 10 + (((d[6].toInt() and 0x7F) shl 21) or ((d[7].toInt() and 0x7F) shl 14) or ((d[8].toInt() and 0x7F) shl 7) or (d[9].toInt() and 0x7F))
    }

    private fun containsMarker(d: ByteArray, from: Int, to: Int): Boolean {
        val markers = listOf("Xing", "Info", "VBRI")
        for (m in markers) {
            val mb = m.toByteArray(Charsets.ISO_8859_1)
            var i = from
            while (i + 4 <= to) {
                if (d[i] == mb[0] && d[i + 1] == mb[1] && d[i + 2] == mb[2] && d[i + 3] == mb[3]) return true
                i++
            }
        }
        return false
    }

    /** Pure function: returns the trimmed bytes (ID3v2 tag kept), or an error string. Unit-testable on a PC. */
    internal fun trimMp3Bytes(all: ByteArray, start: Double, stop: Double?): Pair<ByteArray?, String?> {
        if (stop != null && stop <= start) return Pair(null, "Trim range is empty - the end point is at or before the start point.")
        val id3Len = Math.min(id3v2Size(all), all.size)
        val id3 = all.copyOfRange(0, id3Len)
        var aStart = id3Len
        val aEnd = all.size
        val first = parseMp3Header(all, aStart, aEnd)
        if (first != null && containsMarker(all, aStart, Math.min(aStart + first[0], aEnd))) aStart += first[0]   // drop stale Xing/Info/VBRI header
        var offset = aStart
        var cum = 0.0
        var startByte = -1
        var endByte = aEnd
        var frames = 0
        while (offset < aEnd) {
            val p = parseMp3Header(all, offset, aEnd)
            if (p == null) { offset++; continue }
            val dur = p[1].toDouble() / p[2]
            if (startByte < 0 && cum + dur > start) startByte = offset
            if (stop != null && cum >= stop) { endByte = offset; break }
            cum += dur; offset += p[0]; frames++
        }
        if (frames == 0) return Pair(null, "Couldn't find any valid MP3 audio frames in this file - it may be corrupt.")
        if (startByte < 0) startByte = aEnd
        if (startByte >= endByte) return Pair(null, "Trim range is empty - the end point is at or before the start point.")
        endByte = Math.min(endByte, aEnd)
        val out = ByteArray(id3.size + (endByte - startByte))
        System.arraycopy(id3, 0, out, 0, id3.size)
        System.arraycopy(all, startByte, out, id3.size, endByte - startByte)
        return Pair(out, null)
    }

    private fun trimMp3(src: File, dest: File, start: Double, stop: Double?): Pair<Boolean, String?> {
        return try {
            val (bytes, err) = trimMp3Bytes(src.readBytes(), start, stop)
            if (bytes == null) return Pair(false, err)
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            Pair(true, null)
        } catch (e: OutOfMemoryError) { Pair(false, "Not enough memory to trim this MP3.")
        } catch (e: Exception) { Pair(false, "Couldn't trim MP3: ${e.message}") }
    }

    // -- lossless re-mux (AAC/M4A, OGG/Opus) ------------------------------------------------------
    private fun trimMux(src: File, dest: File, start: Double, stop: Double?, fmt: Int): Pair<Boolean, String?> {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src.path)
            var idx = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) { idx = i; break }
            }
            if (idx < 0) return Pair(false, "No audio track found in ${src.name}.")
            ex.selectTrack(idx)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            muxer = MediaMuxer(dest.path, fmt)
            val out = muxer.addTrack(ex.getTrackFormat(idx))
            muxer.start(); started = true
            ex.seekTo((start * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val stopUs = if (stop != null) (stop * 1_000_000).toLong() else Long.MAX_VALUE
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            var written = 0
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                if (t >= stopUs) break
                if (firstUs < 0) firstUs = t
                info.set(0, n, t - firstUs, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(out, buf, info)
                written++
                ex.advance()
            }
            if (written == 0) { return Pair(false, "Trim range is empty - nothing to write.") }
            muxer.stop(); started = false
            muxer.release(); muxer = null
            return Pair(true, null)
        } catch (e: Exception) {
            try { dest.delete() } catch (x: Exception) { }
            return Pair(false, "Couldn't trim ${src.name}: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { if (started) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
        }
    }
}
