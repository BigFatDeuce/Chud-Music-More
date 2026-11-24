package app.chudmusic.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Collects items from several coroutines and writes them [size] at a time; [flush] writes what is
 * left. Used by the scans so a scan stopped halfway keeps what it already read.
 */
class BatchWriter<T>(private val size: Int, private val write: suspend (List<T>) -> Unit) {
    private val mutex = Mutex()
    private val pending = ArrayList<T>()

    suspend fun add(item: T) {
        val full = mutex.withLock {
            pending += item
            if (pending.size >= size) pending.toList().also { pending.clear() } else null
        }
        if (full != null) write(full)
    }

    suspend fun flush() {
        val rest = mutex.withLock { pending.toList().also { pending.clear() } }
        if (rest.isNotEmpty()) write(rest)
    }
}
