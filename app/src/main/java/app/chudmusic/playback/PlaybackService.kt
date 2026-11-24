package app.chudmusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.chudmusic.MainActivity
import app.chudmusic.audio.ChudAudioSink
import app.chudmusic.audio.ReplayGain
import app.chudmusic.audio.SpectrumTap
import app.chudmusic.data.LoudnessEntity
import app.chudmusic.graph
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * The player lives here, in a foreground media service: playback goes on with the screen off or
 * the app closed, with the system notification, the lock screen, Bluetooth buttons and Android
 * Auto — all through Media3's MediaSession. One ExoPlayer for every source.
 */
class PlaybackService : MediaLibraryService() {
    companion object {
        /** Messages for the UI (an unplayable track, a YouTube error…), only while it is open. */
        val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
        val eventFlow: SharedFlow<PlayerEvent> get() = events
        /** Playback stopped for lack of network and goes on by itself when it is back ([ErrorAction.WAIT_NETWORK], [ErrorAction.RETRY_LATER]). */
        val waiting = MutableStateFlow(false)
    }

    sealed interface PlayerEvent {
        data class Skipped(val title: String, val reason: SkipReason) : PlayerEvent
        data object AllFailed : PlayerEvent
        data object WaitingNetwork : PlayerEvent
    }

    /** Why a track could not be played, as told to the user. */
    enum class SkipReason {
        MISSING, NETWORK, AUTH, FORMAT, OTHER;

        companion object {
            fun of(errorCode: Int): SkipReason = when (errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> MISSING
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> NETWORK
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FAILED -> FORMAT
                else -> OTHER
            }
        }
    }

    /**
     * What to do when the current track fails (state machine of [ErrorHandler], see docs/STATE-MACHINES.md):
     *
     *     Playing ──error──▶ RETRY_SAME   an expired YouTube address, once per track
     *                      ▶ SKIP_NEXT    tell the user, play the next track
     *                      ▶ STOP         tell the user (a radio, or nothing after)
     *                      ▶ STOP_ALL     every track of the queue failed in a row: tell it once and stop
     *                      ▶ WAIT_NETWORK the phone has no network: the same track again when it is back
     *                      ▶ RETRY_LATER  the connection was lost while the track (or radio) played: again in a moment
     *     any ──a track really plays──▶ the counts of failures and retries go back to 0
     */
    enum class ErrorAction { RETRY_SAME, SKIP_NEXT, STOP, STOP_ALL, WAIT_NETWORK, RETRY_LATER }

    object ErrorPolicy {
        /** Waits before trying again a track whose connection was lost while it played. */
        val RETRY_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)

        /** [failures] counts this error too; [retries]: how many times this track was already tried again after a lost connection. */
        fun decide(
            expiredYoutubeUrl: Boolean, alreadyRetried: Boolean, failures: Int, queueSize: Int, isRadio: Boolean, hasNext: Boolean,
            lostConnection: Boolean = false, offline: Boolean = false, hadPlayed: Boolean = false, retries: Int = 0,
        ): ErrorAction = when {
            lostConnection && offline -> ErrorAction.WAIT_NETWORK
            expiredYoutubeUrl && !alreadyRetried -> ErrorAction.RETRY_SAME
            lostConnection && hadPlayed && retries < RETRY_MS.size -> ErrorAction.RETRY_LATER
            failures >= queueSize -> ErrorAction.STOP_ALL
            isRadio || !hasNext -> ErrorAction.STOP
            else -> ErrorAction.SKIP_NEXT
        }

        /** An error that says the way to the track is cut, not that the track is bad. */
        fun isConnection(errorCode: Int): Boolean = errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saveJob: Job? = null
    private lateinit var listen: ListenTracker

    override fun onCreate() {
        super.onCreate()
        val g = graph
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
                ChudAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(g.normalizer, g.crossfade, g.equalizer, SpectrumTap()))
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                        .build(),
                    g.normalizer, g.crossfade, g.equalizer,
                ) { id -> runBlocking { withTimeoutOrNull(200) { g.db.loudness().get(id) } }?.let { ReplayGain.Measure(it.lufs, it.peak) } }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        g.normalizer.onMeasured = { id, m ->
            g.scope.launch(Dispatchers.IO) { g.db.loudness().put(LoudnessEntity(id, m.lufs, m.peak, System.currentTimeMillis())) }
        }

        var preferOpus = true
        scope.launch { g.settings.ytPreferOpus.collect { preferOpus = it } }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ChudDataSource.Factory(this, g.sources) { preferOpus })
                    .setLoadErrorHandlingPolicy(FailFastPolicy()),
            )
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(ErrorHandler())
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) scheduleSave()
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED) || (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !p.isPlaying)) savePlace()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) applyPauseAtEnd()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) graph.sleep.off()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The queue ended before the timer had to stop it.
                if (playbackState == Player.STATE_ENDED && graph.sleep.state.value !is SleepTimer.State.At) graph.sleep.off()
            }
        })
        scope.launch { g.settings.speed.collect { player.setPlaybackSpeed(it) } }
        listen = ListenTracker(player, g.stats, scope).also { it.start() }

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(this, scope) { savedQueue() })
            .setSessionActivity(open)
            .build()
        scope.launch { restore() }
        scope.launch { while (isActive) { delay(PLACE_EVERY_MS); if (player.isPlaying) savePlace() } }
        scope.launch { g.sleep.state.collectLatest { runSleep(it) } }
    }

    // ── Sleep timer ─────────────────────────────────────────────────────────────────────────

    private suspend fun runSleep(s: SleepTimer.State) {
        applyPauseAtEnd()
        if (s !is SleepTimer.State.At) { player.volume = 1f; return }
        try {
            while (true) {
                val left = s.endsAt - System.currentTimeMillis()
                if (left <= 0) break
                player.volume = SleepTimer.volume(left)
                delay(if (left > SleepTimer.FADE_MS) minOf(1000, left - SleepTimer.FADE_MS).coerceAtLeast(50) else 100)
            }
            player.pause()
            graph.sleep.off()
        } finally {
            player.volume = 1f
        }
    }

    /** "End of the track" and "end of the album": the player itself stops there, to the sample. */
    private fun applyPauseAtEnd() {
        val state = graph.sleep.state.value
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { MediaItems.track(player.getMediaItemAt(it)) }
        player.pauseAtEndOfMediaItems = SleepTimer.stopsAfter(state, MediaItems.track(player.currentMediaItem), next)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /** Swiping the app away stops the service only when nothing is playing (music keeps playing otherwise). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        runBlocking { listen.flush() }
        saveNow()
        waiting.value = false
        graph.sleep.off()
        graph.normalizer.onMeasured = null
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ── Errors: one notice, then the next track (PC rule: a bad file never stops the session) ──

    private inner class ErrorHandler : Player.Listener {
        private var failures = 0
        private val retried = HashSet<String>()
        private var retries = 0
        /** The item that really played since it was started: a connection lost then is worth waiting for. */
        private var played: String? = null
        private var recovery: Job? = null

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failures = 0
            retries = 0
            played = player.currentMediaItem?.mediaId
            stopWaiting()
        }

        // The user chose something else, or stopped: nothing is waited for any more.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) { retries = 0; stopWaiting() } }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) stopWaiting() }

        private fun stopWaiting() {
            recovery?.cancel()
            recovery = null
            waiting.value = false
        }

        /** Plays the current track again from where it was, once [ready] returns. */
        private fun recover(ready: suspend () -> Unit) {
            stopWaiting()
            waiting.value = true
            // Kept as a wake-up by the foreground service: playWhenReady stays true meanwhile.
            recovery = scope.launch {
                ready()
                val live = MediaItems.track(player.currentMediaItem)?.isRadio == true
                if (live) player.seekToDefaultPosition(player.currentMediaItemIndex) else player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                player.prepare()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            val track = MediaItems.track(item)
            val cause = error.cause
            // A YouTube stream URL expired or was refused: resolve it again once, same position.
            val expired = track?.source == Source.YOUTUBE && track.videoId != null &&
                (cause is HttpDataSource.InvalidResponseCodeException || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            val refused = generateSequence<Throwable>(error) { it.cause }.any { it is app.chudmusic.net.RemoteAuthFailed }
            val lost = track != null && track.source != Source.LOCAL && !refused && ErrorPolicy.isConnection(error.errorCode)
            val action = ErrorPolicy.decide(
                expiredYoutubeUrl = expired, alreadyRetried = track != null && track.id in retried, failures = failures + 1,
                queueSize = player.mediaItemCount, isRadio = track?.source == Source.RADIO, hasNext = player.hasNextMediaItem(),
                lostConnection = lost, offline = !graph.network.online.value, hadPlayed = played == item.mediaId, retries = retries,
            )
            when (action) {
                ErrorAction.RETRY_SAME -> {
                    retried += track!!.id
                    app.chudmusic.youtube.YouTubeStreams.forget(track.videoId!!)
                    player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                    player.prepare()
                    return
                }
                ErrorAction.WAIT_NETWORK -> {
                    events.tryEmit(PlayerEvent.WaitingNetwork)
                    // A moment more: the address and the servers are not there at the very instant the network is.
                    recover { graph.network.online.first { it }; delay(1500) }
                    return
                }
                ErrorAction.RETRY_LATER -> {
                    val wait = ErrorPolicy.RETRY_MS[retries++]
                    recover { delay(wait); graph.sources.networkChanged() }
                    return
                }
                else -> {}
            }
            failures++
            val reason = if (refused) SkipReason.AUTH else SkipReason.of(error.errorCode)
            events.tryEmit(PlayerEvent.Skipped(track?.title ?: item.mediaMetadata.title?.toString() ?: "?", reason))
            when (action) {
                ErrorAction.STOP_ALL -> events.tryEmit(PlayerEvent.AllFailed)
                ErrorAction.SKIP_NEXT -> scope.launch {
                    delay(900)
                    // The sleep timer had to stop after this very track: it does, even if the track never played.
                    if (player.pauseAtEndOfMediaItems) { player.pause(); graph.sleep.off() }
                    player.seekToNextMediaItem()
                    // Paused (by the timer, or by the user meanwhile): an error never starts the music again.
                    if (player.playWhenReady) player.prepare()
                }
                else -> {}
            }
        }
    }

    // ── The queue survives a restart (restored paused, like the PC) ─────────────────────────

    private val store by lazy { QueueStore(filesDir) }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(SAVE_AFTER_MS); saveList() }
    }

    /** The list as it is now, taken on the player's thread; the returned block writes it. null: not the user's queue. */
    private fun listWriter(): (() -> Unit)? {
        val n = player.mediaItemCount
        if (n == 0) return { store.clear() }
        val items = (0 until n).map { player.getMediaItemAt(it) }
        val json = items.mapNotNull { MediaItems.trackJson(it) }
        if (json.size != n) return null
        val queued = items.map { MediaItems.isQueued(it) }
        val order = if (player.shuffleModeEnabled) shuffledOrder() else emptyList()
        return { store.writeList(json, queued, order) }
    }

    private fun shuffledOrder(): List<Int> {
        val o = player.shuffleOrder
        return generateSequence(o.firstIndex.takeIf { it != C.INDEX_UNSET }) { i -> o.getNextIndex(i).takeIf { it != C.INDEX_UNSET } }.toList()
    }

    private fun place() = QueueStore.Place(
        player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode, player.mediaItemCount, player.currentMediaItem?.mediaId,
    )

    private fun saveList() {
        val write = listWriter() ?: return
        val p = place()
        val some = player.mediaItemCount > 0
        graph.scope.launch(Dispatchers.IO) { write(); if (some) store.writePlace(p) }
    }

    private fun savePlace() {
        if (player.mediaItemCount == 0) return
        val p = place()
        graph.scope.launch(Dispatchers.IO) { store.writePlace(p) }
    }

    /** Before the service goes: written here and now, its coroutines would be cancelled before they ran. */
    private fun saveNow() {
        saveJob?.cancel()
        val write = listWriter() ?: return
        val p = place()
        val some = player.mediaItemCount > 0
        runBlocking(Dispatchers.IO) { write(); if (some) store.writePlace(p) }
    }

    private suspend fun savedQueue(): QueueStore.Saved? = withContext(Dispatchers.IO) {
        store.read() ?: graph.settings.oldQueueJson()?.let { runCatching { oldJson.decodeFromString(QueueStore.Saved.serializer(), it) }.getOrNull() }
            ?.also { graph.settings.forgetOldQueue() }
    }

    private suspend fun restore() {
        val q = savedQueue() ?: return
        if (q.tracks.isEmpty()) return
        // Before the list: it stays true whatever the user starts meanwhile.
        player.repeatMode = q.repeat
        // A long queue takes seconds on a slow phone: never on the main thread.
        val items = withContext(Dispatchers.Default) { q.tracks.mapIndexed { i, t -> MediaItems.toItem(t, q.queued.getOrElse(i) { false }) } }
        // The user already started something while the queue was read.
        if (player.mediaItemCount > 0) return
        player.setMediaItems(items, q.index.coerceIn(0, items.lastIndex), q.positionMs)
        if (q.order.size == items.size) player.setShuffleOrder(DefaultShuffleOrder(q.order.toIntArray(), System.nanoTime()))
        player.shuffleModeEnabled = q.shuffle
        // Not prepared: nothing is loaded (no network for a YouTube track) until the user presses play.
    }
}

private val oldJson = Json { ignoreUnknownKeys = true }
private const val SAVE_AFTER_MS = 1500L
private const val PLACE_EVERY_MS = 5000L

/**
 * A share that refuses the connection, a missing file or a refused password will not fix itself in
 * a few seconds: skip to the next track at once instead of ExoPlayer's usual retries (about 20 s
 * of silence). Other errors (flaky Wi-Fi, a YouTube hiccup) keep the default retries.
 */
private class FailFastPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var e: Throwable? = info.exception
        while (e != null) {
            if (e is app.chudmusic.net.RemoteUnreachable || e is app.chudmusic.net.RemoteAuthFailed || e is java.io.FileNotFoundException) return C.TIME_UNSET
            e = e.cause
        }
        return super.getRetryDelayMsFor(info)
    }
}
