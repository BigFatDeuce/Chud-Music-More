package app.chudmusic.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.data.CountRow
import app.chudmusic.model.Source
import app.chudmusic.stats.Stats
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.ErrorBox
import app.chudmusic.ui.components.Load
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.rememberLoader
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.LcdFamily
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun StatsScreen() {
    val ui = LocalUi.current
    var period by rememberSaveable { mutableStateOf(Stats.Period.MONTH) }
    // A new loader per period (kept while the page lives): switching back and forth is instant.
    val loader = rememberLoader("stats:${period.name}") { ui.g.stats.summary(period) }
    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.section_stats), ui::back)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Stats.Period.entries) { p ->
                Pill(stringResource(when (p) {
                    Stats.Period.WEEK -> R.string.stats_week; Stats.Period.MONTH -> R.string.stats_month
                    Stats.Period.YEAR -> R.string.stats_year; Stats.Period.ALL -> R.string.stats_all
                }), period == p, onClick = { period = p })
            }
        }
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorBox(s.message, loader::reload)
            is Load.Ok -> if (s.value.seconds < 30) EmptyState(Icons.Rounded.Insights, stringResource(R.string.stats_empty), stringResource(R.string.stats_empty_text))
            else Content(s.value)
        }
    }
}

@Composable
private fun Content(s: Stats.Summary) {
    val c = Chud.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(c.lcdBg).padding(16.dp)) {
                Text(stringResource(R.string.stats_listened).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.lcdFg.copy(alpha = 0.7f))
                Text(formatLong(s.seconds * 1000), fontFamily = LcdFamily, style = MaterialTheme.typography.displaySmall, color = c.lcdFg)
                Text(stringResource(R.string.stats_per_day, formatLong(s.avgPerDay * 1000)), fontFamily = LcdFamily, style = MaterialTheme.typography.titleLarge, color = c.lcdFg.copy(alpha = 0.8f))
            }
        }
        item {
            val day = s.biggestDay?.let { (d, sec) ->
                LocalDate.parse(d).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) + " · " + formatLong(sec * 1000)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.stats_plays), s.plays.toString(), Modifier.weight(1f))
                    Tile(stringResource(R.string.stats_distinct), s.distinct.toString(), Modifier.weight(1f))
                    Tile(stringResource(R.string.stats_discoveries), s.discoveries.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.stats_days), s.listeningDays.toString(), Modifier.weight(1f))
                    Tile(stringResource(R.string.stats_streak), s.streak.toString(), Modifier.weight(1f))
                    Tile(stringResource(R.string.stats_best_streak), s.bestStreak.toString(), Modifier.weight(1f))
                }
                if (day != null) Text(stringResource(R.string.stats_biggest_day, day), style = MaterialTheme.typography.bodySmall, color = c.text1)
            }
        }
        if (s.bySource.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.stats_sources)) }
            item { SourceBar(s.bySource) }
        }
        item { SectionTitle(stringResource(R.string.stats_by_hour)) }
        item { Bars(s.byHour, (0..23).map { if (it % 6 == 0) "${it}h" else "" }) }
        item { SectionTitle(stringResource(R.string.stats_by_weekday)) }
        item { Bars(s.byWeekday, stringArrayResource(R.array.weekdays_short).toList()) }
        top(R.string.stats_top_tracks, s.topTracks)
        top(R.string.stats_top_artists, s.topArtists)
        top(R.string.stats_top_albums, s.topAlbums)
        top(R.string.stats_top_radios, s.topRadios)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.top(title: Int, rows: List<CountRow>) {
    if (rows.isEmpty()) return
    item { SectionTitle(stringResource(title)) }
    items(rows.size) { i ->
        val r = rows[i]
        val c = Chud.colors
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = c.accent, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(r.label ?: r.k ?: "?", style = MaterialTheme.typography.bodyLarge, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.artist != null) Text(r.artist, style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(formatLong(r.seconds * 1000), style = MaterialTheme.typography.bodySmall, color = c.text1)
                if (r.plays > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.stats_n_plays, r.plays, r.plays), style = MaterialTheme.typography.bodySmall, color = c.text2)
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    val c = Chud.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = c.text0)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 2)
    }
}

@Composable
private fun SourceBar(by: Map<Source, Long>) {
    val c = Chud.colors
    val total = by.values.sum().coerceAtLeast(1)
    val colors = mapOf(Source.LOCAL to c.accent, Source.NETWORK to c.accent2, Source.YOUTUBE to c.danger, Source.RADIO to c.text1)
    val labels = mapOf(
        Source.LOCAL to stringResource(R.string.src_phone), Source.NETWORK to stringResource(R.string.src_network),
        Source.YOUTUBE to stringResource(R.string.src_youtube), Source.RADIO to stringResource(R.string.nav_radio),
    )
    Column(Modifier.padding(horizontal = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            var x = 0f
            for (src in Source.entries) {
                val w = size.width * (by[src] ?: 0) / total
                if (w > 0) drawRect(colors.getValue(src), Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
        Spacer(Modifier.height(8.dp))
        Source.entries.filter { (by[it] ?: 0) > 0 }.forEach { src ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Canvas(Modifier.width(10.dp).height(10.dp)) { drawCircle(colors.getValue(src)) }
                Spacer(Modifier.width(8.dp))
                Text(labels.getValue(src), style = MaterialTheme.typography.bodySmall, color = c.text0, modifier = Modifier.weight(1f))
                Text("${(by[src] ?: 0) * 100 / total} % · " + formatLong((by[src] ?: 0) * 1000), style = MaterialTheme.typography.bodySmall, color = c.text1)
            }
        }
    }
}

@Composable
private fun Bars(values: LongArray, labels: List<String>) {
    val c = Chud.colors
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                drawRoundRect(c.bg3, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(3f))
                if (h > 0) drawRoundRect(c.accent, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(3f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l -> Text(l, style = MaterialTheme.typography.labelSmall, color = c.text2, modifier = Modifier.weight(1f), maxLines = 1) }
        }
    }
}
