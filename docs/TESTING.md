# What was tested, what was not, known limits

*[Version française](fr/TESTS.md)*

State on 22 November 2026, version 0.4.0. Everything below was done on the **Android 16
emulator (API 36)**, with the debug build; what was also seen in the release build says so. The
author's test servers were Samba and rclone (WebDAV) running in WSL.

## Checked automatically

`./gradlew testDebugUnitTest`: 103 unit tests, of which 10 only run on request (6 against the
Internet services, 4 against an SMB server). All pass.

- Playlist exchange with the PC app (JSON and M3U8, both ways, path matching).
- Equalizer maths, synced lyrics parsing, version order.
- Audio: crossfade, gapless across track boundaries, resampler, ReplayGain, loudness, equalizer
  continuity, sleep timer (`AudioTest`).
- Smart playlists on a real SQLite with 100,000 tracks (each query under 120 ms on the author's PC),
  queue kept for the next start, playback errors, both exchange files (`Features040Test`).
- The state machines listed in [STATE-MACHINES.md](STATE-MACHINES.md): player phase, playback
  errors, scan, YouTube search paging, network search, permission, random draw, release notes,
  pasted links, scan resumed after a stop.
- Links (`LinksTest`): YouTube and Spotify link parsing (same cases as the PC app), the matcher
  shared with the PC app, Spotify's embed page; online, a 140-track YouTube playlist read past
  its first page and a Spotify album found on YouTube Music.
- SMB against a real server: share list without hidden shares, wrong password, reading after
  40 s of silence, a connection cut by the server.

## Checked by hand on the emulator

**Phone and network**
- Scan of the phone's music (tags, embedded covers; FLAC, MP3, M4A).
- **Search the network**: the sweep finds the SMB server; a server that refuses guests asks for an
  account; with an account, its shares are listed; picking one tests it, opens the folder
  browser, saves, scans and plays. Same in the release build.
- SMB (user and password) and WebDAV (HTTP, read-only): test, scan, playback (FLAC included).
- An SMB track starts within seconds after the connection sat idle for 50 s.
- A share scan of 600 new files stopped at about 240 (app force-stopped): at the next start
  only the remaining 325 were read, ending with every file in the library.
- A first phone scan of 1,200 tracks (100 albums) stopped after 400 (app force-stopped): at the
  next start it went on and ended with all 1,200, in half the time of a full scan (15 November).
- Background scan (16 November): with the app sent to the background and Doze forced, the scan of
  1,200 tracks ran to the end as a foreground job with its notification; with the screen off and
  the process killed at 200, Android restarted it by itself and the scan went on to 1,200 without
  the app being opened. Release build: the scan starts as a foreground job.
- No music file is modified or deleted: the test servers were read-only.

**YouTube Music**
- Search, suggestions, the four filters, further pages while scrolling.
- Playback without ads (Opus, the default; the AAC option was not tried on its own).
- Album and artist pages, "Start mix", a `music.youtube.com` link shared to the app.
- Links: a Spotify album typed in the search (14 of 14 found, in order, saved as a playlist);
  a Spotify playlist imported into an empty playlist (50 of 50 found and added); a YouTube
  playlist shared from another app (140 tracks, played).
- Links **pasted** in the search box (15 November): a bare link, a share sentence around a link
  ("Listen to … : https://…"), and a link pasted after a typed word all open the song at once
  (the last two since 0.3.1).
- Hidden artists (16 November, French and English): "Hide …" from a song's ⋮ menu removes the
  artist's songs, albums and artist entry from the search, songs that only name them as a guest
  in the title too; the eye on the artist page hides and restores; Settings › YouTube Music lists
  the artist and restores it. Mixes and shared playlists are covered by unit tests only.

**Playlists and the PC app**
- Import of a JSON in the PC's format: Windows path → phone file; `\\10.0.2.2\Musique\…` → SMB
  share; `\\NAS-SALON\…` → found by the end of the path; tags only → found by title, artist and
  duration; missing file → kept, greyed; YouTube Music tracks imported.
- M3U8 import; opening an `.m3u8` from a file manager.
- Delete all playlists: both confirmations shown (English and French), cancelling at the second
  keeps them, confirming deletes them and leaves the library untouched.
- JSON and M3U8 exports: compared with the PC app's format and import code.

**Home, radio, player, statistics**
- The Random shelf: same draw until redrawn, plays from the tapped track.
- Settings › Home page: hiding Random and Recently added removes them from Home, showing them
  again brings them back (16 November).
- Radio: MP3 and AAC stations, favourites, recents; opens on Favourites when there are some.
- Equalizer, synced lyrics, shuffle, repeat, queue, queue restored after the app is stopped.
- Statistics: every section; copies of the same track are counted together.

**External review of 24 November** (version 0.4.1; debug and release builds)
- Cover links for other apps: an app without any right (uid 2000) made version 0.4.0 connect to a
  server of its choice and read a cover by its name; with the fix both are refused and nothing
  connects, while the system's media controls still show the covers (files and web images, debug
  and release).
- WebDAV share whose server refuses HEAD: a new file is read with its tags.
- Text shared from another app: `example.com/watch?v=…` played a YouTube song in 0.4.0; no longer.
  A real `youtu.be` link still plays.
- Unit tests (`AuditTest`): the music-link corpus shared with the PC app plus 32 cases answered by
  the PC app's own code, WebDAV against a local server (ranges ignored, wrong offset, no HEAD,
  past the end), signed cover links, size limits.
- Not checked: Android Auto itself, and the playback service called by another installed app
  (the rule is unit-tested only).

**Added in 0.4.0** (22 November; debug build, French, with the main screens also seen in English;
the release build as stated at the end)
- Playlist picture: long press in the list and on the cover of an opened playlist, photo picker,
  back to the automatic picture.
- Recently played: stays at 10, *Clear all* with its question, Undo.
- Gapless: three FLAC files cut from one sound, the output recorded: 1,984,500 samples, not one added
  or missing. Same with the crossfade on (album kept gapless).
- Crossfade of 6 s from 44.1 to 48 kHz: equal power, no click.
- ReplayGain: a file measured at -15.64 LUFS (ffmpeg: -15.6); a loud and a tagged file played at the
  expected level.
- Sleep timer: end of track, end of album (followed by another album), 1 minute.
- Queue: drag, several removed at once, emptied both ways, saved as a playlist.
- Resume: after a forced stop, and after the emulator itself crashed while a track played: same
  track, queue and position (8 s).
- Smart playlists: the four that come with the app, rules, an exclusion, a maximum, a track excluded
  then Undo, saved as an ordinary playlist, deleted; a track played leaves *Never played* by itself.
- Playlist file exported and imported again with a picture and smart playlists; settings file
  exported, changed by hand, imported.
- Two WebDAV shares scanned side by side. Network cut while a track played from one: the player
  waited and went on from the same place. Network cut three times during a scan of 1,000 files: it
  went on to the end with every tag read.
- 102,000 tracks in the library (100,000 of them made-up rows): lists, search, albums, artists,
  shuffle of everything, a smart playlist of 10,000. Java memory 26 to 66 MB, no freeze.
- Ten minutes of playback, 277 tracks from a share, with the screen off, Doze forced, airplane mode
  40 s, Wi-Fi off 20 s: no crash, no freeze, no track skipped for the network.
- **Release build** (French), installed over 0.3.3: database upgraded with tracks, history and queue kept,
  the four smart playlists there, playlist and settings files imported, a smart playlist edited, crossfade
  seen on the timeline, queue and position back after a forced stop.
- Faults found this way and fixed: a long queue froze the app at start-up; a track could be stored
  without its tags when the network dropped; the sleep timer went past a track that could not be
  played, and an error while paused started the music again; the equalizer clicked between two tracks of an album.

**Added in 0.3.3** (18 November, debug build). *This paragraph first claimed more than was done: it
said everything had been seen in French, English and the release build. Seen in English: the playlist
menu, the "no duplicates" message, the track card, the covers and share rows of Settings. Seen in the
release build (French): lyrics of two files, the track card, the cover search. The rest was seen in
French in the debug build only.*
- Playlist: 🔍 keeps only the matching tracks; *Sort by… › Title* reorders and saves, with Undo;
  *Find duplicates…* on an MP3 and a FLAC of the same song added three times removed two entries
  and kept the FLAC (seen on its track card).
- Home: *Remove from Recently played* (long press) and *Remove* on a Recently added album take
  them off. Undo on that message was not tapped.
- *Find missing covers*: Daft Punk's *Discovery* got its cover, the 11 made-up test albums none;
  ⋮ › *Remove the cover found online*, then *Find the cover online* on the album page. The cover was
  still there after a rescan, which did not prove that the files had been read again.
- Lyrics from the file: MP3 with USLT (UTF-16), MP3 written by ffmpeg (TXXX), FLAC with synced
  LRC lyrics; on the phone and on a WebDAV share.
- Track card: album, format and bit rate, source, path, plays; the Wikipedia link opens the
  browser.
- Pause scanning on a WebDAV share: a file added on the server was not read by *Rescan* while
  paused, and was once the pause was taken off.

**Permissions and updates**
- Music permission: refused once (asked again), refused twice (the card offers Android's
  settings), allowed in the settings (seen when coming back, scan starts).
- Updates, with the real GitHub release 0.1.0: found from an older test build, downloaded,
  SHA-256 checked, installed over it, data kept. A version found is still offered after a restart;
  the dot on Home's gear; release notes with bold text and bullets.
- 0.1.0 → 0.2.0, both as published on GitHub: the app found the new version by itself at
  start-up, downloaded it and installed it; the playlist was kept.

## Not checked

- **Android Auto**.
- A real **NAS**, a Windows share with an account, **Nextcloud** over HTTPS.
- Network search on a real Wi-Fi: **server names** (NetBIOS) and servers that **announce
  themselves** (mDNS, which is how WebDAV servers are found) cannot be seen from the emulator.
  Only the address sweep was exercised.
- **Hours** of playback in a row, on a real phone: the long test lasted ten minutes.
- A reboot asked of Android (the emulator crashed instead: a power cut, in effect).
- Two **SMB** shares at the same time, SMB and WebDAV together (two WebDAV shares were).
- A real library of 100,000 **files**: the rows were made up, the files did not exist.
- The shuffle order after a restart, the waiting state on YouTube Music and on the radio (on a WebDAV
  share only).
- The settings file in the **PC app**, which does not read it yet.
- Lyrics in **Ogg/Opus** and **M4A** files (unit tests only), the online cover search on a large
  real library, the Grokipedia, Last.fm and Discogs links (only Wikipedia was opened).
- Importing, in the **PC app** itself, the files exported by the phone.

## Known limits

- **WMA** is not played by Android: the track is skipped with a message.
- The network search looks at the 254 addresses around the phone's own; a server elsewhere on a
  large network must be typed by hand. A VPN on the phone hides the local network.
- Listing a server's shares needs an account on most Windows PCs (guests are refused).
- After an update the app closes (Android replaces it): open it again.
- Importing the same file twice makes two playlists, as on the PC.
- YouTube changes how it works now and then: if YouTube Music stops playing, a new version of
  NewPipe Extractor (hence of Chud Music) is needed.

## Cleaning the author's test environment

The test servers ran in the WSL distribution `FedoraLinux-45`. To remove everything, once they
are stopped:

```bash
wsl -d FedoraLinux-45 -u root -- dnf remove -y samba rclone
```

```bash
wsl -d FedoraLinux-45 -u root -- userdel chudtest
```

```bash
wsl -d FedoraLinux-45 -u root -- rm -rf /srv/chudtest
```

`/srv/chudtest` only holds generated test files, no real music.
