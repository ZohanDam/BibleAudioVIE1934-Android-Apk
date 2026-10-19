package com.zohan.nghekinhthanh1925

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast

class AudioPlayerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: BibleRepository
    private lateinit var downloads: AudioDownloadStore
    private lateinit var loops: PlaybackLoopStore
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var chapters: List<BibleChapter>
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest

    private var mediaPlayer: MediaPlayer? = null
    private var currentChapter: BibleChapter? = null
    private var foregroundStarted = false
    private var playbackGeneration = 0
    private var resumeAfterFocusGain = false
    private var prepared = false
    private var buffering = false
    private var lastPositionSaveElapsed = 0L

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusGain = false
                pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterFocusGain = PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying
                pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    mediaPlayer?.setVolume(0.25f, 0.25f)
                } catch (_: IllegalStateException) {
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    mediaPlayer?.setVolume(1f, 1f)
                } catch (_: IllegalStateException) {
                }
                if (resumeAfterFocusGain) {
                    resumeAfterFocusGain = false
                    resumePlayback()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = BibleRepository(applicationContext)
        downloads = AudioDownloadStore(applicationContext)
        loops = PlaybackLoopStore(applicationContext)
        preferences = getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        PlaybackStateStore.audioVersion = preferences.getString(KEY_AUDIO_VERSION, "VIE1925") ?: "VIE1925"
        chapters = repository.chapters(PlaybackStateStore.audioVersion)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
        PlaybackStateStore.speed = preferences.getFloat(KEY_SPEED, 1.0f)
        PlaybackStateStore.currentOrder = preferences.getInt(KEY_LAST_ORDER, 1)
        mediaSession = MediaSession(this, "NgheKinhThanh1925").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = playRelative(1)
                override fun onSkipToPrevious() = playPrevious()
                override fun onRewind() = rewindFifteenSeconds()
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == ACTION_REWIND_15) rewindFifteenSeconds()
                }
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
        registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        createNotificationChannel()
        SleepTimer.restore(this)
        if (!expireSleepIfNeeded()) scheduleSleepCheck()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        expireSleepIfNeeded()
        if (intent == null) {
            if (preferences.getBoolean(KEY_WAS_PLAYING, false)) {
                resumePlayback()
            } else {
                stopSelf()
            }
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY_ORDER, ACTION_SELECT_ORDER -> {
                val order = intent.getIntExtra(EXTRA_ORDER, preferences.getInt(KEY_LAST_ORDER, 1))
                val version = intent.getStringExtra(EXTRA_AUDIO_VERSION) ?: PlaybackStateStore.audioVersion
                loops.onManualSelection(currentChapter?.audioVersion ?: PlaybackStateStore.audioVersion, version, order)
                playOrder(order, intent.getBooleanExtra(EXTRA_AUTO_PLAY, true),
                    intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false), version)
            }
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_NEXT -> playRelative(1)
            ACTION_PREVIOUS -> playPrevious()
            ACTION_REWIND_15 -> rewindFifteenSeconds()
            ACTION_CHECK_SLEEP -> expireSleepIfNeeded()
            ACTION_SEEK -> seekTo(intent.getIntExtra(EXTRA_POSITION, 0))
            ACTION_SPEED -> setSpeed(intent.getFloatExtra(EXTRA_SPEED, PlaybackStateStore.speed))
            ACTION_SLEEP -> scheduleSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_STOP -> stopPlayback()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        savePlaybackPosition()
        handler.removeCallbacksAndMessages(null)
        playbackGeneration += 1
        PlaybackStateStore.isPlaying = false
        PlaybackStateStore.playWhenReady = false
        releasePlayer()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        unregisterReceiver(becomingNoisyReceiver)
        mediaSession.release()
        repository.close()
        super.onDestroy()
    }

    private fun playOrder(
        order: Int,
        autoPlay: Boolean = true,
        startFromBeginning: Boolean = false,
        audioVersion: String = PlaybackStateStore.audioVersion
    ) {
        if (chapters.firstOrNull()?.audioVersion != audioVersion) chapters = repository.chapters(audioVersion)
        val chapter = chapters.firstOrNull { it.globalOrder == order } ?: return
        val generation = ++playbackGeneration
        handler.removeCallbacks(progressRunnable)
        savePlaybackPosition()
        releasePlayer()
        currentChapter = chapter
        PlaybackStateStore.audioVersion = chapter.audioVersion
        PlaybackStateStore.playWhenReady = autoPlay
        resumeAfterFocusGain = false
        PlaybackStateStore.currentOrder = chapter.globalOrder
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.positionUpdatedElapsed = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.isPlaying = false
        preferences.edit().putInt(KEY_LAST_ORDER, chapter.globalOrder)
            .putString(KEY_AUDIO_VERSION, chapter.audioVersion)
            .putBoolean(KEY_WAS_PLAYING, autoPlay).apply()
        if (startFromBeginning) preferences.edit().putLong(chapter.positionKey, 0L).apply()
        if (!autoPlay) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            updatePlaybackState()
            updateNotification()
            return
        }
        startInForeground()

        val source = downloads.availableFile(chapter)?.absolutePath ?: chapter.url
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(source)
            player.setOnPreparedListener {
                if (generation != playbackGeneration || mediaPlayer !== it) {
                    try {
                        it.release()
                    } catch (_: Exception) {
                    }
                    return@setOnPreparedListener
                }
                try {
                    val savedPosition = if (startFromBeginning) {
                        0
                    } else {
                        preferences.getLong(chapter.positionKey, 0L).toInt()
                    }
                    if (savedPosition in 1 until it.duration) it.seekTo(savedPosition)
                    prepared = true
                    if (PlaybackStateStore.playWhenReady && requestAudioFocus()) {
                        it.start()
                        applySpeed()
                    } else {
                        PlaybackStateStore.playWhenReady = false
                    }
                    updatePlaybackState()
                    updateNotification()
                    startProgressTicker()
                } catch (_: Exception) {
                    showPlaybackError(chapter)
                }
            }
            player.setOnSeekCompleteListener {
                if (generation == playbackGeneration && mediaPlayer === it) {
                    savePlaybackPosition()
                    updatePlaybackState()
                }
            }
            player.setOnInfoListener { source, what, _ ->
                if (generation != playbackGeneration || mediaPlayer !== source) return@setOnInfoListener false
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START, MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        buffering = what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        updatePlaybackState()
                        updateNotification()
                        true
                    }
                    else -> false
                }
            }
            player.setOnCompletionListener {
                if (generation != playbackGeneration || !PlaybackStateStore.playWhenReady) return@setOnCompletionListener
                val next = loops.load(chapter.audioVersion).relativeOrder(chapter.globalOrder, 1)
                    ?: chapters.firstOrNull { it.globalOrder == chapter.globalOrder + 1 }?.globalOrder
                if (next == null) {
                    pausePlayback()
                    seekTo(0)
                } else {
                    playOrder(next, autoPlay = true, startFromBeginning = true)
                }
                // playOrder saves the old player position before releasing it.
                preferences.edit().putLong(chapter.positionKey, 0L).apply()
            }
            player.setOnErrorListener { _, _, _ ->
                if (generation != playbackGeneration) return@setOnErrorListener true
                showPlaybackError(chapter)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            showPlaybackError(chapter)
        }
    }

    private fun togglePlayPause() {
        if (PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying) pausePlayback()
        else resumePlayback()
    }

    private fun resumePlayback() {
        if (expireSleepIfNeeded()) return
        PlaybackStateStore.playWhenReady = true
        resumeAfterFocusGain = false
        if (mediaPlayer == null) {
            val order = currentChapter?.globalOrder ?: preferences.getInt(KEY_LAST_ORDER, 1)
            val loop = loops.load(PlaybackStateStore.audioVersion)
            val target = if (loop.enabled && order !in loop.orders) loop.orders.first() else order
            playOrder(target, startFromBeginning = target != order)
            return
        }
        if (!prepared) return
        try {
            startInForeground()
            if (!requestAudioFocus()) {
                PlaybackStateStore.playWhenReady = false
                return
            }
            mediaPlayer?.start()
            applySpeed()
            startProgressTicker()
            updatePlaybackState()
            updateNotification()
        } catch (_: IllegalStateException) {
            currentChapter?.let(::showPlaybackError)
        }
    }

    private fun pausePlayback() {
        resumeAfterFocusGain = false
        PlaybackStateStore.playWhenReady = false
        handler.removeCallbacks(progressRunnable)
        try {
            if (prepared) mediaPlayer?.pause()
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            savePlaybackPosition()
            updatePlaybackState()
            updateNotification()
        } catch (_: IllegalStateException) {
            currentChapter?.let(::showPlaybackError)
        }
    }

    private fun playRelative(
        offset: Int,
        autoPlay: Boolean = PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying
    ) {
        val order = currentChapter?.globalOrder ?: preferences.getInt(KEY_LAST_ORDER, 1)
        val target = loops.load(PlaybackStateStore.audioVersion).relativeOrder(order, offset) ?: order + offset
        chapters.firstOrNull { it.globalOrder == target }?.let {
            playOrder(it.globalOrder, autoPlay = autoPlay, startFromBeginning = true)
        }
    }

    private fun playPrevious() {
        val player = mediaPlayer
        try {
            if (player != null && player.currentPosition > 5_000) {
                player.seekTo(0)
                updatePlaybackState()
                return
            }
        } catch (_: IllegalStateException) {
        }
        playRelative(-1)
    }

    private fun seekTo(position: Int) {
        if (!prepared) return
        try {
            mediaPlayer?.let { it.seekTo(position.coerceIn(0, it.duration.coerceAtLeast(0))) }
            updatePlaybackState()
        } catch (_: IllegalStateException) {
        }
    }

    private fun rewindFifteenSeconds() {
        if (!prepared) return
        try {
            mediaPlayer?.let { seekTo((it.currentPosition - 15_000).coerceAtLeast(0)) }
        } catch (_: IllegalStateException) {
        }
    }

    private fun setSpeed(speed: Float) {
        PlaybackStateStore.speed = speed.coerceIn(0.5f, 2.0f)
        preferences.edit().putFloat(KEY_SPEED, PlaybackStateStore.speed).apply()
        applySpeed()
        updatePlaybackState()
        updateNotification()
    }

    private fun applySpeed() {
        try {
            val player = mediaPlayer ?: return
            // Setting playbackParams can start MediaPlayer; never apply it while paused.
            if (!prepared || !PlaybackStateStore.playWhenReady || !player.isPlaying) return
            player.playbackParams = player.playbackParams.setSpeed(PlaybackStateStore.speed)
        } catch (_: Exception) {
        }
    }

    private fun scheduleSleep(minutes: Int) {
        SleepTimer.start(this, minutes)
        scheduleSleepCheck()
    }

    private fun scheduleSleepCheck() {
        handler.removeCallbacksAndMessages(SLEEP_TOKEN)
        if (PlaybackStateStore.sleepDeadlineElapsed == 0L) return
        handler.postAtTime(
            {
                if (!expireSleepIfNeeded()) scheduleSleepCheck()
            },
            SLEEP_TOKEN,
            SystemClock.uptimeMillis() + SleepTimer.remainingMs()
        )
    }

    private fun expireSleepIfNeeded(): Boolean {
        if (PlaybackStateStore.sleepDeadlineElapsed == 0L || SleepTimer.remainingMs() > 0) return false
        SleepTimer.cancel(this)
        handler.removeCallbacksAndMessages(SLEEP_TOKEN)
        pausePlayback()
        Toast.makeText(this, UiLanguage.text(this, "Đã hết giờ nghe.", "Sleep timer finished."), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun stopPlayback() {
        pausePlayback()
        preferences.edit().putBoolean(KEY_WAS_PLAYING, false).apply()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foregroundStarted = false
        }
    }

    private fun showPlaybackError(chapter: BibleChapter) {
        PlaybackStateStore.playWhenReady = false
        PlaybackStateStore.isPlaying = false
        preferences.edit().putBoolean(KEY_WAS_PLAYING, false).apply()
        releasePlayer()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        Toast.makeText(
            this,
            UiLanguage.text(this, "Không thể phát ${chapter.bookName} ${chapter.chapter}.", "Could not play ${chapter.bookName} ${chapter.chapter}."),
            Toast.LENGTH_LONG
        ).show()
        updateNotification()
    }

    private fun savePlaybackPosition(force: Boolean = true) {
        val chapter = currentChapter ?: return
        val player = mediaPlayer ?: return
        if (!prepared) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPositionSaveElapsed < POSITION_SAVE_INTERVAL_MS) return
        try {
            if (player.duration > 0) {
                preferences.edit()
                    .putLong(chapter.positionKey, player.currentPosition.toLong())
                    .apply()
                lastPositionSaveElapsed = now
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun releasePlayer() {
        prepared = false
        buffering = false
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun requestAudioFocus(): Boolean {
        return audioManager.requestAudioFocus(audioFocusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun pauseForFocusLoss() {
        PlaybackStateStore.playWhenReady = false
        handler.removeCallbacks(progressRunnable)
        try {
            if (prepared) mediaPlayer?.pause()
            savePlaybackPosition()
            updatePlaybackState()
            updateNotification()
        } catch (_: IllegalStateException) {
        }
    }

    private fun startProgressTicker() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (expireSleepIfNeeded()) return
            updatePlaybackState(publishSession = false)
            savePlaybackPosition(force = false)
            if (PlaybackStateStore.isPlaying) handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private fun updatePlaybackState(publishSession: Boolean = true) {
        val player = mediaPlayer
        PlaybackStateStore.currentOrder = currentChapter?.globalOrder
            ?: preferences.getInt(KEY_LAST_ORDER, 1)
        try {
            PlaybackStateStore.isPlaying = prepared && player?.isPlaying == true
            PlaybackStateStore.positionMs = if (prepared) player?.currentPosition ?: 0 else 0
            PlaybackStateStore.durationMs = if (prepared) player?.duration?.takeIf { it > 0 } ?: 0 else 0
        } catch (_: IllegalStateException) {
            PlaybackStateStore.isPlaying = false
            PlaybackStateStore.positionMs = 0
            PlaybackStateStore.durationMs = 0
        }
        PlaybackStateStore.positionUpdatedElapsed = if (buffering) 0 else SystemClock.elapsedRealtime()
        if (preferences.getBoolean(KEY_WAS_PLAYING, false) != PlaybackStateStore.playWhenReady) {
            preferences.edit().putBoolean(KEY_WAS_PLAYING, PlaybackStateStore.playWhenReady).apply()
        }
        if (publishSession) updateMediaSession()
    }

    private fun startInForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        } catch (_: SecurityException) {
        }
    }

    private fun updateNotification() {
        updateMediaSession()
        if (!foregroundStarted) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                UiLanguage.text(this, "Phát Kinh Thánh", "Bible playback"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val chapter = currentChapter
        val title = chapter?.let { "${it.bookName} ${it.chapter}" }
            ?: UiLanguage.text(this, "Kinh Thánh Offline", "Offline Bible")
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("Audio ${currentChapter?.audioVersion ?: PlaybackStateStore.audioVersion}")
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .addAction(notificationAction(
                R.drawable.ic_rewind_15,
                UiLanguage.text(this, "Lùi 15 giây", "Rewind 15 seconds"),
                ACTION_REWIND_15
            ))
            .addAction(notificationAction(
                if (PlaybackStateStore.isPlaying) {
                    android.R.drawable.ic_media_pause
                } else {
                    android.R.drawable.ic_media_play
                },
                if (PlaybackStateStore.isPlaying) UiLanguage.text(this, "Tạm dừng", "Pause") else UiLanguage.text(this, "Phát", "Play"),
                ACTION_TOGGLE
            ))
            .addAction(notificationAction(
                android.R.drawable.ic_media_next,
                UiLanguage.text(this, "Chương sau", "Next chapter"),
                ACTION_NEXT
            ))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(PlaybackStateStore.isPlaying)
            .build()
    }

    private fun notificationAction(
        iconResource: Int,
        title: String,
        action: String
    ): Notification.Action {
        return Notification.Action.Builder(
            Icon.createWithResource(this, iconResource),
            title,
            serviceIntent(action)
        ).build()
    }

    private fun updateMediaSession() {
        val chapter = currentChapter
        val title = chapter?.let { "${it.bookName} ${it.chapter}" }
            ?: UiLanguage.text(this, "Kinh Thánh Offline", "Offline Bible")
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter?.audioVersion ?: PlaybackStateStore.audioVersion)
                .putLong(
                    MediaMetadata.METADATA_KEY_DURATION,
                    PlaybackStateStore.durationMs.toLong()
                )
                .build()
        )
        mediaSession.setPlaybackState(buildMediaPlaybackState())
    }

    private fun buildMediaPlaybackState(): PlaybackState {
        val actions = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val state = if (PlaybackStateStore.isPlaying && buffering) {
            PlaybackState.STATE_BUFFERING
        } else if (PlaybackStateStore.isPlaying) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        return PlaybackState.Builder()
            .setActions(actions)
            // Android 13+ fills the previous slot with this custom action.
            .addCustomAction(ACTION_REWIND_15,
                UiLanguage.text(this, "Lùi 15 giây", "Rewind 15 seconds"), R.drawable.ic_rewind_15)
            .setState(
                state,
                PlaybackStateStore.positionMs.toLong(),
                if (state == PlaybackState.STATE_PLAYING) PlaybackStateStore.speed else 0f
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlayerService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_PLAY_ORDER = "com.zohan.nghekinhthanh1925.PLAY_ORDER"
        const val ACTION_SELECT_ORDER = "com.zohan.nghekinhthanh1925.SELECT_ORDER"
        const val ACTION_PAUSE = "com.zohan.nghekinhthanh1925.PAUSE"
        const val ACTION_RESUME = "com.zohan.nghekinhthanh1925.RESUME"
        const val EXTRA_AUDIO_VERSION = "audioVersion"
        const val ACTION_TOGGLE = "com.zohan.nghekinhthanh1925.TOGGLE"
        const val ACTION_NEXT = "com.zohan.nghekinhthanh1925.NEXT"
        const val ACTION_PREVIOUS = "com.zohan.nghekinhthanh1925.PREVIOUS"
        const val ACTION_REWIND_15 = "com.zohan.nghekinhthanh1925.REWIND_15"
        const val ACTION_CHECK_SLEEP = "com.zohan.nghekinhthanh1925.CHECK_SLEEP"
        const val ACTION_SEEK = "com.zohan.nghekinhthanh1925.SEEK"
        const val ACTION_SPEED = "com.zohan.nghekinhthanh1925.SPEED"
        const val ACTION_SLEEP = "com.zohan.nghekinhthanh1925.SLEEP"
        const val ACTION_STOP = "com.zohan.nghekinhthanh1925.STOP"
        const val EXTRA_ORDER = "order"
        const val EXTRA_POSITION = "position"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_AUTO_PLAY = "autoPlay"
        const val EXTRA_START_FROM_BEGINNING = "startFromBeginning"

        private const val STATE_PREFERENCES = "state"
        private const val KEY_LAST_ORDER = "lastOrder"
        private const val KEY_AUDIO_VERSION = "audioVersion"
        private const val KEY_SPEED = "speed"
        private const val KEY_WAS_PLAYING = "wasPlaying"
        private const val CHANNEL_ID = "bible_playback"
        private const val NOTIFICATION_ID = 1925
        private const val SLEEP_TOKEN = "sleep_timer"
        private const val PROGRESS_INTERVAL_MS = 5_000L
        private const val POSITION_SAVE_INTERVAL_MS = 15_000L
    }
}
