package app.chudmusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps the audio after the equaliser (like the PC app's analyser node) into a small ring buffer.
 * It does not change the sound and costs a copy; the FFT runs only when the UI asks for bars,
 * that is when a spectrum is on screen.
 */
class SpectrumTap : BaseAudioProcessor() {
    companion object {
        const val SIZE = 2048
        private val ring = FloatArray(SIZE)
        @Volatile private var head = 0
        @Volatile var sampleRate = 44100; private set
        @Volatile var lastWrite = 0L; private set

        /** The last [SIZE] mono samples, oldest first. */
        fun snapshot(into: FloatArray) {
            val h = head
            for (i in 0 until SIZE) into[i] = ring[(h + i) % SIZE]
        }
    }

    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val view = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        var h = head
        var ch = 0
        var acc = 0f
        while (view.hasRemaining()) {
            acc += if (float) view.float else view.short / 32768f
            if (++ch == channels) {
                ring[h] = acc / channels
                h = (h + 1) % SIZE
                ch = 0; acc = 0f
            }
        }
        head = h
        lastWrite = System.nanoTime()
        replaceOutputBuffer(n).put(inputBuffer).flip()
    }
}

/** Turns the tap's samples into [bands] log-spaced bars (0..1), smoothed between frames. */
class SpectrumAnalyzer(private val bands: Int = 24) {
    private val n = SpectrumTap.SIZE
    private val samples = FloatArray(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val levels = FloatArray(bands)

    fun bars(playing: Boolean): FloatArray {
        val fresh = playing && System.nanoTime() - SpectrumTap.lastWrite < 400_000_000L
        if (!fresh) {
            for (i in levels.indices) levels[i] *= 0.85f
            return levels
        }
        SpectrumTap.snapshot(samples)
        for (i in 0 until n) { re[i] = samples[i] * window[i]; im[i] = 0.0 }
        fft(re, im)
        val rate = SpectrumTap.sampleRate.toDouble()
        val lo = 40.0; val hi = minOf(16000.0, rate / 2)
        for (b in 0 until bands) {
            val f0 = lo * (hi / lo).pow(b.toDouble() / bands)
            val f1 = lo * (hi / lo).pow((b + 1).toDouble() / bands)
            val i0 = (f0 / rate * n).toInt().coerceIn(1, n / 2 - 1)
            val i1 = (f1 / rate * n).toInt().coerceIn(i0 + 1, n / 2)
            var peak = 0.0
            for (i in i0 until i1) peak = maxOf(peak, sqrt(re[i] * re[i] + im[i] * im[i]))
            val db = 20 * log10(peak / (n / 4) + 1e-9)
            val v = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
            levels[b] = if (v > levels[b]) v else levels[b] * 0.82f + v * 0.18f
        }
        return levels
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
