package com.voicemusic.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * SQLite persistence for songs + playlists. Reads happen once at startup; every later change is
 * written through on a single background thread so the UI never waits on disk (weak head-unit flash).
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 1) {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "db-writer").apply { isDaemon = true } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE songs(id TEXT PRIMARY KEY, path TEXT, folder TEXT, title TEXT, artist TEXT, album TEXT,
            genre TEXT, year INTEGER, track INTEGER, duration REAL, rating INTEGER, rating_synced INTEGER, favorite INTEGER,
            approved INTEGER, trim_start REAL, trim_end REAL, play_count INTEGER, last_played INTEGER, added_at INTEGER,
            replaygain REAL, bookmark REAL)""")
        db.execSQL("CREATE TABLE playlists(id TEXT PRIMARY KEY, name TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE playlist_items(playlist_id TEXT, pos INTEGER, song_id TEXT)")
        db.execSQL("CREATE INDEX idx_pl ON playlist_items(playlist_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun values(s: Song): ContentValues {
        val v = ContentValues()
        v.put("id", s.id); v.put("path", s.path); v.put("folder", s.folder); v.put("title", s.title)
        v.put("artist", s.artist); v.put("album", s.album); v.put("genre", s.genre)
        v.put("year", s.year); v.put("track", s.trackNumber); v.put("duration", s.duration)
        v.put("rating", s.rating); v.put("rating_synced", if (s.ratingTagSynced) 1 else 0)
        v.put("favorite", if (s.favorite) 1 else 0); v.put("approved", if (s.approved) 1 else 0)
        v.put("trim_start", s.trimStart); v.put("trim_end", s.trimEnd)
        v.put("play_count", s.playCount); v.put("last_played", s.lastPlayedAt); v.put("added_at", s.addedAt)
        if (s.hasReplayGain) v.put("replaygain", s.replayGainDb) else v.putNull("replaygain")
        if (s.bookmark >= 0) v.put("bookmark", s.bookmark) else v.putNull("bookmark")
        return v
    }

    fun loadSongs(): List<Song> {
        val out = ArrayList<Song>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,path,folder,title,artist,album,genre,year,track,duration,rating," +
                "rating_synced,favorite,approved,trim_start,trim_end,play_count,last_played,added_at,replaygain,bookmark FROM songs", null)
            c.use {
                while (it.moveToNext()) {
                    out.add(Song(
                        id = it.getString(0), path = it.getString(1) ?: "", folder = it.getString(2) ?: "",
                        title = it.getString(3) ?: "", artist = it.getString(4) ?: "Unknown", album = it.getString(5) ?: "",
                        genre = it.getString(6) ?: "", year = it.getInt(7), trackNumber = it.getInt(8), duration = it.getDouble(9),
                        rating = it.getInt(10), ratingTagSynced = it.getInt(11) != 0, favorite = it.getInt(12) != 0,
                        approved = it.getInt(13) != 0, trimStart = it.getDouble(14), trimEnd = it.getDouble(15),
                        playCount = it.getInt(16), lastPlayedAt = it.getLong(17), addedAt = it.getLong(18),
                        replayGainDb = if (it.isNull(19)) Double.NaN else it.getDouble(19),
                        bookmark = if (it.isNull(20)) -1.0 else it.getDouble(20)
                    ))
                }
            }
        } catch (e: Exception) { CrashLog.note("loadSongs failed", e) }
        return out
    }

    fun loadPlaylists(): List<Playlist> {
        val map = LinkedHashMap<String, Playlist>()
        try {
            readableDatabase.rawQuery("SELECT id,name,created_at FROM playlists", null).use {
                while (it.moveToNext()) map[it.getString(0)] = Playlist(it.getString(0), it.getString(1) ?: "", ArrayList(), it.getLong(2))
            }
            readableDatabase.rawQuery("SELECT playlist_id,song_id FROM playlist_items ORDER BY playlist_id,pos", null).use {
                while (it.moveToNext()) map[it.getString(0)]?.songIds?.add(it.getString(1))
            }
        } catch (e: Exception) { CrashLog.note("loadPlaylists failed", e) }
        return map.values.toList()
    }

    // -- async write-through ---------------------------------------------------------------
    fun saveSong(s: Song) {
        val v = values(s)
        writer.execute { try { writableDatabase.insertWithOnConflict("songs", null, v, SQLiteDatabase.CONFLICT_REPLACE) } catch (e: Exception) { CrashLog.note("saveSong", e) } }
    }

    fun saveSongs(list: List<Song>) {
        val vs = list.map { values(it) }
        writer.execute {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try { for (v in vs) db.insertWithOnConflict("songs", null, v, SQLiteDatabase.CONFLICT_REPLACE); db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            } catch (e: Exception) { CrashLog.note("saveSongs", e) }
        }
    }

    fun deleteSongs(ids: List<String>) {
        val copy = ids.toList()
        writer.execute {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try { for (id in copy) db.delete("songs", "id=?", arrayOf(id)); db.setTransactionSuccessful() } finally { db.endTransaction() }
            } catch (e: Exception) { CrashLog.note("deleteSongs", e) }
        }
    }

    fun savePlaylist(p: Playlist) {
        val id = p.id; val name = p.name; val created = p.createdAt; val items = p.songIds.toList()
        writer.execute {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    val cv = ContentValues(); cv.put("id", id); cv.put("name", name); cv.put("created_at", created)
                    db.insertWithOnConflict("playlists", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    db.delete("playlist_items", "playlist_id=?", arrayOf(id))
                    items.forEachIndexed { i, sid ->
                        val r = ContentValues(); r.put("playlist_id", id); r.put("pos", i); r.put("song_id", sid)
                        db.insert("playlist_items", null, r)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } catch (e: Exception) { CrashLog.note("savePlaylist", e) }
        }
    }

    fun deletePlaylist(id: String) {
        writer.execute {
            try {
                val db = writableDatabase
                db.delete("playlists", "id=?", arrayOf(id)); db.delete("playlist_items", "playlist_id=?", arrayOf(id))
            } catch (e: Exception) { CrashLog.note("deletePlaylist", e) }
        }
    }

    /** Blocks until queued writes have finished (used on shutdown). */
    fun flush() {
        try { writer.submit { }.get(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
    }
}
