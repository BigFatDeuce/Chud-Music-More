package app.chudmusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.chudmusic.audio.CrossfadeProcessor
import app.chudmusic.audio.CrossfadeSettings
import app.chudmusic.audio.LoudnessMeter
import app.chudmusic.audio.NormalizerProcessor
import app.chudmusic.audio.ReplayGain
import app.chudmusic.audio.Resampler
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.playback.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Crossfade, gapless, volume normalization and sleep timer, on real samples. */
class AudioTest {
    private fun track(id: String, album: String? = null, no: Int? = null, seconds: Int = 100, disc: Int? = null, source: Source = Source.LOCAL) =
        Track(id, source, "u", id, "A", album, albumArtist = "A", trackNo = no, discNo = disc, folder = "/m/$album", durationMs = seconds * 1000L)

    // ── The audio pipeline, as DefaultAudioSink drives it ───────────────────────────────────

    /** Plays [tracks] one after the other through [p], the way the sink does at each boundary. */
    private fun play(p: CrossfadeProcessor, tracks: List<Triple<Track, Int, ShortArray>>, channels: Int = 1): ShortArray {
        val out = ArrayList<Short>()
        fun pull() { while (true) { val b = p.output; if (!b.hasRemaining()) break; val s = b.order(ByteOrder.nativeOrder()).asShortBuffer(); while (s.hasRemaining()) out += s.get() ; b.position(b.limit()) } }
        fun drain() { p.queueEndOfStream(); var guard = 0; while (!p.isEnded) { pull(); check(++guard < 100_000) { "never ends" } }; pull() }
        var prev: Track? = null
        var active = false
        for ((t, rate, pcm) in tracks) {
            p.beginTrack(t, CrossfadeSettings.between(prev, t, p.settings))
            p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
            if (prev != null && active) drain()
            p.flush(AudioProcessor.StreamMetadata.DEFAULT)
            active = p.isActive
            // DefaultAudioSink.handleDiscontinuity: drained and flushed once more before the first sample.
            if (active) { drain(); p.flush(AudioProcessor.StreamMetadata.DEFAULT) }
            var at = 0
            while (at < pcm.size) {
                val n = minOf(1152 * channels, pcm.size - at)
                val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
                for (i in 0 until n) b.putShort(pcm[at + i])
                b.flip()
                p.queueInput(b)
                pull()
                at += n
            }
            prev = t
        }
        drain()
        return out.toShortArray()
    }

    private fun tone(rate: Int, seconds: Double, hz: Double, amp: Int = 12000, phase: Int = 0) =
        ShortArray((rate * seconds).roundToInt()) { i -> (amp * sin(2 * PI * hz * (i + phase) / rate)).roundToInt().toShort() }

    private fun left(stereo: ShortArray) = ShortArray(stereo.size / 2) { stereo[it * 2] }
    private fun rms(s: ShortArray, from: Int, n: Int): Double { var e = 0.0; for (i in from until from + n) e += s[i].toDouble() * s[i]; return sqrt(e / n) }

    @Test fun crossfadeShortensByItsLengthAndKeepsTheLevel() {
        val p = CrossfadeProcessor().apply { settings = CrossfadeSettings(4) }
        val out = left(play(p, listOf(Triple(track("a", "X"), 48000, tone(48000, 20.0, 440.0)), Triple(track("b", "Y"), 48000, tone(48000, 20.0, 570.0)))))
        assertEquals(36 * 48000, out.size)
        // Equal power: two different tones stay as loud together as one of them alone.
        val alone = rms(out, 48000, 48000)
        for (sec in listOf(16.5, 17.5, 18.5, 19.5)) assertEquals(alone, rms(out, (sec * 48000).toInt() - 12000, 24000), alone * 0.08)
        // No click: never a jump larger than the two tones can make.
        for (i in 1 until out.size) assertTrue("jump at $i", abs(out[i] - out[i - 1]) < 2500)
    }

    @Test fun crossfadeBetweenTwoRates() {
        val p = CrossfadeProcessor().apply { settings = CrossfadeSettings(5) }
        val out = left(play(p, listOf(Triple(track("a"), 44100, tone(44100, 20.0, 440.0)), Triple(track("b"), 48000, tone(48000, 20.0, 440.0)))))
        assertEquals(35 * 48000.0, out.size.toDouble(), 40.0)
        for (i in 1 until out.size) assertTrue(abs(out[i] - out[i - 1]) < 2500)
    }

    @Test fun tracksOfAnAlbumAreChainedToTheSample() {
        // One continuous tone cut in two, as a live album is: after the cut nothing must show.
        for (rate in listOf(48000, 44100)) {
            val whole = tone(rate, 20.0, 441.0)
            val cut = rate * 10 + 123
            val p = CrossfadeProcessor().apply { settings = CrossfadeSettings(6) }
            val two = play(p, listOf(Triple(track("a", "Live", 1), rate, whole.copyOfRange(0, cut)), Triple(track("b", "Live", 2), rate, whole.copyOfRange(cut, whole.size))))
            val one = play(CrossfadeProcessor().apply { settings = CrossfadeSettings(6) }, listOf(Triple(track("a", "Live", 1), rate, whole)))
            assertEquals(one.size, two.size)
            for (i in one.indices) assertTrue("rate $rate, sample $i", abs(one[i] - two[i]) <= 1)
        }
    }

    @Test fun theEqualizerDoesNotClickBetweenTwoTracksOfAnAlbum() {
        val f = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        fun run(cut: Int?): List<Short> {
            val p = app.chudmusic.audio.EqualizerProcessor().apply { set(true, 0f, FloatArray(10) { 6f }) }
            val pcm = tone(44100, 2.0, 60.0, 6000)
            val out = ArrayList<Short>()
            for (part in if (cut == null) listOf(pcm) else listOf(pcm.copyOfRange(0, cut), pcm.copyOfRange(cut, pcm.size))) {
                p.configure(f); p.flush(AudioProcessor.StreamMetadata.DEFAULT)
                val b = ByteBuffer.allocateDirect(part.size * 2).order(ByteOrder.nativeOrder())
                part.forEach { b.putShort(it) }
                b.flip(); p.queueInput(b)
                val o = p.output.order(ByteOrder.nativeOrder()); while (o.hasRemaining()) out += o.short
                p.queueEndOfStream()
            }
            return out
        }
        assertEquals(run(null), run(40_000))
    }

    @Test fun nothingIsChangedWhenCrossfadeIsOff() {
        val p = CrossfadeProcessor()
        p.beginTrack(track("a"), false)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(p.isActive)
    }

    @Test fun turnedOnOrOffBetweenTwoTracksLosesNothing() {
        val p = CrossfadeProcessor().apply { settings = CrossfadeSettings(5) }
        val a = tone(48000, 20.0, 440.0)
        p.beginTrack(track("a"), false)
        p.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        var frames = 0
        fun pull() { while (true) { val b = p.output; if (!b.hasRemaining()) break; frames += b.remaining() / 4; b.position(b.limit()) } }
        for (at in a.indices step 1000) {
            val b = ByteBuffer.allocateDirect(2000).order(ByteOrder.nativeOrder())
            for (i in 0 until 1000) b.putShort(a[at + i])
            b.flip(); p.queueInput(b); pull()
        }
        assertEquals(15 * 48000, frames)
        // Off in Settings before the next track: what is held comes out.
        p.settings = CrossfadeSettings(0)
        p.beginTrack(track("b"), CrossfadeSettings.between(track("a"), track("b"), p.settings))
        p.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        p.queueEndOfStream()
        while (!p.isEnded) pull()
        assertEquals(20 * 48000, frames)
    }

    @Test fun aShortTrackIsNotSwallowedByTheFade() {
        val p = CrossfadeProcessor().apply { settings = CrossfadeSettings(12) }
        val out = left(play(p, listOf(Triple(track("a", seconds = 6), 48000, tone(48000, 6.0, 440.0)), Triple(track("b"), 48000, tone(48000, 20.0, 500.0)))))
        // A third of it at most: 2 s.
        assertEquals(24 * 48000, out.size)
    }

    @Test fun aSeekDropsWhatIsHeld() {
        val p = CrossfadeProcessor().apply { settings = CrossfadeSettings(5) }
        p.beginTrack(track("a"), false)
        p.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val b = ByteBuffer.allocateDirect(96000).order(ByteOrder.nativeOrder())
        repeat(48000) { b.putShort(1000) }
        b.flip(); p.queueInput(b); p.output.let { it.position(it.limit()) }
        p.discard()
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.queueEndOfStream()
        assertTrue(p.isEnded)
    }

    @Test fun whichTracksFadeIntoEachOther() {
        val on = CrossfadeSettings(6)
        assertTrue(CrossfadeSettings.between(track("a", "X", 1), track("b", "Y", 1), on))
        assertFalse("off", CrossfadeSettings.between(track("a"), track("b"), CrossfadeSettings(0)))
        assertFalse("album order", CrossfadeSettings.between(track("a", "X", 1), track("b", "X", 2), on))
        assertTrue("same album, shuffled", CrossfadeSettings.between(track("a", "X", 1), track("b", "X", 7), on))
        assertFalse("next disc", CrossfadeSettings.between(track("a", "X", 12, disc = 1), track("b", "X", 1, disc = 2), on))
        assertTrue("albums not kept", CrossfadeSettings.between(track("a", "X", 1), track("b", "X", 2), CrossfadeSettings(6, keepAlbums = false)))
        assertFalse("radio", CrossfadeSettings.between(track("a"), track("r", source = Source.RADIO), on))
        assertFalse("first track", CrossfadeSettings.between(null, track("a"), on))
    }

    @Test fun resamplingKeepsTheTone() {
        val r = Resampler(44100, 1)
        val pcm = tone(44100, 2.0, 1000.0)
        val got = ArrayList<Float>()
        for (at in pcm.indices step 900) {
            val n = minOf(900, pcm.size - at)
            r.process(pcm.copyOfRange(at, at + n), n)
            for (i in 0 until r.count) got += r.out[i * 2]
        }
        r.drain()
        for (i in 0 until r.count) got += r.out[i * 2]
        assertEquals(96000.0, got.size.toDouble(), 3.0)
        val want = tone(48000, 2.0, 1000.0)
        for (i in 500 until 95000) assertEquals("sample $i", want[i].toDouble(), got[i].toDouble(), 40.0)
    }

    // ── Volume normalization ────────────────────────────────────────────────────────────────

    @Test fun loudnessOfTheReferenceSignal() {
        // EBU Tech 3341, case 1: a stereo 1 kHz sine at -23 dBFS for 20 s measures -23 LUFS.
        val amp = Math.pow(10.0, -23.0 / 20)
        for (rate in listOf(44100, 48000)) {
            val m = LoudnessMeter(rate, 2)
            for (i in 0 until rate * 20) { val v = (amp * sin(2 * PI * 1000 * i / rate)).toFloat(); m.add(v, 0); m.add(v, 1) }
            assertEquals(-23.0, m.lufs()!!.toDouble(), 0.1)
            assertEquals(amp, m.peak.toDouble(), 0.001)
        }
        // Case 3: the quiet parts are gated out.
        val m = LoudnessMeter(48000, 2)
        fun add(db: Double, s: Int) { val a = Math.pow(10.0, db / 20); for (i in 0 until 48000 * s) { val v = (a * sin(2 * PI * 1000 * i / 48000)).toFloat(); m.add(v, 0); m.add(v, 1) } }
        add(-36.0, 10); add(-23.0, 60); add(-36.0, 10)
        assertEquals(-23.0, m.lufs()!!.toDouble(), 0.1)
        assertNull(LoudnessMeter(48000, 2).also { s -> repeat(48000 * 4) { s.add(0f, it % 2) } }.lufs())
    }

    @Test fun replayGainTagsOfEveryFormat() {
        val t = ReplayGain.tags(listOf("replaygain_track_gain" to "-7.89 dB", "REPLAYGAIN_TRACK_PEAK" to "0.988553", "replaygain_album_gain" to "-6,5 dB", "artist" to "12"))
        assertEquals(-7.89f, t.track!!, 0.001f)
        assertEquals(0.988553f, t.trackPeak!!, 0.0001f)
        assertEquals(-6.5f, t.album!!, 0.001f)
        // Opus: 1/256 dB against -23 LUFS.
        assertEquals(1f, ReplayGain.tags(listOf("R128_TRACK_GAIN" to "-1024")).track!!, 0.001f)
        assertTrue(ReplayGain.tags(listOf("REPLAYGAIN_TRACK_GAIN" to "n/a", "REPLAYGAIN_ALBUM_GAIN" to "900 dB")).isEmpty)
    }

    @Test fun replayGainNeverClipsNorBlasts() {
        fun db(f: Float) = 20 * Math.log10(f.toDouble())
        val quiet = ReplayGain.Tags(track = 6f, trackPeak = 0.8f, album = -3f, albumPeak = 1f)
        assertEquals(1f, ReplayGain.factor(ReplayGain.Mode.OFF, quiet, null), 0f)
        assertEquals("held by its peak", 1 / 0.8f, ReplayGain.factor(ReplayGain.Mode.TRACK, quiet, null), 0.001f)
        assertEquals(-3.0, db(ReplayGain.factor(ReplayGain.Mode.ALBUM, quiet, null)), 0.01)
        assertEquals("no peak: never raised", 1f, ReplayGain.factor(ReplayGain.Mode.TRACK, ReplayGain.Tags(track = 6f), null), 0f)
        assertEquals("measured", -8.0, db(ReplayGain.factor(ReplayGain.Mode.TRACK, ReplayGain.Tags(), ReplayGain.Measure(-10f, 1f))), 0.01)
        assertEquals("unknown", -6.0, db(ReplayGain.factor(ReplayGain.Mode.TRACK, ReplayGain.Tags(), null)), 0.01)
    }

    @Test fun normalizerAppliesTheGainAndMeasuresUntaggedTracks() {
        val p = NormalizerProcessor().apply { mode = ReplayGain.Mode.TRACK }
        val measured = HashMap<String, ReplayGain.Measure>()
        p.onMeasured = { id, m -> measured[id] = m }
        val f = AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)
        fun run(piece: NormalizerProcessor.Piece, pcm: ShortArray): ShortArray {
            p.beginTrack(piece); p.configure(f); p.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val out = ArrayList<Short>()
            for (at in pcm.indices step 4800) {
                val b = ByteBuffer.allocateDirect(9600).order(ByteOrder.nativeOrder())
                for (i in 0 until 4800) b.putShort(pcm[at + i])
                b.flip(); p.queueInput(b)
                val o = p.output.order(ByteOrder.nativeOrder()); while (o.hasRemaining()) out += o.short
            }
            p.queueEndOfStream()
            return out.toShortArray()
        }
        val pcm = tone(48000, 20.0, 1000.0, 10000)
        val tagged = run(NormalizerProcessor.Piece("t", ReplayGain.Tags(track = -6.0206f, trackPeak = 1f), null, 20_000), pcm)
        assertEquals(rms(pcm, 0, 48000) / 2, rms(tagged, 0, 48000), 5.0)
        assertTrue("tagged: not measured", measured.isEmpty())
        run(NormalizerProcessor.Piece("u", ReplayGain.Tags(), null, 20_000), pcm)
        // One channel: 3 dB less than the same on two.
        assertEquals(20 * Math.log10(10000 / 32768.0) - 3.01, measured["u"]!!.lufs.toDouble(), 0.15)
        run(NormalizerProcessor.Piece("half", ReplayGain.Tags(), null, 60_000), pcm)
        assertFalse("a third heard is not a measure", "half" in measured)
    }

    // ── Sleep timer ─────────────────────────────────────────────────────────────────────────

    @Test fun sleepTimerStates() {
        var now = 1_000_000L
        val t = SleepTimer { now }
        assertEquals(SleepTimer.State.Off, t.state.value)
        t.inMinutes(30)
        assertEquals(SleepTimer.State.At(now + 30 * 60_000), t.state.value)
        t.inMinutes(0)
        assertEquals("one minute at least", SleepTimer.State.At(now + 60_000), t.state.value)
        t.atEndOfAlbum()
        assertEquals(SleepTimer.State.EndOfAlbum, t.state.value)
        t.off()
        assertEquals(SleepTimer.State.Off, t.state.value)
        now += 1
        assertEquals(1f, SleepTimer.volume(60_000), 0f)
        assertEquals(0.5f, SleepTimer.volume(5_000), 0.01f)
        assertEquals(0f, SleepTimer.volume(-5), 0f)
    }

    @Test fun sleepTimerKnowsTheEndOfAnAlbum() {
        val a = track("a", "X", 1)
        val b = track("b", "X", 2)
        val c = track("c", "Y", 1)
        val end = SleepTimer.State.EndOfAlbum
        assertFalse(SleepTimer.stopsAfter(end, a, b))
        assertTrue(SleepTimer.stopsAfter(end, b, c))
        assertTrue("end of the queue", SleepTimer.stopsAfter(end, b, null))
        assertTrue(SleepTimer.stopsAfter(SleepTimer.State.EndOfTrack, a, b))
        assertFalse(SleepTimer.stopsAfter(SleepTimer.State.Off, a, b))
        assertFalse(SleepTimer.stopsAfter(SleepTimer.State.At(0), a, b))
    }
}
