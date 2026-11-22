# User guide

*[Version française](fr/GUIDE.md)*

## First launch

1. Chud Music asks for access to the **music on the phone** and for permission to show a
   **notification** (the playback controls). Both are optional: without the first one, only
   network shares, YouTube Music and the radio are available.
2. The library fills in a few seconds. Covers are the ones embedded in the files; on network
   shares, the album folder's picture is used too.

The five tabs at the bottom: **Home**, **Library**, **YouTube**, **Playlists**, **Radio**. The
mini player above them opens the full player.

## Home

- **Recently played** keeps the last 10 tracks; the oldest leaves by itself. **Clear all** empties it,
  with Undo; your statistics are kept.
- **Random**: 18 tracks of your library drawn when the app starts. The
  draw stays the same while the app runs; the ⟳ button draws others.
- Your playlists, recently added albums, favourite stations.
- A dot on the gear means a new version is waiting in Settings › Updates.
- **Settings › Home page** chooses which of these shelves Home shows. With nothing to show, Home
  shows the Chud Music logo.
- A long press on a track of **Recently played** › *Remove from Recently played*, or on an album
  of **Recently added** › *Remove*, takes it off Home (with Undo). Its plays stay in the
  statistics and the album stays in the library.

## Music on the phone

- **Settings › Phone music › Folders**: untick a folder (ringtones, voice notes…) to leave it out
  of the library. Nothing is deleted.
- **Rescan** reads the phone and the network shares again right away.
- "Remove from library" hides a track; it comes back at the next scan.
- A scan goes on in the background, with another app open or the screen off: a notification
  shows its progress. If Android stops it (app closed, phone switched off), it goes on by itself
  where it stopped, without starting over: what was already read is kept. The same goes for
  network shares.
- **Find missing covers** looks for the covers of the albums whose files hold none, on Deezer then
  iTunes: only the album's artist and title are sent, and only when you tap it. Covers are kept
  in the app's folder, never written into your files. On an album's page, ⋮ › *Find the cover
  online* searches that album again, and *Remove the cover found online* takes a wrong one away.
- If access to the music was refused twice, Android stops asking: Home then offers **Open
  settings**, where you allow "Music and audio".

## Network shares (SMB and WebDAV)

**Settings › Network shares › Add a network share.**

The easy way: **Search the network**. Chud Music looks for the PCs and NAS switched on on your
Wi-Fi (a few seconds) and lists them. Tap a server: its shared folders appear (if it asks for an
account, fill in "User" and "Password", then tap "List shares"). Tap a share: the connection is
tested and you can pick a sub-folder. Then **Save**.

The search only opens a connection to each address of the local network and closes it: nothing
is read or written on the servers. You can also type everything by hand:

| Field | SMB (Windows PC, NAS, Samba) | WebDAV (Nextcloud…) |
|---|---|---|
| Server | name or IP of the PC/NAS, e.g. `192.168.1.20` | host, e.g. `cloud.example.com` |
| Share / path | name of the shared folder, e.g. `Music` | base path, e.g. `remote.php/dav/files/me` |
| Folder | optional: a sub-folder | optional |
| User / password | an account allowed on the share (empty = guest) | the server's account |

Pasting a full address (`\\NAS\Music\Rock`, `smb://nas/Music`, `https://cloud.example.com/dav/`)
in the Server field fills in the rest.

- **Test** checks the connection before saving and opens a folder browser.
- Passwords are encrypted on the phone (Android Keystore).
- Files stay on the server: Chud Music streams them and never modifies or deletes anything. Only
  tags and covers are read during the scan.
- **Automatic scan**: shares are read again every 6 hours, on Wi-Fi only if that option is on.
- **Pause scanning** (open the share in Settings): when you know nothing changed on it, automatic
  scans and **Rescan** skip it. Its tracks stay in the library; saving the share reads it anyway.
- The phone must be on the same network as the server (or reach it through a VPN).
- You can add **as many shares as you like**, SMB and WebDAV: they are scanned side by side, and one
  that does not answer does not hold up the others.
- A connection lost during a scan only pauses it: the scan goes on by itself. Files already read are
  not read again unless they changed.
- When a share does not work, its row in Settings says why and what to do.

## YouTube Music

- Search with suggestions; results as **Songs**, **Other versions** (live, covers, uploads; always played as sound only), **Albums**, **Artists**.
- Album and artist pages (top songs, albums, singles, similar artists).
- **Start mix** plays an endless radio from a track.
- **Paste a link** in the search box, as in the PC app: a YouTube or YouTube Music song, playlist,
  album or artist, or a Spotify song, album, playlist or artist. A Spotify link only names
  songs: each one is looked up on YouTube Music (title, artists, duration), and those with no
  close enough version are listed apart, one tap away from a search. **Save as playlist** keeps
  the result.
- From the YouTube or Spotify app or a browser, **Share › Chud Music**: a song plays at once, a
  playlist or an album opens as above.
- No Google account, no ads. YouTube Music tracks go into playlists like any other.
- **Hide an artist**: ⋮ on one of their songs › *Hide “…”*, or the crossed-out eye on their page.
  They no longer appear in searches, mixes, artist pages or shared YouTube playlists. Your own
  playlists and library are not touched. **Settings › YouTube Music › Hidden artists** opens their list, to restore them.
- **Settings › YouTube Music › Prefer Opus**: best quality; switch it off if an old Bluetooth
  device plays badly (AAC is used instead).

## Playlists

- **+ New playlist**, or ⋮ › "Add to a playlist" from a track, an album…
- An empty playlist offers **Import from a link**: paste a YouTube, YouTube Music or Spotify
  playlist (or album) link and its tracks are found on YouTube Music and added to it.
- In a playlist: the ≡ handle reorders, ⋮ › "Remove from playlist" takes a track out; rename
  and delete are in the playlist's menu (your files are not touched).
- **Change the picture**: a long press on a playlist (or its ⋮), or on the cover once it is open ›
  *Change the image…*. The picture is copied into the app's folder; the covers of the tracks can
  be brought back from the same menu.
- **Delete all playlists** (the red bin at the top of Playlists) asks twice before deleting
  every playlist; it cannot be undone, so use **Export all** first to keep a copy.
- 🔍 searches inside the playlist (title, artist, album).
- ⋮ › **Sort by…** title, artist, album or duration: the new order is saved in the playlist, and
  Undo on the message brings the old one back.
- ⋮ › **Find duplicates…** lists the tracks that are there more than once (the same file, or the
  same song by the same artist with the same length) and, after asking, keeps the best copy of
  each: FLAC rather than MP3, the higher bit rate, a file rather than its YouTube Music twin.
  Live versions and remixes are other songs. Only the playlist changes, never your files.
- Tracks that cannot be found (file gone, share off) stay in the playlist, greyed.
- "Replace YouTube tracks with my files" swaps, where possible, YouTube Music tracks for the same
  track in your library.
- Import and export: see [Exchanging playlists with the PC app](PC-PLAYLISTS.md).

### Smart playlists

**Playlists › Smart playlists** holds playlists that fill themselves by rules, and follow the library
without anything to do. Four come with the app: *Recent additions* (the last 30 days), *Never played*,
*Most played* and *Rediscover* (not played for 6 months). All four can be changed or deleted.

**New smart playlist** starts from one of them or from nothing:

- **Rules** on genre, artist, album, title, folder, year, plays, date added, last play or duration.
  With several, choose whether a track must follow all of them or any one.
- **Exclusions**: what must stay out, with the same rules. On a track, ⋮ › *Leave out of this
  playlist* leaves out that one (with Undo).
- **Order** and **maximum number of tracks**. Without a maximum, a list stops at 10,000.

The number of tracks is shown while you edit. ⋮ also changes the picture, or saves the tracks of the
moment as an ordinary playlist. Rules only apply to your library, not to YouTube Music.

## Radio

Popular stations, genres, countries, worldwide search. The star adds a station to **Favorites**,
which then opens first. The list a station was started from becomes the queue: "next" and
"previous" (lock screen, headset) change station. The title on air is shown when the station sends it; its lyrics are
looked up too.

## Player

- Shuffle, repeat (all / one), playback speed.
- **Queue**: the ≡ handle reorders. Its ⋮ menu selects several tracks to remove, removes those already
  played, empties the queue (with or without the current track) and saves it as a playlist. A list of
  more than 2,000 tracks is played 2,000 at a time.
- **Resume**: the last track, its position and the queue come back after the app is closed or the
  phone restarts, paused.
- **Gapless**: the tracks of an album follow one another without a break, always.
- **Crossfade** (Settings › Playback): off, or 1 to 12 seconds. Not inside an album unless you switch
  that off, nor on the radio, nor when you change track yourself.
- **Normalize volume (ReplayGain)**: off, by track or by album. Uses the files' ReplayGain or R128
  tags; without tags, a track is measured the first time it is played to the end and the result
  serves afterwards. Your files are never changed.
- **Sleep timer** (the moon): after a number of minutes (the sound fades over the last 10 seconds), at
  the end of the track, or at the end of the album.
- **Connection lost**: on a share, YouTube Music or the radio, the player waits (`WAIT` on its
  display) and goes on from where it was when the network is back.
- **Equalizer**: 10 bands, pre-amp, presets and your own; applied to everything that plays.
- **Lyrics**: the file's own lyrics first, when its tags hold some (MP3, FLAC, Ogg, Opus, M4A;
  synced if they carry LRC times); otherwise from LRCLIB, synced when it has them. "Search
  differently" lets you correct the title or the artist (and asks LRCLIB).
- **Track card** (⋮ on any track): album, year, format and bit rate, where the file is, how many
  times you played it, when first and last, and links to learn more about the artist: its
  YouTube Music page, Wikipedia, Grokipedia, Last.fm and Discogs.
- Controls are also in the notification, on the lock screen, on Bluetooth headsets and cars.
  **Settings › Playback** says whether the app's notifications are on; if the controls are
  missing from the lock screen, check in the phone's settings that the lock screen shows
  notifications and media.
- A track that cannot be played (missing file, unsupported format, share unreachable) is skipped
  with one message; the music goes on.

## Settings shared with the PC

**Settings › Sync with Chud Music for PC** exports to a file, and imports, the equalizer and its
presets, the crossfade, the volume normalization, hidden artists and favourite stations. Never a
password nor a server's address. The PC app does not read this file yet; the format is in
[Files exchanged with the PC app](EXCHANGE.md). Playlists have their own export.

## Statistics

The icon at the top of Home: listening time, plays, discoveries, streaks, by hour and weekday,
favourite tracks, artists, albums and stations, over 7 days, 30 days, a year or since the start.
Everything stays on the phone.

## Skins and language

**Settings › Skin**: *Follow the system*, *Chud Nocturne* (dark) or *Platine* (light).
**Settings › Language**: English or French (the phone's language by default).

## Updates

**Settings › Updates**: at most once a day, Chud Music looks at the GitHub releases page. A new
version is downloaded and installed **only when you tap "Update"**; the first time, Android asks
to allow Chud Music to install apps. Your playlists, settings and statistics are kept. The app
closes while Android replaces it: open it again afterwards.
