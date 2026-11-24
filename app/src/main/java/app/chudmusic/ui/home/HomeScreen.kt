package app.chudmusic.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.library.Library
import app.chudmusic.library.ScanState
import app.chudmusic.ui.AudioAccess
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.Permissions
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.Tile
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.Radii
import app.chudmusic.ui.tracksCount
import kotlinx.coroutines.flow.map

@Composable
fun HomeScreen() {
    val ui = LocalUi.current
    val g = ui.g
    val c = Chud.colors
    val context = LocalContext.current
    val lib by g.library.state.collectAsState()
    val scan by g.scanner.state.collectAsState()
    val recentPlayed by remember { g.db.tracks().recentlyPlayed().map { l -> l.map { it.toTrack() } } }.collectAsState(initial = emptyList())
    val hiddenAdded by g.settings.homeHiddenAdded.collectAsState(initial = emptySet())
    val addedAlbums by remember { g.db.tracks().recentlyAdded(300).map { l -> Library.albumsOf(l.map { it.toTrack() }).also { g.library.lastAdded = it } } }.collectAsState(initial = g.library.lastAdded)
    val recentAlbums = remember(addedAlbums, hiddenAdded) { addedAlbums.filter { it.key !in hiddenAdded }.take(12) }
    var albumMenu by remember { mutableStateOf<app.chudmusic.library.Album?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val playlists by g.db.playlists().summaries().collectAsState(initial = emptyList())
    val radios by g.radio.favorites.collectAsState(initial = emptyList())
    val sources by g.db.sources().flow().collectAsState(initial = emptyList())
    val access by g.audio.access.collectAsState()
    val shown by g.settings.homeSections.collectAsState(initial = HomeSection.entries.toSet())
    val hasAudio = access == AudioAccess.GRANTED
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        g.audio.onResult(ok, (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(Permissions.audio) ?: true)
        if (ok) { g.scanner.watchLocal(); g.scanner.scanAll() }
    }
    // The same tracks all session long, whatever is added or removed meanwhile (like the PC app).
    val seed by g.randomSeed.collectAsState()
    var random by remember { mutableStateOf(g.library.lastRandom) }
    LaunchedEffect(lib.version, seed) { random = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.library.random(seed, 18) } }

    val filled = buildSet {
        if (recentPlayed.isNotEmpty()) add(HomeSection.RECENT_PLAYED)
        if (random.isNotEmpty()) add(HomeSection.RANDOM)
        if (playlists.isNotEmpty()) add(HomeSection.PLAYLISTS)
        if (recentAlbums.isNotEmpty()) add(HomeSection.RECENT_ADDED)
        if (radios.isNotEmpty()) add(HomeSection.RADIO_FAVORITES)
        if (lib.loaded && lib.count < 20) add(HomeSection.GETTING_STARTED)
    }
    val visible = HomeSection.visible(shown, filled)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = c.text0, modifier = Modifier.weight(1f))
                IconButton(onClick = { ui.navigate(Routes.Stats) }) { Icon(Icons.Rounded.Insights, stringResource(R.string.section_stats), tint = c.text1) }
                // A dot on the gear while a new version waits in Settings › Updates.
                val update by g.updater.state.collectAsState()
                IconButton(onClick = { ui.navigate(Routes.Settings) }) {
                    androidx.compose.material3.BadgedBox(badge = { if (update is app.chudmusic.update.Updater.State.Available) androidx.compose.material3.Badge(containerColor = c.accent) }) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.section_settings), tint = c.text1)
                    }
                }
            }
        }
        (scan as? ScanState.Running)?.let { running ->
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.scan_running, running.label ?: stringResource(R.string.src_phone)) + running.counter,
                        style = MaterialTheme.typography.bodySmall, color = c.text1,
                    )
                    Spacer(Modifier.height(4.dp))
                    val progress = running.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg3)
                    else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg3)
                }
            }
        }
        when (access) {
            AudioAccess.GRANTED -> {}
            AudioAccess.ASKABLE -> item {
                Card(Icons.Rounded.LibraryMusic, stringResource(R.string.perm_audio_title), stringResource(R.string.perm_audio_text)) {
                    Button(onClick = { g.audio.onAsk(); askAudio.launch(Permissions.audio) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk)) {
                        Text(stringResource(R.string.perm_allow))
                    }
                }
            }
            // Android no longer shows its question: the only way left is the app's page in the settings.
            AudioAccess.BLOCKED -> item {
                Card(Icons.Rounded.LibraryMusic, stringResource(R.string.perm_audio_title), stringResource(R.string.perm_audio_blocked_text)) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
                    ) { Text(stringResource(R.string.perm_open_settings)) }
                }
            }
        }

        if (HomeSection.RECENT_PLAYED in visible) {
            item { SectionTitle(stringResource(R.string.lib_recent_played), action = stringResource(R.string.clear_all)) { clearing = true } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recentPlayed.size) { i ->
                        val t = recentPlayed[i]
                        Tile(t.title, t.artist, t.coverUri, onClick = { ui.play(recentPlayed, i) }, size = 132.dp, onLongClick = { ui.openMenu(t, app.chudmusic.ui.MenuContext.RecentPlayed) })
                    }
                }
            }
        }
        if (HomeSection.RANDOM in visible) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.home_random), Modifier.weight(1f))
                    IconButton(onClick = { g.randomSeed.value = kotlin.random.Random.nextInt() }, modifier = Modifier.padding(top = 14.dp, end = 4.dp)) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.home_random_refresh), tint = c.text1)
                    }
                }
            }
            item {
                // A new draw starts from its first track.
                val rowState = rememberLazyListState()
                LaunchedEffect(seed) { rowState.scrollToItem(0) }
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(random.size, key = { random[it].id }) { i ->
                        val t = random[i]
                        Tile(t.title, t.artist, t.coverUri, onClick = { ui.play(random, i) }, size = 132.dp, onLongClick = { ui.openMenu(t) })
                    }
                }
            }
        }
        if (HomeSection.PLAYLISTS in visible) {
            item { SectionTitle(stringResource(R.string.nav_playlists), action = stringResource(R.string.see_all)) { ui.navigate(Routes.Playlists) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists.sortedByDescending { it.updatedAt }.take(12), key = { it.id }) { p ->
                        PlaylistTile(p.id, p.name, tracksCount(p.count), p.cover, p.updatedAt) { ui.navigate(Routes.Playlist(p.id)) }
                    }
                }
            }
        }
        if (HomeSection.RECENT_ADDED in visible) {
            item { SectionTitle(stringResource(R.string.lib_recent_added), action = stringResource(R.string.see_all)) { ui.navigate(Routes.Library) } }
            item {
                // New albums arrive at the front (a scan in progress): stay at the start instead of
                // following the previously first album.
                val rowState = rememberLazyListState()
                LaunchedEffect(recentAlbums.first().key) { if (rowState.firstVisibleItemIndex <= 1) rowState.scrollToItem(0) }
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recentAlbums, key = { it.key }) { a ->
                        Tile(a.title, a.artist, a.cover, onClick = { ui.navigate(Routes.Album(a.key)) }, size = 132.dp, onLongClick = { albumMenu = a })
                    }
                }
            }
        }
        if (HomeSection.RADIO_FAVORITES in visible) {
            item { SectionTitle(stringResource(R.string.radio_favorites), action = stringResource(R.string.see_all)) { ui.navigate(Routes.Radio) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(radios, key = { it.id }) { r -> Tile(r.title, r.artist, r.coverUri, onClick = { ui.playRadio(r) }, size = 110.dp) }
                }
            }
        }

        // First steps: what can be added, as long as there is little to show.
        if (HomeSection.GETTING_STARTED in visible) {
            item { SectionTitle(stringResource(R.string.home_start)) }
            if (sources.isEmpty()) item {
                Card(Icons.Rounded.Lan, stringResource(R.string.home_add_network), stringResource(R.string.home_add_network_text), onClick = { ui.navigate(Routes.NetSource()) })
            }
            item { Card(Icons.Rounded.SmartDisplay, stringResource(R.string.nav_youtube), stringResource(R.string.home_yt_text), onClick = { ui.navigate(Routes.YouTube) }) }
            if (hasAudio && !scan.running) item {
                Card(Icons.Rounded.Sync, stringResource(R.string.set_rescan), stringResource(R.string.home_rescan_text), onClick = { g.scanner.scanAll(force = true) })
            }
        }

        // Nothing to show (all hidden, or still empty): the logo rather than a blank page. Not before
        // the library is read, so it does not flash at launch.
        if (visible.isEmpty() && lib.loaded) item {
            Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(0.75f), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.chud_logo), stringResource(R.string.app_name),
                    Modifier.size(180.dp),
                )
            }
        }
    }

    if (clearing) app.chudmusic.ui.components.ConfirmDialog(
        title = stringResource(R.string.home_clear_played_q), text = stringResource(R.string.home_clear_played_text), confirm = stringResource(R.string.clear_all),
        onDismiss = { clearing = false }, onConfirm = { ui.clearRecentPlays() },
    )
    albumMenu?.let { a ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { albumMenu = null },
            title = { Text(a.title) },
            text = { Text(stringResource(R.string.home_forget_added_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { albumMenu = null; ui.forgetRecentAlbum(a.key, a.title) }) { Text(stringResource(R.string.home_forget_added)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { albumMenu = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Card(icon: ImageVector, title: String, text: String, onClick: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val c = Chud.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(Radii.l)).background(c.bg2)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text0)
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.text1)
            if (action != null) { Spacer(Modifier.height(8.dp)); action() }
        }
    }
}

/** A playlist shown with up to 4 covers of its tracks. */
@Composable
fun PlaylistTile(id: Long, name: String, subtitle: String, cover: String?, updatedAt: Long, size: androidx.compose.ui.unit.Dp = 132.dp, onClick: () -> Unit) {
    val ui = LocalUi.current
    Column(Modifier.width(size).combinedClickable(onClick = onClick, onLongClick = { ui.playlistMenu = app.chudmusic.ui.PlaylistRef(id, name, cover) })) {
        PlaylistCover(id, cover, updatedAt, size)
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = Chud.colors.text0, maxLines = 1)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Chud.colors.text1, maxLines = 1)
    }
}

/** The picture chosen for a playlist, else up to 4 covers of its tracks. */
@Composable
fun PlaylistCover(id: Long, cover: String?, updatedAt: Long, size: androidx.compose.ui.unit.Dp) {
    val ui = LocalUi.current
    if (cover != null) {
        app.chudmusic.ui.components.Cover(cover, size = size, shape = RoundedCornerShape(Radii.m), placeholder = Icons.Rounded.LibraryMusic)
        return
    }
    val covers by remember(id, updatedAt) { kotlinx.coroutines.flow.flow { emit(ui.g.db.playlists().covers(id)) } }.collectAsState(initial = emptyList())
    Mosaic(covers, size)
}

@Composable
fun Mosaic(covers: List<String>, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(Radii.m)
    if (covers.size < 4) {
        app.chudmusic.ui.components.Cover(covers.firstOrNull(), size = size, shape = shape, placeholder = Icons.Rounded.LibraryMusic)
    } else {
        Column(Modifier.size(size).clip(shape)) {
            for (r in 0..1) Row {
                for (k in 0..1) app.chudmusic.ui.components.Cover(covers[r * 2 + k], size = size / 2, shape = RoundedCornerShape(0.dp))
            }
        }
    }
}
