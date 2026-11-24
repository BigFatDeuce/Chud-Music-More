package app.chudmusic.playback

import android.util.AtomicFile
import app.chudmusic.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The play queue on disk, so that it comes back after the app is closed, killed, or the phone
 * restarted. Two files: the list (written when it changes) and the place in it (written every few
 * seconds), each replaced as a whole ([AtomicFile]): a write cut short leaves the previous one.
 */
class QueueStore(dir: File) {
    /** [order]: the shuffled order, as indexes in [tracks]; empty when it was not shuffled. */
    @Serializable
    data class Saved(
        val tracks: List<Track>,
        val queued: List<Boolean> = emptyList(),
        val index: Int = 0,
        val positionMs: Long = 0,
        val shuffle: Boolean = false,
        val repeat: Int = 0,
        val order: List<Int> = emptyList(),
    )

    @Serializable
    data class Place(val index: Int, val positionMs: Long, val shuffle: Boolean = false, val repeat: Int = 0, val size: Int = -1, val trackId: String? = null)

    private val list = AtomicFile(File(dir, "queue.json"))
    private val place = AtomicFile(File(dir, "queue-place.json"))

    /** [tracksJson]: each track as JSON already (what the player's items carry). */
    @Synchronized
    fun writeList(tracksJson: List<String>, queued: List<Boolean>, order: List<Int>) = write(list) { listJson(tracksJson, queued, order) }

    @Synchronized
    fun writePlace(p: Place) = write(place) { json.encodeToString(Place.serializer(), p) }

    @Synchronized
    fun clear() { list.delete(); place.delete() }

    @Synchronized
    fun read(): Saved? = runCatching { join(read(list), read(place)) }.getOrNull()

    private fun read(f: AtomicFile): String? = runCatching { f.readFully().decodeToString() }.getOrNull()

    private fun write(f: AtomicFile, text: () -> String) {
        val out = runCatching { f.startWrite() }.getOrNull() ?: return
        try {
            out.write(text().toByteArray())
            f.finishWrite(out)
        } catch (_: Exception) {
            f.failWrite(out)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun listJson(tracksJson: List<String>, queued: List<Boolean>, order: List<Int>): String = buildString {
            append("{\"tracks\":[")
            tracksJson.joinTo(this, ",")
            append("],\"queued\":").append(queued.joinToString(",", "[", "]"))
            append(",\"order\":").append(order.joinToString(",", "[", "]")).append('}')
        }

        /** The list with its place. A place written for another list (the app was killed between the two) is not trusted. */
        fun join(listText: String?, placeText: String?): Saved? {
            val saved = json.decodeFromString(Saved.serializer(), listText ?: return null)
            if (saved.tracks.isEmpty()) return null
            val p = placeText?.let { runCatching { json.decodeFromString(Place.serializer(), it) }.getOrNull() }
            val n = saved.tracks.size
            val order = saved.order.takeIf { it.size == n && it.toSet() == (0 until n).toSet() }.orEmpty()
            if (p == null) return saved.copy(order = order, index = saved.index.coerceIn(0, n - 1))
            // Same place by its track when the index no longer says the same thing.
            val index = when {
                p.trackId == null -> p.index
                saved.tracks.getOrNull(p.index)?.id == p.trackId -> p.index
                else -> saved.tracks.indexOfFirst { it.id == p.trackId }
            }
            val known = index in 0 until n
            return saved.copy(
                index = if (known) index else 0, positionMs = if (known) p.positionMs.coerceAtLeast(0) else 0,
                shuffle = p.shuffle, repeat = p.repeat, order = order,
            )
        }
    }
}
