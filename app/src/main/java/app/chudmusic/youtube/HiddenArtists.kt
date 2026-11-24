package app.chudmusic.youtube

import app.chudmusic.model.Track
import kotlinx.serialization.Serializable

/** An artist the user hid from YouTube Music results. [id] is the channel id when it was known. */
@Serializable
data class HiddenArtist(val id: String? = null, val name: String)

/**
 * The artists hidden from YouTube Music lists (search, mixes, artist pages, shared playlists).
 *
 * Matched by channel id when both sides have one, else by name: YouTube often gives a video's
 * artist as plain text without an id, and a song by several artists only carries the first one's
 * id. The name is compared normalized ([InnerTube.norm]) to the whole credit and to each artist
 * of it ([InnerTube.artistsOf]), so hiding "X" also hides "Y feat. X" but not "Xavier".
 *
 * Only lists coming from YouTube are filtered: playlists the user saved and the library are the
 * user's own and stay as they are.
 */
class HiddenArtists(val list: List<HiddenArtist> = emptyList()) {
    private val ids = list.mapNotNullTo(HashSet()) { it.id?.takeIf(String::isNotBlank) }
    // "Name - Topic" and "NameVEVO" are YouTube's auto-generated channels of the same artist.
    private val names = list.mapTo(HashSet()) { InnerTube.norm(it.name.replace(Regex("""vevo$|\s-\s*topic$""", RegexOption.IGNORE_CASE), "")) }
        .apply { removeAll { it.isBlank() } }

    val isEmpty get() = ids.isEmpty() && names.isEmpty()

    fun hides(id: String?, name: String?): Boolean {
        if (id != null && id in ids) return true
        if (name.isNullOrBlank() || names.isEmpty()) return false
        return InnerTube.norm(name) in names || InnerTube.artistsOf(name).any { it in names }
    }

    /**
     * A YouTube track, also when the hidden artist is only named in the title: a guest, "Song
     * (feat. X)", "(avec X)", or an upload by someone else titled "X - Song".
     */
    fun hides(t: Track): Boolean = t.isYoutube && (hides(t.ytArtistId, t.artist) || guestsOf(t.title).any { it in names })
    fun hides(a: YtAlbumItem): Boolean = hides(a.artistId, a.artist)
    fun hides(a: YtArtistItem): Boolean = hides(a.id, a.name)
    fun hides(i: YtItem): Boolean = when (i) {
        is YtItem.Song -> hides(i.track)
        is YtItem.Album -> hides(i.album)
        is YtItem.Artist -> hides(i.artist)
    }

    fun keep(tracks: List<Track>): List<Track> = if (isEmpty) tracks else tracks.filterNot(::hides)

    /** A mix without the hidden artists, except its seed: the song the user chose to start it from. */
    fun keep(mix: YtMix): YtMix =
        if (isEmpty) mix else mix.copy(tracks = mix.tracks.filter { it.videoId == mix.seed?.videoId || !hides(it) })

    /** The shelves of an artist page (albums, related artists, songs) without the hidden ones; empty shelves dropped. */
    fun keep(page: YtArtistPage): YtArtistPage = if (isEmpty) page else page.copy(
        topSongs = if (hides(page.id, page.name)) page.topSongs else keep(page.topSongs),
        shelves = page.shelves.map { s ->
            s.copy(albums = s.albums.filterNot(::hides), artists = s.artists.filterNot(::hides), songs = keep(s.songs))
        }.filter { it.albums.isNotEmpty() || it.artists.isNotEmpty() || it.songs.isNotEmpty() },
    )

    /** The list after hiding [a]. An artist already there (same id, or same name) is not added twice; it gains the id if it had none. */
    fun plus(a: HiddenArtist): List<HiddenArtist> =
        if (list.none { same(it, a) }) list + a else list.map { if (same(it, a)) it.copy(id = it.id ?: a.id) else it }

    /** The list after restoring [a]. */
    fun minus(a: HiddenArtist): List<HiddenArtist> = list.filterNot { same(it, a) }

    private fun same(x: HiddenArtist, y: HiddenArtist) =
        (x.id != null && x.id == y.id) || InnerTube.norm(x.name) == InnerTube.norm(y.name)

    companion object {
        private val GUESTS = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with|avec|con|mit)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)

        /** The artists a title names, normalized: guests in brackets, "Song (feat. A & B)" → a, b; "A - Song" → a. */
        fun guestsOf(title: String): Set<String> {
            val found = GUESTS.findAll(title).flatMapTo(HashSet()) { InnerTube.artistsOf(it.groupValues[1].replace(AND, ",")) }
            title.split(" - ", " – ", limit = 2).takeIf { it.size == 2 }?.let { found += InnerTube.artistsOf(it[0].replace(AND, ",")) }
            return found
        }

        private val AND = Regex("""\s(?:and|et|y|und)\s""", RegexOption.IGNORE_CASE)

        /**
         * The artist the menu of [t] offers to hide: the first one credited, whose channel id is
         * the one a YouTube track carries. null when the track has no artist.
         */
        fun mainArtistOf(t: Track): HiddenArtist? {
            val name = t.artist?.split(", ")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return HiddenArtist(t.ytArtistId, name)
        }
    }
}
