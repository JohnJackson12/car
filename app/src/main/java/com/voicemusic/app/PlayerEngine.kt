package com.voicemusic.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.File

/**
 * Playback engine (port of player.py, on ExoPlayer instead of libVLC): queue, shuffle/repeat, skip-intro,
 * per-song trim, speed, EQ, ReplayGain, auto-duck, stall detection. MAIN THREAD ONLY.
 */
class PlayerEngine(private val ctx: Context, private val library: Library, private val config: Config) {

    interface Listener {
        fun onSongChanged(song: Song?)
        fun onPlaybackStateChanged()
        fun onIssue(message: String)
    }
    var listener: Listener? = null

    val queue = ArrayList<String>()
    var index = -1
        private set
    var shuffleEnabled = false
        private set
    var repeatMode = "off"                 // off | one | all
        private set
    private var playOrder = ArrayList<Int>()
    private var orderPos = -1

    private val eq = EqProcessor()
    private val exo: ExoPlayer
    private var skipSeconds = config.skipSeconds
    private var stopAt: Double? = null
    private var startedAt = 0.0
    private var ended = false
    private var released = false
    private var pendingError: String? = null

    // volume / duck
    private var baseVolume = config.masterVolume.coerceIn(0.0, 1.0)
    private var volume = baseVolume
    private var ducked = false
    private var fadeSeq = 0
    private var trackGain = 1.0
    private var rate = config.playbackSpeed.coerceIn(0.25, 3.0)

    // effects
    private var eqPreamp = config.eqPreamp
    private var eqBase = config.eqBandValues()
    private var bassBoost = config.bassBoost
    private var virtualizer = config.virtualizer

    // monitor state
    private var lastElapsed = -1.0
    private var stallStarted = 0L
    private var warned = false
    private var recovered = false
    private var consecutiveErrors = 0
    private var monitorRunning = false
    private var shutdown = false

    val audioSessionId: Int get() = exo.audioSessionId

    init {
        val rf = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)                       // 16-bit only: most compatible with cheap audio HALs
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(eq))
                    .build()
            }
        }
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        exo = ExoPlayer.Builder(ctx, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx, extractors))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { pendingError = error.errorCodeName; CrashLog.note("ExoPlayer error: ${error.errorCodeName}", error) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { listener?.onPlaybackStateChanged() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { listener?.onPlaybackStateChanged() }
            override fun onPlaybackStateChanged(playbackState: Int) { listener?.onPlaybackStateChanged() }
        })
        applyVolume()
        applyEffects()
        exo.setPlaybackParameters(PlaybackParameters(rate.toFloat(), 1f))
    }

    // -- queue / order -----------------------------------------------------------------------------
    fun loadQueue(ids: List<String>) { queue.clear(); queue.addAll(ids); index = -1; rebuildOrder(false) }

    private fun rebuildOrder(keepCurrent: Boolean = true) {
        val n = queue.size
        val order = ArrayList<Int>(n); for (i in 0 until n) order.add(i)
        if (shuffleEnabled && n > 1) {
            if (keepCurrent && index in 0 until n) {
                val rest = order.filter { it != index }.toMutableList(); rest.shuffle()
                order.clear(); order.add(index); order.addAll(rest)
            } else order.shuffle()
        }
        playOrder = order
        orderPos = if (index in 0 until n) playOrder.indexOf(index) else -1
    }

    fun setShuffle(on: Boolean) { if (on == shuffleEnabled) return; shuffleEnabled = on; rebuildOrder(true) }
    fun cycleRepeat(): String { repeatMode = when (repeatMode) { "off" -> "all"; "all" -> "one"; else -> "off" }; return repeatMode }

    fun currentSong(): Song? = if (index in 0 until queue.size) library.get(queue[index]) else null

    private fun effectiveStart(s: Song?): Double = Math.max(skipSeconds, s?.trimStart ?: 0.0)

    // -- transport -----------------------------------------------------------------------------------------
    fun playIndex(i: Int): Boolean {
        if (i !in 0 until queue.size) return false
        index = i
        val song = currentSong() ?: return false
        if (index !in playOrder) rebuildOrder(false)
        orderPos = playOrder.indexOf(index)
        loadAndPlay(song, effectiveStart(song), true)
        library.incrementPlayCount(song.id)
        listener?.onSongChanged(song)
        ensureMonitor()
        return true
    }

    private fun loadAndPlay(song: Song, startSeconds: Double, autoplay: Boolean) {
        ended = false; released = false; pendingError = null
        var start = Math.max(0.0, startSeconds)
        if (song.duration > 0) start = Math.min(start, Math.max(0.0, song.duration - song.trimEnd - 0.5))
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(song.path))), (start * 1000).toLong())
        trackGain = library.getPlaybackGain(song.id)
        applyVolume()
        exo.setPlaybackParameters(PlaybackParameters(rate.toFloat(), 1f))
        exo.playWhenReady = autoplay
        exo.prepare()
        startedAt = start
        stopAt = if (song.duration > 0 && song.trimEnd > 0) Math.max(start, song.duration - song.trimEnd) else null
        resetMonitor()
        listener?.onPlaybackStateChanged()
    }

    fun playSongId(id: String): Boolean { if (id !in queue) queue.add(id); return playIndex(queue.indexOf(id)) }

    fun play() {
        if (index == -1 && queue.isNotEmpty()) { playIndex(0); return }
        if (ended || released) {
            val s = currentSong() ?: return
            loadAndPlay(s, effectiveStart(s), true)
            library.incrementPlayCount(s.id)
            listener?.onSongChanged(s); ensureMonitor(); return
        }
        exo.play()
        ensureMonitor()
    }

    fun pause() { exo.pause() }
    fun isPaused(): Boolean = !exo.playWhenReady || ended || released
    fun isActuallyPlaying(): Boolean = exo.isPlaying

    fun next(): Boolean {
        if (repeatMode == "one") {
            val s = currentSong()
            if (s != null) { loadAndPlay(s, effectiveStart(s), true); library.incrementPlayCount(s.id); listener?.onSongChanged(s); return true }
        }
        if (orderPos + 1 < playOrder.size) return playIndex(playOrder[orderPos + 1])
        if (playOrder.isNotEmpty()) { if (shuffleEnabled) rebuildOrder(false); return playIndex(playOrder[0]) }
        return false
    }

    fun previous(): Boolean {
        if (orderPos - 1 >= 0) return playIndex(playOrder[orderPos - 1])
        if (playOrder.isNotEmpty()) return playIndex(playOrder[playOrder.size - 1])
        return false
    }

    /** Fully stop and let go of the file (needed before deleting/replacing the file that is playing). */
    fun stopAndRelease() { exo.stop(); exo.clearMediaItems(); released = true; listener?.onPlaybackStateChanged() }

    fun releaseFileForTrim(songId: String): Boolean {
        val s = currentSong() ?: return false
        if (s.id != songId) return false
        stopAndRelease(); return true
    }

    private fun reloadAt(start: Double) {
        val s = currentSong() ?: return
        val wasPaused = isPaused() && !ended
        loadAndPlay(s, start, !wasPaused)
    }

    fun getSkipSeconds() = skipSeconds
    fun setSkipSeconds(sec: Double): Boolean {
        skipSeconds = Math.max(0.0, sec)
        currentSong()?.let { reloadAt(effectiveStart(it)) }
        return true
    }

    fun setTrimStart(sec: Double): Boolean {
        val s = currentSong() ?: return false
        library.setTrimStart(s.id, sec)
        reloadAt(effectiveStart(s)); return true
    }

    fun setTrimEnd(sec: Double): Boolean {
        val s = currentSong() ?: return false
        library.setTrimEnd(s.id, sec)
        stopAt = if (s.duration > 0 && s.trimEnd > 0) Math.max(elapsed(), s.duration - s.trimEnd) else null
        return true
    }

    fun seek(sec: Double): Boolean {
        val s = currentSong() ?: return false
        if (released || ended) { loadAndPlay(s, sec, !isPaused() || ended); return true }
        exo.seekTo((Math.max(0.0, sec) * 1000).toLong()); return true
    }

    fun seekRelative(delta: Double): Boolean {
        val s = currentSong() ?: return false
        var target = Math.max(0.0, elapsed() + delta)
        if (s.duration > 0) target = Math.min(target, Math.max(0.0, s.duration - s.trimEnd - 0.5))
        return seek(target)
    }

    fun elapsed(): Double {
        if (released) return startedAt
        val t = exo.currentPosition
        return if (t >= 0) t / 1000.0 else startedAt
    }

    fun duration(): Double? = currentSong()?.duration?.takeIf { it > 0 }

    fun removeCurrentFromQueue() {
        if (index in 0 until queue.size) {
            queue.removeAt(index)
            if (index >= queue.size) index = -1
            rebuildOrder(index != -1)
        }
    }

    fun queueMove(id: String, dir: Int): Boolean {
        val i = queue.indexOf(id); if (i < 0) return false
        val j = i + dir; if (j < 0 || j >= queue.size) return false
        java.util.Collections.swap(queue, i, j)
        if (index == i) index = j else if (index == j) index = i
        rebuildOrder(index != -1); return true
    }

    fun queueRemove(id: String): Boolean {
        val i = queue.indexOf(id); if (i < 0) return false
        if (i == index) { removeCurrentFromQueue(); return true }
        queue.removeAt(i); if (i < index) index--
        rebuildOrder(index != -1); return true
    }

    fun addToQueueIfMissing(id: String) { if (id !in queue) { queue.add(id); rebuildOrder(index != -1) } }

    fun resumeState(): Pair<String?, Double> { val s = currentSong() ?: return Pair(null, 0.0); return Pair(s.id, elapsed()) }

    fun cueSongId(id: String, position: Double = 0.0, paused: Boolean = true): Boolean {
        if (id !in queue) queue.add(id)
        index = queue.indexOf(id)
        rebuildOrder(true)
        val s = currentSong() ?: return false
        loadAndPlay(s, position, !paused)
        listener?.onSongChanged(s)
        ensureMonitor()
        return true
    }

    // -- volume & duck ---------------------------------------------------------------------------------------
    fun getVolume() = baseVolume
    fun setVolume(v: Double) {
        baseVolume = v.coerceIn(0.0, 1.0)
        config.masterVolume = baseVolume
        if (!ducked) { fadeSeq++; setActual(baseVolume) }
    }

    private fun setActual(v: Double) { volume = v.coerceIn(0.0, 1.0); applyVolume() }
    private fun applyVolume() { exo.volume = (volume * trackGain).coerceIn(0.0, 1.0).toFloat() }

    fun duck(factor: Double, fadeMs: Int = 150) {
        if (ducked) return
        ducked = true
        fadeTo((baseVolume * factor).coerceIn(0.0, 1.0), fadeMs)
    }

    fun restoreVolume(fadeMs: Int = 250) {
        if (!ducked) return
        ducked = false
        fadeTo(baseVolume, fadeMs)
    }

    private fun fadeTo(target: Double, ms: Int) {
        fadeSeq++
        val mySeq = fadeSeq
        val start = volume
        val steps = Math.max(1, ms / 20)
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (mySeq != fadeSeq || shutdown) return
                i++
                setActual(start + (target - start) * i / steps)
                if (i < steps) Bg.main.postDelayed(this, 20)
            }
        }
        Bg.main.post(step)
    }

    // -- speed & effects ---------------------------------------------------------------------------------------
    fun getRate() = rate
    fun setRate(r: Double) {
        rate = r.coerceIn(0.25, 3.0); config.playbackSpeed = rate
        exo.setPlaybackParameters(PlaybackParameters(rate.toFloat(), 1f))
    }

    private fun effectiveBands(): DoubleArray {
        val b = eqBase.copyOf()
        if (bassBoost) for (i in 0 until Math.min(EqDsp.BASS_BOOST_BANDS, b.size)) b[i] = Math.max(b[i], EqDsp.BASS_BOOST_DB)
        return b
    }
    private fun applyEffects() { eq.dsp.setParams(eqPreamp, effectiveBands(), virtualizer) }

    fun eqState(): Triple<Double, DoubleArray, Pair<Boolean, Boolean>> = Triple(eqPreamp, effectiveBands(), Pair(bassBoost, virtualizer))
    fun applyPreset(name: String): Boolean {
        val p = EqPresets.TABLE[name] ?: return false
        eqBase = p.copyOf(); eqPreamp = 0.0; bassBoost = false
        config.eqPreset = name; persistEq(); applyEffects(); return true
    }
    fun setEqBand(i: Int, db: Double) {
        if (i !in 0 until 10) return
        eqBase[i] = db.coerceIn(-20.0, 20.0); config.eqPreset = "Custom"; persistEq(); applyEffects()
    }
    fun setPreamp(db: Double) { eqPreamp = db.coerceIn(-20.0, 20.0); config.eqPreset = "Custom"; persistEq(); applyEffects() }
    fun resetEq() { eqBase = DoubleArray(10); eqPreamp = 0.0; bassBoost = false; config.eqPreset = "Custom"; persistEq(); applyEffects() }
    fun setBassBoost(on: Boolean) { bassBoost = on; persistEq(); applyEffects() }
    fun setVirtualizer(on: Boolean) { virtualizer = on; persistEq(); applyEffects() }
    private fun persistEq() { config.eqPreamp = eqPreamp; config.setEqBandValues(eqBase); config.bassBoost = bassBoost; config.virtualizer = virtualizer }

    // -- monitor: auto-advance, trim-end, error skip, stall recovery (port of _monitor_loop) -----------------------
    private fun resetMonitor() { lastElapsed = -1.0; stallStarted = 0L; warned = false; recovered = false }

    private fun ensureMonitor() {
        if (monitorRunning) return
        monitorRunning = true
        Bg.main.postDelayed(object : Runnable {
            override fun run() {
                if (shutdown) { monitorRunning = false; return }
                try { tick() } catch (e: Throwable) { CrashLog.note("player tick", e) }
                Bg.main.postDelayed(this, 200)
            }
        }, 200)
    }

    private fun songName() = currentSong()?.title ?: "the current track"

    private fun endOfQueueStop() { exo.playWhenReady = false; exo.stop(); ended = true; listener?.onPlaybackStateChanged() }

    private fun tick() {
        if (index == -1 || released || ended || !exo.playWhenReady) { resetMonitor(); return }
        val state = exo.playbackState
        val hitTrimEnd = stopAt != null && elapsed() >= stopAt!!
        val finished = state == Player.STATE_ENDED
        val errored = pendingError != null
        if (hitTrimEnd || finished || errored) {
            if (errored) {
                consecutiveErrors++
                listener?.onIssue("Playback error on '${songName()}' - skipping to the next track.")
                pendingError = null
                if (consecutiveErrors >= 5) {
                    listener?.onIssue("$consecutiveErrors tracks in a row failed to play - stopping rather than skipping through the whole " +
                        "queue. This usually means a drive/USB stick just went offline, not that the songs are broken.")
                    consecutiveErrors = 0
                    endOfQueueStop(); resetMonitor(); return
                }
            } else consecutiveErrors = 0
            if (!next()) endOfQueueStop()
            resetMonitor(); return
        }
        // stall detection: "playing" but the position isn't moving
        val e = elapsed()
        if (exo.isPlaying && lastElapsed >= 0 && e <= lastElapsed + 0.01) {
            if (stallStarted == 0L) stallStarted = System.currentTimeMillis()
            val stalled = (System.currentTimeMillis() - stallStarted) / 1000.0
            if (stalled >= 2.5 && !warned) { warned = true; listener?.onIssue("Playback stalled on '${songName()}' (slow or disconnected drive?) - trying to recover.") }
            if (stalled >= 6.0 && !recovered) { recovered = true; try { exo.seekTo((Math.max(0.0, e) * 1000).toLong()) } catch (x: Exception) { } }
            if (stalled >= 12.0) {
                listener?.onIssue("'${songName()}' didn't recover from a playback stall - skipping it.")
                if (!next()) endOfQueueStop()
                resetMonitor(); return
            }
        } else { stallStarted = 0L; warned = false; recovered = false }
        lastElapsed = e
    }

    /**
     * Trashes the currently playing song safely (port of main.py's "delete" handler): release the
     * file first so nothing has it open, drop it from the queue, THEN move it to trash, then resume
     * playing whatever now sits at that queue position.
     */
    fun deleteCurrentSong(library: Library): Pair<Boolean, Boolean> {
        val s = currentSong() ?: return Pair(false, false)
        stopAndRelease()
        removeCurrentFromQueue()
        val result = library.delete(s.id)
        if (queue.isNotEmpty()) {
            val nextIndex = (if (index >= 0) index else 0).coerceIn(0, queue.size - 1)
            playIndex(nextIndex)
        }
        return result
    }

    /** Re-loads a song that was just overwritten on disk (e.g. after baking a trim), preserving playing state. */
    fun reloadCurrentAfterFileReplaced(paused: Boolean) {
        val s = currentSong() ?: return
        loadAndPlay(s, 0.0, !paused)
    }

    fun release() { shutdown = true; try { exo.release() } catch (e: Throwable) { } }
}
