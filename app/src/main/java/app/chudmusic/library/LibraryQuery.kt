package app.chudmusic.library

import androidx.compose.runtime.mutableStateMapOf
import androidx.sqlite.db.SimpleSQLiteQuery
import app.chudmusic.data.fold
import app.chudmusic.model.Track

/**
 * Part of the library, as SQL: the words of a search box and one origin (null: everything, 0: the
 * phone, else a network place). [where] and [args] are plain text, so a test runs them on SQLite.
 */
class LibraryQuery(q: String = "", origin: Long? = null) {
    private val words = fold(q).split(' ').filter { it.isNotBlank() }
    val where: String
    val args: Array<Any>

    init {
        val parts = mutableListOf("inLibrary = 1")
        val values = mutableListOf<Any>()
        when (origin) {
            null -> {}
            0L -> parts += "source = 'LOCAL'"
            else -> { parts += "sourceId = ?"; values += origin }
        }
        for (w in words) {
            parts += "searchKey LIKE ? ESCAPE '\\'"
            values += "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        where = parts.joinToString(" AND ")
        args = values.toTypedArray()
    }

    fun rowIds() = SimpleSQLiteQuery("SELECT rowid FROM tracks WHERE $where ORDER BY title COLLATE NOCASE", args)
    fun albumKeys() = SimpleSQLiteQuery("SELECT DISTINCT albumKey FROM tracks WHERE $where AND albumKey IS NOT NULL", args)
    fun artistKeys() = SimpleSQLiteQuery("SELECT DISTINCT artistKey FROM tracks WHERE $where AND artistKey IS NOT NULL", args)
    fun tracks(limit: Int) = SimpleSQLiteQuery("SELECT * FROM tracks WHERE $where ORDER BY title COLLATE NOCASE LIMIT $limit", args)
}

/**
 * A long list of tracks, known by their row numbers and read [PAGE] at a time around what is on
 * screen. What was read stays until [KEEP] tracks are held; [old] gives its own, shown until the
 * fresh ones arrive, so that a list that changes does not blink.
 */
class PagedTracks(val rows: LongArray, private val load: suspend (List<Long>) -> Map<Long, Track>, old: PagedTracks? = null) {
    private val held = mutableStateMapOf<Long, Track>()
    private val fresh = HashSet<Int>()

    init {
        if (old != null) for (r in rows) { old.held[r]?.let { held[r] = it }; if (held.size >= KEEP) break }
    }

    val size get() = rows.size
    operator fun get(i: Int): Track? = held[rows[i]]

    fun pageOf(i: Int) = i / PAGE

    /** Reads the page of [i] (and the next one) unless it already was. Main thread. */
    suspend fun ensure(page: Int) {
        for (p in page..page + 1) {
            if (p * PAGE >= rows.size || !fresh.add(p)) continue
            if (held.size > KEEP) {
                // Far from here: forgotten, read again if the user comes back.
                val near = ((page - 4) * PAGE).coerceAtLeast(0)..((page + 5) * PAGE).coerceAtMost(rows.size) - 1
                val keep = near.mapTo(HashSet()) { rows[it] }
                held.keys.toList().forEach { if (it !in keep) held.remove(it) }
                fresh.retainAll { it in page - 4..page + 4 }
                fresh.add(p)
            }
            val ids = rows.copyOfRange(p * PAGE, minOf((p + 1) * PAGE, rows.size)).toList()
            held.putAll(load(ids))
        }
    }

    companion object {
        const val PAGE = 100
        const val KEEP = 3000
    }
}

/** What goes into the play queue when a very long list is played. */
object QueueWindow {
    /** A queue longer than this slows the player and its notification for nothing (days of music). */
    const val MAX = 2000
    private const val BEFORE = 200

    /** The part of a list of [size] that is played from [index], in order. */
    fun around(size: Int, index: Int, max: Int = MAX): IntRange {
        if (size <= max) return 0 until size
        val from = (index - BEFORE.coerceAtMost(max / 2)).coerceIn(0, size - max)
        return from until from + max
    }

    /** [max] places drawn at random among [size], [index] (when there is one) included. */
    fun drawn(size: Int, index: Int?, max: Int = MAX, random: kotlin.random.Random = kotlin.random.Random): List<Int> {
        if (size <= max) return (0 until size).toList()
        val picked = LinkedHashSet<Int>()
        if (index != null) picked += index
        while (picked.size < max) picked += random.nextInt(size)
        return picked.toList()
    }
}
