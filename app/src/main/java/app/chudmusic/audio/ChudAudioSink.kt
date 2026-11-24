package app.chudmusic.audio

import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.chudmusic.model.Track
import app.chudmusic.playback.MediaItems

/**
 * Tells the audio processors which track the next samples belong to. The player configures its
 * sink at each track boundary, before the first sample of the new track: the gain and the
 * crossfade change exactly there, whatever is still to be heard of the previous track.
 */
class ChudAudioSink(
    sink: AudioSink,
    private val normalizer: NormalizerProcessor,
    private val crossfade: CrossfadeProcessor,
    private val equalizer: EqualizerProcessor,
    /** The measured loudness of a track, if any. Called on the playback thread. */
    private val measureOf: (trackId: String) -> ReplayGain.Measure?,
) : ForwardingAudioSink(sink) {
    private var period: Any? = null
    private var track: Track? = null

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val uid = audioSinkConfig.mediaPeriodId?.periodUid
        val next = trackOf(audioSinkConfig.timeline, uid)
        val changed = uid != period || uid == null
        val fade = changed && period != null && CrossfadeSettings.between(track, next, crossfade.settings)
        if (changed || track?.id != next?.id) normalizer.beginTrack(piece(next, audioSinkConfig.format))
        crossfade.beginTrack(next, fade)
        period = uid
        track = next
        super.configure(audioSinkConfig)
    }

    private fun piece(t: Track?, format: Format): NormalizerProcessor.Piece {
        val tags = ReplayGain.tags(format.metadata)
        val id = t?.takeIf { !it.isRadio }?.id
        val measure = if (tags.isEmpty && id != null && normalizer.mode != ReplayGain.Mode.OFF) measureOf(id) else null
        return NormalizerProcessor.Piece(id, tags, measure, t?.durationMs)
    }

    private fun trackOf(timeline: Timeline, uid: Any?): Track? {
        if (uid == null || timeline.isEmpty) return null
        val index = timeline.getIndexOfPeriod(uid)
        if (index < 0) return null
        val window = timeline.getPeriod(index, Timeline.Period()).windowIndex
        return MediaItems.track(timeline.getWindow(window, Timeline.Window()).mediaItem)
    }

    /** A seek or a skip: what the crossfade holds back is no longer to be played. */
    override fun flush() {
        crossfade.discard()
        equalizer.discard()
        super.flush()
    }

    override fun reset() {
        crossfade.discard()
        equalizer.discard()
        period = null
        track = null
        super.reset()
    }
}
