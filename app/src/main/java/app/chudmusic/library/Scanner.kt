package app.chudmusic.library

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.chudmusic.ChudApp
import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.Settings
import app.chudmusic.net.NetworkSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/**
 * What the library reader is doing (state machine, see docs/STATE-MACHINES.md):
 *
 *     Idle ──scanLocal()──▶ Running(phone) ──▶ Idle
 *     Idle ──scanNetwork()──▶ Running(places 1 to 3) ──▶ … ──▶ Idle(lastError)
 *
 * One scan at a time (a mutex): a second request waits for its turn, it never interleaves. Inside a
 * network scan, up to [Scanner.PLACES_AT_ONCE] places are read side by side: a NAS that does not
 * answer does not hold up the others.
 */
sealed interface ScanState {
    /** Nothing is being read. [lastError] is what went wrong in the last network scan, if anything. */
    data class Idle(val lastError: String? = null) : ScanState

    /** Reading the phone, or one or more network places at the same time. */
    data class Running(val places: List<Place>) : ScanState {
        constructor(sourceId: Long? = null, label: String? = null, done: Int = 0, total: Int = 0) : this(listOf(Place(sourceId, label, done, total)))

        /** The network place [id], when it is one of those being read. */
        fun place(id: Long): Place? = places.firstOrNull { it.sourceId == id }
        /** null for the phone. */
        val label: String? get() = places.mapNotNull { it.label }.joinToString(", ").ifEmpty { null }
        val done: Int get() = places.sumOf { it.done }
        val total: Int get() = if (places.any { it.total == 0 }) 0 else places.sumOf { it.total }
        /** 0..1, or null while the total is not known yet. */
        val progress: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        val counter: String get() = if (total > 0) " · $done/$total" else ""
        fun with(p: Place) = Running(places.map { if (it.sourceId == p.sourceId) p else it })
    }

    /** The phone ([sourceId] and [label] null) or one network place. [total] is 0 while files are still being counted. */
    data class Place(val sourceId: Long? = null, val label: String? = null, val done: Int = 0, val total: Int = 0) {
        val counter: String get() = if (total > 0) " · $done/$total" else ""
    }

    val running: Boolean get() = this is Running
}

/**
 * Runs scans one at a time: the phone (MediaStore, fast) and each network place.
 * Network places are not watched (like the PC: a watch over SMB either sees nothing or
 * floods); they are re-read on demand, at start-up and every 6 hours (Wi-Fi only by default).
 */
class Scanner(
    private val context: Context,
    private val db: ChudDatabase,
    private val settings: Settings,
    private val sources: NetworkSources,
    covers: CoverStore,
    private val scope: CoroutineScope,
) {
    val local = LocalScanner(context, db, covers)
    private val network = NetworkScanner(db, sources, covers)
    private val mutex = Mutex()
    private val queueLock = Mutex()
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle())
    val state: StateFlow<ScanState> = _state
    private var observer: ContentObserver? = null
    private var pendingLocal: Job? = null

    fun hasAudioPermission(): Boolean = app.chudmusic.ui.Permissions.hasAudio(context)

    suspend fun scanLocal(): Unit = mutex.withLock {
        if (!hasAudioPermission()) return@withLock
        _state.value = ScanState.Running()
        try {
            local.scan { d, t -> _state.value = ScanState.Running(done = d, total = t) }
        } finally {
            _state.value = ScanState.Idle()
        }
    }

    suspend fun scanNetwork(onlyId: Long? = null, force: Boolean = false): Unit = mutex.withLock {
        val metered = settings.netWifiOnly.first() && !onUnmetered()
        val unfinished = settings.scanUnfinished()
        val paused = settings.scanPaused.first()
        val todo = db.sources().all().filter { s ->
            (onlyId == null || s.id == onlyId) && mustScan(force, metered, s.id in unfinished, paused = s.id in paused, chosen = onlyId == s.id)
        }
        if (todo.isEmpty()) return@withLock
        val errors = java.util.concurrent.ConcurrentHashMap<Long, String>()
        val places = Semaphore(PLACES_AT_ONCE)
        _state.value = ScanState.Running(todo.map { ScanState.Place(it.id, it.name) })
        fun show(p: ScanState.Place) = _state.update { (it as? ScanState.Running)?.with(p) ?: it }
        try {
            supervisorScope {
                for (s in todo) launch {
                    places.withPermit {
                        // A scan the user asked for (or one stopped before its end) is remembered until it ends:
                        // closed halfway, it goes on at the next start even away from Wi-Fi.
                        if (force || s.id in unfinished) settings.setScanUnfinished(s.id, true)
                        val r = network.scan(s) { d, t -> show(ScanState.Place(s.id, s.name, d, t)) }
                        if (keepTrying(r.error)) settings.setScanUnfinished(s.id, true) else settings.setScanUnfinished(s.id, false)
                        r.error?.let { errors[s.id] = "${s.name}: $it" }
                    }
                }
            }
        } finally {
            // Never left in Running: a cancelled or crashed scan gives the hand back.
            _state.value = ScanState.Idle(todo.firstNotNullOfOrNull { errors[it.id] })
        }
    }

    /** The network is back: what it interrupted goes on by itself. */
    suspend fun onNetworkBack() {
        if (settings.scanUnfinished().isNotEmpty()) request(ScanRequest(local = false, network = true))
    }

    companion object {
        const val PLACES_AT_ONCE = 3

        /** A place out of reach is tried again when the network comes back; a refused password or a missing folder needs the user. */
        fun keepTrying(error: String?): Boolean = error == NetworkScanner.ERROR_UNREACHABLE

        /**
         * Whether a network place is read now. A place the user [paused] is read only when this very
         * place is asked for ([chosen]): not by "scan everything" nor the periodic scan. Otherwise
         * always when asked for ([force]) or when its last asked-for scan did not end
         * ([unfinished]); not on a metered network when the user wants Wi-Fi only.
         */
        fun mustScan(force: Boolean, metered: Boolean, unfinished: Boolean, paused: Boolean = false, chosen: Boolean = false): Boolean =
            if (paused) chosen else force || unfinished || !metered
    }

    /** A network place removed from Settings: its tracks leave the library (playlists keep them, flagged missing). */
    suspend fun removeSource(id: Long): Unit = mutex.withLock {
        val ids = db.tracks().idsOfNetworkSource(id)
        ids.chunked(500).forEach { db.tracks().leaveLibrary(it); db.tracks().setAvailable(it, false) }
        db.sources().delete(id)
        sources.forget(id)
        db.tracks().purgeOrphans()
    }

    fun scanAll(force: Boolean = false) = request(ScanRequest(local = true, network = true, force = force))

    /**
     * Queues a scan in the background ([ScanWorker]): it keeps going with another app in front or
     * the screen off, and comes back by itself if Android stops it. Dropped when a scan still
     * waiting in the queue already covers it ([ScanRequest.mustQueue]).
     */
    fun request(req: ScanRequest) = scope.launch(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        queueLock.withLock {
            val waiting = runCatching { wm.getWorkInfosForUniqueWork(ScanRequest.WORK).get() }.getOrDefault(emptyList())
                .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                .map { ScanRequest.fromTags(it.tags) }
            if (!ScanRequest.mustQueue(req, waiting)) return@withLock
            val work = OneTimeWorkRequestBuilder<ScanWorker>().apply { req.tags().forEach { addTag(it) } }.build()
            wm.enqueueUniqueWork(ScanRequest.WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }

    private fun onUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** MediaStore changes (a file copied, deleted, downloaded) → a quick local rescan, grouped over 2 s. */
    fun watchLocal() {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                pendingLocal?.cancel()
                pendingLocal = scope.launch(Dispatchers.IO) { delay(2000); request(ScanRequest(local = true, network = false)) }
            }
        }.also { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, it) }
    }

    suspend fun schedulePeriodic() {
        val wifiOnly = settings.netWifiOnly.first()
        val auto = settings.netAutoScan.first()
        val wm = WorkManager.getInstance(context)
        if (!auto) { wm.cancelUniqueWork("network-scan"); return }
        val req = PeriodicWorkRequestBuilder<NetworkScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).build()
        wm.enqueueUniquePeriodicWork("network-scan", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class NetworkScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as ChudApp).graph
        g.scanner.scanNetwork(force = true)
        return Result.success()
    }
}
