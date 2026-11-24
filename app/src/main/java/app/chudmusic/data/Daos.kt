package app.chudmusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.chudmusic.model.Source
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    // ── The library, never read as a whole (see Library): counts, groups and pages ──────────

    @Query(
        """SELECT albumKey AS `key`, MIN(album) AS title, MAX(albumArtist) AS albumArtist, MAX(year) AS year, MAX(coverUri) AS cover,
           COUNT(*) AS count, COALESCE(SUM(durationMs), 0) AS durationMs
           FROM tracks WHERE inLibrary = 1 AND albumKey IS NOT NULL GROUP BY albumKey""",
    )
    suspend fun albumRows(): List<AlbumRow>

    /** Artists of the albums without an album artist: the most frequent one names the album. */
    @Query(
        """SELECT albumKey AS `key`, artist, COUNT(*) AS count FROM tracks
           WHERE inLibrary = 1 AND albumKey IS NOT NULL AND albumArtist IS NULL AND artist IS NOT NULL GROUP BY albumKey, artist""",
    )
    suspend fun albumArtistRows(): List<AlbumArtistRow>

    @Query("SELECT artistKey AS name, COUNT(*) AS count, MAX(coverUri) AS cover FROM tracks WHERE inLibrary = 1 AND artistKey IS NOT NULL GROUP BY artistKey")
    suspend fun artistRows(): List<ArtistRow>

    @Query("SELECT COUNT(*) FROM tracks WHERE inLibrary = 1")
    suspend fun libraryCountNow(): Int

    @Query("SELECT * FROM tracks WHERE inLibrary = 1 AND albumKey = :key")
    suspend fun byAlbum(key: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE inLibrary = 1 AND albumKey = :key")
    fun byAlbumFlow(key: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE inLibrary = 1 AND artistKey = :name")
    suspend fun byArtist(name: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE inLibrary = 1 AND artistKey = :name")
    fun byArtistFlow(name: String): Flow<List<TrackEntity>>

    /** Row numbers only ([app.chudmusic.library.LibraryQuery]): a list of 300,000 tracks fits in a few megabytes. */
    @androidx.room.RawQuery
    suspend fun rowIds(query: androidx.sqlite.db.SupportSQLiteQuery): List<Long>

    @androidx.room.RawQuery
    suspend fun strings(query: androidx.sqlite.db.SupportSQLiteQuery): List<String>

    @Query("SELECT rowid AS rowNo, * FROM tracks WHERE rowid IN (:rows)")
    suspend fun byRows(rows: List<Long>): List<NumberedTrack>

    /** The library page by page, to look through all of it without holding it. */
    @Query("SELECT rowid AS rowNo, * FROM tracks WHERE inLibrary = 1 AND rowid > :after ORDER BY rowid LIMIT :n")
    suspend fun libraryPage(after: Long, n: Int): List<NumberedTrack>

    @Query("SELECT id FROM tracks WHERE inLibrary = 1")
    suspend fun libraryIds(): List<String>

    @Query("SELECT COUNT(*) FROM tracks WHERE id = :id AND inLibrary = 1")
    fun inLibraryFlow(id: String): Flow<Int>

    @androidx.room.RawQuery(observedEntities = [TrackEntity::class])
    fun tracksFlow(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<List<TrackEntity>>

    @androidx.room.RawQuery(observedEntities = [TrackEntity::class])
    fun totalsFlow(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<TracksTotal>

    @androidx.room.RawQuery
    suspend fun tracksNow(query: androidx.sqlite.db.SupportSQLiteQuery): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<TrackEntity>

    /** What a scan compares files with: no title, no tags, so a huge library stays small in memory. */
    @Query("SELECT id, path, sizeBytes, mtime, available, inLibrary, coverUri, addedAt, lastPlayedAt, playCount, durationMs FROM tracks WHERE source = :source")
    suspend fun knownBySource(source: Source): List<KnownFile>

    @Query("SELECT id, path, sizeBytes, mtime, available, inLibrary, coverUri, addedAt, lastPlayedAt, playCount, durationMs FROM tracks WHERE sourceId = :sourceId")
    suspend fun knownByNetworkSource(sourceId: Long): List<KnownFile>

    @Query("SELECT id FROM tracks WHERE sourceId = :sourceId")
    suspend fun idsOfNetworkSource(sourceId: Long): List<String>

    @Query("UPDATE tracks SET inLibrary = 1 WHERE id IN (:ids)")
    suspend fun joinLibrary(ids: List<String>)

    @Upsert suspend fun upsert(tracks: List<TrackEntity>)

    /** Keeps an existing row (its play count, its date added); used when a playlist refers to a track. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(t: TrackEntity): Long

    @Query("UPDATE tracks SET inLibrary = 0 WHERE id IN (:ids)")
    suspend fun leaveLibrary(ids: List<String>)

    @Query("UPDATE tracks SET available = :available WHERE id IN (:ids)")
    suspend fun setAvailable(ids: List<String>, available: Boolean)

    @Query("UPDATE tracks SET lastPlayedAt = :at, playCount = playCount + 1 WHERE id = :id")
    suspend fun notePlayed(id: String, at: Long)

    @Query("UPDATE tracks SET coverUri = :cover WHERE id IN (:ids)")
    suspend fun setCover(ids: List<String>, cover: String?)

    /** Rows nothing refers to any more (not in the library, no playlist, no history). */
    @Query("DELETE FROM tracks WHERE inLibrary = 0 AND id NOT IN (SELECT trackId FROM playlist_items) AND id NOT IN (SELECT trackId FROM play_history)")
    suspend fun purgeOrphans()

    @Query("SELECT COUNT(*) FROM tracks WHERE inLibrary = 1")
    fun libraryCount(): Flow<Int>

    @Query("SELECT t.* FROM recent_plays r JOIN tracks t ON t.id = r.trackId ORDER BY r.playedAt DESC")
    fun recentlyPlayed(): Flow<List<TrackEntity>>


    @Query("SELECT * FROM tracks WHERE inLibrary = 1 ORDER BY addedAt DESC LIMIT :n")
    fun recentlyAdded(n: Int): Flow<List<TrackEntity>>
}

data class TrackHistoryRow(val plays: Int, val firstAt: Long?, val lastAt: Long?)

data class PlaylistSummaryRow(val id: Long, val name: String, val updatedAt: Long, val cover: String?, val count: Int, val durationMs: Long)

data class AlbumRow(val key: String, val title: String, val albumArtist: String?, val year: Int?, val cover: String?, val count: Int, val durationMs: Long)
data class AlbumArtistRow(val key: String, val artist: String, val count: Int)
data class ArtistRow(val name: String, val count: Int, val cover: String?)

/** A track with its row number in the table, which lists use instead of the track itself. */
data class NumberedTrack(val rowNo: Long, @androidx.room.Embedded val track: TrackEntity)

/** What is already known of a file, for a scan (see [TrackDao.knownBySource]). */
data class KnownFile(
    val id: String,
    val path: String?,
    val sizeBytes: Long?,
    val mtime: Long?,
    val available: Boolean,
    val inLibrary: Boolean,
    val coverUri: String?,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val playCount: Int,
    val durationMs: Long?,
)

data class PlaylistTrackRow(
    val entryId: Long,
    val position: Int,
    @androidx.room.Embedded(prefix = "t_") val track: TrackEntity,
)

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.updatedAt, p.cover, COUNT(i.id) AS count, COALESCE(SUM(t.durationMs), 0) AS durationMs
           FROM playlists p LEFT JOIN playlist_items i ON i.playlistId = p.id LEFT JOIN tracks t ON t.id = i.trackId
           GROUP BY p.id ORDER BY p.name COLLATE NOCASE""",
    )
    fun summaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun flow(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT i.id AS entryId, i.position AS position,
           t.id AS t_id, t.source AS t_source, t.uri AS t_uri, t.path AS t_path, t.sourceId AS t_sourceId, t.folder AS t_folder,
           t.videoId AS t_videoId, t.title AS t_title, t.artist AS t_artist, t.album AS t_album, t.albumArtist AS t_albumArtist,
           t.durationMs AS t_durationMs, t.trackNo AS t_trackNo, t.discNo AS t_discNo, t.year AS t_year, t.genre AS t_genre,
           t.mime AS t_mime, t.sizeBytes AS t_sizeBytes, t.mtime AS t_mtime, t.coverUri AS t_coverUri, t.ytArtistId AS t_ytArtistId,
           t.ytAlbumId AS t_ytAlbumId, t.available AS t_available, t.inLibrary AS t_inLibrary, t.addedAt AS t_addedAt,
           t.lastPlayedAt AS t_lastPlayedAt, t.playCount AS t_playCount, t.searchKey AS t_searchKey,
           t.albumKey AS t_albumKey, t.artistKey AS t_artistKey
           FROM playlist_items i JOIN tracks t ON t.id = i.trackId WHERE i.playlistId = :id ORDER BY i.position""",
    )
    fun entriesFlow(id: Long): Flow<List<PlaylistTrackRow>>

    @Query(
        """SELECT i.id AS entryId, i.position AS position,
           t.id AS t_id, t.source AS t_source, t.uri AS t_uri, t.path AS t_path, t.sourceId AS t_sourceId, t.folder AS t_folder,
           t.videoId AS t_videoId, t.title AS t_title, t.artist AS t_artist, t.album AS t_album, t.albumArtist AS t_albumArtist,
           t.durationMs AS t_durationMs, t.trackNo AS t_trackNo, t.discNo AS t_discNo, t.year AS t_year, t.genre AS t_genre,
           t.mime AS t_mime, t.sizeBytes AS t_sizeBytes, t.mtime AS t_mtime, t.coverUri AS t_coverUri, t.ytArtistId AS t_ytArtistId,
           t.ytAlbumId AS t_ytAlbumId, t.available AS t_available, t.inLibrary AS t_inLibrary, t.addedAt AS t_addedAt,
           t.lastPlayedAt AS t_lastPlayedAt, t.playCount AS t_playCount, t.searchKey AS t_searchKey,
           t.albumKey AS t_albumKey, t.artistKey AS t_artistKey
           FROM playlist_items i JOIN tracks t ON t.id = i.trackId WHERE i.playlistId = :id ORDER BY i.position""",
    )
    suspend fun entries(id: Long): List<PlaylistTrackRow>

    @Query("SELECT DISTINCT t.coverUri FROM playlist_items i JOIN tracks t ON t.id = i.trackId WHERE i.playlistId = :id AND t.coverUri IS NOT NULL LIMIT 40")
    suspend fun covers(id: Long): List<String>

    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Insert suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("UPDATE playlists SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long)

    @Query("UPDATE playlists SET cover = :cover, updatedAt = :at WHERE id = :id")
    suspend fun setCover(id: Long, cover: String?, at: Long)

    @Query("UPDATE playlists SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    /** Every playlist; their entries go with them (foreign key, cascade). */
    @Query("DELETE FROM playlists")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :id")
    suspend fun count(id: Long): Int

    @Query("UPDATE playlist_items SET position = position + :n WHERE playlistId = :id AND position >= :from")
    suspend fun shift(id: Long, from: Int, n: Int)

    @Query("DELETE FROM playlist_items WHERE id IN (:entryIds) AND playlistId = :id")
    suspend fun deleteEntries(id: Long, entryIds: List<Long>)

    @Query("SELECT id FROM playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun entryIds(id: Long): List<Long>

    @Query("UPDATE playlist_items SET position = :pos WHERE id = :entryId AND playlistId = :id")
    suspend fun setPosition(id: Long, entryId: Long, pos: Int)

    @Query("UPDATE playlist_items SET trackId = :trackId WHERE id = :entryId AND playlistId = :id")
    suspend fun setTrack(id: Long, entryId: Long, trackId: String)

    /** A file moved or renamed: every playlist that held it follows it. */
    @Query("UPDATE playlist_items SET trackId = :to WHERE trackId = :from")
    suspend fun relink(from: String, to: String)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE trackId = :trackId")
    suspend fun usage(trackId: String): Int
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM network_sources ORDER BY name COLLATE NOCASE")
    fun flow(): Flow<List<NetworkSourceEntity>>

    @Query("SELECT * FROM network_sources")
    suspend fun all(): List<NetworkSourceEntity>

    @Query("SELECT * FROM network_sources WHERE id = :id")
    suspend fun get(id: Long): NetworkSourceEntity?

    @Upsert suspend fun upsert(s: NetworkSourceEntity): Long

    @Query("DELETE FROM network_sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE network_sources SET lastScanAt = :at, lastError = :error, trackCount = :count WHERE id = :id")
    suspend fun scanned(id: Long, at: Long, error: String?, count: Int)

    @Query("SELECT * FROM excluded_folders")
    fun excludedFolders(): Flow<List<ExcludedFolderEntity>>

    @Query("SELECT path FROM excluded_folders")
    suspend fun excludedFolderPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun excludeFolder(f: ExcludedFolderEntity)

    @Query("DELETE FROM excluded_folders WHERE path = :path")
    suspend fun includeFolder(path: String)

    @Query("SELECT trackId FROM library_exclusions")
    suspend fun exclusions(): List<String>

    @Query("SELECT COUNT(*) FROM library_exclusions")
    fun exclusionCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun exclude(e: List<ExclusionEntity>)

    @Query("DELETE FROM library_exclusions WHERE trackId IN (:ids)")
    suspend fun unexclude(ids: List<String>)

    @Query("DELETE FROM library_exclusions")
    suspend fun clearExclusions()
}

data class CountRow(val k: String?, val label: String?, val artist: String?, val seconds: Long, val plays: Int)
data class BucketRow(val bucket: Int, val seconds: Long)
data class SourceRow(val source: Source, val seconds: Long)

@Dao
interface StatsDao {
    @Insert suspend fun insertHistory(h: PlayHistoryEntity)

    @Query(
        """INSERT INTO listen_log (day, hour, trackId, source, seconds, label, artist, album) VALUES (:day, :hour, :trackId, :source, :seconds, :label, :artist, :album)
           ON CONFLICT(day, hour, trackId) DO UPDATE SET seconds = seconds + :seconds""",
    )
    suspend fun addListen(day: String, hour: Int, trackId: String, source: Source, seconds: Long, label: String?, artist: String?, album: String?)

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM listen_log WHERE day >= :from")
    suspend fun totalSeconds(from: String): Long

    @Query("SELECT COUNT(*) FROM play_history WHERE playedAt >= :from")
    suspend fun plays(from: Long): Int

    @Query("SELECT COUNT(DISTINCT trackId) FROM play_history WHERE playedAt >= :from")
    suspend fun distinctTracks(from: Long): Int

    @Query("SELECT DISTINCT day FROM listen_log WHERE seconds >= 30 ORDER BY day")
    suspend fun listeningDays(): List<String>

    @Query(
        """SELECT l.trackId AS k, COALESCE(t.title, l.label) AS label, COALESCE(t.artist, l.artist) AS artist,
           SUM(l.seconds) AS seconds, (SELECT COUNT(*) FROM play_history h WHERE h.trackId = l.trackId AND h.playedAt >= :fromMs) AS plays
           FROM listen_log l LEFT JOIN tracks t ON t.id = l.trackId WHERE l.day >= :from AND l.source != 'RADIO'
           GROUP BY l.trackId ORDER BY plays DESC, seconds DESC LIMIT :n""",
    )
    suspend fun topTracks(from: String, fromMs: Long, n: Int): List<CountRow>

    @Query(
        """SELECT COALESCE(t.artist, l.artist) AS k, COALESCE(t.artist, l.artist) AS label, NULL AS artist, SUM(l.seconds) AS seconds, 0 AS plays
           FROM listen_log l LEFT JOIN tracks t ON t.id = l.trackId WHERE l.day >= :from AND l.source != 'RADIO' AND COALESCE(t.artist, l.artist) IS NOT NULL
           GROUP BY k ORDER BY seconds DESC LIMIT :n""",
    )
    suspend fun topArtists(from: String, n: Int): List<CountRow>

    @Query(
        """SELECT COALESCE(t.album, l.album) AS k, COALESCE(t.album, l.album) AS label, COALESCE(t.albumArtist, t.artist, l.artist) AS artist, SUM(l.seconds) AS seconds, 0 AS plays
           FROM listen_log l LEFT JOIN tracks t ON t.id = l.trackId WHERE l.day >= :from AND l.source != 'RADIO' AND COALESCE(t.album, l.album) IS NOT NULL
           GROUP BY k ORDER BY seconds DESC LIMIT :n""",
    )
    suspend fun topAlbums(from: String, n: Int): List<CountRow>

    @Query(
        """SELECT trackId AS k, label, NULL AS artist, SUM(seconds) AS seconds, 0 AS plays FROM listen_log
           WHERE day >= :from AND source = 'RADIO' GROUP BY trackId ORDER BY seconds DESC LIMIT :n""",
    )
    suspend fun topRadios(from: String, n: Int): List<CountRow>

    @Query("SELECT hour AS bucket, SUM(seconds) AS seconds FROM listen_log WHERE day >= :from GROUP BY hour")
    suspend fun byHour(from: String): List<BucketRow>

    @Query("SELECT day, SUM(seconds) AS seconds FROM listen_log WHERE day >= :from GROUP BY day")
    suspend fun byDay(from: String): List<DayRow>

    @Query("SELECT source, SUM(seconds) AS seconds FROM listen_log WHERE day >= :from GROUP BY source")
    suspend fun bySource(from: String): List<SourceRow>

    /** Tracks whose first listen falls in the period. */
    @Query("SELECT COUNT(*) FROM (SELECT trackId, MIN(playedAt) AS firstAt FROM play_history GROUP BY trackId) WHERE firstAt >= :fromMs")
    suspend fun discoveries(fromMs: Long): Int

    @Query("SELECT COUNT(*) AS plays, MIN(playedAt) AS firstAt, MAX(playedAt) AS lastAt FROM play_history WHERE trackId = :id")
    suspend fun trackHistory(id: String): TrackHistoryRow

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM listen_log WHERE trackId = :id")
    suspend fun trackSeconds(id: String): Long

    // ── "Recently played" (see Stats.notePlay) ─────────────────────────────────────────────

    @Upsert suspend fun putRecent(r: RecentPlayEntity)

    /** The oldest ones go away by themselves. */
    @Query("DELETE FROM recent_plays WHERE trackId NOT IN (SELECT trackId FROM recent_plays ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trimRecent(keep: Int)

    @Query("SELECT * FROM recent_plays")
    suspend fun recent(): List<RecentPlayEntity>

    @Query("SELECT * FROM recent_plays WHERE trackId = :id")
    suspend fun recent(id: String): RecentPlayEntity?

    @Query("DELETE FROM recent_plays WHERE trackId = :id")
    suspend fun forgetRecent(id: String)

    @Query("DELETE FROM recent_plays")
    suspend fun clearRecent()

    @Query("SELECT trackId FROM play_history ORDER BY playedAt DESC LIMIT :n")
    suspend fun recentHistory(n: Int): List<String>
}

data class DayRow(val day: String, val seconds: Long)

@Dao
interface SmartDao {
    @Query("SELECT * FROM smart_playlists ORDER BY name COLLATE NOCASE")
    fun flow(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<SmartPlaylistEntity>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    fun flow(id: Long): Flow<SmartPlaylistEntity?>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    suspend fun get(id: Long): SmartPlaylistEntity?

    @Insert suspend fun insert(p: SmartPlaylistEntity): Long

    @Query("UPDATE smart_playlists SET name = :name, rules = :rules, updatedAt = :at WHERE id = :id")
    suspend fun save(id: Long, name: String, rules: String, at: Long)

    @Query("UPDATE smart_playlists SET cover = :cover, updatedAt = :at WHERE id = :id")
    suspend fun setCover(id: Long, cover: String?, at: Long)

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM loudness WHERE trackId = :id")
    suspend fun get(id: String): LoudnessEntity?

    @Upsert suspend fun put(e: LoudnessEntity)
}

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_stations WHERE favorite = 1 ORDER BY name COLLATE NOCASE")
    fun favorites(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE uuid = :uuid")
    suspend fun get(uuid: String): RadioStationEntity?

    @Upsert suspend fun upsert(s: RadioStationEntity)

    @Query("UPDATE radio_stations SET favorite = :fav WHERE uuid = :uuid")
    suspend fun setFavorite(uuid: String, fav: Boolean)

    @Query("SELECT * FROM radio_stations WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :n")
    fun recent(n: Int): Flow<List<RadioStationEntity>>

    @Query("UPDATE radio_stations SET lastPlayedAt = :at WHERE uuid = :uuid")
    suspend fun touch(uuid: String, at: Long)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE `key` = :key")
    suspend fun get(key: String): LyricsCacheEntity?

    @Upsert suspend fun put(e: LyricsCacheEntity)
}

/** Groups the multi-step writes the DAOs cannot express alone. */
@Dao
abstract class TxDao {
    @Transaction
    open suspend fun run(block: suspend () -> Unit) = block()
}

/** A number of tracks and how long they last together. */
data class TracksTotal(val count: Int, val durationMs: Long)
