package app.chudmusic.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.chudmusic.ui.theme.Skin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.store by preferencesDataStore("settings")

@Serializable
data class EqState(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bandsDb: List<Float> = List(10) { 0f },
    val preset: String? = "0",
)

@Serializable
data class EqPreset(val name: String, val preampDb: Float, val bandsDb: List<Float>)

/** User preferences (DataStore). Not the library or playlists: those live in the database. */
class Settings(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object K {
        val skin = stringPreferencesKey("skin")
        val eq = stringPreferencesKey("eq")
        val eqUser = stringPreferencesKey("eq.user")
        val ytOpus = booleanPreferencesKey("yt.opus")
        val hideSmallAlbums = booleanPreferencesKey("lib.hideSmallAlbums")
        val netWifiOnly = booleanPreferencesKey("net.wifiOnly")
        val netAutoScan = booleanPreferencesKey("net.autoScan")
        val updAuto = booleanPreferencesKey("upd.auto")
        val updLastCheck = longPreferencesKey("upd.lastCheck")
        val updAvailable = stringPreferencesKey("upd.available")
        val queue = stringPreferencesKey("player.queue")
        val speed = floatPreferencesKey("player.speed")
        val radioCountry = stringPreferencesKey("radio.country")
        val lastExportDir = stringPreferencesKey("pl.exportDir")
        val scanUnfinished = stringSetPreferencesKey("scan.unfinished")
        val homeHidden = stringSetPreferencesKey("home.hidden")
        val ytHidden = stringPreferencesKey("yt.hiddenArtists")
        val homeHiddenAdded = stringSetPreferencesKey("home.hiddenAdded")
        val scanPaused = stringSetPreferencesKey("scan.paused")
        val xfSeconds = intPreferencesKey("player.crossfade")
        val xfKeepAlbums = booleanPreferencesKey("player.crossfadeKeepAlbums")
        val gainMode = stringPreferencesKey("player.gain")
        val smartSeeded = booleanPreferencesKey("smart.seeded")
    }

    private fun <T> flow(read: (Preferences) -> T): Flow<T> = context.store.data.map(read)
    private suspend fun set(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) { context.store.edit(block) }

    /** null = follow the system (dark → Nocturne, light → Platine). */
    val skin: Flow<Skin?> = flow { p -> p[K.skin]?.let { runCatching { Skin.valueOf(it) }.getOrNull() } }
    suspend fun setSkin(s: Skin?) = set { if (s == null) it.remove(K.skin) else it[K.skin] = s.name }

    val eq: Flow<EqState> = flow { p -> p[K.eq]?.let { runCatching { json.decodeFromString<EqState>(it) }.getOrNull() } ?: EqState() }
    suspend fun setEq(e: EqState) = set { it[K.eq] = json.encodeToString(EqState.serializer(), e) }
    suspend fun eqNow(): EqState = eq.first()

    val eqUserPresets: Flow<List<EqPreset>> = flow { p ->
        p[K.eqUser]?.let { runCatching { json.decodeFromString<List<EqPreset>>(it) }.getOrNull() } ?: emptyList()
    }
    suspend fun setEqUserPresets(l: List<EqPreset>) = set { it[K.eqUser] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(EqPreset.serializer()), l) }

    val ytPreferOpus: Flow<Boolean> = flow { it[K.ytOpus] ?: true }
    suspend fun setYtPreferOpus(v: Boolean) = set { it[K.ytOpus] = v }

    /** Sections of Home the user chose to show (all by default), see [HomeSection.shown]. */
    val homeSections: Flow<Set<app.chudmusic.ui.home.HomeSection>> = flow { app.chudmusic.ui.home.HomeSection.shown(it[K.homeHidden].orEmpty()) }
    suspend fun setHomeSection(s: app.chudmusic.ui.home.HomeSection, shown: Boolean) = set {
        val now = it[K.homeHidden].orEmpty()
        it[K.homeHidden] = if (shown) now - s.name else now + s.name
    }

    /** YouTube Music artists the user hid (Settings › YouTube Music lists them to restore them). */
    val hiddenArtists: Flow<app.chudmusic.youtube.HiddenArtists> = flow { p ->
        app.chudmusic.youtube.HiddenArtists(
            p[K.ytHidden]?.let { runCatching { json.decodeFromString<List<app.chudmusic.youtube.HiddenArtist>>(it) }.getOrNull() } ?: emptyList(),
        )
    }
    private suspend fun editHidden(change: (app.chudmusic.youtube.HiddenArtists) -> List<app.chudmusic.youtube.HiddenArtist>) = set {
        val now = it[K.ytHidden]?.let { s -> runCatching { json.decodeFromString<List<app.chudmusic.youtube.HiddenArtist>>(s) }.getOrNull() } ?: emptyList()
        it[K.ytHidden] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(app.chudmusic.youtube.HiddenArtist.serializer()), change(app.chudmusic.youtube.HiddenArtists(now)))
    }
    suspend fun hideArtist(a: app.chudmusic.youtube.HiddenArtist) = editHidden { it.plus(a) }
    suspend fun restoreArtist(a: app.chudmusic.youtube.HiddenArtist) = editHidden { it.minus(a) }

    /** Albums taken out of "Recently added" on Home ([app.chudmusic.library.Library.albumKey]). */
    val homeHiddenAdded: Flow<Set<String>> = flow { it[K.homeHiddenAdded].orEmpty() }
    suspend fun setHomeHiddenAdded(key: String, hidden: Boolean) = set {
        val now = it[K.homeHiddenAdded].orEmpty()
        it[K.homeHiddenAdded] = if (hidden) now + key else now - key
    }

    /** Network places the user paused: automatic scans skip them (see [app.chudmusic.library.Scanner.mustScan]). */
    val scanPaused: Flow<Set<Long>> = flow { p -> p[K.scanPaused].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }
    suspend fun setScanPaused(id: Long, paused: Boolean) = set {
        val now = it[K.scanPaused].orEmpty()
        it[K.scanPaused] = if (paused) now + id.toString() else now - id.toString()
    }

    val hideSmallAlbums: Flow<Boolean> = flow { it[K.hideSmallAlbums] ?: false }
    suspend fun setHideSmallAlbums(v: Boolean) = set { it[K.hideSmallAlbums] = v }

    val netWifiOnly: Flow<Boolean> = flow { it[K.netWifiOnly] ?: true }
    suspend fun setNetWifiOnly(v: Boolean) = set { it[K.netWifiOnly] = v }
    val netAutoScan: Flow<Boolean> = flow { it[K.netAutoScan] ?: true }
    suspend fun setNetAutoScan(v: Boolean) = set { it[K.netAutoScan] = v }

    val updAuto: Flow<Boolean> = flow { it[K.updAuto] ?: true }
    suspend fun setUpdAuto(v: Boolean) = set { it[K.updAuto] = v }
    val updLastCheck: Flow<Long> = flow { it[K.updLastCheck] ?: 0L }
    suspend fun setUpdLastCheck(v: Long) = set { it[K.updLastCheck] = v }
    /** The release found by the last check (JSON of Updater.Release), kept so a restart does not forget it. */
    suspend fun updAvailable(): String? = context.store.data.first()[K.updAvailable]
    suspend fun setUpdAvailable(v: String?) = set { if (v == null) it.remove(K.updAvailable) else it[K.updAvailable] = v }

    /** Network places whose asked-for scan has not ended yet (the app was closed meanwhile). */
    suspend fun scanUnfinished(): Set<Long> = context.store.data.first()[K.scanUnfinished].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    suspend fun setScanUnfinished(id: Long, unfinished: Boolean) = set {
        val now = it[K.scanUnfinished].orEmpty()
        it[K.scanUnfinished] = if (unfinished) now + id.toString() else now - id.toString()
    }

    /** The queue as versions up to 0.3.3 kept it; [app.chudmusic.playback.QueueStore] holds it now. */
    suspend fun oldQueueJson(): String? = context.store.data.first()[K.queue]
    suspend fun forgetOldQueue() = set { it.remove(K.queue) }

    val crossfade: Flow<app.chudmusic.audio.CrossfadeSettings> = flow {
        app.chudmusic.audio.CrossfadeSettings((it[K.xfSeconds] ?: 0).coerceIn(0, app.chudmusic.audio.CrossfadeSettings.MAX_SECONDS), it[K.xfKeepAlbums] ?: true)
    }
    suspend fun setCrossfade(s: app.chudmusic.audio.CrossfadeSettings) = set { it[K.xfSeconds] = s.seconds; it[K.xfKeepAlbums] = s.keepAlbums }

    val gainMode: Flow<app.chudmusic.audio.ReplayGain.Mode> = flow { p ->
        p[K.gainMode]?.let { runCatching { app.chudmusic.audio.ReplayGain.Mode.valueOf(it) }.getOrNull() } ?: app.chudmusic.audio.ReplayGain.Mode.OFF
    }
    suspend fun setGainMode(m: app.chudmusic.audio.ReplayGain.Mode) = set { it[K.gainMode] = m.name }

    /** The ready-made smart playlists were created once. */
    suspend fun smartSeeded(): Boolean = context.store.data.first()[K.smartSeeded] ?: false
    suspend fun setSmartSeeded() = set { it[K.smartSeeded] = true }

    val speed: Flow<Float> = flow { it[K.speed] ?: 1f }
    suspend fun setSpeed(v: Float) = set { it[K.speed] = v }

    val radioCountry: Flow<String?> = flow { it[K.radioCountry] }
    suspend fun setRadioCountry(v: String) = set { it[K.radioCountry] = v }

}
