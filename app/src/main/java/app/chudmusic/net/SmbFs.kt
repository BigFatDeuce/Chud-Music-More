package app.chudmusic.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Connection details of an SMB share (SMB 2 and 3, through smbj, Apache-2.0). */
data class SmbTarget(
    val host: String,
    val port: Int = 445,
    val share: String,
    val user: String? = null,
    val password: String? = null,
    val domain: String? = null,
)

/**
 * One open SMB share. Reconnects once when the connection dropped (Wi-Fi change, NAS asleep):
 * the caller only sees an error if the share is really unreachable.
 *
 * State machine ([Link], see docs/STATE-MACHINES.md):
 *
 *     Closed ──share()──▶ Open            (connect, sign in, open the share)
 *     Closed ──share()──▶ Down(20 s)      (the server does not answer)
 *     Down ──share() before `until`──▶ Down (fails at once, no network)   ──after──▶ like Closed
 *     any ──networkChanged()──▶ Closed    (the phone changed network: see NetworkWatch)
 *     Open ──connection lost──▶ Closed    (dropped without waiting; the next share() connects again)
 */
class SmbFs(private val target: SmbTarget) : RemoteFs {
    private val client = SMBClient(config())

    private sealed interface Link {
        data object Closed : Link

        class Open(val connection: Connection, val session: Session, val disk: DiskShare) : Link {
            val alive: Boolean get() = disk.isConnected && connection.isConnected
        }

        /**
         * After a failed connection the server is taken as down for a moment: a queue of tracks on a
         * switched-off NAS is skipped at once instead of trying (and waiting) for each track.
         */
        class Down(val until: Long, val reason: String) : Link
    }

    private var link: Link = Link.Closed

    @Synchronized
    private fun share(): DiskShare {
        when (val l = link) {
            is Link.Open -> if (l.alive) return l.disk else drop()
            is Link.Down -> if (System.currentTimeMillis() < l.until) throw RemoteUnreachable(l.reason) else link = Link.Closed
            Link.Closed -> {}
        }
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = try {
                client.connect(target.host, target.port)
            } catch (e: IOException) {
                link = Link.Down(System.currentTimeMillis() + DOWN_MS, e.message ?: "SMB")
                throw e
            }
            session = try {
                connection.authenticate(authentication(target))
            } catch (e: SMBApiException) {
                throw RemoteAuthFailed(e.status.name, e)
            }
            val disk = session.connectShare(target.share) as? DiskShare ?: throw RemoteUnreachable("not a disk share: ${target.share}")
            link = Link.Open(connection, session, disk)
            return disk
        } catch (e: IOException) {
            abandon(connection)
            if (e is RemoteAuthFailed || e is RemoteUnreachable) throw e
            throw RemoteUnreachable(e.message ?: "SMB", e)
        } catch (e: SMBApiException) {
            abandon(connection)
            if (e.status.name.contains("LOGON") || e.status.name.contains("ACCESS_DENIED")) throw RemoteAuthFailed(e.status.name, e)
            throw RemoteUnreachable(e.status.name, e)
        }
    }

    private fun <T> retrying(block: (DiskShare) -> T): T = try {
        block(share())
    } catch (e: Exception) {
        if (e is RemoteAuthFailed) throw e
        if (e !is TransportException && e !is IOException && e !is SMBApiException) throw e
        if (e is SMBApiException && e.status.name.contains("OBJECT_NAME_NOT_FOUND")) throw java.io.FileNotFoundException(e.message)
        // The connection is suspect: drop it without a word and connect again, once.
        synchronized(this) { drop() }
        try {
            block(share())
        } catch (e2: SMBApiException) {
            throw when {
                e2.status.name.contains("NOT_FOUND") -> java.io.FileNotFoundException(e2.message)
                e2.status.name.contains("ACCESS_DENIED") || e2.status.name.contains("LOGON") -> RemoteAuthFailed(e2.status.name, e2)
                else -> RemoteUnreachable(e2.status.name, e2)
            }
        }
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    override fun list(dir: String): List<RemoteEntry> = retrying { d ->
        d.list(smbPath(dir)).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            val hidden = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L
            if (hidden && name.startsWith(".")) return@mapNotNull null
            RemoteEntry(name, (if (dir.isBlank()) name else "${dir.trim('/')}/$name"), isDir, info.endOfFile, info.lastWriteTime.toEpochMillis())
        }
    }

    override fun open(path: String): RemoteFile = retrying { d ->
        val f = d.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        SmbRemoteFile(f)
    }

    /** Lists the folders at the share's root: a quick "can I get in?" check for the settings screen. */
    fun check(): Int = list("").size

    /**
     * Forgets an open link whose connection is dead or suspect. A Down link stays Down (its delay
     * still holds). Nothing is said to the server: a polite goodbye (close the share, log off) on a
     * dead connection waits for answers that never come, 20 s each, and a NAS that dropped an idle
     * connection would cost a minute of silence before the next track.
     */
    private fun drop() {
        (link as? Link.Open)?.let { abandon(it.connection); link = Link.Closed }
    }

    /** A connection made on another network answers no more (it would wait 20 s to say so); a server down there may be up here. */
    override fun networkChanged() = synchronized(this) {
        drop()
        link = Link.Closed
    }

    /** Closes the socket at once, without logging off. */
    private fun abandon(connection: Connection?) {
        runCatching { connection?.close(true) }
    }

    /** The polite end, on a connection that works: close the share, log off, disconnect. */
    override fun close() {
        synchronized(this) {
            (link as? Link.Open)?.let { l ->
                if (l.alive) {
                    runCatching { l.disk.close() }
                    runCatching { l.session.close() }
                    runCatching { l.connection.close() }
                } else abandon(l.connection)
                link = Link.Closed
            }
        }
        runCatching { client.close() }
    }

    companion object {
        private const val DOWN_MS = 20_000L

        /**
         * [timeoutSeconds] bounds the wait for each answer (a NAS waking its disks can take 10-15 s).
         * No socket read time-out: smbj's reader thread would take 30 s of silence for a failure and
         * close a connection that was merely idle (music paused, a track fully buffered).
         */
        fun config(timeoutSeconds: Long = 20): SmbConfig = SmbConfig.builder()
            .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .withSoTimeout(0)
            .withMultiProtocolNegotiate(true)
            .withSecurityProvider(BCSecurityProvider())
            .withDfsEnabled(false)
            .build()

        fun authentication(target: SmbTarget): AuthenticationContext = when {
            target.user.isNullOrBlank() -> AuthenticationContext.guest()
            else -> AuthenticationContext(target.user, (target.password ?: "").toCharArray(), target.domain ?: "")
        }
    }
}

private class SmbRemoteFile(private val f: File) : RemoteFile {
    override val length: Long = f.getFileInformation(FileStandardInformation::class.java).endOfFile
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = f.read(buf, pos, off, len)
        return if (n <= 0) -1 else n
    }
    override fun close() { runCatching { f.close() } }
}
