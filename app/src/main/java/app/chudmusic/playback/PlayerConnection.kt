package app.chudmusic.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.chudmusic.library.QueueWindow
import app.chudmusic.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The UI's handle on the player (a MediaController bound to [PlaybackService]).
 *
 * The PC's three notions are kept: playing a list sets the queue from that list and never
 * touches a playlist; "Lire ensuite" and "Ajouter à la file" put tracks in an "À suivre" part
 * that plays before the rest of the list, then the list goes on where it was.
 *
 * Two state machines live here (see docs/STATE-MACHINES.md):
 *  - Link, the connection to the service: Disconnected -> Connecting -> Connected (-> Disconnected if
 *    the service dies; the next command connects again). Commands sent meanwhile wait and run in order.
 *  - [Phase], what the player is doing, computed by [phaseOf] from ExoPlayer's flags so that no
 *    screen has to combine isPlaying, playbackState and playWhenReady itself.
 */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {
    /** What the player is doing. Exactly one at a time. */
    enum class Phase {
        /** No controller yet (the app just started). */
        DISCONNECTED,
        /** Connected, nothing in the queue. */
        EMPTY,
        /** Buffering, or asked to play and getting ready: show a spinner. */
        LOADING,
        PLAYING,
        PAUSED,
        /** The queue played to its end. */
        ENDED,
        /** The network is gone: playback goes on by itself when it is back (the service waits for it). */
        WAITING,
        /** The current track could not be played (the service skips to the next one when it can). */
        FAILED,
    }

    data class State(
        val phase: Phase = Phase.DISCONNECTED,
        val track: Track? = null,
        val index: Int = -1,
        val queue: List<Track> = emptyList(),
        val queued: List<Boolean> = emptyList(),
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val durationMs: Long = 0,
        /** ICY title of a radio ("Artist - Title" on air). */
        val streamTitle: String? = null,
    ) {
        val connected: Boolean get() = phase != Phase.DISCONNECTED
        val isPlaying: Boolean get() = phase == Phase.PLAYING
        val loading: Boolean get() = phase == Phase.LOADING || phase == Phase.WAITING
    }

    /** The connection to [PlaybackService]. Only read and written on the main thread. */
    private sealed interface Link {
        data object Disconnected : Link
        data object Connecting : Link
        data class Connected(val controller: MediaController) : Link
    }

    companion object {
        /** The one place that turns ExoPlayer's flags into a [Phase]. */
        fun phaseOf(itemCount: Int, playbackState: Int, playWhenReady: Boolean, isPlaying: Boolean, hasError: Boolean, waiting: Boolean = false): Phase = when {
            itemCount == 0 -> Phase.EMPTY
            waiting && playWhenReady && !isPlaying -> Phase.WAITING
            playbackState == Player.STATE_BUFFERING -> Phase.LOADING
            playWhenReady && playbackState == Player.STATE_IDLE && !hasError -> Phase.LOADING
            isPlaying -> Phase.PLAYING
            hasError -> Phase.FAILED
            playbackState == Player.STATE_ENDED -> Phase.ENDED
            else -> Phase.PAUSED
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _position = MutableStateFlow(0L)
    /** Updated 4 times a second while something plays, not more (the PC learnt it the hard way, §12). */
    val position: StateFlow<Long> = _position

    private var link: Link = Link.Disconnected
    private val controller: MediaController? get() = (link as? Link.Connected)?.controller
    /** Commands sent before the controller was there; run in order once it is. */
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null
    private var waitingWatch: Job? = null

    /** Safe to call any number of times, from any thread: only a Disconnected link starts connecting. */
    fun connect() {
        scope.launch(Dispatchers.Main.immediate) {
            if (link != Link.Disconnected) return@launch
            link = Link.Connecting
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token)
                .setListener(object : MediaController.Listener {
                    // The service was stopped or crashed: the next command connects again.
                    override fun onDisconnected(controller: MediaController) {
                        if ((link as? Link.Connected)?.controller === controller) link = Link.Disconnected
                    }
                })
                .buildAsync()
            future.addListener({
                val c = runCatching { future.get() }.getOrNull()
                if (c == null) { link = Link.Disconnected; return@addListener }
                link = Link.Connected(c)
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refresh(events.contains(Player.EVENT_TIMELINE_CHANGED))
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()
                })
                refresh(queueChanged = true)
                val waiting = pending.toList()
                pending.clear()
                waiting.forEach { it(c) }
            }, androidx.core.content.ContextCompat.getMainExecutor(context))
            if (waitingWatch == null) waitingWatch = scope.launch(Dispatchers.Main) { PlaybackService.waiting.collect { refresh() } }
            if (ticker == null) ticker = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    controller?.let { if (it.isPlaying || _position.value != it.currentPosition) _position.value = it.currentPosition }
                    delay(250)
                }
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch(Dispatchers.Main) {
            val c = controller
            if (c != null) block(c) else { pending += block; connect() }
        }
    }

    /**
     * The queue is read again only when it changed: every other event leaves it as it is. Tracks not
     * seen before are decoded off the main thread; until then their title and artist stand for them.
     */
    private fun readQueue(c: MediaController) {
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val known = decoded
        queue = items.map { known[it.mediaId] ?: placeholder(it) }
        queued = items.map { MediaItems.isQueued(it) }
        decoding?.cancel()
        if (items.all { it.mediaId in known }) return
        decoding = scope.launch(Dispatchers.Main) {
            val all = withContext(Dispatchers.Default) {
                items.associate { it.mediaId to (known[it.mediaId] ?: MediaItems.track(it) ?: placeholder(it)) }
            }
            decoded = all
            queue = items.map { all.getValue(it.mediaId) }
            _state.value = _state.value.copy(queue = queue)
        }
    }

    private var queue: List<Track> = emptyList()
    private var queued: List<Boolean> = emptyList()
    private var decoded: Map<String, Track> = emptyMap()
    private var decoding: Job? = null

    /** The queue with every track complete, to keep it (a playlist). */
    suspend fun fullQueue(): List<Track> = withContext(Dispatchers.Main) { decoding?.join(); queue }

    private fun refresh(queueChanged: Boolean = false) {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (queueChanged || n != queue.size) readQueue(c)
        val cur = c.currentMediaItem
        val icy = cur?.let { c.mediaMetadata.title?.toString()?.takeIf { t -> t != MediaItems.track(cur)?.title } }
        _state.value = State(
            phase = phaseOf(n, c.playbackState, c.playWhenReady, c.isPlaying, c.playerError != null, PlaybackService.waiting.value),
            track = MediaItems.track(cur),
            index = c.currentMediaItemIndex,
            queue = queue,
            queued = queued,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            durationMs = c.duration.takeIf { it > 0 } ?: (MediaItems.track(cur)?.durationMs ?: 0),
            streamTitle = icy,
        )
        _position.value = c.currentPosition
    }

    private fun placeholder(item: MediaItem) = Track(
        item.mediaId, app.chudmusic.model.Source.LOCAL, "", item.mediaMetadata.title?.toString() ?: "?",
        artist = item.mediaMetadata.artist?.toString(),
    )

    /** Builds a list off the main thread; commands sent meanwhile wait for their turn. */
    private val lists = Mutex()
    private fun <T> withList(build: () -> T, block: (MediaController, T) -> Unit) {
        scope.launch(Dispatchers.Main) {
            lists.withLock {
                val built = withContext(Dispatchers.Default) { build() }
                withController { c -> block(c, built) }
            }
        }
    }

    private class Queue(val items: List<MediaItem>, val start: Int)

    // ── Commands ────────────────────────────────────────────────────────────────────────────

    /** Plays [tracks] from [index]: the queue becomes this list (the "playback context"). */
    fun playList(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) = withList({
        // Duplicates: the place asked for, not the first copy of its track.
        val indexed = tracks.withIndex().filter { it.value.available }
        if (indexed.isEmpty()) return@withList Queue(emptyList(), 0)
        val at = indexed.indexOfFirst { it.index == index }.coerceAtLeast(0)
        val kept = if (shuffle) QueueWindow.drawn(indexed.size, null) else QueueWindow.around(indexed.size, at).toList()
        val start = if (shuffle && kept.isNotEmpty()) kept.indices.random() else kept.indexOf(at)
        Queue(kept.map { MediaItems.toItem(indexed[it].value) }, start)
    }) { c, q ->
        if (q.items.isEmpty()) return@withList
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(q.items, q.start, 0)
        c.prepare()
        c.play()
    }

    private fun queuedItems(tracks: List<Track>) = tracks.filter { it.available }.map { MediaItems.toItem(it, queued = true) }

    fun playNext(tracks: List<Track>) = withList({ queuedItems(tracks) }) { c, items ->
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play(); return@withList }
        c.addMediaItems(c.currentMediaItemIndex + 1, items)
    }

    /** After the tracks already waiting in "À suivre", before the rest of the list. */
    fun addToQueue(tracks: List<Track>) = withList({ queuedItems(tracks) }) { c, items ->
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play(); return@withList }
        var i = c.currentMediaItemIndex + 1
        while (i < c.mediaItemCount && MediaItems.isQueued(c.getMediaItemAt(i))) i++
        c.addMediaItems(i, items)
    }

    fun toggle() = withController { c ->
        if (c.isPlaying || (PlaybackService.waiting.value && c.playWhenReady)) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }
    fun play() = withController { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
    fun pause() = withController { it.pause() }
    fun next() = withController { it.seekToNextMediaItem() }
    /** Like every player: back to the start after 3 s, else the previous track. */
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); _position.value = ms }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.prepare(); it.play() }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun removeAt(index: Int) = withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    /** Removes several places at once ([indexes] in the queue as shown). */
    fun removeAll(indexes: Set<Int>) = withController { c ->
        for (i in indexes.filter { it in 0 until c.mediaItemCount }.sortedDescending()) c.removeMediaItem(i)
    }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun clearQueue() = withController { c ->
        val cur = c.currentMediaItemIndex
        if (cur < c.mediaItemCount - 1) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
    }
    fun stop() = withController { it.stop(); it.clearMediaItems() }

    /** Replaces the current track's entry (e.g. its metadata got better); playback goes on. */
    suspend fun currentPositionNow(): Long = withContext(Dispatchers.Main) { controller?.currentPosition ?: 0 }
}
