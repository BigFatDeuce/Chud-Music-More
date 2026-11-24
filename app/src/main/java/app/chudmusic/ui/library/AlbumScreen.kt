package app.chudmusic.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.MoreVert
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.model.Track
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.Tile
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.Radii
import app.chudmusic.ui.tracksCount

/** The head of an album, artist, playlist or mix page: cover, names, Lire / Aléatoire. */
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String?,
    info: String?,
    cover: String?,
    tracks: List<Track>,
    round: Boolean = false,
    placeholder: ImageVector = Icons.Rounded.Album,
    onSubtitle: (() -> Unit)? = null,
    onCoverLongClick: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val ui = LocalUi.current
    val c = Chud.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(
            cover, size = 220.dp, shape = if (round) CircleShape else RoundedCornerShape(Radii.l), placeholder = placeholder,
            modifier = if (onCoverLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onCoverLongClick) else Modifier,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text0, textAlign = TextAlign.Center)
        if (subtitle != null) {
            if (onSubtitle != null) androidx.compose.material3.TextButton(onClick = onSubtitle) { Text(subtitle, style = MaterialTheme.typography.titleMedium, color = c.accent) }
            else Text(subtitle, style = MaterialTheme.typography.titleMedium, color = c.text1, textAlign = TextAlign.Center)
        }
        if (info != null) Text(info, style = MaterialTheme.typography.bodySmall, color = c.text2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { ui.play(tracks, tracks.indexOfFirst { it.available }.coerceAtLeast(0)) }, enabled = tracks.any { it.available },
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play)) }
            FilledTonalButton(onClick = { ui.shuffle(tracks) }, enabled = tracks.any { it.available }) {
                Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.player_shuffle_short))
            }
            IconButton(onClick = { ui.addTo = tracks }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.menu_add_to_playlist), tint = c.text1)
            }
            extra?.invoke()
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun AlbumScreen(key: String) {
    val ui = LocalUi.current
    val lib by ui.g.library.state.collectAsState()
    val current by ui.g.player.state.collectAsState()
    val album = lib.albumByKey[key]
    val tracks by androidx.compose.runtime.remember(key) {
        ui.g.db.tracks().byAlbumFlow(key).map { l -> l.map { it.toTrack() }.sortedWith(app.chudmusic.library.Library.albumOrder) }
    }.collectAsState(initial = emptyList())
    val menuState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var menu by menuState
    Column(Modifier.fillMaxSize()) {
        BackBar(album?.title ?: "", onBack = ui::back, actions = {
            if (album != null && (album.cover == null || ui.g.onlineCovers.isOnline(album))) {
                androidx.compose.material3.IconButton(onClick = { menu = true }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, stringResource(R.string.more_actions))
                }
                androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (album.cover == null) androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_find_cover)) },
                        onClick = {
                            menu = false
                            ui.scope.launch {
                                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ui.g.onlineCovers.findOne(album) } }
                                ui.snack(r.fold({ ui.str(if (it) R.string.album_cover_found else R.string.album_cover_none) }, { ui.str(R.string.set_covers_failed, it.message ?: "") }))
                            }
                        },
                    ) else androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_drop_cover)) },
                        onClick = {
                            menu = false
                            ui.scope.launch(kotlinx.coroutines.Dispatchers.IO) { ui.g.onlineCovers.drop(album) }
                        },
                    )
                }
            }
        })
        if (album == null) {
            EmptyState(Icons.Rounded.Album, stringResource(R.string.album_gone), null)
            return@Column
        }
        val multiDisc = tracks.mapNotNull { it.discNo }.distinct().size > 1
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                CollectionHeader(
                    album.title, album.artist, listOfNotNull(album.year?.toString(), tracksCount(tracks.size), formatLong(album.durationMs)).joinToString(" · "),
                    album.cover, tracks,
                    onSubtitle = album.artist?.let { a -> { app.chudmusic.library.Library.mainArtist(a)?.let { ui.navigate(Routes.Artist(it)) } } },
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                if (multiDisc && (i == 0 || tracks[i - 1].discNo != t.discNo)) {
                    Text(stringResource(R.string.album_disc, t.discNo ?: 1), style = MaterialTheme.typography.labelLarge, color = Chud.colors.text2,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                }
                TrackRow(
                    t, onClick = { ui.play(tracks, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id,
                    number = t.trackNo ?: (i + 1),
                    subtitle = t.artist?.takeIf { it != album.artist } ?: app.chudmusic.ui.formatTime(t.durationMs),
                )
            }
        }
    }
}

@Composable
fun ArtistScreen(name: String) {
    val ui = LocalUi.current
    val c = Chud.colors
    val lib by ui.g.library.state.collectAsState()
    val current by ui.g.player.state.collectAsState()
    val artist = lib.artistByName[name]
    val tracks by androidx.compose.runtime.remember(name) {
        ui.g.db.tracks().byArtistFlow(name).map { l -> l.map { it.toTrack() }.sortedWith(app.chudmusic.library.Library.artistOrder) }
    }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        BackBar(name, onBack = ui::back)
        if (artist == null) {
            EmptyState(Icons.Rounded.Person, stringResource(R.string.artist_gone), null)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                CollectionHeader(
                    artist.name, null, listOf(androidx.compose.ui.res.pluralStringResource(R.plurals.n_albums, artist.albums.size, artist.albums.size), tracksCount(artist.count)).joinToString(" · "),
                    artist.cover, tracks, round = true, placeholder = Icons.Rounded.Person,
                )
            }
            item {
                androidx.compose.material3.TextButton(onClick = { ui.openYtArtist(artist.name) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.artist_yt_page), color = c.accent)
                }
            }
            if (artist.albums.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.lib_albums)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artist.albums.sortedByDescending { it.year ?: 0 }, key = { it.key }) { a ->
                            Tile(a.title, a.year?.toString(), a.cover, onClick = { ui.navigate(Routes.Album(a.key)) }, size = 140.dp)
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.lib_tracks)) }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, onClick = { ui.play(tracks, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id)
            }
        }
    }
}
