package app.chudmusic

import app.chudmusic.library.BatchWriter
import app.chudmusic.library.LocalScanner
import app.chudmusic.library.Scanner
import app.chudmusic.data.TrackEntity
import app.chudmusic.model.Track
import app.chudmusic.radio.RadioBrowser
import app.chudmusic.youtube.LinkImport
import app.chudmusic.youtube.Matcher
import app.chudmusic.youtube.MusicLink
import app.chudmusic.youtube.MusicLink.SpotifyKind
import app.chudmusic.youtube.Spotify
import app.chudmusic.youtube.InnerTube
import app.chudmusic.youtube.WantedTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Pasted links (YouTube, Spotify), the matcher shared with the PC app, resumable scans. */
class LinksTest {

    // ── Link parsing: the same cases as the PC app's links.ts ─────────────────────────────────

    @Test fun youtubeLinks() {
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(MusicLink.YtPlaylist("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"), MusicLink.parse("https://music.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
        // A video inside a playlist: the playlist, with the video as a fallback.
        assertEquals(
            MusicLink.YtPlaylist("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", "dQw4w9WgXcQ"),
            MusicLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&index=3"),
        )
        // A radio list (RD…) is built around the video: the video is what was shared.
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"))
        assertEquals(MusicLink.YtAlbum("MPREb_abcdefghijk"), MusicLink.parse("https://music.youtube.com/browse/MPREb_abcdefghijk"))
        assertEquals(MusicLink.YtArtist("UCRr1xG_2WIDs18a6cIiCxeA"), MusicLink.parse("https://music.youtube.com/channel/UCRr1xG_2WIDs18a6cIiCxeA"))
    }

    @Test fun spotifyLinks() {
        assertEquals(MusicLink.Spotify(SpotifyKind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M"), MusicLink.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1a2b"))
        assertEquals(MusicLink.Spotify(SpotifyKind.ALBUM, "2noRn2Aes5aoNVsU6iWThc"), MusicLink.parse("https://open.spotify.com/intl-fr/album/2noRn2Aes5aoNVsU6iWThc"))
        assertEquals(MusicLink.Spotify(SpotifyKind.TRACK, "4uLU6hMCjMI75M1A2tKUQC"), MusicLink.parse("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(MusicLink.Spotify(SpotifyKind.ARTIST, "4tZwfgrHOc3mvqYlEYSvVi"), MusicLink.parse("open.spotify.com/embed/artist/4tZwfgrHOc3mvqYlEYSvVi"))
    }

    @Test fun notLinks() {
        assertNull(MusicLink.parse("daft punk one more time"))
        assertNull(MusicLink.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(MusicLink.parse("https://open.spotify.com/show/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(MusicLink.parse("https://www.youtube.com/watch?v=short"))
        assertNull(MusicLink.parse("ftp://youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun linkInsideSharedText() {
        assertEquals(
            MusicLink.Spotify(SpotifyKind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M"),
            MusicLink.find("Écoute Today's Top Hits sur Spotify : https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x."),
        )
        assertNull(MusicLink.find("rien à voir ici"))
    }

    /** What the search box gets when a link is pasted: a share sentence, or a link stuck to typed text. */
    @Test fun linkPastedInSearchBox() {
        val song = MusicLink.YtVideo("dQw4w9WgXcQ")
        assertNull(MusicLink.parse("Listen to Never Gonna Give You Up on YouTube Music: https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(song, MusicLink.find("Listen to Never Gonna Give You Up on YouTube Music: https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(song, MusicLink.find("rickhttps://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(
            MusicLink.Spotify(SpotifyKind.ALBUM, "2noRn2Aes5aoNVsU6iWThc"),
            MusicLink.find("abc https://open.spotify.com/intl-fr/album/2noRn2Aes5aoNVsU6iWThc?si=1"),
        )
        assertNull(MusicLink.find("never gonna give you up"))
    }

    // ── Matcher: same scores as the PC app ────────────────────────────────────────────────────

    private fun yt(title: String, artist: String?, sec: Long? = null) = Track.youtube("id" + title.hashCode(), title, artist, durationMs = sec?.let { it * 1000 })

    @Test fun normLeavesOutNoise() {
        assertEquals("one more time", Matcher.norm("One More Time (Official Video)"))
        assertEquals("harder better faster stronger", Matcher.norm("Harder, Better, Faster, Stronger - Remastered 2011"))
        assertEquals("get lucky", Matcher.norm("Get Lucky (feat. Pharrell Williams)"))
        assertEquals("deja vu", Matcher.norm("Déjà vu"))
    }

    @Test fun matcherPicksTheSong() {
        val want = WantedTrack("One More Time", listOf("Daft Punk"), 320_357)
        val candidates = listOf(
            yt("One More Time (Letra)", "Lyrics Channel", 320),
            yt("One More Time", "Daft Punk", 321),
            yt("One More Time (Cover)", "Someone", 300),
        )
        assertEquals("Daft Punk", Matcher.best(candidates, want)?.artist)
        // Nothing close enough: no answer rather than a wrong one.
        assertNull(Matcher.best(listOf(yt("Around the World", "Daft Punk", 429)), want))
        // A fan upload is not the song unless asked for.
        assertTrue(Matcher.score(yt("One More Time lyrics", "Daft Punk", 320), want) < Matcher.score(candidates[1], want))
        // Only named in the title: weaker, still found when the title is the same.
        assertNotNull(Matcher.best(listOf(yt("Daft Punk - One More Time", "SomeUploader", 320)), want))
    }

    // ── Spotify embed page ────────────────────────────────────────────────────────────────────

    private fun page(entity: String) =
        """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script></html>"""

    @Test fun spotifyEmbedPage() {
        val pl = Spotify.parse(
            page("""{"title":"Mix","subtitle":"Spotify","trackList":[{"title":"Get Lucky","subtitle":"Daft Punk, Pharrell Williams","duration":248413},{"title":"","subtitle":"x"}]}"""),
            SpotifyKind.PLAYLIST,
        )
        assertEquals("Mix", pl.title)
        assertEquals(listOf(WantedTrack("Get Lucky", listOf("Daft Punk", "Pharrell Williams"), 248_413)), pl.tracks)
        val track = Spotify.parse(page("""{"name":"Numb","artists":[{"name":"Linkin Park"}],"duration":185000}"""), SpotifyKind.TRACK)
        assertEquals(WantedTrack("Numb", listOf("Linkin Park"), 185_000), track.tracks.single())
        assertEquals("Linkin Park", track.subtitle)
        val e = runCatching { Spotify.parse("<html>nothing</html>", SpotifyKind.ALBUM) }.exceptionOrNull() as Spotify.Failure
        assertEquals(Spotify.Reason.UNREADABLE, e.reason)
        val gone = runCatching { Spotify.parse("""<script id="__NEXT_DATA__" type="application/json">{"props":{}}</script>""", SpotifyKind.ALBUM) }.exceptionOrNull() as Spotify.Failure
        assertEquals(Spotify.Reason.NOT_FOUND, gone.reason)
    }

    // ── Link import state machine ─────────────────────────────────────────────────────────────

    @Test fun linkImportSettlesEachSlotOnce() {
        val a = WantedTrack("A", listOf("X")); val b = WantedTrack("B", listOf("Y"))
        var r = LinkImport.Ready(LinkImport.Origin.SPOTIFY, LinkImport.Kind.PLAYLIST, "P", null, listOf(LinkImport.Slot.Pending(a), LinkImport.Slot.Pending(b)))
        assertEquals(2, r.pending); assertFalse(r.done)
        val found = yt("A", "X")
        r = r.resolve(0, found)
        assertEquals(listOf(found), r.found)
        // A late second answer for the same slot changes nothing.
        r = r.resolve(0, null)
        assertEquals(listOf(found), r.found)
        r = r.resolve(1, null)
        assertTrue(r.done)
        assertEquals(listOf(b), r.missing)
        // Out of range: ignored.
        assertEquals(r, r.resolve(5, found))
    }

    // ── Radio, scans ──────────────────────────────────────────────────────────────────────────

    @Test fun radioCodecLabel() {
        assertEquals("MP3", RadioBrowser.codecLabel("MP3", false))
        assertEquals("HLS", RadioBrowser.codecLabel("UNKNOWN", true))
        assertNull(RadioBrowser.codecLabel("UNKNOWN", false))
        assertNull(RadioBrowser.codecLabel(" ", false))
    }

    @Test fun networkScanRules() {
        assertTrue(Scanner.mustScan(force = false, metered = false, unfinished = false))
        assertFalse(Scanner.mustScan(force = false, metered = true, unfinished = false))
        // Asked for, or stopped halfway last time: read even on mobile data.
        assertTrue(Scanner.mustScan(force = true, metered = true, unfinished = false))
        assertTrue(Scanner.mustScan(force = false, metered = true, unfinished = true))
        // Paused: skipped by "scan everything" and the periodic scan, read when asked for by itself.
        assertFalse(Scanner.mustScan(force = true, metered = false, unfinished = false, paused = true))
        assertFalse(Scanner.mustScan(force = false, metered = false, unfinished = true, paused = true))
        assertTrue(Scanner.mustScan(force = true, metered = true, unfinished = false, paused = true, chosen = true))
    }

    @Test fun batchWriterKeepsEverythingInOrderOfBatches() = runBlocking {
        val written = mutableListOf<List<Int>>()
        val w = BatchWriter<Int>(3) { written += it }
        (1..7).forEach { w.add(it) }
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6)), written)
        w.flush()
        assertEquals(listOf(7), written.last())
        w.flush()
        assertEquals(3, written.size)
    }

    @Test fun phoneTrackUpToDate() {
        val t = Track(id = "local:1", source = app.chudmusic.model.Source.LOCAL, uri = "content://1", title = "T", path = "/m/a.mp3")
        val e = app.chudmusic.data.KnownFile("local:1", "/m/a.mp3", t.sizeBytes, 10, true, true, "cover", 0, null, 0, null)
        assertTrue(LocalScanner.isUpToDate(e, t, 10))
        assertFalse(LocalScanner.isUpToDate(e, t, 11))
        assertFalse(LocalScanner.isUpToDate(e, t.copy(path = "/m/b.mp3"), 10))
        assertFalse(LocalScanner.isUpToDate(e.copy(coverUri = null), t, 10))
        assertFalse(LocalScanner.isUpToDate(e.copy(inLibrary = false), t, 10))
        // A track without a cover whose album was already searched in vain is not read again…
        assertTrue(LocalScanner.isUpToDate(e.copy(coverUri = null), t, 10) { true })
        // …unless the file changed (a cover may have been added to it).
        assertFalse(LocalScanner.isUpToDate(e.copy(coverUri = null), t, 11) { true })
    }

    @Test fun scanRequestsQueueWithoutPilingUp() {
        val all = app.chudmusic.library.ScanRequest(local = true, network = true)
        val phone = app.chudmusic.library.ScanRequest(local = true, network = false)
        val forcedAll = all.copy(force = true)
        val onePlace = app.chudmusic.library.ScanRequest(local = false, network = true, onlyId = 7, force = true)
        val mustQueue = app.chudmusic.library.ScanRequest.Companion::mustQueue

        assertTrue(mustQueue(all, emptyList()))
        // Opening the app again while a full scan waits: nothing more is queued.
        assertFalse(mustQueue(all, listOf(all)))
        assertFalse(mustQueue(phone, listOf(all)))
        // A scan asked for by hand reads even on mobile data: a waiting automatic one is not enough.
        assertTrue(mustQueue(forcedAll, listOf(all)))
        assertFalse(mustQueue(all, listOf(forcedAll)))
        // A new place is read even if only another place's scan is waiting.
        assertTrue(mustQueue(onePlace, listOf(onePlace.copy(onlyId = 8))))
        assertFalse(mustQueue(onePlace, listOf(forcedAll)))
        assertTrue(mustQueue(all, listOf(phone)))
        assertFalse(mustQueue(app.chudmusic.library.ScanRequest(local = false, network = false), emptyList()))

        // Read back from WorkManager's tags exactly as it was queued.
        for (r in listOf(all, phone, forcedAll, onePlace)) assertEquals(r, app.chudmusic.library.ScanRequest.fromTags(r.tags()))
    }

    // ── Against the real services (-Pchud.online=true) ────────────────────────────────────────

    private fun online() = assumeTrue(System.getProperty("chud.online") == "true")

    @Test fun onlineYoutubePlaylistAndSpotify() = runBlocking {
        online()
        val yt = InnerTube()
        // A long public playlist: read past its first page of 100.
        val pl = yt.playlist("PLZt2YpN30WNWSgtys4Uif2zjXnY29Yj3N")
        println("playlist: ${pl.title} ${pl.tracks.size} truncated=${pl.truncated}")
        assertTrue(pl.tracks.size > 100)

        val album = Spotify.entity(SpotifyKind.ALBUM, "2noRn2Aes5aoNVsU6iWThc")
        println("spotify album: ${album.title} / ${album.subtitle} / ${album.tracks.size}")
        assertEquals(14, album.tracks.size)
        val first = yt.match(album.tracks.first())
        println("matched: ${first?.title} / ${first?.artist}")
        assertNotNull(first)
        assertTrue(first!!.artist!!.contains("Daft Punk"))
    }
}
