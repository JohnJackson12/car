package com.voicemusic.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyPOPM
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.io.RandomAccessFile
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads/writes ratings and basic tags inside the audio files themselves (port of tag_writer.py),
 * using the same conventions other players use:
 *  MP3/WAV(ID3): POPM frame, email "Windows Media Player 9 Series", WMP byte scale 0/1/64/128/196/255
 *  FLAC/OGG: RATING (0-100) + FMPS_RATING (0.0-1.0)
 */
object TagIO {
    private val WMP_POPM_BYTES = intArrayOf(0, 1, 64, 128, 196, 255)
    private const val POPM_EMAIL = "Windows Media Player 9 Series"

    private var inited = false
    fun init() {
        if (inited) return
        inited = true
        try {
            Logger.getLogger("org.jaudiotagger").level = Level.OFF   // library is very chatty; slows scans
            TagOptionSingleton.getInstance().setId3v1Save(false)
        } catch (e: Throwable) { CrashLog.note("TagIO.init", e) }
    }

    private fun scaleToFive(rating: Int, max: Int): Int =
        if (max == 5) rating else if (max > 0) Math.round(rating.toDouble() / max * 5).toInt() else 0

    private fun nearestStarFromPopm(b: Int): Int {
        if (b <= 0) return 0
        var best = 0; var bestD = Int.MAX_VALUE
        for (s in 0..5) { val d = Math.abs(WMP_POPM_BYTES[s] - b); if (d < bestD) { bestD = d; best = s } }
        return best
    }

    // ---------------------------------------------------------------------------------------
    // Reading (rating + ReplayGain), used during library scans
    // ---------------------------------------------------------------------------------------
    class Extra(val rating: Int?, val replayGainDb: Double?)

    fun readExtra(file: File): Extra {
        val ext = file.extension.lowercase()
        try {
            if (ext == "mp3" || ext == "wav") {
                val r = readId3(file)
                if (r != null) return r
                if (ext == "mp3") return Extra(null, null)
            }
            if (ext == "flac" || ext == "ogg" || ext == "oga") return readVorbisExtra(file)
        } catch (e: Throwable) { /* fall through: no extra info */ }
        return Extra(null, null)
    }

    private fun readVorbisExtra(file: File): Extra {
        init()
        val af = AudioFileIO.read(file)
        val tag = af.tag ?: return Extra(null, null)
        var rating: Int? = null
        try {
            val s = tag.getFirst("RATING")
            if (!s.isNullOrBlank()) rating = Math.round(s.trim().toDouble() / 20.0).toInt().coerceIn(0, 5)
        } catch (e: Exception) { }
        var gain: Double? = null
        for (k in listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN")) {
            try {
                val s = tag.getFirst(k)
                if (!s.isNullOrBlank()) { gain = s.trim().lowercase().replace("db", "").trim().toDouble(); break }
            } catch (e: Exception) { }
        }
        return Extra(rating, gain)
    }

    /** Minimal read-only ID3v2.3/2.4 reader for POPM (rating) and TXXX REPLAYGAIN_* frames. Never writes. */
    private fun readId3(file: File): Extra? {
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(10)
            var start = 0L
            if (file.extension.lowercase() == "wav") return null   // WAV ID3 chunk: rely on defaults
            if (raf.read(hdr) < 10 || hdr[0] != 'I'.code.toByte() || hdr[1] != 'D'.code.toByte() || hdr[2] != '3'.code.toByte()) return null
            val ver = hdr[3].toInt()
            if (ver != 3 && ver != 4) return null
            val flags = hdr[5].toInt()
            val size = ((hdr[6].toInt() and 0x7F) shl 21) or ((hdr[7].toInt() and 0x7F) shl 14) or
                ((hdr[8].toInt() and 0x7F) shl 7) or (hdr[9].toInt() and 0x7F)
            if (size <= 0 || size > 12_000_000) return null
            var data = ByteArray(size)
            raf.seek(start + 10)
            raf.readFully(data)
            if (flags and 0x80 != 0) data = undoUnsync(data)
            var pos = 0
            if (flags and 0x40 != 0 && data.size >= 4) {   // extended header
                val ext = if (ver == 4) syncsafe(data, 0) else be32(data, 0) + 4
                pos = ext.coerceIn(0, data.size)
            }
            var rating: Int? = null
            var gain: Double? = null
            while (pos + 10 <= data.size) {
                if (data[pos].toInt() == 0) break
                val id = String(data, pos, 4, Charsets.ISO_8859_1)
                val fsz = if (ver == 4) syncsafe(data, pos + 4) else be32(data, pos + 4)
                val fflags = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
                val body = pos + 10
                if (fsz < 0 || body + fsz > data.size) break
                val skip = if (ver == 4) (fflags and 0x000C) != 0 else (fflags and 0x00C0) != 0  // compressed/encrypted
                if (!skip && id == "POPM" && rating == null) {
                    var e = body
                    while (e < body + fsz && data[e].toInt() != 0) e++
                    if (e + 1 < body + fsz) rating = nearestStarFromPopm(data[e + 1].toInt() and 0xFF)
                } else if (!skip && id == "TXXX" && gain == null && fsz > 2) {
                    val enc = data[body].toInt()
                    val (desc, valueStart) = readEncStr(data, body + 1, body + fsz, enc)
                    if (desc.lowercase().let { it == "replaygain_track_gain" || it == "replaygain_album_gain" }) {
                        val (v, _) = readEncStr(data, valueStart, body + fsz, enc)
                        gain = v.trim().lowercase().replace("db", "").trim().toDoubleOrNull()
                    }
                }
                pos = body + fsz
            }
            return Extra(rating, gain)
        }
    }

    private fun readEncStr(d: ByteArray, from: Int, end: Int, enc: Int): Pair<String, Int> {
        val wide = enc == 1 || enc == 2
        var i = from
        if (wide) { while (i + 1 < end && !(d[i].toInt() == 0 && d[i + 1].toInt() == 0)) i += 2 }
        else { while (i < end && d[i].toInt() != 0) i++ }
        val cs = when (enc) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val s = try { String(d, from, (Math.min(i, end) - from).coerceAtLeast(0), cs) } catch (e: Exception) { "" }
        return Pair(s, Math.min(i + (if (wide) 2 else 1), end))
    }

    private fun syncsafe(d: ByteArray, o: Int) = ((d[o].toInt() and 0x7F) shl 21) or ((d[o + 1].toInt() and 0x7F) shl 14) or
        ((d[o + 2].toInt() and 0x7F) shl 7) or (d[o + 3].toInt() and 0x7F)
    private fun be32(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
        ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)
    private fun undoUnsync(d: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(d.size)
        var i = 0
        while (i < d.size) {
            out.write(d[i].toInt())
            if (d[i] == 0xFF.toByte() && i + 1 < d.size && d[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------
    /** Returns (ok, message). ok=false means only the in-app rating was saved. Never throws. */
    fun writeRating(path: String, rating: Int, maxRating: Int = 5): Pair<Boolean, String?> {
        init()
        val f = File(path)
        if (!f.exists()) return Pair(false, "File not found: $path")
        if (!f.canWrite()) return Pair(false, "No write access to ${f.name} (grant 'All files access' in Settings, or the file is on read-only storage).")
        val stars = scaleToFive(rating, maxRating).coerceIn(0, 5)
        return try {
            val af: AudioFile = AudioFileIO.read(f)
            when (f.extension.lowercase()) {
                "mp3" -> writePopm(af as MP3File, stars)
                "flac" -> {
                    val tag = af.tagOrCreateAndSetDefault
                    if (tag is FlacTag) { tag.setField("RATING", (stars * 20).toString()); tag.setField("FMPS_RATING", String.format(java.util.Locale.US, "%.2f", stars / 5.0)) }
                    AudioFileIO.write(af)
                }
                "ogg", "oga" -> {
                    val tag = af.tagOrCreateAndSetDefault
                    if (tag is VorbisCommentTag) { tag.setField("RATING", (stars * 20).toString()); tag.setField("FMPS_RATING", String.format(java.util.Locale.US, "%.2f", stars / 5.0)) }
                    AudioFileIO.write(af)
                }
                "m4a", "mp4", "aac" -> {
                    val tag = af.tagOrCreateAndSetDefault
                    tag.setField(FieldKey.RATING, (stars * 20).toString())
                    AudioFileIO.write(af)
                }
                "wav" -> {
                    val tag = af.tagOrCreateAndSetDefault
                    tag.setField(FieldKey.RATING, WMP_POPM_BYTES[stars].toString())
                    AudioFileIO.write(af)
                }
                else -> return Pair(false, "No rating-tag convention known for .${f.extension} files.")
            }
            Pair(true, null)
        } catch (e: Throwable) { Pair(false, "Couldn't write rating into ${f.name}: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun writePopm(mp3: MP3File, stars: Int) {
        var tag: AbstractID3v2Tag? = mp3.getID3v2Tag()
        if (tag == null) { tag = ID3v23Tag(); mp3.setID3v2Tag(tag) }
        else if (tag is ID3v22Tag) { tag = ID3v23Tag(tag); mp3.setID3v2Tag(tag) }
        val frame: AbstractID3v2Frame = tag!!.createFrame("POPM")
        frame.setBody(FrameBodyPOPM(POPM_EMAIL, WMP_POPM_BYTES[stars].toLong(), 0L))
        tag.setField(frame)
        AudioFileIO.write(mp3)
    }

    /** Writes any of title/artist/album/genre that are non-null. Returns (ok, message). */
    fun writeBasicTags(path: String, title: String?, artist: String?, album: String?, genre: String?): Pair<Boolean, String?> {
        init()
        val f = File(path)
        if (!f.exists()) return Pair(false, "File not found: $path")
        if (title == null && artist == null && album == null && genre == null) return Pair(true, null)
        if (!f.canWrite()) return Pair(false, "No write access to ${f.name}.")
        return try {
            val af = AudioFileIO.read(f)
            var tag: Tag = af.tagOrCreateAndSetDefault
            if (af is MP3File) {
                val t2 = af.getID3v2Tag()
                if (t2 is ID3v22Tag) { val n = ID3v23Tag(t2); af.setID3v2Tag(n); tag = n }
            }
            if (title != null) tag.setField(FieldKey.TITLE, title)
            if (artist != null) tag.setField(FieldKey.ARTIST, artist)
            if (album != null) tag.setField(FieldKey.ALBUM, album)
            if (genre != null) tag.setField(FieldKey.GENRE, genre)
            AudioFileIO.write(af)
            Pair(true, null)
        } catch (e: Throwable) { Pair(false, "Couldn't write tags into ${f.name}: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------------------------------
    // Info / artwork
    // ---------------------------------------------------------------------------------------
    class AudioInfo(val format: String, val bitrateKbps: Int?, val sampleRate: Int?, val channels: String?, val sizeBytes: Long, val durationSec: Double?)

    fun readAudioInfo(path: String): AudioInfo {
        val f = File(path)
        var bitrate: Int? = null; var rate: Int? = null; var ch: String? = null; var dur: Double? = null
        try {
            init()
            val h = AudioFileIO.read(f).audioHeader
            bitrate = h.bitRateAsNumber.toInt().takeIf { it > 0 }
            rate = h.sampleRateAsNumber.takeIf { it > 0 }
            ch = h.channels
            dur = h.trackLength.toDouble().takeIf { it > 0 }
        } catch (e: Throwable) { }
        return AudioInfo(f.extension.uppercase().ifEmpty { "?" }, bitrate, rate, ch, if (f.exists()) f.length() else 0L, dur)
    }

    /** Embedded cover art, scaled down to at most maxDim on the long side; null if none / unreadable. */
    fun readAlbumArt(path: String, maxDim: Int = 600): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val bytes = mmr.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Throwable) { null } finally { try { mmr.release() } catch (e: Throwable) { } }
    }
}
