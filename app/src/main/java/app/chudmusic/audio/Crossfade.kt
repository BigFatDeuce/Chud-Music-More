package app.chudmusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import app.chudmusic.library.Library
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What the user chose in Settings › Playback. [seconds] 0 = off. */
data class CrossfadeSettings(val seconds: Int = 0, val keepAlbums: Boolean = true) {
    val on get() = seconds > 0

    companion object {
        const val MAX_SECONDS = 12

        /**
         * Whether [next] fades into [prev]'s end. Two tracks of an album that follow each other are
         * chained without a gap instead (live and classical albums), unless the user wants it otherwise.
         */
        fun between(prev: Track?, next: Track?, s: CrossfadeSettings): Boolean {
            if (!s.on || prev == null || next == null) return false
            if (prev.source == Source.RADIO || next.source == Source.RADIO) return false
            return !(s.keepAlbums && followsInAlbum(prev, next))
        }

        fun sameAlbum(a: Track, b: Track): Boolean = when {
            a.ytAlbumId != null && b.ytAlbumId != null -> a.ytAlbumId == b.ytAlbumId
            else -> Library.albumKey(a)?.let { it == Library.albumKey(b) } == true
        }

        /** The next track of the same disc, or the first of the next one; without numbers, the same album is enough. */
        fun followsInAlbum(a: Track, b: Track): Boolean {
            if (a.id == b.id || !sameAlbum(a, b)) return false
            val x = a.trackNo
            val y = b.trackNo
            if (x == null || y == null) return true
            val d1 = a.discNo ?: 1
            val d2 = b.discNo ?: 1
            return if (d1 == d2) y == x + 1 else d2 == d1 + 1 && y == 1
        }
    }
}

/**
 * Turns interleaved 16-bit PCM of any rate into [OUT_RATE] stereo (windowed sinc), so that two
 * tracks of different rates can be mixed. The state survives a track boundary: chained tracks of
 * one rate stay chained to the sample.
 */
class Resampler(val inRate: Int, private val inChannels: Int) {
    private val step = inRate.toDouble() / OUT_RATE
    private val same = inRate == OUT_RATE
    private val table = if (same) null else table(minOf(1.0, OUT_RATE.toDouble() / inRate))
    private var hist = FloatArray(TAPS * 2)
    private var frames = TAPS / 2 - 1 // zeros before the first sample: the filter is centred on it
    private var pos = 0.0

    /** Interleaved stereo floats (16-bit scale); the returned array holds [count] frames. */
    var out = FloatArray(0); private set
    var count = 0; private set

    fun process(input: ShortArray, n: Int) {
        if (same) {
            if (out.size < n * 2) out = FloatArray(n * 2)
            if (inChannels == 1) for (i in 0 until n) { val v = input[i].toFloat(); out[i * 2] = v; out[i * 2 + 1] = v }
            else for (i in 0 until n * 2) out[i] = input[i].toFloat()
            count = n
            return
        }
        feed(n) { i -> input[i].toFloat() }
    }

    /** The last samples, still held by the filter. */
    fun drain() {
        if (same) { count = 0; return }
        feed(TAPS / 2) { 0f }
    }

    private inline fun feed(n: Int, sample: (Int) -> Float) {
        val need = (frames + n) * 2
        if (hist.size < need) hist = hist.copyOf(need + 4096)
        for (i in 0 until n) {
            val b = (frames + i) * 2
            if (inChannels == 1) { val v = sample(i); hist[b] = v; hist[b + 1] = v }
            else { hist[b] = sample(i * 2); hist[b + 1] = sample(i * 2 + 1) }
        }
        frames += n
        val max = ((frames - TAPS) / step).toInt() + 4
        if (out.size < max * 2) out = FloatArray(max * 2 + 256)
        val t = table!!
        var c = 0
        while (true) {
            val base = floor(pos).toInt()
            if (base + TAPS > frames) break
            val f = (pos - base) * PHASES
            val p = f.toInt()
            val mix = (f - p).toFloat()
            val r0 = p * TAPS
            val r1 = r0 + TAPS
            var l = 0f
            var r = 0f
            for (k in 0 until TAPS) {
                val w = t[r0 + k] + (t[r1 + k] - t[r0 + k]) * mix
                l += hist[(base + k) * 2] * w
                r += hist[(base + k) * 2 + 1] * w
            }
            out[c * 2] = l
            out[c * 2 + 1] = r
            c++
            pos += step
        }
        count = c
        val drop = floor(pos).toInt().coerceAtMost(frames)
        System.arraycopy(hist, drop * 2, hist, 0, (frames - drop) * 2)
        frames -= drop
        pos -= drop
    }

    companion object {
        const val OUT_RATE = 48000
        private const val TAPS = 32
        private const val PHASES = 256

        private fun table(ratio: Double): FloatArray {
            val cut = ratio * 0.94
            val t = FloatArray((PHASES + 1) * TAPS)
            for (p in 0..PHASES) {
                var sum = 0.0
                for (k in 0 until TAPS) {
                    val x = k - (TAPS / 2 - 1) - p.toDouble() / PHASES
                    val sinc = if (abs(x) < 1e-9) cut else sin(PI * x * cut) / (PI * x)
                    // Blackman window over the filter's length.
                    val w = (x + TAPS / 2) / TAPS
                    val win = if (w <= 0 || w >= 1) 0.0 else 0.42 - 0.5 * cos(2 * PI * w) + 0.08 * cos(4 * PI * w)
                    val v = sinc * win
                    t[p * TAPS + k] = v.toFloat()
                    sum += v
                }
                for (k in 0 until TAPS) t[p * TAPS + k] = (t[p * TAPS + k] / sum).toFloat()
            }
            return t
        }
    }
}

/**
 * The end of a track, held back so that the next one can be mixed over it.
 *
 * Samples go through a line that keeps the last [target] frames: the line fills little by little
 * (half of what comes in goes out until it is full), so nothing is delayed when playback starts. At
 * a boundary, what it holds is either the tail the next track fades over ([carry]), or simply
 * played ([finish]). Everything is 16-bit stereo at [Resampler.OUT_RATE].
 */
class CrossfadeMixer {
    private var line = ShortArray(0)
    private var head = 0
    private var held = 0
    private var tail = ShortArray(0)
    private var tailFrames = 0
    private var fadePos = 0

    var target = 0
    val heldFrames get() = held
    val fading get() = fadePos < tailFrames

    fun clear() { head = 0; held = 0; tailFrames = 0; fadePos = 0 }

    /** The held frames become the tail of the fade; whatever is left of a fade still running is dropped. */
    fun carry() {
        tail = ShortArray(held * 2)
        take(held) { i, v -> tail[i] = v }
        tailFrames = tail.size / 2
        fadePos = 0
    }

    /** Mixes [n] stereo frames with the tail, puts them in the line, and gives what comes out of it to [emit]. */
    fun push(input: FloatArray, n: Int, emit: (index: Int, value: Short) -> Unit): Int {
        add(input, n)
        val out = if (held - n / 2 <= target) minOf(n / 2, held) else held - target
        take(out, emit)
        return out
    }

    /** [push] without letting anything out: the last frames of a track. */
    fun add(input: FloatArray, n: Int) {
        ensure(held + n)
        val cap = line.size / 2
        for (i in 0 until n) {
            var l = input[i * 2]
            var r = input[i * 2 + 1]
            if (fadePos < tailFrames) {
                // Equal power: the loudness stays even through the fade.
                val x = (fadePos + 0.5) / tailFrames * (PI / 2)
                val gi = sin(x).toFloat()
                val go = cos(x).toFloat()
                l = l * gi + tail[fadePos * 2] * go
                r = r * gi + tail[fadePos * 2 + 1] * go
                fadePos++
            }
            val at = (head + held) % cap
            line[at * 2] = l.coerceIn(-32768f, 32767f).toInt().toShort()
            line[at * 2 + 1] = r.coerceIn(-32768f, 32767f).toInt().toShort()
            held++
        }
    }

    /** Up to [max] held frames, played as they are. */
    fun finish(max: Int, emit: (index: Int, value: Short) -> Unit): Int {
        val n = minOf(max, held)
        take(n, emit)
        return n
    }

    private inline fun take(n: Int, emit: (index: Int, value: Short) -> Unit) {
        val cap = line.size / 2
        for (i in 0 until n) {
            emit(i * 2, line[head * 2])
            emit(i * 2 + 1, line[head * 2 + 1])
            head = (head + 1) % cap
        }
        held -= n
    }

    private fun ensure(frames: Int) {
        val cap = line.size / 2
        if (frames <= cap) return
        val bigger = ShortArray(maxOf(frames, cap * 2, 8192) * 2)
        for (i in 0 until held) {
            val from = (head + i) % cap
            bigger[i * 2] = line[from * 2]
            bigger[i * 2 + 1] = line[from * 2 + 1]
        }
        line = bigger
        head = 0
    }
}

/**
 * Crossfade inside the player's audio pipeline: one player, mixed to the sample, for every source.
 * [ChudAudioSink] says when a new track begins and whether it fades into the previous one.
 */
class CrossfadeProcessor : BaseAudioProcessor() {
    @Volatile var settings = CrossfadeSettings()

    private val mixer = CrossfadeMixer()
    private var resampler: Resampler? = null
    private var pendingRate = 0
    private var pendingChannels = 0
    private var channels = 0
    private var pendingOn = false
    /** Set between the end of a track and the start of the next: the tail is kept for it. */
    private var carry = false
    private var boundary = false
    private var draining = false
    /** Something came in since the last flush. The sink drains and flushes a second time at the start of every track. */
    private var fed = false
    private var pendingTarget = 0
    private var shorts = ShortArray(0)

    /** Before the sink is configured for [next]: [fade] = it is mixed with the end of the current track. */
    fun beginTrack(next: Track?, fade: Boolean) {
        boundary = true
        carry = fade
        val s = settings
        // Nothing is held back of a live radio; a short track is never faded over more than a third of it.
        val frames = s.seconds.toLong() * Resampler.OUT_RATE
        pendingOn = s.on
        pendingTarget = when {
            !s.on || next == null || next.source == Source.RADIO -> 0
            else -> minOf(frames, next.durationMs?.takeIf { it > 0 }?.let { it * Resampler.OUT_RATE / 1000 / 3 } ?: frames).toInt()
        }
    }

    /** A seek, a skip, a stop: what is held belongs to what is no longer played. */
    fun discard() {
        mixer.clear()
        resampler = null
        carry = false
        draining = false
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!pendingOn || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount > 2) {
            pendingOn = false
            carry = false
            return AudioProcessor.AudioFormat.NOT_SET
        }
        pendingRate = inputAudioFormat.sampleRate
        pendingChannels = inputAudioFormat.channelCount
        return AudioProcessor.AudioFormat(Resampler.OUT_RATE, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onQueueEndOfStream() {
        if (draining) return
        draining = true
        // Nothing yet: the filter keeps what it holds of the previous track, which this one continues.
        if (!fed) return
        val r = resampler ?: return
        // A track of the same format follows: the filter goes on over the boundary.
        if (boundary && pendingOn && !carry && r.inRate == pendingRate && channels == pendingChannels) return
        r.drain()
        if (r.count > 0) mixer.add(r.out, r.count)
        resampler = null
        if (carry) mixer.carry()
    }

    override fun getOutput(): ByteBuffer {
        if (draining && super.isEnded() && mixer.heldFrames > 0) {
            write(minOf(CHUNK, mixer.heldFrames)) { b -> mixer.finish(CHUNK) { _, v -> b.putShort(v) } }
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && mixer.heldFrames == 0

    private inline fun write(frames: Int, block: (ByteBuffer) -> Unit) {
        val b = replaceOutputBuffer(frames * 4).order(ByteOrder.nativeOrder())
        block(b)
        b.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        mixer.target = pendingTarget
        carry = false
        boundary = false
        draining = false
        fed = false
        channels = pendingChannels
        if (pendingOn && resampler?.inRate != pendingRate) resampler = Resampler(pendingRate, pendingChannels)
    }

    override fun onReset() {
        discard()
        shorts = ShortArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val r = resampler
        val bytes = inputBuffer.remaining()
        if (bytes == 0 || r == null) return
        fed = true
        val n = bytes / 2
        if (shorts.size < n) shorts = ShortArray(n)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts, 0, n)
        inputBuffer.position(inputBuffer.limit())
        r.process(shorts, n / channels.coerceAtLeast(1))
        if (r.count == 0) return
        val count = r.count
        // Room for everything held: the line gets shorter when the setting does.
        val room = count + mixer.heldFrames
        val b = replaceOutputBuffer(room * 4).order(ByteOrder.nativeOrder())
        mixer.push(r.out, count) { _, v -> b.putShort(v) }
        b.flip()
    }

    private companion object { const val CHUNK = 16384 }
}
