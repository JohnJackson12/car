package com.voicemusic.app

/** One library entry (mirrors the dict the PC app kept in library.json). Mutated on the main thread only. */
class Song(
    val id: String,
    var path: String,
    var folder: String,
    var title: String,
    var artist: String = "Unknown",
    var album: String = "",
    var genre: String = "",
    var year: Int = 0,
    var trackNumber: Int = 0,
    var duration: Double = 0.0,          // seconds; 0 = unknown
    var rating: Int = 0,
    var ratingTagSynced: Boolean = true,
    var favorite: Boolean = false,
    var approved: Boolean = false,
    var trimStart: Double = 0.0,
    var trimEnd: Double = 0.0,           // seconds cut off the END
    var playCount: Int = 0,
    var lastPlayedAt: Long = 0L,
    var addedAt: Long = 0L,
    var replayGainDb: Double = Double.NaN,
    var bookmark: Double = -1.0,
    var trashPath: String = ""           // only while sitting in trash (undo)
) {
    val hasReplayGain: Boolean get() = !replayGainDb.isNaN()
    val hasTrim: Boolean get() = trimStart > 0.0 || trimEnd > 0.0

    private var searchKeyCache: String? = null
    fun searchKey(): String {
        var k = searchKeyCache
        if (k == null) {
            k = (title + " " + artist + " " + genre + " " + album + " " + folder).lowercase()
            searchKeyCache = k
        }
        return k
    }
    fun invalidateSearchKey() { searchKeyCache = null }

    fun copySnapshot(): Song = Song(id, path, folder, title, artist, album, genre, year, trackNumber, duration, rating,
        ratingTagSynced, favorite, approved, trimStart, trimEnd, playCount, lastPlayedAt, addedAt, replayGainDb, bookmark, trashPath)
}

class Playlist(val id: String, var name: String, val songIds: ArrayList<String> = ArrayList(), val createdAt: Long = System.currentTimeMillis())
