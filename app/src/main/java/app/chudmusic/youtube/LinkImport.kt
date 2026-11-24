package app.chudmusic.youtube

import app.chudmusic.model.Track
import app.chudmusic.playlists.Playlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One pasted or shared link, from reading it to the tracks it names on YouTube Music (state
 * machine, see docs/STATE-MACHINES.md):
 *
 *     Reading ──▶ Ready(slots)            a YouTube link: every slot already Found
 *     Reading ──▶ Ready(slots: Pending)   a Spotify link ──each song looked up──▶ Found | Missing
 *     Reading ──▶ Elsewhere(target)       an album or an artist: its own page opens instead
 *     Reading ──▶ Failed(reason)          ──retry──▶ Reading
 *     Ready(done) ──imported into a playlist──▶ Ready(added = n)     (once)
 */
sealed interface LinkImport {
    data object Reading : LinkImport
    data class Failed(val reason: Failure, val detail: String? = null) : LinkImport
    data class Elsewhere(val target: Target) : LinkImport

    data class Ready(
        val source: Origin,
        val kind: Kind,
        /** Null: a YouTube playlist without a title. */
        val title: String?,
        val subtitle: String?,
        val slots: List<Slot>,
        /** Only the first tracks were read (a very long playlist, or Spotify's embed page limit). */
        val truncated: Boolean = false,
        /** Tracks added to the target playlist, once every slot is settled; null before. */
        val added: Int? = null,
    ) : LinkImport {
        val found: List<Track> get() = slots.mapNotNull { (it as? Slot.Found)?.track }
        val missing: List<WantedTrack> get() = slots.mapNotNull { (it as? Slot.Missing)?.want }
        val pending: Int get() = slots.count { it is Slot.Pending }
        val done: Boolean get() = pending == 0

        /** Slot [index] settled: [track] found, or null when no close enough version exists. A settled slot never changes. */
        fun resolve(index: Int, track: Track?): Ready {
            val slot = slots.getOrNull(index) as? Slot.Pending ?: return this
            val next = if (track != null) Slot.Found(track) else Slot.Missing(slot.want)
            return copy(slots = slots.toMutableList().also { it[index] = next })
        }
    }

    sealed interface Slot {
        data class Pending(val want: WantedTrack) : Slot
        data class Found(val track: Track) : Slot
        data class Missing(val want: WantedTrack) : Slot
    }

    enum class Origin { YOUTUBE, SPOTIFY }
    enum class Kind { TRACK, ALBUM, PLAYLIST }
    enum class Failure { OFFLINE, NOT_FOUND, EMPTY, SPOTIFY_REFUSED, SPOTIFY_UNREADABLE, ARTIST_NOT_FOUND, OTHER }

    sealed interface Target {
        data class YtAlbum(val id: String) : Target
        data class YtArtist(val id: String) : Target
    }
}

/**
 * Runs link imports in the app's scope, so leaving the screen does not stop one, and opening the
 * same link again shows it as it stands. With a target playlist, the tracks found are added to it
 * once every song is settled.
 */
class LinkImports(
    private val yt: InnerTube, private val playlists: Playlists, private val scope: CoroutineScope,
    /** The artists the user hid: left out of shared YouTube playlists like everywhere else. */
    private val hidden: () -> HiddenArtists = { HiddenArtists() },
) {
    private class Entry(val state: MutableStateFlow<LinkImport>, var job: Job? = null)

    private val entries = HashMap<String, Entry>()

    private fun keyOf(link: MusicLink, into: Long) = "$link#$into"

    /** The import of [link] (started if needed; a failed one is tried again). [into]: a playlist id, 0 for none. */
    fun open(link: MusicLink, into: Long = 0): StateFlow<LinkImport> = synchronized(entries) {
        val key = keyOf(link, into)
        val e = entries.getOrPut(key) { Entry(MutableStateFlow(LinkImport.Reading)) }
        if (e.job == null || e.state.value is LinkImport.Failed) {
            e.state.value = LinkImport.Reading
            e.job = scope.launch(Dispatchers.IO) { run(link, into, e.state) }
        }
        e.state
    }

    private suspend fun run(link: MusicLink, into: Long, state: MutableStateFlow<LinkImport>) {
        state.value = try {
            read(link)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e)
        }
        val ready = state.value as? LinkImport.Ready ?: return
        // Spotify names songs only: each is looked up on YouTube Music, 4 at a time, filling the list as they come.
        val sem = Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            ready.slots.forEachIndexed { i, slot ->
                if (slot !is LinkImport.Slot.Pending) return@forEachIndexed
                launch {
                    val found = sem.withPermit { runCatching { yt.match(slot.want) }.getOrNull() }
                    state.update { (it as? LinkImport.Ready)?.resolve(i, found) ?: it }
                }
            }
        }
        if (into != 0L) {
            val done = state.value as? LinkImport.Ready ?: return
            val tracks = done.found
            val added = withContext(Dispatchers.IO) { runCatching { if (tracks.isNotEmpty()) playlists.add(into, tracks); tracks.size }.getOrDefault(0) }
            state.update { (it as? LinkImport.Ready)?.copy(added = added) ?: it }
        }
    }

    private fun read(link: MusicLink): LinkImport = when (link) {
        is MusicLink.YtAlbum -> LinkImport.Elsewhere(LinkImport.Target.YtAlbum(link.id))
        is MusicLink.YtArtist -> LinkImport.Elsewhere(LinkImport.Target.YtArtist(link.id))
        is MusicLink.YtVideo -> video(link.id)
        is MusicLink.YtPlaylist -> {
            val pl = try {
                yt.playlist(link.id).let { it.copy(tracks = hidden().keep(it.tracks)) }
            } catch (e: YouTubeException) {
                // A private or deleted playlist: the video it was shared from, if any.
                if (link.videoId == null || e.message == "network") throw e
                null
            }
            when {
                pl != null && pl.tracks.isNotEmpty() -> {
                    val album = pl.tracks.map { it.album }.distinct().singleOrNull()
                    LinkImport.Ready(
                        LinkImport.Origin.YOUTUBE, if (album != null && pl.title == null) LinkImport.Kind.ALBUM else LinkImport.Kind.PLAYLIST,
                        pl.title ?: album, if (album != null) pl.tracks.first().artist else null,
                        pl.tracks.map { LinkImport.Slot.Found(it) }, pl.truncated,
                    )
                }
                link.videoId != null -> video(link.videoId)
                else -> LinkImport.Failed(LinkImport.Failure.EMPTY)
            }
        }
        is MusicLink.Spotify -> spotify(link)
    }

    private fun video(id: String): LinkImport {
        val t = yt.song(id) ?: return LinkImport.Failed(LinkImport.Failure.NOT_FOUND)
        return LinkImport.Ready(LinkImport.Origin.YOUTUBE, LinkImport.Kind.TRACK, t.title, t.artist, listOf(LinkImport.Slot.Found(t)))
    }

    private fun spotify(link: MusicLink.Spotify): LinkImport {
        val e = Spotify.entity(link.kind, link.id)
        if (link.kind == MusicLink.SpotifyKind.ARTIST) {
            // A Spotify artist: the YouTube Music artist of the same name.
            val artist = yt.findArtist(e.title) ?: return LinkImport.Failed(LinkImport.Failure.ARTIST_NOT_FOUND, e.title)
            return LinkImport.Elsewhere(LinkImport.Target.YtArtist(artist.id))
        }
        if (e.tracks.isEmpty()) return LinkImport.Failed(LinkImport.Failure.EMPTY)
        val kind = when (link.kind) {
            MusicLink.SpotifyKind.TRACK -> LinkImport.Kind.TRACK
            MusicLink.SpotifyKind.ALBUM -> LinkImport.Kind.ALBUM
            else -> LinkImport.Kind.PLAYLIST
        }
        return LinkImport.Ready(
            LinkImport.Origin.SPOTIFY, kind, e.title, e.subtitle, e.tracks.map { LinkImport.Slot.Pending(it) },
            truncated = link.kind == MusicLink.SpotifyKind.PLAYLIST && e.tracks.size >= Spotify.EMBED_LIMIT,
        )
    }

    private fun failure(e: Exception): LinkImport.Failed = when {
        e is Spotify.Failure -> LinkImport.Failed(
            when (e.reason) {
                Spotify.Reason.OFFLINE -> LinkImport.Failure.OFFLINE
                Spotify.Reason.NOT_FOUND -> LinkImport.Failure.NOT_FOUND
                Spotify.Reason.REFUSED -> LinkImport.Failure.SPOTIFY_REFUSED
                Spotify.Reason.UNREADABLE -> LinkImport.Failure.SPOTIFY_UNREADABLE
            },
            (e.cause as? app.chudmusic.net.HttpException)?.code?.toString(),
        )
        e is YouTubeException && e.message == "network" -> LinkImport.Failed(LinkImport.Failure.OFFLINE)
        e is YouTubeException && e.message == "not-found" -> LinkImport.Failed(LinkImport.Failure.NOT_FOUND)
        e is java.io.IOException -> LinkImport.Failed(LinkImport.Failure.OFFLINE)
        else -> LinkImport.Failed(LinkImport.Failure.OTHER, e.message ?: e.javaClass.simpleName)
    }
}
