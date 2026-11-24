package app.chudmusic.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.stats.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Counts what is really listened to (PC rules): a play counts after 30 s, or half of a short
 * track; listening time is added per day and hour (radio included, never in the history).
 */
class ListenTracker(private val player: Player, private val stats: Stats, private val scope: CoroutineScope) {
    private var track: Track? = null
    private var itemKey: Any? = null
    private var listenedMs = 0L
    private var counted = false
    private var pendingSeconds = 0L

    fun start() {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { switchTo(mediaItem) }
        })
        switchTo(player.currentMediaItem)
        scope.launch {
            var last = System.currentTimeMillis()
            var sinceFlush = 0
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val dt = (now - last).coerceIn(0, 5000)
                last = now
                if (!player.isPlaying) continue
                val t = track ?: continue
                listenedMs += dt
                pendingSeconds += dt
                if (!counted && t.source != Source.RADIO) {
                    if (listenedMs >= playThresholdMs(t.durationMs ?: player.duration.takeIf { it > 0 })) {
                        counted = true
                        withContext(Dispatchers.IO) { stats.notePlay(t) }
                    }
                }
                if (++sinceFlush >= 15) { sinceFlush = 0; flush() }
            }
        }
    }

    private fun switchTo(item: MediaItem?) {
        val prev = track
        val seconds = pendingSeconds
        pendingSeconds = 0
        if (prev != null && seconds >= 1000) scope.launch(Dispatchers.IO) { stats.addListen(prev, seconds / 1000) }
        track = MediaItems.track(item)
        itemKey = item
        listenedMs = 0
        counted = false
    }

    companion object {
        /** Listening time after which a track counts as played: 30 s, or half of it when it is shorter than a minute. */
        fun playThresholdMs(durationMs: Long?): Long = minOf(30_000L, (durationMs ?: 60_000L) / 2)
    }

    suspend fun flush() {
        val t = track ?: return
        val s = pendingSeconds / 1000
        if (s <= 0) return
        pendingSeconds -= s * 1000
        withContext(Dispatchers.IO) { stats.addListen(t, s) }
    }
}
