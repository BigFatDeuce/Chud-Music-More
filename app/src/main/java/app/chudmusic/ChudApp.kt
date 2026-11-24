package app.chudmusic

import android.app.Application
import android.content.Context
import app.chudmusic.audio.EqualizerProcessor
import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.Settings
import app.chudmusic.library.CoverStore
import app.chudmusic.library.Library
import app.chudmusic.library.Scanner
import app.chudmusic.lyrics.Lyrics
import app.chudmusic.net.NetworkSources
import app.chudmusic.playback.PlayerConnection
import app.chudmusic.playlists.Playlists
import app.chudmusic.radio.RadioBrowser
import app.chudmusic.stats.Stats
import app.chudmusic.update.Updater
import app.chudmusic.youtube.InnerTube
import app.chudmusic.youtube.YouTubeStreams
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** Everything the app shares, created once (no DI framework: the graph is small and explicit). */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = ChudDatabase.open(context)
    val settings = Settings(context)
    val covers = CoverStore(context)
    val sources = NetworkSources(db.sources())
    val network = app.chudmusic.net.NetworkWatch(context)
    val library = Library(db, scope)
    val scanner = Scanner(context, db, settings, sources, covers, scope)
    val playlists = Playlists(db)
    val smart = app.chudmusic.playlists.SmartPlaylists(db, settings)
    val normalizer = app.chudmusic.audio.NormalizerProcessor()
    val crossfade = app.chudmusic.audio.CrossfadeProcessor()
    val sleep = app.chudmusic.playback.SleepTimer()
    val onlineCovers = app.chudmusic.library.OnlineCovers(db, covers, scope)
    private val lang = { Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "fr" }
    private val country = { Locale.getDefault().country.takeIf { it.length == 2 } ?: "FR" }
    val youtube = InnerTube(hl = lang, gl = country)
    /** The YouTube Music artists the user hid, read by every YouTube list (search, mixes, pages, links). */
    val hiddenArtists = settings.hiddenArtists.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, app.chudmusic.youtube.HiddenArtists())
    /** YouTube and Spotify links being read (they go on while the user moves around). */
    val links = app.chudmusic.youtube.LinkImports(youtube, playlists, scope) { hiddenArtists.value }
    /** A YouTube Music search asked from elsewhere (a song a link did not find); the YouTube tab runs it. */
    val ytQuery = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lyrics = Lyrics(db.lyrics())
    val embeddedLyrics = app.chudmusic.lyrics.EmbeddedLyrics(context, sources)
    val radio = RadioBrowser(db.radio())
    val stats = Stats(db)
    val updater = Updater(context, settings, scope)
    val equalizer = EqualizerProcessor()
    val player by lazy { PlayerConnection(context, scope) }
    val audio = app.chudmusic.ui.AudioPermission(context)

    /** The seed of Home's "Random" shelf: drawn at launch, kept while the app runs, drawn again on demand. */
    val randomSeed = kotlinx.coroutines.flow.MutableStateFlow(kotlin.random.Random.nextInt())

    /** The skin read once at start, so the first frame already has the right colours. */
    val initialSkin: app.chudmusic.ui.theme.Skin? by lazy { kotlinx.coroutines.runBlocking { settings.skin.first() } }
}

class ChudApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        YouTubeStreams.init(Locale.getDefault().language.ifBlank { "fr" }, Locale.getDefault().country.ifBlank { "FR" })
        graph.network.start()
        graph.scope.launch(Dispatchers.IO) { graph.smart.seed(this@ChudApp) }
        graph.scope.launch { graph.settings.crossfade.collect { graph.crossfade.settings = it } }
        graph.scope.launch { graph.settings.gainMode.collect { graph.normalizer.mode = it } }
        graph.scope.launch(Dispatchers.IO) {
            graph.network.arrivals.collect {
                graph.sources.networkChanged()
                graph.scanner.onNetworkBack()
            }
        }
        graph.scope.launch {
            val eq = graph.settings.eq.first()
            graph.equalizer.set(eq.enabled, eq.preampDb, eq.bandsDb.toFloatArray())
            graph.settings.eq.collect { graph.equalizer.set(it.enabled, it.preampDb, it.bandsDb.toFloatArray()) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { app.chudmusic.net.Http.client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

val Context.graph: AppGraph get() = (applicationContext as ChudApp).graph
