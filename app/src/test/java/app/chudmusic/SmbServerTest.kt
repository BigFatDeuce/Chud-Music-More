package app.chudmusic

import app.chudmusic.net.LanDiscovery
import app.chudmusic.net.RemoteAuthFailed
import app.chudmusic.net.SmbFs
import app.chudmusic.net.SmbTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The SMB code against a real server. Skipped unless a server is named:
 *
 *     ./gradlew testDebugUnitTest -Pchud.smb=host:port/share -Pchud.smbUser=user -Pchud.smbPassword=password
 *
 * The share must hold at least one folder with one file in it. Nothing is written on the server.
 */
class SmbServerTest {
    private val spec: String? = System.getProperty("chud.smb")?.takeIf { it.isNotBlank() }

    private fun target(): SmbTarget {
        assumeTrue(spec != null)
        val (hostPort, share) = spec!!.split('/', limit = 2)
        return SmbTarget(
            hostPort.substringBefore(':'), hostPort.substringAfter(':', "445").toInt(), share,
            System.getProperty("chud.smbUser")?.takeIf { it.isNotBlank() }, System.getProperty("chud.smbPassword"),
        )
    }

    private fun firstFile(fs: SmbFs): String {
        val queue = ArrayDeque(listOf(""))
        while (queue.isNotEmpty()) {
            val entries = fs.list(queue.removeFirst())
            entries.firstOrNull { !it.isDir && it.size > 0 }?.let { return it.path }
            queue += entries.filter { it.isDir }.map { it.path }
        }
        error("no file on the share")
    }

    @Test fun sharesAreListedWithoutTheHiddenOnes() = runBlocking {
        val t = target()
        val shares = LanDiscovery.shares(t)
        assertTrue(shares.toString(), shares.any { it.name.equals(t.share, ignoreCase = true) })
        assertTrue(shares.none { it.name.endsWith("$") })
    }

    @Test fun aWrongPasswordIsAnAuthenticationFailure() = runBlocking {
        val t = target()
        assumeTrue(t.user != null)
        val failure = runCatching { LanDiscovery.shares(t.copy(password = "not-the-password")) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is RemoteAuthFailed)
    }

    /**
     * What the "add a share" screen then the player do, in that order: list the shares, test the
     * share (a connection opened and closed), read it (the scan), then, a moment later, play a file
     * on the connection the scan left open. Reading must not wait for a time-out.
     */
    @Test fun aFileIsReadableRightAfterTheShareWasFound() = runBlocking {
        val t = target()
        LanDiscovery.shares(t)
        SmbFs(t).use { probe -> probe.list("") }
        SmbFs(t).use { fs ->
            val path = firstFile(fs)
            Thread.sleep((System.getProperty("chud.smbIdleMs")?.toLongOrNull() ?: 8_000L))
            val started = System.currentTimeMillis()
            val read = fs.open(path).use { f -> f.read(0, ByteArray(4096), 0, 4096) }
            val took = System.currentTimeMillis() - started
            assertTrue("read $read bytes", read > 0)
            assertTrue("opening the file took $took ms", took < 5_000)
            assertEquals(path, firstFile(fs))
        }
    }

    /** A TCP relay in front of the server, to cut connections like a NAS that drops idle clients does. */
    private class Relay(private val host: String, private val port: Int) : AutoCloseable {
        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val localPort: Int get() = server.localPort

        init {
            kotlin.concurrent.thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    val upstream = java.net.Socket(host, port)
                    sockets += client; sockets += upstream
                    for ((from, to) in listOf(client to upstream, upstream to client)) kotlin.concurrent.thread(isDaemon = true) {
                        runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
                        runCatching { from.close() }; runCatching { to.close() }
                    }
                }
            }
        }

        /** Every open connection dies; new ones are accepted as before. */
        fun cut() { sockets.toList().forEach { runCatching { it.close() } }; sockets.clear() }
        override fun close() { cut(); server.close() }
    }

    /** The server dropped the connection while nothing played: the next track must start at once, on a new connection. */
    @Test fun aDroppedConnectionIsReplacedWithoutWaiting() {
        val t = target()
        Relay(t.host, t.port).use { relay ->
            SmbFs(t.copy(host = "127.0.0.1", port = relay.localPort)).use { fs ->
                val path = firstFile(fs)
                relay.cut()
                Thread.sleep(1_500)
                val started = System.currentTimeMillis()
                val read = fs.open(path).use { f -> f.read(0, ByteArray(4096), 0, 4096) }
                val took = System.currentTimeMillis() - started
                assertTrue("read $read bytes", read > 0)
                assertTrue("reading after the cut took $took ms", took < 5_000)
            }
        }
    }
}
