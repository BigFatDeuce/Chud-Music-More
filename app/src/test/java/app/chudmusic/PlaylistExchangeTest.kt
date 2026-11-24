package app.chudmusic

import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.playlists.PlaylistExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist files exchanged with Chud Music PC. The JSON below is what the PC's exportJson()
 * writes (src/main/playlists.ts: JSON.stringify(data, null, 2), Windows paths, YouTube entries).
 */
class PlaylistExchangeTest {
    private val nas = 7L

    /** The phone's library: one phone file, three files on the SMB share "\\NAS\Musique" (source 7). */
    private val library = listOf(
        Track("local:1", Source.LOCAL, "content://media/external/audio/media/1", "Foreword", "Linkin Park", "Meteora",
            durationMs = 13_000, path = "/storage/emulated/0/Music/Linkin Park/Meteora/01 Foreword.mp3"),
        Track("net:7:Linkin Park/Meteora/02 Don't Stay.flac", Source.NETWORK, "chud-net://7/Linkin%20Park/Meteora/02%20Don't%20Stay.flac",
            "Don't Stay", "Linkin Park", "Meteora", durationMs = 187_000, path = "Linkin Park/Meteora/02 Don't Stay.flac", sourceId = nas),
        Track("net:7:Daft Punk/Discovery/01 One More Time.flac", Source.NETWORK, "chud-net://7/x", "One More Time", "Daft Punk", "Discovery",
            durationMs = 320_000, path = "Daft Punk/Discovery/01 One More Time.flac", sourceId = nas),
        Track("net:7:Misc/numb.flac", Source.NETWORK, "chud-net://7/y", "Numb", "Linkin Park", "Meteora",
            durationMs = 185_000, path = "Misc/numb.flac", sourceId = nas),
    )

    private val resolver = object : PlaylistExchange.Resolver {
        override val library = this@PlaylistExchangeTest.library
        override fun fromUnc(host: String, share: String, rest: String, hint: Track): Track? {
            if (!host.equals("NAS", true) || !share.equals("Musique", true)) return null
            return library.firstOrNull { it.sourceId == nas && it.path.equals(rest, true) }
                ?: Track("net:7:$rest", Source.NETWORK, "chud-net://7/$rest", hint.title, hint.artist, path = rest, sourceId = nas)
        }
    }

    private val pcJson = """
        {
          "format": "chud-music-playlists",
          "version": 1,
          "exportedAt": "2026-10-02T18:44:10.512Z",
          "playlists": [
            {
              "name": "Route du soir",
              "tracks": [
                { "source": "local", "title": "Don't Stay", "artist": "Linkin Park", "album": "Meteora", "durationSec": 187.4, "path": "\\\\NAS\\Musique\\Linkin Park\\Meteora\\02 Don't Stay.flac" },
                { "source": "local", "title": "Foreword", "artist": "Linkin Park", "album": "Meteora", "durationSec": 13.1, "path": "D:\\Musique\\Linkin Park\\Meteora\\01 Foreword.mp3" },
                { "source": "local", "title": "One More Time", "artist": "Daft Punk", "album": "Discovery", "durationSec": 320.2, "path": "E:\\Rips\\OMT.flac" },
                { "source": "youtube", "title": "Numb", "artist": "Linkin Park", "album": "Meteora", "durationSec": 186, "videoId": "kXYiU_JCYtU", "coverUrl": "https://lh3.googleusercontent.com/abc" },
                { "source": "local", "title": "Inconnu", "artist": "Personne", "album": null, "durationSec": null, "path": "C:\\Users\\Moi\\Music\\nulle-part.mp3" }
              ]
            },
            { "name": "Vide", "tracks": [] }
          ]
        }
    """.trimIndent()

    @Test fun importsPcJson() {
        val result = PlaylistExchange.parse("playlists.json", "\uFEFF" + pcJson, resolver)
        assertEquals(listOf("Route du soir", "Vide"), result.map { it.name })
        val p = result[0]
        assertEquals(4, p.found)
        assertEquals(1, p.missing)
        val t = p.tracks
        // 1. UNC path on a share added on the phone → that network file.
        assertEquals("net:7:Linkin Park/Meteora/02 Don't Stay.flac", t[0].id)
        // 2. Another drive letter, same Artist/Album/file → the phone file.
        assertEquals("local:1", t[1].id)
        // 3. Unknown path, same title/artist/duration (±5 s) → the NAS file.
        assertEquals("net:7:Daft Punk/Discovery/01 One More Time.flac", t[2].id)
        // 4. YouTube Music entry kept as is.
        assertEquals(Source.YOUTUBE, t[3].source)
        assertEquals("kXYiU_JCYtU", t[3].videoId)
        assertEquals(186_000L, t[3].durationMs)
        assertEquals("https://lh3.googleusercontent.com/abc", t[3].coverUri)
        // 5. Found nowhere: kept, flagged, original path preserved.
        assertFalse(t[4].available)
        assertEquals("C:\\Users\\Moi\\Music\\nulle-part.mp3", t[4].path)
        assertTrue(result[1].tracks.isEmpty())
    }

    @Test fun exportIsReadableByThePcAndRoundTrips() {
        val unc: (Track) -> String? = { t -> if (t.source == Source.NETWORK) "\\\\NAS\\Musique\\" + t.path!!.replace('/', '\\') else t.path }
        val yt = Track.youtube("kXYiU_JCYtU", "Numb", "Linkin Park", "Meteora", 186_000, "https://lh3.googleusercontent.com/abc")
        val radio = Track("radio:1", Source.RADIO, "https://stream", "FIP")
        val text = PlaylistExchange.toJson(listOf(PlaylistExchange.Out("Mix", listOf(library[1], yt, library[0], radio))), unc)

        // What the PC's importFile() reads: format, version, playlists[].tracks[] with source/title/path or videoId.
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals("chud-music-playlists", root["format"].toString().trim('"'))
        assertEquals("1", root["version"].toString())
        val tracks = (((root["playlists"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject)["tracks"] as kotlinx.serialization.json.JsonArray)
        assertEquals("radio is not exported", 3, tracks.size)
        val first = tracks[0] as kotlinx.serialization.json.JsonObject
        assertEquals("\"local\"", first["source"].toString())
        assertEquals("\"\\\\\\\\NAS\\\\Musique\\\\Linkin Park\\\\Meteora\\\\02 Don't Stay.flac\"", first["path"].toString())
        assertEquals("187.0", first["durationSec"].toString())
        val second = tracks[1] as kotlinx.serialization.json.JsonObject
        assertEquals("\"youtube\"", second["source"].toString())
        assertEquals("\"kXYiU_JCYtU\"", second["videoId"].toString())

        // And back on the phone: the same tracks.
        val back = PlaylistExchange.parse("x.json", text, resolver).single()
        assertEquals(listOf(library[1].id, yt.id, library[0].id), back.tracks.map { it.id })
        assertEquals(0, back.missing)
    }

    @Test fun m3uLikeThePc() {
        val unc: (Track) -> String? = { t -> if (t.source == Source.NETWORK) "\\\\NAS\\Musique\\" + t.path!!.replace('/', '\\') else t.path }
        val yt = Track.youtube("kXYiU_JCYtU", "Numb", "Linkin Park", durationMs = 186_000)
        val radio = Track("radio:1", Source.RADIO, "https://stream", "FIP")
        val (text, skipped) = PlaylistExchange.toM3u("Soir", listOf(library[1], yt, radio), unc)
        assertEquals(1, skipped)
        assertEquals(
            "#EXTM3U\r\n#PLAYLIST:Soir\r\n#EXTINF:187,Linkin Park - Don't Stay\r\n\\\\NAS\\Musique\\Linkin Park\\Meteora\\02 Don't Stay.flac\r\n" +
                "#EXTINF:186,Linkin Park - Numb\r\nhttps://music.youtube.com/watch?v=kXYiU_JCYtU\r\n",
            text,
        )
        val back = PlaylistExchange.parse("Soir.m3u8", text, resolver).single()
        assertEquals("Soir", back.name)
        assertEquals(listOf(library[1].id, yt.id), back.tracks.map { it.id })
        assertEquals("Numb", back.tracks[1].title)
        assertEquals("Linkin Park", back.tracks[1].artist)
    }

    @Test fun m3uFromAnotherPlayerWithoutExtinf() {
        val text = "Linkin Park/Meteora/01 Foreword.mp3\nhttps://youtu.be/kXYiU_JCYtU\n/nowhere/song.ogg\n"
        val p = PlaylistExchange.parse("My list.m3u", text, resolver).single()
        assertEquals("My list", p.name)
        assertEquals("local:1", p.tracks[0].id)
        assertEquals("yt:kXYiU_JCYtU", p.tracks[1].id)
        assertFalse(p.tracks[2].available)
        assertEquals("song", p.tracks[2].title)
    }

    @Test(expected = PlaylistExchange.NotChudFile::class)
    fun otherJsonIsRefused() {
        PlaylistExchange.parse("x.json", """{"format":"something-else","playlists":[]}""", resolver)
    }

    @Test fun ambiguousSuffixFallsBackToMetadata() {
        // Two files end with the same "Album/01.flac": the suffix is ambiguous, metadata decides.
        val lib = listOf(
            Track("a", Source.LOCAL, "u", "Intro", "A", "Album", durationMs = 60_000, path = "/m/A/Album/01.flac"),
            Track("b", Source.LOCAL, "u", "Intro", "B", "Album", durationMs = 90_000, path = "/m/B/Album/01.flac"),
        )
        val r = object : PlaylistExchange.Resolver {
            override val library = lib
            override fun fromUnc(host: String, share: String, rest: String, hint: Track): Track? = null
        }
        val text = "#EXTM3U\n#EXTINF:90,B - Intro\nX:\\Album\\01.flac\n"
        assertEquals("b", PlaylistExchange.parse("x.m3u8", text, r).single().tracks.single().id)
    }
}
