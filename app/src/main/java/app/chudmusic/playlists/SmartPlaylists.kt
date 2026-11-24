package app.chudmusic.playlists

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import app.chudmusic.R
import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.Settings
import app.chudmusic.data.SmartPlaylistEntity
import app.chudmusic.data.TracksTotal
import app.chudmusic.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Smart playlists: rules kept in the database, tracks found again each time the library or the plays change. */
class SmartPlaylists(private val db: ChudDatabase, private val settings: Settings) {
    data class Entry(val id: Long, val name: String, val rules: SmartRules, val cover: String?)

    private fun entry(e: SmartPlaylistEntity) = Entry(e.id, e.name, SmartRules.parse(e.rules) ?: SmartRules(), e.cover)

    val list: Flow<List<Entry>> = db.smart().flow().map { l -> l.map(::entry) }

    fun flow(id: Long): Flow<Entry?> = db.smart().flow(id).map { it?.let(::entry) }

    /** Up to date as tracks are played, added or removed; the day limits move on by themselves. */
    fun tracks(rules: SmartRules): Flow<List<Track>> = ticks().flatMapLatest { now ->
        // A scan writes many times a second: at most one refresh every second and a half.
        db.tracks().tracksFlow(query(rules, now)).conflate()
            .let { f -> flow { f.collect { emit(it.map { e -> e.toTrack() }); delay(1500) } } }
            .distinctUntilChanged()
    }

    suspend fun tracksNow(rules: SmartRules): List<Track> =
        db.tracks().tracksNow(query(rules, System.currentTimeMillis())).map { it.toTrack() }

    suspend fun tracksNow(id: Long): List<Track> = db.smart().get(id)?.let { tracksNow(entry(it).rules) }.orEmpty()

    private fun query(rules: SmartRules, now: Long): SimpleSQLiteQuery = rules.sql(now).let { SimpleSQLiteQuery(it.text, it.args.toTypedArray()) }

    /** How many tracks and how long, kept up to date like [tracks]. */
    fun totals(rules: SmartRules): Flow<TracksTotal> = ticks().flatMapLatest { now ->
        val q = rules.sql(now)
        db.tracks().totalsFlow(SimpleSQLiteQuery(q.totals, q.args.toTypedArray())).conflate()
            .let { f -> flow { f.collect { emit(it); delay(1500) } } }
            .distinctUntilChanged()
    }

    suspend fun covers(rules: SmartRules): List<String> {
        val q = rules.sql(System.currentTimeMillis())
        return db.tracks().strings(SimpleSQLiteQuery(q.covers, q.args.toTypedArray()))
    }

    private fun ticks(): Flow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(3600_000) }
    }

    suspend fun create(name: String, rules: SmartRules, cover: String? = null): Long =
        db.smart().insert(SmartPlaylistEntity(name = name.trim().ifEmpty { "Playlist" }, rules = rules.toJson(), cover = cover))

    suspend fun save(id: Long, name: String, rules: SmartRules) =
        db.smart().save(id, name.trim().ifEmpty { "Playlist" }, rules.toJson(), System.currentTimeMillis())

    suspend fun exclude(id: Long, trackId: String, excluded: Boolean) {
        val e = db.smart().get(id)?.let(::entry) ?: return
        val ids = if (excluded) e.rules.excludeIds - trackId + trackId else e.rules.excludeIds - trackId
        save(id, e.name, e.rules.copy(excludeIds = ids))
    }

    suspend fun delete(id: Long) = db.smart().delete(id)

    /** The four ready-made lists, once: deleted by the user, they do not come back. */
    suspend fun seed(context: Context) {
        if (settings.smartSeeded()) return
        settings.setSmartSeeded()
        if (db.smart().all().isNotEmpty()) return
        create(context.getString(R.string.smart_never), SmartRules.neverPlayed())
        create(context.getString(R.string.smart_rediscover), SmartRules.rediscover())
        create(context.getString(R.string.smart_recent), SmartRules.recentlyAdded())
        create(context.getString(R.string.smart_most), SmartRules.mostPlayed())
    }
}
