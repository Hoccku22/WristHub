package dev.wristreader.wear

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.wristreader.shared.Document
import dev.wristreader.shared.DocumentStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class MusicState(val id: String = "", val name: String = "", val playing: Boolean = false,
    val loading: Boolean = false, val position: Long = 0, val duration: Long = 0, val message: String = "")

object MusicPlayback {
    val state = MutableStateFlow(MusicState())
    fun command(context: Context, action: String, id: String? = null, position: Long? = null) {
        val intent = Intent(context,MusicService::class.java).setAction(action)
        id?.let { intent.putExtra("id",it) }; position?.let { intent.putExtra("position",it) }
        if (action == "PLAY") ContextCompat.startForegroundService(context,intent)
        else if (state.value.id.isNotEmpty()) context.startService(intent)
    }
}

/** Local files only. The foreground service owns playback across Activity stops. */
class MusicService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playWhenReady = false
    private var resumeOnFocus = false
    private var queue = emptyList<Document>()
    private var selected = -1
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("music","Музыка WristHub",NotificationManager.IMPORTANCE_LOW))
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        player?.setVolume(1f,1f)
                        if (resumeOnFocus) { resumeOnFocus = false; play() }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f,0.2f)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        val resume = playWhenReady
                        pause(abandon = false); resumeOnFocus = resume
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                }
            }.build()
        session = MediaSession(this,"WristHub").apply {
            setSessionActivity(PendingIntent.getActivity(this@MusicService,0,
                Intent(this@MusicService,MainActivity::class.java).putExtra("music",true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopSelf()
                override fun onSkipToNext() = move(1)
                override fun onSkipToPrevious() = move(-1)
                override fun onSeekTo(pos: Long) = seek(pos)
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            while (isActive) { delay(1000); if (prepared) publish(notify = false) }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY" -> {
                // Promote before asynchronous loading or requesting audio focus (Android 15+).
                startForeground(28,notification())
                val id = intent.getStringExtra("id")
                if (id != null && id != MusicPlayback.state.value.id) load(id) else play()
            }
            "PAUSE" -> pause()
            "TOGGLE" -> if (playWhenReady) pause() else play()
            "NEXT" -> move(1)
            "PREVIOUS" -> move(-1)
            "SEEK" -> seek(intent.getLongExtra("position",0))
            "STOP" -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun load(id: String) {
        loadJob?.cancel()
        releasePlayer()
        playWhenReady = true
        MusicPlayback.state.value = MusicState(id = id,loading = true,message = "Загрузка…")
        publish()
        loadJob = scope.launch {
            try {
                queue = withContext(Dispatchers.IO) { DocumentStore(this@MusicService).list().filter { it.header.mime.startsWith("audio/") } }
                selected = queue.indexOfFirst { it.header.id == id }
                check(selected >= 0) { "Аудиофайл не найден" }
                val doc = queue[selected]
                MusicPlayback.state.value = MusicPlayback.state.value.copy(name = doc.header.name)
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setWakeMode(this@MusicService,android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(doc.original.path)
                    setOnPreparedListener {
                        prepared = true
                        MusicPlayback.state.value = MusicPlayback.state.value.copy(loading = false,duration = it.duration.toLong(),message = "")
                        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,doc.header.name)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST,"WristHub")
                            .putLong(MediaMetadata.METADATA_KEY_DURATION,it.duration.toLong()).build())
                        if (playWhenReady) play() else publish()
                    }
                    setOnCompletionListener {
                        if (selected < queue.lastIndex) move(1)
                        else { playWhenReady = false; audio.abandonAudioFocusRequest(focus); publish() }
                    }
                    setOnErrorListener { _,_,_ -> fail("Не удалось воспроизвести файл: формат или кодек недоступен"); true }
                    prepareAsync()
                }
                publish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message ?: "Ошибка воспроизведения") }
        }
    }
    private fun play() {
        resumeOnFocus = false
        playWhenReady = true
        if (prepared) {
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                runCatching { player?.start() }.onFailure { fail("Не удалось запустить аудио") }
            } else {
                playWhenReady = false
                MusicPlayback.state.value = MusicPlayback.state.value.copy(message = "Звук занят другим приложением")
            }
        }
        publish()
    }
    private fun pause(abandon: Boolean = true) {
        playWhenReady = false; resumeOnFocus = false
        if (prepared) runCatching { player?.pause() }
        if (abandon) audio.abandonAudioFocusRequest(focus)
        publish()
    }
    private fun seek(position: Long) {
        if (prepared) {
            runCatching { player?.seekTo(position.coerceIn(0,MusicPlayback.state.value.duration),MediaPlayer.SEEK_CLOSEST) }
            publish()
        }
    }
    private fun move(direction: Int) {
        val next = selected + direction
        if (next in queue.indices) load(queue[next].header.id)
    }
    private fun fail(message: String) {
        releasePlayer(); playWhenReady = false
        MusicPlayback.state.value = MusicPlayback.state.value.copy(playing = false,loading = false,message = message)
        stopSelf()
    }
    private fun releasePlayer() {
        prepared = false
        player?.apply { setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null); release() }
        player = null
    }
    private fun publish(notify: Boolean = true) {
        val old = MusicPlayback.state.value
        val playing = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
        val position = if (prepared) runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else 0L
        MusicPlayback.state.value = old.copy(playing = playing,position = position)
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (old.loading) PlaybackState.STATE_BUFFERING else if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,if (playing) 1f else 0f).build())
        if (notify) getSystemService(NotificationManager::class.java).notify(28,notification())
    }
    private fun notification(): Notification {
        fun action(command: String) = PendingIntent.getService(this,command.hashCode(),
            Intent(this,MusicService::class.java).setAction(command),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val state = MusicPlayback.state.value
        val show = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra("music",true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"music").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.name.ifBlank { "WristHub • Музыка" }).setContentText(if (state.loading) "Загрузка…" else if (state.playing) "Воспроизведение" else "Пауза")
            .setContentIntent(show).setOnlyAlertOnce(true).setOngoing(state.playing)
            .addAction(android.R.drawable.ic_media_previous,"Назад",action("PREVIOUS"))
            .addAction(if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "Пауза" else "Играть",action("TOGGLE"))
            .addAction(android.R.drawable.ic_media_next,"Далее",action("NEXT"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Стоп",action("STOP"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    override fun onDestroy() {
        loadJob?.cancel(); scope.cancel(); releasePlayer()
        audio.abandonAudioFocusRequest(focus)
        unregisterReceiver(noisy); session.release()
        val error = MusicPlayback.state.value.message
        MusicPlayback.state.value = MusicState(message = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
