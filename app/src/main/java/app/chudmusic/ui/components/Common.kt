package app.chudmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.ui.formatTime
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.Radii
import coil3.compose.SubcomposeAsyncImage

/** A cover, or a quiet placeholder with the right symbol when there is none. */
@Composable
fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape = RoundedCornerShape(Radii.s),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    val c = Chud.colors
    Box(modifier.size(size).clip(shape).background(c.bg3), contentAlignment = Alignment.Center) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                error = { Icon(placeholder, null, tint = c.text2, modifier = Modifier.size(size * 0.45f)) },
                loading = { },
            )
        } else {
            Icon(placeholder, null, tint = c.text2, modifier = Modifier.size(size * 0.45f))
        }
    }
}

/** A small tag telling where a track comes from (the PC shows the same: phone, network, YouTube). */
@Composable
fun SourceBadge(t: Track, modifier: Modifier = Modifier) {
    val c = Chud.colors
    val (icon, label) = when (t.source) {
        Source.LOCAL -> return
        Source.NETWORK -> Icons.Rounded.Lan to null
        Source.YOUTUBE -> null to "YT"
        Source.RADIO -> Icons.Rounded.Radio to null
    }
    if (icon != null) Icon(icon, null, tint = c.text2, modifier = modifier.size(14.dp))
    else Text(
        label!!, style = MaterialTheme.typography.labelSmall, color = c.text1,
        modifier = modifier.border(1.dp, c.lineStrong, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
    )
}

/**
 * One track in a list. Tap = play from here (the whole list becomes the queue);
 * long press or ⋮ = actions.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    number: Int? = null,
    showCover: Boolean = true,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Chud.colors
    val dim = !track.available
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(enabled = !dim || onMore != null, onClick = { if (!dim) onClick() }, onLongClick = onMore)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                number.toString(), style = MaterialTheme.typography.bodySmall, color = if (playing) c.accent else c.text2,
                textAlign = TextAlign.End, modifier = Modifier.width(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        } else if (showCover) {
            Cover(track.coverUri, size = 48.dp, placeholder = if (track.isRadio) Icons.Rounded.Radio else Icons.Rounded.MusicNote)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = when { dim -> c.text2; playing -> c.accent; else -> c.text0 },
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceBadge(track)
                if (dim) Icon(Icons.Rounded.CloudOff, null, tint = c.danger, modifier = Modifier.size(14.dp))
                Text(
                    if (dim) stringResource(R.string.track_missing) else subtitle ?: listOfNotNull(track.artist ?: stringResource(R.string.unknown_artist), track.album).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (dim) c.danger else c.text1,
                )
            }
        }
        if (trailing != null) trailing() else if (track.durationMs != null && !track.isRadio) {
            Text(formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(start = 8.dp))
        }
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_actions), tint = c.text1) }
        } else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Chud.colors.text0, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Chud.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text0, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text1, textAlign = TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Chud.colors.accent) }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Chud.colors
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, tint = c.text2, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, color = c.text1, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** Top bar of the inner pages, with a back arrow. */
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Chud.colors.bg0, scrolledContainerColor = Chud.colors.bg1),
    )
}

/** Album / artist tile for horizontal rows and grids. */
@Composable
fun Tile(
    title: String,
    subtitle: String?,
    cover: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
    round: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Chud.colors
    Column(modifier.width(size).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Cover(
            cover, size = size, shape = if (round) CircleShape else RoundedCornerShape(Radii.m),
            placeholder = if (round) Icons.Rounded.Person else Icons.Rounded.Album,
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
    }
}

/** A selectable pill (filters). */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Chud.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.accentInk else c.text0,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.bg3)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

val ListPadding = PaddingValues(bottom = 24.dp)
