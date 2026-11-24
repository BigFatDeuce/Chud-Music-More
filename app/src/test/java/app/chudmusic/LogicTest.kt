package app.chudmusic

import app.chudmusic.audio.Eq
import app.chudmusic.data.LyricsCacheEntity
import app.chudmusic.data.LyricsDao
import app.chudmusic.library.Library
import app.chudmusic.lyrics.Lyrics
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.ui.Incoming
import app.chudmusic.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    /** Runs a sine through one biquad (direct form I) and returns the gain in dB, after settling. */
    private fun measuredGainDb(c: DoubleArray, freq: Double, rate: Double = 48000.0): Double {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var inSq = 0.0; var outSq = 0.0
        val n = 48000
        for (i in 0 until n) {
            val x = sin(2 * PI * freq * i / rate)
            val y = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            if (i > n / 2) { inSq += x * x; outSq += y * y }
        }
        return 20 * log10(sqrt(outSq / inSq))
    }

    @Test fun peakingBandGivesItsGainAtItsFrequency() {
        val c = Eq.coefficients(Eq.Kind.PEAKING, 1000.0, 6.0, 48000.0)
        assertEquals(6.0, measuredGainDb(c, 1000.0), 0.05)
        assertEquals(0.0, measuredGainDb(c, 100.0), 0.3)
    }

    @Test fun shelvesBoostTheirEnd() {
        val low = Eq.coefficients(Eq.Kind.LOW_SHELF, 60.0, 9.0, 48000.0)
        assertEquals(9.0, measuredGainDb(low, 20.0), 0.5)
        assertEquals(0.0, measuredGainDb(low, 5000.0), 0.1)
        val high = Eq.coefficients(Eq.Kind.HIGH_SHELF, 16000.0, -6.0, 48000.0)
        assertEquals(0.0, measuredGainDb(high, 200.0), 0.1)
    }

    @Test fun curveMatchesTheFiltersAndThePreamp() {
        val flat = FloatArray(10)
        assertEquals(-3.0, Eq.responseDb(-3f, flat, 1000.0), 1e-9)
        val gains = FloatArray(10).also { it[4] = 6f } // 1 kHz band
        assertEquals(6.0, Eq.responseDb(0f, gains, 1000.0), 0.05)
        // The on-screen curve and the real filter agree.
        val c = Eq.coefficients(Eq.Kind.PEAKING, 1000.0, 6.0, 48000.0)
        assertEquals(Eq.responseDb(0f, gains, 2500.0), measuredGainDb(c, 2500.0), 0.1)
    }

    @Test fun presetsAreTheTwelveOfThePcWithinRange() {
        assertEquals(12, Eq.PRESETS.size)
        for ((pre, bands) in Eq.PRESETS) {
            assertEquals(10, bands.size)
            assertTrue(bands.all { abs(it) <= Eq.RANGE_DB } && abs(pre) <= Eq.RANGE_DB)
        }
    }
}

class LyricsTest {
    private val dao = object : LyricsDao {
        val map = HashMap<String, LyricsCacheEntity>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(e: LyricsCacheEntity) { map[e.key] = e }
    }

    @Test fun parsesLrcWithSeveralStampsAndFractions() {
        val lines = Lyrics.parseLrc(
            """
            [ar:Linkin Park]
            [00:12.30]I'm tired of being what you want me to be
            [00:17.5][01:20.75]Feeling so faithless
            [00:21]
            [01:02.123]Lost under the surface
            """.trimIndent(),
        )
        assertEquals(listOf(12_300L, 17_500L, 21_000L, 62_123L, 80_750L), lines.map { it.timeMs })
        assertEquals("Feeling so faithless", lines[1].text)
        assertEquals("", lines[2].text)
        assertEquals(-1, Lyrics.currentLine(lines, 5_000))
        assertEquals(1, Lyrics.currentLine(lines, 18_000))
        assertEquals(4, Lyrics.currentLine(lines, 999_000))
    }

    @Test fun cleansYouTubeAndRadioTitles() {
        val l = Lyrics(dao)
        val yt = Track.youtube("x", "Numb (Official Music Video) [4K Upgrade]", "Linkin Park")
        assertEquals("Numb" to "Linkin Park", l.clean(yt))
        val feat = Track.youtube("y", "Stay (feat. Justin Bieber)", "The Kid LAROI - Topic")
        assertEquals("Stay" to "The Kid LAROI", l.clean(feat))
        val radio = Track("icy", Source.RADIO, "u", "Daft Punk - One More Time")
        assertEquals("One More Time" to "Daft Punk", l.clean(radio))
        val local = Track("l", Source.LOCAL, "u", "Hey Jude - Remastered 2015", "The Beatles, Paul McCartney")
        assertEquals("Hey Jude - Remastered 2015" to "The Beatles", l.clean(local))
    }
}

class SmallLogicTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(Updater.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(Updater.compareVersions("1.0.0", "1.0") == 0)
        assertTrue(Updater.compareVersions("1.2.0-beta", "1.2.0") < 0)
        assertTrue(Updater.compareVersions("0.1.0", "0.1.1") < 0)
    }

    @Test fun youtubeLinksFromSharedText() {
        assertEquals("kXYiU_JCYtU", Incoming.videoIdOf("Écoute ça https://music.youtube.com/watch?v=kXYiU_JCYtU&si=abc"))
        assertEquals("kXYiU_JCYtU", Incoming.videoIdOf("https://youtu.be/kXYiU_JCYtU?t=10"))
        assertEquals("kXYiU_JCYtU", Incoming.videoIdOf("https://www.youtube.com/shorts/kXYiU_JCYtU"))
        assertNull(Incoming.videoIdOf("https://example.com/watch"))
    }

    @Test fun albumsSplitByFolderAndJoinDiscFolders() {
        fun t(id: String, folder: String, disc: Int? = null, no: Int? = null) =
            Track(id, Source.LOCAL, "u", id, "Linkin Park", "Reanimation", albumArtist = "Linkin Park", folder = folder, discNo = disc, trackNo = no)
        val tracks = listOf(
            t("a", "/Music/LP/Reanimation/CD1", 1, 2), t("b", "/Music/LP/Reanimation/CD2", 2, 1), t("c", "/Music/LP/Reanimation/CD1", 1, 1),
            t("d", "/Music/Other/Reanimation"),
        )
        val albums = Library.albumsOf(tracks)
        assertEquals(2, albums.size)
        assertEquals(setOf(3, 1), albums.mapTo(HashSet()) { it.count })
        assertEquals(listOf("c", "a", "b"), tracks.take(3).sortedWith(Library.albumOrder).map { it.id })
        assertEquals("Linkin Park", Library.mainArtist("Linkin Park feat. Jay-Z & Others"))
    }

    @Test fun homeShowsWhatTheUserKept() {
        val all = app.chudmusic.ui.home.HomeSection.entries.toSet()
        assertEquals(all, app.chudmusic.ui.home.HomeSection.shown(emptySet()))
        val shown = app.chudmusic.ui.home.HomeSection.shown(setOf("RANDOM", "RADIO_FAVORITES"))
        assertEquals(all - app.chudmusic.ui.home.HomeSection.RANDOM - app.chudmusic.ui.home.HomeSection.RADIO_FAVORITES, shown)
        // A name stored by another version that no longer exists changes nothing.
        assertEquals(all, app.chudmusic.ui.home.HomeSection.shown(setOf("SOMETHING_OLD")))
    }

    @Test fun emptyHomeIsWhenNothingShownHasContent() {
        val h = app.chudmusic.ui.home.HomeSection
        val random = app.chudmusic.ui.home.HomeSection.RANDOM
        val playlists = app.chudmusic.ui.home.HomeSection.PLAYLISTS
        // Random has tracks but is hidden; Playlists is shown but has none: Home is empty (logo).
        assertTrue(h.visible(h.shown(setOf("RANDOM")), setOf(random)).isEmpty())
        assertTrue(h.visible(app.chudmusic.ui.home.HomeSection.entries.toSet(), emptySet()).isEmpty())
        assertEquals(setOf(playlists), h.visible(h.shown(setOf("RANDOM")), setOf(random, playlists)))
    }
}
