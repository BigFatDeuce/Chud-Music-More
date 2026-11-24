package app.chudmusic.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Permissions {
    /** Reading the phone's music: READ_MEDIA_AUDIO since Android 13, storage before. */
    val audio: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudio(context: Context) = ContextCompat.checkSelfPermission(context, audio) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Access to the phone's music (state machine, see docs/STATE-MACHINES.md):
 *
 *     ASKABLE ──the user allows──▶ GRANTED
 *     ASKABLE ──Android refuses at once, without asking──▶ BLOCKED   (only the app's settings page can allow it)
 *     BLOCKED / ASKABLE ──allowed in the settings, seen on return──▶ GRANTED
 *     GRANTED ──removed in the settings──▶ ASKABLE
 */
enum class AudioAccess { GRANTED, ASKABLE, BLOCKED }

/** The one place that knows whether the phone's music can be read; every screen observes [access]. */
class AudioPermission(private val context: Context) {
    private val _access = MutableStateFlow(if (Permissions.hasAudio(context)) AudioAccess.GRANTED else AudioAccess.ASKABLE)
    val access: StateFlow<AudioAccess> = _access
    private var askedAt = 0L

    /** Call just before showing Android's question. */
    fun onAsk() { askedAt = android.os.SystemClock.elapsedRealtime() }

    /** Call with Android's answer. [canAskAgain] is `shouldShowRequestPermissionRationale`. */
    fun onResult(granted: Boolean, canAskAgain: Boolean) {
        _access.value = next(granted, canAskAgain, android.os.SystemClock.elapsedRealtime() - askedAt)
    }

    /** Call when the app comes back to the front: the permission may have changed in the settings. */
    fun refresh() {
        val has = Permissions.hasAudio(context)
        _access.value = when {
            has -> AudioAccess.GRANTED
            _access.value == AudioAccess.GRANTED -> AudioAccess.ASKABLE
            else -> _access.value
        }
    }

    companion object {
        /**
         * A refusal that came back in a blink, with nothing to explain, is Android answering by itself:
         * the question is no longer shown. A refusal after the user looked at the question can be asked again.
         */
        fun next(granted: Boolean, canAskAgain: Boolean, answeredAfterMs: Long): AudioAccess = when {
            granted -> AudioAccess.GRANTED
            !canAskAgain && answeredAfterMs < 350 -> AudioAccess.BLOCKED
            else -> AudioAccess.ASKABLE
        }
    }
}
