package app.chudmusic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import app.chudmusic.ui.ChudRoot
import app.chudmusic.ui.Incoming
import app.chudmusic.ui.Permissions
import app.chudmusic.ui.theme.ChudTheme
import app.chudmusic.ui.theme.Nocturne
import app.chudmusic.ui.theme.Platine
import app.chudmusic.ui.theme.Skin
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        granted[Permissions.audio]?.let { ok ->
            graph.audio.onResult(ok, shouldShowRequestPermissionRationale(Permissions.audio))
            if (ok) {
                graph.scanner.watchLocal()
                graph.scanner.scanAll()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = graph
        g.player.connect()
        setContent {
            val skin by g.settings.skin.collectAsState(initial = g.initialSkin)
            val dark = when (skin) { Skin.NOCTURNE -> true; Skin.PLATINE -> false; null -> androidx.compose.foundation.isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                val bar = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bar, bar)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (dark) Nocturne.bg0.toArgb() else Platine.bg0.toArgb()))
            }
            ChudTheme(skin) { ChudRoot() }
        }
        if (savedInstanceState == null) {
            handle(intent)
            val missing = listOfNotNull(
                Permissions.audio.takeIf { !Permissions.hasAudio(this) },
                "android.permission.POST_NOTIFICATIONS".takeIf { !Permissions.hasNotifications(this) },
            )
            if (missing.isNotEmpty()) { g.audio.onAsk(); askPermissions.launch(missing.toTypedArray()) }
            if (Permissions.hasAudio(this)) g.scanner.watchLocal()
            g.scanner.scanAll()
            g.updater.autoCheck()
            lifecycleScope.launch { g.scanner.schedulePeriodic() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Allowed meanwhile in Android's settings: start reading the phone's music.
        val before = graph.audio.access.value
        graph.audio.refresh()
        if (before != app.chudmusic.ui.AudioAccess.GRANTED && graph.audio.access.value == app.chudmusic.ui.AudioAccess.GRANTED) {
            graph.scanner.watchLocal()
            graph.scanner.scanAll()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A playlist file opened from another app, or a YouTube link shared to Chud Music. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { Incoming.send(Incoming.Item.PlaylistFile(it)) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Incoming.send(Incoming.Item.SharedText(it)) }
        }
    }
}

