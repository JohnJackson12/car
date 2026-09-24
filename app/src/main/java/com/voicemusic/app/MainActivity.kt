package com.voicemusic.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat

/**
 * Single-activity shell: permission onboarding, a section rail (Songs/Albums/Artists/Genres/
 * Folders/Playlists/Favorites/Recent/Most played), a list for whichever section is open, and a
 * persistent now-playing bar. Talks to [PlaybackService] over a bound connection.
 */
class MainActivity : Activity(), PlaybackService.UiListener {

    companion object {
        // Simple static handle so Settings/Effects screens (opened from here, same process) can
        // reach the already-bound service without a second bind/unbind dance.
        var boundService: PlaybackService? = null
        private const val PERM_REQ = 900
    }

    private lateinit var app: App
    private lateinit var library: Library
    private var svc: PlaybackService? = null
    private var bound = false

    // view kinds
    private enum class Kind { SONGS, ALBUMS, ALBUM_DETAIL, ARTISTS, ARTIST_DETAIL, GENRES, GENRE_DETAIL,
        FOLDERS, FOLDER_DETAIL, PLAYLISTS, PLAYLIST_DETAIL, FAVORITES, RECENT, RECENT_PLAYED, MOST_PLAYED, QUEUE }
    private var currentKind = Kind.SONGS
    private var currentArg = ""

    // -- root views --------------------------------------------------------------------------------
    private lateinit var root: LinearLayout
    private lateinit var permissionScreen: LinearLayout
    private lateinit var mainScreen: LinearLayout
    private lateinit var sectionRail: LinearLayout
    private lateinit var listView: ListView
    private lateinit var addPlaylistButton: Button
    private lateinit var searchBox: EditText
    private lateinit var genreFilterSpinner: Spinner
    private var genreFilterValue: String = "All Genres"
    private lateinit var emptyLabel: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var micIndicator: TextView
    private lateinit var lastHeardLabel: TextView

    // now playing bar
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npSeek: SeekBar
    private lateinit var npElapsed: TextView
    private lateinit var npDuration: TextView
    private lateinit var npPlayPause: TextView
    private lateinit var npShuffle: TextView
    private lateinit var npRepeat: TextView
    private lateinit var npHeart: TextView
    private lateinit var npStars: List<TextView>
    private lateinit var npMute: TextView
    private lateinit var npVolume: SeekBar
    private var userDraggingSeek = false

    private val progressHandler = android.os.Handler(mainLooper)
    private val progressTick = object : Runnable {
        override fun run() { refreshProgress(); progressHandler.postDelayed(this, 500) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as PlaybackService.LocalBinder).service()
            boundService = svc
            svc?.uiListener = this@MainActivity
            svc?.voice?.updateSongTitles(library.songs.values.map { it.title })
            bound = true
            refreshList()
            refreshNowPlaying()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null; boundService = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        library = app.library
        applyTheme()
        buildUi()
        setContentView(root)
        checkPermissionsThenStart()
    }

    private fun applyTheme() {
        val p = ThemeCatalog.resolve(app.config.themeName, app.config.accentColor.ifEmpty { null })
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        if (app.config.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------------------------------------------
    // Permissions: mic + storage. Storage uses the plain runtime permission on the standard flavor
    // (targetSdk 29 -> full read/write without All-Files-Access) and is skipped on the .car flavor,
    // which is installed with permissions pre-granted (targetSdk 22) for ROMs with broken permission
    // dialogs.
    // ---------------------------------------------------------------------------------------------------
    private fun neededPermissions(): List<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 23) return emptyList()          // granted at install time pre-Marshmallow
        if (Build.VERSION.SDK_INT <= 29) { list.add(Manifest.permission.READ_EXTERNAL_STORAGE); list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun needsManageStorage(): Boolean = Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && Build.VERSION.SDK_INT > 29

    private fun checkPermissionsThenStart() {
        val missing = neededPermissions()
        if (missing.isNotEmpty()) { showPermissionScreen(missing); return }
        showMainScreen()
        startAndBindService()
    }

    private fun showPermissionScreen(missing: List<String>) {
        permissionScreen.visibility = View.VISIBLE; mainScreen.visibility = View.GONE
        val body = permissionScreen.getChildAt(1) as LinearLayout
        body.removeAllViews()
        body.addView(Ui.textView(this, "A couple of permissions are needed:", 15f, false, Color.LTGRAY))
        if (Manifest.permission.RECORD_AUDIO in missing) body.addView(Ui.textView(this, "\u2022 Microphone - for voice commands", 14f))
        if (Manifest.permission.READ_EXTERNAL_STORAGE in missing || Manifest.permission.WRITE_EXTERNAL_STORAGE in missing) body.addView(Ui.textView(this, "\u2022 Storage - to find and manage your music files", 14f))
        body.addView(Ui.button(this, "Grant permissions") {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQ)
        }.apply { val p = Ui.dp(this@MainActivity, 24); (this as Button).setPadding(p, p/2, p, p/2) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ) checkPermissionsThenStart()
    }

    private fun showMainScreen() {
        permissionScreen.visibility = View.GONE; mainScreen.visibility = View.VISIBLE
        if (needsManageStorage()) {
            Ui.confirm(this, "Full file access", "For a music player to freely add/rename/delete your song files, Android needs \"All files access\". You'll be taken to that setting now.") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { } }
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, PlaybackService::class.java)
        try { ContextCompat.startForegroundService(this, intent) } catch (e: Exception) { CrashLog.note("start service failed", e) }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (library.folders.isEmpty()) {
            Ui.confirm(this, "Add a music folder", "No folders are set up yet. Add one now to start scanning for songs.") {
                startActivity(Intent(this, FolderBrowserActivity::class.java))
            }
        } else rescan()
    }

    private fun rescan() {
        library.scanAsync({ _, _ -> }, { added, pruned, err ->
            Bg.post {
                refreshList()
                setupGenreFilterSpinner()
                if (err != null) Ui.toast(this, "Scan error: $err")
                else if (added > 0 || pruned > 0) Ui.toast(this, "Added $added, removed $pruned")
            }
        })
    }

    override fun onResume() { super.onResume(); progressHandler.post(progressTick) }
    override fun onPause() { super.onPause(); progressHandler.removeCallbacks(progressTick) }
    override fun onDestroy() {
        super.onDestroy()
        if (bound) { svc?.uiListener = null; unbindService(connection); bound = false }
    }

    // ==================================================================================================
    // UI construction
    // ==================================================================================================
    private fun buildUi() {
        root = Ui.col(this)

        // -- permission screen --------------------------------------------------------------------------
        permissionScreen = Ui.col(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(18, 18, 24))
            addView(Ui.textView(this@MainActivity, "Voice Music", 24f, true).apply { setPadding(Ui.dp(this@MainActivity,24), Ui.dp(this@MainActivity,60), Ui.dp(this@MainActivity,24), Ui.dp(this@MainActivity,16)) })
            addView(Ui.col(this@MainActivity).apply { setPadding(Ui.dp(this@MainActivity,24), 0, Ui.dp(this@MainActivity,24), 0) })
        }
        root.addView(permissionScreen, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // -- main screen ----------------------------------------------------------------------------------
        mainScreen = Ui.col(this)
        root.addView(mainScreen, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val topBar = Ui.row(this,
            Ui.textView(this, "Voice Music", 19f, true).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            micIndicatorView(), Ui.iconButton(this, "\u21BB") { rescan() }, Ui.iconButton(this, "\u2699") { startActivity(Intent(this, SettingsActivity::class.java)) })
            .apply { setPadding(Ui.dp(this@MainActivity,12), Ui.dp(this@MainActivity,10), Ui.dp(this@MainActivity,4), Ui.dp(this@MainActivity,6)) }
        mainScreen.addView(topBar)

        lastHeardLabel = Ui.textView(this, "", 11.5f, false, Color.argb(255, 140, 145, 155)).apply {
            setPadding(Ui.dp(this@MainActivity,14), 0, Ui.dp(this@MainActivity,14), Ui.dp(this@MainActivity,4)); visibility = View.GONE
        }
        mainScreen.addView(lastHeardLabel)

        sectionRail = Ui.row(this)
        val railScroll = HorizontalScrollView(this).apply { addView(sectionRail); isHorizontalScrollBarEnabled = false }
        mainScreen.addView(railScroll)
        buildRail()

        breadcrumb = Ui.textView(this, "", 13f, false, Color.argb(255, 170, 175, 185)).apply { setPadding(Ui.dp(this@MainActivity,14), Ui.dp(this@MainActivity,6), Ui.dp(this@MainActivity,14), Ui.dp(this@MainActivity,2)); visibility = View.GONE }
        mainScreen.addView(breadcrumb)

        searchBox = EditText(this).apply {
            hint = "Search title / artist / album / genre / folder\u2026"
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { refreshList() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        genreFilterSpinner = Spinner(this)
        val filterRow = Ui.row(this,
            searchBox.apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            genreFilterSpinner)
            .apply { setPadding(Ui.dp(this@MainActivity,10), 0, Ui.dp(this@MainActivity,10), Ui.dp(this@MainActivity,6)) }
        mainScreen.addView(filterRow)
        setupGenreFilterSpinner()

        listView = ListView(this)
        addPlaylistButton = Ui.button(this, "+ New playlist") {
            Ui.prompt(this, "New playlist", "", "Name") { name -> val (id, err) = library.createPlaylist(name); if (err != null) Ui.toast(this, err) else refreshList() }
        }.apply { visibility = View.GONE }
        emptyLabel = Ui.textView(this, "No songs yet - tap \u2699 to add a folder.", 14f, false, Color.GRAY).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val listOuter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(addPlaylistButton)
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val listContainer = FrameLayout(this).apply { addView(listOuter); addView(emptyLabel) }
        mainScreen.addView(listContainer, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        mainScreen.addView(buildNowPlayingBar())
    }

    private fun micIndicatorView(): TextView {
        micIndicator = Ui.textView(this, "MIC", 12f, true).apply {
            val p = Ui.dp(this@MainActivity, 8); setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { svc?.let { it.setVoiceEnabled(!app.config.voiceEnabled); updateMicIndicator() } }
        }
        updateMicIndicator()
        return micIndicator
    }
    private fun updateMicIndicator() { micIndicator.setTextColor(if (app.config.voiceEnabled) Color.rgb(120, 220, 140) else Color.GRAY) }

    private fun railButton(label: String, kind: Kind): TextView = Ui.textView(this, label, 13.5f, currentKind == kind).apply {
        setPadding(Ui.dp(this@MainActivity,14), Ui.dp(this@MainActivity,8), Ui.dp(this@MainActivity,14), Ui.dp(this@MainActivity,8))
        setTextColor(if (currentKind == kind) Color.rgb(255, 205, 100) else Color.LTGRAY)
        isClickable = true
        setOnClickListener { currentKind = kind; currentArg = ""; buildRail(); refreshList() }
    }

    private fun buildRail() {
        sectionRail.removeAllViews()
        sectionRail.addView(railButton("Songs", Kind.SONGS))
        sectionRail.addView(railButton("Albums", Kind.ALBUMS))
        sectionRail.addView(railButton("Artists", Kind.ARTISTS))
        sectionRail.addView(railButton("Genres", Kind.GENRES))
        sectionRail.addView(railButton("Folders", Kind.FOLDERS))
        sectionRail.addView(railButton("Playlists", Kind.PLAYLISTS))
        sectionRail.addView(railButton("Queue", Kind.QUEUE))
        sectionRail.addView(railButton("Favorites", Kind.FAVORITES))
        sectionRail.addView(railButton("Recent added", Kind.RECENT))
        sectionRail.addView(railButton("Recent played", Kind.RECENT_PLAYED))
        sectionRail.addView(railButton("Most played", Kind.MOST_PLAYED))
    }

    private fun buildNowPlayingBar(): LinearLayout {
        npArt = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 46), Ui.dp(this@MainActivity, 46)) }
        npTitle = Ui.textView(this, "Nothing playing", 14.5f, true)
        npArtist = Ui.textView(this, "", 12.5f, false, Color.GRAY)
        npHeart = Ui.iconButton(this, "\u2661", 20f) { svc?.engine?.currentSong()?.let { library.toggleFavorite(it.id); refreshNowPlaying() } }
        val textCol = Ui.col(this, npTitle, npArtist).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(Ui.dp(this@MainActivity,10),0,Ui.dp(this@MainActivity,10),0) }
        // Plain ASCII transport glyphs on purpose: pictographic emoji (shuffle/repeat/mic icons) can
        // silently fail to render on stripped-down car ROMs missing a color emoji font - your own
        // feedback.py has a comment about hitting exactly this problem on Windows. Text always renders.
        npShuffle = Ui.iconButton(this, "SHUF", 12f) { svc?.engine?.let { it.setShuffle(!it.shuffleEnabled); refreshNowPlaying() } }
        npRepeat = Ui.iconButton(this, "REPEAT", 11f) { svc?.engine?.cycleRepeat(); refreshNowPlaying() }
        val prev = Ui.iconButton(this, "<<", 20f) { svc?.engine?.previous() }
        npPlayPause = Ui.iconButton(this, ">", 22f) { togglePlayPause() }
        val next = Ui.iconButton(this, ">>", 20f) { svc?.engine?.next() }
        val menu = Ui.iconButton(this, "\u22EE", 20f) { showSongMenu(svc?.engine?.currentSong() ?: return@iconButton) }
        val topRow = Ui.row(this, npArt, textCol, npHeart, npShuffle, prev, npPlayPause, next, npRepeat, menu)
            .apply { setPadding(Ui.dp(this@MainActivity,8), Ui.dp(this@MainActivity,6), Ui.dp(this@MainActivity,4), 0) }

        // One-tap rating, same as the desktop app's Now Playing card (5 tappable stars, no dialog).
        npStars = (1..5).map { n ->
            Ui.iconButton(this, "\u2606", 20f) {
                val s = svc?.engine?.currentSong() ?: return@iconButton
                library.setApproved(s.id, true)
                FeedbackSounds.playRating(this, App.instance.config.feedbackSound, n, App.instance.config.feedbackVolume)
                library.setRating(s.id, n) { _, _ -> Bg.post { refreshNowPlaying() } }
            }
        }
        val starsRow = Ui.row(this, *npStars.toTypedArray()).apply { setPadding(Ui.dp(this@MainActivity,4), 0, 0, 0) }

        npElapsed = Ui.textView(this, "0:00", 11f, false, Color.GRAY)
        npDuration = Ui.textView(this, "0:00", 11f, false, Color.GRAY)
        npSeek = SeekBar(this).apply { max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) { userDraggingSeek = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userDraggingSeek = false
                    val engine = svc?.engine ?: return
                    val dur = engine.duration() ?: return
                    engine.seek(dur * (sb?.progress ?: 0) / 1000.0)
                }
            })
        }
        val seekRow = Ui.row(this, npElapsed, npSeek.apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, npDuration)
            .apply { setPadding(Ui.dp(this@MainActivity,10), 0, Ui.dp(this@MainActivity,10), 0) }

        npMute = Ui.iconButton(this, "VOL", 12f) { toggleMute() }
        npVolume = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { svc?.engine?.setVolume(p / 100.0); if (p > 0) preMuteVolume = -1.0 } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val volumeRow = Ui.row(this, npMute, npVolume.apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            .apply { setPadding(Ui.dp(this@MainActivity,10), 0, Ui.dp(this@MainActivity,10), Ui.dp(this@MainActivity,4)) }

        return Ui.col(this, Ui.divider(this), topRow, starsRow, seekRow, volumeRow).apply { setBackgroundColor(Color.rgb(24, 24, 30)) }
    }

    private var preMuteVolume = -1.0
    private fun toggleMute() {
        val e = svc?.engine ?: return
        if (preMuteVolume >= 0) { e.setVolume(preMuteVolume); preMuteVolume = -1.0 }
        else { preMuteVolume = e.getVolume(); e.setVolume(0.0) }
        refreshNowPlaying()
    }

    private fun togglePlayPause() {
        val e = svc?.engine ?: return
        if (e.isPaused()) svc?.requestFocusAndPlay() else e.pause()
    }

    // ==================================================================================================
    // Data / list refresh
    // ==================================================================================================
    private fun setupGenreFilterSpinner() {
        val genres = listOf("All Genres") + library.genres()
        genreFilterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genres)
        val idx = genres.indexOf(genreFilterValue).coerceAtLeast(0)
        genreFilterSpinner.setSelection(idx)
        genreFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { genreFilterValue = genres[pos]; refreshList() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun matchesSearch(s: Song, q: String): Boolean =
        q.isEmpty() || s.searchKey().contains(q, ignoreCase = true)

    private fun applyFilters(songs: List<Song>): List<Song> {
        val q = searchBox.text.toString().trim()
        return songs.filter { (genreFilterValue == "All Genres" || it.genre == genreFilterValue) && matchesSearch(it, q) }
    }

    private fun searchFiltersActiveList(): Boolean = currentKind !in listOf(Kind.ALBUMS, Kind.ARTISTS, Kind.GENRES, Kind.FOLDERS, Kind.PLAYLISTS)

    private fun updateFilterRowVisibility() {
        val visible = searchFiltersActiveList()
        searchBox.visibility = if (visible) View.VISIBLE else View.GONE
        genreFilterSpinner.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun currentSongList(): List<Song> = when (currentKind) {
        Kind.SONGS -> library.allSongsSorted()
        Kind.ALBUM_DETAIL -> library.songsInAlbum(currentArg)
        Kind.ARTIST_DETAIL -> library.songsByArtist(currentArg)
        Kind.GENRE_DETAIL -> library.songsInGenre(currentArg)
        Kind.FOLDER_DETAIL -> library.songsInFolder(currentArg)
        Kind.PLAYLIST_DETAIL -> library.playlistSongs(currentArg)
        Kind.FAVORITES -> library.favorites()
        Kind.RECENT -> library.recentlyAdded()
        Kind.RECENT_PLAYED -> library.recentlyPlayed()
        Kind.MOST_PLAYED -> library.mostPlayed()
        Kind.QUEUE -> svc?.engine?.queue?.mapNotNull { library.get(it) } ?: emptyList()
        else -> emptyList()
    }

    private fun refreshList() {
        breadcrumb.visibility = View.GONE
        addPlaylistButton.visibility = View.GONE
        updateFilterRowVisibility()
        when (currentKind) {
            Kind.ALBUMS -> showGroups(library.albums()) { currentKind = Kind.ALBUM_DETAIL; currentArg = it; refreshList() }
            Kind.ARTISTS -> showGroups(library.artists()) { currentKind = Kind.ARTIST_DETAIL; currentArg = it; refreshList() }
            Kind.GENRES -> showGroups(library.genreGroups()) { currentKind = Kind.GENRE_DETAIL; currentArg = it; refreshList() }
            Kind.FOLDERS -> showGroups(library.foldersGrouped()) { currentKind = Kind.FOLDER_DETAIL; currentArg = it; refreshList() }
            Kind.PLAYLISTS -> showPlaylists()
            else -> showSongs()
        }
        if (currentKind in listOf(Kind.ALBUM_DETAIL, Kind.ARTIST_DETAIL, Kind.GENRE_DETAIL, Kind.FOLDER_DETAIL, Kind.PLAYLIST_DETAIL)) {
            breadcrumb.visibility = View.VISIBLE
            val label = if (currentKind == Kind.PLAYLIST_DETAIL) library.playlists[currentArg]?.name ?: "" else currentArg
            breadcrumb.text = "\u2039 Back    $label"
            breadcrumb.setOnClickListener {
                currentKind = when (currentKind) { Kind.ALBUM_DETAIL -> Kind.ALBUMS; Kind.ARTIST_DETAIL -> Kind.ARTISTS
                    Kind.GENRE_DETAIL -> Kind.GENRES; Kind.FOLDER_DETAIL -> Kind.FOLDERS; else -> Kind.PLAYLISTS }
                refreshList()
            }
        }
    }

    private fun showGroups(groups: List<Group>, onClick: (String) -> Unit) {
        listView.choiceMode = ListView.CHOICE_MODE_NONE
        emptyLabel.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        val adapter = GroupRowAdapter(this, groups)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, i, _ -> onClick(groups[i].name) }
        listView.setOnItemLongClickListener(null)
    }

    private fun showPlaylists() {
        listView.choiceMode = ListView.CHOICE_MODE_NONE
        addPlaylistButton.visibility = View.VISIBLE
        val pls = library.listPlaylists()
        emptyLabel.visibility = View.GONE
        val adapter = PlaylistRowAdapter(this, pls)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, i, _ -> currentKind = Kind.PLAYLIST_DETAIL; currentArg = pls[i].id; refreshList() }
        listView.setOnItemLongClickListener { _, _, i, _ -> showPlaylistMenu(pls[i]); true }
    }

    private fun showSongs() {
        val songs = applyFilters(currentSongList())
        emptyLabel.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        emptyLabel.text = when {
            currentKind == Kind.SONGS && library.folders.isEmpty() -> "No folders added yet - tap \u2699 to add one."
            currentKind == Kind.QUEUE -> "Nothing queued - play a song from any list first."
            else -> "No songs here."
        }
        val adapter = SongRowAdapter(this, songs) { svc?.engine?.currentSong()?.id }
        listView.adapter = adapter
        if (currentKind == Kind.QUEUE) {
            listView.setOnItemClickListener { _, _, i, _ -> svc?.engine?.playIndex(i); svc?.requestFocusAndPlay() }
            listView.setOnItemLongClickListener { _, _, i, _ -> showQueueItemMenu(songs[i]); true }
            listView.choiceMode = ListView.CHOICE_MODE_NONE
        } else {
            listView.setOnItemClickListener { _, _, i, _ -> if (listView.checkedItemCount == 0) playFromList(songs, i) }
            listView.setOnItemLongClickListener { _, _, i, _ -> showSongMenu(songs[i]); true }
            enableMultiSelect(songs)
        }
    }

    /** Long-press-to-select, then a contextual action bar for batch actions - same idea as the PC
     * app's Ctrl/Shift-click multi-select, using Android's standard selection UI instead. */
    private fun enableMultiSelect(songs: List<Song>) {
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        listView.setMultiChoiceModeListener(object : android.widget.AbsListView.MultiChoiceModeListener {
            override fun onItemCheckedStateChanged(mode: android.view.ActionMode, position: Int, id: Long, checked: Boolean) {
                mode.title = "${listView.checkedItemCount} selected"
            }
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, 1, 0, "Rate")
                menu.add(0, 2, 1, "Favorite")
                menu.add(0, 6, 2, "Unfavorite")
                menu.add(0, 5, 3, "Set genre")
                menu.add(0, 3, 4, "Add to playlist")
                menu.add(0, 4, 5, "Delete")
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val chosen = songs.filterIndexed { i, _ -> listView.checkedItemPositions.get(i) }
                if (chosen.isEmpty()) return true
                when (item.itemId) {
                    1 -> showBatchRateDialog(chosen) { mode.finish() }
                    2 -> { for (s in chosen) library.setFavorite(s.id, true); refreshList(); mode.finish() }
                    6 -> { for (s in chosen) library.setFavorite(s.id, false); refreshList(); mode.finish() }
                    5 -> { showSetGenreDialog(chosen); mode.finish() }
                    3 -> showBatchAddToPlaylistDialog(chosen) { mode.finish() }
                    4 -> Ui.confirm(this@MainActivity, "Delete ${chosen.size} song(s)?", "They'll move to a trash folder on disk.", "Delete") { batchDelete(chosen); mode.finish() }
                }
                return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        })
    }

    private fun showBatchRateDialog(songs: List<Song>, onDone: () -> Unit) {
        val max = app.config.maxRating
        val labels = (0..max).map { if (it == 0) "Clear rating" else Ui.stars(it, max) }
        android.app.AlertDialog.Builder(this).setTitle("Rate ${songs.size} song(s)").setItems(labels.toTypedArray()) { _, which ->
            for (s in songs) library.setRating(s.id, which)
            refreshList(); onDone()
        }.setOnCancelListener { onDone() }.show()
    }

    private fun showBatchAddToPlaylistDialog(songs: List<Song>, onDone: () -> Unit) {
        val pls = library.listPlaylists()
        val names = pls.map { it.name }.toMutableList().apply { add("+ New playlist\u2026") }
        android.app.AlertDialog.Builder(this).setTitle("Add ${songs.size} song(s) to playlist").setItems(names.toTypedArray()) { _, which ->
            if (which == pls.size) Ui.prompt(this, "New playlist", "", "Name") { name ->
                val (id, err) = library.createPlaylist(name)
                if (id != null) { for (s in songs) library.addToPlaylist(id, s.id) } else Ui.toast(this, err ?: "Failed")
                onDone()
            } else { for (s in songs) library.addToPlaylist(pls[which].id, s.id); onDone() }
        }.setOnCancelListener { onDone() }.show()
    }

    private fun batchDelete(songs: List<Song>) {
        val e = svc?.engine
        val currentId = e?.currentSong()?.id
        if (currentId != null && songs.any { it.id == currentId }) { if (e!!.queue.size > 1) e.next() else e.stopAndRelease() }
        for (s in songs) { library.delete(s.id); e?.queueRemove(s.id) }
        refreshList()
    }

    private fun showQueueItemMenu(song: Song) {
        android.app.AlertDialog.Builder(this).setTitle(song.title).setItems(arrayOf("Play now", "Move up", "Move down", "Remove from queue", "More\u2026")) { _, which ->
            val e = svc?.engine ?: return@setItems
            when (which) {
                0 -> { e.playIndex(e.queue.indexOf(song.id)); svc?.requestFocusAndPlay() }
                1 -> { e.queueMove(song.id, -1); refreshList() }
                2 -> { e.queueMove(song.id, 1); refreshList() }
                3 -> { e.queueRemove(song.id); refreshList() }
                4 -> showSongMenu(song)
            }
        }.show()
    }

    private fun playFromList(list: List<Song>, index: Int) {
        val e = svc?.engine ?: return
        e.loadQueue(list.map { it.id })
        e.playIndex(index)
        svc?.requestFocusAndPlay()
    }

    // ==================================================================================================
    // Song context menu (rate / favorite / approve / trim / edit / playlist / move / copy / delete)
    // ==================================================================================================
    private fun showSongMenu(song: Song) {
        val inPlaylist = currentKind == Kind.PLAYLIST_DETAIL
        val items = mutableListOf(
            "Play", "\u2605 Rate", if (song.favorite) "\u2665 Remove favorite" else "\u2661 Add favorite",
            if (song.approved) "Un-approve" else "Approve", "Set genre", "Edit tags", "Trim", "Add to playlist")
        if (inPlaylist) { items.add("Move up in playlist"); items.add("Move down in playlist"); items.add("Remove from this playlist") }
        items.addAll(listOf("Move to folder", "Copy to folder", "Song info", "Delete"))
        android.app.AlertDialog.Builder(this).setTitle(song.title).setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                "Play" -> { val list = applyFilters(currentSongList()); val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0); playFromList(list.ifEmpty { listOf(song) }, idx) }
                "\u2605 Rate" -> showRateDialog(song)
                "\u2665 Remove favorite", "\u2661 Add favorite" -> { library.toggleFavorite(song.id); refreshList() }
                "Approve", "Un-approve" -> { library.setApproved(song.id, !song.approved); refreshList() }
                "Set genre" -> showSetGenreDialog(listOf(song))
                "Edit tags" -> showEditTagsDialog(song)
                "Trim" -> showTrimDialog(song)
                "Add to playlist" -> showAddToPlaylistDialog(song)
                "Move up in playlist" -> { library.reorderPlaylistSong(currentArg, song.id, -1); refreshList() }
                "Move down in playlist" -> { library.reorderPlaylistSong(currentArg, song.id, 1); refreshList() }
                "Remove from this playlist" -> { library.removeFromPlaylist(currentArg, song.id); refreshList() }
                "Move to folder" -> showMoveCopyDialog(song, move = true)
                "Copy to folder" -> showMoveCopyDialog(song, move = false)
                "Song info" -> showSongInfo(song)
                "Delete" -> confirmDelete(song)
            }
        }.show()
    }

    private fun showSetGenreDialog(songs: List<Song>) {
        val genres = library.genres()
        val ctx = this
        val custom = EditText(ctx).apply { hint = "New genre" }
        val existingList = genres.toTypedArray()
        val builder = android.app.AlertDialog.Builder(ctx).setTitle(if (songs.size == 1) "Set genre" else "Set genre for ${songs.size} songs")
        if (existingList.isEmpty()) {
            builder.setView(Ui.col(ctx, custom).apply { val p = Ui.dp(ctx, 20); setPadding(p, p, p, p) })
                .setPositiveButton("Set") { _, _ -> for (s in songs) library.updateTagsAndFilename(s.id, genre = custom.text.toString().trim()) { _, _ -> Bg.post { refreshList() } } }
                .setNegativeButton("Cancel", null).show()
        } else {
            val options = existingList.toMutableList().apply { add("+ New genre\u2026") }
            builder.setItems(options.toTypedArray()) { _, which ->
                if (which == existingList.size) {
                    Ui.prompt(ctx, "New genre", "") { g -> for (s in songs) library.updateTagsAndFilename(s.id, genre = g.trim()) { _, _ -> Bg.post { refreshList() } } }
                } else for (s in songs) library.updateTagsAndFilename(s.id, genre = options[which]) { _, _ -> Bg.post { refreshList() } }
            }.show()
        }
    }

    private fun showRateDialog(song: Song) {
        val max = app.config.maxRating
        val labels = (0..max).map { if (it == 0) "Clear rating" else Ui.stars(it, max) }
        android.app.AlertDialog.Builder(this).setTitle("Rate \u2013 ${song.title}").setItems(labels.toTypedArray()) { _, which ->
            library.setRating(song.id, which) { ok, msg -> if (!ok && msg != null) Bg.post { Ui.toast(this, msg) } }
            refreshList()
        }.show()
    }

    private fun sanitizeFilename(name: String): String = name.replace(Regex("[<>:\"/\\\\|?*]"), "").trim().replace(Regex("\\s+"), " ")

    private fun showEditTagsDialog(song: Song) {
        val ctx = this
        val file = java.io.File(song.path)
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        fun field(label: String, value: String): Pair<TextView, EditText> {
            val et = EditText(ctx).apply { setText(value) }
            return Pair(Ui.textView(ctx, label, 12f, false, Color.GRAY), et)
        }
        val (l1, title) = field("Title", song.title); val (l2, artist) = field("Artist", song.artist)
        val (l3, album) = field("Album", song.album); val (l4, genre) = field("Genre", song.genre)
        val (l5, filenameField) = field("Filename (without extension)", file.nameWithoutExtension)
        fun applyTemplate(tmpl: String) {
            val g = genre.text.toString().trim().ifEmpty { "Unknown Genre" }
            val a = artist.text.toString().trim().ifEmpty { "Unknown Artist" }
            val t = title.text.toString().trim().ifEmpty { file.nameWithoutExtension }
            filenameField.setText(sanitizeFilename(tmpl.replace("{genre}", g).replace("{artist}", a).replace("{title}", t)))
        }
        val templateRow = Ui.row(this,
            Ui.textView(this, "Quick fill: ", 12f, false, Color.GRAY),
            Ui.button(this, "Genre - Artist - Title") { applyTemplate("{genre} - {artist} - {title}") },
            Ui.button(this, "Artist - Title") { applyTemplate("{artist} - {title}") })
        val body = Ui.col(this, l1, title, l2, artist, l3, album, l4, genre, l5,
            Ui.row(this, filenameField.apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, Ui.textView(this, ext, 14f, false, Color.GRAY)),
            templateRow,
            Ui.textView(this, "Renaming updates the file on disk immediately when you save.", 11.5f, false, Color.GRAY))
            .apply { val p = Ui.dp(ctx,20); setPadding(p,p,p,p) }
        android.app.AlertDialog.Builder(this).setTitle("Edit tags & filename").setView(Ui.scroll(this, body))
            .setPositiveButton("Save") { _, _ ->
                val newName = sanitizeFilename(filenameField.text.toString())
                if (newName.isEmpty()) { Ui.toast(this, "Filename can't be empty."); return@setPositiveButton }
                library.updateTagsAndFilename(song.id, title.text.toString(), artist.text.toString(), album.text.toString(),
                    genre.text.toString(), newName + ext) { ok, msg ->
                    Bg.post { refreshList(); if (msg != null) Ui.toast(this, msg) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTrimDialog(song: Song) {
        val ctx = this
        val dur = song.duration.takeIf { it > 0 } ?: 300.0
        val startLabel = Ui.textView(ctx, "Start: ${fmtTime(song.trimStart)}", 13f)
        val endLabel = Ui.textView(ctx, "Cut from end: ${fmtTime(song.trimEnd)}", 13f)
        val startBar = SeekBar(ctx).apply { max = 1000; progress = ((song.trimStart / dur) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { startLabel.text = "Start: ${fmtTime(dur * p / 1000.0)}" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val endBar = SeekBar(ctx).apply { max = 1000; progress = ((song.trimEnd / dur) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { endLabel.text = "Cut from end: ${fmtTime(dur * p / 1000.0)}" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val body = Ui.col(this, startLabel, startBar, endLabel, endBar,
            Ui.textView(this, "Trim points apply instantly during playback. Use \"Bake trim\" below to physically shorten the file.", 11.5f, false, Color.GRAY))
            .apply { val p = Ui.dp(ctx,20); setPadding(p,p,p,p) }
        android.app.AlertDialog.Builder(this).setTitle("Trim \u2013 ${song.title}").setView(body)
            .setPositiveButton("Apply") { _, _ ->
                library.setTrimStart(song.id, dur * startBar.progress / 1000.0)
                library.setTrimEnd(song.id, dur * endBar.progress / 1000.0)
                svc?.engine?.let { if (it.currentSong()?.id == song.id) it.seek(it.elapsed()) }
                refreshList()
            }
            .setNeutralButton("Bake trim (overwrite file)") { _, _ ->
                library.setTrimStart(song.id, dur * startBar.progress / 1000.0)
                library.setTrimEnd(song.id, dur * endBar.progress / 1000.0)
                Ui.confirm(this, "Bake trim into file?", "The untrimmed original is backed up first. This can't be easily undone.") {
                    val e = svc?.engine
                    val wasCurrent = e?.currentSong()?.id == song.id
                    val wasPlaying = wasCurrent && e?.isPaused() == false
                    library.overwriteWithTrim(song.id, beforeReplace = { if (wasCurrent) e?.releaseFileForTrim(song.id) }) { ok, msg ->
                        Bg.post {
                            refreshList()
                            if (ok && wasCurrent) e?.reloadCurrentAfterFileReplaced(!wasPlaying)
                            Ui.toast(this, msg ?: if (ok) "Done" else "Failed")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val pls = library.listPlaylists()
        val names = pls.map { it.name }.toMutableList().apply { add("+ New playlist\u2026") }
        android.app.AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names.toTypedArray()) { _, which ->
            if (which == pls.size) Ui.prompt(this, "New playlist", "", "Name") { name ->
                val (id, err) = library.createPlaylist(name)
                if (id != null) library.addToPlaylist(id, song.id) else Ui.toast(this, err ?: "Failed")
            } else library.addToPlaylist(pls[which].id, song.id).also { if (!it) Ui.toast(this, "Already in that playlist.") }
        }.show()
    }

    private fun showPlaylistMenu(p: Playlist) {
        android.app.AlertDialog.Builder(this).setTitle(p.name).setItems(arrayOf("Play", "Rename", "Delete")) { _, which ->
            when (which) {
                0 -> playFromList(library.playlistSongs(p.id), 0)
                1 -> Ui.prompt(this, "Rename playlist", p.name) { n -> library.renamePlaylist(p.id, n); refreshList() }
                2 -> Ui.confirm(this, "Delete playlist?", "\"${p.name}\" will be removed. Songs themselves aren't touched.") { library.deletePlaylist(p.id); refreshList() }
            }
        }.show()
    }

    private fun showMoveCopyDialog(song: Song, move: Boolean) {
        val folders = library.listFolders().map { it.path }
        if (folders.isEmpty()) { Ui.toast(this, "Add a library folder first."); return }
        android.app.AlertDialog.Builder(this).setTitle(if (move) "Move to\u2026" else "Copy to\u2026").setItems(folders.toTypedArray()) { _, which ->
            if (move) { val (ok, msg) = library.moveFileTo(song.id, folders[which]); if (!ok) Ui.toast(this, msg ?: "Failed"); refreshList() }
            else library.copyFileTo(song.id, folders[which]) { ok, msg -> Bg.post { Ui.toast(this, msg ?: if (ok) "Copied" else "Failed") } }
        }.show()
    }

    private fun showSongInfo(song: Song) {
        val info = TagIO.readAudioInfo(song.path)
        val text = "File: ${song.path}\nFormat: ${info.format}\nBitrate: ${info.bitrateKbps?.let { "$it kbps" } ?: "?"}\n" +
            "Sample rate: ${info.sampleRate?.let { "$it Hz" } ?: "?"}\nChannels: ${info.channels ?: "?"}\n" +
            "Size: ${info.sizeBytes / 1024} KB\nPlay count: ${song.playCount}\n" +
            "Rating tag saved to file: ${if (song.ratingTagSynced) "yes" else "no (in-app only)"}"
        android.app.AlertDialog.Builder(this).setTitle("Song info").setMessage(text).setPositiveButton("Close", null).show()
    }

    private fun confirmDelete(song: Song) {
        val doDelete = {
            val e = svc?.engine
            val (found, trashedOk) = if (e?.currentSong()?.id == song.id) e.deleteCurrentSong(library) else library.delete(song.id)
            refreshList()
            Ui.toast(this, if (!trashedOk) "Removed from library (couldn't move the file to trash)" else "Deleted \u2013 use \u2699 Settings \u2192 Diagnostics if you need to check what happened, or say \"undo\"")
        }
        if (app.config.confirmListDelete) {
            Ui.confirm(this, "Delete song?", "\"${song.title}\" moves to a trash folder on disk (undoable with the \"undo\" voice command or from here immediately after).", "Delete") { doDelete() }
        } else doDelete()
    }

    // ==================================================================================================
    // PlaybackService.UiListener callbacks
    // ==================================================================================================
    override fun onSongChanged(song: Song?) { Bg.post { refreshNowPlaying(); (listView.adapter as? SongRowAdapter)?.notifyDataSetChanged() } }
    override fun onPlaybackStateChanged() { Bg.post { refreshNowPlaying() } }
    override fun onIssue(message: String) { Bg.post { Ui.toast(this, message) } }
    override fun onVoiceEvent(kind: String, text: String) {
        Bg.post {
            when (kind) {
                "raw" -> { lastHeardLabel.text = "Heard: \u201C$text\u201D"; lastHeardLabel.visibility = View.VISIBLE }
                "error" -> updateMicIndicator()
                "listen_start" -> micIndicator.setTextColor(Color.rgb(255, 200, 80))
                "listen_end" -> updateMicIndicator()
            }
        }
    }

    private var lastArtPath: String? = null
    private fun refreshNowPlaying() {
        val e = svc?.engine ?: return
        val song = e.currentSong()
        npTitle.text = song?.title ?: "Nothing playing"
        npArtist.text = song?.artist ?: ""
        npPlayPause.text = if (e.isPaused()) ">" else "||"
        npShuffle.setTextColor(if (e.shuffleEnabled) Color.rgb(255, 205, 100) else Color.WHITE)
        npRepeat.text = when (e.repeatMode) { "one" -> "REPEAT 1"; "all" -> "REPEAT"; else -> "REPEAT" }
        npRepeat.setTextColor(if (e.repeatMode == "off") Color.WHITE else Color.rgb(255, 205, 100))
        npHeart.text = if (song?.favorite == true) "\u2665" else "\u2661"
        npHeart.setTextColor(if (song?.favorite == true) Color.rgb(230, 90, 90) else Color.WHITE)
        val rating = song?.rating ?: 0
        for ((i, star) in npStars.withIndex()) {
            star.text = if (i < rating) "\u2605" else "\u2606"
            star.setTextColor(if (i < rating) Color.rgb(224, 169, 75) else Color.GRAY)
        }
        val vol = e.getVolume()
        npMute.text = if (vol <= 0.001) "MUTE" else "VOL"
        npMute.setTextColor(if (vol <= 0.001) Color.rgb(230, 90, 90) else Color.WHITE)
        if (!npVolume.isPressed) npVolume.progress = (vol * 100).toInt().coerceIn(0, 100)
        if (song?.path != lastArtPath) {
            lastArtPath = song?.path
            Bg.io {
                val art = song?.let { TagIO.readAlbumArt(it.path, 200) }
                Bg.post { npArt.setImageDrawable(if (art != null) BitmapDrawable(resources, art) else null) }
            }
        }
        refreshProgress()
    }

    private fun refreshProgress() {
        val e = svc?.engine ?: return
        val dur = e.duration()
        npDuration.text = if (dur != null) fmtTime(dur) else "0:00"
        npElapsed.text = fmtTime(e.elapsed())
        if (!userDraggingSeek) npSeek.progress = if (dur != null && dur > 0) ((e.elapsed() / dur) * 1000).toInt().coerceIn(0, 1000) else 0
    }
}
