package app.chudmusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The PC app's 10-band equaliser (src/renderer/src/audio/eq.ts), same bands, same filters
 * (low shelf, 8 peaking at Q 1.1, high shelf: the Web Audio formulas), same presets.
 * It runs inside the player's audio pipeline, so it applies to every source: files, network,
 * YouTube Music and radio. Nothing is ever written to a file.
 */
object Eq {
    val BANDS = intArrayOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
    const val RANGE_DB = 12f
    const val Q = 1.1

    /** Preset ids are their index (names are translated in the UI, `eq_preset_<i>`). */
    val PRESETS: List<Pair<Float, FloatArray>> = listOf(
        0f to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        -4f to floatArrayOf(5f, 3f, -2f, -4f, -1f, 2f, 5f, 6f, 6f, 6f),
        -3f to floatArrayOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, -1f, -1f),
        -5f to floatArrayOf(6f, 5f, 1f, 0f, -2f, 2f, 1f, 1f, 5f, 5f),
        -5f to floatArrayOf(6f, 5f, 2f, 0f, -1f, -1f, 1f, 0f, 2f, 3f),
        -3f to floatArrayOf(3f, 2f, 0f, 2f, -1f, -1f, 0f, 2f, 3f, 3f),
        0f to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -3f, -4f, -4f, -5f),
        -3f to floatArrayOf(3f, 2f, 1f, 1f, 2f, 2f, 3f, 3f, 2f, 1f),
        -3f to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
        -4f to floatArrayOf(5f, 3f, 0f, -1f, -2f, -1f, 0f, 2f, 4f, 5f),
        -6f to floatArrayOf(7f, 6f, 4f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        -6f to floatArrayOf(0f, 0f, 0f, 0f, 0f, 2f, 4f, 6f, 7f, 8f),
    )

    enum class Kind { LOW_SHELF, PEAKING, HIGH_SHELF }

    fun kind(i: Int) = when (i) { 0 -> Kind.LOW_SHELF; BANDS.lastIndex -> Kind.HIGH_SHELF; else -> Kind.PEAKING }

    /** Normalised biquad coefficients [b0, b1, b2, a1, a2] (a0 = 1). */
    fun coefficients(kind: Kind, freq: Double, gainDb: Double, sampleRate: Double): DoubleArray {
        val f = freq.coerceAtMost(sampleRate * 0.45)
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * f / sampleRate
        val cw = cos(w0)
        val sw = sin(w0)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (kind) {
            Kind.PEAKING -> {
                val alpha = sw / (2 * Q)
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
            Kind.LOW_SHELF -> {
                val alpha = sw / 2 * sqrt(2.0)
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + s); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - s)
                a0 = (a + 1) + (a - 1) * cw + s; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - s
            }
            Kind.HIGH_SHELF -> {
                val alpha = sw / 2 * sqrt(2.0)
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + s); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - s)
                a0 = (a + 1) - (a - 1) * cw + s; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - s
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Total response in dB (preamp included) at [freq]: what the curve on screen shows. */
    fun responseDb(preampDb: Float, gains: FloatArray, freq: Double, sampleRate: Double = 48000.0): Double {
        var total = preampDb.toDouble()
        val w = 2 * PI * freq / sampleRate
        for (i in BANDS.indices) {
            val c = coefficients(kind(i), BANDS[i].toDouble(), gains[i].toDouble(), sampleRate)
            // |H(e^jw)|
            val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
            val nr = c[0] + c[1] * cr + c[2] * c2r; val ni = c[1] * ci + c[2] * c2i
            val dr = 1 + c[3] * cr + c[4] * c2r; val di = c[3] * ci + c[4] * c2i
            val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
            total += 20 * kotlin.math.log10(mag.coerceAtLeast(1e-6))
        }
        return total
    }
}

/**
 * The equaliser as a Media3 audio processor (16-bit or float PCM, any channel count).
 * Settings can change at any time from the UI: new coefficients are picked up at the next buffer.
 */
class EqualizerProcessor : BaseAudioProcessor() {
    @Volatile private var enabled = false
    @Volatile private var preampDb = 0f
    @Volatile private var gains = FloatArray(10)
    @Volatile private var version = 0

    private var appliedVersion = -1
    private var coeffs = Array(10) { DoubleArray(5) }
    private var state = Array(0) { DoubleArray(0) } // [channel][band*4]: x1, x2, y1, y2
    private var preampLinear = 1.0
    private var channels = 2
    private var sampleRate = 44100

    fun set(enabled: Boolean, preampDb: Float, gains: FloatArray) {
        this.enabled = enabled
        this.preampDb = preampDb
        this.gains = gains.copyOf()
        version++
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** A seek or a skip: what comes next does not follow what the filters remember. */
    fun discard() { state.forEach { it.fill(0.0) } }

    /**
     * Also called at each track boundary, where the filters go on as they are: reset in the middle
     * of a chained album, they would click.
     */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (inputAudioFormat.channelCount == state.size && inputAudioFormat.sampleRate == sampleRate) return
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate
        state = Array(channels) { DoubleArray(Eq.BANDS.size * 4) }
        appliedVersion = -1
    }

    override fun onReset() { state = Array(0) { DoubleArray(0) } }

    private fun refresh() {
        if (appliedVersion == version) return
        val g = gains
        coeffs = Array(Eq.BANDS.size) { i -> Eq.coefficients(Eq.kind(i), Eq.BANDS[i].toDouble(), g[i].toDouble(), sampleRate.toDouble()) }
        preampLinear = 10.0.pow(preampDb / 20.0)
        appliedVersion = version
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        refresh()
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        var ch = 0
        val bands = coeffs.size
        while (inp.hasRemaining()) {
            var x = if (float) inp.float.toDouble() else inp.short / 32768.0
            x *= preampLinear
            val s = state[ch]
            for (b in 0 until bands) {
                val c = coeffs[b]
                val o = b * 4
                val y = c[0] * x + c[1] * s[o] + c[2] * s[o + 1] - c[3] * s[o + 2] - c[4] * s[o + 3]
                s[o + 1] = s[o]; s[o] = x
                s[o + 3] = s[o + 2]; s[o + 2] = y
                x = y
            }
            if (float) out.putFloat(x.toFloat()) else out.putShort((x.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort())
            ch = (ch + 1) % channels
        }
        out.flip()
    }
}
