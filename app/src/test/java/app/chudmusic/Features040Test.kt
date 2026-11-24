package app.chudmusic

import app.chudmusic.data.EqState
import app.chudmusic.data.SettingsExchange
import app.chudmusic.data.TrackEntity
import app.chudmusic.library.LibraryQuery
import app.chudmusic.library.NetworkScanner
import app.chudmusic.library.QueueWindow
import app.chudmusic.library.ScanState
import app.chudmusic.library.Scanner
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.playback.PlaybackService.ErrorAction
import app.chudmusic.playback.PlaybackService.ErrorPolicy
import app.chudmusic.playback.PlayerConnection
import app.chudmusic.playback.QueueStore
import app.chudmusic.playlists.PlaylistExchange
import app.chudmusic.playlists.SmartRules
import app.chudmusic.playlists.SmartRules.Field
import app.chudmusic.playlists.SmartRules.Op
import app.chudmusic.playlists.SmartRules.Order
import app.chudmusic.playlists.SmartRules.Rule
import app.chudmusic.youtube.HiddenArtist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** 0.4.0: smart playlists and big libraries on a real SQLite, queue, resume, robustness, exchange. */
class Features040Test {
    private lateinit var db: Connection
    private val now = 1_800_000_000_000L
    private val day = SmartRules.DAY_MS

    /** The app's own table, from the schema Room exported. */
    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val schema = Json.parseToJsonElement(File("schemas/app.chudmusic.data.ChudDatabase/2.json").readText())
        val tracks = schema.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"].toString() == "\"tracks\"" }
        fun sql(o: kotlinx.serialization.json.JsonObject) = Json.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), o["createSql"]!!).replace("\${TABLE_NAME}", "tracks")
        db.createStatement().use { s -> s.execute(sql(tracks)); tracks["indices"]!!.jsonArray.forEach { s.execute(sql(it.jsonObject)) } }
    }

    @After fun close() = db.close()

    private fun add(vararg list: TrackEntity) {
        val cols = listOf(
            "id", "source", "uri", "path", "sourceId", "title", "artist", "album", "albumArtist", "durationMs", "trackNo", "discNo", "year", "genre",
            "available", "inLibrary", "addedAt", "lastPlayedAt", "playCount", "searchKey", "albumKey", "artistKey",
        )
        db.prepareStatement("INSERT INTO tracks (${cols.joinToString()}) VALUES (${cols.joinToString { "?" }})").use { st ->
            for (t in list) {
                listOf(
                    t.id, t.source.name, t.uri, t.path, t.sourceId, t.title, t.artist, t.album, t.albumArtist, t.durationMs, t.trackNo, t.discNo, t.year, t.genre,
                    if (t.available) 1 else 0, if (t.inLibrary) 1 else 0, t.addedAt, t.lastPlayedAt, t.playCount, t.searchKey, t.albumKey, t.artistKey,
                ).forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun t(
        id: String, genre: String? = null, artist: String? = "A", albumArtist: String? = null, year: Int? = null, plays: Int = 0, playedDaysAgo: Int? = null,
        addedDaysAgo: Int = 100, seconds: Int? = 200, title: String = id, inLibrary: Boolean = true, available: Boolean = true, path: String? = "/m/$id.mp3",
    ) = TrackEntity(
        id, Source.LOCAL, "u", path = path, title = title, artist = artist, album = "Al", albumArtist = albumArtist, durationMs = seconds?.times(1000L), year = year, genre = genre,
        available = available, inLibrary = inLibrary, addedAt = now - addedDaysAgo * day, lastPlayedAt = playedDaysAgo?.let { now - it * day }, playCount = plays,
    )

    private fun ids(r: SmartRules, at: Long = now): List<String> {
        val q = r.sql(at)
        db.prepareStatement(q.text).use { st ->
            q.args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeQuery().use { rs -> return buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    private fun one(f: Field, op: Op, v: String = "") = ids(SmartRules(rules = listOf(Rule(f, op, v)))).toSet()

    // ── Smart playlists ─────────────────────────────────────────────────────────────────────

    @Test fun readyMadeLists() {
        add(
            t("never", addedDaysAgo = 5), t("old", plays = 3, playedDaysAgo = 400), t("older", plays = 1, playedDaysAgo = 900),
            t("fresh", plays = 9, playedDaysAgo = 2), t("gone", inLibrary = false), t("missing", available = false),
            // 0.3.3 forgot the date of a track taken out of "Recently played": it was played all the same.
            t("forgotten", plays = 2),
        )
        assertEquals(listOf("never"), ids(SmartRules.neverPlayed()))
        assertEquals("the longest forgotten first", listOf("older", "old"), ids(SmartRules.rediscover()))
        assertEquals(listOf("older"), ids(SmartRules.rediscover(500)))
        assertEquals(listOf("never"), ids(SmartRules.recentlyAdded()))
        assertEquals(listOf("fresh", "old", "forgotten", "older"), ids(SmartRules.mostPlayed()))
    }

    @Test fun theyFollowTheLibraryByThemselves() {
        add(t("a"), t("b"))
        assertEquals(setOf("a", "b"), ids(SmartRules.neverPlayed()).toSet())
        db.createStatement().use { it.execute("UPDATE tracks SET playCount = 1, lastPlayedAt = $now WHERE id = 'a'") }
        assertEquals(listOf("b"), ids(SmartRules.neverPlayed()))
        assertEquals(listOf("a"), ids(SmartRules.rediscover(), now + 200 * day))
    }

    @Test fun everyCriterion() {
        add(
            t("rock", "Rock progressif", "Zed", year = 1975, seconds = 600), t("electro", "Électro", "Équipe", year = 2005, seconds = 185),
            t("none", null, "Guest", albumArtist = "Zed", seconds = null, path = "/m/Live/none.mp3"), t("star", "50*50 [x]?", "zed", plays = 4, playedDaysAgo = 1),
        )
        assertEquals(setOf("electro"), one(Field.GENRE, Op.CONTAINS, "ELECTRO"))
        assertEquals(setOf("rock", "none", "star"), one(Field.GENRE, Op.NOT_CONTAINS, "electro"))
        assertEquals(setOf("star"), one(Field.GENRE, Op.CONTAINS, "0*5"))
        assertEquals(setOf("star"), one(Field.GENRE, Op.IS, "50*50 [x]?"))
        assertEquals(emptySet<String>(), one(Field.GENRE, Op.IS, "rock"))
        assertEquals(setOf("none"), one(Field.GENRE, Op.EMPTY))
        assertEquals("its artist or its album's", setOf("rock", "none", "star"), one(Field.ARTIST, Op.IS, "zed"))
        assertEquals(setOf("electro"), one(Field.ARTIST, Op.IS_NOT, "ZED"))
        assertEquals(setOf("none"), one(Field.FOLDER, Op.CONTAINS, "/live/"))
        assertEquals(setOf("rock"), one(Field.YEAR, Op.LTE, "2000"))
        assertEquals(setOf("none", "star"), one(Field.YEAR, Op.UNKNOWN))
        assertEquals("3:00 to 3:59", setOf("electro", "star"), one(Field.DURATION, Op.EQ, "3"))
        assertEquals(setOf("rock"), one(Field.DURATION, Op.GTE, "9,5"))
        assertEquals(setOf("rock", "electro", "none"), one(Field.PLAYS, Op.EQ, "0"))
        assertEquals(setOf("star"), one(Field.PLAYED, Op.EVER))
        assertEquals(setOf("star"), one(Field.PLAYED, Op.WITHIN, "7"))
        assertEquals(4, one(Field.ADDED, Op.BEFORE, "30").size)
    }

    @Test fun severalRulesExclusionsOrderAndSize() {
        add(t("a", "Rock", year = 1990), t("b", "Rock", year = 2010), t("c", "Jazz", year = 2010), t("d", null, year = 1980))
        val rock = Rule(Field.GENRE, Op.IS, "rock")
        val recent = Rule(Field.YEAR, Op.GTE, "2000")
        assertEquals(listOf("b"), ids(SmartRules(rules = listOf(rock, recent))))
        assertEquals(setOf("a", "b", "c"), ids(SmartRules(all = false, rules = listOf(rock, recent))).toSet())
        assertEquals("a track without a genre is not left out with the rock", setOf("c", "d"), ids(SmartRules(exclude = listOf(rock))).toSet())
        assertEquals(setOf("a", "d"), ids(SmartRules(exclude = listOf(recent))).toSet())
        assertEquals(setOf("b", "c", "d"), ids(SmartRules(excludeIds = listOf("a", "unknown"))).toSet())
        assertEquals(listOf("d", "a"), ids(SmartRules(order = Order.YEAR, descending = false, limit = 2)))
        for (o in Order.entries) for (d in listOf(true, false)) assertEquals("$o $d", 4, ids(SmartRules(order = o, descending = d)).size)
        assertEquals("a rule not filled in counts for nothing", 4, ids(SmartRules(rules = listOf(Rule(Field.GENRE, Op.CONTAINS, " "), Rule(Field.YEAR, Op.GTE, "x")))).size)
    }

    @Test fun randomOrderChangesEachDayOnly() {
        add(*(1..60).map { t("t$it") }.toTypedArray())
        val r = SmartRules(order = Order.RANDOM)
        val today = ids(r, now)
        assertEquals(today, ids(r, now + 1000))
        val tomorrow = ids(r, now + day)
        assertNotEquals(today, tomorrow)
        // Not the same order started elsewhere.
        val shift = tomorrow.indexOf(today[0])
        assertNotEquals(today, tomorrow.drop(shift) + tomorrow.take(shift))
    }

    @Test fun rulesAreThePcFormat() {
        // Written by the PC's presetSpec() and its editor.
        val pc = """{"match":"any","rules":[{"field":"played","op":"before","value":182},{"field":"genre","op":"notContains","value":"rock"},
            {"field":"future","op":"is","value":1},{"field":"year","op":"contains","value":"x"}],"exclude":[{"field":"duration","op":"lte","value":1.5}],
            "excludeIds":["x"],"order":"played","desc":false,"limit":50}"""
        val r = SmartRules.parse(pc)!!
        assertEquals(
            SmartRules(
                all = false, rules = listOf(Rule(Field.PLAYED, Op.BEFORE, "182"), Rule(Field.GENRE, Op.NOT_CONTAINS, "rock")), exclude = listOf(Rule(Field.DURATION, Op.LTE, "1.5")),
                excludeIds = listOf("x"), order = Order.PLAYED, descending = false, limit = 50,
            ),
            r,
        )
        assertEquals(r, SmartRules.parse(r.toJson()))
        assertTrue(r.toJson().contains("\"value\":182}"))
        assertTrue(SmartRules.neverPlayed().toJson().contains("""{"field":"played","op":"never","value":null}"""))
        assertEquals("an empty description is the PC's default", SmartRules(), SmartRules.parse("{}"))
        assertNull(SmartRules.parse("oops"))
        // What is being typed is kept as typed.
        val typing = SmartRules(rules = listOf(Rule(Field.DURATION, Op.GTE, "3."), Rule(Field.GENRE, Op.IS, "")))
        assertEquals(typing, SmartRules.parse(typing.toJson(raw = true), keepIncomplete = true))
    }

    // ── Very big libraries ──────────────────────────────────────────────────────────────────

    @Test fun aHundredThousandTracks() {
        db.autoCommit = false
        val n = 100_000
        (0 until n).chunked(5000).forEach { part ->
            add(*part.map { i ->
                val tr = Track("id$i", Source.LOCAL, "u", "Title ${i * 7919 % n}", "Artist ${i % 4000}", "Album ${i % 9000}", albumArtist = "Artist ${i % 4000}", folder = "/m/${i % 9000}", durationMs = 200_000)
                TrackEntity.from(tr, inLibrary = true, now = now - i * 1000L).copy(playCount = i % 5, lastPlayedAt = if (i % 5 == 0) null else now - i * 60_000L)
            }.toTypedArray())
        }
        db.commit()
        fun ms(what: String, limit: Long, block: () -> Unit) { val s = System.nanoTime(); block(); val d = (System.nanoTime() - s) / 1_000_000; println("$n tracks: $what $d ms"); assertTrue("$what took $d ms", d < limit) }
        fun run(sql: String, args: List<Any> = emptyList()): Int = db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeQuery().use { rs -> var c = 0; while (rs.next()) c++; c }
        }
        // Generous limits (a busy PC): they only catch a query gone quadratic.
        ms("row numbers by title", 3000) { assertEquals(n, run("SELECT rowid FROM tracks WHERE ${LibraryQuery().where} ORDER BY title COLLATE NOCASE")) }
        val q = LibraryQuery("title 4242")
        ms("search", 3000) { assertTrue(run("SELECT rowid FROM tracks WHERE ${q.where} ORDER BY title COLLATE NOCASE", q.args.toList()) > 0) }
        ms("albums", 4000) { assertEquals(36_000, run("SELECT albumKey, COUNT(*) FROM tracks WHERE inLibrary = 1 AND albumKey IS NOT NULL GROUP BY albumKey")) }
        ms("artists", 4000) { assertEquals(4000, run("SELECT artistKey, COUNT(*) FROM tracks WHERE inLibrary = 1 AND artistKey IS NOT NULL GROUP BY artistKey")) }
        ms("never played", 3000) { assertEquals(SmartRules.MAX, ids(SmartRules.neverPlayed()).size) }
        ms("most played", 3000) { assertEquals(100, ids(SmartRules.mostPlayed()).size) }
        ms("random", 3000) { assertEquals(500, ids(SmartRules(order = Order.RANDOM, limit = 500)).size) }
        db.autoCommit = true
    }

    @Test fun searchWordsAreTakenLiterally() {
        add(TrackEntity.from(Track("a", Source.LOCAL, "u", "100% Électro_Café", "X"), inLibrary = true), TrackEntity.from(Track("b", Source.LOCAL, "u", "100 electro cafe", "X"), inLibrary = true))
        fun find(s: String) = LibraryQuery(s).let { q ->
            db.prepareStatement("SELECT id FROM tracks WHERE ${q.where}").use { st ->
                q.args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        assertEquals(setOf("a", "b"), find("ELECTRO cafe"))
        assertEquals(setOf("a"), find("100%"))
        assertEquals(setOf("a"), find("o_c"))
        assertEquals(emptySet<String>(), find("jazz"))
    }

    @Test fun queueOfAVeryLongList() {
        assertEquals(0 until 1500, QueueWindow.around(1500, 700))
        val w = QueueWindow.around(100_000, 50_000)
        assertEquals(QueueWindow.MAX, w.count())
        assertTrue(50_000 in w && w.first < 50_000)
        assertEquals(0, QueueWindow.around(100_000, 0).first)
        assertEquals(99_999, QueueWindow.around(100_000, 99_999).last)
        val d = QueueWindow.drawn(100_000, 77)
        assertEquals(QueueWindow.MAX, d.toSet().size)
        assertTrue(77 in d && d.all { it in 0 until 100_000 })
    }

    // ── Resume ──────────────────────────────────────────────────────────────────────────────

    private fun saved(n: Int, queued: List<Boolean> = List(n) { false }, order: List<Int> = emptyList()) =
        QueueStore.listJson((0 until n).map { Json.encodeToString(Track.serializer(), Track("t$it", Source.LOCAL, "u", "T$it")) }, queued, order)

    private fun place(index: Int, ms: Long, id: String?, shuffle: Boolean = false) =
        Json.encodeToString(QueueStore.Place.serializer(), QueueStore.Place(index, ms, shuffle, 2, trackId = id))

    @Test fun theQueueComesBackWhereItWas() {
        val s = QueueStore.join(saved(4, listOf(false, true, false, false), listOf(2, 0, 3, 1)), place(1, 61_000, "t1", shuffle = true))!!
        assertEquals(listOf("t0", "t1", "t2", "t3"), s.tracks.map { it.id })
        assertEquals(1, s.index)
        assertEquals(61_000L, s.positionMs)
        assertEquals(listOf(false, true, false, false), s.queued)
        assertEquals(listOf(2, 0, 3, 1), s.order)
        assertTrue(s.shuffle)
        assertEquals(2, s.repeat)
    }

    @Test fun aWriteCutShortNeverGivesANonsensePlace() {
        // The list changed, the app was killed before the place was written.
        assertEquals("found by its track", 3, QueueStore.join(saved(5), place(1, 9, "t3"))!!.index)
        val gone = QueueStore.join(saved(3), place(7, 9_000, "t9"))!!
        assertEquals(0, gone.index)
        assertEquals(0L, gone.positionMs)
        assertEquals("order of another list", emptyList<Int>(), QueueStore.join(saved(3, order = listOf(0, 1)), place(0, 0, "t0"))!!.order)
        assertEquals(0, QueueStore.join(saved(3), "{broken")!!.index)
        assertNull(QueueStore.join(null, place(0, 0, null)))
        assertNull(QueueStore.join(saved(0), null))
    }

    // ── Lost connections ────────────────────────────────────────────────────────────────────

    @Test fun aLostConnectionWaitsInsteadOfSkippingEverything() {
        fun decide(lost: Boolean, offline: Boolean = false, played: Boolean = true, retries: Int = 0, failures: Int = 1) =
            ErrorPolicy.decide(false, false, failures, 10, isRadio = false, hasNext = true, lostConnection = lost, offline = offline, hadPlayed = played, retries = retries)
        assertEquals(ErrorAction.WAIT_NETWORK, decide(lost = true, offline = true))
        assertEquals(ErrorAction.RETRY_LATER, decide(lost = true))
        assertEquals("not for ever", ErrorAction.SKIP_NEXT, decide(lost = true, retries = ErrorPolicy.RETRY_MS.size))
        assertEquals("never played: its server is off", ErrorAction.SKIP_NEXT, decide(lost = true, played = false))
        assertEquals("a bad file", ErrorAction.SKIP_NEXT, decide(lost = false))
        assertEquals(ErrorAction.STOP_ALL, decide(lost = false, failures = 10))
    }

    @Test fun waitingIsAPhaseOfItsOwn() {
        fun phase(waiting: Boolean, want: Boolean = true, playing: Boolean = false) = PlayerConnection.phaseOf(3, androidx.media3.common.Player.STATE_IDLE, want, playing, true, waiting)
        assertEquals(PlayerConnection.Phase.WAITING, phase(true))
        assertEquals("paused meanwhile", PlayerConnection.Phase.FAILED, phase(true, want = false))
        assertEquals(PlayerConnection.Phase.PLAYING, phase(true, playing = true))
        assertEquals(PlayerConnection.Phase.FAILED, phase(false))
    }

    @Test fun onlyAnUnreachablePlaceIsTriedAgain() {
        assertTrue(Scanner.keepTrying(NetworkScanner.ERROR_UNREACHABLE))
        assertFalse(Scanner.keepTrying(NetworkScanner.ERROR_AUTH))
        assertFalse(Scanner.keepTrying(NetworkScanner.ERROR_FOLDER))
        assertFalse(Scanner.keepTrying(null))
    }

    @Test fun severalPlacesReadAtOnce() {
        val a = ScanState.Place(1, "NAS", 10, 40)
        val b = ScanState.Place(2, "WebDAV", 5, 10)
        val s = ScanState.Running(listOf(a, b))
        assertEquals("NAS, WebDAV", s.label)
        assertEquals(15, s.done)
        assertEquals(50, s.total)
        assertEquals(b, s.place(2))
        assertNull(s.place(3))
        assertEquals(20, s.with(a.copy(done = 15)).done)
        assertNull("one still counts its files", s.with(a.copy(total = 0)).progress)
    }

    // ── Exchange with the PC ────────────────────────────────────────────────────────────────

    private object Nothing : PlaylistExchange.Resolver {
        override val library = emptyList<Track>()
        override fun fromUnc(host: String, share: String, rest: String, hint: Track): Track? = null
    }

    @Test fun smartPlaylistsAndPicturesGoThroughTheFile() {
        val mine = SmartRules.rediscover(365).copy(excludeIds = listOf("local:1"))
        val text = PlaylistExchange.toJson(
            listOf(PlaylistExchange.Out("Mix", listOf(Track("a", Source.LOCAL, "u", "A", path = "/m/a.mp3")), cover = "data:image/jpeg;base64,AAAA"), PlaylistExchange.Out("Oubliés", emptyList(), smart = mine)),
        ) { it.path }
        val back = PlaylistExchange.parse("x.json", text, Nothing)
        assertEquals("data:image/jpeg;base64,AAAA", back[0].cover)
        assertNull(back[0].smart)
        assertEquals("ids of another device are left out", mine.copy(excludeIds = emptyList()), back[1].smart)
        // A file of the PC without any of this.
        val old = PlaylistExchange.parse("x.json", """{"format":"chud-music-playlists","version":1,"playlists":[{"name":"P","tracks":[]}]}""", Nothing)
        assertNull(old[0].cover)
    }

    @Test fun onlyTheTracksAFileSpeaksOfAreLookedAt() {
        fun tr(title: String, path: String) = Track(path, Source.LOCAL, "u", title, path = path)
        val json = """{"format":"chud-music-playlists","version":1,"playlists":[{"name":"P","tracks":[{"source":"local","title":"Numb","path":"D:\\Music\\LP\\Meteora\\13 Numb.flac"}]}]}"""
        val w = PlaylistExchange.wanted(json)
        assertTrue("same end of path", w(tr("Other", "/sd/Meteora/13 Numb.flac")))
        assertTrue("same title", w(tr("Numb", "/sd/x.mp3")))
        assertFalse(w(tr("Faint", "/sd/Meteora/07 Faint.flac")))
        val m3u = PlaylistExchange.wanted("#EXTM3U\n#EXTINF:10,Linkin Park - Numb\n../x/y.mp3\n")
        assertTrue(m3u(tr("Numb", "/sd/z.mp3")))
        assertFalse(m3u(tr("Faint", "/sd/z.mp3")))
    }

    @Test fun settingsFile() {
        val f = SettingsExchange.File(
            SettingsExchange.FORMAT, equalizer = SettingsExchange.Equalizer(true, -3f, List(10) { it - 5f }), eqPresets = listOf(SettingsExchange.Preset("Mine", 0f, List(10) { 1f })),
            crossfadeSec = 6, replayGain = "album", hiddenArtists = listOf(HiddenArtist("UC1", "X")), radioFavorites = listOf(SettingsExchange.Station("u", "FIP", "https://s")),
        )
        assertEquals(f, SettingsExchange.parse(SettingsExchange.toJson(f)))
        assertEquals(EqState(true, -3f, List(10) { it - 5f }, null), SettingsExchange.equalizer(f))
        assertEquals("ALBUM", SettingsExchange.gainMode(f)!!.name)
        assertTrue(SettingsExchange.stations(f).single().favorite)
        // Nothing of a file can break the player.
        val odd = SettingsExchange.parse("""{"format":"chud-music-settings","equalizer":{"gains":[1,2]},"eqPresets":[{"name":"","gains":[0,0,0,0,0,0,0,0,0,0]},{"name":"Loud","gains":[99,0,0,0,0,0,0,0,0,0]}],"replayGain":"loud","radioFavorites":[{"uuid":"u","name":"x","url":"file:///etc"}],"new":1}""")
        assertNull(SettingsExchange.equalizer(odd))
        assertEquals(12f, SettingsExchange.presets(odd).single().bandsDb[0], 0f)
        assertNull(SettingsExchange.gainMode(odd))
        assertTrue(SettingsExchange.stations(odd).isEmpty())
        for (bad in listOf("", "[]", "{}", """{"format":"chud-music-playlists","playlists":[]}""")) {
            try { SettingsExchange.parse(bad); fail(bad) } catch (_: SettingsExchange.NotSettings) {}
        }
    }
}
