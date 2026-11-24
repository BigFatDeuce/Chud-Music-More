package app.chudmusic

import app.chudmusic.data.LyricsCacheEntity
import app.chudmusic.data.LyricsDao
import app.chudmusic.data.RadioDao
import app.chudmusic.data.RadioStationEntity
import app.chudmusic.lyrics.Lyrics
import app.chudmusic.model.Track
import app.chudmusic.radio.RadioBrowser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** LRCLIB and Radio Browser for real (Internet). Skipped unless -Pchud.online=true. */
class OnlineServicesTest {
    private fun online() = assumeTrue(System.getProperty("chud.online") == "true")

    private val lyricsDao = object : LyricsDao {
        val map = HashMap<String, LyricsCacheEntity>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(e: LyricsCacheEntity) { map[e.key] = e }
    }

    private val radioDao = object : RadioDao {
        val rows = MutableStateFlow<List<RadioStationEntity>>(emptyList())
        override fun favorites(): Flow<List<RadioStationEntity>> = rows
        override suspend fun get(uuid: String) = rows.value.firstOrNull { it.uuid == uuid }
        override suspend fun upsert(s: RadioStationEntity) { rows.value = rows.value.filter { it.uuid != s.uuid } + s }
        override suspend fun setFavorite(uuid: String, fav: Boolean) {}
        override fun recent(n: Int): Flow<List<RadioStationEntity>> = rows
        override suspend fun touch(uuid: String, at: Long) {}
    }

    @Test fun syncedLyricsFromLrclib() = runBlocking {
        online()
        val l = Lyrics(lyricsDao)
        val r = l.get(Track.youtube("kXYiU_JCYtU", "Numb (Official Music Video)", "Linkin Park", "Meteora", 187_000))
        println("lyrics: $r".take(300))
        assertTrue(r is Lyrics.Result.Synced && r.lines.size > 10)
        // Second time: from the cache, no network.
        assertTrue(l.get(Track.youtube("kXYiU_JCYtU", "Numb (Official Music Video)", "Linkin Park", "Meteora", 187_000)) is Lyrics.Result.Synced)
        assertTrue(lyricsDao.map.size == 1)
    }

    @Test fun radioDirectory() = runBlocking {
        online()
        val rb = RadioBrowser(radioDao)
        val top = rb.top("FR", 20)
        println("top FR: " + top.take(5).joinToString { "${it.name} (${it.codec} ${it.bitrate})" })
        assertTrue(top.size >= 10)
        val fip = rb.search("FIP", 10)
        println("search FIP: " + fip.take(5).joinToString { it.name + " → " + (it.urlResolved ?: it.url) })
        assertTrue(fip.any { it.name.contains("FIP", true) })
        val tags = rb.tags(10)
        println("tags: " + tags.joinToString { it.name })
        assertTrue(tags.isNotEmpty())
        val t = RadioBrowser.toTrack(top.first())
        assertTrue(t.id.startsWith("radio:") && t.uri.startsWith("http"))
    }
}
