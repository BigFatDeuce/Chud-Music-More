package app.chudmusic.net

import java.io.Closeable
import java.io.IOException

/** A file listed on a network place. Paths are relative to the share (or WebDAV base), '/'-separated, no leading '/'. */
data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val mtime: Long)

/** Random access to one remote file (tags, covers, playback). */
interface RemoteFile : Closeable {
    val length: Long
    /** Reads up to [len] bytes at [pos]; -1 at the end. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** What the library scanner and the player need from a network place. */
interface RemoteFs : Closeable {
    fun list(dir: String): List<RemoteEntry>
    fun open(path: String): RemoteFile
    /** The phone changed network, or the last answer never came: nothing open is worth keeping. */
    fun networkChanged() {}
    fun exists(path: String): Boolean = runCatching { open(path).use { true } }.getOrDefault(false)
}

class RemoteUnreachable(message: String, cause: Throwable? = null) : IOException(message, cause)
class RemoteAuthFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A [RemoteFile] with a small block cache: tag readers (MediaMetadataRetriever) make many tiny
 * reads, each of which would otherwise be one network round trip.
 */
class CachedRemoteFile(private val inner: RemoteFile, private val blockSize: Int = 64 * 1024, private val maxBlocks: Int = 16) : RemoteFile {
    override val length get() = inner.length
    private val blocks = object : LinkedHashMap<Long, ByteArray>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxBlocks
    }

    @Synchronized
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        var done = 0
        var p = pos
        while (done < len && p < length) {
            val index = p / blockSize
            val block = blocks.getOrPut(index) {
                val start = index * blockSize
                val size = minOf(blockSize.toLong(), length - start).toInt()
                val b = ByteArray(size)
                var got = 0
                while (got < size) {
                    val n = inner.read(start + got, b, got, size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < size) b.copyOf(got) else b
            }
            val inBlock = (p - index * blockSize).toInt()
            if (inBlock >= block.size) break
            val n = minOf(len - done, block.size - inBlock)
            System.arraycopy(block, inBlock, buf, off + done, n)
            done += n
            p += n
        }
        return if (done == 0) -1 else done
    }

    override fun close() = inner.close()
}
