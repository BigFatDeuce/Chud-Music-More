package app.chudmusic.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chudmusic.model.Source
import app.chudmusic.model.Track

// Same three notions as the PC app: saved playlists (only explicit actions change them),
// the play queue (belongs to the player), and the library (tracks found in the chosen places).
// A track row exists for anything a playlist or the history refers to, in the library or not.

@Entity(
    tableName = "tracks",
    indices = [Index("source"), Index("videoId"), Index("sourceId"), Index("album"), Index("artist"), Index("inLibrary"), Index("albumKey"), Index("artistKey")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val source: Source,
    val uri: String,
    val path: String? = null,
    val sourceId: Long? = null,
    val folder: String? = null,
    val videoId: String? = null,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val mtime: Long? = null,
    val coverUri: String? = null,
    val ytArtistId: String? = null,
    val ytAlbumId: String? = null,
    val available: Boolean = true,
    val inLibrary: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    /** Title, artist, album without accents or case: what the search box matches. */
    val searchKey: String = "",
    /** Album identity and main artist (see Library), stored so that SQLite groups them, not the app's memory. */
    val albumKey: String? = null,
    val artistKey: String? = null,
) {
    fun toTrack() = Track(
        id = id, source = source, uri = uri, title = title, artist = artist, album = album, albumArtist = albumArtist,
        durationMs = durationMs, trackNo = trackNo, discNo = discNo, year = year, genre = genre, coverUri = coverUri,
        available = available, path = path, sourceId = sourceId, videoId = videoId, ytArtistId = ytArtistId,
        ytAlbumId = ytAlbumId, mime = mime, sizeBytes = sizeBytes, folder = folder,
    )

    companion object {
        fun from(t: Track, inLibrary: Boolean = false, mtime: Long? = null, now: Long = System.currentTimeMillis()) = TrackEntity(
            id = t.id, source = t.source, uri = t.uri, path = t.path, sourceId = t.sourceId, folder = t.folder, videoId = t.videoId,
            title = t.title, artist = t.artist, album = t.album, albumArtist = t.albumArtist, durationMs = t.durationMs,
            trackNo = t.trackNo, discNo = t.discNo, year = t.year, genre = t.genre, mime = t.mime, sizeBytes = t.sizeBytes,
            mtime = mtime, coverUri = t.coverUri, ytArtistId = t.ytArtistId, ytAlbumId = t.ytAlbumId, available = t.available,
            inLibrary = inLibrary, addedAt = now, searchKey = searchKey(t.title, t.artist, t.album),
            albumKey = app.chudmusic.library.Library.albumKey(t), artistKey = app.chudmusic.library.Library.mainArtist(t.albumArtist ?: t.artist),
        )

        fun searchKey(vararg parts: String?): String = fold(parts.filterNotNull().joinToString(" "))
    }
}

private val MARKS = Regex("\\p{M}+")

/** Lowercase, accents removed: "Électro Café" → "electro cafe". */
fun fold(s: String): String =
    // Plain ASCII (most titles) has nothing to strip: this is called for every track of a library.
    if (s.all { it.code < 128 }) s.lowercase() else java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(MARKS, "").lowercase()

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** A picture chosen by the user (a file of [app.chudmusic.library.CoverStore]); null = the covers of its tracks. */
    val cover: String? = null,
)

/** A playlist made of rules ([app.chudmusic.playlists.SmartRules] as JSON): its tracks are asked to the database each time. */
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rules: String,
    val cover: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** "Recently played": the last tracks listened to, at most [app.chudmusic.stats.Stats.RECENT_MAX]. */
@Entity(tableName = "recent_plays")
data class RecentPlayEntity(@PrimaryKey val trackId: String, val playedAt: Long)

/** The loudness of a track without ReplayGain tags, measured the first time it was listened to. */
@Entity(tableName = "loudness")
data class LoudnessEntity(@PrimaryKey val trackId: String, val lufs: Float, val peak: Float, val measuredAt: Long)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId", "position"), Index("trackId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val trackId: String,
    val addedAt: Long = System.currentTimeMillis(),
)

enum class NetKind { SMB, WEBDAV }

/** A network place added by the user. The password is encrypted with a key of the Android Keystore. */
@Entity(tableName = "network_sources")
data class NetworkSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: NetKind,
    val name: String,
    val host: String,
    val port: Int? = null,
    /** SMB share name; for WebDAV, the base path ("/remote.php/dav/files/me"). */
    val share: String,
    /** Folder inside the share that is part of the library ("" = all of it). */
    val rootPath: String = "",
    val username: String? = null,
    val domain: String? = null,
    val passwordEnc: String? = null,
    val useHttps: Boolean = true,
    val lastScanAt: Long? = null,
    val lastError: String? = null,
    val trackCount: Int = 0,
)

/** Phone folders (as MediaStore reports them) the user left out of the library. */
@Entity(tableName = "excluded_folders")
data class ExcludedFolderEntity(@PrimaryKey val path: String)

/** Tracks removed from the library by the user ("Retirer de la bibliothèque": never from the disk). */
@Entity(tableName = "library_exclusions")
data class ExclusionEntity(@PrimaryKey val trackId: String, val at: Long = System.currentTimeMillis())

@Entity(tableName = "play_history", indices = [Index("trackId"), Index("playedAt")])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
)

/** Seconds really listened, per day, hour and track (radio included, under "radio:<uuid>"). */
@Entity(tableName = "listen_log", primaryKeys = ["day", "hour", "trackId"], indices = [Index("trackId")])
data class ListenLogEntity(
    val day: String,
    val hour: Int,
    val trackId: String,
    val source: Source,
    val seconds: Long,
    /** Station name for radio; title for tracks no longer in the database. */
    val label: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

@Entity(tableName = "radio_stations")
data class RadioStationEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val url: String,
    val favicon: String? = null,
    val country: String? = null,
    val tags: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val hls: Boolean = false,
    val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lastPlayedAt: Long = 0,
)

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val key: String,
    /** "synced", "plain", "instrumental", "none" */
    val status: String,
    val synced: String? = null,
    val plain: String? = null,
    val fetchedAt: Long,
)
