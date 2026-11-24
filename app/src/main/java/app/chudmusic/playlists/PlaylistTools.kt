package app.chudmusic.playlists

import app.chudmusic.data.fold
import app.chudmusic.library.Library
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.youtube.InnerTube
import kotlin.math.abs

/** Search, sort and duplicate finding inside one playlist (the PC app's playlist tools). */
object PlaylistTools {
    /** Every word of [query] is somewhere in the title, artist or album (accents and case ignored). */
    fun matches(t: Track, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = fold(listOfNotNull(t.title, t.artist, t.album, t.albumArtist).joinToString(" "))
        return words.all { hay.contains(it) }
    }

    enum class SortKey { TITLE, ARTIST, ALBUM, DURATION }

    /**
     * The entries in the order of [key]; ties keep their current order. Album sorts by album, then
     * disc and track number, so an album stays in its order.
     */
    fun sorted(entries: List<Playlists.Entry>, key: SortKey): List<Playlists.Entry> {
        fun s(v: String?) = fold(v ?: "￿")
        val c: Comparator<Playlists.Entry> = when (key) {
            SortKey.TITLE -> compareBy { s(it.track.title) }
            SortKey.ARTIST -> compareBy<Playlists.Entry> { s(it.track.artist ?: it.track.albumArtist) }.thenBy { s(it.track.album) }.thenBy { it.track.discNo ?: 1 }.thenBy { it.track.trackNo ?: Int.MAX_VALUE }
            SortKey.ALBUM -> compareBy<Playlists.Entry> { s(it.track.album) }.thenBy { it.track.discNo ?: 1 }.thenBy { it.track.trackNo ?: Int.MAX_VALUE }.thenBy { s(it.track.title) }
            SortKey.DURATION -> compareBy { it.track.durationMs ?: Long.MAX_VALUE }
        }
        return entries.sortedWith(c)
    }

    private val LOSSLESS_EXT = setOf("flac", "alac", "wav", "aiff", "aif", "aifc", "ape", "wv", "tta")

    /** Lossless file > lossy file (higher bit rate first) > YouTube Music; a missing file comes last. */
    fun quality(t: Track): Long {
        if (!t.available) return -1
        if (t.source == Source.YOUTUBE) return 0
        val ext = (t.path ?: t.uri).substringAfterLast('.', "").lowercase()
        val lossless = ext in LOSSLESS_EXT || t.mime?.let { "flac" in it || "wav" in it || "alac" in it || "aiff" in it } == true
        val kbps = if (t.sizeBytes != null && t.durationMs != null && t.durationMs > 0) t.sizeBytes * 8 / t.durationMs else 0L
        return (if (lossless) 1_000_000L else 1_000L) + kbps.coerceAtMost(9_999)
    }

    /** Same song: same track, or same title and main artist with durations within 5 s when both are known. */
    private fun songKey(t: Track): String =
        InnerTube.norm(t.title) + "\u0000" + InnerTube.norm(Library.mainArtist(t.artist ?: t.albumArtist)?.replace(Regex("""\s-\s*topic$|vevo$""", RegexOption.IGNORE_CASE), "") ?: "")

    /**
     * The entries to remove so each song stays once: in each group of copies, the best one
     * ([quality]; the first in the list when equal) stays where it is.
     */
    fun duplicates(entries: List<Playlists.Entry>): List<Long> {
        val remove = mutableListOf<Long>()
        val groups = mutableListOf<MutableList<Playlists.Entry>>()
        val byKey = HashMap<String, MutableList<MutableList<Playlists.Entry>>>()
        for (e in entries) {
            val t = e.track
            val key = if (InnerTube.norm(t.title).isBlank()) "id:" + t.id else songKey(t)
            val candidates = byKey.getOrPut(key) { mutableListOf() }
            val group = candidates.firstOrNull { g ->
                g.any { o -> o.track.id == t.id } || g.all { o ->
                    o.track.durationMs == null || t.durationMs == null || abs(o.track.durationMs - t.durationMs) <= 5000
                }
            }
            if (group != null) group += e else mutableListOf(e).also { candidates += it; groups += it }
        }
        for (g in groups) {
            if (g.size < 2) continue
            val keep = g.maxWith(compareBy<Playlists.Entry> { quality(it.track) }.thenBy { -g.indexOf(it) })
            g.filter { it !== keep }.mapTo(remove) { it.entryId }
        }
        return remove
    }
}
