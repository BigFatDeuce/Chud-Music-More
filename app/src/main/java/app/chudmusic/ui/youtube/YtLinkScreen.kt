package app.chudmusic.ui.youtube

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.ErrorBox
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.library.CollectionHeader
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.tracksCount
import app.chudmusic.youtube.LinkImport
import app.chudmusic.youtube.MusicLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A YouTube or Spotify link as tracks on YouTube Music (the PC app's link view). With [into], the
 * tracks found go into that playlist by themselves once every song is settled.
 */
@Composable
fun YtLinkScreen(url: String, into: Long) {
    val ui = LocalUi.current
    val c = Chud.colors
    val link = remember(url) { MusicLink.find(url) }
    if (link == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("", ui::back)
            ErrorBox(stringResource(R.string.share_not_youtube), null)
        }
        return
    }
    val flow = remember(link, into) { ui.g.links.open(link, into) }
    val state by flow.collectAsState()
    val target by remember(into) { ui.g.db.playlists().flow(into) }.collectAsState(initial = null)
    val current by ui.g.player.state.collectAsState()

    // An album or an artist has its own page: it replaces this one.
    LaunchedEffect(state) {
        val s = state as? LinkImport.Elsewhere ?: return@LaunchedEffect
        ui.back()
        when (val t = s.target) {
            is LinkImport.Target.YtAlbum -> ui.navigate(Routes.YtAlbum(t.id))
            is LinkImport.Target.YtArtist -> ui.navigate(Routes.YtArtist(t.id))
        }
    }

    Column(Modifier.fillMaxSize()) {
        when (val s = state) {
            LinkImport.Reading, is LinkImport.Elsewhere -> {
                BackBar(stringResource(R.string.link_reading), ui::back)
                Loading()
            }
            is LinkImport.Failed -> {
                BackBar(stringResource(R.string.link_failed), ui::back)
                ErrorBox(failureText(s), onRetry = { ui.g.links.open(link, into) })
            }
            is LinkImport.Ready -> {
                val title = s.title ?: stringResource(R.string.link_yt_playlist)
                BackBar(title, ui::back)
                val found = s.found
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        val source = if (s.source == LinkImport.Origin.SPOTIFY) "Spotify" else "YouTube"
                        val kind = stringResource(
                            when (s.kind) { LinkImport.Kind.TRACK -> R.string.link_kind_track; LinkImport.Kind.ALBUM -> R.string.link_kind_album; LinkImport.Kind.PLAYLIST -> R.string.link_kind_playlist },
                            source,
                        )
                        val count = if (s.source == LinkImport.Origin.SPOTIFY) stringResource(R.string.link_found_of, found.size, s.slots.size) else tracksCount(found.size)
                        CollectionHeader(
                            title, s.subtitle,
                            listOfNotNull(kind, count, formatLong(found.sumOf { it.durationMs ?: 0 }).takeIf { found.isNotEmpty() }).joinToString(" · "),
                            found.firstNotNullOfOrNull { it.coverUri }, found, placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
                        )
                        if (!s.done) {
                            val total = s.slots.size
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                                LinearProgressIndicator(
                                    progress = { (total - s.pending).toFloat() / total }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg3,
                                )
                                Text(stringResource(R.string.link_matching, total - s.pending, total), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        Tail(s, into, target?.name)
                        if (s.truncated) Text(
                            stringResource(if (s.source == LinkImport.Origin.SPOTIFY) R.string.link_truncated_spotify else R.string.link_truncated, s.slots.size),
                            style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    itemsIndexed(found, key = { i, t -> "${t.id}#$i" }) { i, t ->
                        TrackRow(t, onClick = { ui.play(found, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id)
                    }
                    val missing = s.missing
                    if (missing.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.link_missing, missing.size)) }
                        item {
                            Text(stringResource(R.string.link_missing_text), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                        }
                        items(missing) { w ->
                            Row(
                                Modifier.fillMaxWidth().clickable { ui.searchYouTube(listOfNotNull(w.title, w.artists.firstOrNull()).joinToString(" ")) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.SearchOff, null, tint = c.text2)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(w.title, style = MaterialTheme.typography.bodyLarge, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(w.artists.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Under the header: "Save as playlist", or where the tracks go when filling a playlist. */
@Composable
private fun Tail(s: LinkImport.Ready, into: Long, targetName: String?) {
    val ui = LocalUi.current
    val c = Chud.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (into == 0L) {
            FilledTonalButton(
                enabled = s.done && s.found.isNotEmpty(),
                onClick = {
                    val name = s.title ?: ui.str(R.string.link_yt_playlist)
                    ui.scope.launch {
                        val id = withContext(Dispatchers.IO) { ui.g.playlists.create(name, s.found) }
                        ui.snack(ui.str(R.string.pl_created, name))
                        ui.navigate(Routes.Playlist(id))
                    }
                },
            ) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.link_save)) }
        } else {
            val name = targetName ?: ""
            val added = s.added
            Text(
                if (added == null) stringResource(R.string.link_into, name) else pluralStringResource(R.plurals.pl_added, added, added, name),
                style = MaterialTheme.typography.bodyMedium, color = if (added == null) c.text1 else c.accent, textAlign = TextAlign.Center,
            )
            if (added != null) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = ui::back) { Text(stringResource(R.string.link_open_playlist)) }
            }
        }
    }
}

@Composable
private fun failureText(f: LinkImport.Failed): String = when (f.reason) {
    LinkImport.Failure.OFFLINE -> stringResource(R.string.link_err_offline)
    LinkImport.Failure.NOT_FOUND -> stringResource(R.string.link_err_not_found)
    LinkImport.Failure.EMPTY -> stringResource(R.string.link_err_empty)
    LinkImport.Failure.SPOTIFY_REFUSED -> stringResource(R.string.link_err_spotify_refused, f.detail ?: "?")
    LinkImport.Failure.SPOTIFY_UNREADABLE -> stringResource(R.string.link_err_spotify_unreadable)
    LinkImport.Failure.ARTIST_NOT_FOUND -> stringResource(R.string.link_err_artist, f.detail ?: "?")
    LinkImport.Failure.OTHER -> stringResource(R.string.link_err_other, f.detail ?: "?")
}
