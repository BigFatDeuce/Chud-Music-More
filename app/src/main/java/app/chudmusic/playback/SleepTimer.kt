package app.chudmusic.playback

import app.chudmusic.audio.CrossfadeSettings
import app.chudmusic.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sleep timer (state machine, see docs/STATE-MACHINES.md). Chosen in the player, obeyed by
 * [PlaybackService]; it does not outlive the app's process.
 *
 *     Off ──choose──▶ At(time) | EndOfTrack | EndOfAlbum ──reached, or cancelled──▶ Off
 *
 * The last seconds of [At] fade out.
 */
class SleepTimer(private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface State {
        data object Off : State
        data class At(val endsAt: Long) : State
        data object EndOfTrack : State
        data object EndOfAlbum : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    fun inMinutes(minutes: Int) { _state.value = State.At(clock() + minutes.coerceIn(1, MAX_MINUTES) * 60_000L) }
    fun atEndOfTrack() { _state.value = State.EndOfTrack }
    fun atEndOfAlbum() { _state.value = State.EndOfAlbum }
    fun off() { _state.value = State.Off }

    companion object {
        const val MAX_MINUTES = 24 * 60
        const val FADE_MS = 10_000L

        /** Volume for what is left before the stop: 1, then down to 0. */
        fun volume(leftMs: Long): Float = (leftMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

        /** Whether the player must stop at the end of [current], [next] being what would follow. */
        fun stopsAfter(state: State, current: Track?, next: Track?): Boolean = when (state) {
            State.EndOfTrack -> true
            State.EndOfAlbum -> current == null || next == null || !CrossfadeSettings.sameAlbum(current, next)
            State.Off, is State.At -> false
        }
    }
}
