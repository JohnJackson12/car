package com.voicemusic.app

/**
 * Executes a parsed [VoiceCommand] against the running service (port of the command-handling
 * switch in the PC app's GUI event loop). Runs on the main thread.
 */
object VoiceCommandRouter {
    fun dispatch(svc: PlaybackService, cmd: VoiceCommand) {
        val engine = svc.engine
        val library = (svc.application as App).library
        val config = (svc.application as App).config
        fun say(text: String) { if (config.spokenFeedback) SpokenFeedback.speak(text) }
        when (cmd.kind) {
            "next" -> engine.next()
            "previous" -> engine.previous()
            "pause" -> engine.pause()
            "play" -> if (engine.isPaused()) svc.requestFocusAndPlay()
            "status" -> {
                val s = engine.currentSong()
                say(if (s != null) "Playing ${s.title} by ${s.artist}" else "Nothing is playing")
            }
            "undo" -> { val s = library.undoDelete(); say(if (s != null) "Restored ${s.title}" else "Nothing to restore") }
            "delete" -> { val title = engine.currentSong()?.title; engine.deleteCurrentSong(library); say(if (title != null) "Deleted $title" else "Nothing to delete") }
            // "Keep" is manual curation, not a rating: mark it worth keeping and move straight on
            // to the next song (matches the PC app's "listen a bit, decide, move on" workflow).
            "approve" -> { engine.currentSong()?.let { library.setApproved(it.id, true) }; say("Kept"); engine.next() }
            "rate" -> { engine.currentSong()?.let { library.setRating(it.id, cmd.number) }; say("Rated ${cmd.number} stars") }
            // One-time forward jump in the CURRENT song only - does not touch the persisted global skip.
            "skip_current" -> engine.seekRelative(cmd.number.toDouble())
            // Persisted, global start-point applied to every song (current one included) from now on.
            "skip_all" -> { engine.setSkipSeconds(cmd.number.toDouble()); say("Skip set to ${cmd.number} seconds") }
            "trim" -> engine.currentSong()?.let { s ->
                // Both sides are set unconditionally (0 legitimately means "no cut on this side" -
                // the parser already guarantees at least one of the two is non-zero).
                library.setTrimStart(s.id, cmd.number.toDouble())
                library.setTrimEnd(s.id, cmd.number2.toDouble())
                engine.seek(engine.elapsed())
            }
            "play_song" -> {
                if (!config.voicePlayByTitle) return
                val target = library.findByNormalizedTitle(VoiceParser.normalizeTitle(cmd.text))
                if (target != null) engine.playSongId(target.id)
            }
        }
    }
}
