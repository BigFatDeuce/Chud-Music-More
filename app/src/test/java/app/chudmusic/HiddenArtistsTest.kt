package app.chudmusic

import app.chudmusic.model.Track
import app.chudmusic.youtube.HiddenArtist
import app.chudmusic.youtube.HiddenArtists
import app.chudmusic.youtube.YtAlbumItem
import app.chudmusic.youtube.YtArtistItem
import app.chudmusic.youtube.YtArtistPage
import app.chudmusic.youtube.YtItem
import app.chudmusic.youtube.YtMix
import app.chudmusic.youtube.YtShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenArtistsTest {
    private fun yt(v: String, artist: String?, artistId: String? = null) = Track.youtube(v, "Song $v", artist, artistId = artistId)

    @Test fun hiddenArtistLeavesYouTubeLists() {
        val h = HiddenArtists(listOf(HiddenArtist("UCbad", "Bad Band"), HiddenArtist(null, "Solo")))
        // By channel id, whatever the name says.
        assertTrue(h.hides(yt("1", "Renamed", "UCbad")))
        // By name: plain-text credits, other spellings, featured artists, "- Topic" channels.
        assertTrue(h.hides(yt("2", "BAD BÄND")))
        assertTrue(h.hides(yt("3", "Someone feat. Solo")))
        assertTrue(h.hides(yt("4", "Someone, Solo")))
        assertTrue(h.hides(YtArtistItem("UCother", "Solo - Topic", null, null)))
        // A guest named only in the title.
        assertTrue(h.hides(Track.youtube("g1", "Starboy (avec Solo)", "The Weeknd")))
        assertTrue(h.hides(Track.youtube("g2", "Get Lucky (feat. Pharrell Williams and Solo)", "Daft Punk")))
        assertFalse(h.hides(Track.youtube("g3", "Solo (Live)", "Someone")))
        // An upload by someone else titled "Artist - Song".
        assertTrue(h.hides(Track.youtube("g4", "Solo - Get Lucky", "Various Artists")))
        assertFalse(h.hides(Track.youtube("g5", "Get Lucky - Solo Version", "Someone")))
        // Not a word inside another name, not a phone file, not without an artist.
        assertFalse(h.hides(yt("5", "Solomon")))
        assertFalse(h.hides(Track(id = "f", source = app.chudmusic.model.Source.LOCAL, uri = "content://f", title = "x", artist = "Solo")))
        assertFalse(h.hides(yt("6", null)))
        // Search results of each kind.
        val items = listOf<YtItem>(
            YtItem.Song(yt("7", "Good")), YtItem.Song(yt("8", "Solo")),
            YtItem.Album(YtAlbumItem("MPRE1", "A", "Bad Band", "UCbad", null, null, null)),
            YtItem.Artist(YtArtistItem("UCbad", "Bad Band", null, null)),
        )
        assertEquals(listOf("yt:7"), items.filterNot(h::hides).map { (it as YtItem.Song).track.id })
        // A mix keeps the song it was started from, even by a hidden artist.
        val mix = YtMix(yt("8", "Solo"), "RDAMVM8", listOf(yt("8", "Solo"), yt("9", "Good"), yt("10", "Solo")))
        assertEquals(listOf("yt:8", "yt:9"), h.keep(mix).tracks.map { it.id })
        // An artist page loses the hidden related artists, and a shelf left empty disappears.
        val page = YtArtistPage("UCgood", "Good", null, null, listOf(yt("11", "Good"), yt("12", "Good, Solo")), listOf(
            YtShelf("Fans also like", artists = listOf(YtArtistItem("UCbad", "Bad Band", null, null))),
            YtShelf("Albums", albums = listOf(YtAlbumItem("MPRE2", "B", "Good", "UCgood", null, null, null))),
        ))
        val kept = h.keep(page)
        assertEquals(listOf("yt:11"), kept.topSongs.map { it.id })
        assertEquals(listOf("Albums"), kept.shelves.map { it.title })
        // Nothing hidden: lists untouched.
        assertEquals(mix, HiddenArtists().keep(mix))
    }

    @Test fun hidingTwiceKeepsOneAndRestoreRemovesIt() {
        val a = HiddenArtists.mainArtistOf(yt("1", "Bad Band, Other", "UCbad"))!!
        assertEquals(HiddenArtist("UCbad", "Bad Band"), a)
        val once = HiddenArtists().plus(a)
        val twice = HiddenArtists(once).plus(HiddenArtist(null, "bad band"))
        assertEquals(listOf(HiddenArtist("UCbad", "Bad Band")), twice)
        assertTrue(HiddenArtists(twice).minus(a).isEmpty())
        assertEquals(null, HiddenArtists.mainArtistOf(yt("2", null)))
    }
}
