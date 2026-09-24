package com.voicemusic.app

/**
 * Executes a parsed [VoiceCommand] (port of main.py's on_command(), which is the actual dispatcher
 * in the Windows app - not gui.py, which only logs/displays what happened). Every branch, tone, and
 * spoken line here is matched against that function directly. Runs on the main thread.
 *
 * Two things the original always did regardless of any setting, kept the same way here: the
 * confirmation TONE for every command (ack/error/rating/delete/undo - feedback.py), and wrapping
 * the whole dispatch so an unexpected failure plays the error tone instead of silently doing
 * nothing or crashing. "Spoken feedback" (config.spokenFeedback) only gates the extra TTS lines,
 * which are additive on top of those tones, not a replacement for them.
 */
object VoiceCommandRouter {
    fun dispatch(svc: PlaybackService, cmd: VoiceCommand) {
        val engine = svc.engine
        val library = (svc.application as App).library
        val config = (svc.application as App).config
        val style = config.feedbackSound
        val vol = config.feedbackVolume
        fun ack() { FeedbackSounds.playAck(svc, style, vol, config.feedbackCustomPath) }
        fun err() { FeedbackSounds.playError(svc, style, vol) }
        fun say(text: String) { if (config.spokenFeedback) SpokenFeedback.speak(text) }
        fun refreshVoiceTitles() { svc.voice.updateSongTitles(library.songs.values.map { it.title }) }

        try {
            when (cmd.kind) {
                "rate" -> {
                    val s = engine.currentSong()
                    if (s != null) {
                        library.setApproved(s.id, true)
                        FeedbackSounds.playRating(svc, style, cmd.number, vol)
                        library.setRating(s.id, cmd.number) { ok, _ -> say("Rated ${cmd.number}. ${if (ok) "Saved." else "Not saved to file."}") }
                    } else err()
                }
                "approve" -> {
                    val s = engine.currentSong()
                    if (s != null) { library.setApproved(s.id, true); ack(); engine.next() } else err()
                }
                "delete" -> {
                    val s = engine.currentSong()
                    if (s != null) {
                        val (_, trashedOk) = engine.deleteCurrentSong(library)
                        refreshVoiceTitles()
                        FeedbackSounds.playDelete(svc, style, vol)
                        if (!trashedOk) say("Removed from your library, but the file itself is still on your drive - it may still have been in use.")
                    } else err()
                }
                "undo" -> {
                    val s = library.undoDelete()
                    if (s != null) { engine.addToQueueIfMissing(s.id); refreshVoiceTitles(); FeedbackSounds.playUndo(svc, style, vol) } else err()
                }
                "next" -> if (engine.next()) ack() else err()
                "previous" -> if (engine.previous()) ack() else err()
                "pause" -> { engine.pause(); ack() }
                "play" -> { if (engine.isPaused()) svc.requestFocusAndPlay() else engine.play(); ack() }
                // Persisted, global start-point applied to every song (current one included) from now on.
                "skip_all" -> { engine.setSkipSeconds(cmd.number.toDouble()); ack() }
                // One-time forward jump in the CURRENT song only - does not touch the persisted global skip.
                "skip_current" -> if (engine.seekRelative(cmd.number.toDouble())) ack() else err()
                "trim" -> {
                    val s = engine.currentSong()
                    if (s != null) {
                        library.setTrimStart(s.id, cmd.number.toDouble())
                        library.setTrimEnd(s.id, cmd.number2.toDouble())
                        engine.seek(engine.elapsed())
                        ack()
                        say("Trim set. ${cmd.number} seconds from the start, ${cmd.number2} from the end.")
                    } else err()
                }
                "play_song" -> {
                    val target = library.findByNormalizedTitle(VoiceParser.normalizeTitle(cmd.text))
                    if (target != null) { engine.playSongId(target.id); ack(); say("Playing ${target.title}") } else err()
                }
                "status" -> {
                    ack()
                    val s = engine.currentSong()
                    say(if (s != null) "Playing ${s.title} by ${s.artist}" else "Nothing is playing")
                }
            }
        } catch (e: Throwable) {
            CrashLog.note("voice command '${cmd.kind}' failed", e)
            err()
        }
    }
}
