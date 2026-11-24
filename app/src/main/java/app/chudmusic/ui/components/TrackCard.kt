package app.chudmusic.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.data.TrackHistoryRow
import app.chudmusic.library.Library
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.theme.Chud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Pages about an artist (the PC app's track card links). */
object ArtistLinks {
    data class Link(val label: String, val url: String)

    fun of(artist: String, lang: String): List<Link> {
        val name = artist.trim()
        val q = URLEncoder.encode(name, "UTF-8")
        val wiki = lang.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        return listOf(
            Link("Wikipedia", "https://$wiki.wikipedia.org/w/index.php?search=$q"),
            Link("Grokipedia", "https://grokipedia.com/search?q=$q"),
            Link("Last.fm", "https://www.last.fm/music/$q"),
            Link("Discogs", "https://www.discogs.com/search/?q=$q&type=artist"),
        )
    }

    /** "FLAC · 912 kb/s" from what the library knows; null when it knows nothing. */
    fun format(t: Track): String? {
        if (t.source == Source.YOUTUBE || t.source == Source.RADIO) return null
        val ext = (t.path ?: "").substringAfterLast('.', "").uppercase(Locale.ROOT).takeIf { it.length in 2..5 }
        val kbps = if (t.sizeBytes != null && t.durationMs != null && t.durationMs > 0) t.sizeBytes * 8 / t.durationMs else null
        return listOfNotNull(ext, kbps?.let { "$it kb/s" }).joinToString(" · ").ifEmpty { null }
    }
}

/** Details of a track, what the user listened of it, and where to learn more about its artist. */
@Composable
fun TrackCardSheet(track: Track, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    var history by remember(track.id) { mutableStateOf<TrackHistoryRow?>(null) }
    var seconds by remember(track.id) { mutableStateOf(0L) }
    LaunchedEffect(track.id) {
        withContext(Dispatchers.IO) {
            runCatching { history = ui.g.db.stats().trackHistory(track.id); seconds = ui.g.db.stats().trackSeconds(track.id) }
        }
    }
    val sources by ui.g.db.sources().flow().collectAsState(initial = emptyList())
    val artist = Library.mainArtist(track.artist?.replace(Regex("""\s-\s*Topic$|VEVO$""", RegexOption.IGNORE_CASE), "") ?: track.albumArtist)
    fun open(url: String) = runCatching { ui.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun date(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg2) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.coverUri, size = 72.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = c.text0, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    track.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text1) }
                }
            }
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
            val where = when (track.source) {
                Source.LOCAL -> stringResource(R.string.src_phone)
                Source.NETWORK -> sources.firstOrNull { it.id == track.sourceId }?.name ?: stringResource(R.string.card_network)
                Source.YOUTUBE -> stringResource(R.string.nav_youtube)
                Source.RADIO -> stringResource(R.string.nav_radio)
            }
            Fact(stringResource(R.string.card_album), track.album)
            Fact(stringResource(R.string.card_year), track.year?.toString())
            Fact(stringResource(R.string.card_genre), track.genre)
            Fact(stringResource(R.string.card_duration), track.durationMs?.let { app.chudmusic.ui.formatTime(it) })
            Fact(stringResource(R.string.card_format), ArtistLinks.format(track))
            Fact(stringResource(R.string.card_source), where)
            Fact(stringResource(R.string.card_path), track.path?.takeIf { track.source != Source.YOUTUBE })

            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
            Text(stringResource(R.string.card_history), style = MaterialTheme.typography.labelLarge, color = c.text2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            val h = history
            if (h == null || h.plays == 0) {
                Text(stringResource(R.string.card_never), style = MaterialTheme.typography.bodyMedium, color = c.text1, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            } else {
                Fact(stringResource(R.string.card_plays), pluralStringResource(R.plurals.card_plays_n, h.plays, h.plays))
                Fact(stringResource(R.string.card_listened), app.chudmusic.ui.formatLong(seconds * 1000).takeIf { seconds > 0 })
                Fact(stringResource(R.string.card_first), h.firstAt?.let(::date))
                Fact(stringResource(R.string.card_last), h.lastAt?.let(::date))
            }

            if (artist != null) {
                HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
                Text(stringResource(R.string.card_about, artist), style = MaterialTheme.typography.labelLarge, color = c.text2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                LinkRow(Icons.Rounded.Person, stringResource(R.string.card_on_ytm)) {
                    onDismiss()
                    if (track.isYoutube) ui.goArtist(track) else ui.openYtArtist(artist)
                }
                ArtistLinks.of(artist, Locale.getDefault().language).forEach { l ->
                    LinkRow(Icons.AutoMirrored.Rounded.OpenInNew, l.label) { open(l.url) }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val c = Chud.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.text0, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val c = Chud.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.text1)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.text0)
    }
}
