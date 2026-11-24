package app.chudmusic

import app.chudmusic.net.Http
import app.chudmusic.youtube.InnerTube
import app.chudmusic.youtube.YouTubeStreams
import app.chudmusic.youtube.YtItem
import app.chudmusic.youtube.YtSearchType
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against the real YouTube Music (Internet required). Skipped unless -Pchud.online=true
 * (system property chud.online), so the offline suite never depends on YouTube.
 */
class YouTubeOnlineTest {
    private val yt = InnerTube()
    private fun online() = assumeTrue(System.getProperty("chud.online") == "true")

    @Test fun searchSongsAlbumsArtists() {
        online()
        val songs = yt.search("Linkin Park Numb", YtSearchType.SONG)
        val tracks = songs.items.filterIsInstance<YtItem.Song>().map { it.track }
        println("songs: " + tracks.take(5).joinToString { "${it.title} / ${it.artist} / ${it.album} / ${it.durationMs} / ${it.ytAlbumId}" })
        assertTrue(tracks.size >= 5)
        val numb = tracks.first { it.title.contains("Numb", true) }
        assertTrue(numb.artist!!.contains("Linkin Park"))
        assertNotNull(numb.durationMs)
        assertNotNull(numb.coverUri)
        assertNotNull(songs.continuation)
        val more = yt.searchMore(YtSearchType.SONG, songs.continuation!!)
        println("more: ${more.items.size}")
        assertTrue(more.items.isNotEmpty())

        val albums = yt.search("Meteora", YtSearchType.ALBUM).items.filterIsInstance<YtItem.Album>().map { it.album }
        println("albums: " + albums.take(3))
        assertTrue(albums.any { it.title.contains("Meteora") })
        val artists = yt.search("Linkin Park", YtSearchType.ARTIST).items.filterIsInstance<YtItem.Artist>().map { it.artist }
        println("artists: " + artists.take(3))
        assertEquals("Linkin Park", artists.first().name)
        val videos = yt.search("Linkin Park Numb official", YtSearchType.VIDEO).items
        println("videos: ${videos.size}")
        assertTrue(videos.isNotEmpty())
        println("suggest: " + yt.suggestions("linkin"))
    }

    @Test fun albumArtistMix() {
        online()
        val albumId = yt.search("Meteora Linkin Park", YtSearchType.ALBUM).items.filterIsInstance<YtItem.Album>().first().album.id
        val album = yt.album(albumId)
        println("album: ${album.title} / ${album.artist} / ${album.year} / ${album.kind} / ${album.tracks.size}")
        album.tracks.take(3).forEach { println("  ${it.title} ${it.durationMs} ${it.artist}") }
        assertTrue(album.tracks.size >= 10)
        assertTrue(album.tracks.all { it.durationMs != null })

        val artistId = yt.search("Linkin Park", YtSearchType.ARTIST).items.filterIsInstance<YtItem.Artist>().first().artist.id
        val artist = yt.artist(artistId)
        println("artist: ${artist.name}, top ${artist.topSongs.size}, shelves ${artist.shelves.map { "${it.title}:${it.albums.size}/${it.artists.size}" }}")
        assertTrue(artist.topSongs.isNotEmpty())
        assertTrue(artist.shelves.any { it.albums.isNotEmpty() })

        val seed = album.tracks.first().videoId!!
        val mix = yt.mix(seed)
        println("mix: ${mix.tracks.size} first=${mix.tracks.firstOrNull()?.title}")
        assertTrue(mix.tracks.size >= 20)
    }

    @Test fun audioStream() {
        online()
        val id = yt.search("Linkin Park Numb", YtSearchType.SONG).items.filterIsInstance<YtItem.Song>().first().track.videoId!!
        val t0 = System.currentTimeMillis()
        val s = YouTubeStreams.resolve(id)
        println("stream: ${s.mime} ${s.bitrate} bps, ${s.durationMs} ms, resolved in ${System.currentTimeMillis() - t0} ms")
        for (range in listOf("bytes=0-65535", "bytes=2000000-2065535")) {
            val req = Request.Builder().url(s.url).header("Range", range).header("User-Agent", Http.BROWSER_UA).build()
            Http.client.newCall(req).execute().use { r ->
                val bytes = r.body.bytes()
                println("range $range -> ${r.code} ${bytes.size} bytes")
                assertTrue(r.code == 206 || r.code == 200)
                assertTrue(bytes.size > 10_000)
            }
        }
    }
}
