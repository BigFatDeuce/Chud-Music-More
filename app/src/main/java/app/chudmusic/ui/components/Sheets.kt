package app.chudmusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.MenuContext
import app.chudmusic.ui.PlaylistRef
import app.chudmusic.ui.tracksCount
import app.chudmusic.ui.theme.Chud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun MenuItem(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Chud.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) c.danger else c.text1)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (danger) c.danger else c.text0)
    }
}

/** The actions of one track, the same list as the PC's right-click menu. */
@Composable
fun TrackMenuSheet(track: Track, ctx: MenuContext, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = Chud.colors
    fun act(block: () -> Unit) = { onDismiss(); block() }
    val inLibrary by remember(track.id) { ui.g.db.tracks().inLibraryFlow(track.id) }.collectAsState(initial = 0)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.bg2) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.coverUri, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = c.text0, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(track.artist, track.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
            if (track.available && ctx !is MenuContext.InQueue) {
                MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.menu_play_next), onClick = act { ui.playNext(listOf(track)) })
                MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.menu_enqueue), onClick = act { ui.enqueue(listOf(track)) })
            }
            if (!track.isRadio) MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.menu_add_to_playlist), onClick = act { ui.addTo = listOf(track) })
            if (!track.isRadio) MenuItem(Icons.Rounded.Waves, stringResource(R.string.menu_start_mix), onClick = act { ui.startMix(track) })
            if (ui.canGoAlbum(track)) MenuItem(Icons.Rounded.Album, stringResource(R.string.menu_go_album), onClick = act { ui.goAlbum(track) })
            if (ui.canGoArtist(track)) MenuItem(Icons.Rounded.Person, stringResource(R.string.menu_go_artist), onClick = act { ui.goArtist(track) })
            if (track.isYoutube) {
                MenuItem(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.menu_open_ytm), onClick = act { ui.openInYouTube(track) })
            }
            if (!track.isRadio) MenuItem(Icons.Rounded.Share, stringResource(R.string.menu_share), onClick = act { ui.share(track) })
            if (!track.isRadio) MenuItem(Icons.Rounded.Info, stringResource(R.string.menu_track_card), onClick = act { ui.card = track })
            if (track.isYoutube) app.chudmusic.youtube.HiddenArtists.mainArtistOf(track)?.let { a ->
                MenuItem(Icons.Rounded.VisibilityOff, stringResource(R.string.menu_hide_artist, a.name), onClick = act { ui.hideArtist(a) })
            }
            when (ctx) {
                is MenuContext.InPlaylist -> MenuItem(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.pl_remove_one), danger = true, onClick = act {
                    ui.scope.launch(Dispatchers.IO) { ui.g.playlists.remove(ctx.playlistId, listOf(ctx.entryId)) }
                })
                is MenuContext.InQueue -> MenuItem(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.queue_remove), danger = true, onClick = act { ui.g.player.removeAt(ctx.index) })
                MenuContext.RecentPlayed -> MenuItem(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.home_forget_played), onClick = act { ui.forgetRecentPlay(track) })
                is MenuContext.InSmart -> MenuItem(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.smart_exclude), danger = true, onClick = act {
                    ui.scope.launch {
                        withContext(Dispatchers.IO) { ui.g.smart.exclude(ctx.id, track.id, true) }
                        ui.snack(ui.str(R.string.smart_excluded, track.title), ui.str(R.string.undo)) {
                            ui.scope.launch(Dispatchers.IO) { ui.g.smart.exclude(ctx.id, track.id, false) }
                        }
                    }
                })
                MenuContext.None -> {}
            }
            if ((track.source == Source.LOCAL || track.source == Source.NETWORK) && inLibrary > 0) {
                MenuItem(Icons.Rounded.Delete, stringResource(R.string.lib_remove_one), danger = true, onClick = act { ui.removeFromLibrary(listOf(track)) })
            }
        }
    }
}

/** The actions of one playlist (long press on it, anywhere it is shown). */
@Composable
fun PlaylistMenuSheet(p: PlaylistRef, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun act(block: () -> Unit) = { onDismiss(); block() }
    suspend fun tracks() = withContext(Dispatchers.IO) { if (p.smart) ui.g.smart.tracksNow(p.id) else ui.g.playlists.tracks(p.id) }
    if (!renaming && !deleting) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg2) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.text0, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
            MenuItem(Icons.Rounded.PlayArrow, stringResource(R.string.play), onClick = act { ui.scope.launch { tracks().let { t -> ui.play(t, t.indexOfFirst { it.available }.coerceAtLeast(0)) } } })
            MenuItem(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle_short), onClick = act { ui.scope.launch { ui.shuffle(tracks()) } })
            MenuItem(Icons.Rounded.Image, stringResource(R.string.pl_cover_change), onClick = act { ui.pickCover(p) })
            if (p.cover != null) MenuItem(Icons.Rounded.HideImage, stringResource(R.string.pl_cover_auto), onClick = act { ui.setPlaylistCover(p, null) })
            if (p.smart) MenuItem(Icons.Rounded.Tune, stringResource(R.string.smart_edit), onClick = act { ui.navigate(app.chudmusic.ui.Routes.SmartEdit(p.id)) })
            MenuItem(Icons.Rounded.Edit, stringResource(R.string.rename_ellipsis), onClick = { renaming = true })
            MenuItem(Icons.Rounded.Delete, stringResource(R.string.delete_ellipsis), danger = true, onClick = { deleting = true })
        }
    }
    if (renaming) TextInputDialog(
        title = stringResource(R.string.pl_rename_title), initial = p.name, confirm = stringResource(R.string.rename), onDismiss = onDismiss,
        onConfirm = { n ->
            onDismiss()
            ui.scope.launch(Dispatchers.IO) {
                if (p.smart) ui.g.db.smart().get(p.id)?.let { e -> ui.g.db.smart().save(p.id, n, e.rules, System.currentTimeMillis()) } else ui.g.playlists.rename(p.id, n)
            }
        },
    )
    if (deleting) {
        var count by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(p.id) { if (!p.smart) count = withContext(Dispatchers.IO) { ui.g.db.playlists().count(p.id) } }
        ConfirmDialog(
            title = stringResource(R.string.pl_delete_q, p.name),
            text = if (p.smart) stringResource(R.string.smart_delete_body) else stringResource(R.string.pl_delete_body, tracksCount(count ?: 0)),
            confirm = stringResource(R.string.delete), onDismiss = onDismiss, onConfirm = { ui.deletePlaylist(p) },
        )
    }
}

/** "Ajouter à une playlist": the playlists, most recent first, plus "Nouvelle playlist…". */
@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    val playlists by ui.g.db.playlists().summaries().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg2) {
        Text(
            stringResource(R.string.menu_add_to_playlist) + " · " + tracksCount(tracks.size),
            style = MaterialTheme.typography.titleMedium, color = c.text0, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 480.dp).navigationBarsPadding()) {
            item { MenuItem(Icons.Rounded.Add, stringResource(R.string.pl_new_ellipsis)) { creating = true } }
            items(playlists.sortedByDescending { it.updatedAt }, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onDismiss(); ui.addToPlaylist(p.id, p.name, tracks) }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.LibraryMusic, null, tint = c.text1)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tracksCount(p.count), style = MaterialTheme.typography.bodySmall, color = c.text2)
                    }
                }
            }
        }
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.pl_new), initial = "", confirm = stringResource(R.string.create),
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; onDismiss(); ui.createPlaylist(name, tracks) },
        )
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, label: String? = null) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true, label = label?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(value.text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit, danger: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = if (danger) Chud.colors.danger else Chud.colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Runs [block] off the main thread and reports its failure as a message. */
suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }
