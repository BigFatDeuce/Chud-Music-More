package app.chudmusic.stats

import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.CountRow
import app.chudmusic.data.PlayHistoryEntity
import app.chudmusic.data.TrackEntity
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Listening statistics, same content as the PC's Statistics page (§21). */
class Stats(private val db: ChudDatabase) {
    enum class Period(val days: Long?) { WEEK(7), MONTH(30), YEAR(365), ALL(null) }

    data class Summary(
        val seconds: Long,
        val plays: Int,
        val distinct: Int,
        val listeningDays: Int,
        val avgPerDay: Long,
        val streak: Int,
        val bestStreak: Int,
        val discoveries: Int,
        val biggestDay: Pair<String, Long>?,
        val bySource: Map<Source, Long>,
        val topTracks: List<CountRow>,
        val topArtists: List<CountRow>,
        val topAlbums: List<CountRow>,
        val topRadios: List<CountRow>,
        val byHour: LongArray,
        val byWeekday: LongArray,
    )

    suspend fun notePlay(t: Track) {
        val now = System.currentTimeMillis()
        db.tracks().insertIfAbsent(TrackEntity.from(t))
        db.stats().insertHistory(PlayHistoryEntity(trackId = t.id, playedAt = now))
        db.tracks().notePlayed(t.id, now)
        if (t.source != Source.RADIO) {
            db.stats().putRecent(app.chudmusic.data.RecentPlayEntity(t.id, now))
            db.stats().trimRecent(RECENT_MAX)
        }
    }

    companion object {
        /** "Recently played" keeps this many tracks; the oldest leaves when a new one comes in. */
        const val RECENT_MAX = 10
    }

    suspend fun addListen(t: Track, seconds: Long) {
        if (seconds <= 0) return
        val now = LocalDateTime.now()
        // Radio: the station's name is kept as the label (stations are not in the tracks table).
        db.stats().addListen(now.toLocalDate().toString(), now.hour, t.id, t.source, seconds, t.title, t.artist, t.album)
    }

    suspend fun summary(period: Period): Summary {
        val fromDate = period.days?.let { LocalDate.now().minusDays(it - 1) } ?: LocalDate.of(2000, 1, 1)
        val from = fromDate.toString()
        val fromMs = fromDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val s = db.stats()
        val total = s.totalSeconds(from)
        val days = s.byDay(from).filter { it.seconds >= 30 }
        val allDays = s.listeningDays().map { LocalDate.parse(it) }
        // Streaks over all days (current: ending today or yesterday).
        var best = 0; var run = 0; var prev: LocalDate? = null
        for (d in allDays) { run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1; best = maxOf(best, run); prev = d }
        val today = LocalDate.now()
        val current = if (prev != null && (prev == today || prev == today.minusDays(1))) run else 0
        val hours = LongArray(24).also { a -> s.byHour(from).forEach { a[it.bucket] = it.seconds } }
        val weekdays = LongArray(7).also { a -> s.byDay(from).forEach { a[LocalDate.parse(it.day).dayOfWeek.value - 1] += it.seconds } }
        return Summary(
            seconds = total,
            plays = s.plays(fromMs),
            distinct = s.distinctTracks(fromMs),
            listeningDays = days.size,
            avgPerDay = if (days.isEmpty()) 0 else total / days.size,
            streak = current,
            bestStreak = best,
            discoveries = s.discoveries(fromMs),
            biggestDay = days.maxByOrNull { it.seconds }?.let { it.day to it.seconds },
            bySource = s.bySource(from).associate { it.source to it.seconds },
            topTracks = sameSong(s.topTracks(from, fromMs, 100)).take(10),
            topArtists = s.topArtists(from, 10),
            topAlbums = s.topAlbums(from, 10),
            topRadios = s.topRadios(from, 5),
            byHour = hours,
            byWeekday = weekdays,
        )
    }

    /** The same song heard from several copies (phone, NAS, YouTube) counts as one line. */
    private fun sameSong(rows: List<app.chudmusic.data.CountRow>): List<app.chudmusic.data.CountRow> =
        rows.groupBy { app.chudmusic.youtube.InnerTube.norm(it.label.orEmpty()) + "|" + app.chudmusic.youtube.InnerTube.norm(it.artist.orEmpty()) }
            .values.map { g -> g.first().copy(seconds = g.sumOf { it.seconds }, plays = g.sumOf { it.plays }) }
            .sortedWith(compareByDescending<app.chudmusic.data.CountRow> { it.plays }.thenByDescending { it.seconds })
}
