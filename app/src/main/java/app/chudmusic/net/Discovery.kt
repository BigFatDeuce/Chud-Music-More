package app.chudmusic.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.chudmusic.data.NetKind
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A file server seen on the local network. */
data class FoundServer(
    val kind: NetKind,
    /** IP address (what the phone can always reach; Windows names do not resolve on Android). */
    val address: String,
    /** The name the server gives itself (NetBIOS or mDNS), when it tells. */
    val name: String? = null,
    val port: Int? = null,
    /** WebDAV only: the base path the server advertises, and whether it is HTTPS. */
    val path: String? = null,
    val https: Boolean = false,
) {
    val key: String get() = "$kind|$address|${port ?: 0}"
    val label: String get() = name?.takeIf { it.isNotBlank() && it != address } ?: address
}

/** A folder a server shares over SMB. */
data class SmbShare(val name: String, val remark: String?)

/**
 * Looking for file servers on the Wi-Fi (state machine, see docs/STATE-MACHINES.md):
 *
 *     Idle ──start()──▶ Scanning(found…, progress) ──▶ Done(found)
 *     Idle ──start() without Wi-Fi/Ethernet──▶ NoNetwork
 *     Scanning ──stop()──▶ Done(found so far)        any ──start()──▶ Scanning (a new search)
 */
sealed interface Discovery {
    data object Idle : Discovery
    data object NoNetwork : Discovery
    data class Scanning(val found: List<FoundServer> = emptyList(), val done: Int = 0, val total: Int = 0) : Discovery
    data class Done(val found: List<FoundServer>) : Discovery

    val servers: List<FoundServer>
        get() = when (this) {
            is Scanning -> found
            is Done -> found
            else -> emptyList()
        }

    companion object {
        /**
         * [list] with [server] added. The same server seen twice (port sweep, then mDNS) is one entry
         * that keeps the best of both: a name, a port, a path.
         */
        fun merge(list: List<FoundServer>, server: FoundServer): List<FoundServer> {
            val i = list.indexOfFirst { it.kind == server.kind && it.address == server.address && (it.port ?: -1) == (server.port ?: -1) }
            if (i < 0) return (list + server).sortedWith(compareBy({ it.kind }, { ipKey(it.address) }))
            val old = list[i]
            val merged = old.copy(name = old.name ?: server.name, path = old.path ?: server.path, https = old.https || server.https)
            return list.toMutableList().also { it[i] = merged }
        }

        private fun ipKey(address: String): Long = address.split('.').fold(0L) { acc, p -> acc * 256 + (p.toLongOrNull() ?: 0) }
    }
}

/**
 * Finds SMB servers (a sweep of the phone's sub-network on port 445, named through NetBIOS) and
 * the SMB/WebDAV servers that announce themselves (mDNS/Bonjour, as most NAS do). Nothing is
 * read or written on the servers: a connection is opened and closed.
 */
class LanDiscovery(private val context: Context) {
    private val _state = MutableStateFlow<Discovery>(Discovery.Idle)
    val state: StateFlow<Discovery> = _state
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        val hosts = subnetHosts()
        if (hosts == null) { _state.value = Discovery.NoNetwork; return }
        _state.value = Discovery.Scanning(total = hosts.size)
        job = scope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    val announced = launch { mdns().collect { add(it) } }
                    sweep(hosts, SMB_PORT, onProbed = { _state.update { s -> if (s is Discovery.Scanning) s.copy(done = s.done + 1) else s } }) { ip ->
                        add(FoundServer(NetKind.SMB, ip))
                        // The name comes a moment later: the server is listed at once.
                        launch { netbiosName(ip)?.let { add(FoundServer(NetKind.SMB, ip, name = it)) } }
                    }
                    delay(MDNS_EXTRA_MS)
                    announced.cancel()
                }
            } finally {
                _state.update { s -> if (s is Discovery.Scanning) Discovery.Done(s.found) else s }
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun add(server: FoundServer) = _state.update { s ->
        when (s) {
            is Discovery.Scanning -> s.copy(found = Discovery.merge(s.found, server))
            is Discovery.Done -> s.copy(found = Discovery.merge(s.found, server))
            else -> s
        }
    }

    /** Every other address of the phone's local IPv4 network; null when it is not on Wi-Fi or Ethernet. */
    private fun subnetHosts(): List<String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return null
        val link = cm.getLinkProperties(network) ?: return null
        val own = link.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        val gateways = link.routes.mapNotNull { it.gateway?.hostAddress }.filter { it != "0.0.0.0" }
        return candidates(own.address.hostAddress ?: return null, own.prefixLength, gateways)
    }

    /** Opens then closes a TCP connection to [port] on every host, [PARALLEL] at a time. */
    private suspend fun sweep(hosts: List<String>, port: Int, onProbed: () -> Unit, onOpen: suspend (String) -> Unit) = coroutineScope {
        val gate = Semaphore(PARALLEL)
        hosts.map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val open = runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_MS); true } }.getOrDefault(false)
                    onProbed()
                    if (open) onOpen(ip)
                }
            }
        }.awaitAll()
    }

    /** Servers that announce themselves with mDNS. Endless: the collector decides when to stop. */
    private fun mdns(): Flow<FoundServer> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        if (nsd != null) for ((type, kind, https) in MDNS_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    serviceInfo ?: return
                    runCatching {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo?) {
                                info ?: return
                                @Suppress("DEPRECATION")
                                val ip = (info.host as? Inet4Address)?.hostAddress ?: return
                                val path = runCatching { info.attributes["path"]?.toString(Charsets.UTF_8) }.getOrNull()?.trim('/')?.takeIf { it.isNotEmpty() }
                                val port = info.port.takeIf { it > 0 && !(kind == NetKind.SMB && it == SMB_PORT) }
                                trySend(FoundServer(kind, ip, info.serviceName, port, path, https))
                            }
                        })
                    }
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); listeners += listener }
        }
        awaitClose { listeners.forEach { l -> runCatching { nsd?.stopServiceDiscovery(l) } } }
    }

    companion object {
        const val SMB_PORT = 445
        private const val PARALLEL = 48
        private const val CONNECT_MS = 450
        private const val MDNS_EXTRA_MS = 1500L
        private val MDNS_TYPES = listOf(
            Triple("_smb._tcp", NetKind.SMB, false),
            Triple("_webdav._tcp", NetKind.WEBDAV, false),
            Triple("_webdavs._tcp", NetKind.WEBDAV, true),
        )

        /**
         * The addresses worth probing from [ownIp]/[prefix]: the 254 neighbours of the phone (home
         * networks are /24; a wider network is not swept whole), gateways first, the phone left out.
         */
        fun candidates(ownIp: String, prefix: Int, gateways: List<String> = emptyList()): List<String> {
            val parts = ownIp.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || prefix !in 1..32) return emptyList()
            val base = "${parts[0]}.${parts[1]}.${parts[2]}."
            val hostBits = (32 - prefix).coerceAtMost(8)
            val size = 1 shl hostBits
            val start = parts[3] / size * size
            // Not the network's own address nor its broadcast address (the first and the last).
            val usable = if (size >= 4) (start + 1) until (start + size - 1) else start until (start + size)
            val neighbours = usable.filter { it in 1..254 && it != parts[3] }.map { base + it }
            return (gateways.filter { it.startsWith(base) && it != ownIp } + neighbours).distinct()
        }

        /** The 50-byte NetBIOS "node status" question (RFC 1002 §4.2.17), asked to one host on UDP 137. */
        internal fun nodeStatusQuery(id: Int = 0x4348): ByteArray {
            val q = ByteArray(50)
            q[0] = (id shr 8).toByte(); q[1] = id.toByte()
            q[5] = 1 // one question
            q[12] = 0x20
            // "*" then 15 NUL bytes, each byte split into two nibbles + 'A'
            q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte()
            for (i in 15 until 45) q[i] = 'A'.code.toByte()
            q[45] = 0
            q[47] = 0x21 // NBSTAT
            q[49] = 0x01 // IN
            return q
        }

        /** The workstation name out of a node status answer, or null. */
        internal fun parseNodeStatus(data: ByteArray, length: Int): String? {
            // header 12, name 34, type/class/ttl/rdlength 10, then the number of names
            val at = 56
            if (length <= at) return null
            val count = data[at].toInt() and 0xFF
            var p = at + 1
            repeat(count) {
                if (p + 18 > length) return null
                val suffix = data[p + 15].toInt() and 0xFF
                val group = data[p + 16].toInt() and 0x80 != 0
                if (suffix == 0x00 && !group) {
                    return String(data, p, 15, Charsets.US_ASCII).trim().takeIf { n -> n.isNotEmpty() && n.all { it.code in 0x21..0x7E } }
                }
                p += 18
            }
            return null
        }

        /** Asks a Windows or Samba server its name (NetBIOS). Null when it does not answer. */
        fun netbiosName(ip: String, timeoutMs: Int = 700): String? = runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val q = nodeStatusQuery()
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(1024)
                val answer = DatagramPacket(buf, buf.size)
                s.receive(answer)
                parseNodeStatus(buf, answer.length)
            }
        }.getOrNull()

        /**
         * The folders [target].host shares (its `share` field is not used). Hidden and administrative
         * shares (`C$`, `IPC$`, printers) are left out.
         * @throws RemoteAuthFailed when the server wants a (different) user name and password
         */
        suspend fun shares(target: SmbTarget): List<SmbShare> = withContext(Dispatchers.IO) {
            try {
                SMBClient(SmbFs.config(timeoutSeconds = 8)).use { client ->
                    client.connect(target.host, target.port).use { connection ->
                        val session = try {
                            connection.authenticate(SmbFs.authentication(target))
                        } catch (e: SMBApiException) {
                            throw RemoteAuthFailed(e.status.name, e)
                        }
                        val service = ServerService(SMBTransportFactories.SRVSVC.getTransport(session))
                        service.shares1
                            .filter { it.type and 0x0FFFFFFF == 0 && it.type and Int.MIN_VALUE == 0 && !it.netName.endsWith("$") }
                            .map { SmbShare(it.netName, it.remark?.takeIf { r -> r.isNotBlank() }) }
                            .sortedBy { it.name.lowercase() }
                    }
                }
            } catch (e: RemoteAuthFailed) {
                throw e
            } catch (e: SMBApiException) {
                if (e.status.name.contains("LOGON") || e.status.name.contains("ACCESS_DENIED")) throw RemoteAuthFailed(e.status.name, e)
                throw RemoteUnreachable(e.status.name, e)
            } catch (e: IOException) {
                // smbj-rpc reports a refused listing as an IOException naming the status.
                if (e.message.orEmpty().contains("ACCESS_DENIED") || e.message.orEmpty().contains("LOGON")) throw RemoteAuthFailed(e.message ?: "SMB", e)
                throw RemoteUnreachable(e.message ?: "SMB", e)
            } catch (e: LinkageError) {
                // smbj-rpc reports an answer it cannot read with java.rmi exceptions, which Android
                // does not have: creating one fails with NoClassDefFoundError. Same meaning for us.
                throw RemoteUnreachable("unexpected answer from the server", e)
            }
        }
    }
}
