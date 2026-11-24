package app.chudmusic.audio

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** Volume normalization: the gain of each track comes from its ReplayGain tags, else from its measured loudness. */
object ReplayGain {
    enum class Mode { OFF, TRACK, ALBUM }

    /** ReplayGain 2.0's reference loudness. */
    const val REFERENCE_LUFS = -18f

    /**
     * A track with neither tags nor a measure yet (its first listen): turned down by what recent
     * music usually needs, rather than blasting between two tracks that were.
     */
    const val UNKNOWN_DB = -6f

    data class Tags(val track: Float? = null, val trackPeak: Float? = null, val album: Float? = null, val albumPeak: Float? = null) {
        val isEmpty get() = track == null && album == null
    }

    data class Measure(val lufs: Float, val peak: Float)

    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    /** Reads `REPLAYGAIN_*` (ID3 TXXX, Vorbis, MP4) and Opus `R128_*` among [pairs] of name and value. */
    fun tags(pairs: List<Pair<String, String>>): Tags {
        var t = Tags()
        for ((rawName, rawValue) in pairs) {
            val v = NUMBER.find(rawValue)?.value?.replace(',', '.')?.toFloatOrNull() ?: continue
            t = when (rawName.trim().uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> t.copy(track = sane(v))
                "REPLAYGAIN_ALBUM_GAIN" -> t.copy(album = sane(v))
                "REPLAYGAIN_TRACK_PEAK" -> t.copy(trackPeak = v.takeIf { it > 0f && it < 8f })
                "REPLAYGAIN_ALBUM_PEAK" -> t.copy(albumPeak = v.takeIf { it > 0f && it < 8f })
                // Opus: 1/256 dB, against -23 LUFS instead of -18.
                "R128_TRACK_GAIN" -> t.copy(track = t.track ?: sane(v / 256f + 5f))
                "R128_ALBUM_GAIN" -> t.copy(album = t.album ?: sane(v / 256f + 5f))
                else -> t
            }
        }
        return t
    }

    private fun sane(db: Float): Float? = db.takeIf { it in -40f..40f }

    fun tags(metadata: Metadata?): Tags {
        if (metadata == null) return Tags()
        val pairs = (0 until metadata.length()).mapNotNull { i ->
            when (val e = metadata.get(i)) {
                is TextInformationFrame -> e.description?.let { d -> d to (e.values.firstOrNull() ?: "") }
                is VorbisComment -> e.key to e.value
                is InternalFrame -> e.description to e.text
                else -> null
            }
        }
        return tags(pairs)
    }

    /** The factor to apply to the samples; never so high that the track's peak would clip. */
    fun factor(mode: Mode, tags: Tags, measure: Measure?): Float {
        if (mode == Mode.OFF) return 1f
        val album = mode == Mode.ALBUM && tags.album != null
        val db = if (album) tags.album else tags.track ?: tags.album ?: measure?.let { REFERENCE_LUFS - it.lufs }
        val peak = if (album) tags.albumPeak ?: tags.trackPeak else tags.trackPeak ?: tags.albumPeak ?: measure?.peak
        val f = 10f.pow((db ?: UNKNOWN_DB).coerceIn(-30f, 20f) / 20f)
        // Without a known peak a quiet track is not raised: nothing says it has room.
        return if (peak != null && peak > 0f) minOf(f, 1f / peak) else minOf(f, 1f)
    }
}

/** Integrated loudness (ITU-R BS.1770: K weighting, 400 ms blocks, two gates) and sample peak. */
class LoudnessMeter(private val rate: Int, private val channels: Int) {
    private val shelf = DoubleArray(5)
    private val high = DoubleArray(5)
    private val state = Array(channels) { DoubleArray(8) }
    private val step = rate / 10
    private var inStep = 0
    private var sum = 0.0
    private val steps = ArrayList<Float>()
    var peak = 0f; private set
    val seconds: Double get() = steps.size / 10.0

    init {
        run {
            val k = tan(PI * 1681.974450955533 / rate)
            val q = 0.7071752369554196
            val vh = 10.0.pow(3.999843853973347 / 20)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            shelf[0] = (vh + vb * k / q + k * k) / a0
            shelf[1] = 2 * (k * k - vh) / a0
            shelf[2] = (vh - vb * k / q + k * k) / a0
            shelf[3] = 2 * (k * k - 1) / a0
            shelf[4] = (1 - k / q + k * k) / a0
        }
        run {
            val k = tan(PI * 38.13547087602444 / rate)
            val q = 0.5003270373238773
            val a0 = 1 + k / q + k * k
            high[0] = 1.0; high[1] = -2.0; high[2] = 1.0
            high[3] = 2 * (k * k - 1) / a0
            high[4] = (1 - k / q + k * k) / a0
        }
    }

    /** One sample (-1..1) of channel [ch]; channels come interleaved. */
    fun add(x: Float, ch: Int) {
        val a = abs(x)
        if (a > peak) peak = a
        val s = state[ch]
        val y = shelf[0] * x + shelf[1] * s[0] + shelf[2] * s[1] - shelf[3] * s[2] - shelf[4] * s[3]
        s[1] = s[0]; s[0] = x.toDouble(); s[3] = s[2]; s[2] = y
        val z = high[0] * y + high[1] * s[4] + high[2] * s[5] - high[3] * s[6] - high[4] * s[7]
        s[5] = s[4]; s[4] = y; s[7] = s[6]; s[6] = z
        sum += z * z
        if (ch == channels - 1 && ++inStep == step) {
            steps += (sum / step).toFloat()
            sum = 0.0
            inStep = 0
        }
    }

    /** null: nothing but silence so far. */
    fun lufs(): Float? {
        if (steps.size < 4) return null
        val blocks = DoubleArray(steps.size - 3) { i -> (steps[i] + steps[i + 1] + steps[i + 2] + steps[i + 3]) / 4.0 }
        val loud = blocks.filter { db(it) > -70 }
        if (loud.isEmpty()) return null
        val gate = db(loud.average()) - 10
        val kept = loud.filter { db(it) > gate }
        return if (kept.isEmpty()) null else db(kept.average()).toFloat()
    }

    private fun db(p: Double) = -0.691 + 10 * log10(p.coerceAtLeast(1e-12))
}

/**
 * Applies the gain of each track, changed exactly where the track begins, and measures the loudness
 * of those that carry no ReplayGain tag, for their next listen ([onMeasured]). Off, it does nothing.
 */
class NormalizerProcessor : BaseAudioProcessor() {
    /** The track about to be decoded, as [ChudAudioSink] knows it. */
    class Piece(val trackId: String?, val tags: ReplayGain.Tags, val measure: ReplayGain.Measure?, val durationMs: Long?)

    @Volatile var mode = ReplayGain.Mode.OFF
    @Volatile var onMeasured: ((trackId: String, measure: ReplayGain.Measure) -> Unit)? = null

    private var pending = Piece(null, ReplayGain.Tags(), null, null)
    private var piece = pending
    private var applied = 1f
    private var meter: LoudnessMeter? = null
    private var channels = 2
    private var rate = 44100
    private var fromStart = true

    fun beginTrack(p: Piece) { pending = p }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        piece = pending
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        fromStart = streamMetadata.positionOffsetUs < 1_000_000
        meter = null
        applied = ReplayGain.factor(mode, piece.tags, piece.measure)
    }

    override fun onQueueEndOfStream() {
        val m = meter ?: return
        meter = null
        val id = piece.trackId ?: return
        val length = piece.durationMs?.div(1000.0) ?: return
        // A track heard in full only: a part of it would say something else.
        if (m.seconds < maxOf(10.0, length * 0.9)) return
        m.lufs()?.let { onMeasured?.invoke(id, ReplayGain.Measure(it, m.peak)) }
    }

    override fun onReset() { meter = null }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        val mode = mode
        if (mode == ReplayGain.Mode.OFF && applied == 1f) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        if (mode != ReplayGain.Mode.OFF && meter == null && fromStart && piece.trackId != null && piece.tags.isEmpty && piece.measure == null) {
            meter = LoudnessMeter(rate, channels)
        }
        fromStart = false
        val m = meter
        val n = bytes / 2
        // The setting changed while playing: the volume slides to its new value over one buffer.
        val to = ReplayGain.factor(mode, piece.tags, piece.measure)
        val from = applied
        for (i in 0 until n) {
            val x = inp.short
            if (m != null) m.add(x / 32768f, i % channels)
            val f = if (from == to) to else from + (to - from) * i / n
            out.putShort((x * f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        applied = to
        out.flip()
    }
}
