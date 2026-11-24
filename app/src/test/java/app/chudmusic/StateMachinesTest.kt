package app.chudmusic

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.chudmusic.data.NetKind
import app.chudmusic.library.Library
import app.chudmusic.library.ScanState
import app.chudmusic.library.randomRank
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.net.Discovery
import app.chudmusic.net.FoundServer
import app.chudmusic.net.LanDiscovery
import app.chudmusic.playback.ListenTracker
import app.chudmusic.playback.PlaybackService.ErrorAction
import app.chudmusic.playback.PlaybackService.ErrorPolicy
import app.chudmusic.playback.PlaybackService.SkipReason
import app.chudmusic.playback.PlayerConnection
import app.chudmusic.playback.PlayerConnection.Phase
import app.chudmusic.ui.AudioAccess
import app.chudmusic.ui.AudioPermission
import app.chudmusic.ui.settings.Probe
import app.chudmusic.ui.youtube.YtSearch
import app.chudmusic.update.ReleaseNotes
import app.chudmusic.youtube.YtItem
import app.chudmusic.youtube.YtSearchPage
import app.chudmusic.youtube.YtSearchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's state machines are listed in docs/STATE-MACHINES.md. Each one keeps its rules in a
 * pure function, checked here without a phone: `./gradlew testDebugUnitTest`.
 */
class StateMachinesTest {
    private fun track(id: String) = Track(id, Source.YOUTUBE, "", id)
    private fun song(id: String) = YtItem.Song(track(id))

    // ── Player phase ────────────────────────────────────────────────────────────────────────

    @Test fun playerPhaseIsExactlyOneOfThem() {
        fun phase(n: Int, state: Int, playWhenReady: Boolean, playing: Boolean, error: Boolean) = PlayerConnection.phaseOf(n, state, playWhenReady, playing, error)
        assertEquals(Phase.EMPTY, phase(0, Player.STATE_IDLE, false, false, false))
        assertEquals(Phase.LOADING, phase(3, Player.STATE_BUFFERING, true, false, false))
        // Asked to play, not prepared yet (a queue restored at start-up, then "play").
        assertEquals(Phase.LOADING, phase(3, Player.STATE_IDLE, true, false, false))
        assertEquals(Phase.PLAYING, phase(3, Player.STATE_READY, true, true, false))
        assertEquals(Phase.PAUSED, phase(3, Player.STATE_READY, false, false, false))
        // A restored queue, never played: paused, no spinner.
        assertEquals(Phase.PAUSED, phase(3, Player.STATE_IDLE, false, false, false))
        assertEquals(Phase.ENDED, phase(3, Player.STATE_ENDED, true, false, false))
        // An error leaves ExoPlayer idle with playWhenReady still true: it is not "loading".
        assertEquals(Phase.FAILED, phase(3, Player.STATE_IDLE, true, false, true))
    }

    @Test fun playerStateKeepsItsOldQuestions() {
        assertFalse(PlayerConnection.State().connected)
        val playing = PlayerConnection.State(phase = Phase.PLAYING)
        assertTrue(playing.connected && playing.isPlaying && !playing.loading)
        val loading = PlayerConnection.State(phase = Phase.LOADING)
        assertTrue(loading.loading && !loading.isPlaying)
    }

    // ── Playback errors ─────────────────────────────────────────────────────────────────────

    @Test fun anExpiredYoutubeAddressIsRetriedOncePerTrack() {
        assertEquals(ErrorAction.RETRY_SAME, ErrorPolicy.decide(expiredYoutubeUrl = true, alreadyRetried = false, failures = 1, queueSize = 5, isRadio = false, hasNext = true))
        assertEquals(ErrorAction.SKIP_NEXT, ErrorPolicy.decide(expiredYoutubeUrl = true, alreadyRetried = true, failures = 1, queueSize = 5, isRadio = false, hasNext = true))
    }

    @Test fun aBadTrackIsSkippedButNeverInALoop() {
        assertEquals(ErrorAction.SKIP_NEXT, ErrorPolicy.decide(false, false, failures = 2, queueSize = 5, isRadio = false, hasNext = true))
        assertEquals(ErrorAction.STOP, ErrorPolicy.decide(false, false, failures = 1, queueSize = 5, isRadio = false, hasNext = false))
        assertEquals(ErrorAction.STOP, ErrorPolicy.decide(false, false, failures = 1, queueSize = 5, isRadio = true, hasNext = true))
        // Every track failed in a row (repeat-all on a switched-off NAS): stop instead of going round for ever.
        assertEquals(ErrorAction.STOP_ALL, ErrorPolicy.decide(false, false, failures = 5, queueSize = 5, isRadio = false, hasNext = true))
        assertEquals(ErrorAction.STOP_ALL, ErrorPolicy.decide(false, false, failures = 1, queueSize = 1, isRadio = true, hasNext = false))
    }

    @Test fun skipReasonsNameWhatTheUserCanActOn() {
        assertEquals(SkipReason.MISSING, SkipReason.of(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(SkipReason.NETWORK, SkipReason.of(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(SkipReason.FORMAT, SkipReason.of(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(SkipReason.OTHER, SkipReason.of(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test fun aPlayCountsAfterThirtySecondsOrHalfAShortTrack() {
        assertEquals(30_000L, ListenTracker.playThresholdMs(240_000L))
        assertEquals(10_000L, ListenTracker.playThresholdMs(20_000L))
        assertEquals(30_000L, ListenTracker.playThresholdMs(null))
    }

    // ── Library scan ────────────────────────────────────────────────────────────────────────

    @Test fun scanStateSaysWhatIsRead() {
        assertFalse(ScanState.Idle().running)
        val counting = ScanState.Running(sourceId = 4, label = "NAS")
        assertTrue(counting.running)
        assertNull(counting.progress)
        assertEquals("", counting.counter)
        val half = counting.with(ScanState.Place(4, "NAS", 5, 10))
        assertEquals(0.5f, half.progress!!, 0.0001f)
        assertEquals(" · 5/10", half.counter)
    }

    // ── YouTube Music search ────────────────────────────────────────────────────────────────

    @Test fun searchResultsGrowPageByPageWithoutDuplicates() {
        val first = YtSearch.Results("daft punk", YtSearchType.SONG, listOf(song("a"), song("b")), continuation = "t1", loadingMore = true)
        val second = first.plus(YtSearchPage(YtSearchType.SONG, listOf(song("b"), song("c")), "t2"))
        assertEquals(listOf("a", "b", "c"), second.items.map { YtSearch.keyOf(it) })
        assertEquals("t2", second.continuation)
        assertFalse(second.loadingMore)
        // A page that brings nothing, or the same token again, ends the list (no endless loading).
        assertNull(second.plus(YtSearchPage(YtSearchType.SONG, emptyList(), "t3")).continuation)
        assertNull(second.plus(YtSearchPage(YtSearchType.SONG, listOf(song("d")), "t2")).continuation)
    }

    @Test fun everySearchStateKnowsWhatWasAsked() {
        assertNull(YtSearch.Idle.query)
        assertEquals("x", YtSearch.Loading("x", YtSearchType.ALBUM).query)
        assertEquals("x", YtSearch.Failed("x", YtSearchType.ALBUM, "offline").query)
        assertEquals("x", YtSearch.Results("x", YtSearchType.ALBUM, emptyList(), null).query)
    }

    // ── Network place form ──────────────────────────────────────────────────────────────────

    @Test fun theFolderBrowserStaysOnScreenWhileAFolderOpens() {
        val shown = Probe.Ok("2 folders", "Rock", emptyList())
        assertNull(Probe.Idle.browse)
        assertEquals(shown, shown.browse)
        val opening = Probe.Testing(shown)
        assertTrue(opening.busy)
        assertEquals(shown, opening.browse)
        assertNull(Probe.Failed("refused").browse)
        assertNull(Probe.Testing(null).browse)
    }

    // ── Search of the local network ─────────────────────────────────────────────────────────

    @Test fun theSweepCoversTheNeighboursOfThePhone() {
        val hosts = LanDiscovery.candidates("192.168.1.37", 24, gateways = listOf("192.168.1.1"))
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertFalse("192.168.1.37" in hosts)
        assertFalse("192.168.1.0" in hosts || "192.168.1.255" in hosts)
        // A wide network (/16) is not swept whole: only the phone's own /24.
        assertTrue(LanDiscovery.candidates("10.4.7.20", 16).all { it.startsWith("10.4.7.") })
        // A small network (/28) stays inside its 16 addresses.
        assertEquals((33..46).map { "192.168.0.$it" }.filter { it != "192.168.0.40" }, LanDiscovery.candidates("192.168.0.40", 28))
        assertTrue(LanDiscovery.candidates("not an address", 24).isEmpty())
    }

    @Test fun aServerSeenTwiceIsListedOnceWithItsName() {
        var found = Discovery.merge(emptyList(), FoundServer(NetKind.SMB, "192.168.1.20"))
        found = Discovery.merge(found, FoundServer(NetKind.SMB, "192.168.1.3"))
        found = Discovery.merge(found, FoundServer(NetKind.SMB, "192.168.1.20", name = "NAS-SALON"))
        found = Discovery.merge(found, FoundServer(NetKind.WEBDAV, "192.168.1.20", name = "nas", port = 5006, path = "music", https = true))
        assertEquals(listOf("192.168.1.3", "NAS-SALON", "nas"), found.map { it.label })
        assertEquals(listOf(NetKind.SMB, NetKind.SMB, NetKind.WEBDAV), found.map { it.kind })
        assertEquals(found, Discovery.Done(found).servers)
        assertTrue(Discovery.Idle.servers.isEmpty())
    }

    @Test fun netbiosQuestionAndAnswer() {
        val q = LanDiscovery.nodeStatusQuery(0x1234)
        assertEquals(50, q.size)
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(q, 13, 32, Charsets.US_ASCII))
        assertEquals(0x21, q[47].toInt())
        // An answer with two names: the group "WORKGROUP" (flag 0x80), then the machine "NAS-SALON".
        val answer = ByteArray(57 + 2 * 18)
        answer[56] = 2
        fun put(at: Int, name: String, suffix: Int, flags: Int) {
            name.padEnd(15).toByteArray(Charsets.US_ASCII).copyInto(answer, at)
            answer[at + 15] = suffix.toByte(); answer[at + 16] = flags.toByte()
        }
        put(57, "WORKGROUP", 0x00, 0x84)
        put(75, "NAS-SALON", 0x00, 0x04)
        assertEquals("NAS-SALON", LanDiscovery.parseNodeStatus(answer, answer.size))
        assertNull(LanDiscovery.parseNodeStatus(answer, 40))
    }

    // ── Phone music permission ──────────────────────────────────────────────────────────────

    @Test fun audioAccessTellsAnAnswerFromASilentRefusal() {
        assertEquals(AudioAccess.GRANTED, AudioPermission.next(granted = true, canAskAgain = false, answeredAfterMs = 10))
        // The user read the question and said no: it can be asked again.
        assertEquals(AudioAccess.ASKABLE, AudioPermission.next(granted = false, canAskAgain = true, answeredAfterMs = 2500))
        assertEquals(AudioAccess.ASKABLE, AudioPermission.next(granted = false, canAskAgain = false, answeredAfterMs = 2500))
        // Android answered by itself in a blink: only the settings page can change it.
        assertEquals(AudioAccess.BLOCKED, AudioPermission.next(granted = false, canAskAgain = false, answeredAfterMs = 40))
    }

    // ── Home's "Random" shelf ───────────────────────────────────────────────────────────────

    @Test fun randomRankIsThePcFormula() {
        // Values computed with the PC app's own function (HomePage.tsx, rank()).
        assertEquals(3005517492L, randomRank(12345, "abc"))
        assertEquals(3200438711L, randomRank(0x7ffffffe, "local:/storage/emulated/0/Music/é.mp3"))
        assertEquals(2166136261L, randomRank(0, ""))
        assertEquals(1563938851L, randomRank(1, "yt:47YNsf-7Y7c"))
    }

    @Test fun theRandomDrawStaysTheSameUntilItIsRedrawn() {
        val tracks = (1..200).map { track("t$it") }
        val draw = Library.randomPick(tracks, seed = 42, n = 18) { it.id }
        assertEquals(18, draw.size)
        assertEquals(draw, Library.randomPick(tracks.shuffled(), seed = 42, n = 18) { it.id })
        // Tracks added meanwhile only enter the draw if they rank among the first.
        assertTrue(Library.randomPick(tracks + track("new"), 42, 18) { it.id }.filter { it.id != "new" }.let { it == draw || it == draw.dropLast(1) })
        assertTrue(Library.randomPick(tracks, seed = 43, n = 18) { it.id } != draw)
        assertEquals(3, Library.randomPick(tracks.take(3), 42, 18) { it.id }.size)
    }

    // ── Release notes ───────────────────────────────────────────────────────────────────────

    @Test fun releaseNotesComeInTheInterfaceLanguage() {
        val body = "What's new\n- **Random** shelf\n\n<!-- lang:fr\nNouveautés\n- Section **Au hasard**\n-->\n"
        assertEquals("What's new\n- **Random** shelf", ReleaseNotes.select(body, "en"))
        assertEquals("Nouveautés\n- Section **Au hasard**", ReleaseNotes.select(body, "fr"))
        assertEquals("Nouveautés\n- Section **Au hasard**", ReleaseNotes.select(body, "fr-CA"))
        assertEquals("What's new\n- **Random** shelf", ReleaseNotes.select(body, "de"))
        assertEquals("Plain", ReleaseNotes.select("Plain", "fr"))
    }

    @Test fun releaseNotesMarkdownBecomesReadableText() {
        val lines = ReleaseNotes.lines("## Fixes\n- **Updates**: kept after a restart\n* see [the guide](https://x.y) and `code`")
        assertEquals(listOf(ReleaseNotes.Span("Fixes", true)), lines[0])
        assertEquals(listOf(ReleaseNotes.Span("•  ", false), ReleaseNotes.Span("Updates", true), ReleaseNotes.Span(": kept after a restart", false)), lines[1])
        assertEquals(listOf(ReleaseNotes.Span("•  see the guide and code", false)), lines[2])
    }
}
