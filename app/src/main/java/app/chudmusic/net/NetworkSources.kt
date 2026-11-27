package app.chudmusic.net

import android.net.Uri
import app.chudmusic.data.NetKind
import app.chudmusic.data.NetworkSourceEntity
import app.chudmusic.data.SourceDao
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * The open network places, by source id. Tracks only store `chud-net://<sourceId>/<path>`:
 * moving a NAS to another address or changing its password is one edit in Settings.
 */
class NetworkSources(private val dao: SourceDao) {
    private val open = ConcurrentHashMap<Long, Pair<NetworkSourceEntity, RemoteFs>>()

    suspend fun entity(id: Long): NetworkSourceEntity? = dao.get(id)

    fun fsBlocking(id: Long): Pair<NetworkSourceEntity, RemoteFs> {
        open[id]?.let { return it }
        val e = runBlocking { dao.get(id) } ?: throw RemoteUnreachable("source $id deleted")
        return fsFor(e)
    }

    @Synchronized
    fun fsFor(e: NetworkSourceEntity): Pair<NetworkSourceEntity, RemoteFs> {
        open[e.id]?.let { (old, fs) -> if (old == e) return old to fs else runCatching { fs.close() } }
        val fs = create(e, Secrets.decrypt(e.passwordEnc))
        val pair = e to fs
        if (e.id != 0L) open[e.id] = pair
        return pair
    }

    /** Called after a source is edited or deleted. */
    fun forget(id: Long) { open.remove(id)?.second?.let { runCatching { it.close() } } }

    /** The phone changed network: connections made on the old one are dropped, servers down there tried again. */
    fun networkChanged() { open.values.forEach { runCatching { it.second.networkChanged() } } }

    fun closeAll() { open.values.forEach { runCatching { it.second.close() } }; open.clear() }

    companion object {
        fun create(e: NetworkSourceEntity, password: String?): RemoteFs = when (e.kind) {
            NetKind.SMB -> SmbFs(SmbTarget(e.host, e.port ?: 445, e.share, e.username, password, e.domain))
            NetKind.WEBDAV -> WebDavFs(DavTarget(davBase(e), e.username, password))
        }

        /** A WebDAV place over plain http with a password: it crosses the network in clear (warned in Settings). */
        fun passwordInClear(e: NetworkSourceEntity) = e.kind == NetKind.WEBDAV && !e.useHttps && e.passwordEnc != null

        fun davBase(e: NetworkSourceEntity): String {
            val scheme = if (e.useHttps) "https" else "http"
            val port = e.port?.let { ":$it" } ?: ""
            return "$scheme://${e.host}$port/${e.share.trim('/')}".trimEnd('/')
        }

        fun trackUri(sourceId: Long, path: String): String =
            Uri.Builder().scheme("chud-net").authority(sourceId.toString()).path(path.trim('/')).build().toString()

        /** chud-net://12/Artist/Album/01.flac → (12, "Artist/Album/01.flac") */
        fun parse(uri: Uri): Pair<Long, String>? {
            if (uri.scheme != "chud-net") return null
            val id = uri.authority?.toLongOrNull() ?: return null
            return id to (uri.path ?: "").trim('/')
        }

        /**
         * The path the PC app understands for this file: a UNC path for SMB (`\\nas\Musique\A\B.flac`),
         * the URL for WebDAV. Used by playlist export (the PC finds SMB files directly).
         */
        fun exchangePath(e: NetworkSourceEntity, relPath: String): String = when (e.kind) {
            NetKind.SMB -> "\\\\" + e.host + "\\" + e.share + "\\" + relPath.trim('/').replace('/', '\\')
            NetKind.WEBDAV -> davBase(e) + "/" + relPath.trim('/')
        }
    }
}
