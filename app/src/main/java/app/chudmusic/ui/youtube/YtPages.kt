package app.chudmusic.ui.youtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.ErrorBox
import app.chudmusic.ui.components.Load
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.Tile
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.components.rememberLoader
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.library.CollectionHeader
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.tracksCount
import app.chudmusic.youtube.HiddenArtist
import app.chudmusic.youtube.YouTubeException
import app.chudmusic.youtube.YtMix

@Composable
fun YtAlbumScreen(id: String) {
    val ui = LocalUi.current
    val loader = rememberLoader("yt-album:$id") { ui.g.youtube.album(id) }
    val current by ui.g.player.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        when (val s = loader.state) {
            Load.Loading -> { BackBar("", ui::back); Loading() }
            is Load.Err -> { BackBar("", ui::back); ErrorBox(stringResource(R.string.yt_error, s.message), loader::reload) }
            is Load.Ok -> {
                val a = s.value
                BackBar(a.title, ui::back)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        CollectionHeader(
                            a.title, a.artist,
                            listOfNotNull(a.kind, a.year, tracksCount(a.tracks.size), formatLong(a.tracks.sumOf { it.durationMs ?: 0 })).joinToString(" · "),
                            a.coverUrl, a.tracks,
                            onSubtitle = a.artistId?.let { aid -> { ui.navigate(Routes.YtArtist(aid)) } },
                        )
                    }
                    itemsIndexed(a.tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
                        TrackRow(t, onClick = { ui.play(a.tracks, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id, number = i + 1,
                            subtitle = t.artist ?: a.artist ?: "")
                    }
                }
            }
        }
    }
}

@Composable
fun YtArtistScreen(id: String) {
    val ui = LocalUi.current
    val c = Chud.colors
    val loader = rememberLoader("yt-artist:$id") { ui.g.youtube.artist(id) }
    val current by ui.g.player.state.collectAsState()
    val hidden by ui.g.hiddenArtists.collectAsState()
    Column(Modifier.fillMaxSize()) {
        when (val s = loader.state) {
            Load.Loading -> { BackBar("", ui::back); Loading() }
            is Load.Err -> { BackBar("", ui::back); ErrorBox(stringResource(R.string.yt_error, s.message), loader::reload) }
            is Load.Ok -> {
                val a = remember(s.value, hidden) { hidden.keep(s.value) }
                val isHidden = hidden.hides(a.id, a.name)
                val me = HiddenArtist(a.id, a.name)
                BackBar(a.name, ui::back, actions = {
                    // Hide this artist from YouTube lists, or restore it when it already is.
                    IconButton(onClick = { if (isHidden) ui.restoreArtist(me) else ui.hideArtist(me) }) {
                        Icon(
                            if (isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            stringResource(if (isHidden) R.string.yt_restore_artist else R.string.yt_hide_artist), tint = c.text1,
                        )
                    }
                })
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        CollectionHeader(a.name, null, a.description?.let { d -> if (d.length > 160) d.take(160) + "…" else d }, a.coverUrl, a.topSongs,
                            round = true, placeholder = Icons.Rounded.Person,
                            extra = {
                                a.topSongs.firstOrNull()?.let { first ->
                                    IconButton(onClick = { ui.startMix(first) }) { Icon(Icons.Rounded.Waves, stringResource(R.string.menu_start_mix), tint = c.text1) }
                                }
                            })
                    }
                    if (a.topSongs.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.yt_top_songs)) }
                        itemsIndexed(a.topSongs, key = { i, t -> "top:${t.id}#$i" }) { i, t ->
                            TrackRow(t, onClick = { ui.play(a.topSongs, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id)
                        }
                    }
                    a.shelves.forEachIndexed { si, shelf ->
                        item(key = "shelf:$si") { SectionTitle(shelf.title) }
                        if (shelf.albums.isNotEmpty()) item(key = "albums:$si") { AlbumRow(shelf.albums) }
                        if (shelf.artists.isNotEmpty()) item(key = "artists:$si") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(shelf.artists, key = { it.id }) { ar ->
                                    Tile(ar.name, ar.subtitle, ar.coverUrl, onClick = { ui.navigate(Routes.YtArtist(ar.id)) }, size = 120.dp, round = true)
                                }
                            }
                        }
                        if (shelf.songs.isNotEmpty()) itemsIndexed(shelf.songs, key = { i, t -> "s$si:${t.id}#$i" }) { i, t ->
                            TrackRow(t, onClick = { ui.play(shelf.songs, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Démarrer le mix": the radio YouTube Music builds from a song. For a phone or network file,
 * the song is first matched on YouTube Music (title, artist, duration), like on the PC.
 */
@Composable
fun MixScreen(r: Routes.Mix) {
    val ui = LocalUi.current
    val c = Chud.colors
    val current by ui.g.player.state.collectAsState()
    val loader = rememberLoader("mix:${r.videoId}:${r.title}:${r.artist}") {
        val vid = r.videoId ?: ui.g.youtube.match(r.title, r.artist, r.durationMs)?.videoId
            ?: throw YouTubeException(ui.str(R.string.mix_no_match, r.title))
        ui.g.youtube.mix(vid)
    }
    val hidden by ui.g.hiddenArtists.collectAsState()
    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.mix_title), ui::back, actions = {
            IconButton(onClick = loader::reload) { Icon(Icons.Rounded.Refresh, stringResource(R.string.mix_new), tint = c.text1) }
        })
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorBox(s.message, loader::reload)
            is Load.Ok -> MixList(remember(s.value, hidden) { hidden.keep(s.value) }, r.title, current.track?.id)
        }
    }
}

@Composable
private fun MixList(mix: YtMix, title: String, playingId: String?) {
    val ui = LocalUi.current
    val tracks = mix.tracks
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            CollectionHeader(
                stringResource(R.string.mix_kicker, mix.seed?.title ?: title), mix.seed?.artist,
                listOf(tracksCount(tracks.size), formatLong(tracks.sumOf { it.durationMs ?: 0 })).joinToString(" · "),
                mix.seed?.coverUri ?: tracks.firstOrNull()?.coverUri, tracks, placeholder = Icons.Rounded.Waves,
            )
            Text(stringResource(R.string.mix_note), style = MaterialTheme.typography.bodySmall, color = Chud.colors.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
            TrackRow(t, onClick = { ui.play(tracks, i) }, onMore = { ui.openMenu(t) }, playing = playingId == t.id)
        }
    }
}
