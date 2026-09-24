package com.voicemusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service: owns the PlayerEngine and VoiceEngine so playback and listening keep running
 * with the screen off / app backgrounded, exposes standard transport controls (MediaSessionCompat +
 * a MediaStyle notification) so Bluetooth/AVRCP steering-wheel buttons on a head unit work the same
 * way they do for any other Android music app, and reacts to audio-focus changes (a phone call,
 * nav prompt, or another app's audio) by ducking or pausing like a normal media app should.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTI_ID = 1
        const val ACTION_PLAY_PAUSE = "com.voicemusic.app.PLAY_PAUSE"
        const val ACTION_NEXT = "com.voicemusic.app.NEXT"
        const val ACTION_PREV = "com.voicemusic.app.PREV"
        const val ACTION_STOP = "com.voicemusic.app.STOP"
        const val ACTION_TOGGLE_VOICE = "com.voicemusic.app.TOGGLE_VOICE"
    }

    inner class LocalBinder : Binder() { fun service(): PlaybackService = this@PlaybackService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    lateinit var engine: PlayerEngine
        private set
    lateinit var voice: VoiceEngine
        private set
    private lateinit var app: App
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    var uiListener: UiListener? = null

    interface UiListener {
        fun onSongChanged(song: Song?) {}
        fun onPlaybackStateChanged() {}
        fun onIssue(message: String) {}
        fun onVoiceEvent(kind: String, text: String = "") {}   // kind: listen_start|listen_end|command|error|ready
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { }   // Media3 already pauses on becoming-noisy
    }

    override fun onCreate() {
        super.onCreate()
        app = application as App
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        engine = PlayerEngine(this, app.library, app.config)
        voice = VoiceEngine(this, app.config)

        engine.listener = object : PlayerEngine.Listener {
            override fun onSongChanged(song: Song?) { updateNotification(song); Bg.post { uiListener?.onSongChanged(song) } }
            override fun onPlaybackStateChanged() { updateSessionState(); updateNotification(engine.currentSong()); Bg.post { uiListener?.onPlaybackStateChanged() } }
            override fun onIssue(message: String) { Bg.post { uiListener?.onIssue(message) } }
        }
        voice.listener = object : VoiceEngine.Listener {
            override fun onListenStart() { engine.duck(app.config.duckVolume); Bg.post { uiListener?.onVoiceEvent("listen_start") } }
            override fun onListenEnd() { engine.restoreVolume(); Bg.post { uiListener?.onVoiceEvent("listen_end") } }
            override fun onCommand(cmd: VoiceCommand) { VoiceCommandRouter.dispatch(this@PlaybackService, cmd); Bg.post { uiListener?.onVoiceEvent("command", cmd.kind) } }
            override fun onRawText(text: String) { Bg.post { uiListener?.onVoiceEvent("raw", text) } }
            override fun onError(message: String) { Bg.post { uiListener?.onVoiceEvent("error", message); uiListener?.onIssue(message) } }
            override fun onReady() { Bg.post { uiListener?.onVoiceEvent("ready") } }
        }

        session = MediaSessionCompat(this, "VoiceMusic").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { engine.play() }
                override fun onPause() { engine.pause() }
                override fun onStop() { engine.pause() }
                override fun onSkipToNext() { engine.next() }
                override fun onSkipToPrevious() { engine.previous() }
                override fun onSeekTo(pos: Long) { engine.seek(pos / 1000.0) }
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (ev.action != KeyEvent.ACTION_DOWN) return true
                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (engine.isPaused()) engine.play() else engine.pause()
                        KeyEvent.KEYCODE_MEDIA_PLAY -> engine.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> engine.pause()
                        KeyEvent.KEYCODE_MEDIA_NEXT -> engine.next()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> engine.previous()
                        KeyEvent.KEYCODE_MEDIA_STOP -> engine.pause()
                        else -> return false
                    }
                    return true
                }
            })
            setActive(true)
        }

        createChannel()
        SpokenFeedback.init(this)
        val (id, pos) = Pair(app.config.resumeSongId, app.config.resumePositionSec)
        if (id.isNotEmpty() && app.library.get(id) != null) engine.cueSongId(id, pos, true)
        if (app.config.voiceEnabled) voice.start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, action.hashCode(), i, flags)
        else PendingIntent.getService(this, action.hashCode(), i, flags)
    }

    private fun updateSessionState() {
        val state = if (engine.isActuallyPlaying()) PlaybackStateCompat.STATE_PLAYING
            else if (engine.isPaused()) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_STOPPED
        val ps = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP)
            .setState(state, (engine.elapsed() * 1000).toLong(), engine.getRate().toFloat())
            .build()
        session.setPlaybackState(ps)
    }

    private fun updateNotification(song: Song?) {
        updateSessionState()
        val art: Bitmap? = try { song?.let { TagIO.readAlbumArt(it.path, 300) } } catch (e: Throwable) { null }
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song?.title ?: getString(R.string.app_name))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song?.artist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song?.album ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ((song?.duration ?: 0.0) * 1000).toLong())
            .apply { if (art != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art) }
            .build())

        val playing = engine.isActuallyPlaying()
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.artist ?: "Nothing playing")
            .setSubText(if (app.config.voiceEnabled) "Say \"${app.config.wakeWord}\" for voice commands" else null)
            .setLargeIcon(art)
            .setContentIntent(openIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause", actionIntent(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTI_ID, n)
    }

    // -- audio focus: duck/pause for calls, nav prompts, other apps' audio -----------------------------------
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; engine.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocusGain = engine.isActuallyPlaying(); engine.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.duck(0.3)
            AudioManager.AUDIOFOCUS_GAIN -> { engine.restoreVolume(); if (resumeOnFocusGain) { resumeOnFocusGain = false; engine.play() } }
        }
    }

    fun requestFocusAndPlay() {
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(false).build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (granted) engine.play() else Bg.post { uiListener?.onIssue("Couldn't get audio focus (something else may be playing).") }
    }

    fun setVoiceEnabled(on: Boolean) {
        app.config.voiceEnabled = on
        if (on) voice.start() else voice.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (engine.isPaused()) requestFocusAndPlay() else engine.pause()
            ACTION_NEXT -> engine.next()
            ACTION_PREV -> engine.previous()
            ACTION_STOP -> { engine.pause(); stopForegroundCompat() }
            ACTION_TOGGLE_VOICE -> setVoiceEnabled(!app.config.voiceEnabled)
            Intent.ACTION_MEDIA_BUTTON -> session.controller.dispatchMediaButtonEvent(intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return START_NOT_STICKY)
        }
        return START_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
    }

    override fun onDestroy() {
        val (id, pos) = engine.resumeState()
        app.config.resumeSongId = id ?: ""
        app.config.resumePositionSec = pos
        app.db.flush()
        voice.stop()
        engine.release()
        SpokenFeedback.shutdown()
        session.release()
        try {
            if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
        } catch (e: Throwable) { }
        super.onDestroy()
    }
}
