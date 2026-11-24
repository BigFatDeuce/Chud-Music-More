package app.chudmusic.playlists

import androidx.room.withTransaction
import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.NetKind
import app.chudmusic.data.PlaylistEntity
import app.chudmusic.data.PlaylistItemEntity
import app.chudmusic.data.TrackEntity
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.net.NetworkSources
import app.chudmusic.youtube.InnerTube

/**
 * Saved playlists. Only explicit actions change them (playing, shuffling, queueing never do —
 * the rule of the PC app). Duplicates are allowed. Every multi-step write is one transaction.
 */
class Playlists(private val db: ChudDatabase) {
    private val dao = db.playlists()

    data class Entry(val entryId: Long, val track: Track)

    private suspend fun ensure(tracks: List<Track>) {
        for (t in tracks) {
            if (t.source == Source.RADIO) continue
            db.tracks().insertIfAbsent(TrackEntity.from(t, inLibrary = false))
        }
    }

    suspend fun create(name: String, tracks: List<Track> = emptyList()): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = dao.insert(PlaylistEntity(name = name.trim().ifEmpty { "Playlist" }, createdAt = now, updatedAt = now))
        insert(id, tracks.filter { it.source != Source.RADIO }, 0, now)
        id
    }

    private suspend fun insert(id: Long, tracks: List<Track>, index: Int, now: Long) {
        if (tracks.isEmpty()) return
        ensure(tracks)
        dao.shift(id, index, tracks.size)
        dao.insertItems(tracks.mapIndexed { i, t -> PlaylistItemEntity(playlistId = id, position = index + i, trackId = t.id, addedAt = now) })
    }

    suspend fun add(id: Long, tracks: List<Track>, index: Int? = null) = db.withTransaction {
        val count = dao.count(id)
        val now = System.currentTimeMillis()
        insert(id, tracks.filter { it.source != Source.RADIO }, (index ?: count).coerceIn(0, count), now)
        dao.touch(id, now)
    }

    suspend fun rename(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) dao.rename(id, clean, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.delete(id)
        db.tracks().purgeOrphans()
    }

    /** Deletes every playlist (the music files and the library are not touched). */
    suspend fun deleteAll() = db.withTransaction {
        dao.deleteAll()
        db.tracks().purgeOrphans()
    }

    suspend fun remove(id: Long, entryIds: List<Long>) = db.withTransaction {
        dao.deleteEntries(id, entryIds)
        dao.entryIds(id).forEachIndexed { i, e -> dao.setPosition(id, e, i) }
        dao.touch(id, System.currentTimeMillis())
    }

    /** Refuses anything that is not exactly a permutation: a stale screen must never lose entries. */
    suspend fun reorder(id: Long, entryIds: List<Long>) = db.withTransaction {
        val current = dao.entryIds(id)
        require(current.size == entryIds.size && current.toSet() == entryIds.toSet()) { "bad order" }
        entryIds.forEachIndexed { i, e -> dao.setPosition(id, e, i) }
        dao.touch(id, System.currentTimeMillis())
    }

    suspend fun entries(id: Long): List<Entry> = dao.entries(id).map { Entry(it.entryId, it.track.toTrack()) }

    suspend fun tracks(id: Long): List<Track> = entries(id).map { it.track }

    // ── YouTube Music entries → the same song in the library (PC 1.0.0, §33) ──────────────

    data class Swap(val entryId: Long, val from: Track, val to: Track)

    private val altered = Regex("""\b(cover|karaoke|instrumental|nightcore|sped up|slowed|reverb|8d|remix|live|acoustic|acustic\w*|demo|unplugged)\b""", RegexOption.IGNORE_CASE)

    suspend fun localSwaps(id: Long, library: app.chudmusic.library.Library): List<Swap> {
        val ytEntries = entries(id).filter { it.track.source == Source.YOUTUBE }
        if (ytEntries.isEmpty()) return emptyList()
        val titles = ytEntries.mapTo(HashSet()) { InnerTube.norm(it.track.title) }
        val byTitle = library.filter { it.available && it.source != Source.YOUTUBE && InnerTube.norm(it.title) in titles }.groupBy { InnerTube.norm(it.title) }
        return ytEntries.mapNotNull { e ->
            val yt = e.track
            val who = InnerTube.artistsOf(yt.artist ?: "")
            byTitle[InnerTube.norm(yt.title)].orEmpty()
                .filter { altered.find(it.title)?.value?.lowercase() == altered.find(yt.title)?.value?.lowercase() }
                .filter { r -> InnerTube.artistsOf(listOfNotNull(r.artist, r.albumArtist).joinToString(", ")).any { it in who } }
                .map { r -> r to if (yt.durationMs != null && r.durationMs != null) kotlin.math.abs(yt.durationMs - r.durationMs) else 0L }
                .filter { it.second <= 5000 }
                .minByOrNull { it.second }
                ?.let { Swap(e.entryId, yt, it.first) }
        }
    }

    suspend fun applySwaps(id: Long, swaps: List<Swap>) = db.withTransaction {
        for (s in swaps) dao.setTrack(id, s.entryId, s.to.id)
        db.tracks().purgeOrphans()
        dao.touch(id, System.currentTimeMillis())
    }

    // ── Exchange with the PC ────────────────────────────────────────────────────────────────

    /** What the PC can read: UNC path for SMB files (`\\nas\Musique\…`), the file path for phone files. */
    suspend fun exchangePathFn(): (Track) -> String? {
        val sources = db.sources().all().associateBy { it.id }
        return { t ->
            when (t.source) {
                Source.NETWORK -> t.sourceId?.let { sources[it] }?.let { s -> t.path?.let { NetworkSources.exchangePath(s, it) } }
                Source.LOCAL -> t.path
                else -> null
            }
        }
    }

    suspend fun resolver(library: List<Track>): PlaylistExchange.Resolver {
        val smb = db.sources().all().filter { it.kind == NetKind.SMB }
        return object : PlaylistExchange.Resolver {
            override val library = library
            override fun fromUnc(host: String, share: String, rest: String, hint: Track): Track? {
                val s = smb.firstOrNull { it.host.equals(host, true) && it.share.equals(share, true) } ?: return null
                val rel = rest.trim('/')
                return library.firstOrNull { it.sourceId == s.id && it.path.equals(rel, true) }
                    ?: Track(
                        id = "net:${s.id}:$rel", source = Source.NETWORK, uri = NetworkSources.trackUri(s.id, rel),
                        title = hint.title, artist = hint.artist, album = hint.album, durationMs = hint.durationMs, path = rel, sourceId = s.id,
                        folder = rel.substringBeforeLast('/', ""),
                    )
            }
        }
    }

    /** [onCover] keeps the picture a playlist came with (a data: URI) and gives its place in the app. */
    suspend fun import(fileName: String, text: String, library: app.chudmusic.library.Library, onCover: (String) -> String? = { null }): List<PlaylistExchange.Imported> {
        // Only the tracks the file may speak of: a large library is never in memory.
        val wanted = PlaylistExchange.wanted(text)
        val result = PlaylistExchange.parse(fileName, text, resolver(library.filter(wanted)))
        for (p in result) {
            if (p.smart != null) {
                val name = p.name.trim().ifEmpty { "Playlist" }
                // The ones every device starts with, above all: the same rules under the same name are one playlist.
                val there = db.smart().all().any { it.name == name && SmartRules.parse(it.rules)?.copy(excludeIds = emptyList()) == p.smart }
                if (!there) db.smart().insert(app.chudmusic.data.SmartPlaylistEntity(name = name, rules = p.smart.toJson(), cover = p.cover?.let(onCover)))
                continue
            }
            val cover = p.cover?.let(onCover)
            val id = create(p.name, p.tracks)
            if (cover != null) dao.setCover(id, cover, System.currentTimeMillis())
        }
        return result
    }

    /** The user's picture for a playlist, null for the covers of its tracks. */
    suspend fun setCover(id: Long, cover: String?) = dao.setCover(id, cover, System.currentTimeMillis())
}
