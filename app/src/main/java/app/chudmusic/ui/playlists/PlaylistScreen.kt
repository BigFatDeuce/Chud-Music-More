package app.chudmusic.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import app.chudmusic.playlists.PlaylistTools
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.playlists.PlaylistExchange
import app.chudmusic.playlists.Playlists
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.MenuContext
import app.chudmusic.ui.PlaylistRef
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.ConfirmDialog
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.TextInputDialog
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.library.CollectionHeader
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.tracksCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistScreen(id: Long) {
    val ui = LocalUi.current
    val c = Chud.colors
    val g = ui.g
    val playlist by g.db.playlists().flow(id).collectAsState(initial = null)
    val entries by remember(id) {
        g.db.playlists().entriesFlow(id).map { rows -> rows.map { Playlists.Entry(it.entryId, it.track.toTrack()) } }
    }.collectAsState(initial = null)
    val current by g.player.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var swaps by remember { mutableStateOf<List<Playlists.Swap>?>(null) }
    var linking by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sorting by remember { mutableStateOf(false) }
    var dupes by remember { mutableStateOf<List<Long>?>(null) }

    // Deleted from its long-press menu: nothing left to show here.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(playlist) { if (playlist != null) seen = true else if (seen) ui.back() }

    // Local order while dragging; written to the database when the finger lifts.
    var order by remember { mutableStateOf<List<Playlists.Entry>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(entries) { if (!dragging) entries?.let { order = it } }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        // Index 0 is the header.
        val f = from.index - 1; val t = to.index - 1
        if (f in order.indices && t in order.indices) order = order.toMutableList().apply { add(t, removeAt(f)) }
    }

    val name = playlist?.name ?: ""
    val ref = PlaylistRef(id, name, playlist?.cover)
    val tracks = order.map { it.track }
    // While searching, only the matching entries are listed and dragging is off (moving inside a
    // part of the list would mean nothing).
    val filtering = searching && query.isNotBlank()
    val shown = if (filtering) order.filter { PlaylistTools.matches(it.track, query) } else order
    val shownTracks = shown.map { it.track }

    /** Writes [newOrder]; the snackbar offers to go back to the order before. */
    fun applyOrder(newOrder: List<Playlists.Entry>, message: String) {
        val before = order.map { it.entryId }
        val after = newOrder.map { it.entryId }
        if (before == after) { ui.snack(message); return }
        order = newOrder
        ui.scope.launch(Dispatchers.IO) { runCatching { g.playlists.reorder(id, after) } }
        ui.snack(message, ui.str(R.string.undo)) { ui.scope.launch(Dispatchers.IO) { runCatching { g.playlists.reorder(id, before) } } }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ui.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = PlaylistExchange.toJson(listOf(PlaylistExchange.Out(name, g.playlists.tracks(id), g.covers.toDataUri(playlist?.cover))), g.playlists.exchangePathFn())
                    ui.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("write")
                }
            }
            ui.snack(if (r.isSuccess) ui.str(R.string.pl_exported) else ui.str(R.string.pl_export_failed, r.exceptionOrNull()?.message ?: ""))
        }
    }
    val exportM3u = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ui.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val (text, skipped) = PlaylistExchange.toM3u(name, g.playlists.tracks(id), g.playlists.exchangePathFn())
                    ui.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("write")
                    skipped
                }
            }
            r.onSuccess { skipped ->
                ui.snack(ui.str(R.string.pl_exported) + if (skipped > 0) " · " + ui.context.resources.getQuantityString(R.plurals.pl_export_skipped, skipped, skipped) else "")
            }.onFailure { ui.snack(ui.str(R.string.pl_export_failed, it.message ?: "")) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(name, ui::back, actions = {
            if (tracks.size > 1) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(Icons.Rounded.Search, stringResource(R.string.pl_search))
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_actions)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { androidx.compose.material3.Text(stringResource(R.string.rename_ellipsis)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { androidx.compose.material3.Text(stringResource(R.string.pl_cover_change)) }, onClick = { menu = false; ui.pickCover(ref) })
                if (playlist?.cover != null) DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.pl_cover_auto)) }, onClick = { menu = false; ui.setPlaylistCover(ref, null) },
                )
                if (tracks.size > 1) DropdownMenuItem(text = { androidx.compose.material3.Text(stringResource(R.string.pl_sort_ellipsis)) }, onClick = { menu = false; sorting = true })
                if (tracks.size > 1) DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.pl_dupes_ellipsis)) },
                    onClick = {
                        menu = false
                        val d = PlaylistTools.duplicates(order)
                        if (d.isEmpty()) ui.snack(ui.str(R.string.pl_dupes_none)) else dupes = d
                    },
                )
                DropdownMenuItem(text = { androidx.compose.material3.Text(stringResource(R.string.pl_export_json)) }, onClick = { menu = false; exportJson.launch("$name.json") })
                DropdownMenuItem(text = { androidx.compose.material3.Text(stringResource(R.string.pl_export_m3u)) }, onClick = { menu = false; exportM3u.launch("$name.m3u8") })
                if (tracks.any { it.isYoutube }) DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.pl_swap_local)) },
                    onClick = {
                        menu = false
                        ui.scope.launch {
                            val s = withContext(Dispatchers.IO) { g.playlists.localSwaps(id, g.library) }
                            if (s.isEmpty()) ui.snack(ui.str(R.string.pl_swap_none)) else swaps = s
                        }
                    },
                )
                DropdownMenuItem(text = { androidx.compose.material3.Text(stringResource(R.string.delete_ellipsis), color = c.danger) }, onClick = { menu = false; deleting = true })
            }
        })
        // Opening the search puts the cursor in it (and the keyboard up).
        val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(searching) { if (searching) runCatching { searchFocus.requestFocus() } }
        if (searching) OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.pl_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Rounded.Clear, stringResource(R.string.clear_search)) } },
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.bg2, focusedContainerColor = c.bg2, unfocusedBorderColor = c.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(searchFocus),
        )
        val e = entries
        if (e == null) return@Column
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                val missing = tracks.count { !it.available }
                CollectionHeader(
                    name, null,
                    listOfNotNull(tracksCount(tracks.size), formatLong(tracks.sumOf { it.durationMs ?: 0 }),
                        missing.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pl_missing, it, it) }).joinToString(" · "),
                    playlist?.cover ?: tracks.firstNotNullOfOrNull { it.coverUri }, tracks, placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
                    onCoverLongClick = { ui.playlistMenu = ref },
                )
                if (filtering && shown.isEmpty()) EmptyState(Icons.Rounded.Search, stringResource(R.string.pl_search_none, query), null)
                if (tracks.isEmpty()) EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.pl_empty), stringResource(R.string.pl_empty_text)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { linking = true }) {
                        Icon(Icons.Rounded.Link, null)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 6.dp))
                        androidx.compose.material3.Text(stringResource(R.string.pl_import_link))
                    }
                }
            }
            itemsIndexed(shown, key = { _, it -> it.entryId }) { i, entry ->
                ReorderableItem(reorder, key = entry.entryId, enabled = !filtering) { isDragging ->
                    TrackRow(
                        entry.track,
                        onClick = { ui.play(shownTracks, i) },
                        onMore = { ui.openMenu(entry.track, MenuContext.InPlaylist(id, entry.entryId)) },
                        playing = current.track?.id == entry.track.id,
                        modifier = Modifier.background(if (isDragging) c.bg3 else c.bg0),
                        trailing = if (filtering) null else { {
                            Icon(
                                Icons.Rounded.DragHandle, stringResource(R.string.queue_move), tint = c.text2,
                                modifier = Modifier.padding(horizontal = 4.dp).draggableHandle(
                                    onDragStarted = { dragging = true },
                                    onDragStopped = {
                                        dragging = false
                                        val ids = order.map { it.entryId }
                                        ui.scope.launch(Dispatchers.IO) { runCatching { g.playlists.reorder(id, ids) } }
                                    },
                                ),
                            )
                        } },
                    )
                }
            }
        }
    }

    if (linking) {
        // A link already copied is offered as it is.
        val copied = remember {
            runCatching {
                ui.context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()
            }.getOrNull()?.takeIf { app.chudmusic.youtube.MusicLink.find(it) != null }.orEmpty()
        }
        TextInputDialog(
            title = stringResource(R.string.pl_import_link), initial = copied, confirm = stringResource(R.string.pl_import_link_go),
            label = stringResource(R.string.pl_import_link_label),
            onDismiss = { linking = false },
            onConfirm = { text ->
                linking = false
                if (!ui.openLink(text, into = id)) ui.snack(ui.str(R.string.share_not_youtube))
            },
        )
    }
    if (renaming) TextInputDialog(
        title = stringResource(R.string.pl_rename_title), initial = name, confirm = stringResource(R.string.rename),
        onDismiss = { renaming = false }, onConfirm = { n -> renaming = false; ui.scope.launch(Dispatchers.IO) { g.playlists.rename(id, n) } },
    )
    if (deleting) ConfirmDialog(
        title = stringResource(R.string.pl_delete_q, name), text = stringResource(R.string.pl_delete_body, tracksCount(tracks.size)), confirm = stringResource(R.string.delete),
        onDismiss = { deleting = false },
        onConfirm = { ui.deletePlaylist(ref) },
    )
    if (sorting) SortDialog(onDismiss = { sorting = false }) { key ->
        sorting = false
        applyOrder(PlaylistTools.sorted(order, key), ui.str(R.string.pl_sorted))
    }
    dupes?.let { d ->
        ConfirmDialog(
            title = stringResource(R.string.pl_dupes_title), text = pluralStringResource(R.plurals.pl_dupes_text, d.size, d.size), confirm = stringResource(R.string.remove),
            onDismiss = { dupes = null },
            onConfirm = {
                ui.scope.launch {
                    withContext(Dispatchers.IO) { g.playlists.remove(id, d) }
                    ui.snack(ui.context.resources.getQuantityString(R.plurals.pl_dupes_removed, d.size, d.size))
                }
            },
        )
    }
    swaps?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.pl_swap_local), text = pluralStringResource(R.plurals.pl_swap_text, s.size, s.size), confirm = stringResource(R.string.pl_swap_do),
            danger = false, onDismiss = { swaps = null },
            onConfirm = { ui.scope.launch(Dispatchers.IO) { g.playlists.applySwaps(id, s) } },
        )
    }
}

/** "Trier par…": the new order is written to the playlist (with Undo on the snackbar). */
@Composable
private fun SortDialog(onDismiss: () -> Unit, onPick: (PlaylistTools.SortKey) -> Unit) {
    val labels = listOf(
        PlaylistTools.SortKey.TITLE to R.string.pl_sort_title, PlaylistTools.SortKey.ARTIST to R.string.pl_sort_artist,
        PlaylistTools.SortKey.ALBUM to R.string.pl_sort_album, PlaylistTools.SortKey.DURATION to R.string.pl_sort_duration,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.pl_sort_q)) },
        text = {
            Column {
                androidx.compose.material3.Text(stringResource(R.string.pl_sort_text), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Chud.colors.text1)
                labels.forEach { (k, l) ->
                    androidx.compose.material3.TextButton(onClick = { onPick(k) }, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Text(stringResource(l), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text(stringResource(R.string.cancel)) } },
    )
}
