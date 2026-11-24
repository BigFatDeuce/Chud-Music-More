package app.chudmusic.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.data.NetKind
import app.chudmusic.data.NetworkSourceEntity
import app.chudmusic.library.TagReader
import app.chudmusic.net.Discovery
import app.chudmusic.net.FoundServer
import app.chudmusic.net.LanDiscovery
import app.chudmusic.net.NetworkSources
import app.chudmusic.net.RemoteAuthFailed
import app.chudmusic.net.RemoteEntry
import app.chudmusic.net.Secrets
import app.chudmusic.net.SmbShare
import app.chudmusic.net.SmbTarget
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.ConfirmDialog
import app.chudmusic.ui.theme.Chud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder

/**
 * The connection test of the form, which also drives the folder browser (state machine):
 *
 *     Idle ──test / open a folder──▶ Testing(shown) ──▶ Ok(path, folders) | Failed(message)
 *     any ──the kind (SMB/WebDAV) changes──▶ Idle
 *
 * While a folder is being opened, the folders already on screen stay there ([Testing.shown]).
 */
sealed interface Probe {
    data object Idle : Probe
    data class Testing(val shown: Ok?) : Probe
    data class Ok(val message: String, val path: String, val dirs: List<RemoteEntry>) : Probe
    data class Failed(val message: String) : Probe

    val busy: Boolean get() = this is Testing
    /** What the folder browser shows. */
    val browse: Ok? get() = when (this) { is Ok -> this; is Testing -> shown; else -> null }
}

/**
 * Asking a server which folders it shares (state machine):
 *
 *     Idle ──list──▶ Loading ──▶ Listed(shares) | NeedsLogin | Failed(message)
 */
sealed interface ShareList {
    data object Idle : ShareList
    data class Loading(val host: String) : ShareList
    data class Listed(val host: String, val shares: List<SmbShare>) : ShareList
    /** The server refuses guests (or the given account): a user name and password are needed. */
    data class NeedsLogin(val host: String) : ShareList
    data class Failed(val host: String, val message: String) : ShareList
}

/** The form of one network place (SMB share or WebDAV folder), with a network search, a connection test and a folder browser. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetSourceScreen(id: Long) {
    val ui = LocalUi.current
    val g = ui.g
    val c = Chud.colors
    var loaded by remember { mutableStateOf<NetworkSourceEntity?>(null) }
    var kind by remember { mutableStateOf(NetKind.SMB) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var https by remember { mutableStateOf(true) }
    var probe by remember { mutableStateOf<Probe>(Probe.Idle) }
    var shares by remember { mutableStateOf<ShareList>(ShareList.Idle) }
    var deleting by remember { mutableStateOf(false) }
    val discovery = remember { LanDiscovery(ui.context) }
    val found by discovery.state.collectAsState()
    DisposableEffect(Unit) { onDispose { discovery.stop() } }

    LaunchedEffect(id) {
        if (id == 0L) return@LaunchedEffect
        val e = withContext(Dispatchers.IO) { g.db.sources().get(id) } ?: return@LaunchedEffect
        loaded = e; kind = e.kind; name = e.name; host = e.host; port = e.port?.toString() ?: ""; share = e.share; root = e.rootPath
        user = e.username ?: ""; domain = e.domain ?: ""; https = e.useHttps
    }

    /**
     * A full address (`\\nas\Musique\Rock`, `smb://…`, `https://…/dav/…`) fills the form. Returns false,
     * changing nothing, when [v] is not such an address or has no server name yet.
     */
    fun applyAddress(v: String): Boolean {
        val t = v.trim()
        return when {
            t.startsWith("\\\\") || t.startsWith("//") -> {
                val parts = t.trimStart('\\', '/').split('\\', '/').filter { it.isNotEmpty() }
                if (parts.isEmpty()) return false
                kind = NetKind.SMB; host = parts[0]; share = parts.getOrElse(1) { "" }; root = parts.drop(2).joinToString("/")
                true
            }
            t.startsWith("smb://", true) -> runCatching {
                val u = URI(t)
                val h = u.host ?: return false
                val parts = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
                kind = NetKind.SMB; host = h; if (u.port > 0) port = u.port.toString()
                share = parts.getOrElse(0) { "" }; root = parts.drop(1).joinToString("/")
                u.userInfo?.let { user = it.substringBefore(':') }
                true
            }.getOrDefault(false)
            Regex("^(https?|davs?)://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> runCatching {
                val u = URI(t.replaceFirst(Regex("^davs://", RegexOption.IGNORE_CASE), "https://").replaceFirst(Regex("^dav://", RegexOption.IGNORE_CASE), "http://"))
                val h = u.host ?: return false
                kind = NetKind.WEBDAV; host = h; https = u.scheme.equals("https", true); if (u.port > 0) port = u.port.toString()
                share = URLDecoder.decode(u.rawPath ?: "", "UTF-8").trim('/'); root = ""
                true
            }.getOrDefault(false)
            else -> false
        }
    }

    /** Pasting an address fills the form at once; typed by hand, it is read at test or save time. */
    fun onHost(v: String) {
        if (v.length - host.length > 1 && applyAddress(v)) return
        host = v
    }

    /** The chosen folder's name, else the share's, else the server's. */
    fun defaultName(): String {
        fun last(s: String) = s.trim().trim('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return last(root).ifEmpty { last(share) }.ifEmpty { host.trim() }
    }

    fun draft(): NetworkSourceEntity {
        applyAddress(host)
        val base = loaded ?: NetworkSourceEntity(kind = kind, name = "", host = "", share = "")
        return base.copy(
            kind = kind, name = name.trim().ifEmpty { defaultName() },
            host = host.trim(), port = port.trim().toIntOrNull(), share = share.trim().trim('/', '\\'), rootPath = root.trim().trim('/', '\\').replace('\\', '/'),
            username = user.trim().ifEmpty { null }, domain = domain.trim().ifEmpty { null }, useHttps = https,
        )
    }

    fun passwordFor(e: NetworkSourceEntity): String? = password.ifEmpty { null } ?: Secrets.decrypt(e.passwordEnc)

    fun errorText(e: Throwable): String = when (e) {
        is RemoteAuthFailed -> ui.str(R.string.net_auth_failed)
        is java.io.FileNotFoundException -> ui.str(R.string.net_test_missing)
        is java.io.IOException -> when {
            !ui.g.network.online.value -> ui.str(R.string.net_test_no_network)
            e.message.orEmpty().let { "BAD_NETWORK_NAME" in it || "not a disk share" in it } -> ui.str(R.string.net_test_share, share)
            else -> ui.str(R.string.net_test_unreachable, e.message ?: e.javaClass.simpleName)
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Tests the form by listing [path]; a success also opens the folder browser there. */
    fun list(path: String) {
        probe = Probe.Testing(probe.browse)
        ui.scope.launch {
            val e = draft()
            val r = withContext(Dispatchers.IO) {
                runCatching { NetworkSources.create(e, passwordFor(e)).use { fs -> fs.list(path) } }
            }
            probe = r.fold(
                onSuccess = { entries ->
                    val dirs = entries.filter { it.isDir }.sortedBy { it.name.lowercase() }
                    val audio = entries.count { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in TagReader.AUDIO_EXT }
                    Probe.Ok(ui.str(R.string.net_test_ok, dirs.size, audio), path, dirs)
                },
                onFailure = { Probe.Failed(errorText(it)) },
            )
        }
    }

    /** Asks the server of the form which folders it shares, with the account of the form (or as a guest). */
    fun listShares() {
        val e = draft()
        if (e.host.isBlank()) return
        shares = ShareList.Loading(e.host)
        ui.scope.launch {
            val r = runCatching { LanDiscovery.shares(SmbTarget(e.host, e.port ?: LanDiscovery.SMB_PORT, "", e.username, passwordFor(e), e.domain)) }
            // The form moved on to another server meanwhile: this answer is no longer wanted.
            if ((shares as? ShareList.Loading)?.host != e.host) return@launch
            shares = r.fold(
                onSuccess = { ShareList.Listed(e.host, it) },
                onFailure = { if (it is RemoteAuthFailed) ShareList.NeedsLogin(e.host) else ShareList.Failed(e.host, errorText(it)) },
            )
        }
    }

    /** A server found on the network fills the form; an SMB server is then asked for its shares. */
    fun pick(server: FoundServer) {
        kind = server.kind; host = server.address; port = server.port?.toString() ?: ""; root = ""
        probe = Probe.Idle
        if (server.kind == NetKind.WEBDAV) { share = server.path ?: ""; https = server.https; shares = ShareList.Idle }
        else { share = ""; listShares() }
    }

    val typedAddress = host.trim().let { it.startsWith("\\\\") || it.startsWith("//") || it.contains("://") }
    val valid = host.isNotBlank() && (kind == NetKind.WEBDAV || share.isNotBlank() || typedAddress)

    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(if (id == 0L) R.string.net_add else R.string.net_edit), ui::back, actions = {
            if (id != 0L) TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.remove), color = c.danger) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── Search of the local network ─────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val scanning = found is Discovery.Scanning
                FilledTonalButton(onClick = { if (scanning) discovery.stop() else discovery.start(ui.scope) }) {
                    Icon(Icons.Rounded.WifiFind, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(when { scanning -> R.string.net_find_stop; found is Discovery.Idle -> R.string.net_find; else -> R.string.net_find_again }))
                }
                if (found is Discovery.Idle) Text(stringResource(R.string.net_find_text), style = MaterialTheme.typography.bodySmall, color = c.text1, modifier = Modifier.weight(1f))
            }
            when (val d = found) {
                Discovery.Idle -> {}
                Discovery.NoNetwork -> Text(stringResource(R.string.net_find_no_wifi), style = MaterialTheme.typography.bodySmall, color = c.danger)
                is Discovery.Scanning -> {
                    Text(stringResource(R.string.net_finding, d.done, d.total), style = MaterialTheme.typography.bodySmall, color = c.text1)
                    LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg3)
                }
                is Discovery.Done -> if (d.found.isEmpty()) Text(stringResource(R.string.net_find_none), style = MaterialTheme.typography.bodySmall, color = c.text1)
            }
            found.servers.forEach { s ->
                val chosen = s.address == host.trim() && s.kind == kind
                Row(Modifier.fillMaxWidth().clickable { pick(s) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (s.kind == NetKind.SMB) Icons.Rounded.Lan else Icons.Rounded.Cloud, null, tint = if (chosen) c.accent else c.text1)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, color = c.text0, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            (if (s.kind == NetKind.SMB) "SMB" else if (s.https) "WebDAV · HTTPS" else "WebDAV") + " · " + s.address + (s.port?.let { ":$it" } ?: ""),
                            color = c.text1, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (chosen) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent)
                }
            }

            // ── The form ────────────────────────────────────────────────────────────────────────
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                NetKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k, onClick = { kind = k; probe = Probe.Idle; shares = ShareList.Idle },
                        shape = SegmentedButtonDefaults.itemShape(i, NetKind.entries.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = c.accentSoft, activeContentColor = c.text0),
                    ) { Text(if (k == NetKind.SMB) stringResource(R.string.net_kind_smb) else "WebDAV") }
                }
            }
            Text(
                stringResource(if (kind == NetKind.SMB) R.string.net_smb_help else R.string.net_dav_help),
                style = MaterialTheme.typography.bodySmall, color = c.text1,
            )
            OutlinedTextField(host, ::onHost, label = { Text(stringResource(R.string.net_host)) }, placeholder = { Text(if (kind == NetKind.SMB) "192.168.1.20 / nas" else "cloud.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) applyAddress(host) })
            OutlinedTextField(
                share, { share = it },
                label = { Text(stringResource(if (kind == NetKind.SMB) R.string.net_share else R.string.net_dav_path)) },
                placeholder = { Text(if (kind == NetKind.SMB) "Musique" else "remote.php/dav/files/moi") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (kind == NetKind.SMB) IconButton(onClick = ::listShares, enabled = host.isNotBlank() && shares !is ShareList.Loading) {
                        Icon(Icons.Rounded.FolderShared, stringResource(R.string.net_shares_list))
                    }
                },
            )
            if (kind == NetKind.SMB) when (val s = shares) {
                ShareList.Idle -> {}
                is ShareList.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.net_shares_title, s.host), style = MaterialTheme.typography.bodySmall, color = c.text1)
                }
                is ShareList.Listed -> if (s.shares.isEmpty()) Text(stringResource(R.string.net_shares_none), style = MaterialTheme.typography.bodySmall, color = c.text1)
                else {
                    Text(stringResource(R.string.net_shares_title, s.host), style = MaterialTheme.typography.bodySmall, color = c.text1)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.shares.forEach { sh ->
                            val on = sh.name.equals(share.trim(), ignoreCase = true)
                            AssistChip(
                                onClick = { share = sh.name; root = ""; list("") },
                                label = { Text(sh.name) },
                                leadingIcon = { Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.Folder, null, Modifier.size(18.dp)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (on) c.accentSoft else c.bg2, labelColor = c.text0,
                                    leadingIconContentColor = if (on) c.accent else c.text1,
                                ),
                            )
                        }
                    }
                }
                is ShareList.NeedsLogin -> Text(stringResource(R.string.net_shares_login), style = MaterialTheme.typography.bodySmall, color = c.text0)
                is ShareList.Failed -> Text(stringResource(R.string.net_shares_failed, s.message), style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
            OutlinedTextField(root, { root = it }, label = { Text(stringResource(R.string.net_root)) }, placeholder = { Text(stringResource(R.string.net_root_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.net_port)) },
                    placeholder = { Text(if (kind == NetKind.SMB) "445" else if (https) "443" else "80") },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (kind == NetKind.WEBDAV) Row(Modifier.weight(1f).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("HTTPS", color = c.text0, modifier = Modifier.weight(1f)); Switch(https, { https = it })
                }
                else OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(R.string.net_domain)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.net_user)) }, placeholder = { Text(stringResource(R.string.net_user_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text(stringResource(R.string.net_password)) },
                placeholder = { if (loaded?.passwordEnc != null) Text(stringResource(R.string.net_password_kept)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
            )
            if (shares is ShareList.NeedsLogin) OutlinedButton(onClick = ::listShares, enabled = user.isNotBlank()) {
                Icon(Icons.Rounded.FolderShared, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.net_shares_list))
            }
            if (kind == NetKind.WEBDAV && !https && (password.isNotEmpty() || loaded?.passwordEnc != null))
                Text(stringResource(R.string.net_http_warning), style = MaterialTheme.typography.bodySmall, color = c.danger)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, placeholder = { Text(defaultName()) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            // Saved at once, apart from the form: it changes no connection setting.
            if (id != 0L) {
                val paused by g.settings.scanPaused.collectAsState(initial = emptySet())
                ToggleRow(stringResource(R.string.net_pause), stringResource(R.string.net_pause_text), id in paused) { on ->
                    ui.scope.launch { g.settings.setScanPaused(id, on) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { list(root.trim().trim('/')) }, enabled = valid && !probe.busy) { Text(stringResource(R.string.net_test)) }
                if (probe.busy) CircularProgressIndicator(Modifier.padding(start = 8.dp), color = c.accent, strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
                    onClick = {
                        ui.scope.launch {
                            val e = draft()
                            val newId = withContext(Dispatchers.IO) {
                                val enc = if (password.isNotEmpty()) Secrets.encrypt(password) else e.passwordEnc
                                val saved = e.copy(passwordEnc = enc)
                                val rid = g.db.sources().upsert(saved)
                                val sid = if (saved.id != 0L) saved.id else rid
                                g.sources.forget(sid)
                                sid
                            }
                            ui.snack(ui.str(R.string.net_saved, e.name))
                            ui.back()
                            g.scanner.request(app.chudmusic.library.ScanRequest(local = false, network = true, onlyId = newId, force = true))
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            }
            when (val p = probe) {
                is Probe.Ok -> ProbeMessage(true, p.message)
                is Probe.Failed -> ProbeMessage(false, p.message)
                is Probe.Testing -> p.shown?.let { ProbeMessage(true, it.message) }
                Probe.Idle -> {}
            }
            probe.browse?.let { shown ->
                val path = shown.path
                Text(stringResource(R.string.net_browse), style = MaterialTheme.typography.titleSmall, color = c.text0, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotEmpty()) IconButton(onClick = { list(path.substringBeforeLast('/', "")) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Text("/" + path, style = MaterialTheme.typography.bodyMedium, color = c.text1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { root = path }, enabled = root.trim('/') != path) { Text(stringResource(R.string.net_use_folder)) }
                }
                shown.dirs.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !probe.busy) { list(d.path) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = c.text1); Spacer(Modifier.width(12.dp))
                        Text(d.name, color = c.text0, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Text(stringResource(R.string.net_safety), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
    if (deleting) ConfirmDialog(
        title = stringResource(R.string.net_remove_q, name), text = stringResource(R.string.net_remove_body), confirm = stringResource(R.string.remove),
        onDismiss = { deleting = false },
        onConfirm = { ui.back(); ui.scope.launch(Dispatchers.IO) { g.scanner.removeSource(id) } },
    )
}

@Composable
private fun ProbeMessage(ok: Boolean, message: String) {
    val c = Chud.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) c.accent else c.danger)
        Spacer(Modifier.width(8.dp))
        Text(message, color = if (ok) c.text0 else c.danger, style = MaterialTheme.typography.bodyMedium)
    }
}
