package app.chudmusic.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the phone has a network, and when it gets a new one (state machine, see
 * docs/STATE-MACHINES.md). Playback and scans interrupted by a lost connection wait for it here
 * instead of failing track after track.
 *
 *     Offline ──onAvailable──▶ Online(network) ──another network──▶ Online(other) ──onLost──▶ Offline
 */
class NetworkWatch(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(cm?.activeNetwork != null)
    val online: StateFlow<Boolean> = _online
    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A network just arrived, or replaced another (Wi-Fi ↔ mobile data). */
    val arrivals: SharedFlow<Unit> = _arrivals
    private var current: Network? = cm?.activeNetwork

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = network != current || !_online.value
            current = network
            _online.value = true
            if (changed) _arrivals.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            if (network == current) { current = null; _online.value = false }
        }
    }

    /** Called once, at start: a failure (some phones limit callbacks) leaves the phone taken as online. */
    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(callback) }.onFailure { _online.value = true }
    }
}
