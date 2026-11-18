# State machines

Every part of Chud Music that can be "in the middle of something" is listed here, with the type
that holds its state, the function that decides its transitions, and the test that checks it.

## The rule

A piece of state gets **one sealed type (or enum) and one place that changes it** as soon as it
does any of these:

- it survives an `await` (a network call, a file read, a permission dialog);
- more than one screen, thread or process looks at it;
- two of its flags could contradict each other (`loading = true` with an `error`).

Then:

1. **States are a sealed interface or an enum**, each one carrying only the data that exists in
   that state (`Results` has items, `Failed` has a message; neither has both).
2. **Transitions are decided by a pure function** where there is anything to decide (`phaseOf`,
   `ErrorPolicy.decide`, `AudioPermission.next`, `Results.plus`, `Discovery.merge`). Pure means: no
   Android, no clock, no network — so it runs in a unit test in milliseconds.
3. **A late answer may not overwrite a newer question.** Either the request in flight is
   cancelled, or its result is dropped when the state is no longer the one that asked
   (`if (search === asked) search = next`).
4. **UI code reads the state with an exhaustive `when`.** Adding a state then fails to compile
   everywhere it must be handled, instead of failing silently at run time.
5. **The diagram lives in the KDoc of the type.** This file is the index.

What stays a plain `Boolean` on purpose: a dialog that is open or closed, a password shown or
hidden, a row being dragged. Two states, one screen, no `await` — a sealed type there would be
ceremony.

## Index

| Machine | States | Type and transitions | Tested by |
|---|---|---|---|
| Player phase | `DISCONNECTED` `EMPTY` `LOADING` `PLAYING` `PAUSED` `ENDED` `FAILED` | `PlayerConnection.Phase`, `PlayerConnection.phaseOf()` | `playerPhaseIsExactlyOneOfThem` |
| Link to the playback service | `Disconnected` → `Connecting` → `Connected` | `PlayerConnection.Link` (private), `connect()` | on the emulator |
| Playback error | `RETRY_SAME` `SKIP_NEXT` `STOP` `STOP_ALL` | `PlaybackService.ErrorAction`, `ErrorPolicy.decide()` | `aBadTrackIsSkippedButNeverInALoop`, `anExpiredYoutubeAddressIsRetriedOncePerTrack` |
| Library scan | `Idle(lastError)` `Running(source, done, total)` | `ScanState` in `library/Scanner.kt` | `scanStateSaysWhatIsRead` |
| Scan stopped halfway | written by batches; asked-for network scans remembered until they end | `BatchWriter`, `Scanner.mustScan()`, `LocalScanner.isUpToDate()`, `Settings.scanUnfinished` | `LinksTest`: `networkScanRules`, `batchWriterKeepsEverythingInOrderOfBatches`, `phoneTrackUpToDate` |
| Background scan queue | requests waiting, one running (WorkManager unique work `library-scan`) | `ScanRequest.mustQueue()`, `Scanner.request()`, `ScanWorker` | `LinksTest`: `scanRequestsQueueWithoutPilingUp`; on the emulator |
| Pasted or shared link | `Reading` `Ready(slots: Pending/Found/Missing, added)` `Elsewhere(target)` `Failed(reason)` | `LinkImport` in `youtube/LinkImport.kt`, `Ready.resolve()`, run by `LinkImports` | `LinksTest`: `linkImportSettlesEachSlotOnce` |
| YouTube Music search | `Idle` `Loading` `Results(loadingMore)` `Failed` | `YtSearch` in `ui/youtube/YouTubeScreen.kt`, `Results.plus()` | `searchResultsGrowPageByPageWithoutDuplicates` |
| Page load (album, artist, mix, radio list) | `Loading` `Ok(value)` `Err(message)` | `Load` and `Loader` in `ui/components/Loader.kt` | on the emulator |
| App update | `Idle` `Checking` `UpToDate` `Available` `Downloading` `Installing` `NeedsPermission` `Failed` | `Updater.State` in `update/Updater.kt` | `SmallLogicTest` (version order), on the emulator |
| SMB connection | `Closed` `Open` `Down(until)` | `SmbFs.Link` (private) in `net/SmbFs.kt` | `SmbServerTest` (needs a server) |
| Network search | `Idle` `NoNetwork` `Scanning(found, done, total)` `Done(found)` | `Discovery`, `LanDiscovery` in `net/Discovery.kt`, `Discovery.merge()` | `theSweepCoversTheNeighboursOfThePhone`, `aServerSeenTwiceIsListedOnceWithItsName` |
| Share list of a server | `Idle` `Loading` `Listed` `NeedsLogin` `Failed` | `ShareList` in `ui/settings/NetSourceScreen.kt` | `SmbServerTest` |
| Connection test and folder browser | `Idle` `Testing(shown)` `Ok(path, dirs)` `Failed` | `Probe` in `ui/settings/NetSourceScreen.kt` | `theFolderBrowserStaysOnScreenWhileAFolderOpens` |
| Access to the phone's music | `GRANTED` `ASKABLE` `BLOCKED` | `AudioAccess`, `AudioPermission.next()` in `ui/Permissions.kt` | `audioAccessTellsAnAnswerFromASilentRefusal` |
| Lyrics of the current track | `null` (searching) `Synced` `Plain` `Instrumental` `NotFound` `Error` | `Lyrics.Result` in `lyrics/Lyrics.kt` | `LyricsTest` |
| Track menu context | `None` `InPlaylist` `InQueue` `RecentPlayed` | `MenuContext` in `ui/Ui.kt` | — |
| Online cover search | `Idle` `Running(done, total)` `Done(found, searched)` `Failed(message)` | `OnlineCovers.State` in `library/OnlineCovers.kt`, `OnlineCovers.pick()` | `Features033Test`: `onlineCoverIsTheSameAlbumOrNone`; on the emulator |
| Paused network place | skipped by automatic and "Rescan" scans, read when saved | `Scanner.mustScan(paused, chosen)`, `Settings.scanPaused` | `LinksTest`: `networkScanRules` |
| Things handed over by other apps | `PlaylistFile` `SharedText` | `Incoming.Item` in `ui/Incoming.kt` | `SmallLogicTest` (link parsing) |

All tests: `./gradlew testDebugUnitTest` (`StateMachinesTest` unless another class is named).

## Diagrams

### Player phase

Computed from ExoPlayer's flags in one function, so that no screen combines `isPlaying`,
`playbackState` and `playWhenReady` on its own. `State.isPlaying` and `State.loading` are
derived from it.

```
DISCONNECTED ──controller ready──▶ EMPTY ──a list is played──▶ LOADING ──▶ PLAYING ⇄ PAUSED
                                                                  ▲           │
                                               seek / next track ─┘           ├──end of queue──▶ ENDED
                                                                              └──error──▶ FAILED ──(ErrorPolicy)──▶ LOADING | stays
```

### Link to the playback service

```
Disconnected ──connect()──▶ Connecting ──controller built──▶ Connected
      ▲                          │ failed                        │ the service stopped or crashed
      └──────────────────────────┴───────────────────────────────┘
```

Commands sent while not `Connected` are queued and run in order once it is. `connect()` does
nothing unless the link is `Disconnected`: there is never a second controller.

### Playback error

```
PLAYING ──error──▶ RETRY_SAME   an expired YouTube address, once per track, same position
                 ▶ SKIP_NEXT    one message, then the next track after 0.9 s
                 ▶ STOP         one message (a radio, or nothing after)
                 ▶ STOP_ALL     every track of the queue failed in a row: one message, stop
any ──a track really plays──▶ the count of consecutive failures goes back to 0
```

### Library scan

```
Idle ──scanLocal()──▶ Running(phone) ──▶ Idle
Idle ──scanNetwork()──▶ Running(place 1) ──▶ Running(place 2) ──▶ … ──▶ Idle(lastError)
```

One scan at a time (a mutex): a second request waits, it never interleaves. A scan that is
cancelled or throws still ends in `Idle`.

Scans run in the background: `Scanner.request()` queues a `ScanRequest` as WorkManager work
(`ScanWorker`), one after the other. The worker runs as a foreground job with a progress
notification, so the scan goes on with another app in front or the screen off. A request is
dropped when one still waiting already covers it (`ScanRequest.mustQueue()`). When the job starts
while the app is in the background (the 6-hour network scan), Android refuses the foreground
notification and it runs as an ordinary job.

A scan stopped halfway (app killed, phone switched off, Android's time limit) loses at most one
batch: tracks are written 200 (phone) or 25 (network) at a time. WorkManager runs the stopped job
again by itself, and it skips what is already up to date (phone: same file, same date, cover found
or already searched in vain, see `CoverStore.knownWithout()`; network: same size and date). The
counter goes on from what is done ("400/1200"), it does not start again from 0. A network scan
the user asked for is noted in `Settings.scanUnfinished` until it ends, so it goes on at the next
start even away from Wi-Fi.

### Pasted or shared link

```
Reading ──▶ Ready(slots)            a YouTube link: every slot already Found
Reading ──▶ Ready(slots: Pending)   a Spotify link ──each song looked up──▶ Found | Missing
Reading ──▶ Elsewhere(target)       an album or an artist: its own page opens instead
Reading ──▶ Failed(reason)          ──retry──▶ Reading
Ready(done) ──imported into a playlist──▶ Ready(added = n)     (once)
```

`LinkImports` runs them in the app's scope: leaving the screen does not stop one, and opening the
same link again shows it as it stands. A slot is settled once (`resolve` ignores a second answer).

### YouTube Music search

```
Idle ──search()──▶ Loading ──▶ Results | Failed
Results ──more()──▶ Results(loadingMore) ──▶ Results(+ next page, or continuation = null)
any ──search()──▶ Loading      the request in flight is cancelled
any ──clear()──▶ Idle
```

### App update

```
Idle ──check()──▶ Checking ──▶ UpToDate | Available | Failed(release = null)
Available ──downloadAndInstall()──▶ NeedsPermission | Downloading ──▶ Installing ──▶ (app replaced)
Downloading / Installing ──error──▶ Failed(release)        Installing ──user cancels──▶ Available
```

`Available` is saved (`Settings.updAvailable`) and restored at start-up while that version is
still newer than the installed one. A check that fails offline keeps `Available`.

### SMB connection

```
Closed ──share()──▶ Open                  connect, sign in, open the share
Closed ──share()──▶ Down(20 s)            the server does not answer
Down ──share() before `until`──▶ Down     fails at once, no network attempt
Open ──connection dead or suspect──▶ Closed    dropped without a goodbye; the next share() reconnects
```

Two rules learnt the hard way (0.2.0): no socket read time-out (it killed connections that were
merely idle), and never a polite close on a dead connection (each step waits 20 s for an answer
that cannot come).

### Network search and share list

```
Idle ──start()──▶ Scanning(found…, done/total) ──▶ Done(found)
Idle ──start() without Wi-Fi or Ethernet──▶ NoNetwork
Scanning ──stop()──▶ Done(found so far)

ShareList: Idle ──list──▶ Loading(host) ──▶ Listed(shares) | NeedsLogin | Failed(message)
Probe:     Idle ──test / open a folder──▶ Testing(shown) ──▶ Ok(path, folders) | Failed(message)
```

An answer for a server the form has moved away from is dropped.

### Access to the phone's music

```
ASKABLE ──allowed──▶ GRANTED
ASKABLE ──Android refuses at once, without showing its question──▶ BLOCKED
ASKABLE / BLOCKED ──allowed in Android's settings, seen when the app comes back──▶ GRANTED
GRANTED ──removed in Android's settings──▶ ASKABLE
```

One `AudioPermission` in the app graph; `MainActivity` and the Home screen both report to it
and observe it.

## What making them explicit fixed

| Before | Symptom | Now |
|---|---|---|
| `controller == null` meant both "not connected" and "connecting" | two commands at start-up built two controllers and two position loops | `Link.Connecting` |
| `loading`, `error`, `results`, `moreLoading` as separate fields | a cancelled search switched off the spinner of the next one; a late page could be appended to other results | `YtSearch`, one request in flight |
| `hasAudio` remembered by the Home screen | the "Allow" card stayed after the permission was granted at start-up; after two refusals its button did nothing | `AudioAccess`, with a way out of `BLOCKED` |
| the first tab of Radio chosen from a list not loaded yet | Favourites never opened first | "not loaded" is `null`, not an empty list |
| a scan matched to its row by the place's name | two places with the same name both showed "scanning" | `ScanState.Running.sourceId` |
| `Updater.State` only in memory | a found update was forgotten at restart ("up to date" for a day) | `Available` is persisted |
| reason of a skipped track as `"missing"`, `"network"`… | a typo would compile | `SkipReason` |
| SMB `connection`, `session`, `disk`, `downUntil` as four fields | a dead idle connection cost about a minute of silence | `SmbFs.Link`, dropped at once |
