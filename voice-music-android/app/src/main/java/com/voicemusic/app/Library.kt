package com.voicemusic.app

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Group(val name: String, val count: Int)

/**
 * Song library (port of library.py). All state is touched on the main thread; slow file work
 * (scanning, tag writes, trimming, copying) runs on background threads and reports back on main.
 */
class Library(private val ctx: Context, val config: Config, private val db: Db) {
    val songs = HashMap<String, Song>()
    val playlists = LinkedHashMap<String, Playlist>()
    val folders = ArrayList<String>()
    private val lastDeleted = ArrayList<Song>()
    @Volatile var scanning = false
        private set

    companion object {
        const val TRASH_DIR = ".vm_trash"
        fun normalizeTitle(t: String?) = VoiceParser.normalizeTitle(t)
    }

    init {
        for (s in db.loadSongs()) songs[s.id] = s
        for (p in db.loadPlaylists()) playlists[p.id] = p
        folders.addAll(config.libraryFolders)
    }

    private fun persistFolders() { config.libraryFolders = folders.toList() }

    // -- folders ---------------------------------------------------------------------------------
    class FolderInfo(val path: String, val exists: Boolean)
    fun listFolders(): List<FolderInfo> = folders.map { FolderInfo(it, File(it).isDirectory) }

    fun addFolder(path: String): Pair<Boolean, String?> {
        val p = File(path)
        if (!p.isDirectory) return Pair(false, "'$path' isn't a folder that exists.")
        val resolved = try { p.canonicalPath } catch (e: Exception) { p.absolutePath }
        if (resolved in folders) return Pair(false, "That folder is already in your library.")
        for (ex in folders) {
            if (resolved.startsWith("$ex/")) return Pair(false, "That's inside a folder you already added ($ex).")
            if (ex.startsWith("$resolved/")) return Pair(false, "You already scan a subfolder of this ($ex). Remove it first if you want to add the parent instead.")
        }
        folders.add(resolved); persistFolders()
        return Pair(true, null)
    }

    /** Stops scanning a folder and drops its songs from the app (files on disk untouched). Returns number pruned or null. */
    fun removeFolder(path: String): Int? {
        if (path !in folders) return null
        folders.remove(path); persistFolders()
        val prefix = path.trimEnd('/')
        val gone = songs.values.filter { it.path == prefix || it.path.startsWith("$prefix/") }.map { it.id }
        for (id in gone) songs.remove(id)
        if (gone.isNotEmpty()) db.deleteSongs(gone)
        return gone.size
    }

    // -- metadata reading -------------------------------------------------------------------------
    class Basic(var title: String?, var artist: String?, var album: String?, var genre: String?, var year: Int, var track: Int, var durationSec: Double)

    fun readBasic(path: String, mmr: MediaMetadataRetriever): Basic {
        val b = Basic(null, null, null, null, 0, 0, 0.0)
        try {
            mmr.setDataSource(path)
            fun m(k: Int): String? = try { mmr.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
            b.title = m(MediaMetadataRetriever.METADATA_KEY_TITLE)
            b.artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            b.album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            b.genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val y = (m(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: m(MediaMetadataRetriever.METADATA_KEY_DATE) ?: "")
            val digits = y.take(4)
            b.year = if (digits.length == 4 && digits.all { it in '0'..'9' }) digits.toInt() else 0
            b.track = (m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: "").substringBefore('/').trim().toIntOrNull() ?: 0
            b.durationSec = (m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: Throwable) { /* unreadable tags: fall back to the file name */ }
        return b
    }

    fun readDuration(path: String): Double {
        val mmr = MediaMetadataRetriever()
        return try { readBasic(path, mmr).durationSec } finally { try { mmr.release() } catch (e: Throwable) { } }
    }

    // -- scanning -------------------------------------------------------------------------------------
    private fun collectAudioFiles(root: File, exts: Set<String>, out: MutableList<File>, depth: Int = 0) {
        if (depth > 40) return
        val kids = try { root.listFiles() } catch (e: Exception) { null } ?: return
        for (f in kids) {
            try {
                val name = f.name
                if (f.isDirectory) {
                    if (name.startsWith(".")) continue          // trash + hidden folders (.thumbnails etc.)
                    collectAudioFiles(f, exts, out, depth + 1)
                } else {
                    val dot = name.lastIndexOf('.')
                    if (dot > 0 && ("." + name.substring(dot + 1).lowercase()) in exts && !name.startsWith(".")) out.add(f)
                }
            } catch (e: Exception) { }
        }
    }

    fun scanAsync(progress: (seen: Int, added: Int) -> Unit, done: (added: Int, pruned: Int, error: String?) -> Unit) {
        if (scanning) return
        scanning = true
        val exts = config.supportedExtensions.map { it.lowercase() }.toSet()
        val known = HashSet<String>().also { k -> songs.values.forEach { k.add(it.path) } }
        val folderList = folders.toList()
        val checkList = songs.values.map { Triple(it.id, it.path, it.folder) }
        Bg.io {
            var error: String? = null
            val newSongs = ArrayList<Song>()
            val prune = ArrayList<String>()
            val mmr = MediaMetadataRetriever()
            try {
                var seen = 0
                for (folder in folderList) {
                    val root = File(folder)
                    if (!root.isDirectory) continue
                    val files = ArrayList<File>()
                    collectAudioFiles(root, exts, files)
                    for (f in files) {
                        if (f.path in known) continue
                        seen++
                        val b = readBasic(f.path, mmr)
                        val extra = TagIO.readExtra(f)
                        val stem = f.name.substringBeforeLast('.')
                        newSongs.add(Song(
                            id = UUID.randomUUID().toString().substring(0, 8), path = f.path, folder = root.path,
                            title = b.title ?: stem, artist = b.artist ?: "Unknown", album = b.album ?: "", genre = b.genre ?: "",
                            year = b.year, trackNumber = b.track, duration = b.durationSec, rating = extra.rating ?: 0,
                            addedAt = System.currentTimeMillis(), replayGainDb = extra.replayGainDb ?: Double.NaN))
                        known.add(f.path)
                        if (seen % 25 == 0) { val s = seen; val a = newSongs.size; Bg.post { progress(s, a) } }
                    }
                }
                // prune entries whose file is gone - but never when their whole folder is offline (unmounted USB etc.)
                for ((id, path, folder) in checkList) {
                    if (folder.isNotEmpty() && !File(folder).isDirectory) continue
                    if (!File(path).exists()) prune.add(id)
                }
            } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName; CrashLog.note("scan failed", e) }
            finally { try { mmr.release() } catch (e: Throwable) { } }
            Bg.post {
                for (s in newSongs) songs[s.id] = s
                if (newSongs.isNotEmpty()) db.saveSongs(newSongs)
                for (id in prune) songs.remove(id)
                if (prune.isNotEmpty()) db.deleteSongs(prune)
                scanning = false
                done(newSongs.size, prune.size, error)
            }
        }
    }

    // -- queries ---------------------------------------------------------------------------------------
    fun allSongsSorted(): List<Song> = songs.values.sortedWith(compareBy<Song>({ it.artist }, { it.title }))
    fun get(id: String?): Song? = if (id == null) null else songs[id]
    fun genres(): List<String> = songs.values.map { it.genre }.filter { it.isNotEmpty() }.toSortedSet().toList()

    fun findByNormalizedTitle(n: String): Song? = songs.values.firstOrNull { normalizeTitle(it.title) == n }

    private fun grouped(key: (Song) -> String): List<Group> {
        val counts = HashMap<String, Int>()
        for (s in songs.values) { val k = key(s); if (k.isNotEmpty()) counts[k] = (counts[k] ?: 0) + 1 }
        return counts.entries.map { Group(it.key, it.value) }.sortedBy { it.name.lowercase() }
    }
    fun albums() = grouped { it.album }
    fun artists() = grouped { it.artist }
    fun genreGroups() = grouped { it.genre }
    fun foldersGrouped() = grouped { it.folder }

    fun songsInAlbum(n: String) = songs.values.filter { it.album == n }.sortedWith(compareBy<Song>({ it.trackNumber }, { it.title }))
    fun songsByArtist(n: String) = songs.values.filter { it.artist == n }.sortedWith(compareBy<Song>({ it.album }, { it.trackNumber }, { it.title }))
    fun songsInGenre(n: String) = songs.values.filter { it.genre == n }.sortedWith(compareBy<Song>({ it.artist }, { it.title }))
    fun songsInFolder(n: String) = songs.values.filter { it.folder == n }.sortedWith(compareBy<Song>({ it.artist }, { it.title }))
    fun favorites() = songs.values.filter { it.favorite }.sortedWith(compareBy<Song>({ it.artist }, { it.title }))
    fun recentlyAdded(limit: Int = 200) = songs.values.sortedByDescending { it.addedAt }.take(limit)
    fun recentlyPlayed(limit: Int = 200) = songs.values.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }.take(limit)
    fun mostPlayed(limit: Int = 200) = songs.values.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(limit)

    // -- mutations -----------------------------------------------------------------------------------------
    private fun save(s: Song) { db.saveSong(s) }

    /** Saves the rating in the app immediately, then writes it into the file's own tags in the background. */
    fun setRating(id: String, rating: Int, cb: ((Boolean, String?) -> Unit)? = null) {
        val s = songs[id] ?: run { cb?.invoke(false, "Unknown song."); return }
        s.rating = rating; save(s)
        val path = s.path; val max = config.maxRating
        Bg.io {
            val (ok, msg) = TagIO.writeRating(path, rating, max)
            Bg.post { songs[id]?.let { it.ratingTagSynced = ok; save(it) }; cb?.invoke(ok, msg) }
        }
    }

    fun setTrimStart(id: String, sec: Double): Boolean { val s = songs[id] ?: return false; s.trimStart = Math.max(0.0, sec); save(s); return true }
    fun setTrimEnd(id: String, sec: Double): Boolean { val s = songs[id] ?: return false; s.trimEnd = Math.max(0.0, sec); save(s); return true }
    fun setBookmark(id: String, sec: Double) { songs[id]?.let { it.bookmark = Math.max(0.0, sec); save(it) } }
    fun clearBookmark(id: String) { songs[id]?.let { it.bookmark = -1.0; save(it) } }

    fun incrementPlayCount(id: String) { songs[id]?.let { it.playCount++; it.lastPlayedAt = System.currentTimeMillis(); save(it) } }

    fun toggleFavorite(id: String): Boolean? { val s = songs[id] ?: return null; s.favorite = !s.favorite; save(s); return s.favorite }
    fun setFavorite(id: String, v: Boolean): Boolean? { val s = songs[id] ?: return null; s.favorite = v; save(s); return v }
    fun setApproved(id: String, v: Boolean): Boolean? { val s = songs[id] ?: return null; s.approved = v; save(s); return v }

    fun getPlaybackGain(id: String): Double {
        if (!config.enableReplayGain) return 1.0
        val s = songs[id] ?: return 1.0
        if (!s.hasReplayGain) return 1.0
        return Math.pow(10.0, s.replayGainDb / 20.0).coerceIn(0.1, 2.0)
    }

    // -- tags & rename ------------------------------------------------------------------------------------------
    fun updateTagsAndFilename(id: String, title: String? = null, artist: String? = null, album: String? = null,
                              genre: String? = null, newFilename: String? = null, cb: (Boolean, String?) -> Unit) {
        val s = songs[id] ?: run { cb(false, "Unknown song."); return }
        val oldPath = s.path
        Bg.io {
            var ok = true; var msg: String? = null
            if (title != null || artist != null || album != null || genre != null) {
                val r = TagIO.writeBasicTags(oldPath, title, artist, album, genre); ok = r.first; msg = r.second
            }
            var newPath: String? = null
            var renameMsg: String? = null
            val f = File(oldPath)
            if (!newFilename.isNullOrEmpty() && newFilename != f.name) {
                val target = File(f.parentFile, newFilename)
                if (target.exists()) renameMsg = "Tags saved, but couldn't rename: a file named '$newFilename' already exists."
                else if (f.renameTo(target)) newPath = target.path
                else renameMsg = "Tags saved, but couldn't rename the file (no write access?)."
            }
            Bg.post {
                songs[id]?.let { sg ->
                    if (title != null) sg.title = title
                    if (artist != null) sg.artist = artist
                    if (album != null) sg.album = album
                    if (genre != null) sg.genre = genre
                    if (newPath != null) sg.path = newPath
                    sg.invalidateSearchKey(); save(sg)
                }
                if (renameMsg != null) cb(false, renameMsg) else cb(ok, msg)
            }
        }
    }

    // -- trash / delete / undo -------------------------------------------------------------------------------------
    private fun moveFile(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        return try { src.copyTo(dest, overwrite = false); if (dest.length() == src.length()) { src.delete(); true } else { dest.delete(); false } }
        catch (e: Exception) { try { dest.delete() } catch (x: Exception) { }; false }
    }

    private fun trashDirs(song: Song): List<File> {
        val l = ArrayList<File>()
        if (song.folder.isNotEmpty()) l.add(File(song.folder, TRASH_DIR))
        File(song.path).parentFile?.let { l.add(File(it, TRASH_DIR)) }
        l.add(File(ctx.filesDir, "trash"))
        return l
    }

    private fun toTrash(song: Song, prefix: String, keepOriginal: Boolean): File? {
        val src = File(song.path)
        for (dir in trashDirs(song)) {
            try {
                if (!dir.isDirectory && !dir.mkdirs()) continue
                try { File(dir, ".nomedia").createNewFile() } catch (e: Exception) { }
                val dest = File(dir, "${prefix}_${src.name}")
                if (dest.exists()) dest.delete()
                if (keepOriginal) { src.copyTo(dest, overwrite = true); return dest }
                if (moveFile(src, dest)) return dest
            } catch (e: Exception) { }
        }
        return null
    }

    /** Soft delete. Returns (found, trashedOk). trashedOk=false: removed from the library only. */
    fun delete(id: String): Pair<Boolean, Boolean> {
        val s = songs[id] ?: return Pair(false, false)
        val snap = s.copySnapshot()
        var trashedOk = true
        if (File(s.path).exists()) {
            val dest = toTrash(s, id, false)
            if (dest != null) snap.trashPath = dest.path else trashedOk = false
        }
        songs.remove(id); db.deleteSongs(listOf(id))
        lastDeleted.add(snap)
        return Pair(true, trashedOk)
    }

    fun undoDelete(): Song? {
        if (lastDeleted.isEmpty()) return null
        val s = lastDeleted.removeAt(lastDeleted.size - 1)
        if (s.trashPath.isNotEmpty() && File(s.trashPath).exists()) {
            try {
                val dest = File(s.path)
                dest.parentFile?.mkdirs()
                moveFile(File(s.trashPath), dest)
            } catch (e: Exception) { }
        }
        s.trashPath = ""
        songs[s.id] = s; save(s)
        return s
    }

    fun emptyTrash(): Int {
        var n = 0
        val dirs = HashSet<File>()
        for (f in folders) dirs.add(File(f, TRASH_DIR))
        songs.values.forEach { s -> File(s.path).parentFile?.let { dirs.add(File(it, TRASH_DIR)) } }
        dirs.add(File(ctx.filesDir, "trash"))
        for (d in dirs) d.listFiles()?.forEach { if (it.name != ".nomedia" && it.delete()) n++ }
        lastDeleted.clear()
        return n
    }

    // -- move / copy ------------------------------------------------------------------------------------------------------
    fun moveFileTo(id: String, destFolder: String): Pair<Boolean, String?> {
        val s = songs[id] ?: return Pair(false, "Unknown song.")
        val src = File(s.path); val dd = File(destFolder)
        if (!dd.isDirectory) return Pair(false, "Destination folder doesn't exist.")
        val dest = File(dd, src.name)
        if (dest.exists()) return Pair(false, "A file named '${src.name}' already exists there.")
        if (!moveFile(src, dest)) return Pair(false, "Move failed (no write access to one of the folders?).")
        s.path = dest.path
        s.folder = folders.firstOrNull { it == dd.path } ?: dd.path
        save(s)
        return Pair(true, null)
    }

    fun copyFileTo(id: String, destFolder: String, cb: (Boolean, String?) -> Unit) {
        val s = songs[id] ?: run { cb(false, "Unknown song."); return }
        val src = File(s.path); val dd = File(destFolder)
        if (!dd.isDirectory) { cb(false, "Destination folder doesn't exist."); return }
        val dest = File(dd, src.name)
        if (dest.exists()) { cb(false, "A file named '${src.name}' already exists there."); return }
        Bg.io {
            val r = try { src.copyTo(dest); Pair(true, null as String?) } catch (e: Exception) { Pair(false, "Copy failed: ${e.message}") }
            Bg.post { cb(r.first, r.second) }
        }
    }

    // -- trim baking ---------------------------------------------------------------------------------------------------------
    fun createTrimmedCopy(id: String, dest: File, cb: (Boolean, String?) -> Unit) {
        val s = songs[id] ?: run { cb(false, "Unknown song."); return }
        if (!s.hasTrim) { cb(false, "No trim points are set for this song yet - drag the trim handles first."); return }
        val stop = if (s.duration > 0 && s.trimEnd > 0) s.duration - s.trimEnd else null
        val src = File(s.path); val start = s.trimStart
        Bg.io { val r = AudioTrim.trim(src, dest, start, stop); Bg.post { cb(r.first, r.second) } }
    }

    /**
     * Bakes the trim into the ORIGINAL file (untrimmed backup goes to trash first). [beforeReplace] runs on the
     * main thread right before the swap so the player can let go of the file.
     */
    fun overwriteWithTrim(id: String, beforeReplace: () -> Unit, cb: (Boolean, String?) -> Unit) {
        val s = songs[id] ?: run { cb(false, "Unknown song."); return }
        if (!s.hasTrim) { cb(false, "No trim points are set for this song yet - drag the trim handles first."); return }
        val snap = s.copySnapshot()
        val src = File(snap.path)
        val stop = if (snap.duration > 0 && snap.trimEnd > 0) snap.duration - snap.trimEnd else null
        Bg.io {
            val backup = toTrash(snap, "${id}_before_trim", true)
            if (backup == null) { Bg.post { cb(false, "Didn't touch the file - couldn't make a safety backup first.") }; return@io }
            val tmp = File(src.parentFile, ".${src.nameWithoutExtension}_trimtmp.${src.extension}")
            val r = AudioTrim.trim(src, tmp, snap.trimStart, stop)
            if (!r.first) { try { backup.delete() } catch (e: Exception) { }; try { tmp.delete() } catch (e: Exception) { }; Bg.post { cb(false, r.second) }; return@io }
            val latch = CountDownLatch(1)
            Bg.post { try { beforeReplace() } finally { latch.countDown() } }
            latch.await(5, TimeUnit.SECONDS)
            var replaced = false
            for (i in 0 until 6) {
                if (tmp.renameTo(src)) { replaced = true; break }
                try { Thread.sleep(250) } catch (e: InterruptedException) { }
            }
            if (!replaced) { try { tmp.delete() } catch (e: Exception) { }; Bg.post { cb(false, "Trim succeeded but couldn't replace the original file (no write access?).") }; return@io }
            val newDur = readDuration(src.path)
            Bg.post {
                songs[id]?.let { sg -> sg.trimStart = 0.0; sg.trimEnd = 0.0; if (newDur > 0) sg.duration = newDur; save(sg) }
                cb(true, "backed up untrimmed original to ${backup.path}")
            }
        }
    }

    // -- playlists ------------------------------------------------------------------------------------------------------------
    fun listPlaylists(): List<Playlist> = playlists.values.sortedBy { it.name.lowercase() }

    fun createPlaylist(name: String): Pair<String?, String?> {
        val n = name.trim()
        if (n.isEmpty()) return Pair(null, "Playlist needs a name.")
        if (playlists.values.any { it.name.equals(n, true) }) return Pair(null, "A playlist with that name already exists.")
        val p = Playlist(UUID.randomUUID().toString().substring(0, 8), n)
        playlists[p.id] = p; db.savePlaylist(p)
        return Pair(p.id, null)
    }

    fun renamePlaylist(id: String, newName: String): Pair<Boolean, String?> {
        val p = playlists[id] ?: return Pair(false, "Unknown playlist.")
        val n = newName.trim()
        if (n.isEmpty()) return Pair(false, "Playlist needs a name.")
        if (playlists.values.any { it.id != id && it.name.equals(n, true) }) return Pair(false, "A playlist with that name already exists.")
        p.name = n; db.savePlaylist(p)
        return Pair(true, null)
    }

    fun deletePlaylist(id: String): Boolean { if (playlists.remove(id) == null) return false; db.deletePlaylist(id); return true }
    fun playlistSongs(id: String): List<Song> = playlists[id]?.songIds?.mapNotNull { songs[it] } ?: emptyList()

    fun addToPlaylist(pid: String, sid: String): Boolean {
        val p = playlists[pid] ?: return false
        if (!songs.containsKey(sid) || sid in p.songIds) return false
        p.songIds.add(sid); db.savePlaylist(p); return true
    }

    fun removeFromPlaylist(pid: String, sid: String): Boolean {
        val p = playlists[pid] ?: return false
        if (!p.songIds.remove(sid)) return false
        db.savePlaylist(p); return true
    }

    fun reorderPlaylistSong(pid: String, sid: String, dir: Int): Boolean {
        val p = playlists[pid] ?: return false
        val i = p.songIds.indexOf(sid); if (i < 0) return false
        val j = i + dir; if (j < 0 || j >= p.songIds.size) return false
        java.util.Collections.swap(p.songIds, i, j); db.savePlaylist(p); return true
    }
}
