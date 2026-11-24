package app.chudmusic.ui.playlists

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.playlists.PlaylistExchange
import app.chudmusic.playlists.SmartRules
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.PlaylistRef
import app.chudmusic.ui.Routes
import app.chudmusic.ui.UiController
import app.chudmusic.ui.components.ConfirmDialog
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.TextInputDialog
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.home.PlaylistCover
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.tracksCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the file picker for playlists (JSON of Chud Music PC, M3U, M3U8) and imports what is chosen. */
class PlaylistImporter(private val ui: UiController) : (Uri) -> Unit {
    var pick: () -> Unit = {}

    override fun invoke(uri: Uri) {
        ui.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = ui.context.contentResolver
                    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.m3u8"
                    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("no data")
                    if (bytes.size > 50 * 1024 * 1024) error("file too large")
                    ui.g.playlists.import(name, bytes.toString(Charsets.UTF_8), ui.g.library, ui.g.covers::fromDataUri)
                }
            }
            result.onSuccess { list ->
                // A smart playlist finds its own tracks: those of the file do not count.
                val found = list.filter { it.smart == null }.sumOf { it.found }
                val missing = list.filter { it.smart == null }.sumOf { it.missing }
                val res = ui.context.resources
                val msg = listOfNotNull(
                    res.getQuantityString(R.plurals.pl_imported, list.size, list.size) + " · " + res.getQuantityString(R.plurals.pl_found, found, found),
                    missing.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.pl_missing, it, it) },
                ).joinToString(", ")
                if (list.size == 1) ui.snack(msg, ui.str(R.string.open)) { /* the newest playlist */ ui.scope.launch { openNewest(list[0].name) } }
                else ui.snack(msg)
            }.onFailure { e ->
                ui.snack(if (e is PlaylistExchange.NotChudFile) ui.str(R.string.pl_import_not_chud) else ui.str(R.string.pl_import_failed, e.message ?: ""))
            }
        }
    }

    private suspend fun openNewest(name: String) {
        val smart = withContext(Dispatchers.IO) { ui.g.db.smart().all().filter { it.name == name }.maxByOrNull { it.id } }
        val p = withContext(Dispatchers.IO) { ui.g.db.playlists().all().filter { it.name == name }.maxByOrNull { it.createdAt } }
        when {
            p != null && (smart == null || p.createdAt >= smart.createdAt) -> ui.navigate(Routes.Playlist(p.id))
            smart != null -> ui.navigate(Routes.Smart(smart.id))
        }
    }
}

@Composable
fun rememberPlaylistImporter(ui: UiController): PlaylistImporter {
    val importer = remember { PlaylistImporter(ui) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(importer) }
    importer.pick = { launcher.launch(arrayOf("*/*")) }
    return importer
}

/** Writes every playlist into one JSON file the PC app imports as is. */
@Composable
fun rememberExportAll(): () -> Unit {
    val ui = LocalUi.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ui.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val g = ui.g
                    val path = g.playlists.exchangePathFn()
                    val all = g.db.playlists().all().map { PlaylistExchange.Out(it.name, g.playlists.tracks(it.id), g.covers.toDataUri(it.cover)) } +
                        g.db.smart().all().map { e ->
                            PlaylistExchange.Out(e.name, g.smart.tracksNow(e.id), g.covers.toDataUri(e.cover), SmartRules.parse(e.rules) ?: SmartRules())
                        }
                    val text = PlaylistExchange.toJson(all, path)
                    ui.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("write")
                    all.size
                }
            }
            r.onSuccess { ui.snack(ui.str(R.string.pls_exported)) }.onFailure { ui.snack(ui.str(R.string.pl_export_failed, it.message ?: "")) }
        }
    }
    return { launcher.launch("chud-music-playlists.json") }
}

@Composable
fun PlaylistsScreen(importer: PlaylistImporter) {
    val ui = LocalUi.current
    val c = Chud.colors
    val playlists by ui.g.db.playlists().summaries().collectAsState(initial = null)
    var creating by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf<WipeStep?>(null) }
    val exportAll = rememberExportAll()
    var smartTab by rememberSaveable { mutableStateOf(false) }
    val smart by ui.g.smart.list.collectAsState(initial = null)

    Scaffold(
        containerColor = c.bg0,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (smartTab) ui.navigate(Routes.SmartEdit()) else creating = true }, containerColor = c.accent, contentColor = c.accentInk,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(if (smartTab) R.string.smart_new else R.string.pl_new)) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_playlists), style = MaterialTheme.typography.displaySmall, color = c.text0)
                    Text(stringResource(if (smartTab) R.string.smart_sub else R.string.pls_sub), style = MaterialTheme.typography.bodySmall, color = c.text1)
                }
                IconButton(onClick = { importer.pick() }) { Icon(Icons.Rounded.FileDownload, stringResource(R.string.set_import), tint = c.text1) }
                IconButton(onClick = exportAll, enabled = !playlists.isNullOrEmpty() || !smart.isNullOrEmpty()) { Icon(Icons.Rounded.FileUpload, stringResource(R.string.set_export_all), tint = c.text1) }
                if (!smartTab) IconButton(onClick = { wiping = WipeStep.WARN }, enabled = !playlists.isNullOrEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.pls_delete_all), tint = if (playlists.isNullOrEmpty()) c.text2 else c.danger)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.nav_playlists), !smartTab, onClick = { smartTab = false })
                Pill(stringResource(R.string.smart_title), smartTab, onClick = { smartTab = true })
            }
            val list = playlists
            when {
                smartTab -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    items(smart.orEmpty(), key = { it.id }) { SmartRow(it) }
                }
                list == null -> {}
                list.isEmpty() -> EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.pls_empty_title), stringResource(R.string.pls_empty_text))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    items(list, key = { it.id }) { p ->
                        val menu = { ui.playlistMenu = PlaylistRef(p.id, p.name, p.cover) }
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { ui.navigate(Routes.Playlist(p.id)) }, onLongClick = menu).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCover(p.id, p.cover, p.updatedAt, 64.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(tracksCount(p.count), formatLong(p.durationMs)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.text1)
                            }
                            IconButton(onClick = menu) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.pl_menu), tint = c.text1) }
                        }
                    }
                }
            }
        }
    }
    if (creating) TextInputDialog(
        title = stringResource(R.string.pl_new), initial = "", confirm = stringResource(R.string.create), label = stringResource(R.string.name),
        onDismiss = { creating = false }, onConfirm = { creating = false; ui.createPlaylist(it, emptyList(), open = true) },
    )
    // Two confirmations before deleting every playlist: there is no undo.
    val count = playlists?.size ?: 0
    when (wiping) {
        null -> {}
        WipeStep.WARN -> ConfirmDialog(
            title = stringResource(R.string.pls_delete_all_q),
            text = pluralStringResource(R.plurals.pls_delete_all_body, count, count),
            confirm = stringResource(R.string.pls_delete_all_continue),
            onDismiss = { wiping = null },
            onConfirm = { wiping = WipeStep.CONFIRM },
        )
        WipeStep.CONFIRM -> ConfirmDialog(
            title = stringResource(R.string.pls_delete_all_final_q),
            text = pluralStringResource(R.plurals.pls_delete_all_final_body, count, count),
            confirm = stringResource(R.string.pls_delete_all_do),
            onDismiss = { wiping = null },
            onConfirm = {
                ui.scope.launch(Dispatchers.IO) {
                    val covers = ui.g.db.playlists().all().mapNotNull { it.cover }
                    ui.g.playlists.deleteAll()
                    covers.forEach(ui.g.covers::dropChosen)
                    ui.snack(ui.context.resources.getQuantityString(R.plurals.pls_deleted, count, count))
                }
            },
        )
    }
}

/** The two steps of "Delete all playlists": the warning, then the last confirmation. */
private enum class WipeStep { WARN, CONFIRM }
