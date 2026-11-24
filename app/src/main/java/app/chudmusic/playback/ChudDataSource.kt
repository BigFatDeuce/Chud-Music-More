package app.chudmusic.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.chudmusic.data.NetKind
import app.chudmusic.net.Http
import app.chudmusic.net.NetworkSources
import app.chudmusic.net.RemoteFile
import app.chudmusic.net.RemoteUnreachable
import app.chudmusic.net.WebDavFs
import app.chudmusic.youtube.YouTubeStreams
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Picks how to read a track from its URI:
 * - content:// file:// http(s):// → Media3's default (phone files, radio);
 * - chud-net://<id>/<path> → SMB (random access through smbj) or WebDAV (HTTP with authentication);
 * - chud-yt://<videoId> → the audio stream URL, resolved when playback starts (URLs expire).
 */
class ChudDataSource(
    private val context: Context,
    private val sources: NetworkSources,
    private val preferOpus: () -> Boolean,
) : DataSource {
    class Factory(private val context: Context, private val sources: NetworkSources, private val preferOpus: () -> Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChudDataSource(context, sources, preferOpus)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    private fun http(headers: Map<String, String> = emptyMap(), ua: String = Http.APP_UA): DataSource =
        OkHttpDataSource.Factory(Http.client).setUserAgent(ua).setDefaultRequestProperties(headers).createDataSource()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        current?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val (source, spec) = when (uri.scheme) {
            "chud-yt" -> {
                val id = uri.authority ?: throw IOException("bad YouTube uri")
                val s = try {
                    YouTubeStreams.resolve(id, preferOpus())
                } catch (e: Exception) {
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                http(ua = Http.BROWSER_UA) to dataSpec.withUri(Uri.parse(s.url))
            }
            "chud-net" -> {
                val (id, path) = NetworkSources.parse(uri) ?: throw IOException("bad network uri")
                val (entity, fs) = try {
                    sources.fsBlocking(id)
                } catch (e: IOException) {
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                if (entity.kind == NetKind.WEBDAV) {
                    val dav = fs as WebDavFs
                    http(dav.authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()) to dataSpec.withUri(Uri.parse(dav.streamUrl(path)))
                } else {
                    RemoteFileDataSource { fs.open(path) } to dataSpec
                }
            }
            else -> DefaultDataSource.Factory(context, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Http.client).setUserAgent(Http.APP_UA)).createDataSource() to dataSpec
        }
        listeners.forEach { source.addTransferListener(it) }
        current = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() {
        try { current?.close() } finally { current = null }
    }
}

/** Reads a [RemoteFile] (SMB) in large blocks: one network round trip per 512 KB. */
class RemoteFileDataSource(private val opener: () -> RemoteFile) : BaseDataSource(true) {
    private var file: RemoteFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private val buf = ByteArray(512 * 1024)
    private var bufStart = 0L
    private var bufLen = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = try {
            opener()
        } catch (e: FileNotFoundException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        } catch (e: RemoteUnreachable) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        file = f
        position = dataSpec.position
        if (position > f.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else f.length - position
        bufLen = 0
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("closed")
        if (position < bufStart || position >= bufStart + bufLen) {
            bufStart = position
            bufLen = 0
            val want = minOf(buf.size.toLong(), f.length - position).toInt()
            while (bufLen < want) {
                val n = try { f.read(position + bufLen, buf, bufLen, want - bufLen) } catch (e: IOException) {
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                if (n <= 0) break
                bufLen += n
            }
            if (bufLen == 0) return C.RESULT_END_OF_INPUT
        }
        val inBuf = (position - bufStart).toInt()
        val n = minOf(length.toLong(), (bufLen - inBuf).toLong(), remaining).toInt()
        System.arraycopy(buf, inBuf, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        file?.let { runCatching { it.close() } }
        if (file != null) transferEnded()
        file = null
    }
}
