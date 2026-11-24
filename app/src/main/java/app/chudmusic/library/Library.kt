package app.chudmusic.library

import androidx.room.InvalidationTracker
import app.chudmusic.data.AlbumArtistRow
import app.chudmusic.data.AlbumRow
import app.chudmusic.data.ArtistRow
import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.fold
import app.chudmusic.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/** An album as lists show it; its tracks are asked to [Library.albumTracks] when they are needed. */
data class Album(
    val key: String,
    val title: String,
    val artist: String?,
    val year: Int?,
    val cover: String?,
    val count: Int,
    val durationMs: Long,
)

data class Artist(val name: String, val count: Int, val albums: List<Album>, val cover: String?)

/** A stable pseudo-random rank for a track: FNV-1a of the seed and its id (the PC app's formula). */
internal fun randomRank(seed: Int, id: String): Long {
    var h = 0x811c9dc5.toInt() xor seed
    for (ch in id) h = (h xor ch.code) * 0x01000193
    return h.toLong() and 0xFFFFFFFFL
}

/**
 * The library as the UI and Android Auto see it. Tracks are never all in memory: the database
 * counts and groups them, lists read them page by page ([PagedTracks]), so that a NAS of several
 * hundred thousand files opens like a phone of a few hundred.
 */
class Library(private val db: ChudDatabase, scope: CoroutineScope) {
    /**
     * Albums and artists (names, counts, covers) and the number of tracks. [version] changes every
     * time the tracks may have changed: whoever shows a page of them reads it again.
     */
    data class Snapshot(
        val loaded: Boolean = false,
        val version: Int = 0,
        val count: Int = 0,
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
    ) {
        val albumByKey: Map<String, Album> by lazy { albums.associateBy { it.key } }
        val artistByName: Map<String, Artist> by lazy { artists.associateBy { it.name } }
    }

    private var version = 0

    // A scan writes batch after batch: one refresh covers all the writes of the last moment.
    val state: StateFlow<Snapshot> = db.invalidationTracker.createFlow("tracks").conflate()
        .transform {
            emit(read())
            delay(REFRESH_MS)
        }
        .stateIn(scope, SharingStarted.Eagerly, Snapshot())

    private suspend fun read(): Snapshot {
        val dao = db.tracks()
        val albums = albumsOf(dao.albumRows(), dao.albumArtistRows())
        return Snapshot(true, ++version, dao.libraryCountNow(), albums, artistsOf(dao.artistRows(), albums))
    }

    suspend fun albumTracks(key: String): List<Track> = db.tracks().byAlbum(key).map { it.toTrack() }.sortedWith(albumOrder)

    suspend fun artistTracks(name: String): List<Track> = db.tracks().byArtist(name).map { it.toTrack() }.sortedWith(artistOrder)

    /** The library tracks whose title, artist or album hold every word of [q]. */
    suspend fun search(q: String, limit: Int): List<Track> =
        db.tracks().tracksNow(LibraryQuery(q).tracks(limit)).map { it.toTrack() }

    /** Row numbers of the tracks of [q], sorted by title: the content of a [PagedTracks]. */
    suspend fun rows(q: LibraryQuery): LongArray = db.tracks().rowIds(q.rowIds()).toLongArray()

    /** The tracks of [rows], in that order (those gone meanwhile are left out). */
    suspend fun tracks(rows: List<Long>): List<Track> {
        val found = HashMap<Long, Track>(rows.size * 2)
        for (part in rows.chunked(500)) db.tracks().byRows(part).forEach { found[it.rowNo] = it.track.toTrack() }
        return rows.mapNotNull { found[it] }
    }

    suspend fun byRow(rows: List<Long>): Map<Long, Track> =
        rows.chunked(500).flatMap { db.tracks().byRows(it) }.associate { it.rowNo to it.track.toTrack() }

    /** The same [n] tracks for a [seed] whatever is added or removed meanwhile (Home's "Random"). */
    suspend fun random(seed: Int, n: Int): List<Track> {
        val ids = randomPick(db.tracks().libraryIds(), seed, n) { it }
        val byId = db.tracks().getAll(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toTrack() }.also { lastRandom = it }
    }

    /** The last answer of [random]: what Home shows at once when it comes back. */
    @Volatile var lastRandom: List<Track> = emptyList()
        private set

    /** The last added albums as Home last saw them, for the same reason. */
    @Volatile var lastAdded: List<Album> = emptyList()

    /** The library tracks [keep] accepts, looked for page by page. */
    suspend fun filter(keep: (Track) -> Boolean): List<Track> {
        val out = mutableListOf<Track>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = db.tracks().libraryPage(after, PAGE)
            if (page.isEmpty()) return out
            page.mapTo(ArrayList()) { it.track.toTrack() }.filterTo(out, keep)
            after = page.last().rowNo
        }
    }

    companion object {
        private const val REFRESH_MS = 1500L
        private const val PAGE = 1000

        /**
         * The [n] items of smallest [randomRank] for [seed]: the same draw as long as the seed
         * stays, whatever is added or removed meanwhile.
         */
        fun <T> randomPick(items: List<T>, seed: Int, n: Int, id: (T) -> String): List<T> =
            items.map { it to randomRank(seed, id(it)) }.sortedBy { it.second }.take(n).map { it.first }

        /**
         * Album identity = album artist (or artist) + title + folder: two "Reanimation" albums in two
         * folders stay two albums (PC bug §29); "CD1"/"Disc 2" folders count as their parent.
         */
        fun albumKey(t: Track): String? {
            val album = t.album ?: return null
            val who = fold(t.albumArtist ?: "")
            return "${fold(album)}\u0000$who\u0000${TagReader.albumFolder(t.folder) ?: t.sourceId ?: ""}"
        }

        val albumOrder = compareBy<Track>({ it.discNo ?: 1 }, { it.trackNo ?: Int.MAX_VALUE }, { fold(it.title) })
        val artistOrder = compareBy<Track>({ fold(it.album ?: "") }, { it.discNo ?: 1 }, { it.trackNo ?: 0 })

        /** Albums of a short list of tracks (Home's "Recently added"). */
        fun albumsOf(tracks: List<Track>): List<Album> = tracks.groupBy { albumKey(it) }
            .filterKeys { it != null }
            .map { (key, list) ->
                val artists = list.mapNotNull { it.albumArtist ?: it.artist }.groupingBy { it }.eachCount()
                val artist = list.firstNotNullOfOrNull { it.albumArtist } ?: artists.maxByOrNull { it.value }?.key
                Album(
                    key!!, list.sortedWith(albumOrder).first().album!!, artist, list.mapNotNull { it.year }.maxOrNull(),
                    list.firstNotNullOfOrNull { it.coverUri }, list.size, list.sumOf { it.durationMs ?: 0 },
                )
            }
            .sortedBy { fold(it.title) }

        fun albumsOf(rows: List<AlbumRow>, artists: List<AlbumArtistRow>): List<Album> {
            val most = HashMap<String, AlbumArtistRow>()
            for (a in artists) most.merge(a.key, a) { x, y -> if (y.count > x.count) y else x }
            return rows.map { Album(it.key, it.title, it.albumArtist ?: most[it.key]?.artist, it.year, it.cover, it.count, it.durationMs) }
                .map { fold(it.title) to it }.sortedBy { it.first }.map { it.second }
        }

        fun artistsOf(rows: List<ArtistRow>, albums: List<Album>): List<Artist> {
            val byArtist = albums.groupBy { mainArtist(it.artist) ?: "" }
            return rows.map { r ->
                val al = byArtist[r.name].orEmpty()
                Artist(r.name, r.count, al, al.firstNotNullOfOrNull { it.cover } ?: r.cover)
            }.map { fold(it.name) to it }.sortedBy { it.first }.map { it.second }
        }

        private val ARTIST_SPLIT = Regex("""\s*(?:,|;|&|/|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)

        /** "A, B & C feat. D" → "A": the main artist (same rule as the PC, lib/artist.ts). */
        fun mainArtist(s: String?): String? = s?.split(ARTIST_SPLIT)?.firstOrNull()?.trim()?.ifBlank { null }
    }
}
