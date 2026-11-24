package app.chudmusic.lyrics

import app.chudmusic.data.LyricsCacheEntity
import app.chudmusic.data.LyricsDao
import app.chudmusic.model.Track
import app.chudmusic.net.Http
import app.chudmusic.youtube.InnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Lyrics from LRCLIB (free database, no account), like the PC (§21): exact search (title,
 * artist, album, duration), then a wider one; local cache, a "not found" is retried after a week.
 */
class Lyrics(private val dao: LyricsDao) {
    data class Line(val timeMs: Long, val text: String)

    sealed interface Result {
        data class Synced(val lines: List<Line>) : Result
        data class Plain(val text: String) : Result
        data object Instrumental : Result
        data class NotFound(val query: String) : Result
        data class Error(val message: String) : Result
    }

    @Serializable
    private data class Lrc(
        val id: Long? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** YouTube titles cleaned: "(Official Video)", "feat.", "- Topic", "Artist - Title". */
    fun clean(t: Track): Pair<String, String?> {
        var title = t.title.replace(Regex("""\s*[(\[][^)\]]*(official|video|clip|audio|lyric|paroles|visualizer|hd|4k|remaster)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[(\[]?\b(feat|ft|featuring)\b\.?\s[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE), "").trim()
        var artist = t.artist?.replace(Regex("""\s*-\s*Topic$|VEVO$""", RegexOption.IGNORE_CASE), "")?.let { app.chudmusic.library.Library.mainArtist(it) }
        if (t.isYoutube || t.isRadio) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2 && (artist == null || t.isRadio || InnerTube.norm(parts[0]) == InnerTube.norm(artist))) { artist = parts[0].trim(); title = parts[1].trim() }
        }
        return title to artist
    }

    suspend fun get(t: Track, titleOverride: String? = null, artistOverride: String? = null): Result = withContext(Dispatchers.IO) {
        val (title0, artist0) = clean(t)
        val title = titleOverride ?: title0
        val artist = artistOverride ?: artist0
        val key = "${InnerTube.norm(title)}|${InnerTube.norm(artist ?: "")}"
        dao.get(key)?.let { c ->
            val fresh = c.status != "none" || System.currentTimeMillis() - c.fetchedAt < 7L * 24 * 3600_000
            if (fresh) return@withContext fromCache(c, title, artist)
        }
        try {
            val exact = if (titleOverride == null && artist != null) getExact(title, artist, t.album, t.durationMs) else null
            val hit = exact ?: search(title, artist, if (titleOverride == null) t.durationMs else null)
            val entity = when {
                hit == null -> LyricsCacheEntity(key, "none", null, null, System.currentTimeMillis())
                hit.instrumental -> LyricsCacheEntity(key, "instrumental", null, null, System.currentTimeMillis())
                !hit.syncedLyrics.isNullOrBlank() -> LyricsCacheEntity(key, "synced", hit.syncedLyrics, hit.plainLyrics, System.currentTimeMillis())
                !hit.plainLyrics.isNullOrBlank() -> LyricsCacheEntity(key, "plain", null, hit.plainLyrics, System.currentTimeMillis())
                else -> LyricsCacheEntity(key, "none", null, null, System.currentTimeMillis())
            }
            dao.put(entity)
            fromCache(entity, title, artist)
        } catch (e: Exception) {
            Result.Error(e.message ?: "LRCLIB")
        }
    }

    private fun fromCache(c: LyricsCacheEntity, title: String, artist: String?): Result = when (c.status) {
        "synced" -> Result.Synced(parseLrc(c.synced ?: ""))
        "plain" -> Result.Plain(c.plain ?: "")
        "instrumental" -> Result.Instrumental
        else -> Result.NotFound(listOfNotNull(artist, title).joinToString(" – "))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val headers = mapOf("User-Agent" to "ChudMusic-Android (https://github.com/BigFatDeuce/Chud-Music-More)")

    private fun getExact(title: String, artist: String, album: String?, durationMs: Long?): Lrc? {
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
            if (album != null) append("&album_name=${enc(album)}")
            if (durationMs != null) append("&duration=${durationMs / 1000}")
        }
        return try {
            json.decodeFromString(Lrc.serializer(), Http.getText(url, headers))
        } catch (e: app.chudmusic.net.HttpException) {
            if (e.code == 404) null else throw e
        }
    }

    private fun search(title: String, artist: String?, durationMs: Long?): Lrc? {
        val q = if (artist != null) "track_name=${enc(title)}&artist_name=${enc(artist)}" else "q=${enc(title)}"
        val list = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Lrc.serializer()), Http.getText("https://lrclib.net/api/search?$q", headers))
        val ok = list.filter { durationMs == null || it.duration == null || kotlin.math.abs(it.duration * 1000 - durationMs) < 8000 }
        return ok.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: ok.firstOrNull()
    }

    companion object {
        /** "[01:02.34] text" lines (several stamps per line allowed), sorted. */
        fun parseLrc(text: String): List<Line> {
            val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
            val out = mutableListOf<Line>()
            for (raw in text.lines()) {
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val body = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                    out += Line(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, body)
                }
            }
            return out.sortedBy { it.timeMs }
        }

        /** Index of the line being sung at [posMs], -1 before the first. */
        fun currentLine(lines: List<Line>, posMs: Long): Int {
            var lo = 0; var hi = lines.lastIndex; var ans = -1
            while (lo <= hi) { val mid = (lo + hi) / 2; if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
            return ans
        }
    }
}
