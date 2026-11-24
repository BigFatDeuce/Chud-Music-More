package app.chudmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.chudmusic.R

/** 3:07, 1:02:45 */
fun formatTime(ms: Long?): String {
    if (ms == null || ms < 0) return "–:––"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "52 min", "3 h 05" */
@Composable
fun formatLong(ms: Long): String {
    val min = ms / 60_000
    return if (min < 60) stringResource(R.string.fmt_min, min) else stringResource(R.string.fmt_hmin, min / 60, "%02d".format(min % 60))
}

@Composable
fun tracksCount(n: Int) = pluralStringResource(R.plurals.n_tracks, n, n)
