package app.chudmusic.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.chudmusic.library.Library
import app.chudmusic.model.Source
import app.chudmusic.model.Track

class Converters {
    @TypeConverter fun sourceToString(s: Source) = s.name
    @TypeConverter fun stringToSource(s: String) = Source.valueOf(s)
    @TypeConverter fun kindToString(k: NetKind) = k.name
    @TypeConverter fun stringToKind(s: String) = NetKind.valueOf(s)
}

@Database(
    entities = [
        TrackEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, NetworkSourceEntity::class,
        ExcludedFolderEntity::class, ExclusionEntity::class, PlayHistoryEntity::class, ListenLogEntity::class,
        RadioStationEntity::class, LyricsCacheEntity::class, SmartPlaylistEntity::class, RecentPlayEntity::class, LoudnessEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ChudDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun sources(): SourceDao
    abstract fun stats(): StatsDao
    abstract fun radio(): RadioDao
    abstract fun lyrics(): LyricsDao
    abstract fun smart(): SmartDao
    abstract fun loudness(): LoudnessDao

    companion object {
        /** 0.4.0: playlist covers, smart playlists, the 10 recent plays, measured loudness, albums and artists grouped by SQLite. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN cover TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN albumKey TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN artistKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_albumKey ON tracks (albumKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_artistKey ON tracks (artistKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS smart_playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, rules TEXT NOT NULL, cover TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS recent_plays (trackId TEXT NOT NULL, playedAt INTEGER NOT NULL, PRIMARY KEY(trackId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS loudness (trackId TEXT NOT NULL, lufs REAL NOT NULL, peak REAL NOT NULL, measuredAt INTEGER NOT NULL, PRIMARY KEY(trackId))")
                // What "Recently played" showed stays there, cut to its new length.
                db.execSQL("INSERT INTO recent_plays SELECT id, lastPlayedAt FROM tracks WHERE lastPlayedAt IS NOT NULL AND source != 'RADIO' ORDER BY lastPlayedAt DESC LIMIT ${app.chudmusic.stats.Stats.RECENT_MAX}")
                // 0.3.3 forgot the date of a track taken out of "Recently played"; the history still has it.
                db.execSQL("UPDATE tracks SET lastPlayedAt = (SELECT MAX(playedAt) FROM play_history h WHERE h.trackId = tracks.id) WHERE lastPlayedAt IS NULL AND playCount > 0")
                val update = db.compileStatement("UPDATE tracks SET albumKey = ?, artistKey = ? WHERE id = ?")
                db.query("SELECT id, album, albumArtist, artist, folder, sourceId FROM tracks").use { c ->
                    fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
                    while (c.moveToNext()) {
                        val t = Track(
                            id = c.getString(0), source = Source.LOCAL, uri = "", title = "", album = str(1), albumArtist = str(2), artist = str(3),
                            folder = str(4), sourceId = if (c.isNull(5)) null else c.getLong(5),
                        )
                        update.clearBindings()
                        Library.albumKey(t)?.let { update.bindString(1, it) } ?: update.bindNull(1)
                        Library.mainArtist(t.albumArtist ?: t.artist)?.let { update.bindString(2, it) } ?: update.bindNull(2)
                        update.bindString(3, t.id)
                        update.executeUpdateDelete()
                    }
                }
            }
        }

        fun open(context: Context, name: String = "chud.db"): ChudDatabase =
            Room.databaseBuilder(context, ChudDatabase::class.java, name)
                // WAL: a crash or a kill never loses a playlist write (same rule as the PC app).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
