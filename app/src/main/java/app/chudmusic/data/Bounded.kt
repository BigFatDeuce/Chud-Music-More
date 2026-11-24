package app.chudmusic.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A file chosen by the user or sent by another app is larger than what the app accepts to read. */
class TooLarge(val max: Long) : IOException("file too large (over ${max / (1024 * 1024)} MB)")

/**
 * Reads the whole stream, but never more than [max] bytes: a file opened from another app (a
 * playlist, a settings file, a picture) could be of any size, and reading it whole first would
 * fill the memory before its size could be refused.
 */
fun InputStream.readAtMost(max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw TooLarge(max)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
