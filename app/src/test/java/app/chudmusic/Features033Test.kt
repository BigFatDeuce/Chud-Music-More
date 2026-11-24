package app.chudmusic

import app.chudmusic.library.OnlineCovers
import app.chudmusic.lyrics.EmbeddedLyrics
import app.chudmusic.lyrics.Lyrics
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.playlists.PlaylistTools
import app.chudmusic.playlists.Playlists
import app.chudmusic.ui.components.ArtistLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Features033Test {
    private fun file(id: String, title: String, artist: String, ext: String, kbps: Long, dur: Long = 200_000, album: String? = null, no: Int? = null) =
        Track(id, Source.LOCAL, "u", title, artist, album, durationMs = dur, path = "/m/$id.$ext", sizeBytes = kbps * dur / 8, trackNo = no)

    private fun entries(vararg t: Track) = t.mapIndexed { i, x -> Playlists.Entry(i + 1L, x) }

    @Test fun playlistSearchIgnoresAccentsAndCase() {
        val t = Track("a", Source.LOCAL, "u", "Déjà vu", "Beyoncé", "B'Day")
        assertTrue(PlaylistTools.matches(t, "deja BEYONCE"))
        assertTrue(PlaylistTools.matches(t, "  "))
        assertTrue(!PlaylistTools.matches(t, "deja rihanna"))
    }

    @Test fun playlistSortKeepsAlbumsInTheirOrder() {
        val e = entries(
            file("1", "Zed", "B", "mp3", 320, 300_000, "Second", 2),
            file("2", "Alpha", "A", "mp3", 320, 100_000, "First", 1),
            file("3", "Mid", "B", "mp3", 320, 200_000, "Second", 1),
        )
        assertEquals(listOf("2", "3", "1"), PlaylistTools.sorted(e, PlaylistTools.SortKey.TITLE).map { it.track.id })
        assertEquals(listOf("2", "3", "1"), PlaylistTools.sorted(e, PlaylistTools.SortKey.ALBUM).map { it.track.id })
        assertEquals(listOf("2", "3", "1"), PlaylistTools.sorted(e, PlaylistTools.SortKey.ARTIST).map { it.track.id })
        assertEquals(listOf("2", "3", "1"), PlaylistTools.sorted(e, PlaylistTools.SortKey.DURATION).map { it.track.id })
    }

    @Test fun duplicatesKeepTheBestCopy() {
        val mp3 = file("mp3", "One More Time", "Daft Punk", "mp3", 192)
        val flac = file("flac", "One More Time", "Daft Punk", "flac", 900)
        val yt = Track.youtube("v1", "One More Time", "Daft Punk - Topic", durationMs = 201_000)
        val live = file("live", "One More Time (Live)", "Daft Punk", "flac", 900)
        val radioEdit = file("edit", "One More Time", "Daft Punk", "mp3", 320, dur = 230_000)
        val e = entries(mp3, yt, flac, live, radioEdit, mp3)
        // FLAC stays; the MP3 (twice) and the YouTube twin go; the live version and the longer edit are other songs.
        assertEquals(listOf(1L, 2L, 6L), PlaylistTools.duplicates(e).sorted())
        assertEquals(emptyList<Long>(), PlaylistTools.duplicates(entries(mp3, live)))
        // Equal copies: the first one stays.
        assertEquals(listOf(2L), PlaylistTools.duplicates(entries(mp3, mp3)))
    }

    @Test fun onlineCoverIsTheSameAlbumOrNone() {
        val deezer = """{"data":[{"title":"Discovery (Remastered)","cover_xl":"https://e-cdns/1000x1000.jpg","artist":{"name":"Daft Punk"}},
            {"title":"Homework","cover_xl":"https://e-cdns/h.jpg","artist":{"name":"Daft Punk"}}]}"""
        val c = OnlineCovers.parseDeezer(deezer)
        assertEquals(2, c.size)
        assertEquals("https://e-cdns/1000x1000.jpg", OnlineCovers.pick("Daft Punk", "Discovery", c)?.imageUrl)
        assertNull(OnlineCovers.pick("Daft Punk", "Random Access Memories", c))
        assertNull(OnlineCovers.pick("Justice", "Discovery", c))
        val itunes = """{"results":[{"collectionName":"Random Access Memories","artistName":"Daft Punk","artworkUrl100":"https://is1/100x100bb.jpg"}]}"""
        assertEquals("https://is1/600x600bb.jpg", OnlineCovers.pick("Daft Punk feat. Pharrell", "Random Access Memories", OnlineCovers.parseItunes(itunes))?.imageUrl)
        assertEquals(emptyList<OnlineCovers.Candidate>(), OnlineCovers.parseDeezer("<html>"))
        // Only https pictures.
        assertNull(OnlineCovers.pick("A", "B", listOf(OnlineCovers.Candidate("A", "B", "http://x/y.jpg"))))
    }

    @Test fun artistLinksAreEncodedAndInTheUsersWikipedia() {
        val l = ArtistLinks.of("AC/DC & Co", "fr")
        assertEquals("https://fr.wikipedia.org/w/index.php?search=AC%2FDC+%26+Co", l.first().url)
        assertEquals(listOf("Wikipedia", "Grokipedia", "Last.fm", "Discogs"), l.map { it.label })
        assertTrue(ArtistLinks.of("X", "").first().url.startsWith("https://en."))
        assertEquals("FLAC · 900 kb/s", ArtistLinks.format(file("f", "t", "a", "flac", 900)))
        assertNull(ArtistLinks.format(Track.youtube("v", "t", "a")))
    }

    // ── Lyrics in the file's tags ───────────────────────────────────────────────────────────

    private class Mem(private val a: ByteArray) : EmbeddedLyrics.Parser.Bytes {
        override val length = a.size.toLong()
        override fun read(pos: Long, len: Int): ByteArray =
            if (pos >= a.size) ByteArray(0) else a.copyOfRange(pos.toInt(), minOf(a.size, pos.toInt() + len))
    }

    private fun ByteArrayOutputStream.be32(v: Int) = apply { write(v ushr 24); write(v ushr 16 and 0xFF); write(v ushr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Int) = apply { write(v and 0xFF); write(v ushr 8 and 0xFF); write(v ushr 16 and 0xFF); write(v ushr 24) }
    private fun ByteArrayOutputStream.str(s: String) = apply { write(s.toByteArray(Charsets.ISO_8859_1)) }
    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())

    private fun vorbisComments(vararg fields: String) = ByteArrayOutputStream().apply {
        le32(3); str("enc"); le32(fields.size)
        fields.forEach { val b = it.toByteArray(); le32(b.size); write(b) }
    }.toByteArray()

    @Test fun lyricsFromId3Usl() {
        val text = "Ligne une\nLigne deux é"
        val body = ByteArrayOutputStream().apply { write(3); str("fra"); write(0); write(text.toByteArray()) }.toByteArray()
        val frame = ByteArrayOutputStream().apply { str("TIT2"); write(syncsafe(2)); write(0); write(0); write(3); str("x") }
            .apply { str("USLT"); write(syncsafe(body.size)); write(0); write(0); write(body) }.toByteArray()
        val tag = ByteArrayOutputStream().apply { str("ID3"); write(4); write(0); write(0); write(syncsafe(frame.size + 20)); write(frame); write(ByteArray(20)) }.toByteArray()
        assertEquals(text, EmbeddedLyrics.Parser.find(Mem(tag + ByteArray(100))))
    }

    @Test fun lyricsFromFlacAndOggComments() {
        val lrc = "[00:01.00]a\n[00:02.00]b\n[00:03.00]c"
        val block = vorbisComments("TITLE=x", "lyrics=$lrc")
        val flac = ByteArrayOutputStream().apply {
            str("fLaC"); write(0); write(0); write(0); write(34); write(ByteArray(34)) // STREAMINFO
            write(0x84); write(block.size ushr 16); write(block.size ushr 8 and 0xFF); write(block.size and 0xFF); write(block)
        }.toByteArray()
        assertEquals(lrc, EmbeddedLyrics.Parser.find(Mem(flac)))
        assertTrue(EmbeddedLyrics.toResult(lrc) is Lyrics.Result.Synced)
        assertTrue(EmbeddedLyrics.toResult("just words") is Lyrics.Result.Plain)
        assertNull(EmbeddedLyrics.toResult("  "))

        // Ogg Opus: packet 0 "OpusHead" on page 1, packet 1 "OpusTags" split over two pages.
        val tags = "OpusTags".toByteArray() + vorbisComments("UNSYNCEDLYRICS=" + "la ".repeat(150))
        fun page(segments: List<Int>, payload: ByteArray) = ByteArrayOutputStream().apply {
            str("OggS"); write(ByteArray(22)); write(segments.size); segments.forEach { write(it) }; write(payload)
        }.toByteArray()
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val first = tags.copyOfRange(0, 255)
        val rest = tags.copyOfRange(255, tags.size)
        val ogg = page(listOf(head.size), head) + page(listOf(255), first) + page(listOf(rest.size), rest)
        assertEquals("la ".repeat(150), EmbeddedLyrics.Parser.find(Mem(ogg)))
    }

    @Test fun lyricsFromMp4Atom() {
        fun atom(type: String, body: ByteArray) = ByteArrayOutputStream().apply {
            be32(8 + body.size); write(type.toByteArray(Charsets.ISO_8859_1)); write(body)
        }.toByteArray()
        val data = atom("data", ByteArray(8) + "Hello\nWorld".toByteArray())
        val ilst = atom("ilst", atom("©nam", atom("data", ByteArray(8) + "T".toByteArray())) + atom("©lyr", data))
        val meta = atom("meta", ByteArray(4) + atom("hdlr", ByteArray(25)) + ilst)
        val file = atom("ftyp", "M4A ".toByteArray() + ByteArray(4)) + atom("mdat", ByteArray(50)) + atom("moov", atom("mvhd", ByteArray(100)) + atom("udta", meta))
        assertEquals("Hello\nWorld", EmbeddedLyrics.Parser.find(Mem(file)))
        assertNull(EmbeddedLyrics.Parser.find(Mem("RIFF....WAVE".toByteArray())))
    }
}
